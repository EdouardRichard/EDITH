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
 * 0.53 design (revised) §5.3: the Responses encoder's half of the officially-defined thinking loop.
 *
 * `thinkingEnabled=true` writes the chosen `reasoning.effort` on every request (tools included) and **omits
 * `max_output_tokens`** so the provider's official default applies. `thinkingEnabled=false` writes no thinking field at
 * all — the pre-0.5.3 body. A history assistant turn's `reasoning` is replayed as a `reasoning` input item immediately
 * before its message **only when the request carries tools**; no capability bit is consulted (owner ruling A).
 *
 * The item inner shape is the T11 shape: `{"type":"reasoning","content":[{"type":"reasoning_text","text":…}]}`.
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

    /**
     * A profile with a realistic window: the official 64K/128K output default is reserved by `ContextBudgeter`, so a
     * 4 096-token fixture would refuse every thinking request instead of exercising the wire body.
     */
    private fun profile(
        enabled: Boolean = true,
        effort: ThinkingEffort = ThinkingEffort.LOW,
        thinkingSupported: Boolean = false,
    ) = profileFixture().copy(
        usableWindowTokens = 200_000,
        inputBudgetTokens = 100_000,
        maxOutputTokens = 6_000,
        safetyTokens = 1_000,
        thinkingEnabled = enabled,
        thinkingEffort = effort,
        thinkingSupported = thinkingSupported,
    )

    private fun frame(tools: List<ToolSpec> = emptyList()) = RequestFrame(
        listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "synthetic instructions")),
        listOf(Segment(SegmentOrigin.USER_INPUT, true, "synthetic question")),
        tools,
    )

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun encoded(profile: ModelProfile, request: ModelRequest = requestFixture()) =
        json(adapter.encode(profile, ContextBudgeter().prepare(profile, request)))

    /** Both Responses encoding paths, keyed for assertion messages. */
    private fun bodies(profile: ModelProfile, tools: List<ToolSpec> = emptyList(), cap: Int? = null): Map<String, JsonObject> =
        mapOf(
            "frame" to json(adapter.encode(profile, frame(tools))),
            "budgeted" to json(
                adapter.encode(profile, ContextBudgeter().prepare(profile, requestFixture().copy(tools = tools, maxOutputTokens = cap))),
            ),
        )

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

    @Test fun `thinking on sends the chosen effort and omits the output cap on every request`() {
        for ((effort, wire) in listOf(
            ThinkingEffort.LOW to "low",
            ThinkingEffort.HIGH to "high",
            ThinkingEffort.MAX to "max",
        )) {
            for (withTools in listOf(false, true)) {
                for ((path, body) in bodies(profile(enabled = true, effort = effort), if (withTools) tools else emptyList())) {
                    assertEquals(wire, body.effortOf(), "$path/$effort/tools=$withTools")
                    assertFalse("max_output_tokens" in body, "$path/$effort/tools=$withTools must let the provider default")
                    if (withTools) assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content, "$path")
                }
            }
        }
    }

    @Test fun `thinking off sends no reasoning field at all and keeps the output cap`() {
        for (withTools in listOf(false, true)) {
            for ((path, body) in bodies(profile(enabled = false), if (withTools) tools else emptyList(), cap = 512)) {
                assertFalse("reasoning" in body, "$path/tools=$withTools must send no thinking field")
                // The frame overload carries no turn cap, so the profile's allowance is the value; the live overload
                // keeps the turn's own 512.
                assertEquals(
                    if (path == "frame") 6_000 else 512,
                    body.getValue("max_output_tokens").jsonPrimitive.int,
                    "$path must keep the existing cap",
                )
                assertEquals(withTools, "tools" in body, "$path")
            }
        }
    }

    @Test fun `a reasoning item precedes its assistant message only when the request carries tools`() {
        val profile = profile(enabled = false)
        val withTools = encoded(profile, historyWithReasoning().copy(tools = tools)).inputItems()
        assertEquals(
            listOf("user", "reasoning", "assistant", "function_call", "function_call_output", "user"),
            withTools.map { it.kind() },
            "the reasoning item sits immediately before the assistant message it belongs to: $withTools",
        )
        val reasoning = withTools[1]
        assertEquals(setOf("type", "content"), reasoning.keys, "no id/summary/encrypted_content fields are invented")
        assertEquals("reasoning", reasoning.getValue("type").jsonPrimitive.content)
        val content = reasoning.getValue("content").jsonArray.single().jsonObject
        assertEquals(setOf("type", "text"), content.keys)
        assertEquals("reasoning_text", content.getValue("type").jsonPrimitive.content)
        assertEquals("想", content.getValue("text").jsonPrimitive.content)

        val withoutTools = encoded(profile, historyWithReasoning()).inputItems()
        assertTrue(
            withoutTools.none { it.kind() == "reasoning" },
            "without tools the reasoning is not replayed: $withoutTools",
        )
        assertTrue(withoutTools.any { it.kind() == "assistant" }, "the assistant message itself stays")
    }

    @Test fun `reasoning replay does not look at the compatibility capability bit`() {
        val profile = profile(enabled = false, thinkingSupported = false)
        val input = encoded(profile, historyWithReasoning().copy(tools = tools)).inputItems()
        assertEquals(
            "想",
            input.single { it.kind() == "reasoning" }
                .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test fun `a non deepseek model with the switch off is the pre-0_53 responses body`() {
        val profile = profile(enabled = false).copy(model = "gpt-4o-mini")
        val textOnly = encoded(profile)
        assertEquals(
            setOf("model", "instructions", "stream", "max_output_tokens", "input"),
            textOnly.keys,
            "text-only off must restore the pre-0.5.3 shape",
        )
        assertNull(textOnly["reasoning"])
        // Nothing was captured in this history (no assistant reasoning), so the tool request is also the plain body.
        val plainHistory = requestFixture().copy(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "old question"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "old answer"),
                ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
                ModelHistoryItem.ToolOutput("call_1", "noted"),
            ),
            tools = tools,
        )
        val withTools = encoded(profile, plainHistory)
        assertEquals(
            setOf("model", "instructions", "stream", "max_output_tokens", "tools", "tool_choice", "input"),
            withTools.keys,
            "a tool request keeps the tool table but no thinking field",
        )
        assertTrue(withTools.inputItems().none { it.kind() == "reasoning" })
    }
}
