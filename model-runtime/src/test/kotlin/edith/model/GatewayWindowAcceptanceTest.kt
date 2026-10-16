package edith.model

import edith.agent.GatewayTurn
import edith.agent.ModelClientGateway
import edith.agent.RequestFrame
import edith.agent.Segment
import edith.agent.SegmentOrigin
import edith.agent.SegmentRole
import edith.agent.SummaryBoundary
import edith.agent.TrimCounts
import edith.agent.TurnTrimmer
import edith.contracts.ContractError
import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.InputModality
import edith.plugin.InputPart
import edith.plugin.MessageRole
import edith.plugin.ModelClient
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelProfile
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.MonotonicClock
import edith.plugin.OutputModality
import edith.plugin.SecretHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * The gate is the `budgeter.prepare(...)` call itself, not the shape of the wire. [TurnTrimmer.total] and
 * [ContextBudgeter.prepare] compute **structurally identical** totals against the same `minOf(...)` limit — the same
 * per-item `bytes + 32`, the same fixed 32, and `instructions == frame.systemText` — so parity is exact and the
 * gateway's `- 32` in `windowBytes` is a deliberate **safety margin**, not a correction. A frame trimmed to that
 * margin is therefore at least 32 bytes under the check it must pass; what these tests can still catch is a *shape*
 * drift in the mapping (for example rendering each system segment as its own message, or merging the leading context
 * in a way that inflates the cost), which is why the frame below is trimmed right up to the window.
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
     * A gateway over [recording] (the shared [client] by default) whose profile lookup answers [chosen]. A fresh
     * instance per test keeps a tight-profile turn from leaking into another test through [ModelClientGateway.lastTrim].
     */
    private fun gateway(
        recording: RecordingModelClient = client,
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
        // Byte-exact, so a shape drift in the mapping shows up as a rejection rather than being swallowed:
        //
        //   ContextBudgeter limit = minOf(93_000, 100_000 - 6_000 - 1_000) = **93_000**
        //   gateway window        = 93_000 - FIXED_OVERHEAD(32)            = **92_968**
        //   TurnTrimmer.total()   = 32 + (3 + 32) + (2_800 + 32) + (90_000 + 32) + (5 + 32) = **92_968**
        //
        // The frame sits exactly on the trim window, so cut ① does not fire and the whole transcript is sent. The
        // send-time total is the same 92_968 (identical formula) against the 93_000 limit — 32 bytes of slack, all of
        // it the deliberate margin. The discriminating assertion is that `prepare` accepts it.
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
        assertEquals(frame, TurnTrimmer().trim(frame, 92_968).kept, "92_968 is the exact window: nothing is cut")

        gateway().turn(turn(frame))

        val sent = client.requests.single()
        try {
            budgeter.prepare(profile, sent)
        } catch (rejected: ContractError.ConfigurationRejected) {
            fail("the gateway trimmed to a request the send-time ContextBudgeter rejects: ${rejected.message}")
        }
        // Supporting observations, not the discriminator: the untrimmed frame's transcript survives as history and
        // the current turn is the request input.
        assertEquals(2, sent.history.size)
        assertEquals(listOf("hello"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
    }

    /**
     * The genuinely tight case (Task 8's flagged gap): a window so small that **only the current turn's input can
     * survive** the trim. The other acceptance tests trim at or near a 93 000-token window and cut nothing; this one
     * makes cut ① fire and then hands the result to the real send-time [ContextBudgeter].
     *
     * `tightProfile()` pins `minOf(200, 100_000 - 128 - 128)` = **200**, so the trim window is `200 - 32` = **168**:
     *
     * ```
     * ContextBudgeter limit = minOf(200, 100_000 - 128 - 128)                    = **200**
     * gateway window        = 200 - FIXED_OVERHEAD(32)                           = **168**
     * frame total           = 32 + (40 + 32) + (1 + 32) + (2 + 32) + (14 + 32)   = **217**  > 168  ⇒ ① fires
     * kept total            = 32 + (40 + 32) + (14 + 32)                         = **150**  ≤ 168
     * ```
     *
     * The old turn is deliberately **two** segments (`USER` + `ASSISTANT`) so that the input sits inside cut ①'s slice:
     * with a one-segment turn the slice would stop before the input and the eviction filter would never be consulted.
     * `prepare` re-measures the kept request with the same per-message arithmetic, so the acceptance is exact.
     */
    @Test fun `a frame trimmed to a window that fits only the current turn input is accepted`() {
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "i".repeat(40))),
            conversationFlow = listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "u", SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "a1", SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, "现在几点了"),
            ),
            tools = emptyList(),
        )
        // The window really is the tight one: the untrimmed frame does not fit, and one whole old turn is what gives way.
        assertEquals(1, TurnTrimmer().trim(frame, 168).droppedTurns, "the whole old turn is dropped")

        val tightClient = RecordingModelClient()
        val tightGateway = gateway(recording = tightClient, chosen = tightProfile())
        tightGateway.turn(turn(frame, tightProfile()))

        val sent = tightClient.requests.single()
        // The discriminating assertion: the real send-time budgeter accepts the trimmed request.
        try {
            budgeter.prepare(tightProfile(), sent)
        } catch (rejected: ContractError.ConfigurationRejected) {
            fail("a tightly trimmed frame must still be sendable: ${rejected.message}")
        }
        // …and what survived is the current turn's input, with the old turn gone.
        assertEquals(emptyList(), sent.history, "the whole old turn left the transcript")
        assertEquals(listOf("现在几点了"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })
        assertEquals(TrimCounts(1, emptyList(), false), tightGateway.lastTrim)
    }

    @Test fun `the merged leading context is accepted where the trailing memory order is rejected`() {
        // The owner-approved design change (2026-10-16), proven against the real budgeter: the old order — transcript
        // then a `MEMORY_EVIDENCE` user turn then the input — ends the history on a lone user group, which
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
        } catch (rejected: ContractError.ConfigurationRejected) {
            fail("the merged leading context must be a sendable shape: ${rejected.message}")
        }
        // …and it really is the merged order: memory folded into the head user message, not a second user turn.
        val head = sent.history.first() as ModelHistoryItem.Text
        assertEquals(MessageRole.USER, head.role)
        assertTrue(head.text.contains(memory), head.text)
        assertTrue(head.text.endsWith("第一轮问"), head.text)
        assertEquals(ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一轮答"), sent.history[1])
        assertEquals(listOf("现在呢"), sent.input.filterIsInstance<InputPart.Text>().map { it.text })

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
}
