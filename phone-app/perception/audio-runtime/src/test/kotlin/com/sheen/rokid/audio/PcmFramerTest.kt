package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import kotlin.test.*

class PcmFramerTest {
    private val epoch = InputEpoch(SessionId("s"), Generation(0), 1)
    private fun format(encoding: PcmEncoding = PcmEncoding.SIGNED_LE) =
        VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), encoding, "synthetic")
    private fun chunk(bytes: ByteArray, received: Long = 0, continuity: String = "c", sequence: Long = 0) =
        PcmChunk(epoch, sequence, received, bytes, continuity)

    @Test fun `arbitrary callback fragments preserve samples and media time`() {
        val framer = PcmFramer(format())
        val bytes = ByteArray(1280)
        bytes[0] = 0; bytes[1] = 0x80.toByte()
        bytes[2] = 0xff.toByte(); bytes[3] = 0x7f
        val frames = framer.accept(chunk(bytes.copyOfRange(0, 1))) +
            framer.accept(chunk(bytes.copyOfRange(1, 640), 999_000_000, sequence = 1)) +
            framer.accept(chunk(bytes.copyOfRange(640, 1280), 2_000_000_000, sequence = 2))
        assertEquals(2, frames.size)
        assertEquals(-1f, frames[0].samples[0])
        assertEquals(32767f / 32768f, frames[0].samples[1])
        assertEquals(listOf(0L, 320L), frames.map { it.range.first })
        assertEquals(listOf(20_000_000L, 40_000_000L), frames.map { it.endNanos })
    }

    @Test fun `big endian signed samples decode without relabelling`() {
        val framer = PcmFramer(format(PcmEncoding.SIGNED_BE))
        val bytes = ByteArray(640).also { it[0] = 0x80.toByte(); it[1] = 0 }
        assertEquals(-1f, framer.accept(chunk(bytes)).single().samples[0])
    }

    @Test fun `continuity change discards partial sample and resets offset`() {
        val framer = PcmFramer(format())
        assertTrue(framer.accept(chunk(byteArrayOf(0x7f))).isEmpty())
        val frame = framer.accept(chunk(ByteArray(640), 100_000_000, "new", 1)).single()
        assertEquals(0f, frame.samples[0])
        assertEquals(0L, frame.range.first)
        assertEquals(120_000_000L, frame.endNanos)
    }

    @Test fun `unsupported verified representation requires converter`() {
        assertFailsWith<ContractError.CapabilityMissing> {
            PcmFramer(VerifiedPcm(AudioFormat.Pcm(48000, 2, 16), PcmEncoding.SIGNED_LE, "synthetic"))
        }
    }

    @Test fun `explicit reset discards remainder and reanchors next callback`() {
        val framer = PcmFramer(format())
        assertEquals(1, framer.accept(chunk(ByteArray(641).also { it[640] = 0x7f })).size)
        framer.reset()
        val next = framer.accept(chunk(ByteArray(640), 80_000_000, sequence = 1)).single()
        assertEquals(0f, next.samples[0])
        assertEquals(0L, next.range.first)
        assertEquals(80_000_000L, next.startNanos)
        assertEquals(100_000_000L, next.endNanos)
    }
}
