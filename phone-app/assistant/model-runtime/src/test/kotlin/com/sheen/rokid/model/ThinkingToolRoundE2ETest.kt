package com.sheen.rokid.model

import com.sheen.rokid.agent.ActionPolicy
import com.sheen.rokid.agent.AgentLoop
import com.sheen.rokid.agent.AgentOutcome
import com.sheen.rokid.agent.AgentRequest
import com.sheen.rokid.agent.AgentRun
import com.sheen.rokid.agent.AuditSink
import com.sheen.rokid.agent.ModelClientGateway
import com.sheen.rokid.agent.PolicyDecision
import com.sheen.rokid.agent.ProgressGuard
import com.sheen.rokid.agent.ToolDescriptor
import com.sheen.rokid.agent.ToolRegistry
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.ThinkingEffort
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.storage.MemorySecretResolver
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.53 Task 9: the two-round thinking tool loop, end to end over real HTTP.
 *
 * Everything on the production path is real here — `AgentLoop` → `ModelClientGateway` → `ControlledModelClient` →
 * OkHttp → `MockWebServer` — except the provider itself. Round 1 streams reasoning + a tool call; the loop executes a
 * local tool; round 2 must replay that reasoning (Chat: `reasoning_content` on the assistant message, Responses: a
 * `reasoning` input item immediately before it) and finish with the final answer. The non-DeepSeek control proves the
 * whole chain never sends or replays a DeepSeek field.
 */
class ThinkingToolRoundE2ETest {

    // ------------------------------------------------------------ round-1/round-2 SSE scripts

    private fun chatRoundOne(reasoning: String? = "need lookup") = sse(
        (reasoning?.let { "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$it\"},\"index\":0}]}\n\n" } ?: "") +
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"hangzhou\\\"}\"}}]},\"index\":0}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10," +
            "\"completion_tokens\":4}}\n\n" +
            "data: [DONE]\n\n",
    )

    private fun chatRoundTwo(reasoning: String? = "done thinking") = sse(
        (reasoning?.let { "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$it\"},\"index\":0}]}\n\n" } ?: "") +
            "data: {\"choices\":[{\"delta\":{\"content\":\"sunny 25C\"},\"index\":0}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20," +
            "\"completion_tokens\":3}}\n\n" +
            "data: [DONE]\n\n",
    )

    /** A second tool round of the same run, so the multi-round case exercises a second replayed reasoning. */
    private fun chatSecondToolRound(reasoning: String? = "second thought") = sse(
        (reasoning?.let { "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$it\"},\"index\":0}]}\n\n" } ?: "") +
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_2\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"beijing\\\"}\"}}]},\"index\":0}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":30," +
            "\"completion_tokens\":4}}\n\n" +
            "data: [DONE]\n\n",
    )

    private fun responsesRoundOne(reasoning: String? = "need lookup") = sse(
        "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"s\"}}\n\n" +
            (reasoning?.let {
                "data: {\"type\":\"response.reasoning_text.delta\",\"sequence_number\":1,\"response_id\":\"s\"," +
                    "\"item_id\":\"rs_1\",\"content_index\":0,\"delta\":\"$it\"}\n\n"
            } ?: "") +
            "data: {\"type\":\"response.output_item.added\",\"sequence_number\":2,\"output_index\":0," +
            "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\"," +
            "\"arguments\":\"\"}}\n\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":3,\"response_id\":\"s\"," +
            "\"output_index\":0,\"item_id\":\"fc_1\",\"delta\":\"{\\\"q\\\":\\\"hangzhou\\\"}\"}\n\n" +
            "data: {\"type\":\"response.output_item.done\",\"sequence_number\":4,\"output_index\":0," +
            "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\"," +
            "\"arguments\":\"{\\\"q\\\":\\\"hangzhou\\\"}\"}}\n\n" +
            "data: {\"type\":\"response.completed\",\"sequence_number\":5,\"response\":{\"id\":\"s\"," +
            "\"status\":\"completed\",\"output\":[]}}\n\n",
    )

