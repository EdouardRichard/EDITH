package com.sheen.rokid.model

import com.sheen.rokid.agent.CONTEXT_OVERFLOW
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Detects the provider's own "this request does not fit the context window" signal (0.25 R70).
 *
 * The provider is the third party that knows its tokenizer and its window; this module only reads the machine-readable
 * `error.code`/`error.type` it sends (with its message as a last-resort fallback, because some providers put the code
 * only in prose). Nothing here is logged or stored: the body is read bounded, matched, and dropped.
 */
internal fun isProviderContextOverflow(code: String?, type: String?, message: String?): Boolean {
    fun signal(value: String?): Boolean {
        val normalized = value?.lowercase() ?: return false
        if (normalized.contains("context") &&
            (normalized.contains("overflow") ||
                normalized.contains("length") ||
                normalized.contains("exceed") ||
                normalized.contains("too long"))
        ) {
            return true
        }
        return (normalized.contains("prompt") || normalized.contains("input") || normalized.contains("request")) &&
            normalized.contains("too large")
    }
    if (signal(code) || signal(type)) return true
    val normalizedMessage = message?.lowercase() ?: return false
    return (normalizedMessage.contains("context") || normalizedMessage.contains("prompt")) &&
        (normalizedMessage.contains("overflow") ||
            normalizedMessage.contains("too long") ||
            normalizedMessage.contains("exceed") ||
            normalizedMessage.contains("maximum context length") ||
            normalizedMessage.contains("too large"))
}

/**
 * The overflow signal from an HTTP error body, or null. Reads the `{"error": {code,type,message}}` shape both
 * supported protocols document, and tolerates the fields sitting at the top level.
 *
 * The body is read by the caller with a byte bound; this function only parses. It never keeps the message text.
 */
internal fun providerContextOverflow(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val error = root["error"] as? JsonObject ?: root
    fun field(name: String): String? = (error[name] as? JsonPrimitive)?.contentOrNull
    return if (isProviderContextOverflow(field("code"), field("type"), field("message"))) {
        CONTEXT_OVERFLOW
    } else {
        null
    }
}
