package com.sheen.rokid.phone
import com.sheen.rokid.phone.debug.F4MemoryBackup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The F4 memory backup wrapper (Phase 7 design §3.3).
 *
 * The backup has to preserve the plugin's existing memory document **byte for byte**, including the difference between
 * "this build never wrote a document" (`read` answered `null`) and "a document exists and is empty" (zero bytes). Those
 * two states restore differently - the first deletes the key, the second writes an empty value - so the file cannot be
 * the raw bytes alone; the one-byte marker is what keeps them apart.
 */
class DebugF4MemoryTest {

    @Test
    fun `an absent document round-trips as absent`() {
        val encoded = F4MemoryBackup.encode(null)

        assertFalse("the wrapper must not be empty, or it could not carry the marker", encoded.isEmpty())
        assertNull(F4MemoryBackup.decode(encoded))
    }

    @Test
    fun `an empty document round-trips as empty rather than absent`() {
        val encoded = F4MemoryBackup.encode(ByteArray(0))

        assertArrayEquals(ByteArray(0), F4MemoryBackup.decode(encoded))
        assertFalse(
            "an empty document must not encode as a zero-length file; that is the absent marker's neighbour",
            encoded.contentEquals(ByteArray(0)),
        )
    }

    @Test
    fun `present bytes round-trip byte for byte`() {
        val raw = """{"version":1,"entries":[{"id":"seed","text":"播放周杰伦的稻香"}]}""".toByteArray(Charsets.UTF_8)

        val encoded = F4MemoryBackup.encode(raw)

        assertArrayEquals(raw, F4MemoryBackup.decode(encoded))
        assertFalse("the wrapper is not the raw document", encoded.contentEquals(raw))
    }

    @Test
    fun `absent and empty documents encode differently`() {
        assertFalse(F4MemoryBackup.encode(null).contentEquals(F4MemoryBackup.encode(ByteArray(0))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a corrupt wrapper is refused rather than read as absent`() {
        // Restoring a corrupt backup by treating it as "absent" would delete the user's document. Refusing is the only
        // safe answer; the receiver turns this exception into `refused reason=BACKUP_INVALID` and writes nothing.
        F4MemoryBackup.decode(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown marker is refused`() {
        F4MemoryBackup.decode(byteArrayOf(0x7f))
    }

    @Test
    fun `sha 256 of zero bytes is the known digest`() {
        // Pins the `sha=<hex>` field the backup/restore lines carry. This is the digest the campaign compares against.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            F4MemoryBackup.sha256(ByteArray(0)),
        )
    }
}
