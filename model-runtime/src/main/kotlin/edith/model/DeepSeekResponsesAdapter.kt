package edith.model

import edith.plugin.*
import kotlinx.serialization.json.*
import java.util.Locale

class DeepSeekResponsesAdapter {
    val support = ProtocolSupport("DEEPSEEK_RESPONSES", setOf(InputModality.TEXT), setOf(OutputModality.TEXT))
    fun encode(profile: ModelProfile, budgeted: BudgetedRequest): ByteArray {
        require(profile.protocolId == support.id && budgeted.request.profileId == profile.id && budgeted.request.profileRevision == profile.revision)
        require(InputModality.TEXT in profile.inputs && OutputModality.TEXT in profile.outputs)
        require(budgeted.request.output == OutputModality.TEXT)
        require(budgeted.request.input.all { it is InputPart.Text })
        val tools = budgeted.request.tools.map(::descriptor)
        val body = buildJsonObject {
            put("model", profile.model)
            put("instructions", budgeted.request.instructions)
            put("stream", true)
            put("max_output_tokens", profile.maxOutputTokens)
            if (tools.isNotEmpty()) {
                // Measured, not guessed (T4 + the T5 ten-step run). Without `reasoning.effort = "none"` the turn comes
                // back `incomplete/max_output_tokens`: the deliberation is charged against the output budget and the
                // call never arrives. `tool_choice` is `auto` only — the endpoint answered HTTP 400 to `required`.
                put("tool_choice", "auto")
                put("reasoning", buildJsonObject { put("effort", "none") })
                put("tools", buildJsonArray { tools.forEach(::add) })
            }
            put("input", buildJsonArray {
                budgeted.request.history.forEach { add(historyItem(it)) }
                budgeted.request.context.forEach { add(message("user", it.text)) }
                budgeted.request.input.forEach { add(message("user", (it as InputPart.Text).text)) }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    private fun historyItem(item: ModelHistoryItem) = when (item) {
        is ModelHistoryItem.Text -> message(item.role.name.lowercase(Locale.ROOT), item.text)
        // The wire shape the proven probe sent: the model's own function_call item, echoed verbatim.
        is ModelHistoryItem.ToolCall -> buildJsonObject {
            put("type", "function_call")
            put("id", item.itemId)
            put("call_id", item.callId)
            put("name", item.name)
            put("arguments", item.argsJson)
        }
        is ModelHistoryItem.ToolOutput -> buildJsonObject {
            put("type", "function_call_output")
            put("call_id", item.callId)
            put("output", item.output)
        }
    }

    /**
     * The flattened descriptor proven against the endpoint: `{type, name, description, parameters}` at the top level.
     * The nested OpenAI `function` wrapper is not part of this vendor's schema.
     */
    private fun descriptor(spec: ToolSpec) = buildJsonObject {
        put("type", "function")
        put("name", spec.name)
        put("description", spec.description)
        put("parameters", parseParameters(spec))
    }

    private fun parseParameters(spec: ToolSpec): JsonElement = try {
        Json.parseToJsonElement(spec.parametersJson)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Tool parameters are not valid JSON")
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role); put("content", content)
    }
}
