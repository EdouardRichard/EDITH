package edith.probe.toolcall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One attempt's outcome.
 *
 * [safeCode] is the field that keeps a wrong-shaped request from being recorded as a vendor limitation:
 * `"HTTP_401"`, `"BODY_UNPARSABLE"` and `"NO_TOOL_CALL"` are three completely different findings, and only the last one
 * is about tool support.
 */
data class ToolCallOutcome(
    val httpStatus: Int,
    val sawToolCall: Boolean,
    val toolName: String?,
    val safeCode: String?,
)

private fun JsonObject.stringAt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Reads a Responses-style reply: per the DeepSeek API reference, output items carry `type` of `message`, `reasoning` or
 * `function_call`, and a `function_call` item carries `call_id`, `name` and `arguments` (a JSON *string*).
 *
 * Never throws: an unparsable body is a `safeCode`, because a gateway error page must not crash the probe and must not
 * be mistaken for an answer.
 */
fun outcomeOf(httpStatus: Int, body: String): ToolCallOutcome {
    if (httpStatus != 200) return ToolCallOutcome(httpStatus, false, null, "HTTP_$httpStatus")
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return ToolCallOutcome(httpStatus, false, null, "BODY_UNPARSABLE")
    val output = root["output"] as? JsonArray ?: return ToolCallOutcome(httpStatus, false, null, "NO_TOOL_CALL")
    val call = output.filterIsInstance<JsonObject>().firstOrNull { it.stringAt("type") == "function_call" }
        ?: return ToolCallOutcome(httpStatus, false, null, "NO_TOOL_CALL")
    val arguments = call.stringAt("arguments")
    val argumentsValid = arguments != null && runCatching { Json.parseToJsonElement(arguments) }.isSuccess
    return ToolCallOutcome(
        httpStatus = httpStatus,
        sawToolCall = true,
        toolName = call.stringAt("name"),
        safeCode = if (argumentsValid) "OK" else "ARGUMENTS_INVALID_JSON",
    )
}
