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
                    val usage = usage(root)
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

    /**
     * 0.3 design §5.3: the provider's cache telemetry, read from whichever member the protocol reports.
     *
     * OpenAI nests the hit count under `prompt_tokens_details.cached_tokens`; DeepSeek reports
     * `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` at the usage top level. Every member is optional and an
     * absent one stays `null` — no count is fabricated, and the miss count is only ever read from its own member.
     */
    private fun usage(root: JsonObject): ModelUsage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val details = usage["prompt_tokens_details"] as? JsonObject
        return ModelUsage(
            inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull,
            outputTokens = usage["completion_tokens"]?.jsonPrimitive?.longOrNull,
            cachedInputTokens = details?.get("cached_tokens")?.jsonPrimitive?.longOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.longOrNull,
            cacheMissInputTokens = usage["prompt_cache_miss_tokens"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun terminal(end: ModelEnd, usage: ModelUsage?, code: String?): List<ModelStreamEvent> {
        if (ended) return emptyList()
        ended = true
        return listOf(ModelStreamEvent.Terminal(requestId, end, usage, code))
    }
}
