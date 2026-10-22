package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.agent.SummaryBoundary
import com.sheen.rokid.agent.TokenMeter
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.plugin.smarthelper.context.CompressionReport
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextCompressor
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 5's production wiring: with a `ConversationStore` per route injected, the session records into the store and
 * each model turn is assembled as a `RequestFrame`; with none injected, both legacy paths stay byte for byte.
 *
 * Every ordering or independence guarantee here is written so that **removing the code it names turns it red**: the
 * routes test drives *both* routes and asserts each store holds its own half (a plugin that records nothing fails on
 * the "used route is non-empty" half), the compressor test asserts the **call count** and what the store held **on
 * entry** (not merely that a summary appeared), and the gate tests assert the boolean the plugin actually passed in
 * both directions. The mutation outputs are in the Task 6 report.
 */
class StoreSessionWiringTest {

    /** One fresh store per route, exactly as `SmartWiring` builds them. */
    private fun stores(): Map<ContextRoute, ConversationStore> =
        ContextRoute.entries.associateWith { ConversationStore() }

    /**
     * An activated production-shaped rig whose two route stores are `stores`.
     *
     * The wide window keeps the 24-tool system prompt (0.3 Phase 4A Task 6) out of these content assertions: a
     * 4k-token fixture that just fitted the old six-tool prompt would otherwise let the trimmer drop the memory or a
     * tool round the test is about. Tests that need a budget edge override `rig.profile` explicitly.
     */
    private fun rig(
        stores: Map<ContextRoute, ConversationStore> = stores(),
        compressor: ContextCompressor? = null,
        overrides: Map<Capability, Any> = mapOf(Capability.Storage to SmartFakeStorage()),
    ): SmartTestRig = SmartTestRig(conversationStores = stores, compressor = compressor).also {
        it.profile = smartProfile(
            usableWindowTokens = 32_000,
            inputBudgetTokens = 16_000,
            maxOutputTokens = 2_000,
            safetyTokens = 1_000,
        )
        it.activate(overrides = overrides)
        it.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
    }

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

    private fun textItems(store: ConversationStore): List<String> =
        store.history().filterIsInstance<ModelHistoryItem.Text>().map { it.text }

    // ------------------------------------------------------------ the store path

