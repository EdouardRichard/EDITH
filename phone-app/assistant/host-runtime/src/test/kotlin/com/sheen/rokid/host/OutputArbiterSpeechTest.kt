package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * 0.25 Task 20 / R114–R116: a player whose terminal state the test fires by hand and whose play/stop calls are
     * recorded on a shared timeline, so "did the expiry stop the utterance?" and "which came first, speech end or
     * screen clear?" are both assertable.
     */
    private class TimelineSpeechPlayer(private val timeline: MutableList<String>) : SpeechPlayer {
        private val callbacks = mutableMapOf<String, (SpeechPlayback) -> Unit>()
        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            timeline += "play:${assistance.hintId}"
            callbacks[assistance.hintId] = onState
            onState(SpeechPlayback.STARTED)
            return object : CancellationHandle {
                private var done = false
                override fun cancel() {
                    if (done) return
                    done = true
                    timeline += "stop:${assistance.hintId}"
                }
            }
        }
        fun terminal(hintId: String, state: SpeechPlayback) { callbacks.getValue(hintId)(state) }
    }

    /** A device whose show/clear calls land on the same timeline as the playback. */
    private class TimelineHintDevice(private val timeline: MutableList<String>) : HintDevice {
        override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
            timeline += "show:${assistance.hintId}"
            return FakeCancellation()
        }
        override fun clear(sessionId: SessionId, generation: Generation) { timeline += "clear" }
    }

    /** Display states, forwarded speech ends and device calls all in one ordered timeline. */
    private fun timelineArbiter(timeline: MutableList<String>, player: SpeechPlayer): OutputArbiter = OutputArbiter(
        clock,
        scheduler,
        TimelineHintDevice(timeline),
        player,
        { identity },
        { hint, status -> timeline += "state:${hint.hintId}:${status.name}" },
        { hint, result -> timeline += "speech:${hint.hintId}:${result.name}" },
    )

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
        // stop:first must appear before the second play:first, and the interrupt clears between them
        val stopIdx = timeline.indexOf("stop:first")
        val clearIdx = timeline.indexOf("clear")
        val secondPlayIdx = timeline.lastIndexOf("play:first")
        assertTrue(stopIdx in 0 until secondPlayIdx, "expected stop before second play, got $timeline")
        assertTrue(clearIdx in (stopIdx + 1) until secondPlayIdx, "expected stop, clear, then the new play, got $timeline")
    }

    // ---------------------------------------------------------------- 0.25 Task 20 / R114–R117: natural expiry

    @Test fun `display expiry while speech is playing neither stops nor clears until FINISHED`() {
        val timeline = mutableListOf<String>()
        val speech = TimelineSpeechPlayer(timeline)
        val arbiter = timelineArbiter(timeline, speech)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))

        clock.advance(50); scheduler.runDue()

        // R114: the natural display deadline must not cut an in-flight utterance or take the hint off screen. This is
        // the user's defect: a 324-codepoint answer whose TTS is still speaking when the display budget ends.
        assertFalse("stop:h" in timeline, "the display expiry cancelled in-flight speech: $timeline")
        assertFalse("clear" in timeline, "the hint left the screen while it was still speaking: $timeline")
        assertFalse("state:h:Expired" in timeline, "the display expiry reported the hint as gone: $timeline")

        speech.terminal("h", SpeechPlayback.FINISHED)
        drain(arbiter)

        // R116: the terminal is forwarded while the hint is still on screen, then the screen clears.
        assertTrue("speech:h:FINISHED" in timeline, "the playback end was dropped: $timeline")
        val speechIdx = timeline.indexOf("speech:h:FINISHED")
        val clearIdx = timeline.indexOf("clear")
        assertTrue(speechIdx >= 0 && clearIdx > speechIdx, "expected the speech end before the clear, got $timeline")
        assertEquals(1, timeline.count { it == "clear" }, "expected exactly one clear, got $timeline")
        assertTrue("state:h:Expired" in timeline, "the deferred display expiry ends as Expired, got $timeline")
        assertFalse("stop:h" in timeline, "a naturally finished utterance must not be cancelled: $timeline")
    }

    @Test fun `the playback end is reported before the next hint takes the screen`() {
        val timeline = mutableListOf<String>()
        val speech = TimelineSpeechPlayer(timeline)
        val arbiter = timelineArbiter(timeline, speech)
        arbiter.submit(hint("answer", priority = 2, expiry = 50, speech = SpeechRequest()))
        assertEquals(OutputStatus.Queued, arbiter.submit(hint("next", priority = 1, expiry = 1_000)))
        clock.advance(50); scheduler.runDue()

        speech.terminal("answer", SpeechPlayback.FINISHED)
        drain(arbiter)

        val speechIdx = timeline.indexOf("speech:answer:FINISHED")
        val clearIdx = timeline.indexOf("clear")
        val nextShowIdx = timeline.indexOf("show:next")
        assertTrue(speechIdx >= 0 && clearIdx > speechIdx, "expected the speech end before the clear, got $timeline")
        assertTrue(nextShowIdx > clearIdx, "the next hint takes the screen after the clear, got $timeline")
    }

    @Test fun `a long playback that outlives the display budget is neither stopped nor cleared`() {
        val timeline = mutableListOf<String>()
        val speech = TimelineSpeechPlayer(timeline)
        val arbiter = timelineArbiter(timeline, speech)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))
        clock.advance(50); scheduler.runDue()

        // Ten seconds past the display budget: a long answer still playing, which the old arbiter cancelled.
        clock.advance(10_000L * 1_000_000L); scheduler.runDue()

        assertFalse("stop:h" in timeline, "a long answer was cut at its display budget: $timeline")
        assertFalse("clear" in timeline, "a long answer left the screen before it finished: $timeline")

        speech.terminal("h", SpeechPlayback.FINISHED)
        drain(arbiter)

        assertEquals(1, timeline.count { it == "clear" }, "expected one clear after playback, got $timeline")
        assertTrue("speech:h:FINISHED" in timeline, "the long answer's end was not forwarded: $timeline")
        assertFalse("stop:h" in timeline, "the answer played to its natural end: $timeline")
    }

    @Test fun `a playback with no terminal state is stopped and cleared at the bounded grace`() {
        val timeline = mutableListOf<String>()
        val speech = TimelineSpeechPlayer(timeline)
        val arbiter = timelineArbiter(timeline, speech)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))
        clock.advance(50); scheduler.runDue()

        // One nanosecond before R114's 120 s grace bound (pinned as a literal so a silent constant change fails here):
        // still held, so the screen is not unbounded either.
        clock.advance(120_000L * 1_000_000L - 1); scheduler.runDue()
        assertFalse("stop:h" in timeline, "the grace ended a nanosecond too early: $timeline")
        assertFalse("clear" in timeline, "the grace ended a nanosecond too early: $timeline")

        clock.advance(1); scheduler.runDue()

        val stopIdx = timeline.indexOf("stop:h")
        val clearIdx = timeline.indexOf("clear")
        assertTrue(stopIdx >= 0 && clearIdx > stopIdx, "the grace fallback must stop speech before clearing, got $timeline")
        assertTrue("state:h:Expired" in timeline, "the grace fallback ends the hint as Expired, got $timeline")
        assertFalse("speech:h:FINISHED" in timeline, "no terminal state was reported")
    }

    @Test fun `a failed playback after the display budget is forwarded before the screen clears`() {
        val timeline = mutableListOf<String>()
        val speech = TimelineSpeechPlayer(timeline)
        val arbiter = timelineArbiter(timeline, speech)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))
        clock.advance(50); scheduler.runDue()

        speech.terminal("h", SpeechPlayback.FAILED)
        drain(arbiter)

        assertTrue("speech:h:FAILED" in timeline, "a failed playback is still a terminal result: $timeline")
        val speechIdx = timeline.indexOf("speech:h:FAILED")
        val clearIdx = timeline.indexOf("clear")
        assertTrue(speechIdx >= 0 && clearIdx > speechIdx, "expected the failure before the clear, got $timeline")
        assertFalse("stop:h" in timeline, "the failed utterance is already over: $timeline")
    }

    @Test fun `cancelling the turn after expiry still stops speech before clearing`() {
        val timeline = mutableListOf<String>()
        val speech = TimelineSpeechPlayer(timeline)
        val arbiter = timelineArbiter(timeline, speech)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))
        clock.advance(50); scheduler.runDue()

        arbiter.cancelTurn("turn-h")

        val stopIdx = timeline.indexOf("stop:h")
        val clearIdx = timeline.indexOf("clear")
        assertTrue(stopIdx >= 0 && clearIdx > stopIdx, "an interrupt must stop speech before clearing, got $timeline")
        assertFalse("speech:h:FINISHED" in timeline, "an interrupted playback is not a natural finish: $timeline")
        assertTrue("state:h:Rejected" in timeline, "the turn cancellation rejects the hint, got $timeline")
    }

    @Test fun `a hint without speech is still cleared at its display expiry`() {
        val speech = RecordingSpeechPlayer()
        val arbiter = arbiterWith(speech)
        arbiter.submit(hint("plain", expiry = 50))

        clock.advance(50); scheduler.runDue()

        assertEquals(1, device.clears.size, "a hint with nothing to say must leave at its display budget")
        assertEquals("plain" to OutputStatus.Expired, states.last())
        assertEquals(emptyList(), speech.played)
    }

    @Test fun `a speech hint without a player is still cleared at its display expiry`() {
        val arbiter = arbiterWith(null)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))

        clock.advance(50); scheduler.runDue()

        assertEquals(1, device.clears.size, "no player means there is no playback to wait for")
        assertEquals("h" to OutputStatus.Expired, states.last())
    }

    @Test fun `a playback that ends before the display budget keeps the hint until expiry`() {
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("h", expiry = 100, speech = SpeechRequest()))

        speech.terminal("h", SpeechPlayback.FINISHED)
        drain(arbiter)

        assertEquals(listOf("h" to SpeechResult.FINISHED), speeches)
        assertEquals(0, device.clears.size, "a short playback must not shorten the display budget")

        clock.advance(100); scheduler.runDue()
        assertEquals(1, device.clears.size)
        assertEquals("h" to OutputStatus.Expired, states.last())
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
     * The arbiter posts the callback to its own serial executor, so [drain] runs a no-op command as a barrier: every
     * task queued before it (including the posted callback) has run when it returns.
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
        // A no-op command on the arbiter's serial executor: anything posted before it has executed when it returns.
        // `cancelHint` is used instead of `clear` so the barrier cannot itself clear the hint or post a status.
        arbiter.cancelHint("barrier-turn", "barrier-hint")
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

    @Test fun `a playback end after the deadline is still reported and the queued expiry then clears`() {
        // The device may report FINISHED after the hint's deadline but before the scheduled `expire` task runs (the
        // two run on different threads). R116: the hint is still displayed while its speech is in flight, so the
        // terminal must be forwarded, not dropped as "already gone"; the due display deadline then clears the screen.
        val speech = ControlledSpeechPlayer()
        val arbiter = speechArbiter(speech)
        arbiter.submit(hint("h", expiry = 50, speech = SpeechRequest()))
        clock.advance(50) // the deadline is due; the scheduler has not released it

        speech.terminal("h", SpeechPlayback.FINISHED)
        drain(arbiter)

        assertEquals(
            listOf("h" to SpeechResult.FINISHED),
            speeches,
            "an answer that outlived its display budget still reports its speech end",
        )

        scheduler.runDue()
        assertEquals(1, device.clears.size, "the due display deadline clears the held hint afterwards")
        assertEquals("h" to OutputStatus.Expired, states.last())
    }
}
