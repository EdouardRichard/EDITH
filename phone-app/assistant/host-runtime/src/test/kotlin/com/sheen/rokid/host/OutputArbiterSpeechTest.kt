package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputArbiterSpeechTest {
    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
    private val device = RecordingHintDevice()
    private var identity: Pair<SessionId, Generation>? = SessionId("s") to Generation(1)
    private val states = mutableListOf<Pair<String, OutputStatus>>()

    private class RecordingSpeechPlayer : SpeechPlayer {
        val played = mutableListOf<String>()
        val events = mutableListOf<String>()
        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            played += assistance.hintId
            events += "play:${assistance.hintId}"
            onState(SpeechPlayback.STARTED)
            return object : CancellationHandle {
                var cancelled = false
                override fun cancel() {
                    if (!cancelled) {
                        cancelled = true
                        events += "stop:${assistance.hintId}"
                    }
                }
            }
        }
    }

    private fun hint(id: String, priority: Int = 1, expiry: Long = 100, speech: SpeechRequest? = null) =
        Assistance(id, SessionId("s"), Generation(1), "turn-$id", priority, "text-$id", speech, expiry)

    private fun arbiterWith(speech: SpeechPlayer?) = OutputArbiter(clock, scheduler, device, speech, { identity }) { h, s ->
        states += h.hintId to s
    }

    @Test fun `the speaker is asked exactly when the hint goes on screen`() {
        val speech = RecordingSpeechPlayer()
        val arbiter = arbiterWith(speech)
        arbiter.submit(hint("h", speech = SpeechRequest()))
        assertEquals(listOf("h"), speech.played)
        // device.show must have been called before speech.play
        assertTrue(device.shown.isNotEmpty())
        assertEquals("h", device.shown.first().first.hintId)
    }

    @Test fun `a queued hint is not spoken until it is shown`() {
        val speech = RecordingSpeechPlayer()
        val arbiter = arbiterWith(speech)
        arbiter.submit(hint("first", priority = 2, speech = SpeechRequest()))
        arbiter.submit(hint("second", priority = 1, speech = SpeechRequest()))
        // only first should be spoken
        assertEquals(listOf("first"), speech.played)
        // now remove first => second gets shown and spoken
        arbiter.cancelTurn("turn-first")
        assertEquals(listOf("first", "second"), speech.played)
    }

    @Test fun `replacing a hint stops the previous speech first`() {
        // Use a shared timeline to verify stop happens BEFORE the second play (and before device.clear).
        val timeline = mutableListOf<String>()
        val speech = object : SpeechPlayer {
            override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
                timeline += "play:${assistance.hintId}"
                onState(SpeechPlayback.STARTED)
                return object : CancellationHandle {
                    var cancelled = false
                    override fun cancel() { if (!cancelled) { cancelled = true; timeline += "stop:${assistance.hintId}" } }
                }
            }
        }
        val timelineDevice = object : HintDevice {
            override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
                timeline += "show:${assistance.hintId}"
                return FakeCancellation()
            }
            override fun clear(sessionId: SessionId, generation: Generation) { timeline += "clear" }
        }
        val arbiter = OutputArbiter(clock as MonotonicClock, scheduler, timelineDevice, speech, { identity }) { _, _ -> }
        arbiter.submit(hint("first", speech = SpeechRequest()))
        arbiter.submit(hint("first").copy(text = "updated", optionalSpeech = SpeechRequest()))
        // stop:first must appear before the second play:first
        val stopIdx = timeline.indexOf("stop:first")
        val secondPlayIdx = timeline.lastIndexOf("play:first")
        assertTrue(stopIdx in 0 until secondPlayIdx, "expected stop before second play, got $timeline")
    }

    @Test fun `expiry stops speech and clears the screen`() {
        val timeline = mutableListOf<String>()
        val speech = object : SpeechPlayer {
            override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
                timeline += "play:${assistance.hintId}"
                onState(SpeechPlayback.STARTED)
                return object : CancellationHandle {
                    var cancelled = false
                    override fun cancel() { if (!cancelled) { cancelled = true; timeline += "stop:${assistance.hintId}" } }
                }
            }
        }
        val timelineDevice = object : HintDevice {
            override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
                timeline += "show:${assistance.hintId}"
                return FakeCancellation()
            }
            override fun clear(sessionId: SessionId, generation: Generation) { timeline += "clear" }
        }
        val arbiter = OutputArbiter(clock as MonotonicClock, scheduler, timelineDevice, speech, { identity }) { _, _ -> }
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))
        clock.advance(50); scheduler.runDue()
        // stop must come before clear
        val stopIdx = timeline.indexOf("stop:h")
        val clearIdx = timeline.indexOf("clear")
        assertTrue(stopIdx >= 0, "expected stop event, got $timeline")
        assertTrue(clearIdx >= 0, "expected clear, got $timeline")
        assertTrue(stopIdx < clearIdx, "expected stop before clear, got $timeline")
    }

    @Test fun `a refused hint is never spoken`() {
        val speech = RecordingSpeechPlayer()
        val arbiter = arbiterWith(speech)
        // expired hint => Rejected, no speech
        arbiter.submit(hint("old", expiry = 0, speech = SpeechRequest()))
        assertEquals(emptyList(), speech.played)
    }

    @Test fun `a hint without speech does not call the player`() {
        val speech = RecordingSpeechPlayer()
        val arbiter = arbiterWith(speech)
        arbiter.submit(hint("plain")) // no optionalSpeech
        assertEquals(emptyList(), speech.played)
        assertEquals(1, device.shown.size) // still displayed
    }

    @Test fun `a speech hint still displays without a player`() {
        // Spec §S2: speech capability missing => hint still shows, just not spoken. Must NOT become Rejected.
        val arbiter = arbiterWith(null) // no speech player
        val status = arbiter.submit(hint("h", speech = SpeechRequest()))
        assertEquals(OutputStatus.Accepted, status)
        assertEquals(1, device.shown.size)
    }

    @Test fun `a speech hint without a player does not attempt playback`() {
        val arbiter = arbiterWith(null)
        arbiter.submit(hint("h", speech = SpeechRequest()))
        // No exception, no crash; device.show called normally
        assertEquals(1, device.shown.size)
    }

    // ---------------------------------------------------------------- 0.24 S3 Task 14: speech completion

    private val speeches = mutableListOf<Pair<String, SpeechResult>>()

    /**
     * A player whose terminal callback the test fires by hand.
     *
     * The arbiter posts the callback to its own serial executor, so [done] runs the test's later `clear` as a
     * barrier: every task queued before it (including the posted callback) has run when `clear` returns.
     */
    private class ControlledSpeechPlayer : SpeechPlayer {
        val played = mutableListOf<String>()
        val callbacks = mutableMapOf<String, (SpeechPlayback) -> Unit>()
        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            played += assistance.hintId
            callbacks[assistance.hintId] = onState
            onState(SpeechPlayback.STARTED)
            return object : CancellationHandle { override fun cancel() = Unit }
        }
        fun terminal(hintId: String, state: SpeechPlayback) { callbacks.getValue(hintId)(state) }
    }

    private fun speechArbiter(player: SpeechPlayer): OutputArbiter = OutputArbiter(
        clock,
        scheduler,
        device,
        player,
        { identity },
        { hint, status -> states += hint.hintId to status },
        { hint, result -> speeches += hint.hintId to result },
    )

    private fun drain(arbiter: OutputArbiter) {
        // A command on the arbiter's serial executor: anything posted before it has executed when it returns.
        arbiter.clear(SessionId("s"), Generation(1))
    }

    @Test fun `a finished playback reports the hint and result exactly once`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("h", speech = SpeechRequest()))

        speech.terminal("h", SpeechPlayback.FINISHED)
        // A player that violates its own "exactly once" contract must not continue the conversation twice.
        speech.terminal("h", SpeechPlayback.FINISHED)
        drain(arbiter)

        assertEquals(listOf("h" to SpeechResult.FINISHED), speeches)
    }

    @Test fun `a failed playback reports FAILED`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("h", speech = SpeechRequest()))

        speech.terminal("h", SpeechPlayback.FAILED)
        drain(arbiter)

        assertEquals(listOf("h" to SpeechResult.FAILED), speeches, "a failed playback is still a terminal result")
    }

    @Test fun `started alone is not a completion`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("h", speech = SpeechRequest()))

        // `play` reported STARTED synchronously; no FINISHED/FAILED has arrived.
        drain(arbiter)

        assertEquals(emptyList(), speeches)
    }

    @Test fun `a hint without speech never reports a completion`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("plain"))

        drain(arbiter)

        assertEquals(emptyList(), speeches, "there was no playback to finish")
    }

    @Test fun `a completion for a hint that left the screen is not reported`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("h", speech = SpeechRequest()))
        arbiter.clear(SessionId("s"), Generation(1))

        // What a player reports when its playback is cancelled after the hint was cleared.
        speech.terminal("h", SpeechPlayback.FINISHED)
        drain(arbiter)

        assertEquals(emptyList(), speeches, "a cancelled hint's playback end must not drive the conversation")
    }

    @Test fun `a replaced hint's finished playback is not reported`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("first", priority = 2, speech = SpeechRequest()))
        // A higher-priority hint takes the screen; the first item is removed and its speech handle cancelled.
        arbiter.submit(hint("second", priority = 3, speech = SpeechRequest()))

        speech.terminal("first", SpeechPlayback.FINISHED)
        drain(arbiter)

        assertEquals(emptyList(), speeches, "only the hint that is still on screen may report its speech end")
    }
}
