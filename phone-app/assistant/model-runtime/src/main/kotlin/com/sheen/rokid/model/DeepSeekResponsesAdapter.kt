package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import java.util.Locale

class DeepSeekResponsesAdapter {
    val support = ProtocolSupport("DEEPSEEK_RESPONSES", setOf(InputModality.TEXT), setOf(OutputModality.TEXT))

    /**
     * Encodes a [RequestFrame] into the DeepSeek Responses API wire format (spec §3.2 / Phase 3).
     *
     * The system slot goes to `"instructions"`. Conversation flow segments are rendered in order:
     * TRANSCRIPT/SUMMARY as role-tagged messages, MEMORY_EVIDENCE as user messages, TOOL_ROUND as
     * function_call/function_call_output items, and USER_INPUT last.
     */
    fun encode(profile: ModelProfile, frame: RequestFrame): ByteArray {
        require(profile.protocolId == support.id)
        require(InputModality.TEXT in profile.inputs && OutputModality.TEXT in profile.outputs)
        val tools = frame.tools.map(::descriptor)
        val body = buildJsonObject {
            put("model", profile.model)
            put("instructions", frame.systemText)
            put("stream", true)
            put("max_output_tokens", profile.maxOutputTokens)
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
                put("reasoning", buildJsonObject { put("effort", "none") })
                put("tools", buildJsonArray { tools.forEach(::add) })
            }
            put("input", buildJsonArray {
                for (segment in frame.conversationFlow) {
                    when (segment.origin) {
                        SegmentOrigin.TRANSCRIPT -> {
                            // Transcript segments carry role-prefixed text: "user: ..." or "assistant: ..."
                            // For now, render as user messages (the TurnRunner will structure these properly)
                            add(message("user", segment.text))
                        }
                        SegmentOrigin.SUMMARY -> add(message("user", segment.text))
                        SegmentOrigin.MEMORY_EVIDENCE -> add(message("user", segment.text))
                        SegmentOrigin.PERCEPTION -> add(message("user", segment.text))
                        SegmentOrigin.USER_INPUT -> add(message("user", segment.text))
                        SegmentOrigin.TOOL_ROUND -> {
                            // Parse tool round JSON: array of {type, callId, name, argsJson, output}
                            parseToolRound(segment.text)?.forEach { add(it) }
                        }
                        SegmentOrigin.RETRY -> add(message("user", segment.text))
                        else -> add(message("user", segment.text))
                    }
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses a TOOL_ROUND segment's text into wire-format JSON elements.
     * Expected format: JSON array of objects with "type" field ("call" or "output").
     */
    private fun parseToolRound(text: String): List<JsonElement>? = try {
        val array = Json.parseToJsonElement(text) as? JsonArray ?: return null
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.content) {
                "call" -> buildJsonObject {
                    put("type", "function_call")
                    put("id", obj["itemId"]?.jsonPrimitive?.content ?: obj["callId"]?.jsonPrimitive?.content ?: "")
                    put("call_id", obj["callId"]?.jsonPrimitive?.content ?: "")
                    put("name", obj["name"]?.jsonPrimitive?.content ?: "")
                    put("arguments", obj["argsJson"]?.jsonPrimitive?.content ?: "{}")
                }
                "output" -> buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", obj["callId"]?.jsonPrimitive?.content ?: "")
                    put("output", obj["output"]?.jsonPrimitive?.content ?: "")
                }
                else -> null
            }
        }
    } catch (_: Exception) { null }

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
