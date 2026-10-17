package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
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

    private var receivedContent = false

    fun accept(frame: SseFrame): List<ModelStreamEvent> {
        if (ended) return emptyList()
        // "[DONE]" sentinel ends the stream — treat as COMPLETED if we received content
        if (frame.data.trim() == "[DONE]") {
            return if (receivedContent) terminal(ModelEnd.COMPLETED, null, null)
            else terminal(ModelEnd.INCOMPLETE, null, "EOF_INCOMPLETE")
        }
        val size = frame.data.toByteArray(Charsets.UTF_8).size
        if (bytes + size > limit) return terminal(ModelEnd.FAILED, null, "RESPONSE_LIMIT")
        bytes += size
        return try {
            val root = Json.parseToJsonElement(frame.data) as? JsonObject ?: return emptyList()
            val choices = root["choices"]?.jsonArray ?: return emptyList()
            val events = mutableListOf<ModelStreamEvent>()
            for (choice in choices) {
                val obj = choice as? JsonObject ?: continue
                val delta = obj["delta"] as? JsonObject
                // Qwen-Omni returns both reasoning_content and content; only emit content as text deltas
                val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (content != null && content.isNotEmpty()) {
                    receivedContent = true
                    events += ModelStreamEvent.TextDelta(requestId, content)
                }
                val finishReason = obj["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason != null) {
                    val usage = (root["usage"] as? JsonObject)?.let {
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
        // If we received content but no finish_reason/[DONE], treat as completed (some providers omit it)
        return if (receivedContent) terminal(ModelEnd.COMPLETED, null, null)
        else terminal(ModelEnd.INCOMPLETE, null, "EOF_INCOMPLETE")
    }

    private fun terminal(end: ModelEnd, usage: ModelUsage?, code: String?): List<ModelStreamEvent> {
        if (ended) return emptyList()
        ended = true
        return listOf(ModelStreamEvent.Terminal(requestId, end, usage, code))
    }
}
