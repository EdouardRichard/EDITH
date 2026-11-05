package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.52 design §5.7 / 0.53 design §5.2: the profile's thinking choice is mapped onto each protocol's wire fields by the
 * adapters themselves — no shared config object and no new public type.
 *
 * Both encoders have a legacy frame overload and a budgeted live overload, and the live path has already diverged from
 * the legacy one once (the Chat budgeted overload silently dropped the system slot and the tool table). Every mapping
 * test therefore asserts both overloads.
 *
 * 0.53 §T3 changed the tool rule: a **tool request no longer forces thinking off** when the profile has thinking active;
 * the switch and the chosen effort are sent with tools too. When thinking is off it is still stated explicitly
 * (`disabled`/`none`) because DeepSeek defaults thinking on — an absent field is not the same request. A profile whose
 * model is not thinking-capable keeps the pre-0.5.2 body: no DeepSeek-native field at all.
 *
 * 0.53 §T5: thinking is charged against the output budget, so an active request's output allowance is raised to
 * [ThinkingOutputBudget.reserve]; the budgeted overload's own cap is a lower bound, not the wire value.
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
     * A Chat profile with room for the thinking output floor. `profileFixture()` only reserves 4 096 tokens, so its
     * low/high floor is capped back down to its 256-token allowance and the floor rule is unobservable.
     */
    private fun roomyChatProfile(
        enabled: Boolean = true,
        effort: ThinkingEffort = ThinkingEffort.HIGH,
    ) = chatProfile(enabled = enabled, effort = effort).copy(
        usableWindowTokens = 200_000,
        inputBudgetTokens = 100_000,
        maxOutputTokens = 1_024,
        safetyTokens = 1_000,
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

    @Test fun `responses tool requests still state effort none when thinking is disabled`() {
        for ((path, body) in responsesBodies(responsesProfile(enabled = false), tools)) {
            assertEquals("none", body.reasoningEffort(), path)
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

    @Test fun `chat tool requests keep thinking enabled and the chosen effort`() {
        // 0.53 §T3: the DeepSeek tool turn is no longer forced off; the reasoning history is replayed instead.
        for ((effort, wire) in efforts) {
            for ((path, body) in chatBodies(chatProfile(enabled = true, effort = effort), tools)) {
                assertEquals("enabled", body.thinkingType(), "$path/$effort")
                assertEquals(wire, body.getValue("reasoning_effort").jsonPrimitive.content, "$path/$effort")
                assertEquals(
                    "record_note",
                    body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
                        .getValue("name").jsonPrimitive.content,
                    "$path/$effort",
                )
                assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content, "$path/$effort")
            }
        }
    }

    @Test fun `chat tool requests still state disabled and none when thinking is disabled`() {
        for ((path, body) in chatBodies(chatProfile(enabled = false), tools)) {
            assertEquals("disabled", body.thinkingType(), path)
            assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content, path)
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

    // ------------------------------------------------------------ 0.53 §T5 output floor

    @Test fun `an active thinking chat request raises the output allowance to the floor`() {
        for ((effort, floor) in listOf(
            ThinkingEffort.LOW to 32_768,
            ThinkingEffort.HIGH to 32_768,
            ThinkingEffort.MAX to 65_536,
        )) {
            val chat = roomyChatProfile(effort = effort)
            assertEquals(maxOf(chat.maxOutputTokens, floor), ThinkingOutputBudget.reserve(chat), "$effort")
            for ((path, body) in chatBodies(chat)) {
                assertEquals(
                    ThinkingOutputBudget.reserve(chat),
                    body.getValue("max_tokens").jsonPrimitive.int,
                    "chat/$path/$effort",
                )
            }
        }
    }

    @Test fun `a small chat request cap is raised to the thinking output floor and left alone when thinking is off`() {
        val active = roomyChatProfile()
        val capped = requestFixture().copy(maxOutputTokens = 512, tools = tools)
        assertEquals(32_768, ThinkingOutputBudget.reserve(active))
        assertEquals(
            32_768,
            json(chat.encode(active, ContextBudgeter().prepare(active, capped))).getValue("max_tokens").jsonPrimitive.int,
        )

        val off = roomyChatProfile(enabled = false)
        assertEquals(off.maxOutputTokens, ThinkingOutputBudget.reserve(off))
        assertEquals(
            512,
            json(chat.encode(off, ContextBudgeter().prepare(off, capped))).getValue("max_tokens").jsonPrimitive.int,
            "a disabled profile keeps the turn's own cap",
        )
    }

    // ------------------------------------------------------------ 0.53 §T4 reasoning replay (Chat)

    @Test fun `a chat history replays assistant reasoning on the very assistant message that carried the calls`() {
        val profile = chatProfile()
        val request = requestFixture().copy(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "old question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "先说", reasoning = "想"),
                ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
                ModelHistoryItem.ToolOutput("call_1", "noted"),
            ),
        )
        val messages = json(chat.encode(profile, ContextBudgeter().prepare(profile, request)))
            .getValue("messages").jsonArray.map { it.jsonObject }

        val assistant = messages[2]
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
        assertEquals("先说", assistant.getValue("content").jsonPrimitive.content)
        assertEquals("想", assistant.getValue("reasoning_content").jsonPrimitive.content)
        assertEquals(
            "call_1",
            assistant.getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content,
            "the reasoning rides on the message that merges the assistant text and its calls",
        )
    }

    @Test fun `a chat history omits reasoning for a non deepseek model`() {
        val profile = chatProfile().copy(thinkingSupported = false)
        val request = requestFixture().copy(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "old question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "old answer", reasoning = "想"),
            ),
        )
        val messages = json(chat.encode(profile, ContextBudgeter().prepare(profile, request)))
            .getValue("messages").jsonArray.map { it.jsonObject }
        assertFalse("reasoning_content" in messages[2], "a non-DeepSeek endpoint never receives reasoning_content")
    }

    @Test fun `a plain assistant text keeps its own reasoning when another assistant text follows`() {
        val profile = chatProfile()
        val request = requestFixture().copy(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "old question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "first answer", reasoning = "first thought"),
            ),
        )
        val messages = json(chat.encode(profile, ContextBudgeter().prepare(profile, request)))
            .getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("first thought", messages[2].getValue("reasoning_content").jsonPrimitive.content)
        assertTrue("tool_calls" !in messages[2], "a text-only assistant turn has no calls")
    }
}
