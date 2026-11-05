package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.53 design §5.3: the Responses encoder's half of the DeepSeek thinking loop.
 *
 * The request keeps the switch explicit (`reasoning.effort`) and, when the profile is thinking-capable, replays each
 * assistant turn's `reasoning` as a `reasoning` input item immediately before that assistant message. The item's inner
 * shape is the one validated against the real gateway: `{"type":"reasoning","content":[{"type":"reasoning_text",
 * "text":…}]}`. A non-DeepSeek profile never emits one.
 */
class ResponsesThinkingTest {
    private val adapter = DeepSeekResponsesAdapter()

    private val tools = listOf(
        ToolSpec(
            "record_note",
            "Record a short note for the user.",
            """{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""",
        ),
    )

    private fun profile(enabled: Boolean = true, effort: ThinkingEffort = ThinkingEffort.LOW) =
        profileFixture().copy(thinkingSupported = true, thinkingEnabled = enabled, thinkingEffort = effort)

    /** `profileFixture()` only reserves 4 096 tokens; the thinking floor needs a window with room for it. */
    private fun roomyProfile(enabled: Boolean = true, effort: ThinkingEffort = ThinkingEffort.HIGH) = profile(enabled, effort)
        .copy(
            usableWindowTokens = 200_000,
            inputBudgetTokens = 100_000,
            maxOutputTokens = 1_024,
            safetyTokens = 1_000,
        )

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun encoded(profile: ModelProfile, request: ModelRequest = requestFixture()) =
        json(adapter.encode(profile, ContextBudgeter().prepare(profile, request)))

    private fun JsonObject.inputItems() = getValue("input").jsonArray.map { it.jsonObject }

    /** The item kind: message items carry a `role`, every other Responses item carries a `type`. */
    private fun JsonObject.kind() = (this["type"] ?: this["role"])?.jsonPrimitive?.content

    private fun JsonObject.effortOf() =
        getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content

    private fun historyWithReasoning() = requestFixture().copy(
        history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "old question"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "先说", reasoning = "想"),
            ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
        ),
    )

    @Test fun `an active thinking tool request sends the chosen effort`() {
        for ((effort, wire) in listOf(
            ThinkingEffort.LOW to "low",
            ThinkingEffort.HIGH to "high",
            ThinkingEffort.MAX to "max",
        )) {
            val body = encoded(profile(enabled = true, effort = effort), requestFixture().copy(tools = tools))
            assertEquals(wire, body.effortOf(), "$effort: the tool turn keeps thinking")
            assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content, "$effort")
        }
    }

    @Test fun `a disabled thinking profile still states effort none on a tool request`() {
        val body = encoded(profile(enabled = false), requestFixture().copy(tools = tools))
        assertEquals("none", body.effortOf(), "DeepSeek defaults thinking on; the off state must be explicit")
    }

    @Test fun `an assistant history item is preceded by its own reasoning item`() {
        val input = encoded(profile(), historyWithReasoning()).inputItems()
        assertEquals(
            listOf("user", "reasoning", "assistant", "function_call", "function_call_output", "user"),
            input.map { it.kind() },
            "the reasoning item sits immediately before the assistant message it belongs to: $input",
        )

        val reasoning = input[1]
        assertEquals(setOf("type", "content"), reasoning.keys, "no id/summary/encrypted_content fields are invented")
        assertEquals("reasoning", reasoning.getValue("type").jsonPrimitive.content)
        val content = reasoning.getValue("content").jsonArray.single().jsonObject
        assertEquals(setOf("type", "text"), content.keys)
        assertEquals("reasoning_text", content.getValue("type").jsonPrimitive.content)
        assertEquals("想", content.getValue("text").jsonPrimitive.content)

        val assistant = input[2]
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
        assertEquals("先说", assistant.getValue("content").jsonPrimitive.content)
        assertEquals(
            "call_1",
            input[3].getValue("call_id").jsonPrimitive.content,
            "the assistant message is still followed by the call it produced",
        )
    }

    @Test fun `a non deepseek profile never emits a reasoning item`() {
        val input = encoded(profile().copy(thinkingSupported = false), historyWithReasoning()).inputItems()
        assertTrue(
            input.none { it.kind() == "reasoning" },
            "a non-DeepSeek endpoint must not receive a DeepSeek-native item: $input",
        )
        // The assistant message itself is unchanged, reasoning dropped with it.
        assertEquals(
            listOf("user", "assistant", "function_call", "function_call_output", "user"),
            input.map { it.kind() },
        )
        assertNull(input[1]["reasoning_content"], "the Responses shape never carries the Chat field")
    }

    @Test fun `an assistant text without reasoning emits no reasoning item`() {
        val plain = requestFixture().copy(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "old question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "old answer"),
            ),
        )
        val input = encoded(profile(), plain).inputItems()
        assertEquals(listOf("user", "assistant", "user"), input.map { it.kind() })
    }

    @Test fun `a deepseek text only request without reasoning keeps the plain item shape`() {
        val body = encoded(profile(enabled = false), requestFixture())
        assertEquals(setOf("model", "instructions", "stream", "max_output_tokens", "reasoning", "input"), body.keys)
        assertTrue(body.inputItems().none { it.kind() == "reasoning" })
    }

    @Test fun `an active thinking request raises max output tokens to the floor`() {
        val roomy = roomyProfile(effort = ThinkingEffort.HIGH)
        assertEquals(32_768, ThinkingOutputBudget.reserve(roomy))
        assertEquals(32_768, encoded(roomy).getValue("max_output_tokens").jsonPrimitive.int)

        val legacy = json(adapter.encode(roomy, RequestFrame(
            listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "synthetic instructions")),
            listOf(Segment(SegmentOrigin.USER_INPUT, true, "synthetic question")),
            emptyList(),
        )))
        assertEquals(32_768, legacy.getValue("max_output_tokens").jsonPrimitive.int, "both overloads share the floor")

        val max = roomyProfile(effort = ThinkingEffort.MAX)
        assertEquals(65_536, encoded(max).getValue("max_output_tokens").jsonPrimitive.int)

        // The turn's own cap is a lower bound, not the wire value, while thinking is active.
        val capped = requestFixture().copy(maxOutputTokens = 512, tools = tools)
        assertEquals(32_768, encoded(roomy, capped).getValue("max_output_tokens").jsonPrimitive.int)
        val off = roomyProfile(enabled = false)
        assertEquals(512, encoded(off, capped).getValue("max_output_tokens").jsonPrimitive.int)
    }

    @Test fun `every reasoning uses the one frozen item shape`() {
        val input = encoded(profile(), historyWithReasoning()).inputItems()
        val reasoningItems = input.filter { it.kind() == "reasoning" }
        assertEquals(1, reasoningItems.size)
        for (item in reasoningItems) {
            assertFalse("id" in item, "the probe accepted no invented item id")
            assertFalse("summary" in item, "summary content is not supported by the endpoint")
            assertFalse("encrypted_content" in item, "encrypted content is not supported by the endpoint")
        }
    }
}
