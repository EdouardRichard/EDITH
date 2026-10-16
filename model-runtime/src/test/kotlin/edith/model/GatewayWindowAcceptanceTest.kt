package edith.model

import edith.agent.GatewayTurn
import edith.agent.ModelClientGateway
import edith.agent.RequestFrame
import edith.agent.Segment
import edith.agent.SegmentOrigin
import edith.agent.SegmentRole
import edith.agent.TurnTrimmer
import edith.contracts.ContractError
import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.InputModality
import edith.plugin.InputPart
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
import kotlin.test.fail

/**
 * Task 4's cross-module half: a frame the gateway trims must reach the send-time [ContextBudgeter] in a shape it
 * **accepts**.
 *
 * `ContextBudgeter` lives in `model-runtime`, and `model-runtime` already depends on `agent-runtime`
 * (`model-runtime/build.gradle.kts:13`), so the reverse test dependency — the one the task brief sketched for
 * `ModelClientGatewayTest` — would be a project cycle. This test therefore drives the real [ModelClientGateway] from
 * the module that owns the budgeter and hands the outgoing [ModelRequest] straight to it.
 *
 * The window arithmetic itself (`windowBytes = min(inputBudget, usable - maxOutput - safety) - FIXED_OVERHEAD`) is
 * asserted directly next to the code in `ModelClientGatewayTest`'s
 * `the trim window subtracts the fixed overhead the send time budgeter adds`. This test pins the end-to-end property:
 * whatever the gateway trims to, the composition of the two must not reject.
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

    private fun gateway() = ModelClientGateway(
        client = client,
        profileLookup = { profile },
        clock = object : MonotonicClock { override fun nowNanos() = 0L },
        scheduler = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
                object : CancellationHandle { override fun cancel() = Unit }
        },
        envelopeFor = envelopeFor,
    )

    private fun turn(frame: RequestFrame?) =
        GatewayTurn("turn-7", profile.id, "You are Edith.", emptyList(), emptyList(), 1_000_000_000L, frame)

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

    @Test fun `a frame trimmed to the window is accepted by the send time budgeter`() {
        // Byte-exact, so the fixed 32 is visible on the wire rather than swallowed by the send-time check:
        //
        //   ContextBudgeter limit = minOf(93_000, 100_000 - 6_000 - 1_000) = 93_000
        //   gateway window        = 93_000 - FIXED_OVERHEAD(32)            = 92_968
        //
        //   TurnTrimmer.total()   = 32 + (3 + 32) + (2_831 + 32) + (90_000 + 32) + (5 + 32) = **92_999**
        //
        // 92_999 is the boundary that discriminates: 31 over the correct 92_968 window, so ① evicts the 90_000-byte
        // old turn; but 1 *under* the un-subtracted 93_000, where no cut would fire at all. Same frame, a different
        // number of whole turns on the wire, because the trimmer's `total()` already counts the same fixed 32 that
        // `ContextBudgeter.kt:61` adds on top.
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
            conversationFlow = listOf(
                Segment(SegmentOrigin.TRANSCRIPT, true, "u".repeat(2_831), SegmentRole.USER),
                Segment(SegmentOrigin.TRANSCRIPT, true, "a".repeat(90_000), SegmentRole.ASSISTANT),
                Segment(SegmentOrigin.USER_INPUT, true, "hello"),
            ),
            tools = emptyList(),
        )

        // The trimmer's own verdict at the same boundary, asserted here so the wire assertion cannot drift from it.
        assertEquals(1, TurnTrimmer().trim(frame, 92_968).kept.conversationFlow.size, "92_968 evicts the old turn")
        assertEquals(3, TurnTrimmer().trim(frame, 93_000).kept.conversationFlow.size, "93_000 would keep everything")

        gateway().turn(turn(frame))

        val request = client.requests.single()
        try {
            budgeter.prepare(profile, request)
        } catch (rejected: ContractError.ConfigurationRejected) {
            fail("the gateway trimmed to a window the send-time ContextBudgeter rejects: ${rejected.message}")
        }
        assertEquals(listOf("hello"), request.input.filterIsInstance<InputPart.Text>().map { it.text })
        assertEquals(
            emptyList(),
            request.history,
            "the trim window (92_968) evicts the old turn exactly as the trimmer's own verdict said; a window 32 bytes " +
                "wider (the un-subtracted 93_000) would have kept the whole 92_999-byte frame instead",
        )
    }
}
