package com.sheen.rokid.model

import com.sheen.rokid.agent.CONTEXT_OVERFLOW
import com.sheen.rokid.agent.ContextPolicy
import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.GatewayTurn
import com.sheen.rokid.agent.ModelClientGateway
import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.agent.SegmentRole
import com.sheen.rokid.agent.SummaryBoundary
import com.sheen.rokid.agent.TrimCounts
import com.sheen.rokid.agent.TurnTrimmer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task 4's cross-module half: whatever the gateway sends must be a shape the send-time [ContextBudgeter] **accepts**.
 *
 * `ContextBudgeter` lives in `model-runtime`, and `model-runtime` already depends on `agent-runtime`
 * (`model-runtime/build.gradle.kts:13`), so the reverse test dependency — the one the task brief sketched for
 * `ModelClientGatewayTest` — would be a project cycle. This test therefore drives the real [ModelClientGateway] from
 * the module that owns the budgeter and hands the outgoing [ModelRequest] straight to it.
 *
 * ## What the discriminating assertion is (I-4)
 *
 * The gate is the `budgeter.prepare(...)` call itself, not the shape of the wire. [TurnTrimmer] and
 * [ContextBudgeter.prepare] both price text with `TokenMeter` token units (0.3 design §5.2), both add the same fixed 32
 * and the same per-message 32, and both charge the same
 * [com.sheen.rokid.contracts.ImageBudget.UNITS_PER_IMAGE] per image. The gateway's `- 32` in its window is still a
 * deliberate safety margin, not a unit correction: the wire rendering can fold leading context and itemise a tool
 * round, so a request's price is not necessarily the trimmer's per-segment total. These tests pin the send-time
 * acceptance and, where a boundary is meaningful, the exact token price of a fixture.
 *
 * The margin itself is pinned next to the code it lives in, by `ModelClientGatewayTest`'s
 * `the trim window is the send time limit minus a deliberate safety margin`.
 */
class GatewayWindowAcceptanceTest {

    private val budgeter = ContextBudgeter()
    private val client = RecordingModelClient()
    private val envelopeFor: (String, Long, Long) -> EventEnvelope = { requestId, start, end ->
        EventEnvelope(SessionId("session"), Generation(1), requestId, start, end)
    }

    /**
     * A [ModelClient] that records the request and completes the turn on the calling thread, so the gateway settles
     * synchronously and the test can assert on exactly what it sent. No text is ever printed or logged.
     */
    private class RecordingModelClient : ModelClient {
        val requests = mutableListOf<ModelRequest>()
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            requests += request
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    /**
     * A [ModelClient] that answers the provider's overflow verdict for its first [overflows] requests and completes the
     * turn afterwards, recording every request. This is how an overflow the local estimate cannot see (the provider
     * counts its own tokens, this project only estimates) reaches the gateway's recovery path.
     */
    private class OverflowFirstModelClient(private val overflows: Int) : ModelClient {
        val requests = mutableListOf<ModelRequest>()
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            requests += request
            val overflow = requests.size <= overflows
            listener(
                ModelStreamEvent.Terminal(
                    request.requestId,
                    if (overflow) ModelEnd.FAILED else ModelEnd.COMPLETED,
                    null,
                    if (overflow) CONTEXT_OVERFLOW else null,
                ),
            )
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    /**
     * A gateway over [recording] (the shared [client] by default) whose profile lookup answers [chosen]. A fresh
     * instance per test keeps a tight-profile turn from leaking into another test through [ModelClientGateway.lastTrim].
     */
    private fun gateway(
        recording: ModelClient = client,
        chosen: ModelProfile = profile,
    ) = ModelClientGateway(
        client = recording,
        profileLookup = { chosen },
        clock = object : MonotonicClock { override fun nowNanos() = 0L },
        scheduler = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
                object : CancellationHandle { override fun cancel() = Unit }
        },
        envelopeFor = envelopeFor,
    )

    private fun tightProfile() = profile.copy(inputBudgetTokens = 200, maxOutputTokens = 128, safetyTokens = 128)

    /**
     * The trim window the gateway uses at send time: the session `ContextPolicy.hardLimit`
     * (`hardInputLimit - hardMargin`), recomputed here through the real policy so a budget change cannot leave this
     * helper behind. The comparison in the production-shape test is a "nothing was cut" check, so sharing the send-time
     * `prepare` limit itself would be circular; the formula above it is `ContextBudgeter`'s own.
     */
    private fun limitWindowTokens(chosen: ModelProfile): Int = ContextPolicy.from(chosen).hardLimit

