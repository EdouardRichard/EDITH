package edith.glasses.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's verdict is "did the glasses microphone really produce audio, and on which channels". A wrong
 * threshold or a wrong byte order would turn digital silence into a confident format claim, so the analysis is
 * tested on the JVM before it ever runs on the glasses.
 */
class PcmChannelAnalysisTest {
    /** Interleaves per-channel sample values into 16-bit little-endian PCM. */
    private fun interleave(vararg frames: List<Int>): ByteArray {
        val channelCount = frames.size
        val frameCount = frames[0].size
        val out = ByteArray(frameCount * channelCount * 2)
        var index = 0
        for (frame in 0 until frameCount) {
            for (channel in 0 until channelCount) {
                val sample = frames[channel][frame]
                out[index++] = (sample and 0xFF).toByte()
                out[index++] = ((sample shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun constant(channelCount: Int, frames: Int, vararg values: Int): ByteArray {
        val channels = (0 until channelCount).map { values[it % values.size] }
        return interleave(*Array(channelCount) { channel -> List(frames) { channels[channel] } })
    }

    @Test fun digitalSilenceIsReportedAsSilent() {
        val analysis = PcmChannelAnalysis.of(constant(8, 160, 0), 8)
        assertEquals(8, analysis.channelCount)
        assertEquals(160L, analysis.frameCount)
        assertFalse("all-zero PCM must not be reported as signal", analysis.hasSignal)
        assertEquals((0 until 8).toList(), analysis.silentChannels)
        assertTrue(analysis.speechLikeChannels().isEmpty())
    }

    @Test fun aSingleLoudChannelIsDetectedAlongsideSilentOnes() {
        // Channel 2 carries speech-level audio; everything else is silent. This is the shape the probe looks for:
        // the documented layout puts raw microphones on 2-5.
        val channels = Array(8) { channel -> List(160) { if (channel == 2) 8000 else 0 } }
        val analysis = PcmChannelAnalysis.of(interleave(*channels), 8)
        assertTrue("one live channel is enough to prove the microphone worked", analysis.hasSignal)
        assertEquals(listOf(2), analysis.speechLikeChannels())
        assertEquals(listOf(0, 1, 3, 4, 5, 6, 7), analysis.silentChannels)
    }

    @Test fun littleEndianBytesAreDecodedInTheRightOrder() {
        // 0x0100 little-endian is bytes 00 01 => sample 256. Reading it big-endian would give 1.
        val pcm = byteArrayOf(0x00, 0x01)
        val analysis = PcmChannelAnalysis.of(pcm, 1)
        assertEquals(1L, analysis.frameCount)
        // 256 / 32768 = 7.8 permille, which must not be rounded down to 0 by a big-endian misread.
        assertTrue("byte order must be little-endian", analysis.hasSignal)
    }

    @Test fun aNegativeSampleCountsByMagnitudeNotSign() {
        val pcm = byteArrayOf(0x00, 0x80.toByte()) // -32768, full-scale negative
        val analysis = PcmChannelAnalysis.of(pcm, 1)
        assertEquals(1000, analysis.peakPermille[0])
        assertTrue(analysis.hasSignal)
    }

    @Test fun aTrailingPartialFrameIsIgnoredRatherThanDistortingTheResult() {
        // 8 channels but only 7 samples supplied: the incomplete frame must be dropped.
        val pcm = ByteArray(7 * 2) { 0x10 }
        val analysis = PcmChannelAnalysis.of(pcm, 8)
        assertEquals("a partial frame must not be counted", 0L, analysis.frameCount)
        assertFalse(analysis.hasSignal)
    }

    @Test fun emptyInputYieldsZeroFramesButKeepsTheRequestedChannelCount() {
        val analysis = PcmChannelAnalysis.of(ByteArray(0), 8)
        // The channel count describes the configuration that was attempted, so the report can still say
        // "8 channels requested, 0 frames received" - which is exactly the noFrames verdict.
        assertEquals(8, analysis.channelCount)
        assertEquals(0L, analysis.frameCount)
        assertFalse(analysis.hasSignal)
        assertEquals((0 until 8).toList(), analysis.silentChannels)
    }

    @Test fun levelsAreReportedAsFixedPointForByteStableReports() {
        val analysis = PcmChannelAnalysis.of(constant(1, 4, 3277), 1)
        // 3277 / 32768 = 100.0 permille -> "10.0"
        assertEquals(listOf("10.0"), analysis.rmsText())
    }

    @Test fun aQuietRoomIsBelowTheSpeechThresholdButStillSignal() {
        // ~1% of full scale: real but quiet, so it proves the microphone works while not claiming speech.
        val analysis = PcmChannelAnalysis.of(constant(1, 64, 300), 1)
        assertTrue(analysis.hasSignal)
        assertTrue("a quiet room must not be called speech", analysis.speechLikeChannels().isEmpty())
    }

    @Test fun aShortUtteranceInALongCaptureIsCaughtByTheWindowedMaximum() {
        // This is the case that matters on the glasses: the wearer speaks for ~0.5 s inside a 6 s capture. The
        // whole-capture mean stays near the noise floor, so only the windowed maximum reveals the speech.
        val window = PcmChannelAnalysis.WINDOW_FRAMES
        val frames = List(window * 10) { frame -> if (frame in window until window * 2) 6000 else 40 }
        val analysis = PcmChannelAnalysis.of(interleave(frames), 1)
        assertTrue("a 0.5 s utterance must not be averaged away", analysis.windowPeakPermille[0] >= PcmChannelAnalysis.SPEECH_RMS_PERMILLE)
        assertTrue("and it must be reported as a speech-like window", analysis.windowSpeechChannels().contains(0))
    }

    @Test fun aCaptureWhereNobodySpokeStaysBelowTheWindowThreshold() {
        val analysis = PcmChannelAnalysis.of(constant(1, PcmChannelAnalysis.WINDOW_FRAMES * 3, 60), 1)
        assertTrue(analysis.windowSpeechChannels().isEmpty())
        assertTrue(analysis.hasSignal)
    }
}
