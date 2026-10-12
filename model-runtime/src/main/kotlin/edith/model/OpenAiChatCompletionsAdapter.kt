package edith.model

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
