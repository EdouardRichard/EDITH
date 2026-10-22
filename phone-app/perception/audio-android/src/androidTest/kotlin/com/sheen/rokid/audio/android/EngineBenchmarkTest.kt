package com.sheen.rokid.audio.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.testsupport.BenchmarkAccumulator
import com.sheen.rokid.testsupport.BenchmarkPlan
import com.sheen.rokid.testsupport.DeviceIdentity
import com.sheen.rokid.testsupport.EngineMetrics
import com.sheen.rokid.testsupport.EngineMetricsSchema
import com.sheen.rokid.testsupport.MetricsCandidate
import com.sheen.rokid.testsupport.RamPcmFixture
import com.sheen.rokid.testsupport.ResourceMeasurement
import com.sheen.rokid.testsupport.ResourceSample
import com.sheen.rokid.testsupport.SemanticTrialRecorder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Disabled by default. Opt-in requires previously authorized local PCM, imported public models and a T17
 * controlled `verified-format.json`.
 *
 * The combined run emits one `EngineMetrics` record — the same versioned shape `tools/p2/report-engine.ps1`
 * validates — instead of the earlier ad-hoc scaffold: explicit half-open ten-second windows including the
 * final drain window, every scheduled speaker job counted, faults and drops attributed to the window that
 * owned them, and minute-cadence resource rows with the frozen host identity. The record is written on the
 * success path and on the failure path, so a failed candidate still leaves usable evidence.
 *
 * T16 gaps intentionally still open here: the D33-authorized path is a bounded phone-RAM fixture supplied
 * by the fixed-speech session (the on-disk route below is the narrower, separately authorized variant), and
 * the 30 frozen semantic trials plus speaker calibration rows need a human judging locally. This test does
 * not claim engine selection; it produces the measurement record only.
 */
class EngineBenchmarkTest {
    @Test fun nativePartialResetAndRepeatedRelease() {
        fixture().use { fixture ->
            val candidate = fixture.open()
            try {
                val ingress = fixture.ingress()
                val framer = PcmFramer(fixture.verified)
                var partialBeforeFinish = false
                replayOnce(fixture, ingress) {
                    val chunk = requireNotNull(ingress.poll())
                    try {
                        framer.accept(chunk).forEach { frame ->
                            try {
                                candidate.vad.accept(frame)
                                val hypotheses = candidate.asr.accept(frame)
                                partialBeforeFinish = partialBeforeFinish || hypotheses.isNotEmpty()
                            } finally { frame.samples.fill(0f) }
                        }
                    } finally { chunk.bytes.fill(0) }
                }
                assertTrue("Authorized speech fixture must produce a partial before finish", partialBeforeFinish)
                candidate.asr.finish()
                candidate.asr.reset()
                candidate.vad.reset()
                val silence = framer.accept(com.sheen.rokid.audio.PcmChunk(fixture.epoch, 0, 0, ByteArray(640), "reset"))
                silence.forEach { frame ->
                    try { assertTrue(candidate.asr.accept(frame).isEmpty()) } finally { frame.samples.fill(0f) }
                }
                assertTrue("Reset must discard the old final", candidate.asr.finish().text.isBlank())
                ingress.close()
            } finally { candidate.close(); candidate.close() }
        }
    }

