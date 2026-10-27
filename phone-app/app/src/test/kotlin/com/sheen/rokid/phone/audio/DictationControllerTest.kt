package com.sheen.rokid.phone.audio

import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.contracts.SessionStateEvent
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 12b-i (spec §5.3, D204): the dictation controller, on the JVM.
 *
 * The controller is the **UI-side one-shot lease**: it installs a redirect on the shared [DictationPipeline] while
 * the phone microphone dictates, takes exactly one final transcript for the input field, and puts the wake route back.
 * These tests pin the rules a device run cannot prove by watching the screen:
 *
 * - **the final goes to the field and never to the host.** A final `TranscriptEvent` is consumed by the controller;
 *   `forward` is for the non-transcript events (session/fault/speaker), and partial transcripts - also dictation
 *   content - are dropped rather than handed to a host that would route them to the plugin.
 * - **one final, one state change.** The terminal transition (final, no speech, timeout, cancel) happens once, under
 *   one lock; a second final, a late in-flight event, or a repeated stop/cancel cannot repeat it.
 * - **silence is a visible outcome.** Stop with no turn, a blank final, the 15 s deadline and cancel each end the
 *   lease; the final-bearing paths say `Idle`, the no-result paths say `NoSpeech`, a failed finish says `Error`.
 * - **the turn is not left behind.** Timeout/cancel seal the active pipeline turn while the redirect is still
 *   installed, so the discarded final cannot surface later on the restored wake route.
 * - **close is final.** After it the redirect is restored, no callback is delivered for a late event, and `start`
 *   answers `Unavailable`.
 *
 * No Android APIs and no real scheduler: [ManualScheduler]/[FakeClock] drive the deadline, and [FakePipeline] records
 * redirects and plays the part of `SpeechPipeline.redirectEmit`/`finishCurrentTurn`.
 */
class DictationControllerTest {

    private val envelope = EventEnvelope(SessionId("s-dictation"), Generation(1), "e-dictation", 0, 1_000_000)

    private fun transcript(text: String, final: Boolean, revision: Long = 0): TranscriptEvent =
        TranscriptEvent(TranscriptRevision(
            envelope = envelope,
            turnId = "turn-dictation",
            revision = revision,
            text = text,
            final = final,
            startNanos = 0,
            endNanos = 1_000_000,
        ))

    /**
     * The production adapter (Task 12b-ii) maps [redirect] to `SpeechPipeline.redirectEmit` and [finishCurrentTurn]
     * to the pipeline's forced final. This fake keeps the current sink so a test can emit from "the pipeline thread",
     * and records every redirect so restore can be asserted.
     */
    private class FakePipeline : DictationPipeline {
        private var current: ((HostEvent) -> Unit)? = null
        val redirects = mutableListOf<((HostEvent) -> Unit)?>()
        var finishCalls = 0
            private set
        var finishResult = false
        var finishFailure: Exception? = null
        var finishAction: (() -> Unit)? = null

        val sink: ((HostEvent) -> Unit)? get() = current
        val installations: Int get() = redirects.count { it != null }

        override fun redirect(sink: ((HostEvent) -> Unit)?) {
            redirects += sink
            current = sink
        }

        override fun finishCurrentTurn(): Boolean {
            finishCalls++
            finishFailure?.let { throw it }
            finishAction?.invoke()
            return finishResult
        }

        fun emit(event: HostEvent) {
            current?.invoke(event)
        }
    }

    private inner class Rig(timeoutMillis: Long = 15_000) {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val pipeline = FakePipeline()
        val finals = mutableListOf<String>()
        val states = mutableListOf<DictationState>()
        val forwarded = mutableListOf<HostEvent>()

        /** The callback order as strings, so "state before final" can be asserted without relying on list coincidence. */
        val order = mutableListOf<String>()

        val controller = DictationController(
            pipeline = pipeline,
            scheduler = scheduler,
            clock = clock,
            timeoutMillis = timeoutMillis,
            onFinal = { text ->
                finals += text
                order += "final:$text"
            },
            onState = { state ->
                states += state
                order += "state:${state.label()}"
            },
            forward = { event ->
                forwarded += event
                order += "forward"
            },
        )

        fun start() = controller.start()

        fun advance(millis: Long) {
            clock.advance(millis * 1_000_000L)
            scheduler.runDue()
        }
    }

