package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import kotlinx.serialization.json.*

/** Synthetic-schema compatibility decoder; supplier wire behavior still requires a profile probe. */
class ResponsesDecoder(requestId: String, maxResponseBytes: Int = 1048576) {
    private val request = requestId.also { require(it.isNotBlank()) }
    private val limit = maxResponseBytes.also { require(it > 0) }
    var ignoredSequenceCount: Long = 0
        private set
    private var bytes = 0
    private var sequence: Long? = null
    private var responseId: String? = null
    private var ended = false
    private data class ContentId(val item: String, val index: Long)
    private val text = mutableMapOf<ContentId, StringBuilder>()
    private val done = mutableSetOf<ContentId>()
    private val items = mutableMapOf<String, String>()
    private val outputItems = mutableMapOf<Long, String>()
    private val parts = mutableMapOf<ContentId, String>()
    private val toolCalls = mutableMapOf<String, String>()
    private val toolArgs = mutableMapOf<String, StringBuilder>()
    private val toolNames = mutableMapOf<String, String>()

    fun accept(frame: SseFrame): List<ModelStreamEvent> {
        if (ended) return emptyList()
        if (frame.data.length > limit - bytes) return terminal(ModelEnd.FAILED, null, "RESPONSE_LIMIT")
        val size = frame.data.toByteArray(Charsets.UTF_8).size.toLong() +
            (frame.event?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        if (size > limit - bytes) return terminal(ModelEnd.FAILED, null, "RESPONSE_LIMIT")
        bytes += size.toInt()
        return try {
            val root = Json.parseToJsonElement(frame.data) as? JsonObject ?: invalid("MALFORMED_JSON")
            val type = string(root, "type", required = true)!!
            if (frame.event != null && frame.event != type) invalid("EVENT_TYPE_CONFLICT")
            val current = number(root["sequence_number"], required = true)!!
            if (sequence != null && current <= sequence!!) {
                ignoredSequenceCount++
                emptyList()
            } else {
                sequence = current
                string(root, "response_id")?.let(::bindResponse)
                when (type) {
                    "response.created" -> {
                        val response = objectValue(root["response"], required = true)!!
                        bindResponse(string(response, "id", required = true)!!)
                        val status = string(response, "status")
                        if (status != null && status != "in_progress") invalid("STATUS_CONFLICT")
                        emptyList()
                    }
                    "response.output_item.added", "response.output_item.done" -> trackItem(root)
                    "response.content_part.added", "response.content_part.done" -> trackPart(root)
                    "response.output_text.delta" -> delta(root)
                    "response.output_text.done" -> done(root)
                    "response.function_call_arguments.delta" -> toolDelta(root)
                    "response.completed", "response.incomplete", "response.failed" -> finish(type, root)
                    else -> emptyList()
                }
            }
        } catch (failure: InvalidStream) {
            terminal(ModelEnd.FAILED, null, failure.code)
        } catch (_: IllegalArgumentException) {
            terminal(ModelEnd.FAILED, null, "MALFORMED_JSON")
        } catch (_: StackOverflowError) {
            // kotlinx JsonTreeReader has no configurable depth bound; never surface its stack/body.
            terminal(ModelEnd.FAILED, null, "JSON_DEPTH_LIMIT")
        }
    }

    fun endOfInput(): List<ModelStreamEvent> = if (ended) emptyList() else
        terminal(ModelEnd.INCOMPLETE, null, "EOF_INCOMPLETE")

    private class InvalidStream(val code: String) : RuntimeException()
    private fun invalid(code: String): Nothing = throw InvalidStream(code)

    private fun objectValue(value: JsonElement?, required: Boolean = false): JsonObject? {
        if (value == null || value is JsonNull) {
            if (required) invalid("MISSING_OBJECT")
            return null
        }
        return value as? JsonObject ?: invalid("INVALID_OBJECT")
    }

    private fun string(obj: JsonObject, name: String, required: Boolean = false): String? {
        val value = obj[name]
        if (value == null || value is JsonNull) {
            if (required) invalid("MISSING_FIELD")
            return null
        }
        val primitive = value as? JsonPrimitive ?: invalid("INVALID_FIELD")
        if (!primitive.isString) invalid("INVALID_FIELD")
        return primitive.content
    }

    private fun number(value: JsonElement?, required: Boolean = false): Long? {
        if (value == null || value is JsonNull) {
            if (required) invalid("INVALID_NUMBER")
            return null
        }
        val primitive = value as? JsonPrimitive ?: invalid("INVALID_NUMBER")
        val literal = primitive.content
        if (primitive.isString || literal.isEmpty() || !literal.all { it in '0'..'9' }) invalid("INVALID_NUMBER")
        return primitive.longOrNull ?: invalid("INVALID_NUMBER")
    }

    private fun bindResponse(id: String) {
        if (id.isBlank()) invalid("IDENTITY_CONFLICT")
        if (responseId == null) responseId = id
        if (responseId != id) invalid("IDENTITY_CONFLICT")
    }

    private fun identity(root: JsonObject): ContentId {
        if (responseId == null) invalid("MISSING_RESPONSE_ID")
        val item = string(root, "item_id", required = true)!!
        if (item.isBlank()) invalid("MISSING_CONTENT_ID")
        val index = number(root["content_index"], required = true)!!
        val outputIndex = number(root["output_index"])
        if (outputIndex != null) bindItem(outputIndex, item)
        return ContentId(item, index)
    }

    private fun bindItem(index: Long, item: String) {
        if (item.isBlank()) invalid("IDENTITY_CONFLICT")
        if (outputItems[index]?.let { it != item } == true || outputItems.any { it.key != index && it.value == item })
            invalid("IDENTITY_CONFLICT")
        outputItems[index] = item
    }

    private fun trackItem(root: JsonObject): List<ModelStreamEvent> {
        val item = objectValue(root["item"], required = true)!!
        val id = string(item, "id", required = true)!!
        val type = string(item, "type", required = true)!!
        bindItem(number(root["output_index"], required = true)!!, id)
        if (items[id]?.let { it != type } == true) invalid("IDENTITY_CONFLICT")
        items[id] = type
        if (type == "message" && root["type"] == JsonPrimitive("response.output_item.done")) {
            val content = item["content"] as? JsonArray ?: invalid("INVALID_CONTENT")
            val fullContent = mutableSetOf<ContentId>()
            for ((index, value) in content.withIndex()) {
                val block = objectValue(value, required = true)!!
                if (string(block, "type") != "output_text") continue
                val key = ContentId(id, index.toLong())
                fullContent += key
                if (!validateText(key, string(block, "text", required = true)!!))
                    return terminal(ModelEnd.INCOMPLETE, null, "TEXT_DELIVERY_INCOMPLETE")
                done += key
            }
            if (text.keys.any { it.item == id && it !in fullContent })
                return terminal(ModelEnd.INCOMPLETE, null, "TEXT_DELIVERY_INCOMPLETE")
        }
        if (type == "function_call") {
            // `call_id` and `name` are optional here so an added/done pair that only carries them once still works; the
            // terminal event is the point at which both must exist.
            string(item, "call_id")?.let { if (it.isNotBlank()) toolCalls.putIfAbsent(id, it) }
            string(item, "name")?.let { if (it.isNotBlank()) toolNames.putIfAbsent(id, it) }
            if (root["type"] == JsonPrimitive("response.output_item.done"))
                return toolTerminal(id, item)
        }
        return emptyList()
    }

    /**
     * Arguments stream in fragments; the `output_item.done` item is authoritative. A *partial* stream that disagrees
     * with the item means the stream is not internally coherent, so this refuses rather than handing the upper layer a
     * call it never fully observed — the same failure mode `TEXT_DELIVERY_INCOMPLETE` covers for text. An item that
     * arrives without any delta at all is not a disagreement: the whole document came inline in the item.
     */
    private fun toolTerminal(id: String, item: JsonObject): List<ModelStreamEvent> {
        val callId = string(item, "call_id")?.takeIf { it.isNotBlank() } ?: toolCalls[id]
            ?: invalid("MISSING_CALL_ID")
        val name = string(item, "name")?.takeIf { it.isNotBlank() } ?: toolNames[id]
            ?: invalid("MISSING_TOOL_NAME")
        val arguments = string(item, "arguments") ?: ""
        val streamed = toolArgs[id]?.toString()
        if (streamed != null && streamed != arguments)
            return terminal(ModelEnd.INCOMPLETE, null, "TOOL_CALL_DELIVERY_INCOMPLETE")
        done += ContentId(id, FUNCTION_CALL_INDEX)
        return listOf(ModelStreamEvent.ToolCallTerminal(request, id, callId, name, arguments))
    }

    private fun functionCall(root: JsonObject): String {
        val item = string(root, "item_id", required = true)!!
        if (item.isBlank()) invalid("MISSING_CONTENT_ID")
        val bound = items[item]
        if (bound != null && bound != "function_call") invalid("CONTENT_TYPE_CONFLICT")
        items.putIfAbsent(item, "function_call")
        number(root["output_index"])?.let { bindItem(it, item) }
        return item
    }

    private fun toolDelta(root: JsonObject): List<ModelStreamEvent> {
        val key = functionCall(root)
        if (ContentId(key, FUNCTION_CALL_INDEX) in done) invalid("DELTA_AFTER_DONE")
        val value = string(root, "delta", required = true)!!
        toolArgs.getOrPut(key) { StringBuilder() }.append(value)
        return if (value.isEmpty()) emptyList()
        else listOf(ModelStreamEvent.ToolCallDelta(request, key, toolNames[key], value))
    }

    private fun trackPart(root: JsonObject): List<ModelStreamEvent> {
        val key = identity(root)
        val part = objectValue(root["part"], required = true)!!
        val type = string(part, "type", required = true)!!
        if (parts[key]?.let { it != type } == true) invalid("IDENTITY_CONFLICT")
        parts[key] = type
        if (type == "output_text" && root["type"] == JsonPrimitive("response.content_part.done")) {
            if (!validateText(key, string(part, "text", required = true)!!))
                return terminal(ModelEnd.INCOMPLETE, null, "TEXT_DELIVERY_INCOMPLETE")
            done += key
        }
        return emptyList()
    }

    private fun textIdentity(root: JsonObject): ContentId {
        val key = identity(root)
        if (items[key.item]?.let { it != "message" } == true || parts[key]?.let { it != "output_text" } == true)
            invalid("CONTENT_TYPE_CONFLICT")
        // Delta-first streams still bind semantic types; later lifecycle events cannot relabel them.
        items.putIfAbsent(key.item, "message")
        parts.putIfAbsent(key, "output_text")
        return key
    }

    private fun delta(root: JsonObject): List<ModelStreamEvent> {
        val key = textIdentity(root)
        if (key in done) invalid("DELTA_AFTER_DONE")
        val value = string(root, "delta", required = true)!!
        text.getOrPut(key) { StringBuilder() }.append(value)
        return if (value.isEmpty()) emptyList() else listOf(ModelStreamEvent.TextDelta(request, value))
    }

    private fun validateText(key: ContentId, final: String): Boolean = final == (text[key]?.toString() ?: "")

    private fun done(root: JsonObject): List<ModelStreamEvent> {
        val key = textIdentity(root)
        val final = string(root, "text", required = true)!!
        if (!validateText(key, final)) return terminal(ModelEnd.INCOMPLETE, null, "TEXT_DELIVERY_INCOMPLETE")
        done += key
        return emptyList()
    }

    private fun finish(type: String, root: JsonObject): List<ModelStreamEvent> {
        val response = objectValue(root["response"], required = true)!!
        string(response, "id")?.let(::bindResponse)
        val expected = type.removePrefix("response.")
        val status = string(response, "status")
        if (status != null && status != expected) invalid("STATUS_CONFLICT")
        val usage = objectValue(response["usage"])?.let(::usage)
        val end = when (expected) {
            "completed" -> ModelEnd.COMPLETED
            "incomplete" -> ModelEnd.INCOMPLETE
            else -> ModelEnd.FAILED
        }
        if (end == ModelEnd.COMPLETED && !terminalTextMatches(response))
            return terminal(ModelEnd.INCOMPLETE, usage, "TEXT_DELIVERY_INCOMPLETE")
        // The same gate for calls: text is gated on `text.keys.all { it in delivered }`, and a call whose
        // `output_item.done` never arrived has no terminal event. Accepting `completed` there would hand the upper
        // layer a successful empty turn and silently swallow the call.
        if (end == ModelEnd.COMPLETED && !terminalToolMatches())
            return terminal(ModelEnd.INCOMPLETE, usage, "TOOL_CALL_DELIVERY_INCOMPLETE")
        // 0.25 Task 13 / R70: a failed response carrying the provider's context-length error is the one failed verdict
        // the gateway can recover from deterministically, so it gets its own stable code.
        val failedCode = if (end == ModelEnd.FAILED && responseSignalsContextOverflow(response)) {
            com.sheen.rokid.agent.CONTEXT_OVERFLOW
        } else {
            "PROVIDER_FAILED"
        }
        return terminal(end, usage, when (end) {
            ModelEnd.FAILED -> failedCode
            ModelEnd.INCOMPLETE -> "PROVIDER_INCOMPLETE"
            else -> null
        })
    }

    /** The provider's `error.code`/`type`/`message`, matched without keeping any of it. */
    private fun responseSignalsContextOverflow(response: JsonObject): Boolean {
        val error = response["error"] as? JsonObject ?: return false
        fun field(name: String): String? = (error[name] as? JsonPrimitive)?.contentOrNull
        return isProviderContextOverflow(field("code"), field("type"), field("message"))
    }

    /**
     * 0.3 design §5.3: the provider's cache telemetry, read from whichever member the protocol reports.
     *
     * The Responses schema nests the hit count under `input_tokens_details.cached_tokens`; DeepSeek reports
     * `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` at the usage top level. Every member is optional and an
     * absent one stays `null` — no count is fabricated, and the miss count is only ever read from its own member.
     */
    private fun usage(usage: JsonObject): ModelUsage {
        val details = objectValue(usage["input_tokens_details"])
        return ModelUsage(
            inputTokens = number(usage["input_tokens"]),
            outputTokens = number(usage["output_tokens"]),
            cachedInputTokens = number(details?.get("cached_tokens"))
                ?: number(usage["prompt_cache_hit_tokens"]),
            cacheMissInputTokens = number(usage["prompt_cache_miss_tokens"]),
        )
    }

    /** Mirrors the text path's `text.keys.all { it in delivered }`: every observed call must have its done sentinel. */
    private fun terminalToolMatches(): Boolean =
        items.filterValues { it == "function_call" }.keys.all { ContentId(it, FUNCTION_CALL_INDEX) in done }

    private fun terminalTextMatches(response: JsonObject): Boolean {
        val output = response["output"] ?: return true
        if (output is JsonNull) return true
        val array = output as? JsonArray ?: invalid("INVALID_OUTPUT")
        val delivered = mutableSetOf<ContentId>()
        var matches = true
        for ((outputIndex, element) in array.withIndex()) {
            val item = objectValue(element, required = true)!!
            // P2 tolerance: only `message` items carry text to validate, so every other item type is skipped before
            // any field is required of it. Requiring `id` on every item turned an id-less non-message item into
            // FAILED/MISSING_FIELD, which P2 ignored. Message items keep the position binding added for them.
            if (string(item, "type") != "message") continue
            val id = string(item, "id", required = true)!!
            bindItem(outputIndex.toLong(), id)
            val content = item["content"] as? JsonArray ?: invalid("INVALID_CONTENT")
            for ((index, part) in content.withIndex()) {
                val block = objectValue(part, required = true)!!
                if (string(block, "type") != "output_text") continue
                val key = ContentId(id, index.toLong())
                delivered += key
                if (!validateText(key, string(block, "text", required = true)!!)) matches = false
            }
        }
        return matches && text.keys.all { it in delivered }
    }

    private fun terminal(end: ModelEnd, usage: ModelUsage?, code: String?): List<ModelStreamEvent> {
        if (ended) return emptyList()
        ended = true
        text.clear(); done.clear(); items.clear(); parts.clear(); outputItems.clear()
        toolCalls.clear(); toolArgs.clear(); toolNames.clear()
        return listOf(ModelStreamEvent.Terminal(request, end, usage, code))
    }

    private companion object {
        /** Content ids are `(item, content index)`; tool arguments have no content index, so one sentinel is used. */
        const val FUNCTION_CALL_INDEX = -1L
    }
}