    private fun turn(frame: RequestFrame?, chosen: ModelProfile = profile) =
        GatewayTurn("turn-7", chosen.id, "You are Edith.", emptyList(), emptyList(), 1_000_000_000L, frame = frame)

    /**
     * `ModelProfile` requires `inputBudgetTokens + maxOutputTokens + safetyTokens <= usableWindowTokens`
     * (`ModelServices.kt:44`), so these four pin `minOf(93_000, 100_000 - 6_000 - 1_000)` = **93_000**.
     */
    private val profile = ModelProfile(
        id = "deepseek-default",
        revision = 7,
        name = "DeepSeek chat",
        service = "deepseek",
        baseUrl = "https://api.deepseek.com",
        protocolId = "DEEPSEEK_RESPONSES",
        model = "deepseek-chat",
        secret = object : SecretHandle { override val id = "secret-1" },
        usableWindowTokens = 100_000,
        inputBudgetTokens = 93_000,
        maxOutputTokens = 6_000,
        safetyTokens = 1_000,
        timeoutMillis = 30_000,
        inputs = setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        verifications = emptyList(),
    )

    private fun request(
        history: List<ModelHistoryItem>,
        input: List<String>,
    ) = ModelRequest(
        envelope = EventEnvelope(SessionId("session"), Generation(1), "turn-7", 0, 0),
        requestId = "turn-7",
        turnId = "turn-7",
        profileId = profile.id,
        profileRevision = profile.revision,
        instructions = "sys",
        input = input.map { InputPart.Text(it) },
        history = history,
        output = OutputModality.TEXT,
        deadlineNanos = 1_000_000_000L,
    )

