package com.sheen.rokid.testsupport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T16 measurement gaps #3/#4/#6: the runner must slice admitted audio on explicit half-open window
 * boundaries (keeping the final drain window), count every speaker job it scheduled, attribute faults to
 * the window that owned them, and sample resource rows on their own cadence. None of this exists in the
 * scaffold runner yet, so this run is the behavioral RED.
 */
class BenchmarkPlanTest {
    @Test fun everyWindowIsTenSecondsAndTheFinalWindowIsKept() {
        val plan = BenchmarkPlan(measuredSeconds = 600)
        assertEquals(60, plan.windowCount)
        // Window indices are 1-based, so window 1 is [0, 10000) and window 60 is [590000, 600000).
        assertEquals(0L, plan.windowStartMillis(1))
        assertEquals(10_000L, plan.windowStartMillis(2))
        assertEquals(590_000L, plan.windowStartMillis(60))
        assertEquals(600_000L, plan.windowEndMillis(60))
        assertEquals(1, plan.windowIndexAt(0))
        assertEquals(60, plan.windowIndexAt(599_999))
    }

    @Test fun warmupAudioIsNeverCountedAsMeasured() {
        val plan = BenchmarkPlan(measuredSeconds = 600)
        assertEquals(0, plan.windowIndexAt(-1))
        assertTrue(plan.isWarmup(-1))
        assertFalse(plan.isWarmup(0))
    }

    @Test fun aShortMeasurementKeepsEveryWholeWindowIncludingTheLast() {
        val plan = BenchmarkPlan(measuredSeconds = 25)
        assertEquals(3, plan.windowCount)
        assertEquals(20_000L, plan.windowStartMillis(3))
        assertEquals(25_000L, plan.windowEndMillis(3))
    }

    @Test fun scheduledSpeakerJobsAndFaultsStayInTheirOwnWindow() {
        val plan = BenchmarkPlan(measuredSeconds = 20)
        val accumulator = plan.newAccumulator()
        accumulator.record(ownerIndex = 1, frames = 100, workNanos = 900_000_000L, speakerJobs = 1)
        accumulator.record(ownerIndex = 2, frames = 50, workNanos = 300_000_000L, speakerJobs = 2)
        accumulator.recordFault(2)
        accumulator.recordDrop(2)
        accumulator.recordQueueDepth(1, 4096)
        accumulator.recordQueueDepth(2, 0)

        val windows = accumulator.windows(measuredSeconds = 20.0)
        assertEquals(2, windows.size)
        val first = windows[0]
        assertEquals(1, first.index)
        assertEquals(10.0, first.audioSeconds)
        assertEquals(0.45, first.rtf, 1e-9)
        assertEquals(1, first.speakerCalls)
        assertEquals(0, first.faults)
        assertEquals(4096, first.queueDepth)
        val second = windows[1]
        assertEquals(2, second.index)
        assertEquals(2, second.speakerCalls, "each scheduled speaker job must be counted, not just completions")
        assertEquals(0.3, second.rtf, 1e-9)
        assertEquals(1, second.faults)
        assertEquals(1, second.droppedFrames)
        assertEquals(0, second.queueDepth)
    }

    @Test fun resourceRowsFollowTheirOwnCadenceAndNeverDuplicateAWindow() {
        val plan = BenchmarkPlan(measuredSeconds = 600)
        val accumulator = plan.newAccumulator()
        // One sample per minute of the measured interval; window indices are 1-based so the first sample
        // lands in window 6 and the last exactly on the final window.
        assertEquals(listOf(6, 12, 18, 24, 30, 36, 42, 48, 54, 60), plan.resourceOwnerIndices())
        accumulator.recordResource(6, ResourceSample(pssKiB = 1000, rssKiB = 2000,
            batteryTemperatureTenthsC = 310, batteryPercent = 90))
        accumulator.recordResource(6, ResourceSample(pssKiB = 1100, rssKiB = 2100,
            batteryTemperatureTenthsC = 311, batteryPercent = 89))
        accumulator.recordResource(12, ResourceSample(pssKiB = 1200, rssKiB = 2200,
            batteryTemperatureTenthsC = 312, batteryPercent = 88))
        val rows = accumulator.resourceRows()
        assertEquals(2, rows.size)
        assertEquals(6, rows[0].windowIndex)
        assertEquals(1100, rows[0].pssKiB, "a repeated sample for one window replaces the earlier one")
        assertEquals(12, rows[1].windowIndex)
        assertEquals(1200, rows[1].pssKiB)
    }

    @Test fun missingSensorReadingsStayExplicitInsteadOfBecomingZero() {
        val accumulator = BenchmarkPlan(measuredSeconds = 10).newAccumulator()
        accumulator.recordResource(1, ResourceSample.batteryUnavailable(pssKiB = 700, rssKiB = 800))
        val row = accumulator.resourceRows().single()
        assertEquals(-1, row.batteryTemperatureTenthsC)
        assertEquals(-1, row.batteryPercent)
        assertEquals("battery-temperature-unavailable", row.temperatureSource)
    }

