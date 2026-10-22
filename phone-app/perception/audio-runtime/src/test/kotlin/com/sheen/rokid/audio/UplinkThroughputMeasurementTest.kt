package com.sheen.rokid.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The verdict decides whether the self-built uplink is viable. Getting it wrong in the optimistic direction would
 * green-light a channel that cannot carry audio, and the failure would surface much later as choppy transcription.
 */
class UplinkThroughputMeasurementTest {
    private fun measurement(required: Int = 64_000, expectedFrames: Int = 25) =
        UplinkThroughputMeasurement(required, expectedFrames)

    @Test fun aChannelThatDeliversTheFullRateInTheExpectedFramesSustains() {
        val subject = measurement()
        subject.start(0)
        // 25 frames of 2560 bytes over exactly one second is the required 64 KB/s.
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560) }
        subject.finish(1_000_000_000)
        assertEquals("SUSTAINS_REAL_TIME", subject.verdict())
        assertEquals(64_000.0, subject.achievedBytesPerSecond, 1.0)
        assertTrue(subject.summary().contains("verdict=SUSTAINS_REAL_TIME"), subject.summary())
    }

    @Test fun aChannelThatIsTooSlowIsReportedAsSuchEvenWithNoRefusals() {
        val subject = measurement()
        subject.start(0)
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560) }
        // Twice the time for the same audio: half the required rate.
        subject.finish(2_000_000_000)
        assertEquals("TOO_SLOW_FOR_REAL_TIME", subject.verdict())
    }

    @Test fun refusalsOutrankTheRateWhenTheDeliveredRateIsAlsoShort() {
        val subject = measurement()
        subject.start(0)
        repeat(25) { index -> subject.record(acceptedFrame = index % 3 != 0, bytes = 2_560) }
        subject.finish(1_000_000_000)
        // Only accepted frames count towards payload, so refusing a third of them is both a rate loss and a burst
        // loss. The verdict must name the refusal, because that is the actionable cause.
        assertTrue(subject.achievedBytesPerSecond < 64_000.0, "refused payload is not delivered payload")
        assertTrue(subject.refusalRatio > 0.0)
        assertEquals("TRANSPORT_REFUSED_FRAMES", subject.verdict())
    }

    @Test fun aRefusingChannelIsNamedAsRefusingEvenWhenItsAverageRateWouldPass() {
        val subject = measurement(expectedFrames = 30)
        subject.start(0)
        // 29 accepted frames of 2560 bytes in one second is 74 KB/s - above the 64 KB/s requirement - but one frame
        // was refused. Reading only the average would call this a pass.
        repeat(30) { index -> subject.record(acceptedFrame = index != 0, bytes = 2_560) }
        subject.finish(1_000_000_000)
        assertTrue(subject.achievedBytesPerSecond >= 64_000.0, "the average does clear the bar")
        assertEquals("TRANSPORT_REFUSED_FRAMES", subject.verdict())
    }

    @Test fun anIncompleteRunIsNeverAPassHoweverGoodItsRateLooks() {
        val subject = measurement(expectedFrames = 25)
        subject.start(0)
        // Only three very fast frames: the rate looks enormous but almost no audio was attempted.
        repeat(3) { subject.record(acceptedFrame = true, bytes = 2_560) }
        subject.finish(1_000_000)
        assertTrue(subject.achievedBytesPerSecond > 64_000.0)
        assertEquals("INCOMPLETE_RUN", subject.verdict())
    }

    @Test fun aRunThatAttemptedNothingIsReportedAsSuchRatherThanAsTooSlow() {
        val subject = measurement()
        subject.start(0)
        subject.finish(1_000_000_000)
        assertEquals("NO_FRAMES_ATTEMPTED", subject.verdict())
        assertEquals(0.0, subject.refusalRatio, 0.0001)
    }

    @Test fun aZeroElapsedTimeCannotProduceAnInfiniteRate() {
        val subject = measurement()
        subject.start(1_000)
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560) }
        subject.finish(1_000)
        assertEquals(0L, subject.elapsedMillis)
        assertEquals(0.0, subject.achievedBytesPerSecond, 0.0001)
        assertEquals("TOO_SLOW_FOR_REAL_TIME", subject.verdict())
    }

    @Test fun aFinishedRunReportsTheSameVerdictWhenReadRepeatedly() {
        val subject = measurement()
        subject.start(0)
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560) }
        subject.finish(1_000_000_000)
        val first = subject.verdict()
        val second = subject.verdict()
        assertEquals(first, second, "the verdict must be a pure function of the recorded run")
    }

    @Test fun frameCountingFollowsThePayloadSize() {
        // One second of 40 ms frames is 25 frames; 20 ms frames need 50.
        assertEquals(25, UplinkThroughputMeasurement.framesFor(payloadBytesPerFrame = 2_560, requiredBytesPerSecond = 64_000))
        assertEquals(50, UplinkThroughputMeasurement.framesFor(payloadBytesPerFrame = 1_280, requiredBytesPerSecond = 64_000))
    }

    @Test fun impossibleConstructionIsRefused() {
        assertTrue(
            runCatching { UplinkThroughputMeasurement(0, 25) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { UplinkThroughputMeasurement(64_000, 0) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test fun aPacedRunWhoseSendsAllFitTheIntervalSustains() {
        // 25 frames at a 40 ms cadence is one second of audio, and every send finishes well inside its interval.
        val subject = UplinkThroughputMeasurement(64_000, 25, frameIntervalMillis = 40)
        subject.start(0)
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560, sendNanos = 2_000_000) }
        subject.finish(1_000_000_000)
        assertEquals("SUSTAINS_REAL_TIME", subject.verdict())
        assertEquals(2L, subject.slowestSendMillis)
    }

    @Test fun aSingleSendSlowerThanItsIntervalFailsThePacedRunDespiteAFineAverage() {
        // This is the distinction the paced measurement exists for: the channel has capacity on average, but one send
        // overran the 40 ms interval, so a real sender would fall behind and accumulate latency.
        val subject = UplinkThroughputMeasurement(64_000, 25, frameIntervalMillis = 40)
        subject.start(0)
        repeat(24) { subject.record(acceptedFrame = true, bytes = 2_560, sendNanos = 1_000_000) }
        subject.record(acceptedFrame = true, bytes = 2_560, sendNanos = 55_000_000)
        subject.finish(1_000_000_000)
        assertTrue(subject.achievedBytesPerSecond >= 64_000.0, "the average still clears the bar")
        assertTrue(!subject.everySendFitsTheInterval)
        assertEquals("SLOWEST_SEND_EXCEEDS_FRAME_INTERVAL", subject.verdict())
    }

    @Test fun refusalStillOutranksAPacingProblem() {
        val subject = UplinkThroughputMeasurement(64_000, 25, frameIntervalMillis = 40)
        subject.start(0)
        repeat(24) { subject.record(acceptedFrame = true, bytes = 2_560, sendNanos = 1_000_000) }
        subject.record(acceptedFrame = false, bytes = 2_560, sendNanos = 60_000_000)
        subject.finish(1_000_000_000)
        // Both rules are violated; the refusal is the more actionable cause and is named first.
        assertEquals("TRANSPORT_REFUSED_FRAMES", subject.verdict())
    }

    @Test fun aBurstRunIsNotJudgedOnPacingBecauseItNeverClaimedToBePaced() {
        // With no interval declared, the 15 ms burst that measured channel capacity stays a capacity result.
        val subject = UplinkThroughputMeasurement(64_000, 25)
        subject.start(0)
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560, sendNanos = 600_000) }
        subject.finish(15_000_000)
        assertTrue(subject.everySendFitsTheInterval, "no interval was declared, so pacing is not judged")
        assertEquals("SUSTAINS_REAL_TIME", subject.verdict())
    }

    @Test fun theSummaryNamesTheIntervalAndSlowestSendForAPacedRun() {
        val subject = UplinkThroughputMeasurement(64_000, 25, frameIntervalMillis = 40)
        subject.start(0)
        repeat(25) { subject.record(acceptedFrame = true, bytes = 2_560, sendNanos = 3_000_000) }
        subject.finish(1_000_000_000)
        val text = subject.summary()
        assertTrue(text.contains("frameIntervalMillis=40"), text)
        assertTrue(text.contains("slowestSendMillis=3"), text)
    }
}
