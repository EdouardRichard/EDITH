package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.3 design §5.3: the cache counts are read from the provider's own usage members — OpenAI's
 * `prompt_tokens_details.cached_tokens` and DeepSeek's `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` — and stay
 * absent when the provider reports none. A missing count is not a zero, and a miss count is never derived by
 * subtracting the hit count from the input count.
 *
 * 0.3 Phase 2 Task 11: OpenAI's `stream_options.include_usage` sends usage in a **trailing** usage-only chunk
 * (`choices: []`) after the `finish_reason` chunk, whose own `usage` is empty. A decoder that emits the terminal on the
 * finish chunk drops that chunk's usage entirely, so the finish is held until usage arrives, the stream ends, or a
 * chunk carries both. The terminal is emitted exactly once, and no count is ever invented.
 */
class ChatCompletionsDecoderTest {

    private fun finish(usage: String) = SseFrame(null,
        """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":$usage}""")

    private fun finishWithoutUsage() = SseFrame(null,
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")

    private fun usageChunk(usage: String) = SseFrame(null,
        """{"choices":[],"usage":$usage}""")

    private fun content(text: String) = SseFrame(null,
        """{"choices":[{"delta":{"content":"$text"}}]}""")

    private fun reasoning(text: String) = SseFrame(null,
        """{"choices":[{"delta":{"reasoning_content":"$text"},"index":0}]}""")

    private fun finishWith(reason: String) = SseFrame(null,
        """{"choices":[{"delta":{},"finish_reason":"$reason"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""")

    private fun done() = SseFrame(null, "[DONE]")

    private fun usageOf(usage: String): ModelUsage? {
        val terminal = ChatCompletionsDecoder("q").accept(finish(usage)).single()
        return (terminal as ModelStreamEvent.Terminal).usage
    }

    private fun terminal(events: List<ModelStreamEvent>): ModelStreamEvent.Terminal =
        events.single() as ModelStreamEvent.Terminal

    @Test fun `usage exposes openai cached tokens from prompt token details`() {
        val usage = usageOf("""{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64}}""")
        assertEquals(64L, usage?.cachedInputTokens)
        assertNull(usage?.cacheMissInputTokens, "the provider reported no miss count; it must not be derived")
    }

    @Test fun `usage exposes deepseek prompt cache hit and miss tokens`() {
        val usage = usageOf("""{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}""")
        assertEquals(64L, usage?.cachedInputTokens)
        assertEquals(36L, usage?.cacheMissInputTokens)
    }

    @Test fun `usage leaves cache fields null when the provider omits them`() {
        val usage = usageOf("""{"prompt_tokens":100,"completion_tokens":20}""")
        assertEquals(100L, usage?.inputTokens)
        assertEquals(20L, usage?.outputTokens)
        assertNull(usage?.cachedInputTokens)
        assertNull(usage?.cacheMissInputTokens)
    }

    @Test fun `a trailing usage-only chunk completes the pending finish with that usage`() {
        val decoder = ChatCompletionsDecoder("q")

        assertTrue(decoder.accept(finishWithoutUsage()).isEmpty(), "the finish must wait for the trailing usage")

        val terminal = terminal(decoder.accept(usageChunk(
            """{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64}}""",
        )))
        assertEquals(ModelEnd.COMPLETED, terminal.end)
        assertEquals(ModelUsage(100, 20, cachedInputTokens = 64), terminal.usage)
        // Exactly one terminal: the sentinel after it must not produce a second one.
        assertTrue(decoder.accept(done()).isEmpty())
        assertTrue(decoder.endOfInput().isEmpty())
    }

    @Test fun `a usage-only chunk before the finish is attached when the stream ends`() {
        val decoder = ChatCompletionsDecoder("q")

        assertTrue(decoder.accept(usageChunk(
            """{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}""",
        )).isEmpty(), "usage alone is not a verdict")

        assertTrue(decoder.accept(finishWithoutUsage()).isEmpty(), "the finish waits for the stream to end")

        val terminal = terminal(decoder.accept(done()))
        assertEquals(ModelEnd.COMPLETED, terminal.end)
        assertEquals(ModelUsage(100, 20, 64, 36), terminal.usage)
        assertNull(terminal.safeCode)
    }

    @Test fun `no finish reason falls back to endOfInput with the reported usage`() {
        val decoder = ChatCompletionsDecoder("q")

        val deltas = decoder.accept(content("好。"))
        assertEquals(ModelStreamEvent.TextDelta("q", "好。"), deltas.single())

        assertTrue(decoder.accept(usageChunk("""{"prompt_tokens":12,"completion_tokens":3}""")).isEmpty())

        val terminal = terminal(decoder.endOfInput())
        assertEquals(ModelEnd.COMPLETED, terminal.end)
        assertEquals(ModelUsage(12, 3), terminal.usage)
    }

    @Test fun `a finish chunk that carries usage still emits immediately`() {
        val decoder = ChatCompletionsDecoder("q")

        val terminal = terminal(decoder.accept(
            finish("""{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}"""),
        ))
        assertEquals(ModelEnd.COMPLETED, terminal.end)
        assertEquals(ModelUsage(100, 20, 64, 36), terminal.usage)

        // The old immediate behaviour is preserved: everything after the terminal is a late delivery and is dropped,
        // including a usage-only chunk and the sentinel.
        assertTrue(decoder.accept(usageChunk("""{"prompt_tokens":1,"completion_tokens":1}""")).isEmpty())
        assertTrue(decoder.accept(done()).isEmpty())
    }

    @Test fun `a pending finish with no usage chunk ends at EOF without fabricating counts`() {
        val decoder = ChatCompletionsDecoder("q")

        assertTrue(decoder.accept(finishWithoutUsage()).isEmpty())

        val terminal = terminal(decoder.endOfInput())
        assertEquals(ModelEnd.COMPLETED, terminal.end)
        assertNull(terminal.usage, "no usage was reported; the terminal must not invent one")
    }

    @Test fun `partial reports merge field by field without inventing a count`() {
        val decoder = ChatCompletionsDecoder("q")

        assertTrue(decoder.accept(usageChunk("""{"prompt_tokens":100}""")).isEmpty())
        assertTrue(decoder.accept(usageChunk(
            """{"completion_tokens":20,"prompt_cache_miss_tokens":36}""",
        )).isEmpty())
        assertTrue(decoder.accept(finishWithoutUsage()).isEmpty())

        val terminal = terminal(decoder.accept(done()))
        assertEquals(ModelUsage(100, 20, cacheMissInputTokens = 36), terminal.usage)
        assertNull(terminal.usage?.cachedInputTokens, "a counter never reported stays absent")
    }

    // ------------------------------------------------------------ 0.53 DeepSeek thinking (design §5.2)

    @Test fun `reasoning content streams before the answer without becoming text`() {
        // The chain of thought is its own event stream: it never becomes answer text and never marks the turn as
        // "text delivered" (so it cannot satisfy the retry gate or the content-based verdict).
        val decoder = ChatCompletionsDecoder("q")

        val first = decoder.accept(reasoning("先想"))
        assertEquals(listOf(ModelStreamEvent.ReasoningDelta("q", "先想")), first)

        val second = decoder.accept(content("好"))
        assertEquals(listOf(ModelStreamEvent.TextDelta("q", "好")), second)
    }

    @Test fun `an empty reasoning chunk emits nothing and a reasoning only turn is decided by its finish reason`() {
        val decoder = ChatCompletionsDecoder("q")

        assertTrue(decoder.accept(reasoning("")).isEmpty(), "an empty reasoning fragment is not an event")
        assertEquals(
            listOf(ModelStreamEvent.ReasoningDelta("q", "想")),
            decoder.accept(reasoning("想")),
            "a reasoning-only turn still surfaces its chain of thought",
        )
        val terminal = terminal(decoder.accept(finishWith("stop")))
        assertEquals(ModelEnd.COMPLETED, terminal.end, "the finish reason decides, not the absent answer text")
    }

    @Test fun `a reasoning only stream with no finish reason ends incomplete`() {
        val decoder = ChatCompletionsDecoder("q")

        decoder.accept(reasoning("想"))

        val terminal = terminal(decoder.accept(done()))
        assertEquals(ModelEnd.INCOMPLETE, terminal.end)
        assertEquals("EOF_INCOMPLETE", terminal.safeCode, "reasoning is not answer content, so it cannot complete a turn")
    }

    @Test fun `a tool call finish reason is a completed turn`() {
        // 0.53 §T10: a tool-call turn is a successful model turn. Before this fix `tool_calls` fell into the unknown
        // branch and failed the whole Chat protocol tool loop.
        val decoder = ChatCompletionsDecoder("q")

        decoder.accept(reasoning("想"))

        val terminal = terminal(decoder.accept(finishWith("tool_calls")))
        assertEquals(ModelEnd.COMPLETED, terminal.end)
        assertNull(terminal.safeCode)
    }

    @Test fun `the other finish reasons keep their existing verdicts`() {
        assertEquals(ModelEnd.COMPLETED, terminal(ChatCompletionsDecoder("q").accept(finishWith("stop"))).end)
        assertEquals(ModelEnd.INCOMPLETE, terminal(ChatCompletionsDecoder("q").accept(finishWith("length"))).end)
        for (reason in listOf("content_filter", "insufficient_system_resource", "aborted")) {
            val terminal = terminal(ChatCompletionsDecoder("q").accept(finishWith(reason)))
            assertEquals(ModelEnd.FAILED, terminal.end, reason)
            assertEquals("PROVIDER_FAILED", terminal.safeCode, reason)
        }
    }

    // ------------------------------------------------------------ 0.53 Chat tool calls (Task 9 prerequisite)

    @Test fun `streamed tool call fragments become one terminal call and stay replayable`() {
        // OpenAI/DeepSeek Chat streams a call in pieces: the first fragment carries id/name, later ones append
        // argument text. The loop only executes a call it received a terminal event for, so the decoder must assemble
        // the fragments. This was never implemented: 0.52 only used Chat for text, which is why Task 9's chat tool
        // round could not exist.
        val decoder = ChatCompletionsDecoder("q")
        val first = decoder.accept(SseFrame(null,
            """{"choices":[{"delta":{"reasoning_content":"查"},"index":0}]}"""))
        assertEquals(listOf(ModelStreamEvent.ReasoningDelta("q", "查")), first)

        val opening = decoder.accept(SseFrame(null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"ci"}}]},"index":0}]}"""))
        assertEquals(
            listOf(ModelStreamEvent.ToolCallDelta("q", "chat:0", "lookup", "{\"ci")),
            opening,
            "the fragment is surfaced as a delta under a stable per-call identity",
        )

        val finishing = decoder.accept(SseFrame(null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"Hangzhou\"}"}}]},"index":0},{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""))
        val call = finishing.filterIsInstance<ModelStreamEvent.ToolCallTerminal>().single()
        assertEquals("call_1", call.callId)
        assertEquals("lookup", call.name)
        assertEquals("""{"city":"Hangzhou"}""", call.argsJson, "argument fragments are assembled in order")
        assertEquals("chat:0", call.itemId, "the delta and terminal share one item identity")
        assertEquals(ModelEnd.COMPLETED, finishing.filterIsInstance<ModelStreamEvent.Terminal>().single().end)
    }

    @Test fun `a tool call without a finish reason still completes at the stream end`() {
        val decoder = ChatCompletionsDecoder("q")
        decoder.accept(SseFrame(null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"lookup","arguments":"{}"}}]}}]}"""))
        val events = decoder.accept(done())
        assertEquals("call_9", events.filterIsInstance<ModelStreamEvent.ToolCallTerminal>().single().callId)
        assertEquals(ModelEnd.COMPLETED, events.filterIsInstance<ModelStreamEvent.Terminal>().single().end)
    }

    @Test fun `a truncated tool call surfaces a dangling delta and never a terminal call`() {
        // A stream that dies mid-call must not look like a successful empty turn: the delta without a terminal item is
        // what `ModelClientGateway` reads as TOOL_CALL_DELIVERY_INCOMPLETE.
        val decoder = ChatCompletionsDecoder("q")
        decoder.accept(SseFrame(null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{\"ci"}}]}}]}"""))
        val events = decoder.endOfInput()
        assertTrue(events.filterIsInstance<ModelStreamEvent.ToolCallTerminal>().isEmpty(), "no terminal call from a partial one")
        assertEquals(ModelEnd.INCOMPLETE, events.filterIsInstance<ModelStreamEvent.Terminal>().single().end)
    }

    // ------------------------------------------------------------ review round 1 fixes

    @Test fun `a whitespace-only reasoning fragment is ignored and the turn survives`() {
        // `ReasoningDelta` requires a non-blank text. Treating a whitespace chunk as an event made its constructor throw
        // inside the decoder, which the outer catch turned into FAILED/MALFORMED_JSON — a valid turn lost.
        val decoder = ChatCompletionsDecoder("q")

        assertTrue(decoder.accept(reasoning(" ")).isEmpty(), "a space is not a reasoning fragment")
        assertTrue(decoder.accept(reasoning("\\n")).isEmpty(), "a newline is not a reasoning fragment")
        assertEquals(listOf(ModelStreamEvent.ReasoningDelta("q", "想")), decoder.accept(reasoning("想")))
        assertEquals(listOf(ModelStreamEvent.TextDelta("q", "好")), decoder.accept(content("好")))
        assertEquals(ModelEnd.COMPLETED, terminal(decoder.accept(finishWith("stop"))).end)
    }

    @Test fun `a length-truncated tool call stays dangling and never becomes callable`() {
        // `finish_reason="length"` means the provider cut the call off, not that it completed it. Emitting a terminal
        // would hand the loop a call whose arguments the provider never finished.
        val decoder = ChatCompletionsDecoder("q")
        decoder.accept(SseFrame(null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{\"q\""}}]}}]}"""))

        val events = decoder.accept(finishWith("length"))
        assertTrue(events.filterIsInstance<ModelStreamEvent.ToolCallTerminal>().isEmpty(), "a length-cut call is not callable")
        assertEquals(ModelEnd.INCOMPLETE, events.filterIsInstance<ModelStreamEvent.Terminal>().single().end)
    }

    @Test fun `a tool call missing its identity refuses the turn instead of completing silently`() {
        // The id and name are what pair a call with its tool output. A call missing either must never read as success:
        // a completed turn with text would otherwise let the gateway's dangling-delta check miss it entirely.
        for ((label, fragment) in listOf(
            "missing id" to """{"index":0,"function":{"name":"lookup","arguments":"{}"}}""",
            "missing name" to """{"index":0,"id":"call_1","function":{"arguments":"{}"}}""",
        )) {
            val decoder = ChatCompletionsDecoder("q")
            decoder.accept(content("partial answer"))
            decoder.accept(SseFrame(null, """{"choices":[{"delta":{"tool_calls":[$fragment]},"index":0}]}"""))
            val events = decoder.accept(finishWith("tool_calls"))
            assertTrue(events.filterIsInstance<ModelStreamEvent.ToolCallTerminal>().isEmpty(), label)
            val terminal = events.filterIsInstance<ModelStreamEvent.Terminal>().single()
            assertEquals(ModelEnd.INCOMPLETE, terminal.end, label)
            assertEquals("TOOL_CALL_DELIVERY_INCOMPLETE", terminal.safeCode, label)
        }
    }
}
