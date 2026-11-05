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
/** 0.53 review: the stable code for a completed stream whose call never received both its id and its name. */
private const val TOOL_CALL_DELIVERY_INCOMPLETE = "TOOL_CALL_DELIVERY_INCOMPLETE"

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

    /**
     * 0.53 Task 9 prerequisite: the tool calls this stream is assembling. Chat has no item id of its own, so a call is
     * keyed by its `tool_calls[].index`, and the stable `chat:<index>` identity is used for both the delta and the
     * terminal event (the gateway fails a turn whose delta identity never gets a terminal one).
     */
    private class StreamingCall(val index: Int) {
        var callId: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var terminalEmitted = false
    }
    private val calls = linkedMapOf<Int, StreamingCall>()

    fun accept(frame: SseFrame): List<ModelStreamEvent> {
        if (ended) return emptyList()
        // "[DONE]" sentinel ends the stream — treat as COMPLETED if we received content
        if (frame.data.trim() == "[DONE]") return finishAtStreamEnd(providerSaidDone = true)
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
                // 0.53: DeepSeek streams its chain of thought as `delta.reasoning_content` beside `delta.content`.
                // It is not answer text: it is surfaced as a ReasoningDelta so the client can store it with the
                // assistant turn and replay it, and it never marks the turn as having delivered text.
                val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                // A whitespace-only fragment is not a chain of thought; emitting it would throw in ReasoningDelta's
                // constructor (non-blank required) and the outer catch would report MALFORMED_JSON for a valid turn.
                if (reasoning != null && reasoning.isNotBlank()) {
                    events += ModelStreamEvent.ReasoningDelta(requestId, reasoning)
                }
                // Qwen-Omni returns both reasoning_content and content; only emit content as text deltas
                val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (content != null && content.isNotEmpty()) {
                    receivedContent = true
                    events += ModelStreamEvent.TextDelta(requestId, content)
                }
                // 0.53 Task 9 prerequisite: Chat streams a call across chunks; assemble it here.
                delta?.get("tool_calls")?.let { toolCalls ->
                    val array = toolCalls as? JsonArray ?: return@let
                    for (element in array) {
                        val fragment = element as? JsonObject ?: continue
                        val index = fragment["index"]?.jsonPrimitive?.intOrNull ?: continue
                        val call = calls.getOrPut(index) { StreamingCall(index) }
                        fragment["id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }?.let { call.callId = it }
                        val function = fragment["function"] as? JsonObject
                        function?.get("name")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }?.let { call.name = it }
                        val arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                        if (!arguments.isNullOrEmpty()) {
                            call.arguments.append(arguments)
                            events += ModelStreamEvent.ToolCallDelta(requestId, itemId(call), call.name, arguments)
                        }
                    }
                }
                // The response's verdict: the first finish_reason wins; a second choice cannot add a second terminal.
                val finishReason = obj["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason != null && finishEnd == null) {
                    finishEnd = when (finishReason) {
                        "stop" -> ModelEnd.COMPLETED
                        // 0.53 §T10: a tool-call turn is a successful model turn. The pre-0.53 unknown branch made
                        // every Chat tool round fail before the tool could run.
                        "tool_calls" -> ModelEnd.COMPLETED
                        "length" -> ModelEnd.INCOMPLETE
                        else -> ModelEnd.FAILED
                    }
                    finishCode = if (finishEnd == ModelEnd.FAILED) "PROVIDER_FAILED" else null
                }
            }
            // 0.53: the finish chunk is the point at which every assembled call is complete — but only a **successful**
            // finish completes a call. `length`/an unknown finish cut it off, so the calls stay dangling. A call that
            // never received both its id and its name is a delivery failure, never a silent success: refusing here
            // keeps the gateway's dangling-delta check honest even when answer text was streamed.
            // `[DONE]`/EOF without a finish_reason complete the calls in [finishAtStreamEnd] instead.
            if (finishEnd != null) {
                when {
                    finishEnd == ModelEnd.COMPLETED && calls.values.any { !it.terminalEmitted && !it.identified() } -> {
                        finishEnd = ModelEnd.INCOMPLETE
                        finishCode = TOOL_CALL_DELIVERY_INCOMPLETE
                    }
                    finishEnd == ModelEnd.COMPLETED -> events += callTerminals()
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

    /** `chat:<index>`: the stable identity shared by a call's deltas and its terminal (Chat has no item id). */
    private fun itemId(call: StreamingCall) = "chat:${call.index}"

    /** A call is only replayable once the provider gave it both the id and the name that pair it with its output. */
    private fun StreamingCall.identified() = callId != null && name != null

    /**
     * 0.53: the terminal event of every call whose id and name are known and whose terminal has not gone out yet.
     * A call missing either stays a dangling delta instead of being invented, so a truncated stream cannot read as a
     * successful turn.
     */
    private fun callTerminals(): List<ModelStreamEvent> {
        val terminals = mutableListOf<ModelStreamEvent>()
        for (call in calls.values) {
            if (call.terminalEmitted || !call.identified()) continue
            call.terminalEmitted = true
            terminals += ModelStreamEvent.ToolCallTerminal(requestId, itemId(call), call.callId!!, call.name!!, call.arguments.toString())
        }
        return terminals
    }

    fun endOfInput(): List<ModelStreamEvent> = finishAtStreamEnd(providerSaidDone = false)

    /**
     * One terminal for either `[DONE]` or EOF: the held finish when there is one, else the old content-based verdict.
     *
     * 0.53: a tool call only completes when the provider itself said the turn ended **successfully** — a completing
     * `finish_reason` chunk or the `[DONE]` sentinel. Bare EOF after a half-streamed call is a truncated delivery:
     * completing it would hand the loop a call whose arguments were cut off, so it stays a dangling delta. A completed
     * stream carrying a call without its id/name is refused with [TOOL_CALL_DELIVERY_INCOMPLETE].
     */
    private fun finishAtStreamEnd(providerSaidDone: Boolean): List<ModelStreamEvent> {
        if (ended) return emptyList()
        if (providerSaidDone && pendingEnd == null && calls.values.any { !it.terminalEmitted && !it.identified() }) {
            return terminal(ModelEnd.INCOMPLETE, reportedUsage, TOOL_CALL_DELIVERY_INCOMPLETE)
        }
        // Only a success verdict completes the calls; `length`/`failed` keep them dangling.
        val completedCalls = if (providerSaidDone && pendingEnd != ModelEnd.INCOMPLETE && pendingEnd != ModelEnd.FAILED) {
            callTerminals()
        } else {
            emptyList()
        }
        pendingEnd?.let { end -> return completedCalls + terminal(end, reportedUsage, pendingCode) }
        // If we received content but no finish_reason/[DONE], treat as completed (some providers omit it). 0.53: a
        // completed tool call is a payload too, so a call-only stream the provider ended is accepted the same way.
        return completedCalls + if (receivedContent || completedCalls.isNotEmpty()) {
            terminal(ModelEnd.COMPLETED, reportedUsage, null)
        } else {
            terminal(ModelEnd.INCOMPLETE, reportedUsage, "EOF_INCOMPLETE")
        }
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
