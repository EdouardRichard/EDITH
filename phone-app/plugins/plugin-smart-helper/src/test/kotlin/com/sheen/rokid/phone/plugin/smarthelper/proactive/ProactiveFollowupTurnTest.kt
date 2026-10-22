package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.ContextChanged
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.plugin.smarthelper.OffThreadTasks
import com.sheen.rokid.phone.plugin.smarthelper.SmartRecordingDisplay
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.smartAction
import com.sheen.rokid.phone.plugin.smarthelper.smartStores
import com.sheen.rokid.phone.plugin.smarthelper.smartToolCall
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import com.sheen.rokid.phone.plugin.smarthelper.turn.SmartTurnPhase
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.contracts.ToolPhase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.3 Phase 3 Task 7: the **FOLLOWUP_TURN** engine and the route-parameterized display.
 *
 * Task 5 produced a hint; Task 6 opened its bounded PROACTIVE listening window and handed a usable final over. Task 7
 * runs that follow-up through the shared `AgentLoop`/`SmartTurn` machinery with the **normal** tool face, and settles
 * its answer through the PROACTIVE route of the same `ConversationCycle` the wake route uses - a mechanism shared, a
 * session not (P3-13).
 *
 * Only PROACTIVE route deltas are covered here. The shared loop/tool/context/display/confirmation engine is already
 * pinned by the WAKE suites; these tests answer the questions that engine cannot: is the follow-up really a full turn,
 * do its rows land on the PROACTIVE route, does the answer continue the **PROACTIVE** conversation only, and are the
 * route's display/speech identities kept apart from WAKE's.
 */
class ProactiveFollowupTurnTest {

    /** One scripted model reply per request, in wire order. */
    private class ModelScript {
        private val queue = ArrayDeque<(ModelRequest, (ModelStreamEvent) -> Unit) -> Unit>()

        fun next(handler: (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit) {
            queue += handler
        }

        fun install(rig: SmartTestRig) {
            rig.model.onRequest = { request, listener ->
                val handler = queue.removeFirstOrNull()
                    ?: error("unscripted model request for turn=${request.turnId}")
                handler(request, listener)
            }
        }
    }

    private class FollowupRig(
        val rig: SmartTestRig,
        val display: SmartRecordingDisplay,
        val hint: Assistance,
        val script: ModelScript,
        val stores: Map<ContextRoute, ConversationStore>,
    )

    /**
     * A gate that always intervenes and records every judgement, so "the parked trigger never reached the decision
     * gate" is observable rather than inferred. `ContextChanged` only evaluates an ambient trigger when a gate exists.
     */
    private class AlwaysInterveneGate : ProactiveGate {
        val seen = mutableListOf<ProactiveGateState>()
        override val form = GateForm.NOUL
        override fun shouldIntervene(state: ProactiveGateState): GateOutcome {
            seen += state
            return GateOutcome(true, 0.9, 1.0)
        }
    }

    /** The host's perception signal, the plugin's own arming evidence (spec §16.2). */
    private fun arm(rig: SmartTestRig, label: AcousticLabel = AcousticLabel.Speech) {
        rig.plugin.handle(AcousticEvent(rig.envelope(), label))
    }

