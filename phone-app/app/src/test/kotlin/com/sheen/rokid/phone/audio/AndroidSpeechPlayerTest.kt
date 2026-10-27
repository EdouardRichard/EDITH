package com.sheen.rokid.phone.audio

import android.speech.tts.UtteranceProgressListener
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.SpeechPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpeechPlayerTest {

    private class FakeTtsEngine : TtsEngine {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutdownCount = 0
        var listener: UtteranceProgressListener? = null
        override var isLanguageAvailable: Int = 0
        override var initStatus: Int = 0

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text
            return true
        }
        override fun stop() { stopCount++ }
        override fun shutdown() { shutdownCount++ }
        override fun setOnProgressListener(listener: UtteranceProgressListener?) { this.listener = listener }

        fun fireStart(utteranceId: String) { listener?.onStart(utteranceId) }
        fun fireDone(utteranceId: String) { listener?.onDone(utteranceId) }
        @Suppress("DEPRECATION")
        fun fireError(utteranceId: String) { listener?.onError(utteranceId) }
    }

    private fun hint(id: String = "h", text: String = "hello") =
        Assistance(id, SessionId("s"), Generation(1), "t", 1, text, null, Long.MAX_VALUE)

    @Test fun `begin and end are paired on normal completion`() {
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine)
        val events = mutableListOf<SpeechPlayback>()
        player.play(hint()) { events += it }
        engine.fireStart("h")
        engine.fireDone("h")
        assertEquals(listOf(SpeechPlayback.STARTED, SpeechPlayback.FINISHED), events)
    }

    @Test fun `cancel is idempotent and reports finished once`() {
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine)
        val events = mutableListOf<SpeechPlayback>()
        val handle = player.play(hint()) { events += it }
        engine.fireStart("h")
        handle.cancel()
        handle.cancel() // second cancel must be a no-op
        assertEquals(listOf(SpeechPlayback.STARTED, SpeechPlayback.FINISHED), events)
        assertTrue(engine.stopCount >= 1)
    }

    @Test fun `onError reports failed and ends the gate`() {
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine)
        val events = mutableListOf<SpeechPlayback>()
        player.play(hint()) { events += it }
        engine.fireStart("h")
        engine.fireError("h")
        assertEquals(listOf(SpeechPlayback.STARTED, SpeechPlayback.FAILED), events)
    }

    @Test fun `speakText speaks the exact detached acknowledgement and gates it`() {
        val engine = FakeTtsEngine()
        val gate = com.sheen.rokid.phone.session.PlaybackGate()
        val player = AndroidSpeechPlayer(engine, gate)
        val events = mutableListOf<SpeechPlayback>()
        assertTrue(player.speakText("我在", "detached-wake-ack") { events += it })
        assertEquals(listOf("我在"), engine.spoken)
        engine.fireStart("detached-wake-ack")
        assertTrue(gate.isPlaying)
        engine.fireDone("detached-wake-ack")
        assertTrue(!gate.isPlaying)
        assertEquals(listOf(SpeechPlayback.STARTED, SpeechPlayback.FINISHED), events)
    }

    @Test fun `close stops and shuts down the engine`() {
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine)
        player.close()
        assertTrue(engine.stopCount >= 1)
        assertEquals(1, engine.shutdownCount)
    }
}
