package com.sheen.rokid.phone.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmentCaptureTest {

    private fun capture(maxMillis: Int = 30_000, maxBytes: Int = 2_000_000) =
        AudioSegmentCapture(maxMillis, maxBytes)

    @Test fun `no speech segment returns null on seal`() {
        val cap = capture()
        cap.offer(byteArrayOf(1, 2), isSpeech = false)
        assertNull(cap.seal("t1"))
    }

    @Test fun `speech segment is captured and sealed`() {
        val cap = capture()
        cap.offer(byteArrayOf(1, 2), isSpeech = true)
        cap.offer(byteArrayOf(3, 4), isSpeech = true)
        cap.offer(byteArrayOf(5), isSpeech = false) // silence ends the segment
        val seg = cap.seal("t1")
        assertNotNull(seg)
        assertEquals("t1", seg!!.turnId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), seg.bytes)
        assertEquals(false, seg.truncated)
    }

    @Test fun `only the latest segment is kept`() {
        val cap = capture()
        // First segment
        cap.offer(byteArrayOf(1), isSpeech = true)
        cap.offer(byteArrayOf(0), isSpeech = false)
        // Second segment overwrites
        cap.offer(byteArrayOf(2), isSpeech = true)
        cap.offer(byteArrayOf(0), isSpeech = false)
        val seg = cap.seal("t1")
        assertNotNull(seg)
        assertArrayEquals(byteArrayOf(2), seg!!.bytes)
    }

    @Test fun `oversized segment is truncated with zero bytes`() {
        val cap = capture(maxBytes = 4)
        cap.offer(byteArrayOf(1, 2, 3), isSpeech = true)
        cap.offer(byteArrayOf(4, 5, 6), isSpeech = true) // total 6 > maxBytes 4
        cap.offer(byteArrayOf(0), isSpeech = false)
        val seg = cap.seal("t1")
        assertNotNull(seg)
        assertTrue(seg!!.truncated)
        assertEquals(0, seg.bytes.size)
    }

    @Test fun `release makes subsequent seal return null`() {
        val cap = capture()
        cap.offer(byteArrayOf(1), isSpeech = true)
        cap.offer(byteArrayOf(0), isSpeech = false)
        cap.release("t1")
        assertNull(cap.seal("t1"))
    }

    @Test fun `speech before first silence does not start capture`() {
        val cap = capture()
        // Leading silence should not affect anything
        cap.offer(byteArrayOf(0), isSpeech = false)
        cap.offer(byteArrayOf(1), isSpeech = true)
        cap.offer(byteArrayOf(0), isSpeech = false)
        val seg = cap.seal("t1")
        assertNotNull(seg)
        assertArrayEquals(byteArrayOf(1), seg!!.bytes)
    }
}
