package edith.glasses.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sheen.rokid.audio.AudioUplinkCodec
import com.sheen.rokid.audio.AudioUplinkSession
import com.sheen.rokid.audio.BoundedAudioUplinkSender
import com.sheen.rokid.audio.CaptureConfig
import com.sheen.rokid.audio.UplinkChannelSelector
import com.sheen.rokid.audio.UplinkThroughputMeasurement
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * T17 glasses-side audio capture probe.
 *
 * Answers one question with real hardware: **can an application running on the glasses capture the microphone,
 * and what does the captured PCM actually look like?** That is the first real evidence for the T17 format
 * question, and it tests the architecture that the official docs prescribe (a glasses-side app capturing with
 * `AudioRecord`) rather than the phone-side `CxrSession.startAudioStream()` request that never delivered frames.
 *
 * Deliberate constraints:
 * - **RAM only.** Samples are analysed in memory and never written to disk; the report carries levels, not audio.
 * - **Bounded.** Each variant records for [CAPTURE_MILLIS] and then releases the recorder.
 * - **Conclusive about silence.** A capture that produces digital silence is reported as `noSignal`, and the
 *   report never calls a silent capture a format measurement.
 *
 * The two variants exist because they answer different questions: the documented 8-channel layout says the
 * processed audio is on channels 0/1, while a mono capture answers the simpler "can this app get microphone audio
 * at all". If the 8-channel attempt fails but mono works, the fault is the channel mask, not the microphone.
 */
class ProbeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var reportView: TextView

    private data class Variant(val name: String, val channelMask: Int, val requestedChannels: Int,
        val audioSource: Int)

    private data class VariantResult(
        val name: String, val channelMask: Int, val requestedChannels: Int, val audioSource: Int,
        val state: String, val error: String?,
        val actualSampleRate: Int, val actualChannels: Int,
        val bytesRead: Long, val frames: Long, val millis: Long,
        val rms: List<String>, val peak: List<String>, val windowPeak: List<String>,
        val silentChannels: List<Int>, val speechLikeChannels: List<Int>, val windowSpeechChannels: List<Int>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        val statusView = TextView(this).apply { text = "EDITH glasses audio probe\nidle" }
        reportView = TextView(this).apply { text = "" }
        val button = Button(this).apply { text = "Run capture" }
        root.addView(statusView)
        root.addView(button)
        root.addView(reportView)
        setContentView(root)
        status = statusView
        button.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
            } else {
                runCapture()
            }
        }
        // The probe is driven from the host through the recording UI; logging the entry makes an automated run
        // observable without needing a screenshot.
        Log.i(TAG, "PROBE_READY permission=${checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}")
        // `am start ... --ez auto true` starts the capture without a tap, so an automated run needs only the
        // wearer to speak. The permission must already be granted, otherwise the normal request path runs.
        if (intent?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                runCapture()
            } else {
                Log.w(TAG, "PROBE_AUTO_NO_PERMISSION")
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
            }
        }
        // `--ez throughput true` measures whether the CXR message channel can carry real-time audio. This is the
        // measurement the whole self-built uplink depends on, and it needs no microphone and no audio content.
        // `--ez throughput true [--ei pacedSeconds N]` measures whether the CXR message channel can carry real-time
        // audio. It opens no microphone and involves no audio content.
        if (intent?.getBooleanExtra(EXTRA_THROUGHPUT, false) == true) {
            val pacedSeconds = intent.getIntExtra(EXTRA_PACED_SECONDS, 0)
            runThroughputMeasurement(pacedSeconds)
        }
        // `--ez uplink true --ei uplinkSeconds N` captures real audio and sends it over the CXR uplink, reporting
        // frames rather than audio. This is the end-to-end path: microphone -> selector -> framed -> bridge.
        if (intent?.getBooleanExtra(EXTRA_UPLINK, false) == true) {
            val seconds = intent.getIntExtra(EXTRA_UPLINK_SECONDS, 10)
            runUplink(seconds, intent.getStringExtra(EXTRA_SEND_VARIANT))
        }
        // `--ez audiostart true [--ei audioStartSeconds N] [--ei codecTypeMask N]` drives the glasses-side bridge's
        // own audio-stream entry point. `--ei codecTypes 1,2` narrows the sweep to specific codec values; the
        // default walks the small integers the device's own logs have been seen to use.
        if (intent?.getBooleanExtra(EXTRA_AUDIOSTART, false) == true) {
            runAudioStream(intent.getIntExtra(EXTRA_AUDIOSTART_SECONDS, 8), intent.getStringExtra(EXTRA_CODEC_TYPES))
        }
    }

    /**
     * The CXR session launches this activity as its custom-app target, so it is usually already the top-most instance
     * by the time an operator triggers a mode. Without this, `am start --ez uplink true` only delivers a warning and
     * the run produces no report at all - which looks exactly like a broken uplink.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_UPLINK, false) == true) {
            runUplink(intent.getIntExtra(EXTRA_UPLINK_SECONDS, 10), intent.getStringExtra(EXTRA_SEND_VARIANT))
        }
        if (intent?.getBooleanExtra(EXTRA_THROUGHPUT, false) == true) {
            runThroughputMeasurement(intent.getIntExtra(EXTRA_PACED_SECONDS, 0))
        }
        // Every mode needs its `onNewIntent` branch, not just its `onCreate` branch. The CXR session makes this
        // activity its custom-app target, so it is usually already top-most by the time an operator triggers a run -
        // and a mode wired only into `onCreate` produces a silent no-op that is indistinguishable from a broken
        // feature. That defect was found the hard way on the uplink mode.
        if (intent?.getBooleanExtra(EXTRA_AUDIOSTART, false) == true) {
            runAudioStream(intent.getIntExtra(EXTRA_AUDIOSTART_SECONDS, 8), intent.getStringExtra(EXTRA_CODEC_TYPES))
        }
    }

    /**
     * Captures the microphone and uplinks it. No audio is persisted and none is logged: the report contains only
     * frame counts, byte counts and levels, so the run stays inside the RAM-only constraint.
     */
    private fun runUplink(seconds: Int, sendVariantLabel: String?) {
        status.text = "uplinking audio..."
        // Refused before any capture starts: an unrecognised variant means the run would test something other than
        // what it says, and the default is deliberately the *challenger* overload rather than the already-measured
        // baseline. Resolving this after the microphone opened would waste a capture window on a void run.
        val sendVariant = runCatching { CxrSendVariant.fromLabel(sendVariantLabel ?: CxrSendVariant.DEFAULT.label) }
            .getOrElse { failure ->
                Log.e(TAG, "PROBE_UPLINK_BAD_VARIANT ${failure.message}")
                runOnUiThread { status.text = "bad send variant" }
                return
            }
        // Logged **before** the capture starts, so an orchestrator can treat "the glasses is sending right now" as a
        // captured fact rather than inferring it from a completion line that only appears once the window has closed.
        // Every previous dual-end attempt had to infer it, which is the difference between a negative result and a
        // void run - and it is the reason several runs had to be discarded.
        Log.i(TAG, "PROBE_UPLINK_START seconds=$seconds sendVariant=${sendVariant.label} permission=" +
            (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED))
        Thread {
            val capture = AudioRecordCapture(CaptureConfig())
            val transport = CxrBridgeTransport(sendVariant = sendVariant)
            val attached = transport.attach()
            val selector = UplinkChannelSelector(
                sourceChannelCount = CaptureConfig().sourceChannelCount,
                selectedChannels = PROCESSED_CHANNELS,
            )
            val sender = BoundedAudioUplinkSender(
                selector = selector,
                sessionTag = "glasses-probe",
                sampleRateHz = CaptureConfig().sampleRateHz,
                transport = { frame -> transport.send(frame) },
            )
            val session = AudioUplinkSession(
                capture = capture,
                sender = sender,
                sessionTag = "glasses-probe",
                readBufferBytes = CaptureConfig().readBufferBytes(),
            )
            val outcome = session.run(durationMillis = seconds * 1000L)
            val report = "uplink sendVariant=${sendVariant.label} attached=$attached connected=${transport.isConnected} " +
                "seconds=$seconds started=${outcome.started} blocks=${outcome.blocksRead} " +
                "bytes=${outcome.bytesRead} framesSent=${outcome.framesSent} framesDropped=${outcome.framesDropped} " +
                "channels=${capture.actualChannelCount} sampleRate=${capture.actualSampleRateHz} " +
                "refusal=${capture.refusalReason ?: "none"} lastCode=${transport.lastRefusalCode} " +
                "endReason=${outcome.endReason}"
            Log.i(TAG, "PROBE_UPLINK $report")
            transport.close()
            runOnUiThread { status.text = "uplink done"; reportView.text = report }
        }.start()
    }

    /**
     * Sends synthetic frames over the CXR message channel and reports whether the channel keeps up. No microphone is
     * opened and no audio is involved: the payload is a fixed marker, so the measurement is purely about the transport.
     *
     * [pacedSeconds] of zero measures raw capacity by sending as fast as possible. A positive value instead sends at
     * the real 40 ms cadence for that many seconds, which is the only way to tell "the channel has capacity" from
     * "the channel keeps up in real time" - a burst can post a huge rate without sustaining anything.
     */
    private fun runThroughputMeasurement(pacedSeconds: Int) {
        status.text = if (pacedSeconds > 0) "measuring paced throughput..." else "measuring CXR throughput..."
        Thread {
            val required = UplinkThroughputProbe.requiredBytesPerSecond(channels = SELECTED_CHANNEL_COUNT)
            val payload = UplinkThroughputProbe.payloadBytesPerFrame(
                channels = SELECTED_CHANNEL_COUNT, frameMillis = FRAME_MILLIS,
            )
            val paced = pacedSeconds > 0
            // A paced run attempts one frame per interval for the requested duration; a capacity run attempts exactly
            // one second's worth as fast as it can.
            val frames = if (paced) {
                pacedSeconds * UplinkThroughputProbe.framesPerSecond(FRAME_MILLIS)
            } else {
                UplinkThroughputMeasurement.framesFor(payload, required)
            }
            val transport = CxrBridgeTransport()
            val attached = transport.attach()
            val measurement = UplinkThroughputMeasurement(
                requiredBytesPerSecond = required,
                expectedFrames = frames,
                frameIntervalMillis = if (paced) FRAME_MILLIS.toLong() else 0L,
            )
            // A frame with a recognisable marker; content is irrelevant to the transport question.
            val frame = AudioUplinkCodec.encodePcm(0L, 0L, ByteArray(payload) { 0x5A })
            measurement.start(System.nanoTime())
            var nextDueNanos = System.nanoTime()
            for (index in 1..frames) {
                if (paced) {
                    // Wait until this frame is actually due, so the run reflects real pacing rather than a burst.
                    val wait = nextDueNanos - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    nextDueNanos += FRAME_MILLIS * 1_000_000L
                }
                val sentAt = System.nanoTime()
                val ok = transport.send(frame)
                measurement.record(ok, payload, System.nanoTime() - sentAt)
            }
            measurement.finish(System.nanoTime())
            val report = "throughput mode=${if (paced) "paced${pacedSeconds}s" else "burst"} attached=$attached " +
                "connected=${transport.isConnected} channel=${CxrBridgeTransport.DEFAULT_CHANNEL_KEY} " +
                "lastCode=${transport.lastRefusalCode} ${measurement.summary()}"
            Log.i(TAG, "PROBE_THROUGHPUT $report")
            transport.close()
            runOnUiThread { status.text = "throughput done"; reportView.text = report }
        }.start()
    }

    /**
     * Drives the glasses-side bridge's own audio-stream entry point and reports what the device says.
     *
     * This is the third and last audio route in the locked artifact that this project has never called. The two
     * already refuted on this device are the phone's supported session API (zero frames, six of six attempts) and
     * the self-built `sendMessage` uplink (zero delivered while the sender reported success on 499 frames).
     *
     * What this can and cannot show, stated before the run so the result cannot be over-read afterwards:
     *
     * - **It can show** whether the device accepts and starts a stream driven by our own application, which is the
     *   one thing neither previous route established.
     * - **It cannot show that audio arrived anywhere.** Only the phone can say that, by watching its own CXR audio
     *   callback. A `STREAM_STARTED` verdict here with nothing on the phone is a meaningful and expected outcome.
     *
     * The parameters are swept one variable at a time because their meanings are undocumented: only `codecType`
     * changes between attempts; the stream type, the record parameters and the observation window are held fixed.
     */
    private fun runAudioStream(seconds: Int, codecTypesExtra: String?) {
        status.text = "driving bridge audio stream..."
        val codecTypes = parseCodecTypes(codecTypesExtra)
        // Logged before the first attempt so an observer on the phone can prove this end was driving while it
        // listened - the distinction that turned several earlier attempts into void runs.
        Log.i(TAG, "PROBE_AUDIOSTREAM_START seconds=$seconds codecTypes=$codecTypes")
        Thread {
            val probe = GlassesAudioStreamProbe()
            // Byte-rate is measured per attempt rather than assumed from the request, because a "running" stream
            // that yields nothing must not be reported as a stream.
            val attached = probe.attach()
            val observeMillis = seconds.coerceAtLeast(1) * 1000L
            val lines = mutableListOf<String>()
            try {
                val sets = GlassesAudioStreamProbe.parameterSets(
                    base = AudioStreamParams(
                        codecType = codecTypes.first(), secondary = SECONDARY_PARAM,
                        streamType = STREAM_TYPE_BASE, denoiseMode = DENOISE_MODE,
                        rokidDtlnAEC = true, rokidBF = true,
                    ),
                    codecTypes = codecTypes,
                )
                for (set in sets) {
                    val startedAt = System.currentTimeMillis()
                    val attempt = probe.attempt(set, observeMillis)
                    val line = GlassesAudioStreamProbe.summary(
                        attempt = attempt, params = set, attached = attached,
                        connected = probe.deviceConnected, subscribed = probe.subscriptionResult,
                        seconds = seconds.toLong(), millis = System.currentTimeMillis() - startedAt,
                    )
                    lines += line
                    Log.i(TAG, "PROBE_AUDIOSTREAM $line")
                }
                val verdicts = lines.joinToString(";") { it.substringAfter("verdict=").substringBefore(" ") }
                Log.i(TAG, "PROBE_AUDIOSTREAM_DONE attempts=${lines.size} verdicts=$verdicts")
            } catch (error: Throwable) {
                Log.e(TAG, "PROBE_AUDIOSTREAM_FAILED ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                probe.close()
            }
            runOnUiThread { status.text = "audio stream done"; reportView.text = lines.joinToString("\n") }
        }.start()
    }

    /**
     * The codec values to sweep. The domain is undocumented, so the default is the small set the device's own logs
     * have been observed to carry rather than a single guess: a one-value run that fails would leave open whether
     * the value or the route was wrong.
     */
    private fun parseCodecTypes(raw: String?): List<Int> {
        val parsed = raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        return parsed.ifEmpty { DEFAULT_CODEC_TYPES }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) runCapture()
            else status.text = "RECORD_AUDIO denied"
        }
    }

    private fun runCapture() {
        status.text = "capturing..."
        Thread {
            val results = VARIANTS.map { capture(it) }
            val report = buildReport(results)
            writeReport(report)
            runOnUiThread {
                status.text = "done"
                reportView.text = report
            }
            Log.i(TAG, "PROBE_REPORT_BEGIN")
            report.lineSequence().forEach { Log.i(TAG, "PROBE_REPORT $it") }
            Log.i(TAG, "PROBE_REPORT_END")
        }.start()
    }

    private fun capture(variant: Variant): VariantResult {
        // 0x6000FC is the documented device channel mask; AudioRecord reports the real channel count for it.
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, variant.channelMask, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Room for a whole capture at the requested width, but never smaller than the platform minimum.
        val wholeCapture = (SAMPLE_RATE.toLong() * 2 * variant.requestedChannels * CAPTURE_MILLIS / 1000L / 8L).toInt()
        val bufferBytes = maxOf(minimum, wholeCapture)
        var recorder: AudioRecord? = null
        return try {
            recorder = AudioRecord.Builder()
                .setAudioSource(variant.audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(variant.channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return VariantResult(variant.name, variant.channelMask, variant.requestedChannels,
                    variant.audioSource, stateName(recorder.state), "STATE_NOT_INITIALIZED", 0, 0, 0, 0, 0,
                    emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
            val channelCount = recorder.channelCount
            recorder.startRecording()
            val single = ShortArray(READ_SAMPLES)
            // Interleaved 16-bit samples accumulated in RAM only. A growable stream avoids both a second copy and
            // any chance of writing audio to storage.
            val collected = ByteArrayOutputStream()
            // Speech is looked for inside the capture rather than assumed to fill it: the first run showed the
            // wearer's timing is the dominant variable, so a fixed window could easily miss the only utterance.
            var speechHeard = false
            var elapsedMillis = 0L
            val startedAt = System.currentTimeMillis()
            while (elapsedMillis < CAPTURE_MILLIS) {
                val read: Int = recorder.read(single, 0, single.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                for (index in 0 until read) {
                    // Little-endian, matching the platform's own byte order for 16-bit PCM.
                    collected.write(single[index].toInt() and 0xFF)
                    collected.write((single[index].toInt() shr 8) and 0xFF)
                }
                elapsedMillis = System.currentTimeMillis() - startedAt
                // Stop as soon as speech is present and the minimum duration has been covered, so the capture
                // ends while the utterance is still happening instead of outlasting it.
                if (!speechHeard && elapsedMillis >= MIN_CAPTURE_MILLIS) {
                    val so_far = PcmChannelAnalysis.of(collected.toByteArray(), maxOf(channelCount, 1))
                    if (so_far.windowSpeechChannels().isNotEmpty()) speechHeard = true
                }
                if (speechHeard && elapsedMillis >= MIN_CAPTURE_MILLIS) break
            }
            Log.i(TAG, "PROBE_CAPTURE variant=${variant.name} millis=$elapsedMillis speechHeard=$speechHeard")
            val merged = collected.toByteArray()
            val bytes = merged.size.toLong()
            // Raw audio leaves scope here; only the derived level statistics survive.
            val analysis = PcmChannelAnalysis.of(merged, maxOf(channelCount, 1))
            VariantResult(
                name = variant.name, channelMask = variant.channelMask,
                requestedChannels = variant.requestedChannels, audioSource = variant.audioSource,
                state = stateName(recorder.state), error = null,
                actualSampleRate = recorder.sampleRate, actualChannels = channelCount,
                bytesRead = bytes, frames = analysis.frameCount, millis = elapsedMillis,
                rms = analysis.rmsText(), peak = analysis.peakText(), windowPeak = analysis.windowPeakText(),
                silentChannels = analysis.silentChannels, speechLikeChannels = analysis.speechLikeChannels(),
                windowSpeechChannels = analysis.windowSpeechChannels(),
            )
        } catch (error: Throwable) {
            VariantResult(variant.name, variant.channelMask, variant.requestedChannels,
                variant.audioSource, "EXCEPTION", "${error.javaClass.simpleName}: ${error.message}", 0, 0, 0, 0, 0,
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        } finally {
            // Releasing on every exit path is what keeps a failed run from holding the microphone.
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    private fun stateName(state: Int) = when (state) {
        AudioRecord.STATE_INITIALIZED -> "STATE_INITIALIZED"
        AudioRecord.STATE_UNINITIALIZED -> "STATE_UNINITIALIZED"
        else -> "STATE_$state"
    }

    private fun buildReport(results: List<VariantResult>): String {
        val json = StringBuilder()
        json.append("{\n")
        json.append("  \"schemaVersion\": 1,\n")
        json.append("  \"probe\": \"glasses-audio-probe\",\n")
        json.append("  \"device\": {")
        json.append("\"model\": \"${jsonEscape(android.os.Build.MODEL)}\", ")
        json.append("\"android\": \"${jsonEscape(android.os.Build.VERSION.RELEASE)}\", ")
        json.append("\"sdk\": ${android.os.Build.VERSION.SDK_INT}, ")
        json.append("\"fingerprint\": \"${jsonEscape(android.os.Build.FINGERPRINT)}\"},\n")
        json.append("  \"requested\": {\"sampleRateHz\": $SAMPLE_RATE, \"encoding\": \"ENCODING_PCM_16BIT\", ")
        json.append("\"audioSource\": \"MIC\", \"captureMillis\": $CAPTURE_MILLIS},\n")
        json.append("  \"capturedAt\": \"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())}\",\n")
        json.append("  \"rawAudioPersisted\": false,\n")
        // A session-level verdict, because "did the microphone hear a person at all" is the one question a reader
        // needs answered before looking at any per-variant number.
        val speechOverAllVariants = results.filter { it.windowSpeechChannels.isNotEmpty() }.map { it.name }
        json.append("  \"speechHeardInAnyVariant\": ${speechOverAllVariants.isNotEmpty()},\n")
        json.append("  \"variantsWithSpeech\": [${speechOverAllVariants.joinToString(",") { "\"${jsonEscape(it)}\"" }}],\n")
        json.append("  \"sessionVerdict\": \"${if (speechOverAllVariants.isEmpty()) "noSpeechDetectedInAnyVariant" else "speechDetected"}\",\n")
        json.append("  \"variants\": [\n")
        results.forEachIndexed { index, result ->
            json.append("    {\n")
            json.append("      \"name\": \"${jsonEscape(result.name)}\",\n")
            json.append("      \"channelMask\": ${result.channelMask},\n")
            json.append("      \"requestedChannels\": ${result.requestedChannels},\n")
            json.append("      \"audioSource\": ${result.audioSource},\n")
            json.append("      \"state\": \"${result.state}\",\n")
            json.append("      \"error\": ${result.error?.let { "\"${jsonEscape(it)}\"" } ?: "null"},\n")
            json.append("      \"actualSampleRateHz\": ${result.actualSampleRate},\n")
            json.append("      \"actualChannelCount\": ${result.actualChannels},\n")
            json.append("      \"bytesRead\": ${result.bytesRead},\n")
            json.append("      \"frames\": ${result.frames},\n")
            json.append("      \"captureMillis\": ${result.millis},\n")
            json.append("      \"rmsPermille\": [${result.rms.joinToString(",")}],\n")
            json.append("      \"peakPermille\": [${result.peak.joinToString(",")}],\n")
            json.append("      \"windowPeakPermille\": [${result.windowPeak.joinToString(",")}],\n")
            json.append("      \"silentChannels\": [${result.silentChannels.joinToString(",")}],\n")
            json.append("      \"speechLikeChannels\": [${result.speechLikeChannels.joinToString(",")}],\n")
            json.append("      \"windowSpeechChannels\": [${result.windowSpeechChannels.joinToString(",")}],\n")
            json.append("      \"verdict\": \"${verdict(result)}\"\n")
            json.append("    }${if (index == results.lastIndex) "" else ","}\n")
        }
        json.append("  ]\n")
        json.append("}\n")
        return json.toString()
    }

    /**
     * The report must never call a silent capture a measurement. A capture with no frames is "noFrames"; one with
     * frames but no level is "noSignal"; only a capture with real level is a usable observation, and even then the
     * verdict stays descriptive because the format still needs corroboration.
     *
     * `speechHeardInWindow` is the strongest available evidence that the microphone captured a person, and is kept
     * distinct from the whole-capture mean so a short utterance is not averaged into silence.
     */
    private fun verdict(result: VariantResult): String = when {
        result.error != null -> "error"
        result.state != "STATE_INITIALIZED" -> "notInitialized"
        result.frames == 0L -> "noFrames"
        result.silentChannels.size == result.actualChannels -> "noSignal"
        result.windowSpeechChannels.isNotEmpty() -> "speechHeardInWindow"
        result.speechLikeChannels.isNotEmpty() -> "signalPresent"
        else -> "signalPresentBelowSpeechThreshold"
    }

    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun writeReport(report: String) {
        // Only the small JSON report is persisted, never PCM. getExternalFilesDir needs no extra permission.
        val directory = getExternalFilesDir(null) ?: File(Environment.getExternalStorageDirectory(), "Android/data/$packageName/files")
        runCatching {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(directory, "audio-probe-$stamp.json")
            file.writeText(report)
            Log.i(TAG, "PROBE_REPORT_FILE ${file.absolutePath}")
        }.onFailure { Log.e(TAG, "PROBE_REPORT_WRITE_FAILED ${it.message}") }
    }

    companion object {
        private const val TAG = "EdithAudioProbe"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_THROUGHPUT = "throughput"
        const val EXTRA_UPLINK = "uplink"
        const val EXTRA_UPLINK_SECONDS = "uplinkSeconds"
        /**
         * Which `sendMessage` overload the uplink uses: `capsOnly` (the already-measured baseline) or
         * `capsWithDataArray` (the challenger, and the default). Unrecognised values are refused before capture.
         */
        const val EXTRA_SEND_VARIANT = "sendVariant"
        /** When positive, the throughput run is paced at the real frame cadence for this many seconds. */
        const val EXTRA_PACED_SECONDS = "pacedSeconds"
        /** Drives the glasses-side bridge's own `startAudioStream` and reports what the device says. */
        const val EXTRA_AUDIOSTART = "audiostart"
        /** Seconds each audio-stream attempt is observed for before it is stopped. */
        const val EXTRA_AUDIOSTART_SECONDS = "audiostartSeconds"
        /** Comma-separated codec values to sweep; the default is [DEFAULT_CODEC_TYPES]. */
        const val EXTRA_CODEC_TYPES = "codecTypes"

        /**
         * The codec values the default sweep walks. Chosen from what the device's own logs have been observed to
         * carry (`codec 1`, `codec 2`) plus the neighbouring value, because the mapping is undocumented and a
         * single-value run could not distinguish "wrong codec" from "wrong route".
         */
        private val DEFAULT_CODEC_TYPES = listOf(1, 2, 0)
        /** Held fixed across the sweep: the value the device's own record path was observed to use. */
        private const val SECONDARY_PARAM = 1
        private const val STREAM_TYPE_BASE = "edith-audiostream"
        /** Denoise mode 1: the middle setting, with AEC and beam-forming enabled alongside it. */
        private const val DENOISE_MODE = 1
        /** The two processed channels: enough for speech, a quarter of the 8-channel bandwidth. */
        private val PROCESSED_CHANNELS = listOf(0, 1)
        /** The two processed channels: enough for speech, a quarter of the 8-channel bandwidth. */
        private const val SELECTED_CHANNEL_COUNT = 2
        private const val FRAME_MILLIS = 40
        private const val RECORD_AUDIO_REQUEST = 4101
        private const val SAMPLE_RATE = 16_000
        /** Upper bound on one capture; the loop exits early once speech is heard. */
        private const val CAPTURE_MILLIS = 15_000L
        /** Never stop sooner than this, so a cough or a door slam cannot end the capture by itself. */
        private const val MIN_CAPTURE_MILLIS = 3_000L
        private const val READ_SAMPLES = 2048

        /** Documented 8-channel device mask: 0/1 processed, 2-5 raw microphones, 6/7 hardware echo. */
        private const val MASK_EIGHT_CHANNEL = 0x6000FC

        /**
         * One entry per (requested mask, source) pair, because the mask changes two things at once and the product
         * needs to know which one matters:
         *
         * - `monoMask` asks for one channel but keeps `AudioSource.MIC`, so if the device still returns several
         *   channels the request is being ignored and only the mask selects the layout.
         * - `unprocessedOneChannel` asks for a single channel with `UNPROCESSED`, which is the closest available
         *   stand-in for "what does this application get if it simply requests a microphone".
         *
         * The first run of this probe showed the whole-capture mean cannot tell "nobody spoke" from "deaf
         * microphone", so every variant now reports a windowed maximum as well.
         */
        private val VARIANTS = listOf(
            Variant("eightChannel", MASK_EIGHT_CHANNEL, 8, MediaRecorder.AudioSource.MIC),
            Variant("monoMask", AudioFormat.CHANNEL_IN_MONO, 1, MediaRecorder.AudioSource.MIC),
            Variant("unprocessedOneChannel", AudioFormat.CHANNEL_IN_MONO, 1, MediaRecorder.AudioSource.UNPROCESSED),
        )
    }
}
