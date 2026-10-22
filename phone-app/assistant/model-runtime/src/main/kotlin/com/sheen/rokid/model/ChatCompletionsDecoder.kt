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
 *
 * ## The terminal waits for usage when the finish chunk carries none
 *
 * OpenAI's usage-enabled stream ends `finish_reason` chunk → usage-only chunk (`choices: []`) → `[DONE]`; the finish
 * chunk's own `usage` is absent. Emitting the terminal on the finish chunk would both drop the provider's accounting
 * and close the decoder before the usage chunk arrived. So a finish chunk **without** usage records the pending
 * verdict instead, and exactly one terminal is emitted when:
 *
 * 1. a usage-only chunk arrives (the terminal now has the merged accounting),
 * 2. `[DONE]` arrives, or
 * 3. `endOfInput()` is reached.
 *
 * A finish chunk that **does** carry usage keeps the pre-0.3 immediate behaviour; anything after the terminal is a
 * late delivery and is dropped. `[DONE]`/EOF without a finish_reason keep their old verdict (content ⇒ `COMPLETED`,
 * no content ⇒ `INCOMPLETE`/`EOF_INCOMPLETE`), with whatever usage was reported.
 *
 * Usage objects from more than one chunk are merged field by field: a later non-null counter replaces an earlier one,
 * and a counter nobody reported stays `null`. No count is ever derived or fabricated.
 */
class ChatCompletionsDecoder(private val requestId: String) {
    private var ended = false
    private var bytes = 0
    private val limit = 1048576

    private var receivedContent = false

    /** Every usage object this stream reported, merged field by field; `null` until the provider reports any. */
    private var reportedUsage: ModelUsage? = null

    /** A `finish_reason` whose chunk carried no usage: its terminal is withheld until usage or the stream end. */
    private var pendingEnd: ModelEnd? = null
    private var pendingCode: String? = null

    fun accept(frame: SseFrame): List<ModelStreamEvent> {
        if (ended) return emptyList()
        // "[DONE]" sentinel ends the stream — treat as COMPLETED if we received content
        if (frame.data.trim() == "[DONE]") return finishAtStreamEnd()
        val size = frame.data.toByteArray(Charsets.UTF_8).size
        if (bytes + size > limit) return terminal(ModelEnd.FAILED, null, "RESPONSE_LIMIT")
        bytes += size
        return try {
            val root = Json.parseToJsonElement(frame.data) as? JsonObject ?: return emptyList()
            // A usage-only chunk has `choices: []` (or no choices member at all); it carries no deltas but its usage
            // is still the provider's accounting for the request.
            val choices = root["choices"]?.jsonArray ?: JsonArray(emptyList())
            val chunkUsage = usage(root)
            if (chunkUsage != null) reportedUsage = mergeUsage(reportedUsage, chunkUsage)
            val events = mutableListOf<ModelStreamEvent>()
            var finishEnd: ModelEnd? = null
            var finishCode: String? = null
            for (choice in choices) {
                val obj = choice as? JsonObject ?: continue
                val delta = obj["delta"] as? JsonObject
                // Qwen-Omni returns both reasoning_content and content; only emit content as text deltas
                val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (content != null && content.isNotEmpty()) {
                    receivedContent = true
                    events += ModelStreamEvent.TextDelta(requestId, content)
                }
                // The response's verdict: the first finish_reason wins; a second choice cannot add a second terminal.
                val finishReason = obj["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason != null && finishEnd == null) {
                    finishEnd = when (finishReason) {
                        "stop" -> ModelEnd.COMPLETED
                        "length" -> ModelEnd.INCOMPLETE
                        else -> ModelEnd.FAILED
                    }
                    finishCode = if (finishEnd == ModelEnd.FAILED) "PROVIDER_FAILED" else null
                }
            }
            val heldEnd = pendingEnd
            val heldCode = pendingCode
            when {
                // A finish chunk that carries usage keeps the immediate terminal of the pre-0.3 behaviour.
                finishEnd != null && chunkUsage != null -> events += terminal(finishEnd, reportedUsage, finishCode)
                // A finish chunk without usage withholds the verdict until the usage chunk, [DONE] or EOF.
                finishEnd != null -> {
                    pendingEnd = finishEnd
                    pendingCode = finishCode
                }
                // The usage-only chunk that follows a held finish completes it.
                chunkUsage != null && heldEnd != null -> events += terminal(heldEnd, reportedUsage, heldCode)
            }
            events
        } catch (_: Exception) {
            terminal(ModelEnd.FAILED, null, "MALFORMED_JSON")
        }
    }

    fun endOfInput(): List<ModelStreamEvent> = finishAtStreamEnd()

    /** One terminal for either `[DONE]` or EOF: the held finish when there is one, else the old content-based verdict. */
    private fun finishAtStreamEnd(): List<ModelStreamEvent> {
        if (ended) return emptyList()
        pendingEnd?.let { end -> return terminal(end, reportedUsage, pendingCode) }
        // If we received content but no finish_reason/[DONE], treat as completed (some providers omit it)
        return if (receivedContent) terminal(ModelEnd.COMPLETED, reportedUsage, null)
        else terminal(ModelEnd.INCOMPLETE, reportedUsage, "EOF_INCOMPLETE")
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

    /**
     * Field-wise merge of two usage reports: a counter the later report carries wins, and one it omits keeps the
     * earlier count. A counter neither report carries stays absent — merging never manufactures a zero.
     */
    private fun mergeUsage(earlier: ModelUsage?, later: ModelUsage?): ModelUsage? = when {
        earlier == null -> later
        later == null -> earlier
        else -> ModelUsage(
            inputTokens = later.inputTokens ?: earlier.inputTokens,
            outputTokens = later.outputTokens ?: earlier.outputTokens,
            cachedInputTokens = later.cachedInputTokens ?: earlier.cachedInputTokens,
            cacheMissInputTokens = later.cacheMissInputTokens ?: earlier.cacheMissInputTokens,
        )
    }

    private fun terminal(end: ModelEnd, usage: ModelUsage?, code: String?): List<ModelStreamEvent> {
        if (ended) return emptyList()
        ended = true
        pendingEnd = null
        pendingCode = null
        return listOf(ModelStreamEvent.Terminal(requestId, end, usage, code))
    }
}
