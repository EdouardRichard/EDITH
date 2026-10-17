package com.sheen.rokid.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The uplink frame codec carries captured PCM from the glasses to the phone over the CXR custom-command channel.
 *
 * It is binary rather than JSON on purpose: base64 inside JSON inflates audio by a third, and this link is the
 * throughput bottleneck. The format is fixed and explicit so a receiver can never interpret a truncated or reordered
 * frame as audio - a receiver that guesses would produce noise that looks like a codec problem.
 */
class AudioUplinkCodecTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    /** Tampering helper: overwrite the declared-length field at its real offset. */
    private fun withDeclaredLength(frame: ByteArray, declared: Int): ByteArray {
        val copy = frame.copyOf()
        val at = 24
        copy[at] = ((declared shr 24) and 0xFF).toByte()
        copy[at + 1] = ((declared shr 16) and 0xFF).toByte()
        copy[at + 2] = ((declared shr 8) and 0xFF).toByte()
        copy[at + 3] = (declared and 0xFF).toByte()
        return copy
    }

    @Test fun aStartFrameRoundTripsTheFormatSoTheReceiverNeverGuesses() {
        val start = AudioUplinkCodec.encodeStart(sessionTag = "s-1", sampleRateHz = 16_000, channelCount = 8,
            bitsPerSample = 16, selectedChannels = listOf(0, 1))
        val decoded = AudioUplinkCodec.decode(start)
        assertTrue(decoded is UplinkMessage.Start, "a START frame must decode as START")
        val frame = decoded as UplinkMessage.Start
        assertEquals("s-1", frame.sessionTag)
        assertEquals(16_000, frame.sampleRateHz)
        assertEquals(8, frame.channelCount)
        assertEquals(16, frame.bitsPerSample)
        assertEquals(listOf(0, 1), frame.selectedChannels)
    }

    @Test fun aPcmFrameRoundTripsItsBytesExactly() {
        val payload = pcm(1, -1, 32_767, -32_768, 0)
        val encoded = AudioUplinkCodec.encodePcm(sequence = 7L, capturedAtNanos = 1_234_567L, pcm = payload)
        assertEquals(AudioUplinkCodec.HEADER_BYTES + payload.size, encoded.size,
            "the frame is header plus payload, nothing appended")
        val decoded = AudioUplinkCodec.decode(encoded)
        assertTrue(decoded is UplinkMessage.Pcm, "a PCM frame must decode as PCM")
        val frame = decoded as UplinkMessage.Pcm
        assertEquals(7L, frame.sequence)
        assertEquals(1_234_567L, frame.capturedAtNanos)
        assertContentEquals(payload, frame.pcm, "PCM bytes must survive the wire bit for bit")
    }

    @Test fun anEndFrameCarriesWhyTheStreamStopped() {
        val decoded = AudioUplinkCodec.decode(
            AudioUplinkCodec.encodeEnd(sequence = 9L, framesSent = 9L, reason = UplinkEndReason.UserStopped),
        )
        assertTrue(decoded is UplinkMessage.End, "an END frame must decode as END")
        val frame = decoded as UplinkMessage.End
        assertEquals(9L, frame.framesSent)
        assertEquals(UplinkEndReason.UserStopped, frame.reason)
    }

    @Test fun aFrameFromAnotherProtocolIsRefusedRatherThanMisread() {
        assertNull(AudioUplinkCodec.decode(ByteArray(64) { 0x5A }), "a foreign payload must not decode into audio")
    }

    @Test fun aTruncatedFrameIsRefused() {
        val full = AudioUplinkCodec.encodePcm(1L, 0L, pcm(1, 2, 3, 4))
        assertNull(AudioUplinkCodec.decode(full.copyOf(AudioUplinkCodec.HEADER_BYTES - 1)),
            "a header-only frame is incomplete")
        assertNull(AudioUplinkCodec.decode(full.copyOf(full.size - 1)), "a short payload must be refused")
    }

    @Test fun aFrameDeclaringMoreBytesThanItCarriesIsRefused() {
        val full = AudioUplinkCodec.encodePcm(1L, 0L, pcm(1, 2, 3, 4))
        val declared = full.size - AudioUplinkCodec.HEADER_BYTES + 16
        assertNull(AudioUplinkCodec.decode(withDeclaredLength(full, declared)),
            "a length that exceeds the buffer must be refused")
    }

    @Test fun aFrameDeclaringFewerBytesThanItCarriesIsAlsoRefused() {
        val full = AudioUplinkCodec.encodePcm(1L, 0L, pcm(1, 2, 3, 4))
        val declared = full.size - AudioUplinkCodec.HEADER_BYTES - 2
        assertNull(AudioUplinkCodec.decode(withDeclaredLength(full, declared)),
            "a short declared length must be refused")
    }

    @Test fun aPcmFrameWithAnOddByteCountIsRefused() {
        // 16-bit samples cannot be an odd number of bytes; accepting it would shift every later sample.
        val bad = AudioUplinkCodec.encodePcm(1L, 0L, ByteArray(4))
        val odd = bad.copyOf(bad.size + 1)
        assertNull(AudioUplinkCodec.decode(withDeclaredLength(odd, 5)),
            "an odd payload length cannot be whole 16-bit samples")
    }

    @Test fun anEmptyPcmFrameIsRefusedAtTheSender() {
        assertFailsWith<IllegalArgumentException> { AudioUplinkCodec.encodePcm(1L, 0L, ByteArray(0)) }
    }

    @Test fun anOversizedPayloadIsRefusedBeforeItIsCopied() {
        // The bound exists so a hostile or buggy peer cannot make the receiver allocate without limit.
        assertNull(AudioUplinkCodec.decode(ByteArray(AudioUplinkCodec.MAX_FRAME_BYTES + 1)))
    }

    @Test fun selectionIsValidatedAgainstTheRealChannelCount() {
        // Channel 3 cannot be selected from a 2-channel capture; silently accepting it would mislabel audio.
        assertFailsWith<IllegalArgumentException> {
            AudioUplinkCodec.encodeStart("s", 16_000, 2, 16, listOf(0, 1, 3))
        }
        // A hand-built frame naming an impossible channel must be refused on receipt too.
        val valid = AudioUplinkCodec.encodeStart("s", 16_000, 2, 16, listOf(0, 1))
        val tampered = valid.copyOf()
        tampered[tampered.size - 1] = 3
        assertNull(AudioUplinkCodec.decode(tampered),
            "a receiver must not trust a selection it cannot satisfy")
        assertNotNull(AudioUplinkCodec.decode(valid))
    }

    @Test fun theChannelSelectionShrinksTheUplinkByTheChannelRatio() {
        // 8 raw channels are ~256 KB/s; selecting the two processed ones is ~64 KB/s. This is the throughput lever,
        // so the codec states the reduction rather than leaving it to arithmetic by hand.
        assertEquals(256_000, AudioUplinkCodec.bytesPerSecond(16_000, 8, 16))
        assertEquals(64_000, AudioUplinkCodec.bytesPerSecond(16_000, 2, 16))
        assertEquals(25, AudioUplinkCodec.framesPerSecond())
    }

    @Test fun sequenceNumbersAreCarriedSoTheReceiverCanDetectLoss() {
        val first = AudioUplinkCodec.decode(AudioUplinkCodec.encodePcm(1L, 0L, pcm(1))) as UplinkMessage.Pcm
        val second = AudioUplinkCodec.decode(AudioUplinkCodec.encodePcm(2L, 0L, pcm(2))) as UplinkMessage.Pcm
        assertEquals(1L, first.sequence)
        assertEquals(1L, second.sequence - first.sequence, "a gap in sequence is how the phone counts dropped audio")
    }
}
