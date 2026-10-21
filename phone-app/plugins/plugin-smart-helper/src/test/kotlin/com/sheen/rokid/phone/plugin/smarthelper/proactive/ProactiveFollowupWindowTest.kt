package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.plugin.smarthelper.SmartRecordingDisplay
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.smartToolCall
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.3 Phase 3 Task 6: FOLLOWUP_LISTENING, ASR routing and wake preemption.
 *
 * The hint turn (Task 5) submits one sentence. Once the display acknowledges it (`Displayed`), the plugin opens a
 * **bounded** PROACTIVE listening window through the same `ConversationCycle` the wake route uses - but as its **own**
 * route/state/turn (P3-13). A usable final is the wearer picking the conversation up without the wake word; Task 6 stops
 * at the hand-off (`startFollowupTurn`), because Task 7 owns the full FOLLOWUP_TURN engine.
 *
 * Q4=A (owner-confirmed 2026-10-21): unlike the official "every ASR cancels the timer", a blank or wake-word-only final
 * does **not** cancel this window. Only a usable final or the cycle's own deadline closes it, so the window is always
 * bounded (10 s) instead of becoming unbounded on the wake segment's echo.
 *
 * Every test drives the real plugin through `handle`; the fakes stand in for the transport, the display and the store.
 */
class ProactiveFollowupWindowTest {

    /** The hint that was displayed plus the two collaborators whose calls are the observable behavior. */
    private data class HintRig(
        val rig: SmartTestRig,
        val display: SmartRecordingDisplay,
        val hint: Assistance,
    )

    /** The host's perception signal, which is the plugin's own arming evidence (spec §16.2). */
    private fun arm(rig: SmartTestRig, label: AcousticLabel = AcousticLabel.Speech) {
        rig.plugin.handle(AcousticEvent(rig.envelope(), label))
    }

