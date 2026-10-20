package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.ToolSpec
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DeepSeekResponsesAdapterFrameTest {
    private val adapter = DeepSeekResponsesAdapter()
    private val profile = profileFixture()

    private fun frame(
        systemSegments: List<Segment> = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "system prompt")),
        conversationFlow: List<Segment> = listOf(Segment(SegmentOrigin.USER_INPUT, true, "hello")),
        tools: List<ToolSpec> = emptyList(),
    ) = RequestFrame(systemSegments, conversationFlow, tools)

    private fun encode(frame: RequestFrame): JsonObject {
        val bytes = adapter.encode(profile, frame)
        return Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `encodes system slot as instructions field`() {
        val f = frame(
            systemSegments = listOf(
                Segment(SegmentOrigin.INSTRUCTIONS, true, "base instructions"),
                Segment(SegmentOrigin.SKILL, true, "skill content"),
                Segment(SegmentOrigin.MEMORY_POLICY, true, "memory policy"),
            )
        )
        val body = encode(f)
        assertEquals("base instructions\nskill content\nmemory policy", body["instructions"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodes memory evidence as separate user messages before user input`() {
        val f = frame(
            conversationFlow = listOf(
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory fact 1"),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory fact 2"),
                Segment(SegmentOrigin.USER_INPUT, true, "user question"),
            )
        )
        val body = encode(f)
        val input = body["input"]!!.jsonArray
        // Memory evidence items come before user input
        assertEquals(3, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("memory fact 1", input[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", input[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("memory fact 2", input[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", input[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user question", input[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodes tool call and output pairs from TOOL_ROUND segments`() {
        // TOOL_ROUND text is a JSON array of tool call/output entries
        val toolRoundJson = buildJsonArray {
            add(buildJsonObject {
                put("type", "call")
                put("callId", "call_1")
                put("itemId", "item_1")
                put("name", "get_weather")
                put("argsJson", """{"city":"Beijing"}""")
            })
            add(buildJsonObject {
                put("type", "output")
                put("callId", "call_1")
                put("output", "sunny, 25C")
            })
        }.toString()

        val f = frame(
            conversationFlow = listOf(
                Segment(SegmentOrigin.TOOL_ROUND, true, toolRoundJson),
                Segment(SegmentOrigin.USER_INPUT, true, "thanks"),
            ),
            tools = listOf(ToolSpec("get_weather", "Get weather", """{"type":"object","properties":{"city":{"type":"string"}}}"""))
        )
        val body = encode(f)
        val input = body["input"]!!.jsonArray
        // TOOL_ROUND produces function_call + function_call_output items, then user input
        assertEquals(3, input.size)

        val callItem = input[0].jsonObject
        assertEquals("function_call", callItem["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", callItem["call_id"]!!.jsonPrimitive.content)
        assertEquals("item_1", callItem["id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", callItem["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Beijing"}""", callItem["arguments"]!!.jsonPrimitive.content)

        val outputItem = input[1].jsonObject
        assertEquals("function_call_output", outputItem["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", outputItem["call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny, 25C", outputItem["output"]!!.jsonPrimitive.content)

        val userInput = input[2].jsonObject
        assertEquals("user", userInput["role"]!!.jsonPrimitive.content)
        assertEquals("thanks", userInput["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `system slot is byte-stable across calls with same frame`() {
        val f = frame(
            systemSegments = listOf(
                Segment(SegmentOrigin.INSTRUCTIONS, true, "stable instructions"),
            ),
            conversationFlow = listOf(
                Segment(SegmentOrigin.USER_INPUT, true, "question"),
            )
        )
        val bytes1 = adapter.encode(profile, f)
        val bytes2 = adapter.encode(profile, f)
        assertContentEquals(bytes1, bytes2)
    }

    @Test
    fun `a request level output cap overrides the profile cap`() {
        // 0.3 design §5.2: the turn policy's cap is the wire value; the profile's is only the window reservation.
        val request = requestFixture().copy(maxOutputTokens = 512)
        val body = Json.parseToJsonElement(
            String(adapter.encode(profile, ContextBudgeter().prepare(profile, request)), Charsets.UTF_8),
        ).jsonObject
        assertEquals(512, body["max_output_tokens"]!!.jsonPrimitive.int)
    }
}