    @Test fun `a frame at the trim window is accepted by the send time budgeter`() {
        // Token-exact for the trimmer and for the send-time budgeter:
        //
        //   ContextBudgeter limit = minOf(93_000, 100_000 - 6_000 - 1_000) = **93_000 token units**
        //   gateway trim window   = policy hardLimit (93_000 - 9_300 margin) = **83_700 token units**
        //   TurnTrimmer.total()   = 32 + (1 + 32) + (700 + 32) + (22_500 + 32) + (2 + 32) = **23_363 token units**
        //
        // The frame is comfortably inside the window, so cut ① does not fire and the whole transcript is sent. The
        // exact price is pinned by the boundary pair below: at 23_363 nothing is cut, one unit less evicts the whole
        // oldest turn. The discriminating acceptance assertion remains the send-time `prepare` call.
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            conversationFlow = listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "u".repeat(2_800), SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "a".repeat(90_000), SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, "hello"),
            ),
            tools = emptyList(),
        )

        // The trimmer's own verdict at the window: nothing is cut, so the assertion below cannot drift from it.
        assertEquals(frame, TurnTrimmer().trim(frame, 83_700).kept, "83_700 is the gateway window: nothing is cut")
        assertEquals(frame, TurnTrimmer().trim(frame, 23_363).kept, "23_363 is the frame's exact token price")
        assertEquals(
            1,
            TurnTrimmer().trim(frame, 23_362).droppedTurns,
            "one unit under the exact price must evict the oldest whole turn",
        )

        gateway().turn(turn(frame))

        val sent = client.requests.single()
        try {
            budgeter.prepare(profile, sent)
        } catch (rejected: ContractError) {
            fail("the gateway trimmed to a request the send-time ContextBudgeter rejects: ${rejected.message}")
        }
        // Supporting observations, not the discriminator: the untrimmed frame's transcript survives as history and
        // the current turn is the request input.
        assertEquals(2, sent.history.size)
        assertEquals(listOf("hello"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
    }

    /**
     * The genuinely tight case (Task 8's flagged gap): a window so small that **only the current turn's input can
     * survive** the trim. The other acceptance tests trim at or near a 93 000-unit window and cut nothing; this one
     * makes cut ① fire and then hands the result to the real send-time [ContextBudgeter].
     *
     * `tightProfile()` pins `minOf(200, 100_000 - 128 - 128)` = **200**, so the trim window is the policy's
     * `hardLimit = 200 - floor(200 × 0.10)` = **180 token units**:
     *
     * ```
     * ContextBudgeter limit = minOf(200, 100_000 - 128 - 128)                 = **200 token units**
     * gateway trim window   = policy.hardLimit (200 - 20 hard margin)         = **180 token units**
     * frame total           = 32 + (10 + 32) + (1 + 32) + (10 + 32) + (5 + 32) = **186** > 180 ⇒ ① fires     * kept total            = 32 + (10 + 32) + (5 + 32)                       = **111** ≤ 180
     * ```
     *
     * The old turn is deliberately **two** segments (`USER` + `ASSISTANT`) so that the input sits inside cut ①'s slice:
     * with a one-segment turn the slice would stop before the input and the eviction filter would never be consulted.
     * `prepare` re-measures the kept request in the same token units, so the acceptance is exact.
     */
    @Test fun `a frame trimmed to a window that fits only the current turn input is accepted`() {
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "i".repeat(40))),
            conversationFlow = listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "u", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "a".repeat(40), SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, "现在几点了"),
            ),
            tools = emptyList(),
        )
        // The window really is the tight one: the untrimmed frame does not fit, and one whole old turn is what gives way.
        assertEquals(1, TurnTrimmer().trim(frame, 180).droppedTurns, "the whole old turn is dropped")

        val tightClient = RecordingModelClient()
        val tightGateway = gateway(recording = tightClient, chosen = tightProfile())
        tightGateway.turn(turn(frame, tightProfile()))

        val sent = tightClient.requests.single()
        // The discriminating assertion: the real send-time budgeter accepts the trimmed request.
        try {
            budgeter.prepare(tightProfile(), sent)
        } catch (rejected: ContractError) {
            fail("a tightly trimmed frame must still be sendable: ${rejected.message}")
        }
        // …and what survived is the current turn's input, with the old turn gone.
        assertEquals(emptyList(), sent.history, "the whole old turn left the transcript")
        assertEquals(listOf("现在几点了"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
        assertEquals(TrimCounts(1, emptyList(), false), tightGateway.lastTrim)
    }

    /**
     * The unit switch at the gateway boundary: a Chinese-only input whose UTF-8 size exceeded the tight profile's
     * budget is priced by `TokenMeter` and accepted.
     *
     * The frame has no evictable transcript turn, so the trimmer leaves the input in the kept frame (its own token
     * total, 166, is inside the 168-unit window) and the real send-time budgeter accepts the same price: **166 token
     * units** (32 + (10 + 32) + (60 + 32)). The byte estimator charged **316** (32 + (40 + 32) + (180 + 32)) and
     * rejected the shape. The exact value proves the text went through `TokenMeter`, not that the assertion merely got
     * looser.
     */
    @Test fun `a Chinese input the byte estimator used to reject is accepted at its token price`() {
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "i".repeat(40))),
            conversationFlow = listOf(Segment(SegmentOrigin.USER_INPUT, true, "现在几点了".repeat(12))),
            tools = emptyList(),
        )

        gateway(chosen = tightProfile()).turn(turn(frame, tightProfile()))

        val sent = client.requests.single()
        val prepared = budgeter.prepare(tightProfile(), sent)
        assertEquals(166, prepared.estimatedInputTokens)
    }

    /**
     * The fail-closed boundary of the new unit: one token unit over the tight profile's limit is still the dedicated
     * overflow signal, not a request quietly sent. 95 CJK characters price at **201 units**
     * (32 + (10 + 32) + (95 + 32)) and there is no history to evict, so nothing can make the request fit.
     */
    @Test fun `a request over the token limit still fails closed with the overflow signal`() {
        val overLimit = request(
            history = emptyList(),
            input = listOf("现".repeat(95)),
        ).copy(instructions = "i".repeat(40))

        assertFailsWith<ContractError.ContextWindowExceeded> {
            budgeter.prepare(tightProfile(), overLimit)
        }
    }

    @Test fun `a tail placed memory is accepted where the trailing memory history order is rejected`() {
        // 0.3 design §5.1/§5.3 moves `MEMORY_EVIDENCE` out of the history head and into the dynamic tail. The shape is
        // still accepted by the real budgeter; the old order — transcript then a `MEMORY_EVIDENCE` user turn then the
        // input — is still the counterexample, because it ends the history on a lone user group which
        // `ContextBudgeter` rejects **by design** (whole-group eviction depends on it). Every later turn would fail
        // `ConfigurationRejected` until an app restart (the 2026-10-14 incident).
        //
        // The frame is assembled in the production order (`TurnRunner`: store snapshot → perception → memory →
        // `USER_INPUT`), so the memory is the last thing before the input.
        val memory = "<reference_context>\n用户喜欢咖啡\n</reference_context>"
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            conversationFlow = listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "第一轮问", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "第一轮答", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, memory),
                Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
            ),
            tools = emptyList(),
        )

        gateway().turn(turn(frame))

        val sent = client.requests.single()
        // The new order is accepted (the discriminating assertion).
        try {
            budgeter.prepare(profile, sent)
        } catch (rejected: ContractError) {
            fail("the tail placed memory must be a sendable shape: ${rejected.message}")
        }
        // …and it really is the new layout: the history head is the transcript's own user turn, the memory rides the
        // request input ahead of the wearer's turn.
        val head = sent.history.first() as ModelHistoryItem.Text
        assertEquals(MessageRole.USER, head.role)
        assertEquals("第一轮问", head.text, "the head must not be rewritten by request-scoped memory")
        assertEquals(ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一轮答"), sent.history[1])
        val input = sent.input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.startsWith(memory), "the memory is the input's dynamic prefix: $input")
        assertTrue(input.endsWith("现在呢"), "the wearer's turn stays last: $input")

        // Counterexample: the order the plan used to specify. Built by hand, because that is exactly what the old
        // mapping produced — history ends on `Text(USER, memory)`, one user group with no reply.
        val oldOrder = request(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "第一轮问"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一轮答"),
                ModelHistoryItem.Text(MessageRole.USER, memory),
            ),
            input = listOf("现在呢"),
        )
        assertFailsWith<ContractError.ConfigurationRejected> {
            budgeter.prepare(profile, oldOrder)
        }
    }

    @Test fun `a standalone leading user message beside the transcript's user turn is rejected`() {
        // The mutation that pins *why* the context is merged rather than merely prepended: `[USER(context)]` followed
        // by the transcript's own `[USER(transcript), ASSISTANT(reply), …]` is two consecutive user texts, and the
        // budgeter rejects it. This is the shape a naive reading of "one leading user message" produces, and it is
        // why `frameToTranscript` folds the context into the transcript's head user turn instead.
        val literalLeadingMessage = request(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "<reference_context>\n用户喜欢咖啡\n</reference_context>"),
                ModelHistoryItem.Text(MessageRole.USER, "第一轮问"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一轮答"),
            ),
            input = listOf("现在呢"),
        )
        assertFailsWith<ContractError.ConfigurationRejected> {
            budgeter.prepare(profile, literalLeadingMessage)
        }
    }

    @Test fun `a summary without memory cannot stand at the head as an assistant message`() {
        // The other half of the same constraint, recorded so the shape choice is not mistaken for an oversight: a
        // bounded summary as the history's opening assistant message is rejected (the history must open with a user
        // text), which is why the summary is folded into the leading user message rather than emitted on its own.
        val summary = SummaryBoundary.render("先前对话摘要")
        assertFailsWith<ContractError.ConfigurationRejected> {
            budgeter.prepare(
                profile,
                request(
                    history = listOf(
                        ModelHistoryItem.Text(MessageRole.ASSISTANT, summary),
                        ModelHistoryItem.Text(MessageRole.USER, "第一轮问"),
                        ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一轮答"),
                    ),
                    input = listOf("现在呢"),
                ),
            )
        }
    }

    /**
     * The **production shape** (head summary + memory + a tool round, store-backed) through the real send-time
     * [ContextBudgeter] — the gap Task 8 flagged and Task 9 only half closed.
     *
     * `GatewayWindowAcceptanceTest`'s other cases build their `RequestFrame` by hand, and the only store-backed
     * production shape was checked by `plugins-smart`'s `StoreSessionWiringTest.acceptableWireHistory` — a **local
     * re-implementation** of the budgeter's rules, because `plugins-smart` cannot see `model-runtime` (Gradle cycle).
     * A re-implementation can agree with itself while disagreeing with the real class. This test removes the middle
     * man: the frame below is built exactly as `SmartAssistantPlugin.buildFrame`/`frameBuilderFor` build it (a
     * `ConversationStore` snapshot, then memory, then this turn's `USER_INPUT` last), it goes through the real
     * [ModelClientGateway] — the only `frame → ModelRequest` renderer — and the outgoing request goes to the real
     * `ContextBudgeter().prepare(profile, request)`.
     *
     * The store is driven through its own public API, so the head summary carries the boundary `appendSummary`
     * rendered at insert time and the tool round is stored as `ToolCall`/`ToolOutput` (not as a plain text round).
     *
     * The window for the "nothing was trimmed" check below is written out here rather than calling the gateway's
     * `windowTokens`: that function is `internal` to `agent-runtime`, and this module is on the other side of the
     * boundary. It is the same formula with the same deliberate 32-unit margin, floored at 0 so a small profile cannot
     * produce a negative limit. Both stages now count the same token unit, so this comparison prices the same frame
     * the trimmer did.
     */
    @Test fun `the production shaped store backed frame is accepted by the send time budgeter`() {
        val summaryBody = "上一段对话已被压缩为摘要。"
        // The frame's flow exactly as `frameBuilderFor` produces it from the store's items: index 0 is the summary the
        // store's own metadata marks as one (never inferred from text - the head text is just a bounded assistant
        // text), every other item is mapped by the plugin's `toSegment` rule (`SmartAssistantPlugin.kt:2336`), and
        // memory then this turn's `USER_INPUT` follow, in that order.
        val store = ConversationStore()
        store.appendSummary(summaryBody)
        store.appendUser("旧问题")
        store.appendAssistant("旧回答")
        store.appendToolCall("c1", "notify_user", """{"text":"稍等","channel":"screen"}""")
        store.appendToolOutput("c1", "notified:1")
        // Two views, because a flat `List<ModelHistoryItem>` cannot attribute a summary and cannot carry structured
        // tool items: `snapshot()` hands over the summary's own origin (and only that item is needed from it),
        // `history()` over the wire-shape items the plugin's `toSegment` encodes. `frameBuilderFor` answers exactly
        // these two questions with exactly these two calls (`store.headIsSummary()` / the transcript argument).
        val headSnapshot = store.snapshot().first()
        assertEquals(SegmentOrigin.SUMMARY, headSnapshot.origin, "the store head must be the summary this shape is about")
        assertFalse(headSnapshot.trusted, "a summary is never trusted")
        val frameItems = buildList {
            add(headSnapshot)
            store.history().drop(1).forEach { add(it.toSegment()) }
        }
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "<reference_context>\n用户喜欢咖啡\n</reference_context>")
        val flow = buildList {
            addAll(frameItems)
            add(memory)
            add(Segment(SegmentOrigin.USER_INPUT, true, "现在呢"))
        }
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            conversationFlow = flow,
            tools = emptyList(),
        )
        // The head really is the compressed summary, and cut ① has nothing to drop at this profile's window - so the
        // shape below is the untrimmed production frame, not a trimmer artefact.
        assertTrue(store.headIsSummary(), "the store head must be the summary this shape is about")
        val window = limitWindowTokens(profile)
        assertEquals(frame, TurnTrimmer().trim(frame, window).kept)

        gateway().turn(turn(frame))

        val sent = client.requests.single()
        // The discriminating assertion: the real budgeter accepts the shape the production path actually sends.
        try {
            budgeter.prepare(profile, sent)
        } catch (rejected: ContractError) {
            fail("the production shaped store backed frame must be sendable: ${rejected.message}")
        }
        // …and it is the production shape, not merely accepted: the summary is folded into the one leading user
        // message, ahead of the oldest surviving turn; the memory rides the request input ahead of the current
        // utterance; the tool round came back structured (a plain-text round would hand the endpoint an assistant
        // message where it expects the call it produced).
        val head = sent.history.first() as ModelHistoryItem.Text
        assertEquals(MessageRole.USER, head.role)
        assertTrue(head.text.contains(SummaryBoundary.render(summaryBody)), head.text)
        assertFalse(
            head.text.contains("<reference_context>"),
            "memory is request-scoped tail context, not head context: ${head.text}",
        )
        assertTrue(head.text.endsWith("旧问题"), head.text)
        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "旧回答"),
                ModelHistoryItem.ToolCall(
                    callId = "c1",
                    name = "notify_user",
                    argsJson = """{"text":"稍等","channel":"screen"}""",
                    itemId = "c1",
                ),
                ModelHistoryItem.ToolOutput("c1", "notified:1"),
            ),
            sent.history.drop(1),
        )
        val input = sent.input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.startsWith(memory.text), "the memory is the input's dynamic prefix: $input")
        assertTrue(input.endsWith("现在呢"), "the wearer's turn stays last: $input")
        // The tool round survives `frameToTranscript` as **one** leading user group: the property whole-group eviction
        // depends on, and the reason no request-scoped block can be emitted as its own user message.
        assertEquals(1, sent.history.count { it is ModelHistoryItem.Text && it.role == MessageRole.USER })
    }

    /**
     * R70's two deterministic recoveries must keep the transcript a shape the real send-time [ContextBudgeter] accepts.
     *
     * The frame is the production WAKE shape with **two tool rounds in one user turn**, rendered exactly as
     * `frameBuilderFor` renders the store's items (`toSegment`): `USER, ASSISTANT opener, TOOL_ROUND, ASSISTANT,
     * TOOL_ROUND, ASSISTANT final`.
     *
     * Recovery ① may drop `TOOL_ROUND` segments. Dropping only those leaves the opener of the dropped round beside the
     * next assistant text — two assistant texts in a row, a shape `ContextBudgeter` rejects **by design** — so the first
     * recovery must also merge the assistant texts the removal brought together. Recovery ② (HARD) must additionally
     * drop the older current-turn round: otherwise a multi-round turn ends `CONTEXT_OVERFLOW` at a size the compression
     * already fit.
     */
    @Test fun `overflow recovery on the production store shape is accepted by the real budgeter`() {
        val store = ConversationStore()
        store.appendUser("旧问题")
        store.appendAssistant("我来看看")
        store.appendToolCall("c1", "device", "{}")
        store.appendToolOutput("c1", "旧观察")
        store.appendAssistant("看到了")
        store.appendToolCall("c2", "device", "{}")
        store.appendToolOutput("c2", "新观察")
        store.appendAssistant("最终答案")
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            conversationFlow = store.history().map { it.toSegment() } + Segment(SegmentOrigin.USER_INPUT, true, "现在呢"),
            tools = emptyList(),
        )

        // The provider reports an overflow the local estimate cannot see **twice**; the third attempt is the hard
        // truncate.
        val overflowClient = OverflowFirstModelClient(overflows = 2)
        gateway(recording = overflowClient).turn(turn(frame))

        assertEquals(3, overflowClient.requests.size, "one normal send and two deterministic recoveries")
        val aggressive = overflowClient.requests[1]
        val hard = overflowClient.requests[2]
        // (a) the AGGRESSIVE retry must be a request the real budgeter would send.
        try {
            budgeter.prepare(profile, aggressive)
        } catch (rejected: ContractError) {
            fail("the AGGRESSIVE recovery sent a shape the real ContextBudgeter rejects: ${rejected.message}")
        }
        // (b) HARD's shape must be accepted too, and it must have dropped the older current-turn round.
        try {
            budgeter.prepare(profile, hard)
        } catch (rejected: ContractError) {
            fail("the HARD recovery sent a shape the real ContextBudgeter rejects: ${rejected.message}")
        }
        assertEquals(listOf("c2"), hard.history.filterIsInstance<ModelHistoryItem.ToolCall>().map { it.callId })
        assertTrue(
            hard.history.filterIsInstance<ModelHistoryItem.Text>().none { it.text == "我来看看" },
            "the dropped round's assistant opener must not survive HARD: ${hard.history}",
        )
        // Both recovered shapes drop the older round; AGGRESSIVE keeps the conversation text around it.
        assertEquals(listOf("c2"), aggressive.history.filterIsInstance<ModelHistoryItem.ToolCall>().map { it.callId })
        assertTrue(
            aggressive.history.filterIsInstance<ModelHistoryItem.Text>().any { it.text.contains("旧问题") },
            "AGGRESSIVE drops the round, not the conversation: ${aggressive.history}",
        )
    }
}

