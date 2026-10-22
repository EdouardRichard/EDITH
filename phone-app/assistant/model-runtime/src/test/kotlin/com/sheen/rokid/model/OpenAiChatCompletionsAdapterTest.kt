package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiChatCompletionsAdapterTest {
    private val profile = ModelProfile("p1", 1, "Omni Test", "Alibaba",
        "https://example.com/v1", "OPENAI_CHAT_COMPLETIONS", "qwen-omni-test",
        object : SecretHandle { override val id = "test" },
        4096, 2048, 1024, 128, 5000,
        setOf(InputModality.TEXT, InputModality.AUDIO), setOf(OutputModality.TEXT), emptyList())

    private fun budgeted(input: List<InputPart> = listOf(InputPart.Text("hello"))) = BudgetedRequest(
        ModelRequest(EventEnvelope(SessionId("s"), Generation(1), "e", 0, 1),
            "q", "t", "p1", 1, "instructions", input, emptyList(), OutputModality.TEXT, 100),
        estimatedInputTokens = 10, removedIds = emptyList(), estimated = false)

    @Test fun `text only request has no input_audio part`() {
        val media = object : MediaSource { override fun resolveAudio(ref: String) = null }
        val adapter = OpenAiChatCompletionsAdapter(media)
        val body = Json.parseToJsonElement(String(adapter.encode(profile, budgeted()))).jsonObject
        val messages = body["messages"]!!.jsonArray
        val content = messages.last().jsonObject["content"]!!.jsonArray
        assertFalse(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "input_audio" })
        assertTrue(adapter.support.inputs.contains(InputModality.AUDIO))
    }

    @Test fun `audio part is encoded as base64 wav with RIFF header`() {
        val pcm = byteArrayOf(0, 1, 2, 3)
        val media = object : MediaSource {
            override fun resolveAudio(ref: String) = ResolvedAudio(pcm, "pcm16-16k-mono", 100)
        }
        val adapter = OpenAiChatCompletionsAdapter(media)
        val req = budgeted(listOf(InputPart.Text("hi"), InputPart.Audio("ref1")))
        val body = Json.parseToJsonElement(String(adapter.encode(profile, req))).jsonObject
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        val audioPart = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "input_audio" }
        val data = audioPart.jsonObject["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        val decoded = java.util.Base64.getDecoder().decode(data)
        // First 4 bytes must be RIFF
        assertEquals("RIFF", String(decoded.sliceArray(0..3), Charsets.US_ASCII))
        assertEquals("wav", audioPart.jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test fun `unresolvable ref throws CapabilityMissing`() {
        val media = object : MediaSource { override fun resolveAudio(ref: String) = null }
        val adapter = OpenAiChatCompletionsAdapter(media)
        val req = budgeted(listOf(InputPart.Text("hi"), InputPart.Audio("bad-ref")))
        assertFailsWith<ContractError.CapabilityMissing> { adapter.encode(profile, req) }
    }

    @Test fun `wav wrap produces valid header`() {
        val pcm = ByteArray(640) // 20ms at 16kHz mono 16bit
        val wav = OpenAiChatCompletionsAdapter.wavWrap(pcm)
        assertEquals(44 + 640, wav.size)
        assertEquals("RIFF", String(wav.sliceArray(0..3), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.sliceArray(8..11), Charsets.US_ASCII))
    }
}
