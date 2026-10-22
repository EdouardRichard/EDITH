package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Encoder for the OpenAI Chat Completions API with optional audio input (spec §A3).
 *
 * Protocol id is `OPENAI_CHAT_COMPLETIONS` (family name, not vendor name). Audio is sent as base64-encoded WAV
 * inside `input_audio` content parts; text-only requests omit the audio part entirely. The adapter refuses to encode
 * a request whose audio or current-input image ref cannot be resolved — sending an empty payload would silently degrade
 * to text-only at the model side, which violates the capability gate's intent. A **historical** tool image whose
 * artifact expired is different (R71): it becomes a visible `[image unavailable: …]` text part so the rest of the
 * request still reaches the model.
 *
 * 0.25 (§4.10) adds images and tool history to the budgeted (live) path: the history's tool round-trip is rendered as
 * `assistant.tool_calls` + one `role:"tool"` message per output, and images — from the current input or a tool result —
 * are `image_url` parts on `user` messages, the only role that may carry them.
 */
class OpenAiChatCompletionsAdapter(private val media: MediaSource) {
    val support = ProtocolSupport("OPENAI_CHAT_COMPLETIONS",
        setOf(InputModality.TEXT, InputModality.IMAGE, InputModality.AUDIO), setOf(OutputModality.TEXT))