    private fun DictationState.label(): String = when (this) {
        DictationState.Idle -> "idle"
        DictationState.Listening -> "listening"
        DictationState.NoSpeech -> "no-speech"
        is DictationState.Error -> "error:$code"
    }

    // ---- start and the normal final -------------------------------------------------------------------------------

    @Test
    fun `start installs the redirect and reports listening`() {
        val rig = Rig()

        assertEquals(DictationController.StartResult.Accepted, rig.start())

        assertNotNull("the pipeline must emit to the controller while dictating", rig.pipeline.sink)
        assertEquals(1, rig.pipeline.installations)
        assertEquals(listOf<DictationState>(DictationState.Listening), rig.states)
        assertTrue("start must not forward anything", rig.forwarded.isEmpty())
    }

    @Test
    fun `a final transcript is delivered once and never forwarded to the host`() {
        val rig = Rig()
        rig.start()
        val sink = requireNotNull(rig.pipeline.sink)

        sink(transcript("dictated text", final = true))

        assertEquals(listOf("dictated text"), rig.finals)
        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.Idle), rig.states)
        assertEquals(
            "the field is filled only after the controller has left Listening",
            listOf("state:listening", "state:idle", "final:dictated text"),
            rig.order,
        )
        assertTrue("a dictation final must not reach the host route", rig.forwarded.isEmpty())
        assertNull("the wake route is back as soon as the final is consumed", rig.pipeline.sink)

        // A duplicate final (same revision re-emitted, or an event already in flight) is not a second dictation.
        sink(transcript("dictated text", final = true))
        assertEquals(listOf("dictated text"), rig.finals)
        assertEquals(2, rig.states.size)
    }

    @Test
    fun `non-transcript events are forwarded while partial transcripts are dropped`() {
        val rig = Rig()
        rig.start()
        val sink = requireNotNull(rig.pipeline.sink)
        val state = SessionStateEvent(envelope, SessionState.Listening)
        val partial = transcript("dictated", final = false, revision = 1)

        sink(state)
        sink(partial)

        assertEquals(
            "the host needs session/fault/speaker events, but partial dictation text stays in the controller",
            listOf(state),
            rig.forwarded,
        )
        assertEquals(listOf<DictationState>(DictationState.Listening), rig.states)
        assertTrue(rig.finals.isEmpty())
    }

    // ---- stop -----------------------------------------------------------------------------------------------------

    @Test
    fun `stop finishes the active turn and delivers its final exactly once`() {
        val rig = Rig()
        rig.start()
        rig.pipeline.finishResult = true
        rig.pipeline.finishAction = { rig.pipeline.emit(transcript("dictated by stop", final = true)) }

        rig.controller.stop()

        assertEquals(1, rig.pipeline.finishCalls)
        assertEquals(listOf("dictated by stop"), rig.finals)
        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.Idle), rig.states)
        assertTrue("the forced final is not forwarded either", rig.forwarded.isEmpty())
        assertNull(rig.pipeline.sink)

        rig.controller.stop()

        assertEquals("a repeated stop must not finish a turn again", 1, rig.pipeline.finishCalls)
        assertEquals(1, rig.finals.size)
        assertEquals(2, rig.states.size)
    }

    @Test
    fun `stop without an active turn ends with no speech`() {
        val rig = Rig()
        rig.start()

        rig.controller.stop()

        assertEquals("stop asks the pipeline to finish exactly once", 1, rig.pipeline.finishCalls)
        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.NoSpeech), rig.states)
        assertTrue(rig.finals.isEmpty())
        assertNull(rig.pipeline.sink)
    }

    @Test
    fun `a finish failure is reported as an error and still restores the route`() {
        val rig = Rig()
        rig.start()
        rig.pipeline.finishFailure = IllegalStateException("asr died during finish")

        rig.controller.stop()

        assertEquals(
            listOf<DictationState>(
                DictationState.Listening,
                DictationState.Error(DictationController.ERROR_STOP_FAILED),
            ),
            rig.states,
        )
        assertTrue(rig.finals.isEmpty())
        assertNull(rig.pipeline.sink)
        assertEquals(DictationController.StartResult.Accepted, rig.start())
    }

    // ---- timeout --------------------------------------------------------------------------------------------------

    @Test
    fun `the fifteen second deadline ends with no speech and no final`() {
        val rig = Rig()
        rig.start()
        val lateSink = requireNotNull(rig.pipeline.sink)

        rig.advance(15_000)

        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.NoSpeech), rig.states)
        assertTrue("a timeout is not a transcript", rig.finals.isEmpty())
        assertTrue(rig.forwarded.isEmpty())
        assertNull(rig.pipeline.sink)
        assertEquals("the timed-out turn is sealed so it cannot finalise later", 1, rig.pipeline.finishCalls)

        // The deadline is one-shot: the timer is not left armed for the next dictation, and a late event is ignored.
        lateSink(transcript("too late", final = true))
        assertEquals(2, rig.states.size)
        assertTrue(rig.finals.isEmpty())
    }

    @Test
    fun `the deadline is canceled once a final arrives`() {
        val rig = Rig()
        rig.start()
        rig.pipeline.emit(transcript("in time", final = true))

        rig.advance(15_000)

        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.Idle), rig.states)
        assertEquals(listOf("in time"), rig.finals)
    }

    // ---- cancel ---------------------------------------------------------------------------------------------------

    @Test
    fun `cancel restores the route and delivers no final`() {
        val rig = Rig()
        rig.start()
        val canceledSink = requireNotNull(rig.pipeline.sink)

        rig.controller.cancel()

        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.Idle), rig.states)
        assertTrue(rig.finals.isEmpty())
        assertTrue(rig.forwarded.isEmpty())
        assertNull(rig.pipeline.sink)
        assertEquals("the aborted turn is sealed while still redirected", 1, rig.pipeline.finishCalls)

        // Nothing is delivered for the old sink, the canceled deadline, or a repeated cancel.
        canceledSink(transcript("after cancel", final = true))
        rig.advance(15_000)
        rig.controller.cancel()
        assertEquals(2, rig.states.size)
        assertEquals(1, rig.pipeline.finishCalls)
        assertTrue(rig.finals.isEmpty())
    }

    @Test
    fun `a late final after cancel is ignored`() {
        val rig = Rig()
        rig.start()
        val oldSink = requireNotNull(rig.pipeline.sink)
        rig.controller.cancel()

        oldSink(transcript("late final", final = true))

        assertTrue(rig.finals.isEmpty())
        assertTrue("the late final must not be forwarded as an ordinary event either", rig.forwarded.isEmpty())
    }

    // ---- idempotency, blank final, restart, close ------------------------------------------------------------------

    @Test
    fun `a second start while listening is refused`() {
        val rig = Rig()
        assertEquals(DictationController.StartResult.Accepted, rig.start())

        val second = rig.start()

        assertEquals(DictationController.StartResult.AlreadyListening, second)
        assertEquals(1, rig.pipeline.installations)
        assertEquals(1, rig.states.size)
    }

    @Test
    fun `an empty final ends with no speech and no text`() {
        val rig = Rig()
        rig.start()
        val sink = requireNotNull(rig.pipeline.sink)

        sink(transcript("   ", final = true))

        assertEquals(listOf<DictationState>(DictationState.Listening, DictationState.NoSpeech), rig.states)
        assertTrue("a blank final is not a result", rig.finals.isEmpty())
        assertTrue("and it is still a final, so it is not forwarded", rig.forwarded.isEmpty())
        assertNull(rig.pipeline.sink)
    }

    @Test
    fun `start is accepted again after a final`() {
        val rig = Rig()
        rig.start()
        rig.pipeline.emit(transcript("first", final = true))

        assertEquals(DictationController.StartResult.Accepted, rig.start())

        assertNotNull(rig.pipeline.sink)
        assertEquals(
            listOf<DictationState>(DictationState.Listening, DictationState.Idle, DictationState.Listening),
            rig.states,
        )
        assertEquals(2, rig.pipeline.installations)

        rig.controller.cancel()
    }

    @Test
    fun `close restores the route and makes start unavailable`() {
        val rig = Rig()
        rig.start()
        val oldSink = requireNotNull(rig.pipeline.sink)

        rig.controller.close()
        rig.controller.close()

        assertNull(rig.pipeline.sink)
        assertEquals(DictationController.StartResult.Unavailable, rig.start())
        assertTrue("close is a teardown, not a dictation result", rig.states == listOf(DictationState.Listening))

        oldSink(transcript("after close", final = true))
        assertTrue(rig.finals.isEmpty())
        assertTrue(rig.forwarded.isEmpty())
    }
}
