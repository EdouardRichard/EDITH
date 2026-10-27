package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.4 Task 10 (`D203`, spec §5.2): a typed message enters the **WAKE** route as if the wearer had spoken it.
 *
 * The contract under test is deliberately narrow:
 * - one WAKE turn per typed message, with the typed text as the request's own input, and no PROACTIVE turn/gate;
 * - the row keeps the `asr-<turnId>` identity with a `typed-<uuid>` turn id;
 * - blank, foreign and busy messages are refused without cancelling what is already running, and a wake-word-only
 *   message is accepted (the text did not come through the speech recogniser, so `isUsableUtterance` must not apply);
 * - the manual entry does not touch the wake cooldown and does not write `lastFinalTranscript`, so `Help` still
 *   replays the last thing the wearer actually said;
 * - the text never reaches the diagnostic log.
 */
class TypedMessageTurnTest {

    private val typedText = "把客厅的灯调暗一点"

    /** The production display double (it records the 0.24 `userFinal`/`assistantFinal` identities). */
    private data class Rig(val rig: SmartTestRig, val display: SmartRecordingDisplay)

    private fun newRig(panelVisible: Boolean = false, tasks: TaskScope = InlineTasks): Rig {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = panelVisible)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display), tasks = tasks)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "已经调暗了。") }
        return Rig(rig, display)
    }

    /** The one typed event a composition would raise: its envelope id is the turn id the plugin must reuse. */
    private fun typed(rig: SmartTestRig, text: String?, eventId: String = "typed-turn-1"): UserAction =
        UserAction(rig.envelope().copy(eventId = eventId), UserAction.Action.Typed, text)

    /** Releases the closing fallback emit and acknowledges it, exactly as the host does after the run ends. */
    private fun ackAnswer(rig: SmartTestRig): Assistance {
        rig.tick(millis = 200)
        val answer = rig.output.hints.last { it.route == AssistantRoute.WAKE }
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId))
        return answer
    }

    @Test
    fun `a typed message starts exactly one WAKE turn with the typed text`() {
        val (rig, display) = newRig()

        rig.plugin.handle(typed(rig, "  $typedText  "))
        ackAnswer(rig)

        assertEquals(1, rig.model.requests.size, "one typed message must start exactly one model request")
        val request = rig.model.requests.single()
        assertEquals(
            listOf(typedText),
            request.input.filterIsInstance<InputPart.Text>().map { it.text },
            "the request's own input is the typed text, trimmed",
        )
        assertEquals("typed-turn-1", request.turnId, "the typed event's id is the turn id")

        val user = display.userFinals.single()
        assertEquals(AssistantRoute.WAKE, user.route)
        assertEquals("typed-turn-1", user.turnId)
        assertEquals("asr-typed-turn-1", user.entryId, "the row keeps the documented asr-<turnId> identity")
        assertEquals(typedText, user.text)
        assertEquals(1, display.assistantFinals.count { it.route == AssistantRoute.WAKE },
            "the answer is recorded once on the WAKE route: ${display.assistantFinals}")
        assertEquals(1, display.userFinals.size, "exactly one typed row: ${display.userFinals}")
        assertEquals(1, rig.hintTexts.size, "the answer reaches the output exactly once: ${rig.hintTexts}")
    }

    @Test
    fun `a typed message never starts a PROACTIVE turn or gate`() {
        val (rig, display) = newRig()
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(typed(rig, typedText))
            ackAnswer(rig)
        } finally {
            P3Log.sink = previous
        }

        assertEquals(1, rig.model.requests.size, "the only model request is the WAKE turn")
        assertEquals("typed-turn-1", rig.model.requests.single().turnId)
        assertTrue(
            display.calls.none { it.contains("PROACTIVE") },
            "no PROACTIVE row, level or state may be produced: ${display.calls}",
        )
        assertTrue(
            lines.none { it.startsWith("P3_PROACTIVE") },
            "no PROACTIVE path may run: $lines",
        )
    }

    @Test
    fun `a typed message asks the model with the same shape as an accepted wake final`() {
        val (wakeRig, _) = newRig()
        wakeRig.plugin.handle(wakeRig.wakeEvent())
        wakeRig.plugin.handle(TranscriptEvent(wakeRig.transcript("同一个问题", turnId = "asr-1")))
        val wake = wakeRig.model.requests.single()

        val (typedRig, _) = newRig()
        typedRig.plugin.handle(typed(typedRig, "同一个问题", eventId = "typed-9"))
        val typed = typedRig.model.requests.single()

        // Same downstream processing: only the identity fields are the caller's; everything the model sees is equal.
        assertEquals(wake.instructions, typed.instructions)
        assertEquals(wake.input, typed.input)
        assertEquals(wake.history, typed.history)
        assertEquals(wake.output, typed.output)
        assertEquals(wake.tools, typed.tools)
        assertEquals(wake.profileId, typed.profileId)
        assertEquals(wake.profileRevision, typed.profileRevision)
        assertEquals(wake.maxOutputTokens, typed.maxOutputTokens)
        assertEquals("typed-9", typed.turnId)
    }

    @Test
    fun `blank typed text is refused without a turn`() {
        val (rig, display) = newRig()

        listOf("", "   ", "\n\t ").forEach { rig.plugin.handle(typed(rig, it, eventId = "typed-$it")) }
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Typed, null))
        rig.tick(millis = 200)

        assertTrue(rig.model.requests.isEmpty(), "a blank typed message must not reach the model")
        assertTrue(display.calls.isEmpty(), "a blank typed message must paint nothing: ${display.calls}")
        assertTrue(rig.plugin.canAcceptTypedMessage(), "a refused blank message must not consume the acceptance slot")
    }

    @Test
    fun `a typed message from another session or generation is ignored`() {
        val (rig, display) = newRig()

        rig.plugin.handle(UserAction(
            rig.envelope(sessionId = SessionId("other-session")), UserAction.Action.Typed, "秘密"))
        rig.plugin.handle(UserAction(
            rig.envelope(generation = Generation(9)), UserAction.Action.Typed, "秘密"))
        rig.tick(millis = 200)

        assertTrue(rig.model.requests.isEmpty(), "a foreign typed message must not run")
        assertTrue(display.calls.isEmpty(), "a foreign typed message must paint nothing: ${display.calls}")
    }

    @Test
    fun `a typed message while a wake turn awaits its transcript is refused and keeps the turn`() {
        val (rig, display) = newRig()
        rig.plugin.handle(rig.wakeEvent())
        assertFalse(rig.plugin.canAcceptTypedMessage(), "an AwaitingTranscript turn is busy")

        rig.plugin.handle(typed(rig, "打字内容"))
        rig.tick(millis = 200)

        assertTrue(rig.model.requests.isEmpty(), "the refused typed message must not run")
        assertTrue(display.userFinals.isEmpty(), "a refused typed message must not paint a user row")
        // The wake turn is untouched: its own final is still what runs, under its own id.
        rig.plugin.handle(TranscriptEvent(rig.transcript("语音问题", turnId = "asr-1")))
        assertEquals(1, rig.model.requests.size)
        assertEquals(listOf(InputPart.Text("语音问题")), rig.model.requests.single().input)
        assertEquals("asr-1", rig.model.requests.single().turnId)
    }

    @Test
    fun `a typed message while the wake turn runs is refused and does not cancel it`() {
        val tasks = OffThreadTasks()
        val (rig, _) = newRig(tasks = tasks)
        val firstRequest = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests += 1
            if (requests == 1) {
                firstRequest.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
            streamText(request, listener, "答案。")
        }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("语音问题", turnId = "asr-1")))
        assertTrue(firstRequest.await(5, TimeUnit.SECONDS), "the wake turn must be Running")
        assertFalse(rig.plugin.canAcceptTypedMessage(), "a Running turn is busy")

        rig.plugin.handle(typed(rig, "打字内容"))
        assertTrue(rig.cancels.isEmpty(), "a refused typed message must not cancel the running turn: ${rig.cancels}")

        releaseFirst.countDown()
        tasks.joinAll()
        assertEquals(1, rig.model.requests.size, "the refused typed message must not open a second request")
        assertEquals("asr-1", rig.model.requests.single().turnId)
    }

    @Test
    fun `a typed message does not start the wake cooldown`() {
        val (rig, _) = newRig()
        rig.plugin.handle(typed(rig, "第一句"))
        ackAnswer(rig)

        // A fresh rig has no wake history, so this wake is admitted only if the typed turn did not record a turn.
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 1)))
        assertEquals(2, rig.model.requests.size, "the typed turn must not start the 3 s wake cooldown")
        assertEquals(listOf("typed-turn-1", "asr-2"), rig.model.requests.map { it.turnId })
    }

    @Test
    fun `help still replays the last ASR final after a typed message`() {
        val (rig, _) = newRig()
        // Remember a real final first: this is what the manual entry point replays.
        rig.plugin.handle(TranscriptEvent(rig.transcript("语音问题", turnId = "asr-1")))
        rig.plugin.handle(typed(rig, "打字内容", eventId = "typed-1"))
        ackAnswer(rig)

        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))

        assertEquals(2, rig.model.requests.size, "Help must open its own turn after the typed one")
        val help = rig.model.requests.last()
        assertEquals(listOf(InputPart.Text("语音问题")), help.input,
            "Help replays the last ASR final, never the typed text")
        assertEquals("asr-1", help.turnId, "Help keeps the transcript's own turn id")
    }

    @Test
    fun `a typed message accepts wake-word-only text`() {
        val (rig, _) = newRig()

        rig.plugin.handle(typed(rig, "伊迪丝"))

        assertEquals(1, rig.model.requests.size, "typed text is never filtered by isUsableUtterance")
        assertEquals(listOf(InputPart.Text("伊迪丝")), rig.model.requests.single().input)
    }

    @Test
    fun `a typed message leaves a pending PROACTIVE hint panel alone`() {
        val tasks = OffThreadTasks()
        val panelRequested = CountDownLatch(1)
        val typedRequested = CountDownLatch(1)
        val display = SmartRecordingDisplay()
        // Hidden panel, and a `PANEL_OPEN` the host accepts but never confirms: the hint is left waiting for its
        // layout - exactly the pending PROACTIVE panel a session-wide cycle reset would cancel.
        val rig = SmartTestRig(panelVisible = false, requestPanelOpen = { panelRequested.countDown(); true })
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display), tasks = tasks)
        rig.model.onRequest = { request, listener ->
            if (request.turnId.startsWith("typed-")) {
                typedRequested.countDown()
                streamText(request, listener, "打字回答。")
            } else {
                listener(smartToolCall("hint-1", "propose_hint", """{"text":"提醒一句"}""",
                    requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            }
        }
        // Arm perception, then a final transcript triggers the proactive hint.
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-1")))
        assertTrue(panelRequested.await(5, TimeUnit.SECONDS), "the proactive hint must ask for the hidden panel")
        assertTrue(rig.output.hints.isEmpty(), "the hint waits for PANEL_SHOWN")

        rig.plugin.handle(typed(rig, "帮我记一条笔记", eventId = "typed-during-hint"))
        assertTrue(typedRequested.await(5, TimeUnit.SECONDS), "the typed turn runs while the hint is pending")

        // The panel arrives late: the hint must still be released. A typed message may not cancel it.
        rig.plugin.onPanelShown(AssistantRoute.PROACTIVE)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (rig.output.hints.none { it.route == AssistantRoute.PROACTIVE } && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        tasks.joinAll()

        assertTrue(
            rig.output.hints.any { it.route == AssistantRoute.PROACTIVE },
            "the pending PROACTIVE hint must survive the typed message: ${rig.output.hints}",
        )
        assertTrue(
            display.states.any {
                it.route == AssistantRoute.PROACTIVE && it.status == OfficialStatus.LISTENING.wire
            },
            "the PROACTIVE listening round must survive the typed message: ${display.states}",
        )
        assertEquals(
            listOf("typed-during-hint"),
            display.userFinals.filter { it.route == AssistantRoute.WAKE }.map { it.turnId },
            "the typed row is the only WAKE row; the pending hint belongs to the other conversation",
        )
    }

    @Test
    fun `a typed message is accepted in both glasses and detached panel modes`() {
        // Detached mode's only plugin-visible fact is the conversation cycle's visibility (SmartWiring passes
        // `detached || glassesVisible`), so both values of that fact are driven here.
        listOf(false, true).forEach { visible ->
            val (rig, _) = newRig(panelVisible = visible)
            val eventId = if (visible) "typed-detached" else "typed-glasses"
            assertTrue(rig.plugin.canAcceptTypedMessage(), "panelVisible=$visible")

            rig.plugin.handle(typed(rig, "两种模式都能打字", eventId = eventId))

            assertEquals(1, rig.model.requests.size, "panelVisible=$visible")
            assertEquals(eventId, rig.model.requests.single().turnId, "panelVisible=$visible")
        }
    }

    @Test
    fun `the typed text never reaches the diagnostic log`() {
        val (rig, _) = newRig()
        val secret = "这条打字内容绝不能进日志"
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(typed(rig, secret))
            ackAnswer(rig)
        } finally {
            P3Log.sink = previous
        }

        assertTrue(lines.isNotEmpty(), "the turn must produce its usual count-only lines")
        assertTrue(
            lines.none { it.contains(secret) || it.contains("textSha256") },
            "the typed text must never be logged: $lines",
        )
        assertTrue(
            lines.any { it.startsWith("P3_TURN start turn=typed-turn-1") },
            "the turn's own count-only line names the typed turn: $lines",
        )
    }

    @Test
    fun `canAcceptTypedMessage is true again once the answer has settled`() {
        val (rig, _) = newRig()
        assertTrue(rig.plugin.canAcceptTypedMessage())

        rig.plugin.handle(typed(rig, "第一句"))
        assertFalse(
            rig.plugin.canAcceptTypedMessage(),
            "an Idle turn whose answer was not acknowledged yet is still busy",
        )

        ackAnswer(rig)
        assertTrue(rig.plugin.canAcceptTypedMessage(), "a settled turn leaves the slot open")

        rig.plugin.handle(typed(rig, "第二句", eventId = "typed-turn-2"))
        assertEquals(2, rig.model.requests.size, "the next typed message opens the next turn")
        assertEquals(listOf("typed-turn-1", "typed-turn-2"), rig.model.requests.map { it.turnId })
    }
}
