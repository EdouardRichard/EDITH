package com.sheen.rokid.model

import com.sheen.rokid.agent.ToolResult
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * C07 extension: a `function_call` is a first-class stream outcome. The decoder must surface it and must never swallow
 * a call, because "no tool call" and "the decoder dropped the tool call" are indistinguishable to the upper layer.
 */
class ToolCallDecodingTest {
    private fun frame(type: String, sequence: String, fields: String = "") =
        SseFrame(type, """{"type":"$type","sequence_number":$sequence$fields}""")

    private fun decode(bytes: ByteArray, split: Int? = null): List<ModelStreamEvent> {
        val sse = SseDecoder()
        val frames = if (split == null) sse.feed(bytes) else sse.feed(bytes.copyOfRange(0, split)) +
            sse.feed(bytes.copyOfRange(split, bytes.size))
        val responses = ResponsesDecoder("q")
        return (frames + sse.finish()).flatMap(responses::accept) + responses.endOfInput()
    }

    /** The fixture carries `\u` escapes, matching the other three synthetic fixtures. */
    private fun fixtureBytes() = checkNotNull(javaClass.getResource("/responses/tool-call-completed.sse")).readText()
        .toByteArray(Charsets.UTF_8)

    /**
     * Rebuilds the fixture without one SSE frame, so a truncated stream can be replayed from the honest bytes.
     *
     * The line endings are normalised first. A Windows checkout of the `.sse` fixture carries CRLF, and splitting on
     * `"\n\n"` then finds no separator at all: the whole document contains the removed event's name, so the filter
     * deleted every frame and the test measured `EOF_INCOMPLETE` instead of the incomplete tool delivery it is about.
     * The criterion is unchanged - only the fixture's transport line endings are made irrelevant.
     */
    private fun fixtureWithout(event: String) = fixtureBytes().toString(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .split("\n\n")
        .filterNot { it.contains("event: $event") }
        .joinToString("\n\n")
        .toByteArray(Charsets.UTF_8)

    private fun terminals(events: List<ModelStreamEvent>) = events.filterIsInstance<ModelStreamEvent.ToolCallTerminal>()
    private fun deltas(events: List<ModelStreamEvent>) = events.filterIsInstance<ModelStreamEvent.ToolCallDelta>()
    private fun ends(events: List<ModelStreamEvent>) = events.filterIsInstance<ModelStreamEvent.Terminal>()

    @Test fun `function call fixture yields one terminal tool call and no text delta`() {
        val events = decode(fixtureBytes())
        // The observed item id (`fc_1`) travels beside the call id (`call_1`): the history echo needs the id the model
        // produced, not the defaulted call id.
        assertEquals(
            listOf(ModelStreamEvent.ToolCallTerminal("q", "fc_1", "call_1", "record_note", """{"title":"回电话"}""")),
            terminals(events),
        )
        assertEquals("fc_1", terminals(events).single().itemId)
        assertEquals("call_1", terminals(events).single().callId)
        assertTrue(events.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
        assertEquals(ModelEnd.COMPLETED, ends(events).single().end)
        assertNull(ends(events).single().safeCode)
        assertEquals(ModelUsage(11, 7), ends(events).single().usage)
    }

    @Test fun `streamed tool call without its item done frame cannot complete`() {
        // The call was observed streaming (`output_item.added` + arguments delta) but its `output_item.done` never
        // arrived. Accepting `response.completed` here would hand the upper layer a successful empty turn — exactly the
        // "must never swallow a call" failure. The text path already refuses the same shape with
        // `TEXT_DELIVERY_INCOMPLETE`; the tool path mirrors it.
        val events = decode(fixtureWithout("response.output_item.done"))
        assertTrue(terminals(events).isEmpty(), "events=$events")
        assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        assertEquals("TOOL_CALL_DELIVERY_INCOMPLETE", ends(events).single().safeCode)
        assertEquals(ModelUsage(11, 7), ends(events).single().usage)
    }

    @Test fun `tool call completes identically at every byte split`() {
        val bytes = fixtureBytes()
        val expected = decode(bytes)
        assertEquals(1, terminals(expected).size)
        for (split in 0..bytes.size) assertEquals(expected, decode(bytes, split), "byte split $split")
    }

    @Test fun `arguments delta produces a tool call delta in order and never text`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(
            frame(
                "response.function_call_arguments.delta", "9",
                """, "response_id":"r","output_index":0,"item_id":"fc_1","delta":"{\"title\":\"回" """,
            ),
        )
        assertEquals(listOf(ModelStreamEvent.ToolCallDelta("q", "fc_1", null, """{"title":"回""")), deltas(events))
        assertTrue(events.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
    }

    @Test fun `arguments delta after the item was added carries the observed tool name`() {
        // The endpoint names the call on the item, not on every fragment, so the decoder remembers it and passes it
        // through once it has been observed. The pre-item case above asserts the null-name path.
        val decoder = ResponsesDecoder("q")
        decoder.accept(
            frame(
                "response.output_item.added", "3",
                """, "response_id":"r","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note"}""",
            ),
        )
        val events = decoder.accept(
            frame(
                "response.function_call_arguments.delta", "4",
                """, "response_id":"r","output_index":0,"item_id":"fc_1","delta":"{\"title\":\"call back\"}" """,
            ),
        )
        assertEquals(
            listOf(ModelStreamEvent.ToolCallDelta("q", "fc_1", "record_note", """{"title":"call back"}""")),
            deltas(events),
        )
    }

    @Test fun `function call item without a call id fails with its named safe code`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(
            frame(
                "response.output_item.done", "5",
                """, "output_index":0,"item":{"id":"fc_1","type":"function_call","name":"record_note","arguments":"{}"}""",
            ),
        )
        assertTrue(terminals(events).isEmpty())
        assertEquals(ModelEnd.FAILED, ends(events).single().end)
        assertEquals("MISSING_CALL_ID", ends(events).single().safeCode)
    }

    @Test fun `function call item without a name fails with its named safe code`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(
            frame(
                "response.output_item.done", "5",
                """, "output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","arguments":"{}"}""",
            ),
        )
        assertTrue(terminals(events).isEmpty())
        assertEquals(ModelEnd.FAILED, ends(events).single().end)
        assertEquals("MISSING_TOOL_NAME", ends(events).single().safeCode)
    }