    /** A `propose_hint` call followed by the terminal, as the transport delivers it. */
    private fun proposing(text: String = "现在正适合提醒他一句"): (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit =
        { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"$text"}""", requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

    /** Swaps [P3Log]'s sink for a collector and always restores it. */
    private fun <T> withLines(block: (MutableList<String>) -> T): T {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block(lines)
        } finally {
            P3Log.sink = previous
        }
    }

    /**
     * A proactive hint that the host has acknowledged as displayed, which is the production trigger for the window.
     *
     * With [panelVisible] false the window waits for `PANEL_SHOWN` inside its bounded acquisition wait; true is the
     * production state right after a hint reached the glasses, so the official 10 s starts at `onWake`.
     */
    private fun rigWithDisplayedHint(panelVisible: Boolean = true): HintRig {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()
        rig.panelVisible = panelVisible
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        return HintRig(rig, display, hint)
    }

    // ---- opening the window ------------------------------------------------------------------------------------------

    @Test
    fun `a displayed hint opens a bounded follow-up window on the proactive route`() {
        withLines { lines ->
            val (rig, display, hint) = rigWithDisplayedHint()

            val windowTurn = rig.plugin.followupWindowTurnIdForTest()
            assertNotNull(windowTurn, "the Displayed ACK is what opens the follow-up window")

            // The seam's two observable halves: the cycle opened a PROACTIVE listening round for the same turn, and the
            // device vocabulary names the turn and the hint it follows (never the hint's text).
            val state = display.states.single { it.route == AssistantRoute.PROACTIVE }
            assertEquals(windowTurn, state.turnId)
            assertEquals(OfficialStatus.LISTENING.wire, state.status)
            val line = lines.single { it.startsWith("P3_PROACTIVE followup open") }
            assertTrue(line.contains("turn=$windowTurn"), line)
            assertTrue(line.contains("hint=${hint.hintId}"), line)
            assertTrue(rig.cancels.isEmpty(), "opening the window must not cancel the displayed hint")

            // A repeated acknowledgement is not a second window and not a second opening line.
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
            assertEquals(windowTurn, rig.plugin.followupWindowTurnIdForTest())
            assertEquals(1, lines.count { it.startsWith("P3_PROACTIVE followup open") })
        }
    }

    @Test
    fun `a hidden panel starts the proactive window at panel shown`() {
        withLines { lines ->
            val (rig, display, _) = rigWithDisplayedHint(panelVisible = false)

            // The overlay's own start latency is not the wearer's silence: no listening level and no 10 s yet.
            assertNotNull(rig.plugin.followupWindowTurnIdForTest())
            assertTrue(display.states.isEmpty(), "STATE 1 waits until there is a panel to show it on")

            rig.plugin.onPanelShown(AssistantRoute.PROACTIVE)

            val state = display.states.single { it.route == AssistantRoute.PROACTIVE }
            assertEquals(OfficialStatus.LISTENING.wire, state.status)
            assertNotNull(rig.plugin.followupWindowTurnIdForTest(), "the window stays armed across the panel's layout")

            rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup close") }, "9_999 ms is inside the window")
            rig.tick(1)
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=timeout" }, lines.toString())
        }
    }

    // ---- the window's bounded end ------------------------------------------------------------------------------------

    @Test
    fun `a quiet window times out without cancelling the displayed hint`() {
        val (rig, display, _) = rigWithDisplayedHint()

        withLines { lines ->
            rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "the cycle's 10 s deadline ends the window")
            assertNull(rig.plugin.followupTurnForTest(), "a silent window starts no follow-up")
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=timeout" }, lines.toString())
            assertTrue(lines.any { it == "P3_CONV close route=PROACTIVE reason=timeout" }, lines.toString())
            assertEquals(listOf(AssistantRoute.PROACTIVE), display.closes)
            // The hint has its own TTL; the listening window must not cancel the sentence the wearer is reading.
            assertTrue(rig.cancels.isEmpty(), "the displayed hint is not cancelled by the window's timeout")
            assertEquals(0, rig.scheduler.pending(), "the fired window is released")
        }
    }

    // ---- Q4=A: blank/echo finals do not close the window -------------------------------------------------------------

    @Test
    fun `a blank or wake word only final leaves the window armed`() {
        val (rig, _, _) = rigWithDisplayedHint()

        withLines { lines ->
            rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-blank", revision = 2)))
            rig.plugin.handle(TranscriptEvent(rig.transcript("伊迪丝", turnId = "asr-echo", revision = 3)))

            // Q4=A: the official `AsrMessageHandler` removes the timeout before the blank check; the follow-up route
            // deliberately does not (only a usable final may close it), so the window is still the same 10 s window.
            assertNotNull(rig.plugin.followupWindowTurnIdForTest(), "a blank/echo final must not disarm the window")
            assertNull(rig.plugin.followupTurnForTest())

            rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
            assertNotNull(rig.plugin.followupWindowTurnIdForTest(), "9_999 ms is still inside the window")
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup close") }, lines.toString())
            rig.tick(1)
            assertNull(rig.plugin.followupWindowTurnIdForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=timeout" }, lines.toString())
        }
    }

    // ---- the usable final: the Task 6 seam ---------------------------------------------------------------------------

    @Test
    fun `a usable final closes the window and hands the utterance to the follow-up seam`() {
        val (rig, _, _) = rigWithDisplayedHint()

        withLines { lines ->
            val windowTurn = rig.plugin.followupWindowTurnIdForTest()
            val proactiveLinesBefore = lines.count { it.startsWith("P3_PROACTIVE") }
            val requestsBefore = rig.model.requests.size

            rig.plugin.handle(TranscriptEvent(rig.transcript("把亮度调到最低", turnId = "asr-followup", revision = 2)))

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "a usable final consumes the window")
            val handoff = rig.plugin.followupTurnForTest()
            assertNotNull(handoff, "the accepted utterance is handed to the follow-up seam")
            assertEquals(windowTurn, handoff.first, "the follow-up reuses the listening window's turn id")
            assertEquals("把亮度调到最低", handoff.second)
            // Task 6 stops at the seam: no follow-up loop runs here (Task 7 owns it), and the utterance is not also
            // re-judged as a new proactive trigger.
            assertEquals(requestsBefore, rig.model.requests.size)
            assertEquals(proactiveLinesBefore, lines.count { it.startsWith("P3_PROACTIVE") })
        }
    }

    @Test
    fun `a window whose cycle turn is gone is inert and the next revision clears it`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()
        rig.panelVisible = true
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        assertNotNull(rig.plugin.followupWindowTurnIdForTest())

        // `Help` resets the whole conversation cycle (its non-wake manual entry point) without a per-route close
        // callback, so the plugin's window is left with no PROACTIVE turn behind it. It must be inert - not consume the
        // next final, not start a follow-up - and the next revision drops it.
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好的。") }
        withLines { lines ->
            rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))
            rig.plugin.handle(TranscriptEvent(rig.transcript("继续", turnId = "asr-help", revision = 2, final = false)))

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "an inert window is cleared by the next event")
            assertNull(rig.plugin.followupTurnForTest())
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup turn") })
        }
    }

    // ---- wake preemption ---------------------------------------------------------------------------------------------

    @Test
    fun `a wake preempts an accepted follow-up and opens only the wake turn`() {
        val (rig, display, _) = rigWithDisplayedHint()
        rig.plugin.handle(TranscriptEvent(rig.transcript("把亮度调到最低", turnId = "asr-followup")))
        assertNotNull(rig.plugin.followupTurnForTest())

        withLines { lines ->
            rig.plugin.handle(rig.wakeEvent())

            // P3-4: the wake cancels the handed-over follow-up, but not the hint already on the panel.
            assertNull(rig.plugin.followupTurnForTest(), "the wake cancels the pending follow-up")
            assertNull(rig.plugin.followupWindowTurnIdForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE preempt reason=WAKE_PREEMPTED" }, lines.toString())

            val wakeState = display.states.single { it.route == AssistantRoute.WAKE }
            assertEquals(OfficialStatus.LISTENING.wire, wakeState.status)
            assertEquals(1, rig.model.requests.size, "the follow-up's loop never runs")
            assertEquals(1, rig.output.hints.size, "no second display for the preempted follow-up")
            assertTrue(rig.cancels.isEmpty(), "the displayed hint is not cancelled by the preemption")
        }
    }

    // ---- P3-13: two independent sessions -----------------------------------------------------------------------------

    @Test
    fun `a wake final is consumed by the wake turn and never enters the proactive window`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()
        // The hint was submitted before the wake and acknowledged after it: a WAKE turn is waiting while the PROACTIVE
        // window opens, which is exactly the concurrent state P3-13 is about.
        rig.plugin.handle(rig.wakeEvent())
        rig.panelVisible = true
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        val windowTurn = rig.plugin.followupWindowTurnIdForTest()
        assertNotNull(windowTurn)

        rig.model.onRequest = { request, listener -> streamText(request, listener, "好的。") }
        withLines { lines ->
            rig.plugin.handle(TranscriptEvent(rig.transcript("打开设置", turnId = "asr-wake", revision = 2)))

            // WAKE `AwaitingTranscript` outranks the proactive window (P3-13): the final belongs to WAKE and the window
            // is not consumed by it.
            assertNull(rig.plugin.followupTurnForTest(), "a wake final must not start a follow-up")
            assertEquals(windowTurn, rig.plugin.followupWindowTurnIdForTest(),
                "the proactive window is not touched by a WAKE turn's final")
            assertEquals(2, rig.model.requests.size, "the second request is the wake turn's own")
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup turn") })
        }
    }

    @Test
    fun `a wake answer and its continuation never open a proactive window`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(continueDialogue = true, panelVisible = true)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        withLines { lines ->
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("问题", turnId = "asr-1")))
            rig.tick()
            val answer = rig.output.hints.single { it.route == AssistantRoute.WAKE }

            rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed))

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "only a PROACTIVE hint opens a follow-up window")
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup open") }, lines.toString())
            // The WAKE answer's displayed ACK still does its own job: the automatic continuation is a WAKE round.
            assertEquals(
                OfficialStatus.LISTENING.wire,
                display.states.last { it.route == AssistantRoute.WAKE }.status,
            )
        }
    }

    // ---- STOP / UI_CLOSED --------------------------------------------------------------------------------------------

    @Test
    fun `stop clears the follow-up window and starts nothing`() {
        val (rig, display, _) = rigWithDisplayedHint()

        withLines { lines ->
            rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "STOP ends the follow-up window")
            assertNull(rig.plugin.followupTurnForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=stop" }, lines.toString())
            assertTrue(display.idleCalls.any { it.first == AssistantRoute.PROACTIVE }, "STOP shows the official idle")
            assertEquals(0, rig.scheduler.pending(), "STOP cancels the listening deadline")
        }
    }

    @Test
    fun `a panel close clears the follow-up window and starts nothing`() {
        val (rig, display, _) = rigWithDisplayedHint()

        withLines { lines ->
            rig.plugin.onPanelClosed(AssistantRoute.PROACTIVE)

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "UI_CLOSED ends the follow-up window")
            assertNull(rig.plugin.followupTurnForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=ui_closed" }, lines.toString())
            assertTrue(display.closes.isEmpty(), "the panel is already gone; no CLOSE is sent again")
            assertEquals(0, rig.scheduler.pending(), "the listening deadline is released")

            rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertNull(rig.plugin.followupTurnForTest())
            assertTrue(display.closes.isEmpty())
        }
    }
}
