package edith.glasses.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict logic decides whether the CXR message channel can carry the audio. A wrong threshold here would let a
 * channel that refuses or lags be reported as working, so the arithmetic and the refusal rule are pinned.
 */
class UplinkThroughputProbeTest {
    @Test fun theRequiredRateIsTheAudioGeometryNotAGuess() {
        // 16 kHz x 2 channels x 2 bytes: the two processed channels the uplink carries.
        assertEquals(64_000, UplinkThroughputProbe.requiredBytesPerSecond(channels = 2))
        // All eight channels would be four times that, which is why the selection exists.
        assertEquals(256_000, UplinkThroughputProbe.requiredBytesPerSecond(channels = 8))
    }

    @Test fun framePacingMatchesTheFrameDuration() {
        assertEquals(25, UplinkThroughputProbe.framesPerSecond(40))
        assertEquals(50, UplinkThroughputProbe.framesPerSecond(20))
    }

    @Test fun aFramePayloadMatchesItsDuration() {
        // 40 ms at 16 kHz is 640 samples per channel, x2 channels x2 bytes = 2560 bytes.
        assertEquals(2_560, UplinkThroughputProbe.payloadBytesPerFrame(channels = 2, frameMillis = 40))
        assertEquals(5_120, UplinkThroughputProbe.payloadBytesPerFrame(channels = 4, frameMillis = 40))
    }

    @Test fun aChannelThatCarriesTheFullRateIsReportedAsSustaining() {
        // One second of audio at exactly the required rate.
        val verdict = UplinkThroughputProbe.Verdict(
            acceptedFrames = 25, attemptedFrames = 25, payloadBytes = 64_000, elapsedMillis = 1_000,
            requiredBytesPerSecond = 64_000,
        )
        assertTrue(verdict.sustainsRealTime)
        assertEquals(0.0, verdict.refusalRatio, 0.0001)
        assertTrue(verdict.summary(), verdict.summary().contains("verdict=SUSTAINS_REAL_TIME"))
    }

    @Test fun aSlowChannelIsReportedAsTooSlowEvenWhenNothingIsRefused() {
        // Every frame accepted, but only half the audio in the same second: the channel is too slow.
        val verdict = UplinkThroughputProbe.Verdict(
            acceptedFrames = 12, attemptedFrames = 12, payloadBytes = 32_000, elapsedMillis = 1_000,
            requiredBytesPerSecond = 64_000,
        )
        assertFalse("accepting every frame is not the same as keeping up", verdict.sustainsRealTime)
        assertTrue(verdict.summary(), verdict.summary().contains("verdict=TOO_SLOW_FOR_REAL_TIME"))
    }

    @Test fun refusedFramesAreReportedAsRefusalEvenIfTheAverageRateLooksAdequate() {
        // The average rate meets the target, but a third of the frames were refused: that is a failure, and reading
        // only the average would hide it.
        val verdict = UplinkThroughputProbe.Verdict(
            acceptedFrames = 2, attemptedFrames = 3, payloadBytes = 64_000, elapsedMillis = 1_000,
            requiredBytesPerSecond = 64_000,
        )
        assertTrue(verdict.sustainsRealTime)
        assertTrue(verdict.refusalRatio > 0.0)
        assertTrue(verdict.summary(), verdict.summary().contains("verdict=TRANSPORT_REFUSED_FRAMES"))
    }

    @Test fun aRunThatAttemptedNothingIsNotAPass() {
        val verdict = UplinkThroughputProbe.Verdict(
            acceptedFrames = 0, attemptedFrames = 0, payloadBytes = 0, elapsedMillis = 5,
            requiredBytesPerSecond = 64_000,
        )
        assertFalse(verdict.sustainsRealTime)
        assertTrue(verdict.summary(), verdict.summary().contains("verdict=NO_FRAMES_ATTEMPTED"))
    }

    @Test fun aZeroDurationCannotDivideByZero() {
        val verdict = UplinkThroughputProbe.Verdict(
            acceptedFrames = 1, attemptedFrames = 1, payloadBytes = 1_000, elapsedMillis = 0,
            requiredBytesPerSecond = 64_000,
        )
        assertEquals(0.0, verdict.achievedBytesPerSecond, 0.0001)
        assertFalse(verdict.sustainsRealTime)
    }

    @Test fun impossibleGeometryIsRefusedRatherThanSilentlyZero() {
        val thrown = runCatching { UplinkThroughputProbe.requiredBytesPerSecond(channels = 0) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        val frame = runCatching { UplinkThroughputProbe.framesPerSecond(0) }.exceptionOrNull()
        assertTrue(frame is IllegalArgumentException)
    }
}
