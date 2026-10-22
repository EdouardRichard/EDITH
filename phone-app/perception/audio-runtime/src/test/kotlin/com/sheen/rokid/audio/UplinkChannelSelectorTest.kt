package com.sheen.rokid.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * De-interleaves a capture down to the channels the uplink will carry.
 *
 * This is the step most likely to be silently wrong: an off-by-one here produces audio that still has the right
 * length and level, so nothing downstream complains - it simply transcribes the wrong microphone, or interleaves two
 * speakers into one stream. The tests therefore pin the exact sample values, not just the sizes.
 */
class UplinkChannelSelectorTest {
    /** Builds interleaved little-endian 16-bit PCM from per-channel sample runs. */
    private fun interleave(vararg channels: List<Int>): ByteArray {
        val channelCount = channels.size
        val frames = channels[0].size
        val out = ByteArray(frames * channelCount * 2)
        var index = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                val sample = channels[channel][frame]
                out[index++] = (sample and 0xFF).toByte()
                out[index++] = ((sample shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun samples(bytes: ByteArray): List<Int> = (bytes.indices step 2).map { index ->
        val low = bytes[index].toInt() and 0xFF
        val high = bytes[index + 1].toInt()
        (high shl 8) or low
    }

    @Test fun selectingTheProcessedPairKeepsThoseTwoChannelsInOrder() {
        val capture = interleave(
            listOf(10, 11, 12), listOf(20, 21, 22), listOf(30, 31, 32), listOf(40, 41, 42),
            listOf(50, 51, 52), listOf(60, 61, 62), listOf(70, 71, 72), listOf(80, 81, 82),
        )
        val selector = UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(0, 1))
        val selected = selector.select(capture)
        // Interleaved (0,1) pairs, in frame order.
        assertEquals(listOf(10, 20, 11, 21, 12, 22), samples(selected))
        assertEquals(3, selector.framesIn(capture))
    }

    @Test fun selectingTheRawMicrophonesSkipsTheOthersEntirely() {
        val capture = interleave(
            listOf(1, 1), listOf(2, 2), listOf(300, 301), listOf(400, 401), listOf(500, 501), listOf(600, 601),
            listOf(7, 7), listOf(8, 8),
        )
        val selector = UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(2, 3, 4, 5))
        assertEquals(listOf(300, 400, 500, 600, 301, 401, 501, 601), samples(selector.select(capture)))
    }

    @Test fun selectingASingleChannelIsAMonoDownmixOfExactlyThatChannel() {
        val capture = interleave(listOf(11, 12), listOf(21, 22))
        val selector = UplinkChannelSelector(sourceChannelCount = 2, selectedChannels = listOf(1))
        assertEquals(listOf(21, 22), samples(selector.select(capture)))
    }

    @Test fun negativeSamplesSurviveTheSelectionWithTheirSign() {
        val capture = interleave(listOf(-1, -32768), listOf(32767, 0))
        val selector = UplinkChannelSelector(sourceChannelCount = 2, selectedChannels = listOf(0, 1))
        assertEquals(listOf(-1, 32767, -32768, 0), samples(selector.select(capture)))
    }

    @Test fun aTrailingPartialFrameIsDroppedRatherThanShiftedIntoTheWrongChannel() {
        // 2 channels but 3 samples: the incomplete frame must not be emitted, because emitting it would put channel
        // 0's sample where channel 1's belongs for every later frame.
        val capture = interleave(listOf(1, 2), listOf(3, 4)).copyOf(6)
        val selector = UplinkChannelSelector(sourceChannelCount = 2, selectedChannels = listOf(0, 1))
        assertEquals(1, selector.framesIn(capture))
        assertEquals(listOf(1, 3), samples(selector.select(capture)))
    }

    @Test fun anEmptyCaptureYieldsNoSamplesRatherThanThrowing() {
        val selector = UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(0, 1))
        assertEquals(0, selector.framesIn(ByteArray(0)))
        assertContentEquals(ByteArray(0), selector.select(ByteArray(0)))
    }

    @Test fun aSelectionOutsideTheCapturedChannelsIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(0, 8))
        }
        assertFailsWith<IllegalArgumentException> {
            UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            UplinkChannelSelector(sourceChannelCount = 0, selectedChannels = listOf(0))
        }
    }

    @Test fun theOutputGeometryIsTheSelectionNotTheCapture() {
        val selector = UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(0, 1))
        assertEquals(2, selector.outputChannelCount)
        // One second of 8-channel 16-bit capture becomes one second of 2-channel audio: a quarter of the bytes.
        val oneSecond = ByteArray(16_000 * 8 * 2)
        assertEquals(16_000 * 2 * 2, selector.select(oneSecond).size)
        assertTrue(selector.outputChannelCount < selector.sourceChannelCount)
    }
}
