package com.sheen.rokid.model

import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ResponsesDecoderTest {
    // Synthetic compatibility-schema examples, never captured supplier payloads.
    private fun frame(type: String, sequence: String = "1", fields: String = "") =
        SseFrame(type, """{"type":"$type","sequence_number":$sequence$fields}""")

    private fun delta(sequence: String = "1", text: String = "A", item: String = "i", index: Int = 0,
        response: String = ",\"response_id\":\"r\"") = frame("response.output_text.delta", sequence,
        """, "item_id":"$item","content_index":$index,"delta":"$text"$response""")

    private fun done(sequence: String = "2", text: String = "A", item: String = "i", index: Int = 0) =
        frame("response.output_text.done", sequence,
            """, "response_id":"r","item_id":"$item","content_index":$index,"text":"$text"""")

    private fun terminal(type: String = "completed", sequence: String = "100", response: String = """{"id":"r","status":"$type"}""") =
        frame("response.$type", sequence, """, "response":$response""")

    private fun ends(events: List<ModelStreamEvent>) = events.filterIsInstance<ModelStreamEvent.Terminal>()
    private fun texts(events: List<ModelStreamEvent>) = events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text }

    private fun decode(bytes: ByteArray, split: Int? = null): List<ModelStreamEvent> {
        val sse = SseDecoder()
        val frames = if (split == null) sse.feed(bytes) else sse.feed(bytes.copyOfRange(0, split)) +
            sse.feed(bytes.copyOfRange(split, bytes.size))
        val responses = ResponsesDecoder("q")
        return (frames + sse.finish()).flatMap(responses::accept) + responses.endOfInput()
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/responses/$name.sse")).readText()
        .replace("\\u63d0\\u793a", "\u63d0\u793a").replace("\\ud83d\\ude00", "\ud83d\ude00").toByteArray(Charsets.UTF_8)

    @Test fun `a failed response carrying a context length error is CONTEXT_OVERFLOW`() {
        // 0.25 Task 13 / R70: the Responses error object's machine code is the provider's overflow signal.
        val events = ResponsesDecoder("q").accept(
            terminal(
                "failed",
                response = """{"id":"r","status":"failed","error":{"code":"context_length_exceeded","message":"maximum context length exceeded"}}""",
            ),
        )
        val end = ends(events).single()
        assertEquals(ModelEnd.FAILED, end.end)
        assertEquals(com.sheen.rokid.agent.CONTEXT_OVERFLOW, end.safeCode)
    }

    @Test fun `a failed response without an overflow signal keeps PROVIDER_FAILED`() {
        val events = ResponsesDecoder("q").accept(terminal("failed"))
        val end = ends(events).single()
        assertEquals(ModelEnd.FAILED, end.end)
        assertEquals("PROVIDER_FAILED", end.safeCode)
    }

    @Test fun `done text is not appended twice`() {
        val decoder = ResponsesDecoder("q")
        val result = decoder.accept(delta(text = "\u63d0\u793a")) + decoder.accept(done(text = "\u63d0\u793a"))
        assertEquals(listOf("\u63d0\u793a"), texts(result))
    }

    @Test fun `created first nested identity supports text without response id at every byte split`() {
        val bytes = fixture("text-completed")
        // The fixture reports `input_tokens_details.cached_tokens = 0`: an explicit provider zero, which is a real
        // count, not the absence that stays null (0.3 design §5.3).
        val expected = listOf(ModelStreamEvent.TextDelta("q", "\u63d0\u793a"),
            ModelStreamEvent.TextDelta("q", "\ud83d\ude00"),
            ModelStreamEvent.Terminal("q", ModelEnd.COMPLETED, ModelUsage(12, 4, cachedInputTokens = 0), null))
        assertEquals(expected, decode(bytes))
        for (split in 0..bytes.size) assertEquals(expected, decode(bytes, split), "byte split $split")
        val sse = SseDecoder()
        val decoder = ResponsesDecoder("q")
        val actual = bytes.flatMap { sse.feed(byteArrayOf(it)) }.flatMap(decoder::accept) +
            sse.finish().flatMap(decoder::accept) + decoder.endOfInput()
        assertEquals(expected, actual)
    }

    @Test fun `SSE comments multiline data and all line endings dispatch only at blank line`() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val decoder = SseDecoder()
            val prefix = ":comment${newline}event: custom${newline}data: {${newline}data: \"a\":1}${newline}"
            assertTrue(decoder.feed(prefix.toByteArray()).isEmpty())
            assertEquals(listOf(SseFrame("custom", "{\n\"a\":1}")), decoder.feed(newline.toByteArray()))
            assertTrue(decoder.finish().isEmpty())
        }
    }

    @Test fun `unfinished SSE data at EOF cannot become completed`() {
        val decoder = SseDecoder()
        assertTrue(decoder.feed("event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":1}\n".toByteArray()).isEmpty())
        assertTrue(decoder.finish().isEmpty())
        assertEquals(ModelEnd.INCOMPLETE, ends(ResponsesDecoder("q").endOfInput()).single().end)
    }

    @Test fun `invalid UTF8 and oversized SSE frames throw fixed redacted errors`() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xff.toByte()))) {
            val error = assertFailsWith<SseDecodingException> { SseDecoder().feed(bytes) }
            assertEquals("INVALID_UTF8", error.safeCode)
            assertFalse(error.toString().contains("data:"))
        }
        val truncated = SseDecoder()
        truncated.feed(byteArrayOf(0xe4.toByte()))
        assertEquals("INVALID_UTF8", assertFailsWith<SseDecodingException> { truncated.finish() }.safeCode)
        assertEquals("FRAME_LIMIT", assertFailsWith<SseDecodingException> {
            SseDecoder(16).feed("data: synthetic secret too long\n\n".toByteArray())
        }.safeCode)
    }

    @Test fun `three terminals carry nested optional usage and failed messages stay redacted`() {
        val incomplete = decode(fixture("text-incomplete"))
        assertEquals(ModelEnd.INCOMPLETE, ends(incomplete).single().end)
        assertEquals(ModelUsage(null, 7), ends(incomplete).single().usage)
        val failed = decode(fixture("text-failed"))
        assertEquals(ModelEnd.FAILED, ends(failed).single().end)
        assertNull(ends(failed).single().usage)
        assertFalse(failed.toString().contains("private provider message"))
        val decoder = ResponsesDecoder("q")
        assertNull(ends(decoder.accept(terminal(response = """{"id":"r","usage":null}"""))).single().usage)
    }

    @Test fun `unknown reasoning and future events emit nothing while tool events surface`() {
        // Drift correction (spec §4.2 claimed the decoder already parsed `function_call`): it did not — the type fell
        // into `else -> emptyList()`. Reasoning stays silent; tool events now produce ToolCall*.
        val decoder = ResponsesDecoder("q")
        for ((i, type) in listOf("response.reasoning.delta", "future.event").withIndex()) {
            assertTrue(decoder.accept(frame(type, (i + 10).toString(), """, "delta":"private reasoning"""")).isEmpty())
        }
        val tool = decoder.accept(frame("response.function_call_arguments.delta", "12",
            """, "response_id":"r","output_index":0,"item_id":"fc_1","delta":"{\"title\":\"private" """))
        assertEquals(listOf("""{"title":"private"""), tool.filterIsInstance<ModelStreamEvent.ToolCallDelta>().map { it.argsFragment })
        assertTrue(tool.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
        assertEquals(ModelEnd.INCOMPLETE, ends(decoder.endOfInput()).single().end)
        assertTrue(decoder.endOfInput().isEmpty())
    }

    @Test fun `multiple items and content indices validate done separately`() {
        val decoder = ResponsesDecoder("q")
        val events = decoder.accept(delta("9", "A", "i", 0)) + decoder.accept(delta("13", "B", "j", 0)) +
            decoder.accept(delta("21", "C", "i", 1)) + decoder.accept(done("22", "B", "j", 0)) +
            decoder.accept(done("23", "A", "i", 0)) + decoder.accept(done("24", "C", "i", 1)) + decoder.accept(terminal())
        assertEquals(listOf("A", "B", "C"), texts(events))
        assertEquals(ModelEnd.COMPLETED, ends(events).single().end)
    }

    @Test fun `old and duplicate sequences are ignored including old terminal`() {
        val decoder = ResponsesDecoder("q")
        assertEquals(listOf("A"), texts(decoder.accept(delta("19"))))
        assertTrue(decoder.accept(delta("19", "duplicate")).isEmpty())
        assertTrue(decoder.accept(terminal(sequence = "18")).isEmpty())
        assertEquals(2L, decoder.ignoredSequenceCount)
        assertEquals(listOf("B"), texts(decoder.accept(delta("41", "B"))))
        assertEquals(ModelEnd.COMPLETED, ends(decoder.accept(terminal())).single().end)
    }

    @Test fun `malformed sequence values fail safely`() {
        for (sequence in listOf("null", "-1", "1.5", "1e2", "\"1\"", "9223372036854775808", "true")) {
            val decoder = ResponsesDecoder("q")
            assertEquals(ModelEnd.FAILED, ends(decoder.accept(delta(sequence))).single().end, sequence)
            assertTrue(decoder.endOfInput().isEmpty())
        }
        assertEquals(ModelEnd.FAILED, ends(ResponsesDecoder("q").accept(SseFrame(null,
            """{"type":"future.event"}"""))).single().end)
    }

    @Test fun `missing identity and explicit conflicts fail without guessing latest content`() {
        val bad = listOf(
            frame("response.output_text.delta", "2", """, "delta":"B","content_index":0"""),
            delta("2", response = ",\"response_id\":\"other\""),
            frame("response.output_text.delta", "2", """, "item_id":"i","delta":"B"""),
            frame("response.output_text.delta", "2", """, "item_id":"i","content_index":-1,"delta":"B"""))
        for (frame in bad) {
            val decoder = ResponsesDecoder("q")
            decoder.accept(delta())
            val events = decoder.accept(frame)
            assertEquals(ModelEnd.FAILED, ends(events).single().end)
            assertTrue(texts(events).isEmpty())
        }
        assertEquals(ModelEnd.FAILED, ends(ResponsesDecoder("q").accept(delta(response = ""))).single().end)
    }

    @Test fun `event and JSON type conflicts and malformed JSON terminate once`() {
        for (frame in listOf(SseFrame("response.completed", delta().data), SseFrame(null, "private invalid body"),
            SseFrame(null, "[]"), SseFrame(null, "[DONE]"))) {
            val decoder = ResponsesDecoder("q")
            val events = decoder.accept(frame)
            assertEquals(ModelEnd.FAILED, ends(events).single().end)
            assertFalse(events.toString().contains("private invalid body"))
            assertTrue(decoder.accept(terminal()).isEmpty())
            assertTrue(decoder.endOfInput().isEmpty())
        }
    }

    @Test fun `optional terminal identity status and usage contradictions fail safely`() {
        for (response in listOf("""{"id":"other"}""", """{"id":"r","status":"incomplete"}""",
            """{"id":"r","usage":{"input_tokens":-1}}""", """{"id":"r","usage":{"output_tokens":9223372036854775808}}""",
            """{"id":"r","usage":{"input_tokens":"12"}}""", """{"id":"r","usage":{"output_tokens":1.5}}""")) {
            val decoder = ResponsesDecoder("q")
            decoder.accept(delta())
            assertEquals(ModelEnd.FAILED, ends(decoder.accept(terminal(response = response))).single().end)
        }
        val boundary = ResponsesDecoder("q").accept(terminal(response =
            """{"id":"r","usage":{"input_tokens":9223372036854775807,"unknown":true}}"""))
        assertEquals(ModelUsage(Long.MAX_VALUE, null), ends(boundary).single().usage)
    }

    @Test fun `done full text never fabricates missing streamed content`() {
        for (initial in listOf(emptyList(), listOf(delta()), listOf(delta(text = "")))) {
            val decoder = ResponsesDecoder("q")
            initial.forEach(decoder::accept)
            val events = decoder.accept(done(text = "missing text"))
            assertTrue(texts(events).isEmpty())
            assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        }
    }

    @Test fun `matching repeated done stays silent and new delta after done is rejected`() {
        val decoder = ResponsesDecoder("q")
        decoder.accept(delta())
        assertTrue(decoder.accept(done()).isEmpty())
        assertTrue(decoder.accept(done("3")).isEmpty())
        assertEquals(ModelEnd.FAILED, ends(decoder.accept(delta("4"))).single().end)
    }

    @Test fun `terminal full output validates received delivery without replacement`() {
        for (streamed in listOf(false, true)) {
            val decoder = ResponsesDecoder("q")
            if (streamed) decoder.accept(delta())
            val events = decoder.accept(terminal(response = """{"id":"r","output":[{"id":"i","type":"message","content":[{"type":"output_text","text":"AB"}]}]}"""))
            assertTrue(texts(events).isEmpty())
            assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        }
    }

    @Test fun `terminal remains single and rejects late delta for all terminal kinds`() {
        for (type in listOf("completed", "incomplete", "failed")) {
            val decoder = ResponsesDecoder("q")
            assertEquals(1, ends(decoder.accept(terminal(type))).size)
            assertTrue(decoder.accept(delta("101")).isEmpty())
            assertTrue(decoder.accept(terminal(type, "102")).isEmpty())
            assertTrue(decoder.endOfInput().isEmpty())
        }
    }

    @Test fun `response size includes ignored events and fails before overflow`() {
        val decoder = ResponsesDecoder("q", 220)
        assertEquals(listOf("A"), texts(decoder.accept(delta())))
        val failure = decoder.accept(frame("future.event", "2", """, "ignored":"${"x".repeat(150)}"""))
        assertEquals(ModelEnd.FAILED, ends(failure).single().end)
        assertEquals("RESPONSE_LIMIT", ends(failure).single().safeCode)
        assertTrue(decoder.endOfInput().isEmpty())
    }

    @Test fun `encoder emits allowlisted fields with history and input`() {
        val request = requestFixture(input = listOf(InputPart.Text("\u63d0\u793a \"quoted\"\nline"))).copy(
            instructions = "fixed business constraints",
            history = listOf(ModelHistoryItem.Text(MessageRole.USER, "old question"), ModelHistoryItem.Text(MessageRole.ASSISTANT, "old answer")))
        val body = Json.parseToJsonElement(DeepSeekResponsesAdapter().encode(profileFixture(),
            ContextBudgeter().prepare(profileFixture(), request)).toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("model", "instructions", "stream", "max_output_tokens", "input"), body.keys)
        assertEquals("fixed business constraints", body.getValue("instructions").jsonPrimitive.content)
        assertEquals(true, body.getValue("stream").jsonPrimitive.boolean)
        assertEquals(256, body.getValue("max_output_tokens").jsonPrimitive.int)
        val messages = body.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(listOf("old question", "old answer", "\u63d0\u793a \"quoted\"\nline"),
            messages.map { it.getValue("content").jsonPrimitive.content })
        assertTrue(messages.all { it.keys == setOf("role", "content") })
    }

    @Test fun `adapter declares text and image input rejecting mismatched request and media`() {
        val adapter = DeepSeekResponsesAdapter()
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), adapter.support.inputs)
        assertEquals(setOf(OutputModality.TEXT), adapter.support.outputs)
        for (request in listOf(requestFixture(listOf(InputPart.Image("synthetic"))),
            requestFixture(listOf(InputPart.Audio("synthetic"))), requestFixture(output = OutputModality.AUDIO),
            requestFixture().copy(profileRevision = 2))) {
            assertFails { adapter.encode(profileFixture(), BudgetedRequest(request, 10, emptyList(), true)) }
        }
    }

    @Test fun `content part done detects unobserved full text and closes that content`() {
        val mismatch = ResponsesDecoder("q")
        mismatch.accept(delta())
        val events = mismatch.accept(frame("response.content_part.done", "2",
            """, "item_id":"i","content_index":0,"part":{"type":"output_text","text":"AB"}"""))
        assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        val matched = ResponsesDecoder("q")
        matched.accept(delta())
        assertTrue(matched.accept(frame("response.content_part.done", "2",
            """, "item_id":"i","content_index":0,"part":{"type":"output_text","text":"A"}""")).isEmpty())
        assertEquals(ModelEnd.FAILED, ends(matched.accept(delta("3"))).single().end)
    }

    @Test fun `item done detects missing full text without replaying`() {
        val decoder = ResponsesDecoder("q")
        decoder.accept(delta())
        val events = decoder.accept(frame("response.output_item.done", "2", """, "output_index":0,
            "item":{"id":"i","type":"message","content":[{"type":"output_text","text":"AB"}]}"""))
        assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        assertTrue(texts(events).isEmpty())
    }

    @Test fun `malformed nested fields cannot escape as supplier exceptions`() {
        val malformed = listOf(
            SseFrame(null, """{"type":{},"sequence_number":1}"""),
            frame("response.created", fields = """, "response":[]"""),
            frame("response.output_text.delta", fields = """, "response_id":"r","item_id":[],"content_index":0,"delta":"A"""),
            frame("response.output_text.delta", fields = """, "response_id":"r","item_id":"i","content_index":"0","delta":"A"""),
            frame("response.output_text.delta", fields = """, "response_id":"r","item_id":"i","content_index":0,"delta":{}"""),
            terminal(response = """{"id":"r","usage":[]}"""),
            terminal(response = """{"id":"r","output":{}}"""),
            terminal(response = """{"id":"r","status":true}"""))
        for (frame in malformed) {
            val decoder = ResponsesDecoder("q")
            assertEquals(ModelEnd.FAILED, ends(decoder.accept(frame)).single().end)
            assertTrue(decoder.endOfInput().isEmpty())
        }
    }

    @Test fun `text cannot impersonate known reasoning item or reuse output index`() {
        for (conflict in listOf(
            delta("3", item = "reasoning", response = ""),
            frame("response.output_text.delta", "3", """, "item_id":"other","output_index":0,"content_index":0,"delta":"A"""))) {
            val decoder = ResponsesDecoder("q")
            decoder.accept(frame("response.created", fields = """, "response":{"id":"r"}"""))
            decoder.accept(frame("response.output_item.added", "2",
                """, "output_index":0,"item":{"id":"reasoning","type":"reasoning"}"""))
            assertEquals(ModelEnd.FAILED, ends(decoder.accept(conflict)).single().end)
        }
    }

    @Test fun `SSE frame limit resets within large chunks and empty data dispatch differs from event only`() {
        val decoder = SseDecoder(10)
        assertEquals(List(100) { SseFrame(null, "A") }, decoder.feed("data:A\n\n".repeat(100).toByteArray()))
        assertEquals(listOf(SseFrame(null, "")), decoder.feed("data:\n\n".toByteArray()))
        assertTrue(SseDecoder().feed("event: response.completed\n\n".toByteArray()).isEmpty())
    }

    @Test fun `multibyte CRLF BOM frames remain invariant at every byte boundary`() {
        val bytes = ("\uFEFF:comment\r\nevent: test\r\ndata: \u63d0\u793a\ud83d\ude00\r\n\r\n").toByteArray()
        for (split in 0..bytes.size) {
            val decoder = SseDecoder()
            assertEquals(listOf(SseFrame("test", "\u63d0\u793a\ud83d\ude00")),
                decoder.feed(bytes.copyOfRange(0, split)) + decoder.feed(bytes.copyOfRange(split, bytes.size)) + decoder.finish())
        }
    }

    @Test fun `encoder rejects profile that does not declare text support`() {
        assertFails { DeepSeekResponsesAdapter().encode(profileFixture(inputs = setOf(InputModality.IMAGE)),
            BudgetedRequest(requestFixture(), 10, emptyList(), true)) }
        assertFails { DeepSeekResponsesAdapter().encode(profileFixture(outputs = setOf(OutputModality.AUDIO)),
            BudgetedRequest(requestFixture(), 10, emptyList(), true)) }
    }

    @Test fun `matching terminal output omits no already received item`() {
        val decoder = ResponsesDecoder("q")
        decoder.accept(delta())
        val events = decoder.accept(terminal(response = """{"id":"r","output":[],"usage":{"input_tokens":null,"output_tokens":null}}"""))
        assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
        assertEquals(ModelUsage(null, null), ends(events).single().usage)
    }

    @Test fun `SSE frame bound counts CRLF bytes before a blank line`() {
        assertEquals("FRAME_LIMIT", assertFailsWith<SseDecodingException> {
            SseDecoder(16).feed("data:A\r\ndata:B\r\n\r\n".toByteArray())
        }.safeCode)
    }

    @Test fun `item full content cannot omit text already marked done`() {
        val decoder = ResponsesDecoder("q")
        decoder.accept(delta())
        decoder.accept(done())
        val events = decoder.accept(frame("response.output_item.done", "3",
            """, "output_index":0,"item":{"id":"i","type":"message","content":[]}"""))
        assertEquals(ModelEnd.INCOMPLETE, ends(events).single().end)
    }

    @Test fun `JSON parser depth exhaustion cannot escape the semantic decoder`() {
        val decoder = ResponsesDecoder("q")
        val nested = "[".repeat(10000) + "0" + "]".repeat(10000)
        val events = decoder.accept(frame("future.event", fields = """, "unknown":$nested""")) + decoder.endOfInput()
        assertEquals(1, ends(events).size)
        assertTrue(ends(events).single().end != ModelEnd.COMPLETED)
    }

    @Test fun `late nonmessage item cannot relabel provisional text identity`() {
        // Deliberate correction: a `function_call` item is no longer "nonmessage/unknown" — it emits ToolCall*. The
        // identity conflict is unchanged: text already bound item "i" as a `message` at output_index 0, so a
        // function_call claiming that same slot is still rejected. `custom_tool_call` stays unknown.
        for (itemType in listOf("reasoning", "custom_tool_call")) {
            for (eventType in listOf("response.output_item.added", "response.output_item.done")) {
                val decoder = ResponsesDecoder("q")
                assertEquals(listOf("A"), texts(decoder.accept(delta())))
                val events = decoder.accept(frame(eventType, "2",
                    """, "output_index":0,"item":{"id":"i","type":"$itemType"}"""))
                assertEquals(ModelEnd.FAILED, ends(events).single().end, "$eventType/$itemType")
                assertEquals("IDENTITY_CONFLICT", ends(events).single().safeCode)
                assertTrue(decoder.accept(terminal()).isEmpty())
                assertTrue(decoder.accept(delta("101", "late")).isEmpty())
                assertTrue(decoder.endOfInput().isEmpty())
            }
        }
        for (eventType in listOf("response.output_item.added", "response.output_item.done")) {
            val decoder = ResponsesDecoder("q")
            assertEquals(listOf("A"), texts(decoder.accept(delta())))
            val events = decoder.accept(frame(eventType, "2",
                """, "output_index":0,"item":{"id":"i","type":"function_call","call_id":"call_1","name":"record_note","arguments":"{}"}"""))
            assertEquals(ModelEnd.FAILED, ends(events).single().end, "$eventType/function_call")
            assertEquals("IDENTITY_CONFLICT", ends(events).single().safeCode)
        }
    }

    @Test fun `late nontext part cannot relabel provisional content identity`() {
        for (partType in listOf("reasoning_text", "refusal", "future_part")) {
            for (eventType in listOf("response.content_part.added", "response.content_part.done")) {
                val decoder = ResponsesDecoder("q")
                decoder.accept(delta())
                val events = decoder.accept(frame(eventType, "2",
                    """, "item_id":"i","content_index":0,"part":{"type":"$partType"}"""))
                assertEquals(ModelEnd.FAILED, ends(events).single().end, "$eventType/$partType")
                assertEquals("IDENTITY_CONFLICT", ends(events).single().safeCode)
                assertTrue(decoder.accept(terminal()).isEmpty())
                assertTrue(decoder.endOfInput().isEmpty())
            }
        }
    }

    @Test fun `late matching item and part preserve delta first compatibility`() {
        val decoder = ResponsesDecoder("q")
        assertEquals(listOf("A"), texts(decoder.accept(delta())))
        assertTrue(decoder.accept(frame("response.output_item.added", "2",
            """, "output_index":0,"item":{"id":"i","type":"message","content":[]}""")).isEmpty())
        assertTrue(decoder.accept(frame("response.content_part.added", "3",
            """, "item_id":"i","content_index":0,"part":{"type":"output_text","text":""}""")).isEmpty())
        assertEquals(listOf("B"), texts(decoder.accept(delta("4", "B"))))
        assertTrue(decoder.accept(done("5", "AB")).isEmpty())
        assertEquals(ModelEnd.COMPLETED, ends(decoder.accept(terminal())).single().end)
        val isolated = ResponsesDecoder("q")
        assertEquals(listOf("A"), texts(isolated.accept(delta())))
        assertTrue(isolated.accept(done()).isEmpty())
        assertEquals(ModelEnd.COMPLETED, ends(isolated.accept(terminal())).single().end)
    }

    @Test fun `late part type is bound per content and does not taint another part`() {
        val decoder = ResponsesDecoder("q")
        decoder.accept(delta())
        assertTrue(decoder.accept(frame("response.content_part.added", "2",
            """, "item_id":"i","content_index":1,"part":{"type":"refusal"}""")).isEmpty())
        assertTrue(decoder.accept(done("3")).isEmpty())
        assertEquals(ModelEnd.COMPLETED, ends(decoder.accept(terminal())).single().end)
    }

    @Test fun `sequence zero origin is compatible while duplicate zero is ignored`() {
        val decoder = ResponsesDecoder("q")
        assertTrue(decoder.accept(frame("response.created", "0", """, "response":{"id":"r","status":"in_progress"}""")).isEmpty())
        assertTrue(decoder.accept(frame("response.created", "0", """, "response":{"id":"other"}""")).isEmpty())
        assertEquals(1L, decoder.ignoredSequenceCount)
        assertEquals(listOf("A"), texts(decoder.accept(delta("5", response = ""))))
        assertTrue(decoder.accept(done("8")).isEmpty())
        assertEquals(ModelEnd.COMPLETED, ends(decoder.accept(terminal(sequence = "19"))).single().end)
    }

    // ------------------------------------------------------------ 0.3 cache telemetry (design §5.3)

    @Test fun `usage exposes openai cached tokens from input token details`() {
        val events = ResponsesDecoder("q").accept(terminal(response =
            """{"id":"r","status":"completed","usage":{"input_tokens":100,"output_tokens":20,"input_tokens_details":{"cached_tokens":64}}}"""))
        assertEquals(ModelUsage(100, 20, cachedInputTokens = 64), ends(events).single().usage)
        assertNull(ends(events).single().usage?.cacheMissInputTokens)
    }

    @Test fun `usage exposes deepseek prompt cache hit and miss tokens`() {
        val events = ResponsesDecoder("q").accept(terminal(response =
            """{"id":"r","status":"completed","usage":{"input_tokens":100,"output_tokens":20,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}}"""))
        assertEquals(ModelUsage(100, 20, cachedInputTokens = 64, cacheMissInputTokens = 36), ends(events).single().usage)
    }

    @Test fun `usage leaves cache fields null when the provider omits them`() {
        // A missing count is absent, not zero: a run that never reported a cache hit must not read as a miss either.
        val events = ResponsesDecoder("q").accept(terminal(response =
            """{"id":"r","status":"completed","usage":{"input_tokens":100,"output_tokens":20}}"""))
        val usage = ends(events).single().usage
        assertEquals(100L, usage?.inputTokens)
        assertEquals(20L, usage?.outputTokens)
        assertNull(usage?.cachedInputTokens)
        assertNull(usage?.cacheMissInputTokens)
    }
}
