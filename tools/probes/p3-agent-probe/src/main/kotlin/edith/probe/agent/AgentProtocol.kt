package edith.probe.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One model turn, reduced to the two things the loop needs to know: did it ask for a tool, and if not, what did it say.
 */
data class ModelTurn(
    val httpStatus: Int,
    val safeCode: String?,
    val toolCall: ToolCall?,
    val message: String?,
    val rawOutput: JsonArray,
    val status: String? = null,
    val incompleteReason: String? = null,
) {
    val finishedWithoutTool: Boolean get() = httpStatus == 200 && safeCode == null && toolCall == null

    /**
     * A turn cut off before it produced anything usable. The API reports this as `status: "incomplete"`; the loop must
     * not mistake it for a final answer, because "the model said nothing" and "the model was truncated mid-thought" have
     * different fixes.
     */
    val truncated: Boolean get() = status == "incomplete"
}

data class ToolCall(val callId: String?, val name: String, val arguments: String)

private fun JsonObject.stringAt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Reads one Responses reply. A `function_call` item carries `call_id`, `name` and `arguments` (a JSON *string*), per the
 * DeepSeek Responses reference; anything else is recorded as a `safeCode` rather than thrown, because a gateway error
 * page must not look like a model answer.
 */
fun readTurn(httpStatus: Int, body: String): ModelTurn {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return ModelTurn(httpStatus, "BODY_UNPARSABLE", null, null, JsonArray(emptyList()))
    if (httpStatus != 200) return ModelTurn(httpStatus, "HTTP_$httpStatus", null, null, JsonArray(emptyList()))
    val output = root["output"] as? JsonArray ?: JsonArray(emptyList())
    val objects = output.filterIsInstance<JsonObject>()
    val status = root.stringAt("status")
    val incompleteReason = (root["incomplete_details"] as? JsonObject)?.stringAt("reason")
    val call = objects.firstOrNull { it.stringAt("type") == "function_call" }
    if (call != null) {
        val name = call.stringAt("name")
        if (name.isNullOrBlank()) return ModelTurn(httpStatus, "TOOL_CALL_WITHOUT_NAME", null, null, output, status, incompleteReason)
        return ModelTurn(
            httpStatus = httpStatus,
            safeCode = null,
            toolCall = ToolCall(call.stringAt("call_id"), name, call.stringAt("arguments").orEmpty()),
            message = null,
            rawOutput = output,
            status = status,
            incompleteReason = incompleteReason,
        )
    }
    val text = objects.filter { it.stringAt("type") == "message" }
        .flatMap { (it["content"] as? JsonArray ?: JsonArray(emptyList())).filterIsInstance<JsonObject>() }
        .mapNotNull { it.stringAt("text") }
        .joinToString("\n")
    return ModelTurn(httpStatus, null, null, text.ifBlank { null }, output, status, incompleteReason)
}

/** Builds the next request's `input`: the whole stateless history, per the API's documented behaviour. */
fun appendToolExchange(
    history: JsonArray,
    modelOutput: JsonArray,
    call: ToolCall,
    observation: String,
): JsonArray {
    val items = history.toMutableList()
    // Echo the model's own function_call item back verbatim: the API is stateless and expects the call it produced to
    // be present alongside its result.
    modelOutput.filterIsInstance<JsonObject>()
        .filter { it.stringAt("type") == "function_call" }
        .forEach { items += it }
    items += buildJsonObject {
        put("type", "function_call_output")
        put("call_id", call.callId ?: "")
        put("output", observation)
    }
    return JsonArray(items)
}
