package com.sheen.rokid.testsupport

import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.VerifiedPcm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RamPcmFixtureTest {
    @Test fun unverifiedFormatIsNeverPromoted() {
        assertNull(RamPcmFixture.fromVerified(null, samples(1000)))
        assertNull(RamPcmFixture.fromVerified(verified(encoding = PcmEncoding.SIGNED_BE), samples(1000)))
        assertNull(RamPcmFixture.fromVerified(verified(format = AudioFormat.Pcm(16000, 1, 8)), samples(1000)))
    }

    @Test fun boundedGeometryIsEnforced() {
        assertNull(RamPcmFixture.fromVerified(verified(), ByteArray(0)))
        assertNull(RamPcmFixture.fromVerified(verified(), ByteArray(3)))
        assertNull(RamPcmFixture.fromVerified(verified(), ByteArray(16000 * 2 * (RamPcmFixture.MAX_SECONDS + 1))))
    }

    @Test fun windowSlicesExactByteRange() {
        val fixture = requireNotNull(RamPcmFixture.fromVerified(verified(), samples(1000)))
        try {
            val byteCount = fixture.byteCount
            assertEquals(32000, byteCount)
            assertEquals(1000.0, fixture.durationMillis)
            val half = fixture.window(0, 500)
            assertEquals(16000, half.size)
            assertContentEquals(half, fixture.window(0, 500))
            assertEquals(16000, fixture.window(500, 500).size)
        } finally {
            fixture.close()
        }
    }

    @Test fun outOfRangeAndClosedUseAreRejectedAndBufferIsWiped() {
        val buffer = samples(1000)
        val fixture = requireNotNull(RamPcmFixture.fromVerified(verified(), buffer))
        assertFailsWith<IllegalArgumentException> { fixture.window(-1, 100) }
        assertFailsWith<IllegalArgumentException> { fixture.window(900, 200) }
        assertFailsWith<IllegalArgumentException> { fixture.window(0, 0) }
        fixture.close()
        assertTrue(fixture.closed)
        assertFailsWith<IllegalStateException> { fixture.window(0, 100) }
        assertTrue(buffer.all { it == 0.toByte() }, "owned PCM must be zeroed on close")
    }

    @Test fun closeIsIdempotent() {
        val fixture = requireNotNull(RamPcmFixture.fromVerified(verified(), samples(100)))
        fixture.close()
        fixture.close()
        assertTrue(fixture.closed)
        assertFalse(fixture.byteCount == 0 && !fixture.closed)
    }

    private fun verified(
        format: AudioFormat.Pcm = AudioFormat.Pcm(16000, 1, 16),
        encoding: PcmEncoding = PcmEncoding.SIGNED_LE,
    ) = VerifiedPcm(format, encoding, "controlled-tone-1s")

    private fun samples(millis: Int): ByteArray {
        val bytes = millis * 32
        return ByteArray(bytes) { index -> (index % 251 - 125).toByte() }
    }
}
