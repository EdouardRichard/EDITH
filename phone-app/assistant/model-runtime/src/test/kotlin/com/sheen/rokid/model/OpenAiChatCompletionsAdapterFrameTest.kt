package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiChatCompletionsAdapterFrameTest {
    private val media = object : MediaSource {
        override fun resolveAudio(ref: String): ResolvedAudio? = when (ref) {
            "audio1" -> ResolvedAudio(byteArrayOf(0, 1, 2, 3), "pcm16-16k-mono", 100)
            else -> null
        }
    }
    private val adapter = OpenAiChatCompletionsAdapter(media)

    private val profile = ModelProfile(
        "p1", 1, "OpenAI Test", "service", "https://example.test",
        "OPENAI_CHAT_COMPLETIONS", "gpt-4o-test",
        object : SecretHandle { override val id = "dummy" },
        4096, 3000, 256, 128, 10_000,
        setOf(InputModality.TEXT, InputModality.AUDIO), setOf(OutputModality.TEXT),
        listOf(verification())
    )

    private fun frame(
        systemSegments: List<Segment> = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "system prompt")),
        conversationFlow: List<Segment> = listOf(Segment(SegmentOrigin.USER_INPUT, true, "hello")),
        tools: List<ToolSpec> = emptyList(),
    ) = RequestFrame(systemSegments, conversationFlow, tools)

    private fun encode(frame: RequestFrame, audioParts: List<InputPart.Audio> = emptyList()): JsonObject {
        val bytes = adapter.encode(profile, frame, audioParts)
        return Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `first message is system with system slot content`() {
        val f = frame(
            systemSegments = listOf(
                Segment(SegmentOrigin.INSTRUCTIONS, true, "base instructions"),
                Segment(SegmentOrigin.SKILL, true, "skill content"),
                Segment(SegmentOrigin.MEMORY_POLICY, true, "memory policy"),
            )
        )
        val body = encode(f)
        val messages = body["messages"]!!.jsonArray
        assertTrue(messages.isNotEmpty(), "messages array must not be empty")
        val firstMessage = messages[0].jsonObject
        assertEquals("system", firstMessage["role"]!!.jsonPrimitive.content)
        assertEquals(
            "base instructions\nskill content\nmemory policy",
            firstMessage["content"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `tool call and output are encoded as assistant tool_calls and tool role messages`() {
        val toolRoundJson = buildJsonArray {
            add(buildJsonObject {
                put("type", "call")
                put("callId", "call_abc")
                put("name", "get_weather")
                put("argsJson", """{"city":"Beijing"}""")
            })
            add(buildJsonObject {
                put("type", "output")
                put("callId", "call_abc")
                put("output", "sunny, 25C")
            })
        }.toString()

        val f = frame(
            conversationFlow = listOf(
                Segment(SegmentOrigin.TOOL_ROUND, true, toolRoundJson),
                Segment(SegmentOrigin.USER_INPUT, true, "thanks"),
            ),
            tools = listOf(
                ToolSpec("get_weather", "Get weather", """{"type":"object","properties":{"city":{"type":"string"}}}""")
            )
        )
        val body = encode(f)
        val messages = body["messages"]!!.jsonArray

        // messages[0] = system, messages[1] = assistant with tool_calls, messages[2] = tool output, messages[3] = user
        assertEquals(4, messages.size, "Expected system + assistant(tool_calls) + tool + user")

        // Assistant message with tool_calls
        val assistantMsg = messages[1].jsonObject
        assertEquals("assistant", assistantMsg["role"]!!.jsonPrimitive.content)
        val toolCalls = assistantMsg["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val tc = toolCalls[0].jsonObject
        assertEquals("call_abc", tc["id"]!!.jsonPrimitive.content)
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tc["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Beijing"}""", tc["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)

        // Tool output message
        val toolMsg = messages[2].jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny, 25C", toolMsg["content"]!!.jsonPrimitive.content)

        // Final user message
        val userMsg = messages[3].jsonObject
        assertEquals("user", userMsg["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `memory evidence appears as user messages before final user input`() {
        val f = frame(
            conversationFlow = listOf(
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory fact 1"),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "memory fact 2"),
                Segment(SegmentOrigin.USER_INPUT, true, "user question"),
            )
        )
        val body = encode(f)
        val messages = body["messages"]!!.jsonArray
        // messages[0] = system, messages[1] = memory1, messages[2] = memory2, messages[3] = user input
        assertEquals(4, messages.size)

        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)

        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("memory fact 1", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        assertEquals("user", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("memory fact 2", messages[2].jsonObject["content"]!!.jsonPrimitive.content)

        assertEquals("user", messages[3].jsonObject["role"]!!.jsonPrimitive.content)
        // USER_INPUT renders as content parts array (text + optional audio)
        val contentParts = messages[3].jsonObject["content"]!!.jsonArray
        assertEquals(1, contentParts.size)
        assertEquals("text", contentParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("user question", contentParts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `system message is byte-stable across identical frames`() {
        val f = frame(
            systemSegments = listOf(
                Segment(SegmentOrigin.INSTRUCTIONS, true, "stable instructions"),
                Segment(SegmentOrigin.SKILL, true, "stable skill"),
            ),
            conversationFlow = listOf(
                Segment(SegmentOrigin.USER_INPUT, true, "question"),
            )
        )
        val bytes1 = adapter.encode(profile, f)
        val bytes2 = adapter.encode(profile, f)
        assertContentEquals(bytes1, bytes2, "Identical frames must produce byte-identical output")
    }

    @Test
    fun `audio input still works with RequestFrame`() {
        val f = frame(
            conversationFlow = listOf(
                Segment(SegmentOrigin.USER_INPUT, true, "describe this sound"),
            )
        )
        val body = encode(f, listOf(InputPart.Audio("audio1")))
        val messages = body["messages"]!!.jsonArray
        val lastMessage = messages.last().jsonObject
        assertEquals("user", lastMessage["role"]!!.jsonPrimitive.content)
        val contentParts = lastMessage["content"]!!.jsonArray
        // Should have text part + audio part
        assertEquals(2, contentParts.size)

        val textPart = contentParts[0].jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        assertEquals("describe this sound", textPart["text"]!!.jsonPrimitive.content)

        val audioPart = contentParts[1].jsonObject
        assertEquals("input_audio", audioPart["type"]!!.jsonPrimitive.content)
        val inputAudio = audioPart["input_audio"]!!.jsonObject
        assertEquals("wav", inputAudio["format"]!!.jsonPrimitive.content)
        // Verify base64 data decodes to WAV with RIFF header
        val decoded = java.util.Base64.getDecoder().decode(inputAudio["data"]!!.jsonPrimitive.content)
        assertEquals("RIFF", String(decoded.sliceArray(0..3), Charsets.US_ASCII))
    }

    @Test
    fun `a request level output cap overrides the profile cap`() {
        // 0.3 design §5.2: the turn policy's cap is the wire value; the profile's is only the window reservation.
        val request = requestFixture().copy(
            profileId = profile.id,
            profileRevision = profile.revision,
            maxOutputTokens = 512,
        )
        val body = Json.parseToJsonElement(
            String(adapter.encode(profile, ContextBudgeter().prepare(profile, request)), Charsets.UTF_8),
        ).jsonObject
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.int)
    }
}
