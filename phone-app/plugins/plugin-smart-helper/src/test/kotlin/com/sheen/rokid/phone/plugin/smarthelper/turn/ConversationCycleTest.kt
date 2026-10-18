package com.sheen.rokid.phone.plugin.smarthelper.turn

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeClock
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeScheduler
import com.sheen.rokid.plugin.ConversationDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.24 S3 Task 12: the official 10 s / 120 s / IDLE conversation rules, as a pure state machine.
 *
 * The official reference is `RokidAIController.TIME_OUT` (10 s), `TIME_WAITE_QUIT_TTS` (120 s),
 * `AIOpenHandler` (start the timeout when the dialog opens), `AsrMessageHandler` (remove the timeout before the blank
 * check), `AudioFinishedHandler` (continue on: 10 s + status 5 → 1; continue off: `scheduleExit(1000)`), and
 * `onTimeout`/`showIdleStatus` (a pinned timeout idles; it does not close and is not status 0).
 *
 * Every test drives the **real** class against a manual clock/scheduler and a recording display, so the exact
 * deadline boundaries are observable instead of inferred from a sleep.
 */
class ConversationCycleTest {

    private class RecordingDisplay : ConversationDisplay {
        sealed interface Call {
            data class State(
                val route: AssistantRoute,
                val turnId: String,
                val status: Int,
                val sequence: Long,
            ) : Call

            data class Idle(val route: AssistantRoute, val sequence: Long) : Call
            data class Close(val route: AssistantRoute) : Call
        }

        val calls = mutableListOf<Call>()

        override fun userMessage(route: AssistantRoute, text: String) = Unit
        override fun assistantMessage(route: AssistantRoute, text: String) = Unit
        override fun working(route: AssistantRoute) = Unit
        override fun idle(route: AssistantRoute) = Unit

        override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
            calls += Call.State(route, turnId, status, sequence)
        }

        override fun showIdle(route: AssistantRoute, sequence: Long) {
            calls += Call.Idle(route, sequence)
        }

        override fun closePanel(route: AssistantRoute) {
            calls += Call.Close(route)
        }

        val states: List<Call.State>
            get() = calls.filterIsInstance<Call.State>()

        val statuses: List<Int> get() = states.map { it.status }

