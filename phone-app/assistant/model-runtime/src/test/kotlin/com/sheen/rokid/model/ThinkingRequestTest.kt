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
 * 0.53 design (revised) §5.2: Chat Completions' thinking switch and official output budget.
 *
 * The owner ruling is that the **user switch is the only behaviour switch** — the system does not guess a model family.
 * `thinkingEnabled=true` sends the DeepSeek fields (`thinking.type=enabled` + the chosen `reasoning_effort`) on every
 * request, tools included, and **omits `max_tokens`** so the provider's official default (64K for LOW/HIGH, 128K for
 * MAX) applies. `thinkingEnabled=false` sends **no** thinking field at all — the general OpenAI body, which is exactly
 * the pre-0.5.2 shape — and keeps the existing cap.
 *
 * History reasoning is replayed as `reasoning_content` **only when the request carries tools**; it is not gated on any
 * capability bit. That is what lets a turn whose switch was off still round-trip reasoning the server returned anyway
 * (DeepSeek defaults thinking on), avoiding the next turn's HTTP 400.
 *
 * Both encoders have a legacy frame overload and a budgeted live overload, and the live path has already diverged from
 * the legacy one once, so every mapping test asserts both overloads.
 */
class ThinkingRequestTest {
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

    /**
     * A profile with a realistic window: the official output default (64K/128K) is reserved by the send-time
     * `ContextBudgeter`, so a 4 096-token fixture would refuse every thinking request instead of exercising the body.
     */
    private fun profile(
        enabled: Boolean = false,
        effort: ThinkingEffort = ThinkingEffort.LOW,
        thinkingSupported: Boolean = false,
    ) = profileFixture().copy(
        usableWindowTokens = 200_000,
        inputBudgetTokens = 100_000,
        maxOutputTokens = 6_000,
        safetyTokens = 1_000,
        protocolId = "OPENAI_CHAT_COMPLETIONS",
        thinkingEnabled = enabled,
        thinkingEffort = effort,
        thinkingSupported = thinkingSupported,
    )

    private fun frame(tools: List<ToolSpec>) = RequestFrame(
        listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "synthetic instructions")),
        listOf(Segment(SegmentOrigin.USER_INPUT, true, "synthetic question")),
        tools,
    )

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    /** Both Chat encoding paths: the legacy frame overload and the budgeted live overload. */
    private fun bodies(profile: ModelProfile, tools: List<ToolSpec> = emptyList(), cap: Int? = null): Map<String, JsonObject> =
        mapOf(
            "frame" to json(chat.encode(profile, frame(tools))),
            "budgeted" to json(
                chat.encode(profile, ContextBudgeter().prepare(profile, requestFixture().copy(tools = tools, maxOutputTokens = cap))),
            ),
        )

    private fun JsonObject.thinkingType() =
        getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content

    @Test fun `thinking on sends the deepseek switch and omits the output cap on every request`() {
        for ((effort, wire) in efforts) {
            for (withTools in listOf(false, true)) {
                for ((path, body) in bodies(profile(enabled = true, effort = effort), if (withTools) tools else emptyList())) {
                    assertEquals("enabled", body.thinkingType(), "$path/$effort/tools=$withTools")
                    assertEquals(wire, body.getValue("reasoning_effort").jsonPrimitive.content, "$path/$effort/tools=$withTools")
                    assertFalse("max_tokens" in body, "$path/$effort/tools=$withTools must let the provider use its 64K/128K default")
                }
            }
        }
    }

    @Test fun `thinking off sends no deepseek field and keeps the existing cap`() {
        for (withTools in listOf(false, true)) {
            val chosen = if (withTools) tools else emptyList()
            for ((path, body) in bodies(profile(enabled = false), chosen, cap = 512)) {
                assertEquals(
                    setOf("model", "stream", "stream_options", "modalities", "max_tokens") +
                        (if (withTools) setOf("tools", "tool_choice") else emptySet()) + "messages",
                    body.keys,
                    "$path/tools=$withTools must keep the pre-0.5.3 generic body",
                )
                // The frame overload carries no turn cap, so the profile's allowance is the value; the live overload
                // keeps the turn's own 512.
                assertEquals(
                    if (path == "frame") 6_000 else 512,
                    body.getValue("max_tokens").jsonPrimitive.int,
                    "$path must keep the existing cap",
                )
                assertFalse("enable_thinking" in body, "$path must not use the vendor-only enable_thinking switch")
            }
        }
    }

    @Test fun `history reasoning is replayed as reasoning_content only when the request carries tools`() {
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "old question"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "先说", reasoning = "想"),
            ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
        )
        val profile = profile(enabled = false)
        val withTools = json(chat.encode(profile, ContextBudgeter().prepare(profile, requestFixture().copy(history = history, tools = tools))))
            .getValue("messages").jsonArray.map { it.jsonObject }
        val assistant = withTools.single { it["role"]?.jsonPrimitive?.content == "assistant" && "tool_calls" in it }
        assertEquals("想", assistant.getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("call_1", assistant.getValue("tool_calls").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content)

        val withoutTools = json(chat.encode(profile, ContextBudgeter().prepare(profile, requestFixture().copy(history = history))))
            .getValue("messages").jsonArray.map { it.jsonObject }
        assertTrue(withoutTools.none { "reasoning_content" in it }, "without tools the reasoning stays local: $withoutTools")
    }

    @Test fun `reasoning replay does not look at the compatibility capability bit`() {
        // The switch is off and the compatibility `thinkingSupported` flag is false, yet the server may still have
        // returned reasoning (DeepSeek defaults thinking on). A tool-bearing request must replay it regardless.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "old question"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "先说", reasoning = "想"),
        )
        val profile = profile(enabled = false, thinkingSupported = false)
        val messages = json(chat.encode(profile, ContextBudgeter().prepare(profile, requestFixture().copy(history = history, tools = tools))))
            .getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(
            "想",
            messages.single { it["role"]?.jsonPrimitive?.content == "assistant" }
                .getValue("reasoning_content").jsonPrimitive.content,
        )
    }

    @Test fun `a non deepseek model with the switch off is the pre-0_53 chat body`() {
        val profile = profile(enabled = false).copy(model = "gpt-4o-mini")
        for (withTools in listOf(false, true)) {
            for ((path, body) in bodies(profile, if (withTools) tools else emptyList(), cap = 512)) {
                assertFalse("thinking" in body, "$path must omit thinking")
                assertFalse("reasoning_effort" in body, "$path must omit reasoning_effort")
                assertEquals(
                    if (path == "frame") 6_000 else 512,
                    body.getValue("max_tokens").jsonPrimitive.int,
                    "$path keeps the generic cap",
                )
                assertEquals(withTools, "tools" in body, "$path")
            }
        }
    }
}