/**
 * The plugin's `toSegment` rule, mirrored here because `plugins-smart` is downstream of `model-runtime` and its own
 * private copy is not visible from this module (`SmartAssistantPlugin.kt:2336`). Kept identical in intent: roles
 * ride along for text, and a tool item becomes a `TOOL_ROUND` segment whose text is the frame vocabulary's JSON entry,
 * so the gateway restores the round as **structured** items rather than as assistant texts.
 */
private fun ModelHistoryItem.toSegment(): Segment = when (this) {
    is ModelHistoryItem.Text -> Segment(
        origin = SegmentOrigin.TRANSCRIPT,
        trusted = true,
        text = text,
        role = if (role == MessageRole.USER) SegmentRole.USER else SegmentRole.ASSISTANT,
    )
    is ModelHistoryItem.ToolCall -> Segment(
        origin = SegmentOrigin.TOOL_ROUND,
        trusted = true,
        text = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "call")
                    put("callId", callId)
                    put("itemId", itemId)
                    put("name", name)
                    put("argsJson", argsJson)
                },
            )
        }.toString(),
    )
    is ModelHistoryItem.ToolOutput -> Segment(
        origin = SegmentOrigin.TOOL_ROUND,
        trusted = true,
        text = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "output")
                    put("callId", callId)
                    put("output", output)
                },
            )
        }.toString(),
    )
}