    @Test
    fun `the store route records the turn and hands the model a frame`() {
        val stores = stores()
        val rig = rig(stores)

        rig.wakeAndFinal("现在几点了")

        // ① the store recorded both halves of this turn
        assertEquals(listOf("现在几点了", "好。"), textItems(stores.getValue(ContextRoute.WAKE)))
        // ② on the wire, this turn's utterance is the request input, not a history item
        val sent = rig.model.requests.last()
        assertEquals(listOf("现在几点了"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
        assertTrue(sent.history.isEmpty(), "an empty store opens the request with the input alone: ${sent.history}")
    }

    @Test
    fun `the two routes keep independent stores`() {
        // Both routes are driven, and each store must hold **its own** half only. Asserting "the other route is 0"
        // alone would also pass for a plugin that records nothing at all, so the used route is asserted non-empty too.
        val stores = stores()
        val rig = rig(stores)
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }) {
                listener(smartToolCall("c1", "propose_hint", """{"text":"主动提示"}""", requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        // The wake route.
        rig.wakeAndFinal("开灯")
        rig.tick(4_000)
        // The proactive route: armed by a perception signal, admitted by a question-shaped trigger.
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我记一下这件事", turnId = "asr-p1")))

        val wake = stores.getValue(ContextRoute.WAKE)
        val proactive = stores.getValue(ContextRoute.PROACTIVE)
        assertTrue(wake.size() > 0, "the wake route recorded nothing at all")
        assertTrue(proactive.size() > 0, "the proactive route recorded nothing at all")
        assertEquals(listOf("开灯", "好。"), textItems(wake), "the proactive turn leaked into the wake store")
        assertEquals(
            listOf("帮我记一下这件事", "主动提示"),
            textItems(proactive),
            "a wake turn leaked into the proactive store",
        )
    }

    /** A manager with a plan and the fixture profile's policy; the store path never writes its transcript. */
    private fun manager(): ContextManager =
        ContextManager(policy = smartContextPolicy(), promptPlan = smartPromptPlan("SYSTEM"))

    @Test
    fun `with no injected stores the plugin keeps the legacy transcript`() {
        // Regression: with no store the Phase B behaviour is unchanged — the `ContextManager` transcript is what the
        // session records into, and no frame is assembled.
        val contexts = manager()
        val rig = SmartTestRig(contextForTurn = contexts)
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("你好")

        assertEquals(2, contexts.context(ContextRoute.WAKE).size())
    }

    // ------------------------------------------------------------ a tool round: every model request through one renderer

    /**
     * Whether the wire history is a shape `ContextBudgeter` accepts, re-implemented locally because `plugins-smart`
     * does not depend on `model-runtime` (where the real check lives). It is the same rule: text alternates from a
     * leading `USER`, a tool run is preceded by an assistant text, and the last group is not a lone user message.
     */
    private fun acceptableWireHistory(history: List<ModelHistoryItem>): Boolean {
        var lastTextRole: MessageRole? = null
        var inToolRun = false
        var lastGroupSize = 0
        for (item in history) {
            when (item) {
                is ModelHistoryItem.Text -> {
                    when {
                        lastTextRole == null -> if (item.role != MessageRole.USER) return false
                        inToolRun -> Unit
                        lastTextRole == MessageRole.USER -> if (item.role != MessageRole.ASSISTANT) return false
                        else -> if (item.role != MessageRole.USER) return false
                    }
                    if (item.role == MessageRole.USER) lastGroupSize = 1 else lastGroupSize++
                    lastTextRole = item.role
                    inToolRun = false
                }
                is ModelHistoryItem.ToolCall, is ModelHistoryItem.ToolOutput -> {
                    if (!inToolRun && lastTextRole != MessageRole.ASSISTANT) return false
                    inToolRun = true
                    lastGroupSize++
                }
            }
        }
        return !(history.isNotEmpty() && lastGroupSize == 1 && lastTextRole == MessageRole.USER)
    }

    /**
     * The defect this test exists for: within **one** turn a tool round makes `AgentLoop` issue a second model
     * request, and on the store path that request used to lose the whole session — the store owned the transcript, the
     * frame was supplied for the opening turn only, and the legacy history base was empty. `notify_user` is L0 and
     * non-terminal, so this is reachable on any real tool call.
     *
     * The assertions are on **content**, not on "a second request happened": request 2 must carry the earlier session
     * turns, the memory block, this turn's utterance and the tool round — and still be a shape the send-time budgeter
     * accepts. A builder ignored for the continuation fails on every one of the first three.
     */
    @Test
    fun `a tool round keeps the session and the memory in the second request`() {
        val storage = SmartFakeStorage()
        // The memory the turn retrieves, written through the production store. The query below is a substring of the
        // remembered sentence, so the lexical ranking finds it even though the vector index starts cold.
        MemoryLog(storage, log = {}).record(
            com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind.PREFERENCE,
            "我不吃香菜",
            com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource.EXPLICIT,
        )
        val stores = stores()
        val rig = rig(stores, overrides = mapOf(Capability.Storage to storage))
        // The model notifies once, then answers: two requests in one turn.
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn += 1
            if (turn == 1) {
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "已经看过了。")
            }
        }
        // Seeded after activation: activation is the session boundary and empties the route's store.
        stores.getValue(ContextRoute.WAKE).apply {
            appendUser("旧问题")
            appendAssistant("旧回答")
        }

        val lines = withLines { captured -> rig.wakeAndFinal("香菜"); captured.toList() }

        assertEquals(2, rig.model.requests.size, "the tool round must make a second model request")
        val first = rig.model.requests[0]
        // 0.3 Task 10 (spec §5.1): memory rides the request **input** ahead of the wearer's turn; it is no longer
        // folded into the first history message. The input is one joined text — the dynamic tail, then the utterance.
        val firstInput = first.input.filterIsInstance<InputPart.Text>().map { it.text }
        assertEquals(1, firstInput.size, "the request carries one input message: ${first.input}")
        assertTrue(
            firstInput.single().contains("reference_context") && firstInput.single().contains("我不吃香菜"),
            "the retrieved memory must ride the input tail: ${firstInput.single()}",
        )
        assertTrue(
            firstInput.single().endsWith("香菜"),
            "the wearer's utterance must stay last in the input: ${firstInput.single()}",
        )
        assertTrue(
            first.history.none { it is ModelHistoryItem.ToolOutput },
            "the opening request is issued before any tool has run: ${first.history}",
        )
        // The cacheable history is the transcript verbatim: it opens on the oldest surviving user turn, and the
        // request-scoped memory must never be folded into it (that is what would rewrite the prefix every turn).
        val firstHead = first.history.first() as ModelHistoryItem.Text
        assertEquals("旧问题", firstHead.text, "history[0] must be the oldest transcript user turn: ${first.history}")
        assertFalse(
            firstHead.text.contains("reference_context"),
            "memory must not be folded into history[0] any more: ${first.history}",
        )
        assertEquals(
            listOf("旧问题", "旧回答"),
            first.history.filterIsInstance<ModelHistoryItem.Text>().map { it.text },
            "the opening request must carry the session transcript",
        )

        val second = rig.model.requests[1]
        // 0.25 Task 26 (R140): the transcript already carries this turn's utterance, so the frame's one `USER_INPUT`
        // is the fixed loop-internal cue — not the question again. The memory tail rides ahead of that cue.
        val secondInput = second.input.filterIsInstance<InputPart.Text>().map { it.text }
        assertEquals(1, secondInput.size, "the continuation carries one input message: ${second.input}")
        assertTrue(
            secondInput.single().contains("reference_context") && secondInput.single().contains("我不吃香菜"),
            "request 2 must still carry the retrieved memory on the input tail: ${secondInput.single()}",
        )
        assertTrue(
            secondInput.single().endsWith(CONTINUE_AFTER_TOOL),
            "the continuation's input must be the fixed cue after the dynamic tail: ${secondInput.single()}",
        )
        assertEquals(
            1,
            second.history.count {
                it is ModelHistoryItem.Text && it.role == MessageRole.USER && it.text == "香菜"
            },
            "the conversation history must carry this turn's utterance exactly once: ${second.history}",
        )
        assertTrue(
            second.history.lastOrNull() is ModelHistoryItem.ToolOutput,
            "the tool output must stay last in the transcript: ${second.history}",
        )
        val head = second.history.first() as ModelHistoryItem.Text
        assertEquals("旧问题", head.text, "request 2's history must open on the transcript, not on memory: ${second.history}")
        assertFalse(
            head.text.contains("reference_context"),
            "request 2 must not fold the memory into history[0] either: ${second.history}",
        )
        assertTrue(
            second.history.filterIsInstance<ModelHistoryItem.Text>().map { it.text }.any { it == "旧回答" },
            "request 2 must still carry the earlier assistant turn: ${second.history}",
        )
        assertTrue(
            second.history.any {
                it is ModelHistoryItem.ToolOutput && it.output == pureToolOutput("notified:1")
            },
            "request 2 must carry this turn's tool round: ${second.history}",
        )
        assertTrue(
            second.history.any { it is ModelHistoryItem.ToolCall && it.name == "notify_user" },
            "the call must come back structured, not as text: ${second.history}",
        )
        assertTrue(
            acceptableWireHistory(second.history),
            "the continuation must stay a shape the send-time budgeter accepts: ${second.history}",
        )
        assertTrue(
            second.instructions.contains("记忆策略"),
            "the catalog plan's memory-policy section rides along: ${second.instructions}",
        )
        // Important 2: the count line must report the store's own session, not the manager the store path never writes
        // (which holds only this utterance, so the line used to read `history=1` however many turns the store held).
        assertTrue(
            lines.any { it.startsWith("P3_CONTEXT route=WAKE store=2 historyChars=6") },
            "P3_CONTEXT must report the store's own counts: $lines",
        )
    }

    // ------------------------------------------------------------ a compressed head, and a retry

    /**
     * The defect: the flat transcript a frame builder receives from `AgentLoop` carries no attribution, so a store
     * `SUMMARY` — the store's head after any compression — used to be relabelled a plain assistant `TRANSCRIPT`. The
     * gateway then could not lift it into the leading context, the history opened on an assistant item, and **every**
     * turn after the first compression ended `FAILED`/`NO_USER_TURN` without reaching the model.
     *
     * The assertion is on the assembled request, not on "no exception": the summary body must be in the history, the
     * history must open on the user turn (the shape `ContextBudgeter` requires), and the turn must complete.
     */
    @Test
    fun `a turn after a compression reaches the model with the head summary`() {
        val stores = stores()
        val rig = rig(stores)
        // Seeded after activation: activation is the session boundary and empties the route's store. `appendSummary`
        // lands at the head, which is the only position the store's invariant allows a summary to occupy.
        stores.getValue(ContextRoute.WAKE).apply {
            appendSummary("用户之前问过天气")
            appendUser("旧问题")
            appendAssistant("旧回答")
        }

        val lines = withLines { captured -> rig.wakeAndFinal("现在几点了"); captured.toList() }

        assertTrue(
            lines.contains("P3_TURN end turn=asr-1 outcome=COMPLETED code=- steps=0"),
            "a post-compression turn must complete: $lines",
        )
        assertTrue(lines.none { it.contains("NO_USER_TURN") }, "the head summary must not defeat the turn: $lines")
        val sent = rig.model.requests.single()
        val head = assertIs<ModelHistoryItem.Text>(
            sent.history.firstOrNull(),
            "the history must open on a user turn: ${sent.history}",
        )
        assertEquals(MessageRole.USER, head.role, "the history must open on the user turn: ${sent.history}")
        assertTrue(head.text.contains("用户之前问过天气"), "the head summary must reach the request: ${head.text}")
        assertTrue(
            head.text.contains(SummaryBoundary.OPEN),
            "the store's bounded summary carries its boundary into the request: ${head.text}",
        )
        assertTrue(head.text.contains("旧问题"), "the summary must fold into the surviving user turn: ${head.text}")
    }

    /**
     * The defect this test exists for: `appendSummary` used to merely insert at the head, so a **second** compression
     * left the superseded summary stranded at index 1 (`[S_new, S_old, turns…]`) — a shape `ConversationStoreTest`
     * pinned as legal and that is not swept until the next append, i.e. until the **end** of the next turn. The frame
     * builder can attribute only index 0, so `S_old` came back as an assistant `TRANSCRIPT`; the leading fold then had
     * no `USER` to ride and returned `null` ⇒ the turn after a second compression ended `FAILED`/`NO_USER_TURN`.
     *
     * Supersession is atomic now, so that shape cannot be observed at all — but only a turn driven from a
     * twice-compressed store proves the turn is accepted, so the store is compressed twice here (through
     * `appendSummary`, the operation a compression lands) with a turn in between.
     *
     * The assertion is on the request content and the completion, not on "no exception".
     */
    @Test
    fun `a turn after a second compression reaches the model`() {
        val stores = stores()
        val rig = rig(stores)
        // Seeded after activation: activation is the session boundary and empties the route's store.
        stores.getValue(ContextRoute.WAKE).apply {
            appendSummary("第一次压缩的摘要")
            appendUser("第一次压缩之后的问题")
            appendAssistant("第一次压缩之后的回答")
            // A second compression lands: it supersedes the first summary in the same call.
            appendSummary("第二次压缩的摘要")
        }

        val lines = withLines { captured -> rig.wakeAndFinal("现在几点了"); captured.toList() }

        assertTrue(
            lines.contains("P3_TURN end turn=asr-1 outcome=COMPLETED code=- steps=0"),
            "a turn after a second compression must complete: $lines",
        )
        assertTrue(lines.none { it.contains("NO_USER_TURN") }, "a stranded summary must not defeat the turn: $lines")
        // The transient two-summary state is unobservable: exactly one summary, at the head, before the request was built.
        assertEquals(
            1,
            stores.getValue(ContextRoute.WAKE).snapshot().count { it.origin == SegmentOrigin.SUMMARY },
            "the second compression must leave exactly one summary: ${stores.getValue(ContextRoute.WAKE).snapshot()}",
        )
        val sent = rig.model.requests.single()
        val head = assertIs<ModelHistoryItem.Text>(
            sent.history.firstOrNull(),
            "the history must open on a user turn: ${sent.history}",
        )
        assertEquals(MessageRole.USER, head.role, "the history must open on the user turn: ${sent.history}")
        assertTrue(head.text.contains("第二次压缩的摘要"), "the newer summary must reach the request: ${head.text}")
        assertTrue(head.text.contains("第一次压缩之后的问题"), "the surviving older turn must fold in: ${head.text}")
        assertTrue(
            sent.history.none { it is ModelHistoryItem.Text && it.text.contains("第一次压缩的摘要") },
            "the superseded summary must not be sent as an assistant turn: ${sent.history}",
        )
    }

    /**
     * The defect: the loop's retry continuation is a bare `Text(USER)` — a loop-internal instruction, not the wearer
     * speaking. Rendered as a trailing `TRANSCRIPT` `USER` it opened an unclosed one-item user group, which the
     * gateway refused: the designed retry was never sent, so an empty turn stayed empty on the production path.
     *
     * Two retries are driven (still inside the loop's `maxEmptyTurns`), because that is also where the recovery is
     * load-bearing: only the **pending** retry is a `RETRY`; the earlier one must stay an ordinary user turn, or the
     * assistant placeholders collapse into two consecutive assistant texts — a shape `ContextBudgeter` also rejects.
     *
     * The assertion is on each retry request's content, not on "more requests happened": it must carry the retry
     * instruction, still carry this turn's utterance, and be a shape the send-time budgeter accepts.
     */
    @Test
    fun `an empty turn's retry continuation is sent and accepted`() {
        val stores = stores()
        val rig = rig(stores)
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn += 1
            // An INCOMPLETE turn with no call is the unproductive turn the loop retries; the third answers.
            if (turn <= 2) {
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.INCOMPLETE, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        val lines = withLines { captured -> rig.wakeAndFinal("现在几点了"); captured.toList() }

        assertEquals(3, rig.model.requests.size, "every empty turn must be retried: $lines")
        assertEquals(
            listOf("现在几点了"),
            rig.model.requests.first().input.filterIsInstance<InputPart.Text>().map { it.text },
            "the opening request still carries the wearer's own question as its input",
        )
        assertTrue(lines.none { it.contains("NO_USER_TURN") }, "no retry may be refused: $lines")
        assertTrue(
            lines.contains("P3_TURN end turn=asr-1 outcome=COMPLETED code=- steps=0"),
            "the retried turn must complete: $lines",
        )
        for (retry in rig.model.requests.drop(1)) {
            assertTrue(
                acceptableWireHistory(retry.history),
                "the retried request must stay a shape the send-time budgeter accepts: ${retry.history}",
            )
            val retryInput = retry.input.filterIsInstance<InputPart.Text>().single().text
            assertTrue(
                retryInput.contains("no tool call and no text"),
                "the loop's retry instruction must actually be sent: $retryInput",
            )
            assertTrue(
                retryInput.endsWith(CONTINUE_AFTER_TOOL),
                "0.25 Task 26 (R140): the retry's input is the loop-internal cue, not a second copy of the question: " +
                    retryInput,
            )
            assertFalse(
                retryInput.contains("现在几点了"),
                "the retry continuation must not repeat this turn's utterance as the input: $retryInput",
            )
            assertEquals(
                1,
                retry.history.count {
                    it is ModelHistoryItem.Text && it.role == MessageRole.USER && it.text == "现在几点了"
                },
                "the transcript must carry this turn's utterance exactly once: ${retry.history}",
            )
        }
    }

    // ------------------------------------------------------------ B6's order on the store path

    /**
     * Records what the memory store held **and** what the session store held **at the moment compression ran**.
     *
     * That is the whole assertion: if the memory store already carries this turn's message and the session store
     * already carries this turn's two halves when `compressStore` is entered, then ③ (memory write), ④ (transcript
     * record) and ⑤ (compression) ran in that order.
     */
    private class OrderWatchingStoreCompressor(private val memory: MemoryLog) :
        ContextCompressor(historyBudgetTokens = 3, summaryMaxTokens = 64) {
        val memoryAtCompression = mutableListOf<List<String>>()
        val storeAtCompression = mutableListOf<List<String>>()
        var calls = 0

        override fun compressStore(
            store: ConversationStore,
            compressionOwed: Boolean,
        ): CompressionReport {
            calls++
            memoryAtCompression += memory.entries().map { it.text }
            storeAtCompression += store.history().filterIsInstance<ModelHistoryItem.Text>().map { it.text }
            return CompressionReport(truncated = 0, summaryStarted = false, summaryReason = null)
        }
    }

    @Test
    fun `a completed turn asks the store compressor exactly once, after the memory write`() {
        val storage = SmartFakeStorage()
        val watcher = OrderWatchingStoreCompressor(MemoryLog(storage, log = {}))
        val rig = rig(compressor = watcher, overrides = mapOf(Capability.Storage to storage))

        rig.wakeAndFinal("记住我不吃香菜")

        assertEquals(1, watcher.calls, "a finished store turn consults the compressor exactly once")
        assertTrue(
            watcher.memoryAtCompression.single().any { it == "我不吃香菜" },
            "the memory write must have happened before compression, memory held: ${watcher.memoryAtCompression}",
        )
        assertEquals(
            listOf("记住我不吃香菜", "好。"),
            watcher.storeAtCompression.single(),
            "the turn's two halves must already be recorded when compression enters",
        )
    }

    // ------------------------------------------------------------ the ③ gate, both directions

    /** Records the caller's half of the ③ gate — what the plugin decided, not what compression then did. */
    private class GateWatchingCompressor : ContextCompressor(historyBudgetTokens = 3, summaryMaxTokens = 64) {
        val owedAtEntry = mutableListOf<Boolean>()

        override fun compressStore(
            store: ConversationStore,
            compressionOwed: Boolean,
        ): CompressionReport {
            owedAtEntry += compressionOwed
            return CompressionReport(truncated = 0, summaryStarted = false, summaryReason = null)
        }
    }

    /** The fixed utterance the tiny-window helpers measure against. */
    private val tinyInput = "现在几点"

    /**
     * A profile whose **send-time hard limit** (`hardInputLimit - hardMargin`) is below the fixed prompt, so the
     * trimmer reports `compressionOwed = true` on every turn, while the fixed prompt itself still fits the hard input
     * (otherwise the gateway refuses with `PROMPT_EXCEEDS_BUDGET` before any trim). Whether a **whole turn was dropped**
     * is then the only variable, which is exactly the conjunction the ③ gate is.
     */
    private fun tinyWindowProfile(): ModelProfile {
        val system = PromptCatalog.plan(PromptRoute.WAKE, null).systemText
        val fixedPrompt = 32 + measure(system) + measure(tinyInput)
        return smartProfile().copy(
            usableWindowTokens = fixedPrompt + 10 + 1_000 + 100,
            inputBudgetTokens = fixedPrompt + 10,
            maxOutputTokens = 1_000,
            safetyTokens = 100,
        )
    }

    @Test
    fun `the store gate opens only when a turn was dropped and compression is still owed`() {
        val stores = stores()
        val gate = GateWatchingCompressor()
        val rig = rig(stores, compressor = gate)
        rig.profile = tinyWindowProfile()
        // Seeded after activation: activation is the session boundary and empties the route's store.
        stores.getValue(ContextRoute.WAKE).apply {
            appendUser("旧问题")
            appendAssistant("旧回答")
        }

        val lines = withLines { captured -> rig.wakeAndFinal("现在几点"); captured.toList() }

        assertEquals(
            listOf(true),
            gate.owedAtEntry,
            "cut ① dropped a whole turn and the frame is still over budget, so ③ is owed",
        )
        assertTrue(
            lines.contains(
                "P3_CONTEXT trim route=WAKE droppedTurns=1 compressionOwed=true truncated=- dropped=-",
            ),
            "the trim counts and origin lists must be logged: $lines",
        )
    }

    @Test
    fun `the store gate stays shut when no turn was dropped even though compression is owed`() {
        // An empty store: nothing was evicted, yet the frame is over budget (the system slot + this turn's input).
        // Passing `compressionOwed` alone here would start a summary with no turn to summarise — invented content.
        val gate = GateWatchingCompressor()
        val rig = rig(compressor = gate)
        rig.profile = tinyWindowProfile()

        val lines = withLines { captured -> rig.wakeAndFinal("现在几点"); captured.toList() }

        assertEquals(
            listOf(false),
            gate.owedAtEntry,
            "no turn was dropped, so the caller's half of the ③ gate must be false",
        )
        assertTrue(
            lines.contains(
                "P3_CONTEXT trim route=WAKE droppedTurns=0 compressionOwed=true truncated=- dropped=-",
            ),
            "the counts and origin lists must be logged even when cut ① did nothing: $lines",
        )
    }

    // ------------------------------------------------------------ the lone one-item user group

    /** `TurnTrimmer.total`'s own token unit (`TokenMeter.estimate + 32`), so the window below is exact. */
    private fun measure(text: String) = TokenMeter.estimate(text) + 32

    /**
     * A profile whose window fits the system slot, the surviving pending user turn and this turn's input — with a
     * small margin — so cut ① evicts the older whole turn and stops there. The window is derived through
     * `ContextPolicy.hardLimit` (`hardInputLimit - hardMargin`), not the old raw profile remainder: the hard limit must
     * clear the survivors, because otherwise cut ① would drop the pending user turn too and the frame would become a
     * sendable empty-history request. The system slot is composed exactly as `buildFrame` composes it.
     */
    private fun windowSizedFor(vararg survivors: String): ModelProfile {
        // 0.3 Task 12: the system slot is the session's profile-less WAKE catalog plan (this rig supplies no device
        // provider and no SKILL directory).
        val system = PromptCatalog.plan(PromptRoute.WAKE, null).systemText
        val total = 32 + measure(system) + survivors.sumOf { measure(it) }
        val desiredHardLimit = total + 8
        // `hardLimit = hardInput - max(1, floor(hardInput * 0.10))`; find the first hard input that keeps the survivors.
        var hardInput = desiredHardLimit
        while (hardInput - maxOf(1, kotlin.math.floor(hardInput * 0.10).toInt()) < desiredHardLimit) hardInput++
        return smartProfile().copy(
            usableWindowTokens = hardInput + 1_000 + 100,
            inputBudgetTokens = hardInput,
            maxOutputTokens = 1_000,
            safetyTokens = 100,
        )
    }

    @Test
    fun `a lone surviving user turn ends the turn as NO_USER_TURN without throwing`() {
        // The 2026-10-14 shape: the surviving history's last (hence only) group is a single unanswered user message,
        // which `ContextBudgeter` rejects by design (`groups.last().size == 1`). The gateway must refuse it as
        // `FAILED`/`NO_USER_TURN` instead of handing an unsendable shape to the transport — one bad shape must not
        // make every later turn fail until an app restart.
        val stores = stores()
        val rig = rig(stores)
        val pending = "还没有被回答的那一问"
        val input = "现在几点了"
        rig.profile = windowSizedFor(pending, input)
        stores.getValue(ContextRoute.WAKE).apply {
            appendUser("旧".repeat(60))
            appendAssistant("答".repeat(60))
            appendUser(pending)
        }

        val lines = withLines { captured -> rig.wakeAndFinal(input); captured.toList() }

        assertTrue(
            lines.contains("P3_TURN end turn=asr-1 outcome=FAILED code=NO_USER_TURN steps=0"),
            "the turn must end FAILED/NO_USER_TURN rather than throw or send an unsendable shape: $lines",
        )
        assertTrue(rig.model.requests.isEmpty(), "an unsendable frame must never reach the transport")
    }
}
