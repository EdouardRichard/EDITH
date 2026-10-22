package com.sheen.rokid.testsupport

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineMetricsTest {
    @Test fun completeRunHasNoFailures() {
        assertEquals(emptyList(), sample().failures())
        assertTrue(sample().complete())
    }

    @Test fun shortDurationIsRejected() {
        assertContains(sample(warmupSeconds = 30.0).failures(), EngineMetricsSchema.INSUFFICIENT_DURATION)
        assertContains(sample(measuredSeconds = 300.0, windows = windows(30)).failures(),
            EngineMetricsSchema.INSUFFICIENT_DURATION)
        assertContains(sample(measuredSeconds = 590.0, windows = windows(59)).failures(),
            EngineMetricsSchema.INSUFFICIENT_DURATION)
    }

    @Test fun missingSemanticTrialsAreRejected() {
        assertContains(sample(trials = trials(29)).failures(), EngineMetricsSchema.INSUFFICIENT_SEMANTIC_TRIALS)
    }

    @Test fun incompleteNativeRunIsRejected() {
        assertContains(sample(nativeExecuted = false).failures(), EngineMetricsSchema.INCOMPLETE_COMBINED_NATIVE_RUN)
        assertContains(sample(nativeLifecyclePassed = false).failures(), EngineMetricsSchema.INCOMPLETE_COMBINED_NATIVE_RUN)
        assertContains(sample(interrupted = true).failures(), EngineMetricsSchema.INCOMPLETE_COMBINED_NATIVE_RUN)
        assertContains(sample(windows = windows(60, speakerCalls = 0)).failures(),
            EngineMetricsSchema.INCOMPLETE_COMBINED_NATIVE_RUN)
    }

    @Test fun lostWorkIsRejected() {
        assertContains(sample(windows = windows(60, faults = 1)).failures(), EngineMetricsSchema.LOST_OR_FAULTED_WORK)
        assertContains(sample(windows = windows(60, droppedFrames = 1)).failures(), EngineMetricsSchema.LOST_OR_FAULTED_WORK)
    }

    @Test fun slowOrBackloggedOrInaccurateRunIsRejected() {
        assertContains(sample(windows = windows(60, rtf = 0.8)).failures(), EngineMetricsSchema.RTF_P95_FAILED)
        assertContains(sample(windows = windows(60, queueSweep = true)).failures(), EngineMetricsSchema.BACKLOG_FAILED)
        assertContains(sample(trials = trials(30, correct = 26)).failures(), EngineMetricsSchema.SEMANTIC_ACCURACY_FAILED)
    }

    @Test fun nonZeroFinalQueueIsBacklog() {
        assertContains(sample(windows = windows(60, finalQueue = 1)).failures(), EngineMetricsSchema.BACKLOG_FAILED)
    }

    @Test fun structuralViolationsAreInvalid() {
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(candidate = sampleCandidate(threads = 3)).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(candidate = sampleCandidate(provider = "npu")).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(candidate = sampleCandidate(runtimeSha256 = "abc")).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(windows = windows(60).toMutableList().also { it[0] = it[0].copy(index = 9) }).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(windows = windows(60).toMutableList().also { it[3] = it[3].copy(audioSeconds = 9.0) }).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(trials = listOf(trials(30)[0], trials(30)[0])).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(trials = listOf(SemanticTrial("P2Q031", true))).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(measuredSeconds = 1234.0).failures())
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(windows = windows(60, negativeQueue = true)).failures())
    }

    @Test fun jsonRoundTripsRequiredShape() {
        val json = sample().toJson()
        for (key in listOf("schemaVersion", "corpusId", "candidate", "windows", "semanticTrials",
                "nativeExecuted", "nativeLifecyclePassed", "interrupted", "warmupSeconds", "measuredSeconds")) {
            assertContains(json, "\"$key\"")
        }
        assertContains(json, "\"corpusId\":\"p2-fixed-zh-30-v1\"")
        assertContains(json, "\"deviceId\":\"k40-alpha\"")
        assertFalse(json.contains("NaN"))
    }

    @Test fun resourceAndIdentityEvidenceRideAlongWithoutChangingSelection() {
        val measured = sample(
            resources = listOf(ResourceMeasurement(6, 1000, 2000, 310, 90, "battery-temperature")),
            identity = sampleIdentity())
        val json = measured.toJson()
        assertContains(json, "\"resources\"")
        assertContains(json, "\"pssKiB\":1000")
        assertContains(json, "\"battery-temperature\"")
        assertContains(json, "\"identity\"")
        assertContains(json, "\"M2012K11AC\"")
        assertContains(json, "\"sdkInt\":33")
        // The selection verdict still comes from the frozen window/trial rules alone.
        assertEquals(emptyList(), measured.failures())
        assertTrue(measured.complete())
    }

    @Test fun resourceEvidenceStaysOutOfTheSelectionDecision() {
        // A missing sensor reading is reported honestly; it must not silently pass as zero, and it must
        // not by itself fail a run whose frozen RTF/backlog/semantic rules are satisfied.
        val measured = sample(resources = listOf(ResourceMeasurement(6, 700, 800, -1, -1, "battery-temperature-unavailable")))
        assertContains(measured.toJson(), "\"batteryTemperatureTenthsC\":-1")
        assertEquals(emptyList(), measured.failures())
    }

    @Test fun aReconciledShortRunIsMeasuredNotInvalid() {
        // The planner may shorten the final window when the replay lost wall-clock time. The validator must
        // accept that shape: rejecting it as INVALID_METRICS would contradict BenchmarkPlan.windows(...).
        val plan = BenchmarkPlan(measuredSeconds = 600)
        val accumulator = plan.newAccumulator()
        (1..plan.windowCount).forEach { index ->
            accumulator.record(ownerIndex = index, frames = 500, workNanos = 1_000_000_000L, speakerJobs = 1)
        }
        val windows = accumulator.windows(measuredSeconds = 599.4)
        val measured = sample(measuredSeconds = 599.4, windows = windows)
        assertFalse(measured.failures().contains(EngineMetricsSchema.INVALID),
            "a shortened final window is a valid shape")
        assertEquals(listOf(EngineMetricsSchema.INSUFFICIENT_DURATION), measured.failures())
        assertContains(measured.toJson(), "\"audioSeconds\":9.4")
    }

    @Test fun anUnprocessedMeasuredWindowIsLostWork() {
        // A window that claimed ten seconds of audio but processed nothing must not pass as a fast window.
        val plan = BenchmarkPlan(measuredSeconds = 20)
        val accumulator = plan.newAccumulator()
        accumulator.record(ownerIndex = 1, frames = 500, workNanos = 1_000_000_000L, speakerJobs = 1)
        val measured = sample(measuredSeconds = 20.0,
            windows = accumulator.windows(measuredSeconds = 20.0))
        assertContains(measured.failures(), EngineMetricsSchema.LOST_OR_FAULTED_WORK)
    }

    @Test fun anOversizedOrNonPositiveWindowIsStillInvalid() {
        val oversized = windows(60).toMutableList()
            .also { it[0] = it[0].copy(audioSeconds = 10.5) }
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(windows = oversized).failures())
        val zero = windows(60).toMutableList()
            .also { it[0] = it[0].copy(audioSeconds = 0.0) }
        assertEquals(listOf(EngineMetricsSchema.INVALID), sample(windows = zero).failures())
    }

    @Test fun theGeneratedDocumentShapeSurvivesAShortenedRun() {
        // Guards tools/p2/check-metrics-shape.ps1's assumptions without needing PowerShell: the document a
        // 599 s run produces must keep the window sum equal to measuredSeconds and stay structurally valid.
        val document = MetricsDocumentFixture.document(seconds = 599.0, windowCount = 60,
            trialCount = 30, withResources = true)
        assertEquals(599.0, document.windows.sumOf { it.audioSeconds }, 1e-9)
        assertEquals(60, document.windows.size)
        assertEquals(9.0, document.windows.last().audioSeconds, 1e-9)
        assertFalse(document.failures().contains(EngineMetricsSchema.INVALID),
            "the generated document must be a valid shape")
        assertTrue(document.windows.dropLast(1).all { it.audioSeconds == 10.0 })
        assertTrue(document.windows.none { it.droppedFrames > 0 }, "the fixture records processed frames")
        assertEquals(10, document.resources.size)
    }

    @Test fun theGeneratedDocumentRefusesAWindowCountThatContradictsItsDuration() {
        // 599 s needs 60 windows: the last one carries the remaining 9 s.
        assertFailsWith<IllegalArgumentException> {
            MetricsDocumentFixture.document(seconds = 599.0, windowCount = 59, trialCount = 30,
                withResources = false)
        }
        assertEquals(60, MetricsDocumentFixture.document(seconds = 599.0, windowCount = 60,
            trialCount = 30, withResources = false).windows.size)
    }

    private fun sample(
        candidate: MetricsCandidate = sampleCandidate(),
        nativeExecuted: Boolean = true,
        nativeLifecyclePassed: Boolean = true,
        interrupted: Boolean = false,
        warmupSeconds: Double = 60.0,
        measuredSeconds: Double = 600.0,
        windows: List<MetricsWindow> = windows(60),
        trials: List<SemanticTrial> = trials(30),
        resources: List<ResourceMeasurement> = emptyList(),
        identity: DeviceIdentity? = null,
    ) = EngineMetrics(candidate, nativeExecuted, nativeLifecyclePassed, interrupted,
        warmupSeconds, measuredSeconds, windows, trials, resources, identity)

    private fun sampleIdentity() = DeviceIdentity(deviceId = "k40-alpha", model = "M2012K11AC",
        osVersion = "13", sdkInt = 33, abi = "arm64-v8a", appVersionName = "1.0", appVersionCode = 1)

    private fun sampleCandidate(
        deviceId: String = "k40-alpha",
        runtimeSha256: String = "a".repeat(64),
        provider: String = "cpu",
        threads: Int = 2,
        endpointMillis: Int = 350,
        frameMillis: Int = 20,
        sampleRateHz: Int = 16000,
        modelHashes: Map<String, String> = mapOf("asr" to "b".repeat(64), "vad" to "c".repeat(64)),
    ) = MetricsCandidate(deviceId, runtimeSha256, provider, threads, endpointMillis, frameMillis, sampleRateHz, modelHashes)

    private fun windows(
        count: Int,
        rtf: Double = 0.5,
        speakerCalls: Int = 1,
        faults: Int = 0,
        droppedFrames: Int = 0,
        queueSweep: Boolean = false,
        finalQueue: Int = 0,
        negativeQueue: Boolean = false,
    ) = (1..count).map { index ->
        MetricsWindow(index, 10.0, rtf, when {
            negativeQueue -> -1
            queueSweep -> index
            index == count -> finalQueue
            else -> 0
        }, speakerCalls, faults, droppedFrames)
    }

    private fun trials(count: Int, correct: Int = count) = (1..count).map { index ->
        SemanticTrial("P2Q%03d".format(index), index <= correct)
    }
}
