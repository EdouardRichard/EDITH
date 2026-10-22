package com.sheen.rokid.audio.android

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.contracts.*
import org.junit.Assert.*
import org.junit.Test

class SherpaEnginesTest {
    private val epoch = InputEpoch(SessionId("test"), Generation(1), 0)
    private fun frame(first: Long = 0) = PcmFrame(epoch, FloatArray(320), 16000,
        SampleRange("c", first, first + 320), first * 62_500, (first + 320) * 62_500)

    @Test fun asrEmitsNativeTextAndFinishesOnce() {
        val port = RecordingRecognizerPort().apply { partial = "part"; final = "done" }
        val engine = SherpaStreamingAsrEngine(port)
        assertEquals(listOf(AsrHypothesis("part", false)), engine.accept(frame()))
        assertEquals(AsrHypothesis("done", true), engine.finish())
        assertEquals(AsrHypothesis("done", true), engine.finish())
        assertEquals(1, port.finished)
        engine.close()
        assertEquals(1, port.releases)
        assertThrows(IllegalStateException::class.java) { engine.accept(frame()) }
    }

    @Test fun asrFinishPadsTheFinalChunkOnceWithFourHundredMillisecondsOfSilence() {
        val port = RecordingRecognizerPort().apply { final = "done" }
        val engine = SherpaStreamingAsrEngine(port)
        try {
            engine.accept(frame())
            assertEquals(AsrHypothesis("done", true), engine.finish())
            assertEquals("one real frame plus one padded tail", 2, port.accepts)
            assertEquals("0.4 s at 16 kHz", 6400, port.acceptSizes.last())
            assertTrue("the tail must be silence", port.lastAcceptAllZero)
            engine.finish()
            assertEquals("the cached final must not pad a second time", 2, port.accepts)
        } finally { engine.close() }
    }

    @Test fun asrResetClearsDedupAndRejectsInputAfterFinalUntilReset() {
        val port = RecordingRecognizerPort().apply { partial = "same"; final = "first" }
        val engine = SherpaStreamingAsrEngine(port)
        try {
            assertEquals(1, engine.accept(frame()).size)
            assertTrue(engine.accept(frame(320)).isEmpty())
            engine.finish()
            assertThrows(IllegalStateException::class.java) { engine.accept(frame(640)) }
            engine.reset()
            assertEquals(1, port.resets)
            assertEquals(listOf(AsrHypothesis("same", false)), engine.accept(frame()))
            port.final = "second"
            assertEquals(AsrHypothesis("second", true), engine.finish())
        } finally { engine.close() }
    }

    @Test fun malformedFramesDoNotReachNativePort() {
        val port = RecordingRecognizerPort()
        val engine = SherpaStreamingAsrEngine(port)
        try {
            assertThrows(IllegalArgumentException::class.java) { engine.accept(frame().copy(sampleRateHz = 8000)) }
            assertThrows(IllegalArgumentException::class.java) { engine.accept(frame().copy(samples = floatArrayOf(Float.NaN))) }
            assertEquals(0, port.accepts)
        } finally { engine.close() }
    }

    @Test fun vadResetClearsNativeStateAndCloseIsIdempotent() {
        val port = RecordingVadPort()
        val engine = SherpaVadEngine(port)
        engine.accept(frame())
        engine.reset()
        assertEquals(1, port.resets)
        engine.close(); engine.close()
        assertEquals(1, port.releases)
    }

    private class RecordingRecognizerPort : RecognizerPort {
        var partial = ""; var final = ""; var finished = 0; var releases = 0; var resets = 0; var accepts = 0
        val acceptSizes = mutableListOf<Int>()
        var lastAcceptAllZero = false
        override fun accept(samples: FloatArray, sampleRateHz: Int): String {
            accepts++
            acceptSizes += samples.size
            lastAcceptAllZero = samples.isNotEmpty() && samples.all { it == 0f }
            return partial
        }
        override fun finish() = final.also { finished++ }
        override fun reset() { resets++ }
        override fun release() { releases++ }
    }
    private class RecordingVadPort : VadPort {
        var resets = 0; var releases = 0
        override fun accept(samples: FloatArray, sampleRateHz: Int) = VadDecision(true, false)
        override fun reset() { resets++ }
        override fun release() { releases++ }
    }
}
