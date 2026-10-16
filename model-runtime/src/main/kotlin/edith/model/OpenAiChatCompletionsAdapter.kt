package edith.model

import edith.agent.RequestFrame
import edith.agent.SegmentOrigin
import edith.contracts.ContractError
import edith.plugin.*
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
 * a request whose audio ref cannot be resolved — sending an empty audio payload would silently degrade to text-only
 * at the model side, which violates the capability gate's intent.
 */
class OpenAiChatCompletionsAdapter(private val media: MediaSource) {
    val support = ProtocolSupport("OPENAI_CHAT_COMPLETIONS",
        setOf(InputModality.TEXT, InputModality.AUDIO), setOf(OutputModality.TEXT))

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
                        SegmentOrigin.PERCEPTION -> add(buildJsonObject {
                            put("role", "user")
                            put("content", segment.text)
                        })
                        SegmentOrigin.USER_INPUT -> {
                            // Final user message with optional audio
                            val contentParts = buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", segment.text)
                                })
                                resolvedAudios.forEach { audio ->
                                    add(buildJsonObject {
                                        put("type", "input_audio")
                                        put("input_audio", buildJsonObject {
                                            put("data", Base64.getEncoder().encodeToString(wavWrap(audio.bytes)))
                                            put("format", "wav")
                                        })
                                    })
                                }
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
        val audioParts = budgeted.request.input.filterIsInstance<InputPart.Audio>()
        val textParts = budgeted.request.input.filterIsInstance<InputPart.Text>()

        // Resolve all audio refs first; refuse if any is unresolvable (spec §A4 / Review Focus 5).
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
            put("messages", buildJsonArray {
                // History
                budgeted.request.history.forEach { item ->
                    when (item) {
                        is ModelHistoryItem.Text -> add(buildJsonObject {
                            put("role", item.role.name.lowercase())
                            put("content", item.text)
                        })
                        else -> { /* tool calls not yet supported for this protocol */ }
                    }
                }
                // Context
                budgeted.request.context.forEach { add(buildJsonObject {
                    put("role", "user"); put("content", it.text)
                }) }
                // Current turn: text + optional audio
                val contentParts = buildJsonArray {
                    textParts.forEach { add(buildJsonObject {
                        put("type", "text"); put("text", it.text)
                    }) }
                    resolvedAudios.forEach { audio ->
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject {
                                put("data", Base64.getEncoder().encodeToString(wavWrap(audio.bytes)))
                                put("format", "wav")
                            })
                        })
                    }
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", contentParts)
                })
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
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
