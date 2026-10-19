package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
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
 * 0.25 Task 13 / R70 (D100): two overflow detectors and at most two deterministic recoveries.
 *
 * A local `ConfigurationRejected` from the send-time budget and a provider `CONTEXT_OVERFLOW` verdict both mean the
 * same thing to this turn: the request that was assembled did not fit. Recovery is deterministic and NEVER an LLM
 * summary request:
 *
 * 1. re-trim with the historical tool rounds compressed away;
 * 2. hard-truncate to system + the latest turn + the current input;
 * 3. a third overflow returns `CONTEXT_OVERFLOW` instead of sending a fourth request.
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

    private fun screenTree(label: String): String = buildJsonObject {
        put("ok", true)
        put("tool", "device")
        put("action", "observe")
        put("data", buildJsonObject {
            put("screen", buildJsonObject {
                put("rev", 1)
                put("app", "com.example.app")
                put("text", "#n1 button $label")
            })
        })
    }.toString()

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

    private fun frame(): RequestFrame = RequestFrame(
        systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "sys")),
        conversationFlow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, true, "old-question", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "old-answer", SegmentRole.ASSISTANT),
            toolCall("c1"),
            toolOutput("c1", screenTree("old")),
            Segment(SegmentOrigin.TRANSCRIPT, true, "recent-question", SegmentRole.USER),
            Segment(SegmentOrigin.TRANSCRIPT, true, "(tool call)", SegmentRole.ASSISTANT),
            toolCall("c2"),
            toolOutput("c2", screenTree("current")),
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

    private enum class Fault { LOCAL_REJECT, PROVIDER_OVERFLOW, HTTP_FAILURE, CAPABILITY_MISSING, COMPLETED }

    private class ScriptedOverflowClient(private val faults: MutableList<Fault>) : ModelClient {
        val requests = mutableListOf<ModelRequest>()

        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            requests += request
            when (faults.removeFirstOrNull() ?: Fault.COMPLETED) {
                Fault.LOCAL_REJECT ->
                    throw ContractError.ConfigurationRejected("Explicit context exceeds safe budget or is malformed")

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
    fun `a second overflow hard truncates to the latest turn and the current input`() {
        val client = ScriptedOverflowClient(
            mutableListOf(Fault.LOCAL_REJECT, Fault.LOCAL_REJECT, Fault.COMPLETED),
        )
        val gateway = gateway(client)

        val reply = gateway.turn(turn())

        assertEquals(ModelEnd.COMPLETED, reply.terminal)
        assertEquals(3, client.requests.size)
        val hardened = client.requests[2]
        assertEquals("recent-question", texts(hardened).first(), "hard truncation starts at the latest user turn")
        assertTrue(texts(hardened).none { it == "old-question" }, "the earlier turn is gone: ${texts(hardened)}")
        assertEquals(listOf("c2"), callIds(hardened), "the latest tool round survives with the latest turn")
        assertEquals(listOf("now"), hardened.input.filterIsInstance<com.sheen.rokid.plugin.InputPart.Text>().map { it.text })
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
    fun `the pre-phase-5 history path still lets a local rejection escape unchanged`() {
        val client = ScriptedOverflowClient(mutableListOf(Fault.LOCAL_REJECT))
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