    private fun responsesRoundTwo(reasoning: String? = "done thinking") = sse(
        "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"s\"}}\n\n" +
            (reasoning?.let {
                "data: {\"type\":\"response.reasoning_text.delta\",\"sequence_number\":1,\"response_id\":\"s\"," +
                    "\"item_id\":\"rs_2\",\"content_index\":0,\"delta\":\"$it\"}\n\n"
            } ?: "") +
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":2,\"item_id\":\"m_2\"," +
            "\"content_index\":0,\"delta\":\"sunny 25C\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"id\":\"s\"," +
            "\"status\":\"completed\"}}\n\n",
    )

    private fun sse(body: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setBody(body)

    // ------------------------------------------------------------ production-path rig

    /** The one tool this run offers; a pure local tool, so the loop executes it synchronously. */
    private class LookupTool : ActionExecutor {
        override fun descriptors() = listOf(
            ActionDescriptor(
                name = "lookup",
                title = "Lookup",
                description = "Look up the synthetic forecast for a city.",
                parametersJson = """{"type":"object","properties":{"q":{"type":"string"}}}""",
                risk = RiskLevel.L0,
                requiresConfirmation = false,
                idempotent = true,
                reversible = true,
                scope = "test",
            ),
        )

        override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, "sunny 25C")

        override fun cancel() = Unit
    }

    private object AllowEveryTool : ActionPolicy {
        override fun decide(descriptor: ToolDescriptor) = PolicyDecision.Allow
    }

    private object SilentAudit : AuditSink {
        override fun record(
            turnId: String,
            action: String,
            summary: String,
            outcome: String,
            scope: String?,
            risk: String?,
            decision: String?,
            confirmationSource: com.sheen.rokid.agent.ConfirmationSource?,
        ) = Unit
    }

    /** One request's worth of production wiring. `baseUrl` points at the MockWebServer, so the transport is real. */
    private inner class Rig(
        private val server: MockWebServer,
        protocolId: String,
        thinkingEnabled: Boolean,
        model: String,
    ) : AutoCloseable {
        val resolver = MemorySecretResolver()
        val repository = MemoryProfileRepository(resolver::revoke)
        val lines = CopyOnWriteArrayList<String>()
        private val clock = object : MonotonicClock { override fun nowNanos() = 0L }
        private val scheduler = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
                object : CancellationHandle { override fun cancel() {} }
        }
        val profile: ModelProfile
        private val client: ControlledModelClient