    /**
     * Encodes a [RequestFrame] into the OpenAI Chat Completions API wire format (spec §3.2 / Phase 3).
     *
     * Fixes D1 (system message was missing) and D2 (tool round-trips were silently dropped).
     * The system slot becomes the first message with `role: "system"`. Memory evidence appears as
     * user messages before the final user input. Tool rounds are rendered as assistant tool_calls
     * followed by tool role messages.
     */
    fun encode(profile: ModelProfile, frame: RequestFrame, audioParts: List<InputPart.Audio> = emptyList()): ByteArray {
        require(profile.protocolId == support.id)

        val resolvedAudios = audioParts.map { part ->
            media.resolveAudio(part.localRef)
                ?: throw ContractError.CapabilityMissing("audio ref unresolvable: ${part.localRef}")
        }

        val body = buildJsonObject {
            put("model", profile.model)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("modalities", buildJsonArray { add("text") })
            put("max_tokens", profile.maxOutputTokens)
            if (frame.tools.isNotEmpty()) {
                put("tools", buildJsonArray { frame.tools.forEach { add(toolDescriptor(it)) } })
                put("tool_choice", "auto")
            }
            put("messages", buildJsonArray {
                // D1 FIX: System message — was completely absent before
                add(buildJsonObject {
                    put("role", "system")
                    put("content", frame.systemText)
                })
                // Conversation flow segments in order
                for (segment in frame.conversationFlow) {
                    when (segment.origin) {
                        SegmentOrigin.TRANSCRIPT -> add(buildJsonObject {
                            put("role", "user") // Transcript segments default to user; TurnRunner structures these
                            put("content", segment.text)
                        })
                        SegmentOrigin.SUMMARY -> add(buildJsonObject {
                            put("role", "user")
                            put("content", segment.text)
                        })
                        SegmentOrigin.MEMORY_EVIDENCE -> add(buildJsonObject {
                            put("role", "user")
                            put("content", segment.text)
                        })
                        SegmentOrigin.PERCEPTION, SegmentOrigin.SKILL_BODY -> add(buildJsonObject {
                            put("role", "user")
                            put("content", segment.text)
                        })
                        SegmentOrigin.USER_INPUT -> {
                            // Final user message with optional audio
                            val contentParts = buildJsonArray {
                                add(textPart(segment.text))
                                resolvedAudios.forEach { add(audioPart(it)) }
                            }
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", contentParts)
                            })
                        }
                        SegmentOrigin.TOOL_ROUND -> {
                            // D2 FIX: Tool round-trips — were silently dropped before
                            parseToolRoundForOpenAi(segment.text)?.forEach { add(it) }
                        }
                        SegmentOrigin.RETRY -> add(buildJsonObject {
                            put("role", "user")
                            put("content", segment.text)
                        })
                        else -> add(buildJsonObject {
                            put("role", "user")
                            put("content", segment.text)
                        })
                    }
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses a TOOL_ROUND segment into OpenAI-format messages: assistant with tool_calls + tool role responses.
     */
    private fun parseToolRoundForOpenAi(text: String): List<JsonObject>? = try {
        val array = Json.parseToJsonElement(text) as? JsonArray ?: return null
        val calls = mutableListOf<JsonObject>()
        val outputs = mutableListOf<JsonObject>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.content) {
                "call" -> calls.add(buildJsonObject {
                    put("id", obj["callId"]?.jsonPrimitive?.content ?: "")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", obj["name"]?.jsonPrimitive?.content ?: "")
                        put("arguments", obj["argsJson"]?.jsonPrimitive?.content ?: "{}")
                    })
                })
                "output" -> outputs.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", obj["callId"]?.jsonPrimitive?.content ?: "")
                    put("content", obj["output"]?.jsonPrimitive?.content ?: "")
                })
            }
        }
        val result = mutableListOf<JsonObject>()
        if (calls.isNotEmpty()) {
            result.add(buildJsonObject {
                put("role", "assistant")
                put("content", JsonNull)
                put("tool_calls", buildJsonArray { calls.forEach { add(it) } })
            })
        }
        result.addAll(outputs)
        result
    } catch (_: Exception) { null }

    private fun toolDescriptor(spec: ToolSpec) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", try {
                Json.parseToJsonElement(spec.parametersJson)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Tool parameters are not valid JSON")
            })
        })
    }

    fun encode(profile: ModelProfile, budgeted: BudgetedRequest): ByteArray {
        require(profile.protocolId == support.id)
        val textParts = budgeted.request.input.filterIsInstance<InputPart.Text>()
        val imageParts = budgeted.request.input.filterIsInstance<InputPart.Image>()
        val audioParts = budgeted.request.input.filterIsInstance<InputPart.Audio>()

        // Resolve all refs first; refuse if any is unresolvable (spec §A4 / Review Focus 5). A history image is
        // resolved by [historyToMessages] under the same refusal.
        val resolvedImages = imageParts.map { part ->
            media.resolveImage(part.localRef)
                ?: throw ContractError.CapabilityMissing("image ref unresolvable: ${part.localRef}")
        }
        val resolvedAudios = audioParts.map { part ->
            media.resolveAudio(part.localRef)
                ?: throw ContractError.CapabilityMissing("audio ref unresolvable: ${part.localRef}")
        }

        val body = buildJsonObject {
            put("model", profile.model)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("modalities", buildJsonArray { add("text") })
            // 0.3 design §5.2: the turn policy's cap wins; the profile's value is only the window reservation, used
            // when the request declares no cap of its own (every pre-0.3 caller).
            put("max_tokens", budgeted.request.maxOutputTokens ?: profile.maxOutputTokens)
            put("messages", buildJsonArray {
                // History, including the tool round-trip the stateless endpoint needs echoed back.
                historyToMessages(budgeted.request.history).forEach { add(it) }
                // Current turn: text + optional image + optional audio. An empty input is a history-only continuation
                // (the request that follows a tool round): the tool output stays last and no empty user message is
                // appended after it.
                if (budgeted.request.input.isNotEmpty()) {
                    val contentParts = buildJsonArray {
                        textParts.forEach { add(textPart(it.text)) }
                        resolvedImages.forEach { add(imagePart(it)) }
                        resolvedAudios.forEach { add(audioPart(it)) }
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", contentParts)
                    })
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    /**
     * Maps a stateless history onto Chat Completions messages (0.25 spec §4.10).
     *
     * Consecutive [ModelHistoryItem.ToolCall]s are one assistant message — the model produced them together, so they
     * are echoed together — and an assistant [ModelHistoryItem.Text] directly before them merges into that message's
     * `content` (the model's own preamble, `null` when it said nothing). Every [ModelHistoryItem.ToolOutput] answers
     * one call with a `role:"tool"` message whose content stays a text string: the wire schema allows no image part
     * there. A tool output's images follow in a `user` message instead, the only role that may carry `image_url`
     * (D9; the schema rejects `role:"tool"` image parts with HTTP 400).
     *
     * The images of a parallel batch wait until every `tool` reply of that batch has gone out. Inserting a user
     * message between assistant(`tool_calls`) and one of its `tool` replies is the pairing Qwen documents as invalid
     * and D9 warns against, so a batch renders as `assistant(tool_calls)` → all `tool` replies → the image messages.
     * A one-output tool round (the common case) is therefore exactly `tool` → user image.
     */
    private fun historyToMessages(history: List<ModelHistoryItem>): JsonArray {
        val messages = mutableListOf<JsonObject>()
        // User messages carrying a finished tool run's images; emitted once that run of replies is complete.
        val pendingImages = mutableListOf<JsonObject>()
        // An assistant text waits for the calls directly after it; it becomes that message's content.
        var assistantText: String? = null
        val calls = mutableListOf<ModelHistoryItem.ToolCall>()

        fun flushImages() {
            if (pendingImages.isEmpty()) return
            messages += pendingImages
            pendingImages.clear()
        }

        fun flushCalls() {
            if (calls.isEmpty()) return
            messages += buildJsonObject {
                put("role", "assistant")
                put("content", assistantText?.let { JsonPrimitive(it) } ?: JsonNull)
                put("tool_calls", JsonArray(calls.map(::assistantToolCall)))
            }
            calls.clear()
            assistantText = null
        }

        fun flushText() {
            val text = assistantText ?: return
            messages += buildJsonObject {
                put("role", "assistant")
                put("content", text)
            }
            assistantText = null
        }

        fun queueImages(output: ModelHistoryItem.ToolOutput) {
            if (output.images.isEmpty()) return
            pendingImages += buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    output.images.forEach { image ->
                        val resolved = media.resolveImage(image.artifactId)
                        // R71: a historical image whose artifact expired degrades visibly; the request still encodes.
                        if (resolved == null) add(textPart(imageUnavailablePlaceholder(image.artifactId)))
                        else add(imagePart(resolved))
                    }
                })
            }
        }

        for (item in history) {
            when (item) {
                is ModelHistoryItem.Text -> {
                    // The tool run is over, so its images may go out before the next turn.
                    flushImages()
                    // Calls left open by a malformed history close here, before the next item.
                    flushCalls()
                    if (item.role == MessageRole.ASSISTANT) {
                        // Two assistant texts in a row: the earlier one is its own turn and goes out now.
                        flushText()
                        assistantText = item.text
                    } else {
                        flushText()
                        messages += buildJsonObject {
                            put("role", "user")
                            put("content", item.text)
                        }
                    }
                }
                is ModelHistoryItem.ToolCall -> {
                    // A call that starts a new assistant message ends the previous reply run.
                    if (calls.isEmpty()) flushImages()
                    calls += item
                }
                is ModelHistoryItem.ToolOutput -> {
                    // Emits the assistant `tool_calls` message first, with the assistant's text merged in.
                    flushCalls()
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", item.callId)
                        put("content", item.output)
                    }
                    queueImages(item)
                }
            }
        }
        // A trailing tool run's images, an assistant text, or an output-less call run still goes out.
        flushImages()
        flushCalls()
        flushText()
        return JsonArray(messages)
    }

    private fun assistantToolCall(call: ModelHistoryItem.ToolCall) = buildJsonObject {
        put("id", call.callId)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", call.name)
            put("arguments", call.argsJson)
        })
    }

    private fun textPart(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** One image as Chat Completions renders it: a `data:` URL on a user-message content part. No `detail` is sent. */
    private fun imagePart(image: ResolvedImage) = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", "data:${image.mime};base64,${Base64.getEncoder().encodeToString(image.bytes)}")
        })
    }

    private fun audioPart(audio: ResolvedAudio) = buildJsonObject {
        put("type", "input_audio")
        put("input_audio", buildJsonObject {
            put("data", Base64.getEncoder().encodeToString(wavWrap(audio.bytes)))
            put("format", "wav")
        })
    }

    companion object {
        /**
         * Wraps raw PCM16/16kHz/mono bytes in a minimal 44-byte WAV header. Pure function, no I/O.
         */
        fun wavWrap(pcm: ByteArray): ByteArray {
            val channels = 1
            val sampleRate = 16000
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val riffSize = 36 + dataSize

            val out = ByteArrayOutputStream(44 + dataSize)
            val le = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            fun writeLE(value: Int) { le.clear(); le.putInt(value); out.write(le.array(), 0, 4) }
            fun writeLEShort(value: Int) { out.write(value and 0xFF); out.write((value shr 8) and 0xFF) }

            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLE(riffSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeLE(16) // chunk size
            writeLEShort(1) // PCM format
            writeLEShort(channels)
            writeLE(sampleRate)
            writeLE(byteRate)
            writeLEShort(blockAlign)
            writeLEShort(bitsPerSample)
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeLE(dataSize)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}
