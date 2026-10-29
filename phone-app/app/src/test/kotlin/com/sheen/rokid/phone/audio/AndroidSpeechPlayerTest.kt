package com.sheen.rokid.phone.audio

import android.speech.tts.UtteranceProgressListener
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.phone.session.EchoMuteWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `every playback mutes the phone microphone from onset through the reverb tail`() {
        // 0.5: the answer's speech (not just the wake ack) must be kept out of the phone mic, or the ASR transcribes
        // the assistant's own voice and the continuous-dialogue path answers it in a loop.
        val clock = object : MonotonicClock {
            var now = 0L
            override fun nowNanos(): Long = now
        }
        val mute = EchoMuteWindow(clock)
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, echoMute = mute)
        player.play(hint()) { }
        assertFalse("nothing is muted before the audible onset", mute.covers(clock.now))

        engine.fireStart("h")
        assertTrue("the audible interval is muted", mute.covers(clock.now))

        engine.fireDone("h")
        clock.now += 151_000_000L
        assertFalse("one reverb tail after the end, the mic is live again", mute.covers(clock.now))
    }

    @Test fun `close stops and shuts down the engine`() {
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine)
        player.close()
        assertTrue(engine.stopCount >= 1)
        assertEquals(1, engine.shutdownCount)
    }
}
