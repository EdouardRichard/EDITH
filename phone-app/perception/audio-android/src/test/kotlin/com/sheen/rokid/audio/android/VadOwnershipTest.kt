package com.sheen.rokid.audio.android

import com.sheen.rokid.audio.VadDecision
import org.junit.Assert.*
import org.junit.Test

class VadOwnershipTest {
    @Test fun endpointDrainsCompletedSegmentsAndResetClearsPendingSpeech() {
        val native = FakeVad()
        val port = OwnedVadPort(native)
        native.completed = 2
        assertEquals(VadDecision(true, true), port.accept(floatArrayOf(0f), 16000))
        assertEquals(0, native.completed)
        assertEquals(VadDecision(true, false), port.accept(floatArrayOf(0f), 16000))
        port.reset()
        assertEquals(VadDecision(false, false), port.accept(floatArrayOf(0f), 16000))
        port.release(); port.release()
        assertEquals(1, native.releases)
        assertThrows(IllegalStateException::class.java) { port.reset() }
    }
    private class FakeVad : VadOwner {
        var completed = 0
        var speech = true
        var releases = 0
        override fun accept(samples: FloatArray) {}
        override fun speechDetected() = speech
        override fun empty() = completed == 0
        override fun pop() { completed-- }
        override fun reset() { speech = false; completed = 0 }
        override fun release() { releases++ }
    }
}
