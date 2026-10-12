package edith.model

import edith.plugin.ModelEnd
import edith.plugin.ModelStreamEvent
import edith.plugin.ModelUsage
import kotlinx.serialization.json.*

/**
 * SSE decoder for the OpenAI Chat Completions streaming format.
 *
 * Each frame's `data` is a JSON object with `choices[].delta.content` for text deltas and
 * `choices[].finish_reason` for the terminal signal. Usage appears in the final chunk when
 * `stream_options.include_usage` is set.
 */
class ChatCompletionsDecoder(private val requestId: String) {
    private var ended = false
    private var bytes = 0
    private val limit = 1048576

    fun accept(frame: SseFrame): List<ModelStreamEvent> {
        if (ended) return emptyList()
        // "[DONE]" sentinel ends the stream
        if (frame.data.trim() == "[DONE]") return endOfInput()
        val size = frame.data.toByteArray(Charsets.UTF_8).size
        if (bytes + size > limit) return terminal(ModelEnd.FAILED, null, "RESPONSE_LIMIT")
        bytes += size
        return try {
            val root = Json.parseToJsonElement(frame.data) as? JsonObject ?: return emptyList()
            val choices = root["choices"]?.jsonArray ?: return emptyList()
            val events = mutableListOf<ModelStreamEvent>()
            for (choice in choices) {
                val obj = choice.jsonObject
                val delta = obj["delta"]?.jsonObject
                val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (content != null && content.isNotEmpty()) {
                    events += ModelStreamEvent.TextDelta(requestId, content)
                }
                val finishReason = obj["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason != null) {
                    val usage = root["usage"]?.jsonObject?.let {
                        ModelUsage(
                            it["prompt_tokens"]?.jsonPrimitive?.longOrNull,
                            it["completion_tokens"]?.jsonPrimitive?.longOrNull,
                        )
                    }
                    val end = when (finishReason) {
                        "stop" -> ModelEnd.COMPLETED
                        "length" -> ModelEnd.INCOMPLETE
                        else -> ModelEnd.FAILED
                    }
                    events += ModelStreamEvent.Terminal(requestId, end, usage,
                        if (end == ModelEnd.FAILED) "PROVIDER_FAILED" else null)
                    ended = true
                }
            }
            events
        } catch (_: Exception) {
            terminal(ModelEnd.FAILED, null, "MALFORMED_JSON")
        }
    }

    fun endOfInput(): List<ModelStreamEvent> {
        if (ended) return emptyList()
        return terminal(ModelEnd.INCOMPLETE, null, "EOF_INCOMPLETE")
    }

    private fun terminal(end: ModelEnd, usage: ModelUsage?, code: String?): List<ModelStreamEvent> {
        if (ended) return emptyList()
        ended = true
        return listOf(ModelStreamEvent.Terminal(requestId, end, usage, code))
    }
}
