package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 0.52 design §5.7: the profile's thinking choice (Task 1) is mapped onto each protocol's wire fields by the adapters
 * themselves — no shared config object and no new public type.
 *
 * Both encoders have a legacy frame overload and a budgeted live overload, and the live path has already diverged from
 * the legacy one once (the Chat budgeted overload silently dropped the system slot and the tool table). Every mapping
 * test therefore asserts both overloads, and a tool request always turns thinking off: the deliberation is charged
 * against the output budget and the measured tool turn came back `incomplete/max_output_tokens` without it.
 */
class ThinkingRequestTest {
    private val responses = DeepSeekResponsesAdapter()
    private val chat = OpenAiChatCompletionsAdapter(object : MediaSource {
        override fun resolveAudio(ref: String): ResolvedAudio? = null
    })

    private val tools = listOf(
        ToolSpec(
            "record_note",
            "Record a short note for the user.",
            """{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""",
        ),
    )

    private val efforts = listOf(
        ThinkingEffort.LOW to "low",
        ThinkingEffort.HIGH to "high",
        ThinkingEffort.MAX to "max",
    )

    private fun responsesProfile(enabled: Boolean = false, effort: ThinkingEffort = ThinkingEffort.LOW) =
        profileFixture().copy(thinkingEnabled = enabled, thinkingEffort = effort, thinkingSupported = true)

    private fun chatProfile(enabled: Boolean = false, effort: ThinkingEffort = ThinkingEffort.LOW) =
        profileFixture().copy(
            protocolId = "OPENAI_CHAT_COMPLETIONS",
            thinkingEnabled = enabled,
            thinkingEffort = effort,
            thinkingSupported = true,
        )

    /**
     * A non-DeepSeek family model. Thinking is even switched on to prove the gate wins: the native fields are never
     * sent based on the profile's own choice, only on the model's capability bit (R21).
     */
    private fun nonDeepSeekResponsesProfile() = responsesProfile(enabled = true, effort = ThinkingEffort.MAX)
        .copy(thinkingSupported = false)

    private fun nonDeepSeekChatProfile() = chatProfile(enabled = true, effort = ThinkingEffort.MAX)
        .copy(thinkingSupported = false)