    @Test fun `arguments delta after the call was done is rejected`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(
            frame(
                "response.output_item.done", "5",
                """, "output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note","arguments":"{}"}""",
            ),
        ) + decoder.accept(
            frame(
                "response.function_call_arguments.delta", "6",
                """, "output_index":0,"item_id":"fc_1","delta":"{}" """,
            ),
        )
        assertEquals(1, terminals(events).size, "the completed call still surfaces; events=$events")
        assertEquals(ModelEnd.FAILED, ends(events).single().end)
        assertEquals("DELTA_AFTER_DONE", ends(events).single().safeCode)
    }

    @Test fun `non json arguments still produce a terminal tool call and never swallow the call`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(
            frame(
                "response.output_item.done", "5",
                """, "output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note","arguments":"not json{"}""",
            ),
        ) + decoder.accept(
            frame(
                "response.completed", "6",
                """, "response":{"id":"r","status":"completed","output":[{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note","arguments":"not json{"}]}""",
            ),
        )
        assertEquals(1, terminals(events).size, "events=$events")
        assertEquals(
            listOf(ModelStreamEvent.ToolCallTerminal("q", "fc_1", "call_1", "record_note", "not json{")),
            terminals(events),
        )
        assertEquals(ModelEnd.COMPLETED, ends(events).single().end)
    }

    @Test fun `streamed arguments that disagree with the final item fail instead of guessing`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(
            frame(
                "response.function_call_arguments.delta", "4",
                """, "response_id":"r","output_index":0,"item_id":"fc_1","delta":"{\"title\":\"a\"}" """,
            ),
        ) + decoder.accept(
            frame(
                "response.output_item.done", "5",
                """, "output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"record_note","arguments":"{\"title\":\"b\"}"}""",
            ),
        )
        assertTrue(terminals(events).isEmpty())
        assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        assertEquals("TOOL_CALL_DELIVERY_INCOMPLETE", ends(events).single().safeCode)
    }

    @Test fun `empty output array with no streamed text still completes`() {
        // The existing text check is vacuously true for a text-free turn, and that is correct: there is no text the
        // terminal could have contradicted. A tool-only response completes exactly like a text response does.
        val events = decode(
            """event: response.completed
data: {"type":"response.completed","sequence_number":1,"response":{"id":"r","status":"completed","output":[],"usage":{"input_tokens":1,"output_tokens":0}}}

""".toByteArray(Charsets.UTF_8),
        )
        assertEquals(ModelEnd.COMPLETED, ends(events).single().end)
        assertTrue(events.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
    }

    @Test fun `id less non message output item does not fail the terminal`() {
        // P2 tolerance: only `message` items carry text to validate, so a non-message item is skipped before any field
        // is required of it. Requiring `id` on every item type flipped this to FAILED/MISSING_FIELD, which P2 did not.
        val events = decode(
            """event: response.completed
data: {"type":"response.completed","sequence_number":1,"response":{"id":"r","status":"completed","output":[{"type":"reasoning"}],"usage":{"input_tokens":2,"output_tokens":0}}}

""".toByteArray(Charsets.UTF_8),
        )
        assertEquals(ModelEnd.COMPLETED, ends(events).single().end)
        assertNull(ends(events).single().safeCode)
    }

    @Test fun `tool request encodes the proven flattened descriptor and disables thinking`() {
        val request = requestFixture().copy(tools = toolSpecs())
        val body = encodedBody(request)
        assertEquals(setOf("model", "instructions", "stream", "max_output_tokens", "tools", "tool_choice", "reasoning", "input"), body.keys)
        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals(
            setOf("type", "name", "description", "parameters"),
            tool.keys,
            "the endpoint rejects the nested OpenAI `function` wrapper; T4 proved the flattened shape",
        )
        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertEquals("record_note", tool.getValue("name").jsonPrimitive.content)
        assertEquals("Record a short note for the user.", tool.getValue("description").jsonPrimitive.content)
        assertEquals(
            setOf("type", "properties", "required"),
            tool.getValue("parameters").jsonObject.keys,
            "the descriptor's parameters document is passed through unchanged, exactly as the probe built it",
        )
        assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content)
        assertEquals("none", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test fun `text only request keeps P2 key set without tool keys`() {
        val body = encodedBody(requestFixture())
        assertEquals(setOf("model", "instructions", "stream", "max_output_tokens", "input"), body.keys)
    }

    @Test fun `tool history encodes echoed function call then its output`() {
        val body = encodedBody(
            requestFixture().copy(
                history = listOf(
                    ModelHistoryItem.Text(MessageRole.USER, "remember this"),
                    ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
                    ModelHistoryItem.ToolCall("call_1", "record_note", """{"title":"回电话"}""", "fc_1"),
                    ModelHistoryItem.ToolOutput("call_1", "noted"),
                ),
            ),
        )
        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        // Two history texts, the echoed call, its output, and the fixture's own input part.
        assertEquals(5, input.size, "input=$input")
        assertEquals(setOf("role", "content"), input[0].keys)
        assertEquals("user", input[0].getValue("role").jsonPrimitive.content)
        assertEquals("assistant", input[1].getValue("role").jsonPrimitive.content)
        // Echoed verbatim, exactly as the model produced it: the stateless endpoint expects the call it made to be
        // present beside its result. Field set mirrors p3-agent-probe `appendToolExchange`.
        assertEquals(
            setOf("type", "id", "call_id", "name", "arguments"),
            input[2].keys,
            "the proven echo carries the item id, the call id, the name and the arguments string",
        )
        assertEquals("function_call", input[2].getValue("type").jsonPrimitive.content)
        assertEquals("fc_1", input[2].getValue("id").jsonPrimitive.content)
        assertEquals("call_1", input[2].getValue("call_id").jsonPrimitive.content)
        assertEquals("record_note", input[2].getValue("name").jsonPrimitive.content)
        assertEquals("""{"title":"回电话"}""", input[2].getValue("arguments").jsonPrimitive.content)
        assertEquals(setOf("type", "call_id", "output"), input[3].keys)
        assertEquals("function_call_output", input[3].getValue("type").jsonPrimitive.content)
        assertEquals("call_1", input[3].getValue("call_id").jsonPrimitive.content)
        assertEquals("noted", input[3].getValue("output").jsonPrimitive.content)
        assertEquals(setOf("role", "content"), input[4].keys)
    }

    @Test fun `a tool result envelope reaches the function call output unchanged`() {
        // 0.3 Phase 2.2 Task 7: `ToolResult.payload` is already the model-facing envelope; the Responses adapter forwards
        // it as the `function_call_output.output` string and never rewrites it.
        val envelope = ToolResult(
            id = "call_1",
            ok = true,
            execution = ToolExecution(outcome = ToolOutcome.Done, accepted = true),
            observation = null,
            data = "loaded:1",
        ).payload
        val body = encodedBody(
            requestFixture().copy(
                history = listOf(
                    ModelHistoryItem.Text(MessageRole.USER, "pull the skill"),
                    ModelHistoryItem.Text(MessageRole.ASSISTANT, "pulling"),
                    ModelHistoryItem.ToolCall("call_1", "load_skill", """{"name":"alpha"}""", "fc_1"),
                    ModelHistoryItem.ToolOutput("call_1", envelope),
                ),
            ),
        )

        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        val output = input[3]
        assertEquals("function_call_output", output.getValue("type").jsonPrimitive.content)
        assertEquals("call_1", output.getValue("call_id").jsonPrimitive.content)
        assertEquals(envelope, output.getValue("output").jsonPrimitive.content, "the payload is forwarded byte for byte")
        assertTrue("\"observation\":null" in envelope, "no observation is invented: $envelope")
        assertTrue("\"accepted\":true" in envelope, "the execution facts survive, as on the chat path: $envelope")
        assertFalse("Failed: " in envelope, "no legacy prefix: $envelope")
        assertFalse("Refused: " in envelope, "no legacy prefix: $envelope")
    }

    @Test fun `a continuation request with no input keeps the tool output last on the wire`() {
        // The post-tool round the agent loop hands over: the transcript already carries the user utterance, so the
        // request has no `input` of its own (a history-only request, legal since the 9a fix). The body must render the
        // history in order and end on the echoed `function_call_output`, with no re-asserted user message after it.
        val body = encodedBody(
            requestFixture(
                input = emptyList(),
                history = listOf(
                    ModelHistoryItem.Text(MessageRole.USER, "remember this"),
                    ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
                    ModelHistoryItem.ToolCall("call_1", "record_note", "{}", "fc_1"),
                    ModelHistoryItem.ToolOutput("call_1", "noted"),
                ),
            ),
        )
        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(4, input.size, "input=$input")
        assertEquals("user", input[0].getValue("role").jsonPrimitive.content)
        assertEquals("function_call_output", input[3].getValue("type").jsonPrimitive.content)
        assertEquals("noted", input[3].getValue("output").jsonPrimitive.content)
        assertEquals(
            1,
            input.count { it["role"]?.jsonPrimitive?.contentOrNull == "user" },
            "the utterance stays in history once and is never re-appended after the tool output",
        )
    }

    @Test fun `tool call history without an echoed item id still encodes the identity`() {
        val body = encodedBody(
            requestFixture().copy(
                history = listOf(
                    ModelHistoryItem.Text(MessageRole.USER, "remember this"),
                    ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
                    ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
                ),
            ),
        )
        val item = body.getValue("input").jsonArray.map { it.jsonObject }[2]
        assertEquals("call_1", item.getValue("id").jsonPrimitive.content)
        assertEquals("call_1", item.getValue("call_id").jsonPrimitive.content)
    }

    @Test fun `tool parameters that are not json are rejected before encoding`() {
        val adapter = DeepSeekResponsesAdapter()
        val profile = profileFixture()
        val request = requestFixture().copy(
            tools = listOf(ToolSpec("record_note", "Record a note.", "not json")),
        )
        assertFailsWith<IllegalArgumentException> {
            adapter.encode(profile, ContextBudgeter().prepare(profile, request))
        }
    }

    private fun toolSpecs() = listOf(
        ToolSpec(
            "record_note",
            "Record a short note for the user.",
            """{"type":"object","properties":{"title":{"type":"string","description":"Short title"}},"required":["title"]}""",
        ),
    )

    private fun encodedBody(request: ModelRequest): JsonObject =
        Json.parseToJsonElement(
            DeepSeekResponsesAdapter().encode(
                profileFixture(inputs = setOf(InputModality.TEXT, InputModality.AUDIO)),
                ContextBudgeter().prepare(profileFixture(inputs = setOf(InputModality.TEXT, InputModality.AUDIO)), request),
            ).toString(Charsets.UTF_8),
        ).jsonObject
}