    @Test fun combinedRealtimeReplayEmitsCompatibleMetrics() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit combined benchmark opt-in required", args.getString("runCombinedBenchmark") == "true")
        fixture().use { fixture ->
            val seconds = args.getString("benchmarkSeconds")?.toInt() ?: 600
            require(seconds >= EngineMetricsSchema.MIN_MEASURED_SECONDS.toInt()) {
                "A selection-grade run needs at least ${EngineMetricsSchema.MIN_MEASURED_SECONDS.toInt()} measured seconds"
            }
            require(seconds <= 3600) { "Unbounded measurement is not supported" }
            fixture.open().use { candidate -> measure(fixture, candidate, seconds) }
        }
    }

    private fun measure(fixture: Fixture, candidate: SherpaEngineCandidate, seconds: Int) {
        // All models stay loaded across warmup and measurement; speaker inference keeps its own worker.
        val plan = BenchmarkPlan(measuredSeconds = seconds.toLong())
        val accumulator = plan.newAccumulator()
        val identity = deviceIdentity(fixture.context)
        val args = InstrumentationRegistry.getArguments()
        val expectedDeviceId = args.getString("expectedDeviceId")
        val expectedModel = args.getString("expectedDeviceModel")
        val expectedOs = args.getString("expectedOsVersion")
        if (expectedDeviceId != null && expectedModel != null && expectedOs != null) {
            // A misattributed result is worse than no result: refuse to measure on the wrong host.
            check(identity.matches(expectedDeviceId, expectedModel, expectedOs)) {
                "HOST_IDENTITY_MISMATCH: expected $expectedModel/$expectedOs/$expectedDeviceId, got ${identity.toJson()}"
            }
        }

        val ingress = fixture.ingress()
        val framer = PcmFramer(fixture.verified)
        val running = AtomicBoolean(true)
        val pool = Executors.newSingleThreadExecutor()
        val speakerPool = Executors.newSingleThreadExecutor()
        // Speaker extraction runs asynchronously, so the owning window and its start time are captured at
        // submission and the elapsed work is credited back to that same window when the job finishes.
        val speakerSlot = java.util.concurrent.atomic.AtomicReference<Pair<Int, Long>?>(null)
        var speakerJob: java.util.concurrent.Future<*>? = null
        var failure: Throwable? = null
        var nativeExecuted = false
        var nativeLifecyclePassed = false
        var interrupted = false
        var observedSeconds = 0.0
        val started = SystemClock.elapsedRealtime()
        val measurementStart = started + WARMUP_MILLIS
        try {
            val decode = pool.submit {
                while (running.get() || ingress.queuedBytes > 0) {
                    val chunk = ingress.poll()
                    if (chunk == null) { SystemClock.sleep(1); continue }
                    try {
                        framer.accept(chunk).forEach { frame ->
                            val owner = plan.windowIndexAt(SystemClock.elapsedRealtime() - measurementStart)
                            val start = SystemClock.elapsedRealtimeNanos()
                            try {
                                val decision = candidate.vad.accept(frame)
                                candidate.asr.accept(frame)
                                if (decision.endpoint) { candidate.asr.finish(); candidate.asr.reset() }
                            } finally {
                                // Cost and count are handed over together, so no window boundary can split
                                // a frame's work from its frame count.
                                accumulator.record(ownerIndex = owner, frames = 1,
                                    workNanos = SystemClock.elapsedRealtimeNanos() - start, speakerJobs = 0)
                                frame.samples.fill(0f)
                            }
                        }
                    } finally { chunk.bytes.fill(0) }
                }
            }
            var nextWindowBoundary = measurementStart
            var nextResourceBoundary = measurementStart + plan.firstResourceOffsetMillis()
            var offset = 0
            while (true) {
                if (decode.isDone) decode.get()
                val now = SystemClock.elapsedRealtime()
                if (now - started >= WARMUP_MILLIS + plan.measuredMillis) break
                if (now < nextWindowBoundary - 1) { SystemClock.sleep(1); continue }
                val bytes = fixture.pcm.copyOfRange(offset, offset + 640)
                try { check(ingress.offer(fixture.epoch, bytes) == PcmOffer.ACCEPTED) { "Replay queue overflow" } }
                finally { bytes.fill(0) }
                offset = (offset + 640) % fixture.pcm.size
                if (offset == 0) {
                    // Every wrap schedules exactly one speaker job; a busy worker counts as a dropped job
                    // rather than silently omitting the work the reporter's RTF convention assumes.
                    val owner = plan.windowIndexAt(now - measurementStart)
                    if (speakerJob == null || speakerJob!!.isDone) {
                        speakerJob?.get()
                        val startedJob = SystemClock.elapsedRealtimeNanos()
                        speakerSlot.set(owner to startedJob)
                        speakerJob = speakerPool.submit {
                            try {
                                speakerExtract(fixture, candidate)
                            } finally {
                                val slot = speakerSlot.get()
                                if (slot != null && slot.first == owner) {
                                    accumulator.recordWork(owner, SystemClock.elapsedRealtimeNanos() - startedJob)
                                }
                            }
                        }
                        accumulator.record(owner, frames = 0, workNanos = 0, speakerJobs = 1)
                    } else {
                        accumulator.recordDrop(owner)
                    }
                }
                if (now >= nextWindowBoundary) {
                    accumulator.recordQueueDepth(plan.windowIndexAt(now - measurementStart), ingress.queuedBytes)
                    nextWindowBoundary += WINDOW_MILLIS
                }
                if (now >= nextResourceBoundary) {
                    accumulator.recordResource(plan.resourceOwnerAt(now - measurementStart), resourceSample(fixture.context))
                    nextResourceBoundary += RESOURCE_MILLIS
                }
            }
            nativeExecuted = true
            nativeLifecyclePassed = true
            val elapsedSeconds = (SystemClock.elapsedRealtime() - measurementStart) / 1000.0
            observedSeconds = plan.reconciledMeasuredSeconds(
                minOf(plan.measuredSeconds.toDouble(), elapsedSeconds))
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            running.set(false)
            try {
                speakerJob?.get(30, TimeUnit.SECONDS)
            } catch (speakerFailure: Throwable) {
                interrupted = true
                if (failure == null) failure = speakerFailure
            }
            pool.shutdown(); speakerPool.shutdown()
            // A shutdown timeout does not prove an executing JNI call was cancelled; record it instead.
            if (!pool.awaitTermination(30, TimeUnit.SECONDS) || !speakerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                interrupted = true
            }
            ingress.close()
            writeMetrics(fixture, plan, accumulator, identity, observedSeconds, nativeExecuted,
                nativeLifecyclePassed, interrupted, failure)
        }
        failure?.let { throw it }
    }

    /** Exactly one speaker extraction per fixture wrap, over the bounded RAM samples only. */
    private fun speakerExtract(fixture: Fixture, candidate: SherpaEngineCandidate) {
        val segment = fixture.pcm.copyOf()
        try {
            val count = segment.size / 2L
            val window = SpeakerWindow(AudioSegment(EventEnvelope(fixture.epoch.sessionId,
                fixture.epoch.generation, FIXTURE_CONSENT, 0, count * 62_500),
                fixture.verified.format, segment), SampleRange("speaker", 0, count), false)
            candidate.embedding.extract(window)?.fill(0f)
        } finally { segment.fill(0) }
    }

    private fun writeMetrics(fixture: Fixture, plan: BenchmarkPlan, accumulator: BenchmarkAccumulator,
        identity: DeviceIdentity, observedSeconds: Double, nativeExecuted: Boolean, nativeLifecyclePassed: Boolean,
        interrupted: Boolean, failure: Throwable?) {
        // Locally judged trials, when the operator has supplied both the frozen speech annotations and the
        // judgments. Without them the trial list stays empty rather than being invented, so the run reports
        // INSUFFICIENT_SEMANTIC_TRIALS and is honestly not selectable.
        val recorder = semanticRecorder(fixture)
        val trials = recorder?.toTrials() ?: emptyList()
        // One value drives both the reported duration and the last window's audio, because the reporter
        // asserts sum(audioSeconds) == measuredSeconds exactly.
        val effectiveMeasuredSeconds =
            if (observedSeconds > 0) observedSeconds else plan.measuredSeconds.toDouble()
        val record = EngineMetrics(
            candidate = MetricsCandidate(
                deviceId = identity.deviceId,
                runtimeSha256 = RUNTIME_SHA256,
                provider = fixture.manifest.provider,
                threads = fixture.manifest.threadCount,
                endpointMillis = fixture.manifest.endpointMillis,
                frameMillis = fixture.manifest.frameMillis,
                sampleRateHz = fixture.manifest.sampleRateHz,
                modelHashes = fixture.manifest.artifacts.associate { it.role to it.sha256 },
            ),
            nativeExecuted = nativeExecuted,
            nativeLifecyclePassed = nativeLifecyclePassed,
            interrupted = interrupted || failure != null,
            warmupSeconds = WARMUP_MILLIS / 1000.0,
            // Reconciled to the audio that actually arrived; the reporter requires the sums to agree.
            measuredSeconds = effectiveMeasuredSeconds,
            windows = accumulator.windows(measuredSeconds = effectiveMeasuredSeconds),
            semanticTrials = trials,
            resources = accumulator.resourceRows(),
            identity = identity,
        )
        val target = File(fixture.context.noBackupFilesDir, METRICS_NAME)
        target.writeText(record.toJson())
        // Annotation rows are ids, sample positions and concept counts only - never the recognized text.
        recorder?.let { built ->
            File(fixture.context.noBackupFilesDir, ANNOTATIONS_NAME).writeText(
                built.annotations().joinToString(",", "[", "]") { annotation ->
                    "{\"id\":\"${annotation.id}\",\"speechStartSample\":${annotation.speechStartSample}," +
                        "\"speechEndSample\":${annotation.speechEndSample}," +
                        "\"firstPartialSample\":${annotation.firstPartialSample ?: "null"}," +
                        "\"finalPresent\":${annotation.finalPresent}," +
                        "\"conceptsPresent\":${annotation.conceptsPresent}," +
                        "\"conceptsRequired\":${annotation.conceptsRequired}}"
                })
        }
        if (failure != null) {
            File(fixture.context.noBackupFilesDir, "$METRICS_NAME.failure.txt").writeText(
                "failures=${record.failures()} reason=${failure.message}")
        }
    }

    /**
     * Builds the semantic recorder from the operator's frozen annotation and judgment files.
     *
     * Two separate inputs are required on purpose: the annotations fix each turn's speech interval, and the
     * judgments carry the concepts a human actually recognized. Supplying only one of them is not a
     * judgment, so a missing or malformed pair yields no trials rather than a guess.
     */
    private fun semanticRecorder(fixture: Fixture): SemanticTrialRecorder? {
        val root = File(fixture.context.noBackupFilesDir, "authorized-test")
        val annotations = File(root, ANNOTATIONS_INPUT)
        val judgments = File(root, JUDGMENTS_INPUT)
        if (!annotations.isFile || !judgments.isFile) return null
        return runCatching {
            val entries = Json.parseToJsonElement(annotations.readText()).jsonArray.map { element ->
                val item = element.jsonObject
                SemanticTrialRecorder.Entry(
                    id = item.getValue("id").jsonPrimitive.content,
                    speechStartSample = item.getValue("speechStartSample").jsonPrimitive.long,
                    speechEndSample = item.getValue("speechEndSample").jsonPrimitive.long,
                    requiredConcepts = item.getValue("requiredConcepts").jsonArray
                        .map { it.jsonPrimitive.content },
                )
            }
            val recorder = SemanticTrialRecorder.of(entries)
            val observed = Json.parseToJsonElement(judgments.readText()).jsonArray.associate { element ->
                val item = element.jsonObject
                item.getValue("id").jsonPrimitive.content to item.getValue("concepts").jsonArray
                    .map { it.jsonPrimitive.content }
            }
            entries.forEach { entry ->
                val concepts = observed[entry.id] ?: emptyList()
                // No partial sample is claimed here: associating a partial with a specific turn needs the
                // per-turn capture session (T16 gap #1), so the timing proof stays explicitly unproven.
                recorder.observe(id = entry.id, partialSample = null, finalText = concepts.joinToString(" "))
                recorder.judge(entry.id, concepts)
            }
            recorder
        }.getOrNull()
    }

    private fun deviceIdentity(context: Context): DeviceIdentity {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = packageInfo.versionCode.toLong()
        // A stable, collision-resistant host id. ANDROID_ID needs no privileged permission (unlike
        // Build.getSerial) and is far less collision-prone than a 32-bit fingerprint hash.
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val deviceId = androidId?.takeIf { it.isNotBlank() && it != LEGACY_ANDROID_ID }
            ?: "${Build.MODEL}-${Build.VERSION.SDK_INT}"
        return DeviceIdentity(
            deviceId = deviceId.replace(Regex("[^A-Za-z0-9_-]"), "-").take(64),
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            appVersionName = packageInfo.versionName ?: "unknown",
            appVersionCode = versionCode,
        )
    }

    private fun resourceSample(context: Context): ResourceSample {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val percent = runCatching {
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        // Real RSS from /proc/self/statm (resident pages * page size); unreadable stays unavailable, not null.
        val rssKiB = runCatching {
            val residentPages = File(PROC_STATM).readText().trim().split(" ")[1].toLong()
            residentPages * PAGE_KIB
        }.getOrDefault(-1L).let { if (it in 0..Int.MAX_VALUE.toLong()) it.toInt() else -1 }
        if (temperature < 0) return ResourceSample.batteryUnavailable(memory.totalPss, rssKiB)
        return ResourceSample(memory.totalPss, rssKiB, temperature, percent)
    }

    private fun replayOnce(fixture: Fixture, ingress: PcmIngress, consume: () -> Unit) {
        var target = SystemClock.elapsedRealtime()
        for (offset in fixture.pcm.indices step 640) {
            val bytes = fixture.pcm.copyOfRange(offset, offset + 640)
            try { assertEquals(PcmOffer.ACCEPTED, ingress.offer(fixture.epoch, bytes)); consume() }
            finally { bytes.fill(0) }
            target += 20
            val wait = target - SystemClock.elapsedRealtime()
            if (wait > 0) SystemClock.sleep(wait)
        }
    }

    private fun fixture(): Fixture {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit native test opt-in required", args.getString("runNativeCandidateTests") == "true")
        val name = requireNotNull(args.getString("authorizedPcmFile"))
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.pcm")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "authorized-test").canonicalFile
        // The verified format must come from T17's controlled verification artifact. A caller-supplied
        // consent string must never promote a pairing to VerifiedPcm.
        val evidence = File(root, VERIFIED_FORMAT_EVIDENCE)
        assumeTrue("T17 controlled verified-format evidence is required before decoding real PCM", evidence.isFile)
        val source = File(root, name)
        require(source.canonicalFile.parentFile == root && source.isFile && source.length() in 32_000..MAX_FIXTURE_BYTES)
        val raw = source.readBytes()
        val verified = readVerifiedFormat(evidence)
        // The runner consumes the bounded RAM fixture rather than the raw byte array, so the sample buffer
        // has a single owner that is zeroed on every exit path.
        val bounded = requireNotNull(RamPcmFixture.fromVerified(verified, raw)) {
            "Fixture must be PCM16 LE mono 16 kHz within ${RamPcmFixture.MAX_SECONDS} s"
        }
        try {
            require(bounded.byteCount % 640 == 0) { "Fixture must be in complete 20 ms frames" }
            val manifest = context.assets.open("engine-manifest.json").use(EngineManifestLoader::load)
            val threadCount = args.getString("candidateThreads")?.toInt() ?: manifest.threadCount
            val endpoint = args.getString("candidateEndpointMillis")?.toInt() ?: manifest.endpointMillis
            val configured = manifest.copy(threadCount = threadCount, endpointMillis = endpoint)
            configured.validate()
            val bundle = requireNotNull(EngineModelStore(context.noBackupFilesDir.toPath().resolve("engine-models")).recover(configured))
            return Fixture(context, bounded, configured, bundle, verified)
        } catch (failure: Throwable) { bounded.close(); throw failure }
    }

    private fun readVerifiedFormat(evidence: File): VerifiedPcm {
        val data = Json.parseToJsonElement(evidence.readText()).jsonObject
        val format = requireNotNull(data["format"]).jsonObject
        val encoding = PcmEncoding.valueOf(requireNotNull(data["encoding"]).jsonPrimitive.content)
        val evidenceId = requireNotNull(data["evidenceId"]).jsonPrimitive.content
        require(data["verified"]?.jsonPrimitive?.booleanOrNull == true) { "T17 evidence is not a verified result" }
        return VerifiedPcm(AudioFormat.Pcm(requireNotNull(format["sampleRateHz"]).jsonPrimitive.int,
            requireNotNull(format["channels"]).jsonPrimitive.int,
            requireNotNull(format["bitsPerSample"]).jsonPrimitive.int), encoding, evidenceId)
    }

    private class Fixture(val context: Context, val samples: RamPcmFixture, val manifest: EngineManifest,
        val bundle: ValidatedModelBundle, val verified: VerifiedPcm) : AutoCloseable {
        val epoch = InputEpoch(SessionId("authorized-native-test"), Generation(1), 0)
        /** Replay view of the owned RAM samples in whole 20 ms offers. */
        val pcm: ByteArray get() = samples.window(0, samples.durationMillis.toInt().coerceAtLeast(20))
        fun open() = SherpaEngineFactory.createCandidate(bundle, verified)
        fun ingress() = PcmIngress(object : MonotonicClock {
            override fun nowNanos() = SystemClock.elapsedRealtimeNanos()
        }).also { it.open(epoch, verified) }
        override fun close() = samples.close()
    }

    private companion object {
        const val VERIFIED_FORMAT_EVIDENCE = "verified-format.json"
        const val MAX_FIXTURE_BYTES = 16000 * 2 * 12
        /** The only runtime hash `EngineManifestLoader` accepts; a candidate cannot invent its own. */
        const val RUNTIME_SHA256 = "633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96"
        const val FIXTURE_CONSENT = "authorized-benchmark"
        const val METRICS_NAME = "engine-metrics.json"
        const val ANNOTATIONS_NAME = "semantic-annotations.json"
        const val ANNOTATIONS_INPUT = "semantic-annotations.json"
        const val JUDGMENTS_INPUT = "semantic-judgments.json"
        const val WARMUP_MILLIS = 60_000L
        const val WINDOW_MILLIS = 10_000L
        const val RESOURCE_MILLIS = 60_000L
        const val PROC_STATM = "/proc/self/statm"
        /** The well-known buggy ANDROID_ID that must never be treated as this device's identity. */
        const val LEGACY_ANDROID_ID = "9774d56d682e549c"
        const val PAGE_KIB = 4L
    }
}