    private fun frame(tools: List<ToolSpec>) = RequestFrame(
        listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "synthetic instructions")),
        listOf(Segment(SegmentOrigin.USER_INPUT, true, "synthetic question")),
        tools,
    )

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    /** Both Responses encoding paths: the legacy frame overload and the budgeted live overload. */
    private fun responsesBodies(profile: ModelProfile, tools: List<ToolSpec> = emptyList()): Map<String, JsonObject> =
        mapOf(
            "frame" to json(responses.encode(profile, frame(tools))),
            "budgeted" to json(
                responses.encode(
                    profile,
                    ContextBudgeter().prepare(profile, requestFixture().copy(tools = tools)),
                ),
            ),
        )

    /** Both Chat encoding paths: the legacy frame overload and the budgeted live overload. */
    private fun chatBodies(profile: ModelProfile, tools: List<ToolSpec> = emptyList()): Map<String, JsonObject> =
        mapOf(
            "frame" to json(chat.encode(profile, frame(tools))),
            "budgeted" to json(
                chat.encode(
                    profile,
                    ContextBudgeter().prepare(profile, requestFixture().copy(tools = tools)),
                ),
            ),
        )

    private fun JsonObject.reasoningEffort() =
        getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content

    private fun JsonObject.thinkingType() =
        getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content

    @Test fun `responses text only disables thinking by default`() {
        for ((path, body) in responsesBodies(responsesProfile())) {
            assertEquals(
                setOf("model", "instructions", "stream", "max_output_tokens", "reasoning", "input"),
                body.keys,
                "$path must state the defaulted-off reasoning explicitly: DeepSeek defaults thinking on",
            )
            assertEquals(setOf("effort"), body.getValue("reasoning").jsonObject.keys, path)
            assertEquals("none", body.reasoningEffort(), path)
        }
    }

    @Test fun `responses text only enabled sends low high and max`() {
        for ((effort, wire) in efforts) {
            for ((path, body) in responsesBodies(responsesProfile(enabled = true, effort = effort))) {
                assertEquals(wire, body.reasoningEffort(), "$path/$effort")
            }
        }
    }

    @Test fun `responses tool requests always force effort none even when enabled`() {
        for ((path, body) in responsesBodies(responsesProfile(enabled = true, effort = ThinkingEffort.MAX), tools)) {
            assertEquals("none", body.reasoningEffort(), path)
            assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content, path)
            assertEquals("record_note", body.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content, path)
        }
    }

    @Test fun `chat text only disables thinking by default`() {
        for ((path, body) in chatBodies(chatProfile())) {
            assertEquals("disabled", body.thinkingType(), path)
            assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content, path)
            assertEquals(setOf("type"), body.getValue("thinking").jsonObject.keys, path)
            assertFalse("enable_thinking" in body, "$path must not use the vendor-only enable_thinking switch")
            assertFalse("reasoning" in body, "$path must not send a Responses-style reasoning budget")
        }
    }

    @Test fun `chat text only enabled sends enabled and the chosen effort`() {
        for ((effort, wire) in efforts) {
            for ((path, body) in chatBodies(chatProfile(enabled = true, effort = effort))) {
                assertEquals("enabled", body.thinkingType(), "$path/$effort")
                assertEquals(wire, body.getValue("reasoning_effort").jsonPrimitive.content, "$path/$effort")
            }
        }
    }

    @Test fun `chat tool requests always force thinking off even when enabled`() {
        for ((path, body) in chatBodies(chatProfile(enabled = true, effort = ThinkingEffort.MAX), tools)) {
            assertEquals("disabled", body.thinkingType(), path)
            assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content, path)
            assertEquals("record_note", body.getValue("tools").jsonArray.single().jsonObject
                .getValue("function").jsonObject.getValue("name").jsonPrimitive.content, path)
            assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content, path)
        }
    }

    @Test fun `responses text only omits reasoning for a non deepseek model`() {
        for ((path, body) in responsesBodies(nonDeepSeekResponsesProfile())) {
            assertEquals(
                setOf("model", "instructions", "stream", "max_output_tokens", "input"),
                body.keys,
                "$path must restore the pre-0.5.2 text-only shape for a non-DeepSeek model",
            )
            assertFalse("reasoning" in body, "$path must not send a DeepSeek-native reasoning field")
        }
    }

    @Test fun `responses tool requests keep explicit effort none for a non deepseek model`() {
        // The measured tool turn needs `none` on every protocol, so the tool path keeps the field even when the
        // model is not DeepSeek: the ruling gates only the text-only shape.
        for ((path, body) in responsesBodies(nonDeepSeekResponsesProfile(), tools)) {
            assertEquals("none", body.reasoningEffort(), path)
        }
    }

    @Test fun `chat text only omits the native thinking fields for a non deepseek model`() {
        for ((path, body) in chatBodies(nonDeepSeekChatProfile())) {
            assertEquals(
                setOf("model", "stream", "stream_options", "modalities", "max_tokens", "messages"),
                body.keys,
                "$path must restore the pre-0.5.2 text-only shape for a non-DeepSeek model",
            )
            assertFalse("thinking" in body, "$path must omit thinking")
            assertFalse("reasoning_effort" in body, "$path must omit reasoning_effort")
            assertFalse("enable_thinking" in body, "$path must not use the vendor-only enable_thinking switch")
        }
    }

    @Test fun `chat tool requests omit the native thinking fields for a non deepseek model`() {
        for ((path, body) in chatBodies(nonDeepSeekChatProfile(), tools)) {
            assertEquals(
                setOf("model", "stream", "stream_options", "modalities", "max_tokens", "tools", "tool_choice", "messages"),
                body.keys,
                "$path must keep the tool table while omitting the DeepSeek-native fields",
            )
            assertFalse("thinking" in body, "$path must omit thinking")
            assertFalse("reasoning_effort" in body, "$path must omit reasoning_effort")
            assertFalse("enable_thinking" in body, "$path must not use the vendor-only enable_thinking switch")
        }
    }

    @Test fun `the live chat request keeps instructions and the tool table`() {
        val request = requestFixture().copy(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "old question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "old answer"),
            ),
            tools = tools,
        )
        val profile = chatProfile()
        val body = json(chat.encode(profile, ContextBudgeter().prepare(profile, request)))

        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            messages.map { it.getValue("role").jsonPrimitive.content },
            "message order stays system -> history -> current turn",
        )
        assertEquals("synthetic instructions", messages[0].getValue("content").jsonPrimitive.content)
        assertEquals("old question", messages[1].getValue("content").jsonPrimitive.content)
        assertEquals("old answer", messages[2].getValue("content").jsonPrimitive.content)
        assertEquals("synthetic question", messages[3].getValue("content").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content)
        assertEquals(
            "record_note",
            body.getValue("tools").jsonArray.single().jsonObject
                .getValue("function").jsonObject.getValue("name").jsonPrimitive.content,
            "the live Chat request must carry the tool table, like the legacy overload",
        )
        assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content)
    }
}