    /** A `propose_hint` call followed by the terminal, as the transport delivers it. */
    private fun proposing(text: String = "现在正适合提醒他一句"): (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit =
        { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"$text"}""", requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

    /** One plain answer, the shape a completed follow-up request ends in. */
    private fun answering(text: String): (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit =
        { request, listener -> streamText(request, listener, text) }

    /** One tool call followed by the terminal; the loop then makes the answer request. */
    private fun callingTool(
        name: String,
        argsJson: String = "{}",
    ): (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit = { request, listener ->
        listener(smartToolCall("call-1", name, argsJson, requestId = request.requestId))
        listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
    }

    /** Polls the asynchronous hint turn (the plugin's task pool) and fails if it never submits. */
    private fun awaitHints(rig: SmartTestRig, expected: Int, millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (rig.output.hints.size < expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, rig.output.hints.size, "the expected hint was not submitted")
    }

    /** Polls for one route's submitted hint, releasing the fake scheduler as it goes. */
    private fun awaitRouteHint(rig: SmartTestRig, route: AssistantRoute, millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            rig.tick()
            if (rig.output.hints.any { it.route == route }) return
            Thread.sleep(5)
        }
        assertTrue(rig.output.hints.any { it.route == route }, "no ${route.name} hint was submitted")
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
     * A displayed hint and therefore an armed PROACTIVE follow-up window, plus the model scripts the follow-up rounds
     * consume. The hint request is scripted here; every follow-up round must add its own handlers.
     */
    private fun rigWithDisplayedHint(
        continueDialogue: Boolean = false,
        /** `null` models a legacy no-store host: the request falls back to `AgentRequest.instructions`. */
        stores: Map<ContextRoute, ConversationStore>? = smartStores(),
        descriptors: List<com.sheen.rokid.plugin.ActionDescriptor> = listOf(
            smartAction("device", title = "调节亮度"),
            smartAction("open", title = "打开应用"),
        ),
        maxLevel: String? = null,
        tasks: TaskScope? = null,
        channel: ConfirmationChannel? = null,
        perception: com.sheen.rokid.plugin.PerceptionProvider? = null,
        hintText: String = "现在正适合提醒他一句",
        /** The intervention gate; non-null is what lets a `ContextChanged` reach the proactive admission. */
        interventionGate: ProactiveGate? = null,
        /** The configured hint cooldown, so a test can reach admission after the hint without waiting 60 s. */
        proactiveCooldownMillis: String? = null,
    ): FollowupRig {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(
            panelVisible = true,
            continueDialogue = continueDialogue,
            conversationStores = stores,
            interventionGate = interventionGate,
        )
        rig.actions.descriptors += descriptors
        val overrides = buildMap {
            put(Capability.ConversationDisplay, display)
            channel?.let { put(Capability.Confirmation, it) }
            perception?.let { put(Capability.Perception, it) }
        }
        rig.activate(
            maxLevel = maxLevel,
            overrides = overrides,
            tasks = tasks,
            proactiveCooldownMillis = proactiveCooldownMillis,
        )
        val script = ModelScript()
        script.install(rig)
        script.next(proposing(hintText))
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        awaitHints(rig, 1)
        val hint = rig.output.hints.single()
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        assertNotNull(rig.plugin.followupWindowTurnIdForTest(), "the displayed hint must arm the PROACTIVE window")
        return FollowupRig(rig, display, hint, script, stores ?: emptyMap())
    }

    /** Hands one usable final to the armed follow-up window and lets the inline run reach its answer. */
    private fun injectFollowup(
        rig: FollowupRig,
        text: String,
        turnId: String = "asr-followup",
        revision: Long = 2,
        flush: Boolean = true,
    ) {
        rig.rig.plugin.handle(
            TranscriptEvent(rig.rig.transcript(text, turnId = turnId, revision = revision)),
        )
        if (flush) rig.rig.tick()
    }

    /** Presents the question and leaves it open; [cancelled] proves the host's card was withdrawn. */
    private class OpenChannel : ConfirmationChannel {
        val questionUp = CountDownLatch(1)
        val presented = CopyOnWriteArrayList<String>()
        val cancelled = AtomicInteger()

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            presented += requestId
            questionUp.countDown()
            return object : CancellationHandle {
                override fun cancel() { cancelled.incrementAndGet() }
            }
        }
    }

    // ---- spec §4.4: FOLLOWUP_TURN is a full agent turn --------------------------------------------------------------

    @Test
    fun `a follow-up final runs the normal tool face and displays on the proactive route`() {
        val perception = com.sheen.rokid.phone.plugin.smarthelper.SmartFakePerception()
        val rig = rigWithDisplayedHint(perception = perception)
        rig.script.next(callingTool("device", """{"action":"brightness"}"""))
        rig.script.next(answering("亮度已调低。"))

        withLines { lines ->
            injectFollowup(rig, "把亮度调到最低")

            // The normal six-tool registry really ran a device action: the follow-up is not a hint-only turn.
            assertEquals(listOf("device" to """{"action":"brightness"}"""), rig.rig.actions.executed)
            assertEquals(3, rig.rig.model.requests.size, "hint + tool round + answer request")

            val answer = rig.rig.output.hints.last()
            assertEquals(AssistantRoute.PROACTIVE, answer.route, "the follow-up answer belongs to the PROACTIVE conversation")
            assertEquals("亮度已调低。", answer.text)
            assertEquals("asr-followup", answer.sourceTurnId, "the ASR turn id is the follow-up's identity")

            val toolRows = rig.display.toolStatuses.filter { it.route == AssistantRoute.PROACTIVE }
            assertEquals(listOf(ToolPhase.START, ToolPhase.END), toolRows.map { it.phase })
            assertEquals("调节亮度", toolRows.first().label, "the host action's own title is the row copy")
            assertTrue(
                rig.display.toolStatuses.none { it.route == AssistantRoute.WAKE },
                "a follow-up tool row must never be filed under WAKE",
            )
            assertTrue(rig.display.assistantPartials.isNotEmpty(), "the answer streamed on the PROACTIVE route")
            assertTrue(rig.display.assistantPartials.all { it.route == AssistantRoute.PROACTIVE })
            val recordedAnswer = rig.display.assistantFinals.last()
            assertEquals(AssistantRoute.PROACTIVE, recordedAnswer.route)
            assertEquals("亮度已调低。", recordedAnswer.text)
            assertEquals(answer.sourceTurnId, recordedAnswer.turnId)
            assertTrue(
                rig.display.userFinals.any { it.route == AssistantRoute.PROACTIVE && it.text == "把亮度调到最低" },
                "the wearer's follow-up is the PROACTIVE conversation's user row",
            )

            val proactiveStates = rig.display.states.filter { it.route == AssistantRoute.PROACTIVE }
            assertTrue(proactiveStates.any { it.status == OfficialStatus.TOOL.wire }, proactiveStates.toString())
            assertEquals(OfficialStatus.ANSWERING.wire, proactiveStates.last().status)
            assertEquals("asr-followup", proactiveStates.last().turnId)

            val turnLines = lines.filter { it.startsWith("P3_TURN") }
            assertEquals(
                1,
                turnLines.count { it.startsWith("P3_TURN start") && "route=PROACTIVE" in it },
                turnLines.toString(),
            )
            assertEquals(
                1,
                turnLines.count { it.startsWith("P3_TURN end") && "route=PROACTIVE" in it },
                turnLines.toString(),
            )
            assertTrue(
                lines.any { it.startsWith("P3_PROMPT") && "route=PROACTIVE_FOLLOWUP" in it },
                "the request must render the follow-up prompt route: $lines",
            )
            assertTrue(lines.any { it.startsWith("P3_CONV tool route=PROACTIVE") }, lines.toString())
            assertTrue(lines.any { it.startsWith("P3_CONV answer route=PROACTIVE") }, lines.toString())
            // Full perception (spec §4.4/§7.5): the hint turn and the follow-up each opened and closed their own
            // boundary, and the follow-up's request rendered a perception block (count-only evidence).
            assertEquals(2, perception.beginTurns, "the hint turn and the follow-up each open a perception turn")
            assertEquals(2, perception.endTurns, "both boundaries are closed")
            assertTrue(lines.any { it.startsWith("P3_PERCEPTION") }, "the follow-up request rendered perception")

            // P3-13: the PROACTIVE store received the whole follow-up round; the WAKE store received nothing.
            val proactiveHistory = rig.stores.getValue(ContextRoute.PROACTIVE).history()
            assertTrue(
                proactiveHistory.any { it is ModelHistoryItem.Text && it.role == MessageRole.USER && it.text == "把亮度调到最低" },
                proactiveHistory.toString(),
            )
            assertTrue(
                proactiveHistory.any { it is ModelHistoryItem.Text && it.role == MessageRole.ASSISTANT && it.text == "亮度已调低。" },
                proactiveHistory.toString(),
            )
            assertTrue(
                rig.stores.getValue(ContextRoute.WAKE).history().isEmpty(),
                "the follow-up must never append to the WAKE store",
            )
        }
    }

    @Test
    fun `a follow-up on the legacy no-store path renders the follow-up prompt posture`() {
        // The 0.3 Task 1 carry (minor 4): `ContextManager` maps ContextRoute.PROACTIVE to the HINT plan, so a legacy
        // no-store request must not inherit the hint's instructions. The follow-up renders its own pinned plan.
        val rig = rigWithDisplayedHint(stores = null)
        rig.script.next(answering("好的。"))

        injectFollowup(rig, "打开设置")

        val followRequest = rig.rig.model.requests.last()
        assertEquals(
            PromptCatalog.plan(PromptRoute.PROACTIVE_FOLLOWUP, null).systemText,
            followRequest.instructions,
            "the follow-up must ask the model in the follow-up posture, not the hint's",
        )
    }

    // ---- continuous dialogue (A-04) ----------------------------------------------------------------------------------

    @Test
    fun `continuous dialogue keeps both rounds in the proactive conversation`() {
        val rig = rigWithDisplayedHint(continueDialogue = true)
        rig.script.next(answering("第一轮完成。"))
        rig.script.next(answering("第二轮完成。"))

        withLines { lines ->
            injectFollowup(rig, "打开设置", turnId = "asr-1")
            val first = rig.rig.output.hints.last()
            assertEquals("第一轮完成。", first.text)

            // The displayed answer settles on the PROACTIVE route and opens the next PROACTIVE listening round - not a
            // new hint turn, not the WAKE route.
            rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), first.hintId, OutputStatus.Displayed))
            val continueLine = lines.single { it.startsWith("P3_CONV continue") }
            assertTrue(continueLine.startsWith("P3_CONV continue route=PROACTIVE"), continueLine)
            assertTrue(continueLine.contains("from=asr-1"), continueLine)
            val waiting = rig.rig.plugin.followupTurnStateForTest()
            assertNotNull(waiting, "continueOn must open the next PROACTIVE turn")
            assertEquals(SmartTurnPhase.AwaitingTranscript, waiting.second)
            val listening = rig.display.states.last { it.route == AssistantRoute.PROACTIVE }
            assertEquals(OfficialStatus.LISTENING.wire, listening.status)
            assertEquals(waiting.first, listening.turnId, "the next window is armed for the new PROACTIVE turn")

            injectFollowup(rig, "打开蓝牙", turnId = "asr-2", revision = 3)
            val second = rig.rig.output.hints.last()
            assertEquals("第二轮完成。", second.text)
            assertEquals("asr-2", second.sourceTurnId)

            assertEquals(
                2,
                lines.count { it.startsWith("P3_TURN start") && "route=PROACTIVE" in it },
                "two FOLLOWUP_TURN rounds ran on the PROACTIVE route: $lines",
            )
            assertEquals(
                1,
                lines.count { it.startsWith("P3_CONV continue") && "route=PROACTIVE" in it },
                lines.toString(),
            )

            // One PROACTIVE conversation: the second round's request was assembled from what the first round left.
            val secondRequest = rig.rig.model.requests.last()
            assertTrue(
                secondRequest.history.any { it is ModelHistoryItem.Text && it.text == "第一轮完成。" },
                "round 2 must continue the PROACTIVE transcript: ${secondRequest.history}",
            )
            val history = rig.stores.getValue(ContextRoute.PROACTIVE).history()
            assertTrue(history.any { it is ModelHistoryItem.Text && it.text == "打开设置" })
            assertTrue(history.any { it is ModelHistoryItem.Text && it.text == "第一轮完成。" })
            assertTrue(history.any { it is ModelHistoryItem.Text && it.text == "打开蓝牙" })
            assertTrue(history.any { it is ModelHistoryItem.Text && it.text == "第二轮完成。" })
            assertTrue(rig.stores.getValue(ContextRoute.WAKE).history().isEmpty())
        }
    }

    @Test
    fun `continue off closes the proactive answer without starting a new round`() {
        val rig = rigWithDisplayedHint(continueDialogue = false)
        rig.script.next(answering("第一轮完成。"))

        withLines { lines ->
            injectFollowup(rig, "打开设置")
            val answer = rig.rig.output.hints.last()
            rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), answer.hintId, OutputStatus.Displayed))

            assertTrue(lines.none { it.startsWith("P3_CONV continue") }, "continueOff must not open a next round")
            rig.rig.tick(1_000)

            assertTrue(
                lines.any { it == "P3_CONV close route=PROACTIVE reason=continue_off" },
                lines.toString(),
            )
            assertNull(rig.rig.plugin.followupTurnStateForTest(), "the finished follow-up is torn down")
            assertTrue(rig.display.closes.contains(AssistantRoute.PROACTIVE))
        }
    }

    /**
     * The Task 6 carry, made explicit: the old PROACTIVE listening round used to be closed with `onClose`, which reset
     * the route's turn id. The follow-up turn must re-arm the cycle (or keep the usable final's turn on it) before it
     * runs, or its answer can neither settle nor continue. Here the answer's own level carries the follow-up's id and
     * its displayed ACK is what opens the next PROACTIVE round.
     */
    @Test
    fun `the follow-up answer settles on the re-armed proactive cycle and continues`() {
        val rig = rigWithDisplayedHint(continueDialogue = true)
        rig.script.next(answering("好的。"))

        withLines { lines ->
            injectFollowup(rig, "打开设置", turnId = "asr-follow-up")
            val answer = rig.rig.output.hints.last()
            assertEquals("asr-follow-up", answer.sourceTurnId)

            val answering = rig.display.states.last { it.route == AssistantRoute.PROACTIVE }
            assertEquals(OfficialStatus.ANSWERING.wire, answering.status)
            assertEquals(
                "asr-follow-up",
                answering.turnId,
                "the PROACTIVE cycle must carry the follow-up turn id, not a route reset by a close",
            )

            rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), answer.hintId, OutputStatus.Displayed))
            assertTrue(
                lines.any { it.startsWith("P3_CONV continue route=PROACTIVE") },
                "the displayed follow-up answer must settle and continue: $lines",
            )
            val next = rig.rig.plugin.followupTurnStateForTest()
            assertNotNull(next)
            assertEquals(SmartTurnPhase.AwaitingTranscript, next.second)
            assertNotEquals("asr-follow-up", next.first, "the continuation is a new PROACTIVE turn")
        }
    }

    /**
     * Final review Important 2: the follow-up answer's emit clears `followupBusy` (the ring stops exactly when the
     * sentence appears), but the continuation is not open until that answer's `Displayed` ACK settles it. In that gap
     * the installed FOLLOWUP_TURN is still the live PROACTIVE round; a foreground-changing `ContextChanged` after the
     * hint cooldown must not start a HINT_TURN whose own ACK would install a window over the continuation.
     */
    @Test
    fun `a context change before the continuation ACK starts no hint`() {
        val gate = AlwaysInterveneGate()
        val rig = rigWithDisplayedHint(
            continueDialogue = true,
            interventionGate = gate,
            proactiveCooldownMillis = "1000",
        )
        rig.script.next(answering("第一轮完成。"))
        // Only the defect consumes this handler: a HINT_TURN started in the answer->continuation gap.
        rig.script.next(proposing("不该出现的提醒"))

        withLines { lines ->
            injectFollowup(rig, "打开设置", turnId = "asr-1")
            val answer = rig.rig.output.hints.last()
            assertEquals("第一轮完成。", answer.text)
            // The answer is out; its ACK has not arrived, so no continuation exists yet.
            assertEquals(SmartTurnPhase.Idle, rig.rig.plugin.followupTurnStateForTest()?.second)
            assertTrue(lines.none { it.startsWith("P3_CONV continue") }, lines.toString())
            val requests = rig.rig.model.requests.size
            val gateCalls = gate.seen.size

            // Past the configured 1 s hint cooldown, before the answer's ACK.
            rig.rig.tick(2_000)
            rig.rig.plugin.handle(ContextChanged(rig.rig.envelope(), 2, mapOf("app.foreground" to "设置")))

            assertEquals(
                requests,
                rig.rig.model.requests.size,
                "a pending continuation is busy: no HINT_TURN may start",
            )
            assertEquals(gateCalls, gate.seen.size, "a busy trigger is refused before the intervention gate")
            assertTrue(lines.any { it == "P3_PROACTIVE park reason=BUSY" }, lines.toString())
            assertEquals(2, rig.rig.output.hints.size, "the hint and its follow-up answer are the only hints")

            // The delayed ACK still opens the continuation the answer was waiting for.
            rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), answer.hintId, OutputStatus.Displayed))
            val continuation = rig.rig.plugin.followupTurnStateForTest()
            assertNotNull(continuation, "the pending continuation still opens after the ACK")
            assertEquals(SmartTurnPhase.AwaitingTranscript, continuation.second)
            assertTrue(
                lines.any { it.startsWith("P3_CONV continue route=PROACTIVE") },
                "the continuation survived the refused hint: $lines",
            )
        }
    }

    // ---- spec §4.6: only a WAKE turn releases the parked proactive trigger -------------------------------------------

    /**
     * The Task 7 residual defect: `runTurnBody` is shared with the FOLLOWUP_TURN, so its end used to call
     * `releaseParkedProactive()` for **every** route. A trigger parked while the follow-up was running (the follow-up is
     * a live conversation, so the admission refuses it as BUSY) was then resumed as a HINT_TURN right after the
     * follow-up answer. That hint's `Displayed` ACK installed a fresh PROACTIVE window over the continuation round the
     * follow-up answer had just opened, so the wearer's next sentence became the hint's follow-up and the continuation
     * was silently superseded.
     *
     * Controller ruling: only a **WAKE** turn may release the proactive work it made wait. A follow-up leaves the
     * trigger parked (no new queue, no second HINT_TURN) and the continuation round stays the conversation's own.
     */
    @Test
    fun `a trigger parked during a follow-up is not released as a new hint`() {
        val gate = AlwaysInterveneGate()
        val rig = rigWithDisplayedHint(continueDialogue = true, interventionGate = gate)
        rig.script.next { request, listener ->
            // A foreground-changing ContextChanged lands while the follow-up is genuinely in flight. The follow-up is
            // busy and the context is evaluated, so the trigger is parked instead of run.
            rig.rig.plugin.handle(ContextChanged(rig.rig.envelope(), 2, mapOf("app.foreground" to "设置")))
            streamText(request, listener, "第一轮完成。")
        }
        // Only the buggy release consumes this handler: a HINT_TURN emitted after the follow-up answer.
        rig.script.next(proposing("不该出现的提醒"))
        // The continuation round's own answer.
        rig.script.next(answering("第二轮完成。"))

        withLines { lines ->
            injectFollowup(rig, "打开设置", turnId = "asr-1")

            // The follow-up answered and nothing else ran: no resumed HINT_TURN, no second model request.
            val answer = rig.rig.output.hints.last()
            assertEquals("第一轮完成。", answer.text)
            assertEquals(
                listOf("现在正适合提醒他一句", "第一轮完成。"),
                rig.rig.output.hints.map { it.text },
                "the follow-up answer is the only new hint",
            )
            assertEquals(2, rig.rig.model.requests.size, "no HINT_TURN ran after the follow-up")
            assertEquals(1, gate.seen.size, "a parked trigger never reaches the decision gate")
            assertTrue(lines.any { it == "P3_PROACTIVE park reason=BUSY" }, "the context change was parked: $lines")
            assertTrue(
                lines.none { it.startsWith("P3_PROACTIVE resume") },
                "a follow-up must not release the parked trigger: $lines",
            )
            assertEquals(
                0,
                lines.count { it.startsWith("P3_PROACTIVE hint") },
                "no P3_PROACTIVE hint may follow the follow-up answer: $lines",
            )

            // Acknowledge every hint the host received. The follow-up answer's ACK opens the continuation round; the
            // resumed hint's ACK (absent once fixed) is what used to install a new window over that round.
            rig.rig.output.hints.forEach { submitted ->
                rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), submitted.hintId, OutputStatus.Displayed))
            }
            assertNull(
                rig.rig.plugin.followupWindowTurnIdForTest(),
                "the parked trigger must not become a new hint window",
            )
            assertTrue(
                lines.none { it.startsWith("P3_PROACTIVE followup open") },
                "the resumed hint opened no new follow-up window: $lines",
            )
            val continuation = rig.rig.plugin.followupTurnStateForTest()
            assertNotNull(continuation, "the displayed follow-up answer still continues")
            assertEquals(SmartTurnPhase.AwaitingTranscript, continuation.second)
            assertTrue(
                lines.any { it.startsWith("P3_CONV continue route=PROACTIVE") },
                "the continuation round is the follow-up answer's own: $lines",
            )

            // The next sentence continues the conversation: two PROACTIVE rounds, the second one the continuation.
            injectFollowup(rig, "打开蓝牙", turnId = "asr-2", revision = 3)
            val second = rig.rig.output.hints.last()
            assertEquals("第二轮完成。", second.text)
            assertEquals("asr-2", second.sourceTurnId, "the next final continued the conversation, not a hint")
            assertEquals(
                2,
                lines.count { it.startsWith("P3_TURN start") && "route=PROACTIVE" in it },
                "exactly the follow-up and its continuation ran: $lines",
            )
            assertEquals(0, lines.count { it.startsWith("P3_PROACTIVE hint") }, lines.toString())
        }
    }

    // ---- confirmation still applies in the follow-up ------------------------------------------------------------------

    @Test
    fun `an L3 action in the follow-up asks for confirmation and a denial is reported`() {
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigWithDisplayedHint(
            descriptors = listOf(
                smartAction("device", risk = RiskLevel.L3, requiresConfirmation = true, title = "调节亮度"),
            ),
            maxLevel = "L4",
            tasks = tasks,
            channel = channel,
        )
        rig.script.next(callingTool("device"))
        rig.script.next(answering("好的，已取消。"))

        withLines { lines ->
            injectFollowup(rig, "把亮度调到最低", flush = false)
            assertTrue(
                channel.questionUp.await(5, TimeUnit.SECONDS),
                "an L3 action in the follow-up still goes through the confirmation gate",
            )
            rig.rig.plugin.handle(ConfirmationResult(rig.rig.envelope(), channel.presented.single(), false))
            tasks.joinAll()
            rig.rig.tick()

            assertTrue(
                lines.any { it.startsWith("P3_TOOL call=device") && "CONFIRMATION_DENIED" in it },
                "the denial is the terminal result of the tool step: $lines",
            )
            assertTrue(rig.rig.actions.executed.isEmpty(), "a denied action must never execute")
            val answer = rig.rig.output.hints.last()
            assertEquals(AssistantRoute.PROACTIVE, answer.route)
            assertEquals("好的，已取消。", answer.text)
        }
    }

    // ---- STOP --------------------------------------------------------------------------------------------------------

    @Test
    fun `stop during a follow-up cancels only the follow-up and leaves the wake route usable`() {
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigWithDisplayedHint(
            descriptors = listOf(
                smartAction("device", risk = RiskLevel.L3, requiresConfirmation = true, title = "调节亮度"),
            ),
            maxLevel = "L4",
            tasks = tasks,
            channel = channel,
        )
        rig.script.next(callingTool("device"))
        rig.script.next(answering("已取消。"))

        withLines { lines ->
            injectFollowup(rig, "把亮度调到最低", flush = false)
            assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS))

            rig.rig.plugin.handle(UserAction(rig.rig.envelope(), UserAction.Action.Stop))

            assertEquals(0, rig.rig.plugin.pendingConfirmationCountForTest())
            assertNull(rig.rig.plugin.followupTurnStateForTest(), "STOP cancels the follow-up turn")
            assertNull(rig.rig.plugin.followupWindowTurnIdForTest())
            assertTrue(lines.any { it == "P3_PROACTIVE followup close reason=stop" }, lines.toString())

            // The question was presented on the worker thread; its handle is withdrawn once that call returns, so the
            // count is asserted after the canceled turn has unwound (not at the instant STOP was delivered).
            tasks.joinAll()
            assertEquals(1, channel.cancelled.get(), "STOP must withdraw the follow-up's question")
            rig.rig.tick()
            assertTrue(
                rig.rig.output.hints.none { it.text == "已取消。" },
                "a stopped follow-up must not answer afterwards",
            )

            // The WAKE route was not the cancelled session: a later wake still opens and answers normally.
            rig.rig.model.onRequest = answering("唤醒回答。")
            rig.rig.plugin.handle(rig.rig.wakeEvent())
            rig.rig.plugin.handle(TranscriptEvent(rig.rig.transcript("打开设置", turnId = "asr-wake")))
            awaitRouteHint(rig.rig, AssistantRoute.WAKE)

            val wakeAnswer = rig.rig.output.hints.last { it.route == AssistantRoute.WAKE }
            assertEquals("唤醒回答。", wakeAnswer.text)
            assertTrue(rig.rig.output.hints.none { it.text == "已取消。" })
        }
    }

    // ---- P3-13 route isolation ---------------------------------------------------------------------------------------

    @Test
    fun `the wake route neither reads nor writes the proactive store`() {
        val stores = smartStores()
        val rig = rigWithDisplayedHint(stores = stores)
        // Seeded after activation (activation clears the injected stores): a marker only the PROACTIVE conversation has.
        val proactive = stores.getValue(ContextRoute.PROACTIVE)
        proactive.appendUser("PROACTIVE-ONLY-MARKER")
        proactive.appendAssistant("PROACTIVE-ONLY-REPLY")
        val proactiveSize = proactive.history().size
        val proactiveHints = rig.rig.output.hints.count { it.route == AssistantRoute.PROACTIVE }

        rig.script.next(answering("唤醒回答。"))
        rig.rig.plugin.handle(rig.rig.wakeEvent())
        rig.rig.plugin.handle(TranscriptEvent(rig.rig.transcript("打开设置", turnId = "asr-wake")))
        rig.rig.tick()

        val wakeRequest = rig.rig.model.requests.last()
        assertFalse(
            wakeRequest.history.any { it is ModelHistoryItem.Text && it.text.startsWith("PROACTIVE-ONLY") },
            "a WAKE request must never carry the PROACTIVE store: ${wakeRequest.history}",
        )
        val wakeHistory = stores.getValue(ContextRoute.WAKE).history()
        assertTrue(wakeHistory.isNotEmpty(), "the WAKE turn is recorded where it belongs")
        assertTrue(wakeHistory.none { it is ModelHistoryItem.Text && it.text.startsWith("PROACTIVE-ONLY") })
        assertEquals(proactiveSize, proactive.history().size, "the WAKE turn must never write the PROACTIVE store")
        assertNull(rig.rig.plugin.followupTurnStateForTest(), "a WAKE answer never enters the PROACTIVE turn slot")
        assertNull(rig.rig.plugin.followupWindowTurnIdForTest())
        assertEquals(
            proactiveHints,
            rig.rig.output.hints.count { it.route == AssistantRoute.PROACTIVE },
            "the WAKE answer is not filed under PROACTIVE",
        )
    }

    // ---- display / speech identity ------------------------------------------------------------------------------------

    @Test
    fun `a proactive follow-up final is never replayed by help as a wake utterance`() {
        // P3-13: the manual `Help` entry point replays the last **WAKE** utterance. A follow-up final belongs to the
        // PROACTIVE conversation and must not overwrite that remembered candidate.
        val rig = rigWithDisplayedHint(continueDialogue = true)
        rig.script.next(answering("第一轮完成。"))
        rig.script.next(answering("第二轮完成。"))
        rig.script.next(answering("帮助回答。"))

        injectFollowup(rig, "打开设置", turnId = "asr-follow-1")
        val first = rig.rig.output.hints.last()
        rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), first.hintId, OutputStatus.Displayed))
        injectFollowup(rig, "打开蓝牙", turnId = "asr-follow-2", revision = 3)

        rig.rig.plugin.handle(UserAction(rig.rig.envelope(), UserAction.Action.Help))
        rig.rig.tick()

        val helpRequest = rig.rig.model.requests.last()
        val helpInput = helpRequest.input.filterIsInstance<com.sheen.rokid.plugin.InputPart.Text>()
            .joinToString("") { it.text }
        assertTrue(
            helpInput.contains("现在几点了"),
            "Help must replay the last WAKE utterance, not a follow-up: $helpInput",
        )
        assertFalse(helpInput.contains("打开蓝牙"), "a PROACTIVE final must never cross into WAKE: $helpInput")
        assertEquals(AssistantRoute.WAKE, rig.rig.output.hints.last().route)
    }

    @Test
    fun `display and speech events for the follow-up are matched to the follow-up turn`() {
        val rig = rigWithDisplayedHint(continueDialogue = true)
        rig.script.next { request, listener ->
            listener(
                smartToolCall(
                    "call-1",
                    "notify_user",
                    """{"text":"亮度已调低。","channel":"both"}""",
                    requestId = request.requestId,
                ),
            )
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        withLines { lines ->
            injectFollowup(rig, "把亮度调到最低")
            val answer = rig.rig.output.hints.last()
            assertEquals(AssistantRoute.PROACTIVE, answer.route)
            assertNotNull(answer.emitId, "the answer emits under its own identity")

            // Events naming the PROACTIVE hint (or any other emit) must not settle the follow-up's answer.
            rig.rig.plugin.handle(SpeechFinished(rig.rig.envelope(), rig.hint.hintId, SpeechResult.FINISHED))
            rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), rig.hint.hintId, OutputStatus.Displayed))
            assertTrue(lines.none { it.startsWith("P3_CONV continue") }, "a stranger's events continued the follow-up")

            rig.rig.plugin.handle(
                OutputStateEvent(rig.rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId),
            )
            assertTrue(
                lines.none { it.startsWith("P3_CONV continue") },
                "the follow-up answer is still speaking; its continuation waits for SpeechFinished",
            )

            rig.rig.plugin.handle(
                SpeechFinished(rig.rig.envelope(), answer.hintId, SpeechResult.FINISHED, answer.emitId),
            )
            assertTrue(
                lines.any { it.startsWith("P3_CONV continue route=PROACTIVE") },
                "the follow-up's own speech end settles its answer: $lines",
            )
        }
    }

    /**
     * 0.3 ASR/continuous-dialogue repair: the same loss the WAKE route had exists on the PROACTIVE route. A final the
     * wearer speaks while the follow-up answer is being produced used to reach only the proactive trigger (or nothing),
     * never the next round. It must be queued and consumed by the PROACTIVE continuation the displayed answer opens.
     */
    @Test
    fun `a final during the follow-up answer becomes the proactive continuation utterance`() {
        val tasks = OffThreadTasks()
        val rig = rigWithDisplayedHint(continueDialogue = true, tasks = tasks)
        val firstRequest = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var calls = 0
        rig.rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                firstRequest.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                streamText(request, listener, "第一轮回答。")
            } else {
                streamText(request, listener, "第二轮回答。")
            }
        }

        injectFollowup(rig, "第一句", turnId = "asr-f1", revision = 2, flush = false)
        assertTrue(firstRequest.await(5, TimeUnit.SECONDS), "the follow-up turn must be Running")

        rig.rig.plugin.handle(TranscriptEvent(rig.rig.transcript("第二句", turnId = "asr-f2", revision = 3)))
        releaseFirst.countDown()
        tasks.joinAll()
        rig.rig.tick(200)

        val answer = rig.rig.output.hints.last { it.route == AssistantRoute.PROACTIVE }
        rig.rig.plugin.handle(OutputStateEvent(rig.rig.envelope(), answer.hintId, OutputStatus.Displayed))
        tasks.joinAll()

        assertEquals(2, calls, "the queued sentence opens the PROACTIVE continuation")
        // [0] is the hint turn, [1] the follow-up answer, [2] the continuation opened by the queued sentence.
        assertEquals("asr-f2", rig.rig.model.requests[2].turnId, "the continuation carries the queued ASR turn id")
        assertTrue(
            rig.rig.model.requests[2].input.filterIsInstance<com.sheen.rokid.plugin.InputPart.Text>()
                .any { it.text == "第二句" },
            "the PROACTIVE continuation asks the sentence spoken during the follow-up answer",
        )
    }
}
