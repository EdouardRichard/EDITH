package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import com.sheen.rokid.testsupport.FakeCancellation
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 0.25 Task 13 / R70 (D100) + R101: two overflow detectors and at most two deterministic recoveries.
 *
 * The budgeter's dedicated [ContractError.ContextWindowExceeded] and a provider `CONTEXT_OVERFLOW` verdict both mean
 * the same thing to this turn: the request that was assembled did not fit. Recovery is deterministic and NEVER an LLM
 * summary request:
 *
 * 1. re-trim with the historical tool rounds compressed away;
 * 2. hard-truncate to system + the latest turn + the current input;
 * 3. a third overflow returns `CONTEXT_OVERFLOW` instead of sending a fourth request.
 *
 * A plain [ContractError.ConfigurationRejected] is **not** that signal (R101): it also means a profile-revision race,
 * an invalid endpoint or a client-state refusal, so it is neither retried with a cheaper context nor relabelled.
 */
class ContextOverflowTest {

    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
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
        inputBudgetTokens = 60_000,
        maxOutputTokens = 8_000,
        safetyTokens = 1_000,
        timeoutMillis = 30_000,
        inputs = setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        verifications = emptyList(),
    )
    private val instructions = "You are Edith."
    private val envelopeFor: (String, Long, Long) -> EventEnvelope = { requestId, start, end ->
        EventEnvelope(SessionId("session"), Generation(1), requestId, start, end)
    }

    private fun gateway(client: ModelClient) =
        ModelClientGateway(client, { profile }, clock, scheduler, envelopeFor)

    // ── the frame every test sends: two turns, each with a tool round ───────────────────────────────

    private fun screenTree(label: String): String = ToolResultEnvelope.encode(
        ToolExecution(outcome = ToolOutcome.Done, accepted = true),
        // Task 8 fix review: the frame now carries a real unified envelope, not the pre-0.3 `data.screen` copy. The
        // screen tree is `observation.screen`, so recovery sees the same payload shape production sends.
        ToolObservation(
            foregroundPackage = "com.example.app",
            screen = "#n1 button $label",
            vision = ToolVision.NotRequested,
        ),
        """{"action":"observe"}""",
    )

    private fun toolCall(callId: String): Segment = Segment(
        SegmentOrigin.TOOL_ROUND,
        true,
        buildJsonArray {
            add(buildJsonObject {
                put("type", "call")
                put("callId", callId)
                put("itemId", "item-$callId")
                put("name", "device")
                put("argsJson", "{}")
            })
        }.toString(),
    )

    private fun toolOutput(callId: String, output: String): Segment = Segment(
        SegmentOrigin.TOOL_ROUND,
        true,
        buildJsonArray {
            add(buildJsonObject {
                put("type", "output")
                put("callId", callId)
                put("output", output)
            })
        }.toString(),
    )

    private fun frame(perception: String? = null, memory: String? = null): RequestFrame = RequestFrame(
        systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
        conversationFlow = listOfNotNull(
            Segment(SegmentOrigin.TRANSCRIPT, true, "old-question", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "old-answer", SegmentRole.ASSISTANT),
            toolCall("c1"),
            toolOutput("c1", screenTree("old")),
            Segment(SegmentOrigin.TRANSCRIPT, true, "recent-question", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "(tool call)", SegmentRole.ASSISTANT),
            toolCall("c2"),
            toolOutput("c2", screenTree("current")),
            perception?.let { Segment(SegmentOrigin.PERCEPTION, false, it) },
            memory?.let { Segment(SegmentOrigin.MEMORY_EVIDENCE, false, it) },
            Segment(SegmentOrigin.USER_INPUT, true, "now"),
        ),
        tools = emptyList(),
    )

    private fun turn(frame: RequestFrame? = frame()): GatewayTurn = GatewayTurn(
        turnId = "turn-7",
        profileId = profile.id,
        instructions = instructions,
        history = emptyList(),
        tools = emptyList(),
        deadlineNanos = 1_000_000_000L,
        frame = frame,
    )

    // ── the scripted transport ─────────────────────────────────────────────────────────────────────

    private enum class Fault {
        LOCAL_REJECT,
        CONFIG_REJECTED,
        PROVIDER_OVERFLOW,
        HTTP_FAILURE,
        CAPABILITY_MISSING,
        COMPLETED,
    }

    private class ScriptedOverflowClient(private val faults: MutableList<Fault>) : ModelClient {
        val requests = mutableListOf<ModelRequest>()

        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            requests += request
            when (faults.removeFirstOrNull() ?: Fault.COMPLETED) {
                Fault.LOCAL_REJECT ->
                    throw ContractError.ContextWindowExceeded("Context window exceeded")

                Fault.CONFIG_REJECTED ->
                    throw ContractError.ConfigurationRejected("Profile revision mismatch")

                Fault.PROVIDER_OVERFLOW ->
                    listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, CONTEXT_OVERFLOW))

                Fault.HTTP_FAILURE ->
                    listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, "HTTP_STATUS"))

                Fault.CAPABILITY_MISSING ->
                    throw ContractError.CapabilityMissing("image ref unresolvable: current")

                Fault.COMPLETED ->
                    listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            }
            return FakeCancellation()
        }
    }

    private fun texts(request: ModelRequest): List<String> =
        request.history.filterIsInstance<ModelHistoryItem.Text>().map { it.text }

    private fun callIds(request: ModelRequest): List<String> =
        request.history.filterIsInstance<ModelHistoryItem.ToolCall>().map { it.callId }

    // ── recovery 1: compress the historical tool rounds ────────────────────────────────────────────

    @Test
    fun `a local budget rejection compresses the historical tool rounds before resending`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.LOCAL_REJECT, Fault.COMPLETED))
        val gateway = gateway(client)

        val reply = gateway.turn(turn())

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(2, client.requests.size)
        assertEquals(listOf("c1", "c2"), callIds(client.requests[0]), "the first attempt carried both tool rounds")
        assertEquals(listOf("c2"), callIds(client.requests[1]), "recovery 1 dropped the older tool round")
        assertTrue(texts(client.requests[1]).contains("old-question"), "compression keeps the old conversation text")
        assertEquals(1, gateway.overflowRecoveries)
        assertEquals(0, gateway.overflowFailures)
    }

    @Test
    fun `a provider context overflow triggers the same deterministic recovery`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.PROVIDER_OVERFLOW, Fault.COMPLETED))
        val gateway = gateway(client)

        val reply = gateway.turn(turn())

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(2, client.requests.size)
        assertEquals(listOf("c2"), callIds(client.requests[1]))
        assertEquals(1, gateway.overflowRecoveries)
    }

    // ── recovery 2: hard truncation, then the stable error ─────────────────────────────────────────

    @Test
    fun `a second overflow hard truncates to the latest turn and keeps a marked dynamic tail`() {
        // 0.3 Phase 2 Task 12 (spec §6.6): recovery ② may not silently drop the request-scoped tail. A huge perception
        // block and a huge memory evidence segment are kept as head + marker + tail, still ahead of the current input.
        val perception = "perception-start-" + "p".repeat(2_000) + "-perception-end"
        val memory = "memory-start-" + "m".repeat(2_000) + "-memory-end"
        val client = ScriptedOverflowClient(
            mutableListOf(Fault.LOCAL_REJECT, Fault.LOCAL_REJECT, Fault.COMPLETED),
        )
        val gateway = gateway(client)

        val reply = gateway.turn(turn(frame(perception = perception, memory = memory)))

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(3, client.requests.size)
        val hardened = client.requests[2]
        assertEquals("recent-question", texts(hardened).first(), "hard truncation starts at the latest user turn")
        assertTrue(texts(hardened).none { it == "old-question" }, "the earlier turn is gone: ${texts(hardened)}")
        assertEquals(listOf("c2"), callIds(hardened), "the latest tool round survives with the latest turn")
        val inputText = hardened.input.filterIsInstance<InputPart.Text>().joinToString("") { it.text }
        assertTrue(inputText.endsWith("now"), "the current input still ends the request input: $inputText")
        assertEquals(2, inputText.split("[truncated]").size - 1, "both dynamic segments carry the marker: $inputText")
        assertTrue(inputText.contains(perception.take(400)), "the perception head survives")
        assertTrue(inputText.contains(perception.takeLast(100)), "the perception tail survives")
        assertTrue(inputText.contains(memory.take(400)), "the memory head survives")
        assertTrue(inputText.contains(memory.takeLast(100)), "the memory tail survives")
        assertTrue(!inputText.contains(perception), "the whole perception block is not sent")
        assertTrue(!inputText.contains(memory), "the whole memory evidence is not sent")
        assertEquals(2, gateway.overflowRecoveries)
        assertEquals(0, gateway.overflowFailures)
    }

    @Test
    fun `a third provider overflow returns CONTEXT_OVERFLOW and sends no fourth request`() {
        val client = ScriptedOverflowClient(
            mutableListOf(Fault.PROVIDER_OVERFLOW, Fault.PROVIDER_OVERFLOW, Fault.PROVIDER_OVERFLOW),
        )
        val gateway = gateway(client)

        val reply = gateway.turn(turn())

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals(CONTEXT_OVERFLOW, reply.safeCode)
        assertTrue(reply.toolCalls.isEmpty())
        assertEquals(3, client.requests.size, "two recoveries: exactly three attempts, no extra summary request")
        assertEquals(2, gateway.overflowRecoveries)
        assertEquals(1, gateway.overflowFailures)
        // No LLM summary was asked for anywhere: every request still carries this frame's own system slot and tools.
        assertTrue(client.requests.all { it.instructions == "sys" }, "no summarization request was made")
        assertTrue(client.requests.all { it.tools.isEmpty() }, "no summarization request was made")
    }

    @Test
    fun `a third local rejection also ends as CONTEXT_OVERFLOW instead of escaping`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.LOCAL_REJECT, Fault.LOCAL_REJECT, Fault.LOCAL_REJECT))
        val gateway = gateway(client)

        val reply = gateway.turn(turn())

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals(CONTEXT_OVERFLOW, reply.safeCode)
        assertEquals(3, client.requests.size)
        assertEquals(1, gateway.overflowFailures)
    }

    // ── what must NOT be retried ───────────────────────────────────────────────────────────────────

    @Test
    fun `a non-overflow provider failure is returned unchanged after one attempt`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.HTTP_FAILURE))
        val gateway = gateway(client)

        val reply = gateway.turn(turn())

        assertEquals(ModelEnd.FAILED, reply.terminal)
        assertEquals("HTTP_STATUS", reply.safeCode)
        assertEquals(1, client.requests.size)
        assertEquals(0, gateway.overflowRecoveries)
    }

    @Test
    fun `a capability failure is not disguised as an overflow`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.CAPABILITY_MISSING))
        val gateway = gateway(client)

        assertFailsWith<ContractError.CapabilityMissing> { gateway.turn(turn()) }
        assertEquals(1, client.requests.size)
        assertEquals(0, gateway.overflowRecoveries)
    }

    @Test
    fun `a non-overflow configuration rejection is neither retried nor relabelled`() {
        // R101: `ConfigurationRejected` is also a profile-revision race, an invalid endpoint or a client-state refusal.
        // Only the budgeter's dedicated overflow signal may be answered with a cheaper resend; anything else must reach
        // the caller unchanged, with no recovery count spent and no `CONTEXT_OVERFLOW` relabel hiding the real cause.
        val client = ScriptedOverflowClient(mutableListOf(Fault.CONFIG_REJECTED))
        val gateway = gateway(client)

        val rejected = assertFailsWith<ContractError.ConfigurationRejected> { gateway.turn(turn()) }
        assertEquals("Profile revision mismatch", rejected.message)
        assertEquals(1, client.requests.size, "an unrelated configuration rejection is not retried")
        assertEquals(0, gateway.overflowRecoveries, "no overflow recovery was attempted")
        assertEquals(0, gateway.overflowFailures, "the failure was not counted as an overflow")
    }

    @Test
    fun `the pre-phase-5 history path still lets a local rejection escape unchanged`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.CONFIG_REJECTED))
        val gateway = gateway(client)
        val legacy = GatewayTurn(
            turnId = "turn-7",
            profileId = profile.id,
            instructions = instructions,
            history = listOf(ModelHistoryItem.Text(MessageRole.USER, "hi")),
            tools = emptyList(),
            deadlineNanos = 1_000_000_000L,
            frame = null,
        )

        assertFailsWith<ContractError.ConfigurationRejected> { gateway.turn(legacy) }
        assertEquals(1, client.requests.size, "without a frame there is no deterministic recovery to perform")
    }
}