        val closed: Int get() = calls.count { it is Call.Close }
        val idled: Int get() = calls.count { it is Call.Idle }
    }

    private class Rig(
        var panelVisible: Boolean = false,
        var continueOn: Boolean = true,
    ) {
        val clock = SmartFakeClock()
        val scheduler = SmartFakeScheduler(clock)
        val display = RecordingDisplay()
        val continuations = mutableListOf<String>()
        val cancelled = mutableListOf<AssistantRoute>()
        val logs = mutableListOf<String>()
        val cycle = ConversationCycle(
            clock = clock,
            scheduler = scheduler,
            display = { display },
            continueDialogue = { continueOn },
            openContinuation = { turnId -> continuations += turnId },
            isPanelVisible = { panelVisible },
            cancelTurn = { route -> cancelled += route },
            log = { line -> logs += line },
        )

        /** Moves the clock and releases every deadline that has come due, the way the host scheduler would. */
        fun advance(millis: Long) {
            clock.advance(millis * 1_000_000L)
            scheduler.runDue()
        }
    }

    private fun rig(panelVisible: Boolean = false, continueOn: Boolean = true) =
        Rig(panelVisible = panelVisible, continueOn = continueOn)

    // ---------------------------------------------------------------- the 10 s listening window

    @Test
    fun `a hidden panel delays the ten second window until panel shown`() {
        val rig = rig()
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        // No PANEL_SHOWN yet: the overlay may not even be up, so the wearer's silence has not started.
        rig.advance(60_000)
        assertEquals(0, rig.display.closed, "a hidden panel must not arm the 10 s window")
        assertEquals(emptyList(), rig.display.statuses, "nothing is shown before the panel is")

        rig.cycle.onPanelShown(AssistantRoute.WAKE)
        assertEquals(listOf(OfficialStatus.LISTENING.wire), rig.display.statuses)
        assertEquals(listOf("turn-1"), rig.display.states.map { it.turnId })

        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertEquals(0, rig.display.closed, "9_999 ms is still inside the window")
        rig.advance(1)
        assertEquals(1, rig.display.closed, "10_000 ms exactly must close")
        assertEquals(listOf(AssistantRoute.WAKE), rig.cancelled)
    }

    @Test
    fun `an already visible panel arms the window at wake`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        assertEquals(listOf(OfficialStatus.LISTENING.wire), rig.display.statuses)
        assertEquals(1, rig.scheduler.pending(), "the visible-panel wake must arm exactly one window")

        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, rig.display.closed)
        assertEquals(emptyList(), rig.continuations)
    }

    @Test
    fun `a duplicate panel shown restarts the window without a second state or close`() {
        val rig = rig()
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPanelShown(AssistantRoute.WAKE)

        // Task 11 can re-announce PANEL_SHOWN (or deliver the delayed original), which is idempotent.
        rig.advance(9_000)
        rig.cycle.onPanelShown(AssistantRoute.WAKE)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertEquals(0, rig.display.closed, "the re-announce restarted the window; the first one must not fire")
        rig.advance(1)
        assertEquals(1, rig.display.closed)
        assertEquals(1, rig.display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "a duplicate PANEL_SHOWN must not display a second listening state")
        assertEquals(0, rig.scheduler.pending(), "the fired window is released and no second one remains")
    }

    @Test
    fun `a panel shown with no wake does nothing`() {
        val rig = rig()
        rig.cycle.onPanelShown(AssistantRoute.WAKE)
        rig.advance(60_000)

        assertEquals(emptyList(), rig.display.calls)
        assertEquals(0, rig.scheduler.pending())
        assertEquals(emptyList(), rig.continuations)
    }

    @Test
    fun `any ASR message cancels the listening window even when blank`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.advance(9_000)

        // The official handler removes the timeout *before* it checks for a non-blank ASR payload.
        rig.cycle.onAsr(AssistantRoute.WAKE)
        rig.advance(60_000)

        assertEquals(0, rig.display.closed)
        assertEquals(emptyList(), rig.continuations)
        assertEquals(0, rig.scheduler.pending(), "the cancelled window must be released")
    }

    @Test
    fun `an ASR message before the panel is shown still cancels the window`() {
        val rig = rig()
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onAsr(AssistantRoute.WAKE)
        rig.cycle.onPanelShown(AssistantRoute.WAKE)
        rig.advance(60_000)

        assertEquals(0, rig.display.closed, "a late PANEL_SHOWN must not invent a window an ASR already cancelled")
        assertEquals(listOf(OfficialStatus.LISTENING.wire), rig.display.statuses)
        assertEquals(0, rig.scheduler.pending())
    }

    @Test
    fun `status two four three are displayed but never arm a listening window`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ASR)
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ASR_END)
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ANSWERING)

        assertEquals(
            listOf(
                OfficialStatus.LISTENING.wire,
                OfficialStatus.ASR.wire,
                OfficialStatus.ASR_END.wire,
                OfficialStatus.ANSWERING.wire,
            ),
            rig.display.statuses,
        )
        val sequences = rig.display.states.map { it.sequence }
        assertEquals(sequences.sorted(), sequences, "status sequences must be monotonic")
        assertEquals(sequences.distinct().size, sequences.size, "each level gets its own sequence")

        rig.advance(60_000)
        assertEquals(0, rig.display.closed, "thinking/answering must not re-arm the 10 s window")
        assertEquals(0, rig.scheduler.pending())
    }

    @Test
    fun `the adopted ASR turn id is what later levels carry`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "wake-1")
        rig.cycle.onTurnId(AssistantRoute.WAKE, "asr-1")
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ASR)

        assertEquals("asr-1", rig.display.states.last().turnId)
        assertEquals(
            listOf("wake-1", "asr-1"),
            rig.display.states.map { it.turnId },
            "the wake placeholder is replaced without restarting the window",
        )
    }

    // ---------------------------------------------------------------- answer completion / continual dialogue

    @Test
    fun `continue on displays audio finished and opens exactly one continuation`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ANSWERING)

        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)

        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, rig.display.statuses.last())
        assertEquals(listOf("turn-1"), rig.continuations, "the continuation is opened once, for the finished turn")
        assertEquals(0, rig.display.closed)
        assertEquals(emptyList(), rig.cancelled, "a normal continued answer must not cancel a turn")
    }

    @Test
    fun `continue off closes one second later without status five or a new turn`() {
        val rig = rig(panelVisible = true, continueOn = false)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ANSWERING)

        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses,
            "the continue-off branch must not claim status 5")
        assertEquals(emptyList(), rig.continuations)

        rig.advance(999)
        assertEquals(0, rig.display.closed, "the official scheduleExit is 1_000 ms, not immediate")
        rig.advance(1)
        assertEquals(1, rig.display.closed)
        assertEquals(emptyList(), rig.continuations)
        assertEquals(listOf(AssistantRoute.WAKE), rig.cancelled)
    }

    @Test
    fun `an answer that was not displayed closes immediately without continuation`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = false, speech = false)

        assertEquals(1, rig.display.closed)
        assertEquals(emptyList(), rig.continuations)
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses)
        assertEquals(listOf(AssistantRoute.WAKE), rig.cancelled)
    }

    @Test
    fun `a duplicated answer done callback is ignored`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)
        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)

        assertEquals(1, rig.continuations.size, "a duplicate callback must not open a second turn")
        assertEquals(0, rig.display.closed)

        val off = rig(panelVisible = true, continueOn = false)
        off.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        off.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)
        off.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)
        off.advance(1_000)
        assertEquals(1, off.display.closed, "a duplicate callback must not close twice")
    }

    @Test
    fun `a displayed answer with speech waits for speech finished`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ANSWERING)
        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = true)

        rig.advance(60_000)
        assertEquals(0, rig.display.closed, "the answer's speech is the next-turn gate")
        assertEquals(emptyList(), rig.continuations)
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses)

        // Both FINISHED and FAILED open the next turn; a failed playback still finished.
        rig.cycle.onSpeechFinished(AssistantRoute.WAKE, SpeechResult.FAILED)
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, rig.display.statuses.last())
        assertEquals(listOf("turn-1"), rig.continuations)
    }

    @Test
    fun `speech finished without a pending speech answer is ignored`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        rig.cycle.onSpeechFinished(AssistantRoute.WAKE, SpeechResult.FINISHED)
        assertEquals(emptyList(), rig.continuations)
        assertEquals(0, rig.display.closed)

        // A silent answer (no speech) does not wait for the speech event either.
        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)
        rig.cycle.onSpeechFinished(AssistantRoute.WAKE, SpeechResult.FINISHED)
        assertEquals(1, rig.continuations.size, "a stale speech callback must not open a second turn")
    }

    // ---------------------------------------------------------------- pin / IDLE / 120 s

    @Test
    fun `pin sets pinned without toggling`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        assertTrue(rig.cycle.isPinned(AssistantRoute.WAKE))
        rig.cycle.onPin(AssistantRoute.WAKE)
        assertTrue(rig.cycle.isPinned(AssistantRoute.WAKE), "a second click must not unpin")
    }

    @Test
    fun `a pinned ten second timeout shows idle and closes at one hundred twenty seconds`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)

        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, rig.display.idled, "a pinned timeout is showIdleStatus, not a close")
        assertEquals(0, rig.display.closed)
        assertEquals(
            listOf(OfficialStatus.LISTENING.wire),
            rig.display.statuses,
            "IDLE is not status 0: it emits showIdle and no STATE level",
        )

        rig.advance(OfficialConversation.PINNED_CLOSE_MILLIS - 1)
        assertEquals(0, rig.display.closed, "119_999 ms after idle is still inside the pinned window")
        rig.advance(1)
        assertEquals(1, rig.display.closed, "120_000 ms exactly must close")
    }

    @Test
    fun `the pinned close boundary is exclusive at one hundred nineteen thousand nine hundred ninety nine`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)

        rig.advance(119_999)
        assertEquals(0, rig.display.closed)
        rig.advance(1)
        assertEquals(1, rig.display.closed)
    }

    @Test
    fun `ASR cancels a pending pinned close`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, rig.display.idled)

        // The official AsrMessageHandler removes the delayed exit as well as the timeout.
        rig.cycle.onAsr(AssistantRoute.WAKE)
        rig.advance(200_000)
        assertEquals(0, rig.display.closed)
        assertEquals(0, rig.scheduler.pending())
    }

    // ---------------------------------------------------------------- BACK

    @Test
    fun `back while pinned and answering stops to idle`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ANSWERING)

        rig.cycle.onBack(AssistantRoute.WAKE)

        assertEquals(1, rig.display.idled, "pinned + ANSWERING is the official interrupt, not an exit")
        assertEquals(0, rig.display.closed)
        assertEquals(listOf(AssistantRoute.WAKE), rig.cancelled)
    }

    @Test
    fun `back while unpinned closes and clears pin`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        rig.cycle.onBack(AssistantRoute.WAKE)

        assertEquals(1, rig.display.closed)
        assertFalse(rig.cycle.isPinned(AssistantRoute.WAKE))
        assertEquals(listOf(AssistantRoute.WAKE), rig.cancelled)
        rig.advance(200_000)
        assertEquals(1, rig.display.closed, "a closed conversation must not be closed again by a stale timer")
    }

    @Test
    fun `back while pinned but not answering closes`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ASR_END)

        rig.cycle.onBack(AssistantRoute.WAKE)

        assertEquals(1, rig.display.closed)
        assertFalse(rig.cycle.isPinned(AssistantRoute.WAKE))
    }

    @Test
    fun `back after a pinned idle closes the retained panel`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, rig.display.idled)

        rig.cycle.onBack(AssistantRoute.WAKE)

        assertEquals(1, rig.display.closed)
        assertFalse(rig.cycle.isPinned(AssistantRoute.WAKE))
    }

    // ---------------------------------------------------------------- close / stop / new wake

    @Test
    fun `close cancels all timers and clears pin`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, rig.scheduler.pending(), "the 120 s pinned close is pending")

        // UI_CLOSED: the panel is already gone, so close must not send a CLOSE of its own.
        rig.cycle.onClose(AssistantRoute.WAKE)
        assertFalse(rig.cycle.isPinned(AssistantRoute.WAKE))
        assertEquals(0, rig.display.closed, "onClose does not send a CLOSE: the panel is already hidden")
        assertEquals(0, rig.scheduler.pending())

        rig.advance(200_000)
        assertEquals(0, rig.display.closed)
        assertEquals(1, rig.display.idled)
    }

    @Test
    fun `a new wake resets pin and opens a fresh listening window`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onPin(AssistantRoute.WAKE)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertTrue(rig.cycle.isPinned(AssistantRoute.WAKE))

        rig.cycle.onWake(AssistantRoute.WAKE, "turn-2")

        assertFalse(rig.cycle.isPinned(AssistantRoute.WAKE), "an explicit new round starts unpinned")
        assertEquals("turn-2", rig.display.states.last().turnId)
        rig.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertEquals(0, rig.display.closed)
        rig.advance(1)
        assertEquals(1, rig.display.closed)
    }

    @Test
    fun `stop shows idle without closing the panel`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")

        rig.cycle.onStop(AssistantRoute.WAKE)

        assertEquals(1, rig.display.idled)
        assertEquals(0, rig.display.closed, "STOP keeps the panel; only BACK's EXIT closes it")
        assertEquals(0, rig.scheduler.pending(), "STOP must cancel the listening window")

        rig.advance(200_000)
        assertEquals(0, rig.display.closed, "STOP must not arm a close the S3 spec has not confirmed")
    }

    @Test
    fun `status updates after close are ignored`() {
        val rig = rig(panelVisible = true)
        rig.cycle.onWake(AssistantRoute.WAKE, "turn-1")
        rig.cycle.onBack(AssistantRoute.WAKE)
        val before = rig.display.calls.size

        rig.cycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ANSWERING)
        rig.cycle.onAnswerDone(AssistantRoute.WAKE, displayed = true, speech = false)

        assertEquals(before, rig.display.calls.size)
        assertEquals(emptyList(), rig.continuations)
    }
}
