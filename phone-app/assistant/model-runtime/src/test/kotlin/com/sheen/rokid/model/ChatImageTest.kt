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
 * 0.25 spec §4.10 / D9: Chat Completions carries images only on `user` messages. A tool result's images are therefore
 * split off its `role:"tool"` message (which must stay a text string) into a following `user` message, and the current
 * input's `InputPart.Image` becomes an `image_url` content part.
 */
class ChatImageTest {
    private val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
    private val media = object : MediaSource {
        override fun resolveAudio(ref: String): ResolvedAudio? = null
        override fun resolveImage(artifactId: String): ResolvedImage? = when (artifactId) {
            "img-1" -> ResolvedImage(imageBytes, "image/jpeg", 100, 50)
            else -> null
        }
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
    private val imageRef = ImageRef("img-1", "image/jpeg", 100, 50, "sha256-image")

    private fun encode(
        history: List<ModelHistoryItem> = emptyList(),
        input: List<InputPart> = listOf(InputPart.Text("what is in it?")),
    ): JsonArray {
        val request = ModelRequest(
            EventEnvelope(SessionId("s"), Generation(1), "e", 0, 1),
            "q", "t", "p1", 1, "instructions", input, history, OutputModality.TEXT, 10_000,
        )
        val budgeted = BudgetedRequest(request, 0, emptyList(), true)
        val body = Json.parseToJsonElement(String(adapter.encode(profile, budgeted), Charsets.UTF_8)).jsonObject
        return body.getValue("messages").jsonArray
    }

    private fun JsonObject.parts(): JsonArray = getValue("content").jsonArray

    private fun imageUrlPart(part: JsonElement): JsonObject = part.jsonObject.getValue("image_url").jsonObject

    private fun decodeDataUrl(url: String): ByteArray = Base64.getDecoder().decode(url.substringAfter(','))

    @Test
    fun `support advertises image input beside text and audio`() {
        assertEquals(
            setOf(InputModality.TEXT, InputModality.IMAGE, InputModality.AUDIO),
            adapter.support.inputs,
        )
    }

    @Test
    fun `a tool output with an image keeps the tool message text only and follows it with a user image message`() {
        val messages = encode(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "screenshot the screen"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Taking it."),
                ModelHistoryItem.ToolCall("call_shot", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput("call_shot", "captured", listOf(imageRef)),
            ),
        )

        assertEquals(5, messages.size, "user, assistant(tool_calls), tool, user image, current user: $messages")
        assertEquals("assistant", messages[1].jsonObject.getValue("role").jsonPrimitive.content)

        val tool = messages[2].jsonObject
        assertEquals("tool", tool.getValue("role").jsonPrimitive.content)
        assertEquals("call_shot", tool.getValue("tool_call_id").jsonPrimitive.content)
        assertTrue(
            tool.getValue("content") is JsonPrimitive && tool.getValue("content").jsonPrimitive.isString,
            "a tool message content must stay a text string: ${tool.getValue("content")}",
        )
        assertEquals("captured", tool.getValue("content").jsonPrimitive.content)

        val imageMessage = messages[3].jsonObject
        assertEquals("user", imageMessage.getValue("role").jsonPrimitive.content, "the image follows in a user message")
        val imageParts = imageMessage.parts().filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "image_url"
        }
        assertEquals(1, imageParts.size)
        val imageUrl = imageUrlPart(imageParts[0])
        assertEquals(setOf("url"), imageUrl.keys, "detail is left at the API default")
        val url = imageUrl.getValue("url").jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"), "a data URL is sent: $url")
        assertContentEquals(imageBytes, decodeDataUrl(url))

        assertEquals("user", messages[4].jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `images from parallel calls follow every tool reply of the batch`() {
        val messages = encode(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "look at both"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "Checking."),
                ModelHistoryItem.ToolCall("call_a", "observe", "{}"),
                ModelHistoryItem.ToolCall("call_b", "screenshot", "{}"),
                ModelHistoryItem.ToolOutput("call_a", "captured a", listOf(imageRef)),
                ModelHistoryItem.ToolOutput("call_b", "captured b"),
            ),
        )

        // user, assistant(tool_calls=[a,b]), tool a, tool b, user image, current user. The image must not be inserted
        // between the assistant's tool_calls and one of its tool replies (D9 §2.2 pairing rule).
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "user", "user"),
            messages.map { it.jsonObject.getValue("role").jsonPrimitive.content },
            "wire order: $messages",
        )
        assertEquals("call_a", messages[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("call_b", messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        val imagePart = messages[4].jsonObject.parts().single {
            it.jsonObject.getValue("type").jsonPrimitive.content == "image_url"
        }
        assertContentEquals(imageBytes, decodeDataUrl(imageUrlPart(imagePart).getValue("url").jsonPrimitive.content))
    }

    @Test
    fun `the current input image is encoded on the user message as a data URL`() {
        val messages = encode(input = listOf(InputPart.Text("describe"), InputPart.Image("img-1")))

        val user = messages.last().jsonObject
        assertEquals("user", user.getValue("role").jsonPrimitive.content)
        val parts = user.parts()
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("describe", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject.getValue("type").jsonPrimitive.content)
        val url = imageUrlPart(parts[1]).getValue("url").jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"), "a data URL is sent: $url")
        assertContentEquals(imageBytes, decodeDataUrl(url))
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
        val messages = encode(
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

        // The tool message stays a string and the image message follows it, carrying a text part instead of bytes.
        assertEquals(5, messages.size, "user, assistant(tool_calls), tool, placeholder user, current user: $messages")
        assertEquals("tool", messages[2].jsonObject.getValue("role").jsonPrimitive.content)
        val placeholderMessage = messages[3].jsonObject
        assertEquals("user", placeholderMessage.getValue("role").jsonPrimitive.content)
        val parts = placeholderMessage.parts()
        assertEquals(1, parts.size, "no image part may be sent for a missing artifact: $parts")
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "[image unavailable: gone]",
            parts[0].jsonObject.getValue("text").jsonPrimitive.content,
        )
    }
}