        init {
            val saved = repository.save(
                ModelProfile(
                    id = "p",
                    revision = 1,
                    name = "DeepSeek thinking",
                    service = "deepseek",
                    baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(),
                    protocolId = protocolId,
                    model = model,
                    secret = resolver.put("dummy-key".toCharArray()),
                    usableWindowTokens = 100_000,
                    inputBudgetTokens = 60_000,
                    maxOutputTokens = 6_000,
                    safetyTokens = 1_000,
                    timeoutMillis = 30_000,
                    inputs = setOf(InputModality.TEXT),
                    outputs = setOf(OutputModality.TEXT),
                    verifications = emptyList(),
                    thinkingEnabled = thinkingEnabled,
                    thinkingEffort = ThinkingEffort.LOW,
                ),
            )
            // The repository clears prior evidence on save and only records a verification whose revision matches, so
            // the probe evidence is written after the save — exactly what the production provider verification does.
            repository.verify(saved.id, verification(revision = saved.revision))
            profile = requireNotNull(repository.get(saved.id))
            client = ControlledModelClient(
                OkHttpClient(),
                repository,
                resolver,
                CapabilityGate(),
                ContextBudgeter(),
                clock,
                scheduler,
                { RequestConsent(text = true, image = false, audio = false, syntheticProbe = false) },
                Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC),
                allowLocalHttp = true,
            )
        }

        fun run(): AgentRun {
            val gateway = ModelClientGateway(
                client = client,
                profileLookup = { repository.get(profile.id) },
                clock = clock,
                scheduler = scheduler,
                envelopeFor = { requestId, start, end ->
                    EventEnvelope(SessionId("s"), Generation(1), requestId, start, end)
                },
                metricLine = { lines += it },
            )
            val loop = AgentLoop(
                gateway = gateway,
                registry = ToolRegistry(listOf(LookupTool())),
                policy = AllowEveryTool,
                guard = ProgressGuard(),
                audit = SilentAudit,
                clock = clock,
                metricLine = { lines += it },
            )
            return loop.run(
                AgentRequest(
                    turnId = "turn-1",
                    utterance = "查一下杭州天气",
                    instructions = "You are Edith.",
                    profileId = profile.id,
                    deadlineNanos = Long.MAX_VALUE,
                ),
            )
        }

        fun bodies(count: Int = 2): List<JsonObject> =
            (0 until count).map { json(assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()) }

        override fun close() {
            client.close()
            resolver.close()
        }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }

    private fun JsonObject.inputItems() = getValue("input").jsonArray.map { it.jsonObject }

    private fun JsonObject.roleOf() = this["role"]?.jsonPrimitive?.content

    private fun JsonObject.typeOf() = this["type"]?.jsonPrimitive?.content

    // ------------------------------------------------------------ tests

    @Test fun `a chat tool round replays the first reasoning and completes`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(chatRoundOne())
            server.enqueue(chatRoundTwo())
            Rig(server, "OPENAI_CHAT_COMPLETIONS", thinkingEnabled = true, model = "deepseek-flash")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertEquals("sunny 25C", run.finalText)
                    assertEquals("done thinking", run.finalReasoning, "the closing turn's reasoning reaches the run")
                    assertEquals(2, server.requestCount, "one tool round is exactly two model requests")
                    assertTrue(
                        rig.lines.any { it.startsWith("P3_THINKING active=true") },
                        "the count-only thinking line is emitted: ${rig.lines.filter { it.startsWith("P3_THINKING") }}",
                    )

                    val (first, second) = rig.bodies()
                    assertEquals("enabled", first.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals("low", first.getValue("reasoning_effort").jsonPrimitive.content)
                    // Owner ruling B: with thinking on the provider's official default applies, so no cap is sent.
                    assertFalse("max_tokens" in first, "an active thinking turn must not cap the output: $first")
                    assertFalse("max_tokens" in second, "every request of the active turn omits the cap: $second")
                    assertEquals("lookup", first.getValue("tools").jsonArray.single().jsonObject
                        .getValue("function").jsonObject.getValue("name").jsonPrimitive.content)

                    val assistant = second.messages().single {
                        it.roleOf() == "assistant" && "tool_calls" in it
                    }
                    assertEquals(
                        "need lookup",
                        assistant.getValue("reasoning_content").jsonPrimitive.content,
                        "the first turn's reasoning rides the assistant message that carried the call",
                    )
                    assertEquals(
                        "call_1",
                        assistant.getValue("tool_calls").jsonArray.single().jsonObject
                            .getValue("id").jsonPrimitive.content,
                    )
                    val toolMessage = second.messages().single { it.roleOf() == "tool" }
                    assertEquals("call_1", toolMessage.getValue("tool_call_id").jsonPrimitive.content)
                    // The model-facing tool result is the loop's structured envelope; its `data` is the tool's answer.
                    assertEquals(
                        "sunny 25C",
                        json(toolMessage.getValue("content").jsonPrimitive.content)
                            .getValue("data").jsonPrimitive.content,
                    )

                    // The first request must not have carried reasoning it never produced (there was no history).
                    assertTrue(first.messages().none { "reasoning_content" in it })
                }
        }
    }

    @Test fun `a responses tool round replays the reasoning item before its assistant message`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(responsesRoundOne())
            server.enqueue(responsesRoundTwo())
            Rig(server, "DEEPSEEK_RESPONSES", thinkingEnabled = true, model = "deepseek-flash")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertEquals("sunny 25C", run.finalText)
                    assertEquals("done thinking", run.finalReasoning)
                    assertEquals(2, server.requestCount)

                    val (first, second) = rig.bodies()
                    assertEquals("low", first.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
                    // Owner ruling B: an active thinking turn omits the output cap and the provider's 64K/128K applies.
                    assertFalse("max_output_tokens" in first, "an active thinking turn must not cap the output: $first")
                    assertFalse("max_output_tokens" in second, "every request of the active turn omits the cap: $second")

                    val input = second.inputItems()
                    val reasoningIndex = input.indexOfFirst { it.typeOf() == "reasoning" }
                    assertTrue(reasoningIndex >= 0, "the second request replays the reasoning item: $input")
                    val assistantIndex = input.indexOfFirst { it.roleOf() == "assistant" }
                    assertEquals(reasoningIndex + 1, assistantIndex, "the reasoning item sits immediately before its assistant message")
                    val content = input[reasoningIndex].getValue("content").jsonArray.single().jsonObject
                    assertEquals("reasoning_text", content.getValue("type").jsonPrimitive.content)
                    assertEquals("need lookup", content.getValue("text").jsonPrimitive.content)
                    assertEquals("call_1", input.single { it.typeOf() == "function_call" }
                        .getValue("call_id").jsonPrimitive.content)
                    assertEquals(
                        "sunny 25C",
                        json(input.single { it.typeOf() == "function_call_output" }
                            .getValue("output").jsonPrimitive.content)
                            .getValue("data").jsonPrimitive.content,
                    )
                }
        }
    }

    @Test fun `a thinking-off chat profile still captures server reasoning and replays it with the next tool request`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            // The switch is off and is now sent explicitly as disabled/none — but the server still streams
            // reasoning_content. It must be captured and replayed on the tool-bearing continuation, or the official
            // rule turns round 2 into an HTTP 400.
            server.enqueue(chatRoundOne())
            server.enqueue(chatRoundTwo())
            Rig(server, "OPENAI_CHAT_COMPLETIONS", thinkingEnabled = false, model = "deepseek-flash")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertEquals("sunny 25C", run.finalText)
                    assertEquals("done thinking", run.finalReasoning, "server-returned reasoning is captured even with the switch off")
                    assertEquals(2, server.requestCount)

                    val (first, second) = rig.bodies()
                    for ((path, body) in listOf("first" to first, "second" to second)) {
                        assertEquals(
                            "disabled",
                            body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                            "$path must send an explicit disabled with the switch off",
                        )
                        assertEquals(
                            "none",
                            body["reasoning_effort"]?.jsonPrimitive?.content,
                            "$path must send reasoning_effort=none with the switch off",
                        )
                        assertEquals(6_000, body.getValue("max_tokens").jsonPrimitive.int, "$path keeps the existing cap")
                    }
                    val assistant = second.messages().single {
                        it.roleOf() == "assistant" && "tool_calls" in it
                    }
                    assertEquals(
                        "need lookup",
                        assistant.getValue("reasoning_content").jsonPrimitive.content,
                        "the captured reasoning is replayed beside the assistant message that carried the call",
                    )
                    assertEquals(
                        listOf("need lookup"),
                        run.transcript.filterIsInstance<com.sheen.rokid.plugin.ModelHistoryItem.Text>()
                            .mapNotNull { it.reasoning },
                        "the loop stored the first turn's server-returned reasoning with its assistant turn",
                    )
                }
        }
    }

    @Test fun `a thinking-off responses profile sends no thinking field and no reasoning item in either round`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            // Nothing in this exchange is a thinking request or a thinking reply, so both bodies must be the plain
            // pre-0.5.3 shape — the non-DeepSeek regression control.
            server.enqueue(responsesRoundOne(reasoning = null))
            server.enqueue(responsesRoundTwo(reasoning = null))
            Rig(server, "DEEPSEEK_RESPONSES", thinkingEnabled = false, model = "gpt-4o-mini")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertEquals("sunny 25C", run.finalText)
                    assertNull(run.finalReasoning, "the server returned no reasoning and none was invented")
                    assertEquals(2, server.requestCount)

                    val (first, second) = rig.bodies()
                    for ((path, body) in listOf("first" to first, "second" to second)) {
                        assertFalse("reasoning" in body, "$path must send no reasoning field with the switch off: $body")
                        assertEquals(6_000, body.getValue("max_output_tokens").jsonPrimitive.int, "$path keeps the generic cap")
                    }
                    assertTrue(
                        second.inputItems().none { it.typeOf() == "reasoning" },
                        "no reasoning item may appear when nothing was captured: ${second.inputItems()}",
                    )
                    assertTrue(first.inputItems().none { it.typeOf() == "reasoning" }, "round one carries no reasoning item")
                }
        }
    }

    @Test fun `a thinking-off responses profile replays server-returned reasoning and sends an explicit off`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            // The switch was off and is now sent as `reasoning.effort=none`; the server still reasoned, so the reasoning
            // must be captured and replayed as an input item on the tool-bearing continuation, with the normal
            // `max_output_tokens`.
            server.enqueue(responsesRoundOne())
            server.enqueue(responsesRoundTwo())
            Rig(server, "DEEPSEEK_RESPONSES", thinkingEnabled = false, model = "deepseek-flash")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertEquals("sunny 25C", run.finalText)
                    assertEquals("done thinking", run.finalReasoning, "server-returned reasoning is captured with the switch off")
                    assertEquals(2, server.requestCount)

                    val (first, second) = rig.bodies()
                    for ((path, body) in listOf("first" to first, "second" to second)) {
                        assertEquals(
                            "none",
                            body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
                            "$path must send an explicit off with the switch off",
                        )
                        assertEquals(6_000, body.getValue("max_output_tokens").jsonPrimitive.int, "$path keeps the generic cap")
                    }
                    val input = second.inputItems()
                    val reasoningIndex = input.indexOfFirst { it.typeOf() == "reasoning" }
                    assertTrue(reasoningIndex >= 0, "the second request replays the captured reasoning: $input")
                    assertEquals(reasoningIndex + 1, input.indexOfFirst { it.roleOf() == "assistant" })
                    assertEquals(
                        "need lookup",
                        input[reasoningIndex].getValue("content").jsonArray.single().jsonObject
                            .getValue("text").jsonPrimitive.content,
                    )
                }
        }
    }

    @Test fun `a thinking-off chat profile with a silent server sends the explicit disable bodies`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            // Nothing was requested and nothing was returned: both bodies must be the generic OpenAI shape, with the
            // existing cap and no reasoning_content anywhere.
            server.enqueue(chatRoundOne(reasoning = null))
            server.enqueue(chatRoundTwo(reasoning = null))
            Rig(server, "OPENAI_CHAT_COMPLETIONS", thinkingEnabled = false, model = "deepseek-flash")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertNull(run.finalReasoning, "the server returned no reasoning and none was invented")
                    assertEquals(2, server.requestCount)

                    val (first, second) = rig.bodies()
                    val disabledKeys = setOf(
                        "model", "stream", "stream_options", "modalities", "max_tokens", "thinking", "reasoning_effort",
                        "tools", "tool_choice", "messages",
                    )
                    for ((path, body) in listOf("first" to first, "second" to second)) {
                        assertEquals(disabledKeys, body.keys, "$path must carry the explicit disable")
                        assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content, "$path")
                        assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content, "$path")
                        assertEquals(6_000, body.getValue("max_tokens").jsonPrimitive.int, "$path keeps the generic cap")
                        assertTrue(
                            body.messages().none { "reasoning_content" in it },
                            "$path carries no reasoning_content: ${body.messages()}",
                        )
                    }
                }
        }
    }

    @Test fun `two tool rounds replay every captured reasoning on the third request`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(chatRoundOne())
            server.enqueue(chatSecondToolRound())
            server.enqueue(chatRoundTwo("done thinking"))
            Rig(server, "OPENAI_CHAT_COMPLETIONS", thinkingEnabled = false, model = "deepseek-flash")
                .use { rig ->
                    val run = rig.run()
                    assertEquals(AgentOutcome.COMPLETED, run.outcome)
                    assertEquals("sunny 25C", run.finalText)
                    assertEquals("done thinking", run.finalReasoning)
                    assertEquals(3, server.requestCount, "two tool rounds are three model requests")

                    val (first, second, third) = rig.bodies(count = 3)
                    // Round 2 replays the first round's reasoning already.
                    assertEquals(
                        listOf("need lookup"),
                        second.messages().filter { "tool_calls" in it }.map {
                            it.getValue("reasoning_content").jsonPrimitive.content
                        },
                    )
                    // Round 3 must replay **both** earlier turns' reasoning, in order, on their own messages.
                    assertEquals(
                        listOf("need lookup", "second thought"),
                        third.messages().filter { "tool_calls" in it }.map {
                            it.getValue("reasoning_content").jsonPrimitive.content
                        },
                        "every captured reasoning rides its own assistant message on the third request",
                    )
                    assertTrue(first.messages().none { "reasoning_content" in it }, "round 1 has no reasoning history yet")
                }
        }
    }
}
