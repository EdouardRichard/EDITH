package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ContextChanged
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.PinChanged
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.plugin.smarthelper.SmartRecordingDisplay
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.OffThreadTasks
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.smartToolCall
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputSubmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        /** Every readiness-aware `PANEL_OPEN` the plugin asked the host for; an already visible panel asks for none. */
        val panelOpenRequests: List<AssistantRoute>,
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

    /**
     * A gate that records every judgement and always intervenes, so "the first stage refused the trigger before the
     * gate" is observable (the busy test is exactly that assertion).
     */
    private class RecordingGate : ProactiveGate {
        val seen = mutableListOf<ProactiveGateState>()
        override val form = GateForm.NOUL
        override fun shouldIntervene(state: ProactiveGateState): GateOutcome {
            seen += state
            return GateOutcome(true, 0.9, 1.0)
        }
    }

    /**
     * Runs [block] on another thread and waits for it. The attach gap is a cross-thread interleaving in production
     * (the host dispatcher installs the window while the composition's scheduler delivers the panel event), so the
     * test must not fake it on the attaching thread.
     */
    private fun onAnotherThread(block: () -> Unit) {
        var failure: Throwable? = null
        val thread = Thread({
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }, "followup-lifecycle")
        thread.isDaemon = true
        thread.start()
        thread.join(5_000)
        assertFalse(thread.isAlive, "the concurrent lifecycle event did not finish")
        failure?.let { throw it }
    }

    /** Swaps [P3Log]'s sink for a collector and always restores it. */
    private fun <T> withLines(block: (MutableList<String>) -> T): T {
        // The acquisition tests keep the hint task blocked on another thread while the cycle's timeout worker logs,
        // so the collector must tolerate concurrent appends (the same shape `SmartAssistantPluginTest` uses).
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block(lines)
        } finally {
            P3Log.sink = previous
        }
    }

    /** Polls the asynchronous hint/follow-up work for a submitted hint. */
    private fun awaitHints(rig: SmartTestRig, expected: Int, millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (rig.output.hints.size < expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, rig.output.hints.size, "the expected hint was not submitted")
    }

    /** The follow-up's answer when Task 7's engine runs it (the window tests only need it to complete). */
    private fun answering(text: String = "好的。") = { request: ModelRequest, listener: (ModelStreamEvent) -> Unit ->
        streamText(request, listener, text)
    }

    /**
     * A proactive hint that the host has acknowledged as displayed, which is the production trigger for the window.
     *
     * The panel is **already visible** when the hint is submitted: the plugin must take the visible branch and never
     * ask the host for a readiness-aware open (that would reset the conversation the wearer is watching). The hidden
     * panel branch is exercised by [hiddenPanelRig], where a recorded `PANEL_SHOWN` is what lets the hint submit.
     *
     * [proactiveCooldownMillis] lets a test place a second trigger inside the window after the hint cooldown;
     * [beforeAttach] runs in the install/attach gap (see `beforeFollowupWindowAttachForTest`).
     */
    private fun rigWithDisplayedHint(
        proactiveCooldownMillis: String? = null,
        interventionGate: ProactiveGate? = null,
        beforeAttach: (() -> Unit)? = null,
    ): HintRig {
        val display = SmartRecordingDisplay()
        val panelOpenRequests = mutableListOf<AssistantRoute>()
        val rig = SmartTestRig(
            panelVisible = true,
            interventionGate = interventionGate,
            requestPanelOpen = { route -> panelOpenRequests += route; true },
        )
        rig.activate(
            proactiveCooldownMillis = proactiveCooldownMillis,
            overrides = mapOf(Capability.ConversationDisplay to display),
        )
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()
        rig.plugin.beforeFollowupWindowAttachForTest = beforeAttach
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        return HintRig(rig, display, hint, panelOpenRequests)
    }

    /**
     * 0.3 Phase 3 Task 9d: the production-style readiness-aware panel seam, recorded.
     *
     * Production's `HostCompositionRoot.requestPanelOpen` hands `PANEL_OPEN` to the link and returns whether the
     * request was accepted for delivery; `PANEL_SHOWN` arrives later on the host dispatcher. The deterministic inline
     * variant delivers that report from inside [onRequest], which is the same ordering the production host produces
     * (the open is processed, and its first layout announced, before the hint is submitted).
     */
    private class PanelSeam {
        val routes = mutableListOf<AssistantRoute>()
        val hintsAtRequest = mutableListOf<Int>()
        val requested = CountDownLatch(1)
        var hints: () -> Int = { 0 }
        var onRequest: (() -> Unit)? = null
        var accepted = true

        fun request(route: AssistantRoute): Boolean {
            routes += route
            hintsAtRequest += hints()
            val result = accepted
            if (result) onRequest?.invoke()
            requested.countDown()
            return result
        }
    }

    /** A rig whose proactive hint runs on a hidden panel and must acquire the panel through [PanelSeam]. */
    private data class HiddenPanelRig(
        val rig: SmartTestRig,
        val display: SmartRecordingDisplay,
        val seam: PanelSeam,
    )

    /**
     * Starts the hidden-panel production path: the prompt produces a hint, the plugin requests `PANEL_OPEN`, and the
     * hint may only be submitted after `PANEL_SHOWN`.
     *
     * [deliverShown] false models an overlay that never reports its first layout. [tasks] lets a test keep the hint
     * turn on its own thread while it waits for the panel report (the production shape).
     */
    private fun hiddenPanelRig(
        accepted: Boolean = true,
        deliverShown: Boolean = true,
        tasks: com.sheen.rokid.plugin.TaskScope? = null,
        /** `"0"` lets a test admit a newer trigger right after the hint turn finished (the replacement race). */
        proactiveCooldownMillis: String? = null,
    ): HiddenPanelRig {
        val seam = PanelSeam()
        val display = SmartRecordingDisplay()
        lateinit var rig: SmartTestRig
        seam.accepted = accepted
        seam.hints = { rig.output.hints.size }
        if (deliverShown) {
            seam.onRequest = {
                // The link sets visibility before delivering `PANEL_SHOWN` to the host; this seam delivers the host
                // callback inline, so the test's own display port observes the level the production cycle would show.
                rig.panelVisible = true
                rig.plugin.onPanelShown(AssistantRoute.PROACTIVE)
            }
        }
        rig = SmartTestRig(requestPanelOpen = seam::request)
        rig.activate(
            proactiveCooldownMillis = proactiveCooldownMillis,
            overrides = mapOf(Capability.ConversationDisplay to display),
            tasks = tasks,
        )
        rig.model.onRequest = proposing()
        return HiddenPanelRig(rig, display, seam)
    }

    // ---- opening the window ------------------------------------------------------------------------------------------

    @Test
    fun `a displayed hint opens a bounded follow-up window on the proactive route`() {
        withLines { lines ->
            val (rig, display, hint, panelOpenRequests) = rigWithDisplayedHint()

            val windowTurn = rig.plugin.followupWindowTurnIdForTest()
            assertNotNull(windowTurn, "the Displayed ACK is what opens the follow-up window")
            assertTrue(
                panelOpenRequests.isEmpty(),
                "an already visible panel must not be reset or reopened by the hint: $panelOpenRequests",
            )

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
    fun `an in-submit displayed ack opens the follow-up window`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        // The host's output port acknowledges synchronously, inside `submit`: the hint is still `submitting`, so the
        // plugin remembers the ACK and opens the window from the admitted-submit branch afterwards.
        rig.output.onSubmit = { assistance ->
            rig.plugin.handle(OutputStateEvent(rig.envelope(), assistance.hintId, OutputStatus.Displayed))
        }
        arm(rig)

        withLines { lines ->
            rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))

            val windowTurn = rig.plugin.followupWindowTurnIdForTest()
            assertNotNull(windowTurn, "the in-submit ACK opens the window once the hint is admitted")
            assertEquals(
                OfficialStatus.LISTENING.wire,
                display.states.single { it.route == AssistantRoute.PROACTIVE }.status,
                "the in-submit ACK arms the same PROACTIVE round as a dispatched ACK",
            )
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE followup open") }, lines.toString())

            // The window opened by the in-submit branch is a real listening round, not just flagged state.
            rig.model.onRequest = answering()
            rig.plugin.handle(TranscriptEvent(rig.transcript("把亮度调到最低", turnId = "asr-followup", revision = 2)))
            rig.tick()
            assertNull(rig.plugin.followupWindowTurnIdForTest(), "the usable final consumes the window")
            assertEquals("asr-followup", rig.plugin.followupTurnStateForTest()?.first)
        }
    }

    // ---- the install/attach gap: PANEL_SHOWN and UI_CLOSED can land before the cycle is armed ------------------------

    /**
     * A `Displayed` ACK whose window is installed, with the panel lifecycle event injected on another thread in the gap
     * before the cycle's `onWake`. This is the production interleaving: the host dispatcher installs the window, while
     * the composition's scheduler thread delivers the glasses' report.
     */
    @Test
    fun `a panel shown while the window is attaching does not clear it as inert`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()
        rig.panelVisible = true
        rig.plugin.beforeFollowupWindowAttachForTest = {
            onAnotherThread { rig.plugin.onPanelShown(AssistantRoute.PROACTIVE) }
        }

        withLines { lines ->
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))

            // `onPanelShown` must not treat a window whose cycle round is still being attached as an inert
            // "has no turn" window.
            val windowTurn = rig.plugin.followupWindowTurnIdForTest()
            assertNotNull(windowTurn, "the panel announcement in the attach gap must not drop the pending window")
            assertEquals(
                OfficialStatus.LISTENING.wire,
                display.states.single { it.route == AssistantRoute.PROACTIVE }.status,
            )
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup close") }, lines.toString())

            rig.model.onRequest = answering()
            rig.plugin.handle(TranscriptEvent(rig.transcript("把亮度调到最低", turnId = "asr-followup", revision = 2)))
            rig.tick()
            assertNull(rig.plugin.followupWindowTurnIdForTest())
            assertEquals("asr-followup", rig.plugin.followupTurnStateForTest()?.first,
                "the surviving window accepts the follow-up")
        }
    }

    @Test
    fun `a panel close while the window is attaching cannot leave an orphan proactive round`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()
        rig.panelVisible = true
        rig.plugin.beforeFollowupWindowAttachForTest = {
            onAnotherThread { rig.plugin.onPanelClosed(AssistantRoute.PROACTIVE) }
        }

        withLines { lines ->
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "the panel is gone, so its window is gone")
            assertNull(rig.plugin.followupTurnForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=ui_closed" }, lines.toString())
            assertTrue(
                lines.none { it.startsWith("P3_PROACTIVE followup open") },
                "an attach aborted by UI_CLOSED must not report a window it no longer owns",
            )
            // The bug this pins: `onWake` still arms the cycle's 10 s round after the window was cleared, and no
            // deadline owner remains. With the fix the round is either never armed or released again.
            assertEquals(0, rig.scheduler.pending(), "no listening deadline is left without a window")
            assertTrue(display.closes.isEmpty(), "UI_CLOSED sends no CLOSE")

            rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(
                lines.none { it.startsWith("P3_CONV close route=PROACTIVE") },
                "an orphan PROACTIVE round would time out here and close a panel the wearer already closed",
            )
        }
    }

    // ---- Task 9d: the hidden-panel acquisition (open the panel before the hint) -------------------------------------

    @Test
    fun `a hidden panel hint opens the panel before it submits and listens at panel shown`() {
        val hidden = hiddenPanelRig()

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))

            // The defect this pins: no production code asked for a window, so the hint was ACKed `Displayed` on a
            // hidden panel and the 3 s acquisition wait had no satisfier. The readiness-aware PROACTIVE open is now
            // requested, and the hint is not submitted before it.
            assertEquals(listOf(AssistantRoute.PROACTIVE), hidden.seam.routes)
            assertEquals(
                listOf(0),
                hidden.seam.hintsAtRequest,
                "the hint must not be submitted before the panel request",
            )

            // `PANEL_SHOWN` armed the 10 s window, and only then did the hint go out, so the announced window cannot
            // be reset by a later `PANEL_OPEN`.
            val listen = lines.single { it.startsWith("P3_CONV listen route=PROACTIVE") }
            assertTrue(listen.endsWith("shown=true"), listen)
            assertTrue(lines.none { it.startsWith("P3_CONV panel_not_shown") }, lines.toString())
            val hint = hidden.rig.output.hints.single()
            assertTrue(
                lines.indexOf(listen) < lines.indexOfFirst { it.startsWith("P3_HINT show") },
                "the listening window must be armed before the hint is submitted: $lines",
            )
            assertTrue(hidden.display.closes.isEmpty(), "opening the panel must not clear the announced window")

            // The ACK opens the follow-up window on the already visible panel; no second open/reset may follow.
            hidden.rig.plugin.handle(OutputStateEvent(hidden.rig.envelope(), hint.hintId, OutputStatus.Displayed))
            val windowTurn = hidden.rig.plugin.followupWindowTurnIdForTest()
            assertNotNull(windowTurn, "the displayed hint opens the follow-up window")
            assertEquals(1, hidden.seam.routes.size, "the open must not be repeated once the hint is on the panel")
            assertTrue(hidden.rig.cancels.isEmpty(), "the displayed hint must survive the panel acquisition")
            assertTrue(
                lines.any { it == "P3_PROACTIVE followup open turn=$windowTurn hint=${hint.hintId}" },
                lines.toString(),
            )
        }
    }

    @Test
    fun `a hidden panel follow-up final starts a proactive turn`() {
        val hidden = hiddenPanelRig()

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertEquals(
                listOf(AssistantRoute.PROACTIVE),
                hidden.seam.routes,
                "the hidden path must request the panel before the follow-up can be armed",
            )
            assertTrue(
                lines.any { it.startsWith("P3_CONV listen route=PROACTIVE") },
                "PANEL_SHOWN must arm the listening window: $lines",
            )
            val hint = hidden.rig.output.hints.single()
            hidden.rig.plugin.handle(OutputStateEvent(hidden.rig.envelope(), hint.hintId, OutputStatus.Displayed))
            assertNotNull(hidden.rig.plugin.followupWindowTurnIdForTest())

            hidden.rig.model.onRequest = answering()
            hidden.rig.plugin.handle(
                TranscriptEvent(hidden.rig.transcript("把亮度调到最低", turnId = "asr-followup", revision = 2)),
            )
            hidden.rig.tick()

            assertNull(hidden.rig.plugin.followupWindowTurnIdForTest(), "the usable final consumes the window")
            assertEquals(
                "asr-followup",
                hidden.rig.plugin.followupTurnStateForTest()?.first,
                "the production hidden-panel path reaches the PROACTIVE follow-up turn",
            )
            assertTrue(
                lines.any { it.startsWith("P3_TURN start") && "route=PROACTIVE" in it },
                "the follow-up is a routed agent turn: $lines",
            )
            assertTrue(lines.none { it.startsWith("P3_CONV panel_not_shown") }, lines.toString())
        }
    }

    @Test
    fun `a refused panel open skips the hint and records the reason`() {
        val hidden = hiddenPanelRig(accepted = false, deliverShown = false)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))

            assertTrue(hidden.rig.output.hints.isEmpty(), "a refused open must not submit a hidden hint")
            assertEquals(listOf(AssistantRoute.PROACTIVE), hidden.seam.routes)
            assertTrue(
                lines.any { it == "P3_PROACTIVE hint skipped reason=panel_not_shown" },
                lines.toString(),
            )
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup open") }, lines.toString())
            assertNull(hidden.rig.plugin.followupWindowTurnIdForTest())
            assertTrue(hidden.display.closes.isEmpty(), "a panel that never opened gets no CLOSE command")
            assertEquals(0, hidden.rig.scheduler.pending(), "the pre-armed acquisition wait is released")
        }
    }

    @Test
    fun `a panel that never reports shown skips the hint at the acquisition bound`() {
        val tasks = OffThreadTasks()
        val hidden = hiddenPanelRig(deliverShown = false, tasks = tasks)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertTrue(hidden.seam.requested.await(5, TimeUnit.SECONDS), "the hint requested the panel")
            assertTrue(hidden.rig.output.hints.isEmpty(), "the hint waits for PANEL_SHOWN")

            // The cycle's bounded panel wait (3 s) is the acquisition bound; its timeout is the real reason, and the
            // hint turn records the stable skip line instead of submitting a hint on a hidden panel.
            hidden.rig.tick(3_000)
            tasks.joinAll()

            assertTrue(hidden.rig.output.hints.isEmpty(), "a hidden hint must not be submitted after a timeout")
            assertTrue(
                lines.any { it == "P3_PROACTIVE hint skipped reason=panel_not_shown" },
                lines.toString(),
            )
            assertTrue(lines.any { it.startsWith("P3_CONV panel_not_shown") }, "the real bound is named: $lines")
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup open") }, lines.toString())
            assertNull(hidden.rig.plugin.followupWindowTurnIdForTest())
            assertEquals(0, hidden.rig.scheduler.pending(), "the timed-out acquisition round is released")
        }
    }

    @Test
    fun `a panel shown from the host thread releases a hint that waits for it`() {
        // Production keeps the hint turn on the plugin's task pool, and the host dispatcher delivers `PANEL_SHOWN` on
        // its own thread. Delivering the report inline from the opening seam would bypass the real wait/release, so
        // this test blocks the hint task and hands the report over from the test (host) thread.
        val tasks = OffThreadTasks()
        val hidden = hiddenPanelRig(deliverShown = false, tasks = tasks)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertTrue(hidden.seam.requested.await(5, TimeUnit.SECONDS), "the hint requested the panel")
            assertTrue(hidden.rig.output.hints.isEmpty(), "the hint task blocks until the panel reports its layout")

            hidden.rig.panelVisible = true
            hidden.rig.plugin.onPanelShown(AssistantRoute.PROACTIVE)
            tasks.joinAll()

            assertEquals(listOf(AssistantRoute.PROACTIVE), hidden.seam.routes)
            assertEquals(1, hidden.rig.output.hints.size, "the released hint task submits after the panel report")
            val listen = lines.single { it.startsWith("P3_CONV listen route=PROACTIVE") }
            assertTrue(listen.endsWith("shown=true"), listen)
            assertTrue(lines.none { it.startsWith("P3_CONV panel_not_shown") }, lines.toString())

            val hint = hidden.rig.output.hints.single()
            hidden.rig.plugin.handle(OutputStateEvent(hidden.rig.envelope(), hint.hintId, OutputStatus.Displayed))
            assertNotNull(hidden.rig.plugin.followupWindowTurnIdForTest(), "the ACK opens the follow-up window")
        }
    }

    @Test
    fun `a stop after the panel is acquired shows idle and leaves no timeout close`() {
        // Important 1: `acquireHintPanel` has already cleared `pendingHintPanel`, and no follow-up window exists until
        // the hint's `Displayed` ACK. Without tying the pre-armed round to the hint, STOP fell through both cleanup
        // branches and the 10 s listening round later closed the panel (`reason=timeout`) instead of the official idle.
        val hidden = hiddenPanelRig()

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertEquals(1, hidden.rig.output.hints.size)
            assertEquals(1, hidden.rig.scheduler.pending(), "PANEL_SHOWN armed the official 10 s window")

            hidden.rig.plugin.handle(UserAction(hidden.rig.envelope(), UserAction.Action.Stop))

            assertTrue(
                hidden.display.idleCalls.any { it.first == AssistantRoute.PROACTIVE },
                "STOP shows the official idle and keeps the panel",
            )
            assertEquals(0, hidden.rig.scheduler.pending(), "STOP releases the acquired hint's round")
            assertTrue(hidden.display.closes.isEmpty(), "STOP keeps the panel; no CLOSE is sent")

            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(
                lines.none { it.startsWith("P3_CONV close route=PROACTIVE") },
                "a stopped round must not time out into a CLOSE: $lines",
            )
        }
    }

    @Test
    fun `a newer silent trigger closes the replaced hint's pre-armed round`() {
        // Important 1, replacement half: the newer final is admitted once the hint turn is no longer in flight. It
        // replaces the submitted hint, the ACK will never open a window for the old one, and a silent newer trigger
        // would otherwise leave the old pre-armed round to time out into a CLOSE.
        val hidden = hiddenPanelRig(proactiveCooldownMillis = "0")

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了", turnId = "asr-1")))
            assertEquals(1, hidden.rig.output.hints.size)
            assertEquals(1, hidden.rig.scheduler.pending(), "the acquired hint listens on its pre-armed round")

            hidden.rig.model.onRequest = { request, listener -> streamText(request, listener, "保持沉默") }
            hidden.rig.plugin.handle(
                TranscriptEvent(hidden.rig.transcript("再帮我看一看", turnId = "asr-2", revision = 2)),
            )

            assertEquals(1, hidden.rig.output.hints.size, "the newer trigger stays silent")
            assertEquals(0, hidden.rig.scheduler.pending(), "the replaced hint's round is released with it")
            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(
                lines.none { it.startsWith("P3_CONV close route=PROACTIVE") },
                "no timeout CLOSE for a hint that was replaced: $lines",
            )
        }
    }

    @Test
    fun `a rejected submit through the hidden acquisition closes the pre-armed round`() {
        // Important 3: the legacy `SmartProactiveTurnTest` never acquires a panel, so it cannot exercise the
        // identity-checked `closeIfTurn` cleanup of the round the hidden-panel acquisition pre-armed.
        val hidden = hiddenPanelRig()
        hidden.rig.output.submission = OutputSubmission.Rejected

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))

            assertTrue(lines.any { it.startsWith("P3_PROACTIVE hint rejected") }, lines.toString())
            assertEquals(
                listOf(AssistantRoute.PROACTIVE),
                hidden.display.closes,
                "the round the acquisition opened is closed on the wire",
            )
            assertEquals(0, hidden.rig.scheduler.pending(), "the rejected hint leaves no listening deadline")
            assertNull(hidden.rig.plugin.followupWindowTurnIdForTest())
            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(lines.none { it.startsWith("P3_CONV close route=PROACTIVE") }, lines.toString())
        }
    }

    // ---- Task 9d fix: a hint cancelled while it waits for the panel ---------------------------------------------------

    @Test
    fun `a stop while the hint waits for the panel leaves no orphan round`() {
        // Important 3: the pre-arm has happened (`pendingHintPanel` is set) but `PANEL_SHOWN` has not. STOP must release
        // the acquisition cap and leave no 10 s round behind, and must not paint an idle on a panel that never came up.
        val tasks = OffThreadTasks()
        val hidden = hiddenPanelRig(deliverShown = false, tasks = tasks)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertTrue(hidden.seam.requested.await(5, TimeUnit.SECONDS))
            assertEquals(1, hidden.rig.scheduler.pending(), "the pre-arm's acquisition cap is the only deadline")

            hidden.rig.plugin.handle(UserAction(hidden.rig.envelope(), UserAction.Action.Stop))
            tasks.joinAll()

            assertEquals(0, hidden.rig.scheduler.pending(), "STOP releases the pre-armed acquisition round")
            assertTrue(hidden.rig.output.hints.isEmpty(), "a stopped hint does not submit")
            assertTrue(hidden.display.idleCalls.isEmpty(), "the panel never reported shown: no idle for it")
            assertTrue(hidden.display.closes.isEmpty(), "a panel that never came up gets no CLOSE")
            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(lines.none { it.startsWith("P3_CONV close route=PROACTIVE") }, lines.toString())
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE followup open") }, lines.toString())
        }
    }

    @Test
    fun `a panel close while the hint waits for the panel leaves no orphan round`() {
        val tasks = OffThreadTasks()
        val hidden = hiddenPanelRig(deliverShown = false, tasks = tasks)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertTrue(hidden.seam.requested.await(5, TimeUnit.SECONDS))

            hidden.rig.plugin.onPanelClosed(AssistantRoute.PROACTIVE)
            tasks.joinAll()

            assertEquals(0, hidden.rig.scheduler.pending(), "UI_CLOSED releases the acquisition round")
            assertTrue(hidden.rig.output.hints.isEmpty(), "a hint for a closed panel does not submit")
            assertTrue(hidden.display.closes.isEmpty(), "the panel is already gone; no CLOSE is sent")
            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(lines.none { it.startsWith("P3_CONV close route=PROACTIVE") }, lines.toString())
        }
    }

    @Test
    fun `help while the hint waits for the panel leaves no orphan round`() {
        val tasks = OffThreadTasks()
        val hidden = hiddenPanelRig(deliverShown = false, tasks = tasks)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertTrue(hidden.seam.requested.await(5, TimeUnit.SECONDS))
            // Help reuses the last final and runs its own WAKE turn; script it after the hint turn already made its
            // request, so this only changes the model's answer for Help.
            hidden.rig.model.onRequest = { request, listener -> streamText(request, listener, "在。") }

            hidden.rig.plugin.handle(UserAction(hidden.rig.envelope(), UserAction.Action.Help))
            tasks.joinAll()

            // Help's own WAKE turn legitimately arms its fallback deadline, so the assertion is about the PROACTIVE
            // round: releasing the acquisition must leave no listening round/close line behind on that route.
            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(lines.none { it.startsWith("P3_CONV close route=PROACTIVE") }, lines.toString())
            assertTrue(lines.none { it.startsWith("P3_CONV idle route=PROACTIVE") }, lines.toString())
            assertTrue(lines.none { it.startsWith("P3_CONV panel_not_shown route=PROACTIVE") }, lines.toString())
            assertTrue(
                hidden.rig.output.hints.none { it.route == AssistantRoute.PROACTIVE },
                "no PROACTIVE hint may submit after Help: ${hidden.rig.output.hints.map { it.route }}",
            )
            assertTrue(hidden.display.closes.none { it == AssistantRoute.PROACTIVE }, lines.toString())
        }
    }

    @Test
    fun `a session end while the hint waits for the panel leaves no orphan round`() {
        val tasks = OffThreadTasks()
        val hidden = hiddenPanelRig(deliverShown = false, tasks = tasks)

        withLines { lines ->
            arm(hidden.rig)
            hidden.rig.plugin.handle(TranscriptEvent(hidden.rig.transcript("现在几点了")))
            assertTrue(hidden.seam.requested.await(5, TimeUnit.SECONDS))

            hidden.rig.plugin.deactivate(DeactivationReason.Stopped)
            tasks.joinAll()

            assertEquals(0, hidden.rig.scheduler.pending(), "a session end releases the acquisition round")
            assertTrue(hidden.rig.output.hints.isEmpty(), "a session-end cancellation does not submit")
            assertTrue(hidden.display.closes.isEmpty(), "a session end sends no CLOSE for a panel never shown")
            hidden.rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
            assertTrue(lines.none { it.startsWith("P3_CONV close route=PROACTIVE") }, lines.toString())
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

    @Test
    fun `a pinned window times out with the pinned reason and keeps the panel`() {
        val (rig, display, _) = rigWithDisplayedHint()
        rig.plugin.handle(PinChanged(rig.envelope(), AssistantRoute.PROACTIVE))

        withLines { lines ->
            rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "the pinned round idles and ends the window")
            assertNull(rig.plugin.followupTurnForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=pinned_timeout" }, lines.toString())
            assertTrue(lines.any { it == "P3_CONV idle route=PROACTIVE reason=timeout" }, lines.toString())
            assertTrue(display.idleCalls.any { it.first == AssistantRoute.PROACTIVE }, "the pinned timeout shows idle")
            assertTrue(display.closes.isEmpty(), "the pinned idle keeps the panel until its own 120 s close")
            assertTrue(rig.cancels.isEmpty(), "the displayed hint keeps its TTL")

            // The pinned close is the cycle's separate 120 s timer; the plugin's close callback is not called again.
            rig.tick(OfficialConversation.PINNED_CLOSE_MILLIS)
            assertEquals(listOf(AssistantRoute.PROACTIVE), display.closes)
            assertNull(rig.plugin.followupTurnForTest())
        }
    }

    // ---- spec §4.5: the open follow-up window is a busy conversation -------------------------------------------------

    @Test
    fun `a context change inside the follow-up window does not start a second proactive turn`() {
        val gate = RecordingGate()
        val (rig, _, _) = rigWithDisplayedHint(interventionGate = gate, proactiveCooldownMillis = "1000")
        val windowTurn = rig.plugin.followupWindowTurnIdForTest()
        assertNotNull(windowTurn)
        val gateCalls = gate.seen.size
        val requests = rig.model.requests.size

        withLines { lines ->
            // Past the hint's own cooldown (1 s) but inside the 10 s follow-up window: without the busy rule this
            // foreground-changing `ContextChanged` is admitted and starts a HINT_TURN beside the open PROACTIVE round.
            rig.tick(2_000)
            assertEquals(windowTurn, rig.plugin.followupWindowTurnIdForTest())

            rig.plugin.handle(ContextChanged(rig.envelope(), 2, mapOf("app.foreground" to "设置")))

            assertEquals(requests, rig.model.requests.size, "the follow-up window is busy: no second HINT_TURN")
            assertEquals(gateCalls, gate.seen.size, "a busy trigger is refused before the intervention gate")
            assertTrue(lines.any { it == "P3_PROACTIVE park reason=BUSY" }, lines.toString())
            assertEquals(windowTurn, rig.plugin.followupWindowTurnIdForTest(), "the refused trigger leaves the window")
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

    // ---- the usable final: the Task 7 follow-up turn ---------------------------------------------------------------

    @Test
    fun `a usable final closes the window and starts the follow-up turn`() {
        val (rig, _, _) = rigWithDisplayedHint()
        rig.model.onRequest = answering()

        withLines { lines ->
            val proactiveLinesBefore = lines.count { it.startsWith("P3_PROACTIVE") }
            val requestsBefore = rig.model.requests.size

            rig.plugin.handle(TranscriptEvent(rig.transcript("把亮度调到最低", turnId = "asr-followup", revision = 2)))
            rig.tick()

            assertNull(rig.plugin.followupWindowTurnIdForTest(), "a usable final consumes the window")
            // Task 7 completes the seam: the hand-off is consumed by a real PROACTIVE turn immediately.
            assertNull(rig.plugin.followupTurnForTest(), "the hand-off is no longer a pending record")
            assertEquals(
                "asr-followup",
                rig.plugin.followupTurnStateForTest()?.first,
                "the accepted utterance became the PROACTIVE follow-up turn",
            )
            assertEquals(requestsBefore + 1, rig.model.requests.size, "the follow-up ran the normal model loop")
            assertEquals(
                proactiveLinesBefore,
                lines.count { it.startsWith("P3_PROACTIVE") },
                "the utterance is not re-judged as a new proactive trigger",
            )
            assertTrue(
                lines.any { it.startsWith("P3_TURN start") && "route=PROACTIVE" in it },
                "the follow-up turn is a routed agent turn: $lines",
            )
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
    fun `a wake preempts a running follow-up and opens only the wake turn`() {
        val display = SmartRecordingDisplay()
        val tasks = OffThreadTasks()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display), tasks = tasks)
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        awaitHints(rig, 1)
        val hint = rig.output.hints.single()
        rig.panelVisible = true
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        assertNotNull(rig.plugin.followupWindowTurnIdForTest())

        // Task 7 runs the follow-up as a real, blocking agent turn: hold it inside the transport so the wake really
        // arrives while it is in flight (P3-4's shape, instead of the pre-Task-7 pending hand-off).
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.model.onRequest = { request, listener ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            streamText(request, listener, "迟到的回答。")
        }
        rig.plugin.handle(TranscriptEvent(rig.transcript("把亮度调到最低", turnId = "asr-followup")))
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the follow-up must be in flight when the wake arrives")

        withLines { lines ->
            rig.plugin.handle(rig.wakeEvent())

            // P3-4: the wake cancels the running follow-up, but not the hint already on the panel.
            assertNull(rig.plugin.followupTurnStateForTest(), "the wake cancels the running follow-up turn")
            assertNull(rig.plugin.followupWindowTurnIdForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE preempt reason=WAKE_PREEMPTED" }, lines.toString())

            val wakeState = display.states.single { it.route == AssistantRoute.WAKE }
            assertEquals(OfficialStatus.LISTENING.wire, wakeState.status)
            assertTrue(
                rig.cancels.none { it.third == hint.sourceTurnId },
                "the displayed hint is not cancelled by the preemption",
            )
        }

        release.countDown()
        tasks.joinAll()
        rig.tick()
        assertEquals(1, rig.output.hints.size, "the preempted follow-up's late answer must not be displayed")
        assertEquals(2, rig.model.requests.size, "the follow-up made exactly its one request before the wake")
        assertNull(rig.plugin.followupTurnStateForTest())
    }

    @Test
    fun `a wake the scene cooldown refuses still preempts the follow-up window`() {
        val display = SmartRecordingDisplay()
        // A long wake cooldown so the second wake stays refused; the proactive hint has no prior cooldown.
        val rig = SmartTestRig(cooldownNanos = 30_000_000_000L, panelVisible = true)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)

        // The cooldown starts at an admitted wake. STOP ends that turn but not the scene's cooldown, which lets the
        // follow-up window open while a wake would still be refused.
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-proactive")))
        val hint = rig.output.hints.single()
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        assertNotNull(rig.plugin.followupWindowTurnIdForTest())
        val requests = rig.model.requests.size
        val cancels = rig.cancels.size
        val wakeStates = display.states.count { it.route == AssistantRoute.WAKE }

        withLines { lines ->
            rig.plugin.handle(rig.wakeEvent())

            // A wake word is an explicit route switch: even a refused one ends the follow-up window rather than
            // letting the wake segment's echo/next speech be consumed as PROACTIVE after the wearer asked for WAKE.
            assertNull(rig.plugin.followupWindowTurnIdForTest(), "a refused wake still preempts the window")
            assertTrue(lines.any { it == "P3_PROACTIVE preempt reason=WAKE_PREEMPTED" }, lines.toString())
            assertEquals(requests, rig.model.requests.size, "a refused wake opens no turn and runs no loop")
            assertEquals(wakeStates, display.states.count { it.route == AssistantRoute.WAKE }, "no new WAKE level")
            assertEquals(1, rig.auditEntries.count { it.outcome == "CooldownRefused" }, "the wake really was refused")
            assertEquals(cancels, rig.cancels.size, "the displayed hint is not cancelled by the refused wake")
            assertEquals(0, rig.scheduler.pending(), "the preempted round's deadline is released")
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

    /**
     * Final review Important 1: the resolver's "a WAKE final never enters the proactive session" rule must hold while
     * the WAKE turn is **Running**, not only while it is `AwaitingTranscript`.
     *
     * The stray final is delivered from inside the WAKE turn's own model request, which is exactly the moment that turn
     * is `Running`: `acceptFollowupTranscript` refuses it (WAKE is busy), and `acceptTranscript` used to remember it as
     * Help's next wake utterance before its phase check. The proactive trigger then parked it as BUSY and the wake
     * turn's end resumed it as a HINT_TURN. The final belongs to WAKE and must do none of that.
     */
    @Test
    fun `a usable final while the wake turn is running never enters the proactive session`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        val hint = rig.output.hints.single()

        // The hint's ACK lands after the wake opened, so the PROACTIVE window is armed while WAKE owns the panel.
        rig.plugin.handle(rig.wakeEvent())
        rig.panelVisible = true
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        val windowTurn = rig.plugin.followupWindowTurnIdForTest()
        assertNotNull(windowTurn, "the displayed hint armed the proactive window")

        var strayDelivered = false
        rig.model.onRequest = { request, listener ->
            if (request.turnId.startsWith("proactive-")) {
                // Under the defect this is the parked trigger resumed as a HINT_TURN after the wake answer.
                proposing("不该出现的提醒")(request, listener)
            } else {
                if (!strayDelivered) {
                    strayDelivered = true
                    // Reentrant, from inside the WAKE turn's request: the turn's phase is Running here.
                    rig.plugin.handle(TranscriptEvent(rig.transcript("帮我记一条笔记", turnId = "asr-stray", revision = 3)))
                }
                streamText(request, listener, "唤醒回答。")
            }
        }

        withLines { lines ->
            rig.plugin.handle(TranscriptEvent(rig.transcript("打开设置", turnId = "asr-wake", revision = 2)))
            assertTrue(strayDelivered, "the stray final was delivered while the WAKE turn was Running")
            // The closing wake answer is emitted through the assembler's merge interval, like every fallback answer.
            rig.tick()

            assertTrue(
                lines.none { it.startsWith("P3_TRANSCRIPT") && "turn=asr-stray" in it },
                "a Running WAKE turn's final must not reach acceptTranscript: $lines",
            )
            assertTrue(lines.none { it == "P3_PROACTIVE park reason=BUSY" }, lines.toString())
            assertTrue(lines.none { it.startsWith("P3_PROACTIVE resume") }, lines.toString())
            assertEquals(0, lines.count { it.startsWith("P3_PROACTIVE hint") }, "no HINT_TURN may start: $lines")
            assertEquals(
                listOf("现在正适合提醒他一句", "唤醒回答。"),
                rig.output.hints.map { it.text },
                "the WAKE answer is the only new hint",
            )
            assertEquals(2, rig.model.requests.size, "only the hint turn and the WAKE turn issued requests")
            assertEquals(windowTurn, rig.plugin.followupWindowTurnIdForTest(), "the WAKE final did not touch the window")
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

    @Test
    fun `a wake answer never requests the proactive panel open`() {
        // WAKE's own window request belongs to the composition's wake detector; the plugin's proactive acquisition
        // must not add a second request on the wake route.
        val seam = PanelSeam()
        val rig = SmartTestRig(requestPanelOpen = seam::request)
        seam.hints = { rig.output.hints.size }
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        withLines {
            rig.wakeAndFinal("问题")

            assertEquals(listOf(AssistantRoute.WAKE), rig.output.hints.map { it.route })
            assertTrue(seam.routes.isEmpty(), "the wake route must keep its own panel path: ${seam.routes}")
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
