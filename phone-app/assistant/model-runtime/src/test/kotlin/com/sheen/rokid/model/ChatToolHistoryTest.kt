package com.sheen.rokid.model

import com.sheen.rokid.agent.ToolResult
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 spec §4.10: the Chat Completions encoder must carry a tool round-trip back to the stateless endpoint — the
 * assistant's `tool_calls`, one `role:"tool"` message per output, and the current user turn last.
 *
 * These tests drive the production `encode(profile, budgeted)` overload (the live path through `ControlledModelClient`),
 * not the legacy frame overload.
 */
class ChatToolHistoryTest {
    private val media = object : MediaSource {
        override fun resolveAudio(ref: String): ResolvedAudio? = null
    }
    private val adapter = OpenAiChatCompletionsAdapter(media)
    private val profile = ModelProfile(
        "p1", 1, "Chat Test", "service", "https://example.test",
        "OPENAI_CHAT_COMPLETIONS", "deepseek-flash",
        object : SecretHandle { override val id = "test" },
        65_536, 60_000, 1024, 128, 10_000,
        setOf(InputModality.TEXT, InputModality.IMAGE), setOf(OutputModality.TEXT),
        emptyList(),
    )

    private fun encode(
        history: List<ModelHistoryItem>,
        input: List<InputPart> = listOf(InputPart.Text("continue")),
    ): JsonArray {
        val request = ModelRequest(
            EventEnvelope(SessionId("s"), Generation(1), "e", 0, 1),
            "q", "t", "p1", 1, "instructions", input, history, OutputModality.TEXT, 10_000,
        )
        val budgeted = BudgetedRequest(request, 0, emptyList(), true)
        val body = Json.parseToJsonElement(String(adapter.encode(profile, budgeted), Charsets.UTF_8)).jsonObject
        return body.getValue("messages").jsonArray
    }

    private fun JsonObject.role(): String = getValue("role").jsonPrimitive.content

    @Test
    fun `consecutive tool calls become one assistant message that keeps the assistant text`() {
        val messages = encode(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "weather and time?"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Checking."),
                ModelHistoryItem.ToolCall("call_a", "get_weather", """{"city":"Beijing"}"""),
                ModelHistoryItem.ToolCall("call_b", "get_time", """{"zone":"CST"}"""),
                ModelHistoryItem.ToolOutput("call_a", "sunny 25C"),
                ModelHistoryItem.ToolOutput("call_b", "10:00"),
            ),
        )

        assertEquals(5, messages.size, "user, assistant(tool_calls=[a,b]), tool a, tool b, current user: $messages")

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant.role())
        assertEquals("Checking.", assistant.getValue("content").jsonPrimitive.content)
        val calls = assistant.getValue("tool_calls").jsonArray
        assertEquals(
            listOf("call_a", "call_b"),
            calls.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            "the two consecutive calls share one assistant message, in call order",
        )
        assertEquals("get_weather", calls[0].jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(
            """{"city":"Beijing"}""",
            calls[0].jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content,
        )
        assertEquals("get_time", calls[1].jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)

        val toolA = messages[2].jsonObject
        assertEquals("tool", toolA.role())
        assertEquals("call_a", toolA.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("sunny 25C", toolA.getValue("content").jsonPrimitive.content)

        val toolB = messages[3].jsonObject
        assertEquals("tool", toolB.role())
        assertEquals("call_b", toolB.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("10:00", toolB.getValue("content").jsonPrimitive.content)

        assertEquals("user", messages[4].jsonObject.role(), "the current turn stays last")
    }

    @Test
    fun `a tool result envelope reaches the tool message unchanged`() {
        // 0.3 Phase 2.2 Task 7: the adapter consumes `ToolResult.payload` as an opaque model-facing string. It must not
        // parse, re-serialize or prefix it; the envelope's execution and data reach the endpoint exactly as produced.
        val envelope = ToolResult(
            id = "call_1",
            ok = true,
            execution = ToolExecution(outcome = ToolOutcome.Done, accepted = true),
            observation = null,
            data = "notified:1",
        ).payload

        val messages = encode(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "notify me"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Notifying."),
                ModelHistoryItem.ToolCall("call_1", "notify_user", """{"text":"x","channel":"screen"}"""),
                ModelHistoryItem.ToolOutput("call_1", envelope),
            ),
        )

        val tool = messages[2].jsonObject
        assertEquals("tool", tool.role())
        assertEquals("call_1", tool.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals(envelope, tool.getValue("content").jsonPrimitive.content, "the payload is forwarded byte for byte")
        assertTrue("\"observation\":null" in envelope, "no observation is invented: $envelope")
        assertTrue("\"accepted\":true" in envelope, "the execution facts survive: $envelope")
        assertFalse("Failed: " in envelope, "no legacy prefix: $envelope")
        assertFalse("Refused: " in envelope, "no legacy prefix: $envelope")
    }

    @Test
    fun `tool calls without an assistant text carry a null content`() {
        val messages = encode(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "take a look"),
                ModelHistoryItem.ToolCall("call_shot", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput("call_shot", "captured"),
            ),
        )

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant.role())
        assertTrue(assistant.getValue("content") is JsonNull, "content is null when the round carried no text")
        assertEquals("call_shot", assistant.getValue("tool_calls").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("tool", messages[2].jsonObject.role())
        assertEquals("call_shot", messages[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun `text only history stays plain user and assistant messages`() {
        val messages = encode(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "first question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "first answer"),
            ),
        )

        assertEquals(3, messages.size, "user, assistant, then the current user turn: $messages")
        assertEquals("user", messages[0].jsonObject.role())
        assertEquals("first question", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject.role())
        assertEquals("first answer", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[2].jsonObject.role())
    }

    @Test
    fun `a history only continuation ends on the tool output without an empty user message`() {
        val messages = encode(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "take a look"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Checking."),
                ModelHistoryItem.ToolCall("call_shot", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput("call_shot", "captured"),
            ),
            input = emptyList(),
        )

        assertEquals(3, messages.size, "user, assistant(tool_calls), tool: $messages")
        assertEquals("tool", messages.last().jsonObject.role(), "the tool output stays last on the wire")
        assertTrue(
            messages.none {
                it.jsonObject.role() == "user" &&
                    (it.jsonObject.getValue("content") as? JsonArray)?.isEmpty() == true
            },
            "no empty user content array is appended to a history-only request",
        )
    }
}
