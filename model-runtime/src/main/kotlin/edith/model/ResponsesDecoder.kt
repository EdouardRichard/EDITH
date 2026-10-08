package edith.model

import edith.plugin.ModelEnd
import edith.plugin.ModelStreamEvent
import edith.plugin.ModelUsage
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
        return emptyList()
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
        val usage = objectValue(response["usage"])?.let {
            ModelUsage(number(it["input_tokens"]), number(it["output_tokens"]))
        }
        val end = when (expected) {
            "completed" -> ModelEnd.COMPLETED
            "incomplete" -> ModelEnd.INCOMPLETE
            else -> ModelEnd.FAILED
        }
        if (end == ModelEnd.COMPLETED && !terminalTextMatches(response))
            return terminal(ModelEnd.INCOMPLETE, usage, "TEXT_DELIVERY_INCOMPLETE")
        return terminal(end, usage, when (end) {
            ModelEnd.FAILED -> "PROVIDER_FAILED"
            ModelEnd.INCOMPLETE -> "PROVIDER_INCOMPLETE"
            else -> null
        })
    }

    private fun terminalTextMatches(response: JsonObject): Boolean {
        val output = response["output"] ?: return true
        if (output is JsonNull) return true
        val array = output as? JsonArray ?: invalid("INVALID_OUTPUT")
        val delivered = mutableSetOf<ContentId>()
        var matches = true
        for ((outputIndex, element) in array.withIndex()) {
            val item = objectValue(element, required = true)!!
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
        return listOf(ModelStreamEvent.Terminal(request, end, usage, code))
    }
}
