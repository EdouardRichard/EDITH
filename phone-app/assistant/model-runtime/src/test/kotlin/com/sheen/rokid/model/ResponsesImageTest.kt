package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 0.25 spec §4.10 / D9: Responses carries images on `user` messages and inside a tool result.
 *
 * A `function_call_output.output` stays the plain string it has always been when the tool produced no image. With
 * images it becomes the documented content-item array — `input_text` followed by one `input_image` per image, the
 * `call_id` unchanged — because the tool result is the only place a tool's image may go. The current turn's
 * `InputPart.Image` becomes an `input_image` content item on its user message, beside its text. An image ref that does
 * not resolve refuses the whole request: an empty or dropped image must never reach the wire.
 *
 * These tests drive the production `encode(profile, budgeted)` overload (the live path through `ControlledModelClient`),
 * not the legacy frame overload.
 */
class ResponsesImageTest {
    private val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
    private val media = object : MediaSource {
        override fun resolveAudio(ref: String): ResolvedAudio? = null
        override fun resolveImage(artifactId: String): ResolvedImage? = when (artifactId) {
            "img-1" -> ResolvedImage(imageBytes, "image/jpeg", 100, 50)
            else -> null
        }
    }
    private val adapter = DeepSeekResponsesAdapter(media)
    private val profile = ModelProfile(
        "p1", 1, "Responses Test", "service", "https://example.test",
        "DEEPSEEK_RESPONSES", "deepseek-flash",
        object : SecretHandle { override val id = "test" },
        65_536, 60_000, 1024, 128, 10_000,
        setOf(InputModality.TEXT, InputModality.IMAGE), setOf(OutputModality.TEXT),
        emptyList(),
    )
    private val imageRef = ImageRef("img-1", "image/jpeg", 100, 50, "sha256-image")

    private fun encode(
        history: List<ModelHistoryItem> = emptyList(),
        input: List<InputPart> = listOf(InputPart.Text("what is in it?")),
    ): JsonArray {
        val request = ModelRequest(
            EventEnvelope(SessionId("s"), Generation(1), "e", 0, 1),
            "q", "t", profile.id, profile.revision, "instructions", input, history, OutputModality.TEXT, 10_000,
        )
        val budgeted = ContextBudgeter().prepare(profile, request)
        val body = Json.parseToJsonElement(String(adapter.encode(profile, budgeted), Charsets.UTF_8)).jsonObject
        return body.getValue("input").jsonArray
    }

    private fun JsonArray.functionCallOutput(): JsonObject = last {
        it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
    }.jsonObject

    private fun JsonObject.type(): String = getValue("type").jsonPrimitive.content

    private fun decodeDataUrl(url: String): ByteArray = Base64.getDecoder().decode(url.substringAfter(','))

    private fun assertImageItem(item: JsonElement) {
        val part = item.jsonObject
        assertEquals("input_image", part.type())
        assertEquals("auto", part.getValue("detail").jsonPrimitive.content, "detail is stated explicitly (§4.10)")
        val url = part.getValue("image_url").jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"), "a data URL is sent: $url")
        assertContentEquals(imageBytes, decodeDataUrl(url))
    }

    @Test
    fun `a tool output with an image becomes a text and image content item array`() {
        val input = encode(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "screenshot the screen"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Taking it."),
                ModelHistoryItem.ToolCall("call_shot", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput("call_shot", "captured", listOf(imageRef)),
            ),
        )

        val output = input.functionCallOutput()
        assertEquals("call_shot", output.getValue("call_id").jsonPrimitive.content, "the call id keeps answering the call")
        val items = output.getValue("output").jsonArray
        assertEquals(2, items.size, "one input_text and one input_image, in that order: $items")
        assertEquals("input_text", items[0].jsonObject.type())
        assertEquals("captured", items[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertImageItem(items[1])
    }

    @Test
    fun `a tool output without images keeps its output a plain string`() {
        val input = encode(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "screenshot the screen"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Taking it."),
                ModelHistoryItem.ToolCall("call_shot", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput("call_shot", "captured"),
            ),
        )

        val output = input.functionCallOutput()
        assertEquals(setOf("type", "call_id", "output"), output.keys, "the pre-0.25 item shape is unchanged")
        val value = output.getValue("output")
        assertTrue(value is JsonPrimitive && value.isString, "a text-only tool result is still a string: $value")
        assertEquals("captured", value.jsonPrimitive.content)
    }

    @Test
    fun `the current input image becomes an input image part on the user message`() {
        val input = encode(input = listOf(InputPart.Text("describe"), InputPart.Image("img-1")))

        assertEquals(1, input.size, "the current turn is one user message: $input")
        val user = input[0].jsonObject
        assertEquals("user", user.getValue("role").jsonPrimitive.content)
        val parts = user.getValue("content").jsonArray
        assertEquals(2, parts.size, "text and image share the one user message: $parts")
        assertEquals("input_text", parts[0].jsonObject.type())
        assertEquals("describe", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertImageItem(parts[1])
    }

    @Test
    fun `an unresolvable current input image is refused instead of sent empty`() {
        assertFailsWith<ContractError.CapabilityMissing> {
            encode(input = listOf(InputPart.Text("describe"), InputPart.Image("gone")))
        }
    }

    @Test
    fun `an unresolvable image in a tool output becomes a visible text placeholder, not a refusal`() {
        // 0.25 Task 13 / R71: a historical image whose artifact expired degrades to a visible placeholder. Only the
        // current input may still refuse (`CapabilityMissing`), because that is the turn being asked.
        val input = encode(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "screenshot the screen"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Taking it."),
                ModelHistoryItem.ToolCall("call_shot", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput(
                    "call_shot", "captured",
                    listOf(ImageRef("gone", "image/jpeg", 100, 50, "sha256-gone")),
                ),
            ),
        )

        val output = input.functionCallOutput()
        val items = output.getValue("output").jsonArray
        assertEquals(2, items.size, "the tool's text and one visible placeholder: $items")
        assertEquals("input_text", items[0].jsonObject.type())
        assertEquals("captured", items[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("input_text", items[1].jsonObject.type())
        assertEquals(
            "[image unavailable: gone]",
            items[1].jsonObject.getValue("text").jsonPrimitive.content,
        )
    }
}