    @Test fun identityIsValidatedAgainstTheHostIdentitySoTheResultCannotBeMisattributed() {
        val identity = DeviceIdentity(deviceId = "2696813b", model = "M2012K11AC", osVersion = "13",
            sdkInt = 33, abi = "arm64-v8a", appVersionName = "1.0", appVersionCode = 1)
        assertTrue(identity.matches(expectedDeviceId = "2696813b", expectedModel = "M2012K11AC",
            expectedOsVersion = "13"))
        assertFalse(identity.matches(expectedDeviceId = "other", expectedModel = "M2012K11AC",
            expectedOsVersion = "13"))
        assertFalse(identity.matches(expectedDeviceId = "2696813b", expectedModel = "K70",
            expectedOsVersion = "13"))
        assertFalse(identity.matches(expectedDeviceId = "2696813b", expectedModel = "M2012K11AC",
            expectedOsVersion = "16"))
        val json = identity.toJson()
        assertTrue(json.contains("M2012K11AC"))
        assertTrue(json.contains("arm64-v8a"))
    }

    @Test fun measuredSecondsMustCoverTheFrozenMinimumBeforeAWindowSetIsAccepted() {
        val plan = BenchmarkPlan(measuredSeconds = 600)
        assertTrue(plan.isSufficientForSelection())
        assertFalse(BenchmarkPlan(measuredSeconds = 599).isSufficientForSelection())
        assertNotNull(plan.newAccumulator())
    }

    @Test fun measuredSecondsMayDropToTheAudioThatActuallyArrived() {
        // The reporter's contract is audioSeconds sum == measuredSeconds. A real replay that loses time
        // must reconcile the two together instead of asserting a nominal 600 over less audio.
        val plan = BenchmarkPlan(measuredSeconds = 600)
        assertEquals(600.0, plan.reconciledMeasuredSeconds(600.0), 1e-9)
        assertEquals(599.4, plan.reconciledMeasuredSeconds(599.4), 1e-9)
        assertFailsWith<IllegalArgumentException> { plan.reconciledMeasuredSeconds(0.0) }
        assertFailsWith<IllegalArgumentException> { plan.reconciledMeasuredSeconds(601.0) }
    }

    @Test fun aShortenedObservationShrinksTheLastWindowSoTheSumStillMatches() {
        // The reporter asserts sum(audioSeconds) == measuredSeconds, so a reconciled-down run must not keep
        // claiming a full ten seconds for the final window.
        val plan = BenchmarkPlan(measuredSeconds = 600)
        val accumulator = plan.newAccumulator()
        val windows = accumulator.windows(measuredSeconds = 599.4)
        assertEquals(60, windows.size)
        assertEquals(10.0, windows.first().audioSeconds, 1e-9)
        assertEquals(9.4, windows.last().audioSeconds, 1e-9)
        assertEquals(599.4, windows.sumOf { it.audioSeconds }, 1e-9)
    }

    @Test fun theFullObservationStillKeepsEveryWindowAtTenSeconds() {
        val windows = BenchmarkPlan(measuredSeconds = 600).newAccumulator().windows(measuredSeconds = 600.0)
        assertTrue(windows.all { it.audioSeconds == 10.0 })
        assertEquals(600.0, windows.sumOf { it.audioSeconds }, 1e-9)
    }

    @Test fun speakerWorkIsCountedIntoTheCombinedRtfNumerator() {
        // The reporter's stated convention is combined VAD+ASR+speaker work per unique source audio second,
        // so speaker extraction time must land in the same window's work, not be dropped.
        val plan = BenchmarkPlan(measuredSeconds = 10)
        val accumulator = plan.newAccumulator()
        accumulator.record(ownerIndex = 1, frames = 100, workNanos = 400_000_000L, speakerJobs = 1)
        accumulator.recordWork(ownerIndex = 1, workNanos = 100_000_000L)
        val window = accumulator.windows(measuredSeconds = 10.0).single()
        assertEquals(1, window.speakerCalls)
        // (400 ms VAD+ASR + 100 ms speaker) / (100 frames * 20 ms) = 0.25
        assertEquals(0.25, window.rtf, 1e-9)
    }

    @Test fun theResourceCadenceIsSampledAtTheEndOfEachMeasuredMinute() {
        // The runner fires a resource sample one minute after each previous sample, starting one minute
        // after measurement start. This is the cadence the recorded rows must actually follow.
        val plan = BenchmarkPlan(measuredSeconds = 600)
        assertEquals(60_000L, plan.firstResourceOffsetMillis())
        // A sample taken exactly at the end of the measured interval belongs to the final window rather
        // than falling off the end and being silently dropped.
        assertEquals(60, plan.resourceOwnerAt(plan.measuredMillis - 1))
        assertEquals(60, plan.resourceOwnerAt(plan.measuredMillis))
        assertEquals(1, plan.resourceOwnerAt(0))
        assertFailsWith<IllegalArgumentException> { plan.resourceOwnerAt(-1) }
    }

    @Test fun aWindowWithNoProcessedFramesIsRecordedAsLostWorkNotAsAFastWindow() {
        // rtf = 0.0 for an unprocessed window would help the RTF gate while nothing was actually measured.
        val plan = BenchmarkPlan(measuredSeconds = 20)
        val accumulator = plan.newAccumulator()
        accumulator.record(ownerIndex = 1, frames = 100, workNanos = 1_000_000_000L, speakerJobs = 1)
        // Window 2 processed nothing at all.
        val windows = accumulator.windows(measuredSeconds = 20.0)
        assertEquals(0, windows[0].droppedFrames)
        assertEquals(1, windows[1].droppedFrames, "an unprocessed measured window is lost work")
    }

    @Test fun aPlanMustCoverAtLeastOneWindow() {
        assertFailsWith<IllegalArgumentException> { BenchmarkPlan(measuredSeconds = 0) }
    }
}
