package com.sheen.rokid.phone.audio

import android.speech.tts.UtteranceProgressListener
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.phone.session.EchoMuteWindow
import com.sheen.rokid.phone.session.PlaybackGate
import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpeechPlayerTest {

    private class FakeTtsEngine : TtsEngine {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutdownCount = 0
        var speakResult = true
        var listener: UtteranceProgressListener? = null
        override var isLanguageAvailable: Int = 0
        override var initStatus: Int = 0

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text
            return speakResult
        }
        override fun stop() { stopCount++ }
        override fun shutdown() { shutdownCount++ }
        override fun setOnProgressListener(listener: UtteranceProgressListener?) { this.listener = listener }

        fun fireStart(utteranceId: String) { listener?.onStart(utteranceId) }
        fun fireDone(utteranceId: String) { listener?.onDone(utteranceId) }
        fun fireStop(utteranceId: String, interrupted: Boolean = false) { listener?.onStop(utteranceId, interrupted) }
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
        val gate = PlaybackGate()
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

    @Test fun `close closes the echo window after the reverb tail`() {
        // 0.5.1 C5: the player owns the echo window, so closing it must release the phone microphone. The 150 ms
        // reverb tail is kept (`close()`, not an immediate clear): audio already left the speaker may still be in the
        // room. Before the fix a closed player left the window open for the 120 s watchdog, which 0.5.1 Task 2 removed.
        val clock = FakeClock()
        val mute = EchoMuteWindow(clock)
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, echoMute = mute)
        player.play(hint()) { }
        engine.fireStart("h")
        assertTrue("the audible interval is muted", mute.covers(clock.nowNanos()))

        player.close()
        clock.advance(151_000_000L)

        assertFalse("closing the player must leave the mic live after the tail", mute.covers(clock.nowNanos()))
    }

    @Test fun `the engine stopping the utterance ends the playback like a completion`() {
        // `TextToSpeech.stop()`/an interruption delivers `onStop`, not `onDone`/`onError`. Without the override the
        // gate stayed closed and the echo window stayed open (the review's "TTS onStop is not overridden").
        val clock = FakeClock()
        val mute = EchoMuteWindow(clock)
        val gate = PlaybackGate()
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, gate, mute)
        val events = mutableListOf<SpeechPlayback>()
        player.play(hint()) { events += it }
        engine.fireStart("h")

        engine.fireStop("h", interrupted = true)

        assertEquals(
            "an engine stop is a terminal playback state",
            listOf(SpeechPlayback.STARTED, SpeechPlayback.FINISHED),
            events,
        )
        assertFalse("the playback gate must reopen", gate.isPlaying)
        clock.advance(151_000_000L)
        assertFalse("the echo window must close with its reverb tail", mute.covers(clock.nowNanos()))
    }

    @Test fun `close during playback ends the gate and a late engine stop is inert`() {
        val engine = FakeTtsEngine()
        val gate = PlaybackGate()
        val player = AndroidSpeechPlayer(engine, gate)
        val events = mutableListOf<SpeechPlayback>()
        player.play(hint()) { events += it }
        engine.fireStart("h")
        assertTrue(gate.isPlaying)

        player.close()
        assertFalse("closing the player must not leave the wake gate closed", gate.isPlaying)

        // A platform engine may deliver its own stop after `TextToSpeech.stop()`; the closed player must not report a
        // second completion for an utterance it can no longer own.
        engine.fireStop("h")
        assertEquals(
            "a late engine callback for a closed player is inert",
            listOf(SpeechPlayback.STARTED),
            events,
        )
    }

    @Test fun `a start the engine rejects reports failed exactly once`() {
        // 0.5.1 C5: `engine.speak` returning false is a start that never reached PLAYING - no audible interval began
        // and no engine callback will ever arrive. The port still requires exactly one terminal, or the arbiter holds
        // the hint/turn for its 120 s grace.
        val engine = FakeTtsEngine().apply { speakResult = false }
        val gate = PlaybackGate()
        val player = AndroidSpeechPlayer(engine, gate)
        val events = mutableListOf<SpeechPlayback>()

        val handle = player.play(hint()) { events += it }

        assertEquals(
            "a start that never reached PLAYING still owes its caller a terminal exactly once",
            listOf(SpeechPlayback.FAILED),
            events,
        )
        assertFalse("no audible interval began, so the gate must stay open", gate.isPlaying)

        handle.cancel()
        // A misbehaving engine that still delivers a late onset/end must not turn the rejected start into a second
        // terminal: the guard has already settled this playback.
        engine.fireStart("h")
        assertFalse("a late onset of a refused start must not close the gate", gate.isPlaying)
        engine.fireDone("h")
        assertEquals("the terminal is reported once", listOf(SpeechPlayback.FAILED), events)
        assertFalse("and the late callback cannot leave the gate closed", gate.isPlaying)
    }

    @Test fun `a pre-onset cancel settles once and a late onset cannot reopen the gate or echo`() {
        // Review Important #1: a handle cancel landing before the engine's `onStart` used to call finish() while the
        // audible state was still IDLE, so it reported no terminal and marked nothing cancelled. A late `onStart` then
        // CASed IDLE -> PLAYING, closed the wake gate and reopened the echo window for a playback nobody waits for; the
        // next takeover lost its first words and the arbiter could wait SPEECH_EXPIRY_GRACE (120 s).
        val clock = FakeClock()
        val mute = EchoMuteWindow(clock)
        val gate = PlaybackGate()
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, gate, mute)
        val events = mutableListOf<SpeechPlayback>()
        val handle = player.play(hint()) { events += it }

        handle.cancel()
        handle.cancel() // the handle stays idempotent

        assertEquals(
            "a cancel before the audible onset still owes the caller exactly one terminal",
            listOf(SpeechPlayback.FINISHED),
            events,
        )
        assertFalse("nothing became audible, so the gate must stay open", gate.isPlaying)
        assertFalse("and no echo window may be armed", mute.covers(clock.nowNanos()))

        engine.fireStart("h")
        engine.fireDone("h")

        assertEquals(
            "a late onset/done after the cancel must not report anything",
            listOf(SpeechPlayback.FINISHED),
            events,
        )
        assertFalse("a late onset must not close the gate", gate.isPlaying)
        assertFalse("a late onset must not reopen the echo window", mute.covers(clock.nowNanos()))
    }

    @Test fun `a cancelled utterance keeps the reverb tail and then releases the window`() {
        // 0.5.1 C5/T2: a handle cancel (the router's stop path, e.g. the long-press takeover) settles the playback like
        // an end callback - one reverb tail, then live again. The 120 s watchdog no longer exists to hide a missed close.
        val clock = FakeClock()
        val mute = EchoMuteWindow(clock)
        val gate = PlaybackGate()
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, gate, mute)
        val handle = player.play(hint()) { }
        engine.fireStart("h")
        assertTrue("the audible interval is muted", mute.covers(clock.nowNanos()))

        handle.cancel()
        assertTrue("audio already in the room is still covered by the tail", mute.covers(clock.nowNanos()))
        clock.advance(151_000_000L)
        assertFalse("after the tail the microphone is live again", mute.covers(clock.nowNanos()))
    }

    @Test fun `an explicit clear releases the reverb tail immediately`() {
        // 0.5.1 C5: `close()` deliberately keeps the 150 ms tail; turning speech off (or the next manual dictation) must
        // not inherit it, so those paths call `clear()` right after close. This pins the primitive they rely on.
        val clock = FakeClock()
        val mute = EchoMuteWindow(clock)
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, echoMute = mute)
        player.play(hint()) { }
        engine.fireStart("h")

        player.close()
        assertTrue("close keeps the 150 ms reverb tail", mute.covers(clock.nowNanos()))

        mute.clear()
        assertFalse("an explicit clear must not wait for the tail", mute.covers(clock.nowNanos()))
    }

    @Test fun `a late onset after close cannot reopen the echo window`() {
        // The engine delivers `onStart` from its own thread. An utterance requested just before the off switch can have
        // its onset land after `close()`; re-arming the window there would leave it open with no callback left to close
        // it - the permanent mute the 120 s watchdog used to hide and 0.5.1 Task 2 removed.
        val clock = FakeClock()
        val mute = EchoMuteWindow(clock)
        val gate = PlaybackGate()
        val engine = FakeTtsEngine()
        val player = AndroidSpeechPlayer(engine, gate, mute)
        player.play(hint()) { }

        player.close()
        engine.fireStart("h")

        assertFalse("a closed player must ignore a late onset", mute.covers(clock.nowNanos()))
        assertFalse("and must not close the wake gate", gate.isPlaying)
    }
}
