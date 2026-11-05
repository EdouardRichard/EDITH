package com.sheen.rokid.model

import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.Locale

/**
 * Encoder for the DeepSeek Responses API.
 *
 * 0.25 (§4.10) adds images to the budgeted (live) path: the current turn's `InputPart.Image` becomes an `input_image`
 * content item on its user message, and a tool result's `ImageRef`s ride in `function_call_output.output` as the
 * content-item array the endpoint documents (D9). A text-only request and a text-only tool result keep their exact
 * pre-0.25 string shapes. A **historical** tool image whose artifact expired degrades to a visible
 * `[image unavailable: …]` `input_text` item (R71); the current input image still refuses the request. The legacy
 * [encode] frame overload is deliberately left text-only: its images live in the structured history
 * (`ToolOutput.images`, restored by `ModelClientGateway`), never in `TOOL_ROUND` JSON (R6).
 */
class DeepSeekResponsesAdapter(private val media: MediaSource? = null) {
    val support = ProtocolSupport(ProtocolIds.OPENAI_RESPONSES,
        setOf(InputModality.TEXT, InputModality.IMAGE), setOf(OutputModality.TEXT))

    /**
     * Encodes a [RequestFrame] into the DeepSeek Responses API wire format (spec §3.2 / Phase 3).
     *
     * The system slot goes to `"instructions"`. Conversation flow segments are rendered in order:
     * TRANSCRIPT/SUMMARY as role-tagged messages, MEMORY_EVIDENCE as user messages, TOOL_ROUND as
     * function_call/function_call_output items, and USER_INPUT last. This legacy overload sends text only; images are
     * carried by the budgeted overload below.
     */
    fun encode(profile: ModelProfile, frame: RequestFrame): ByteArray {
        require(ProtocolIds.canonical(profile.protocolId) == support.id)
        require(InputModality.TEXT in profile.inputs && OutputModality.TEXT in profile.outputs)
        val tools = frame.tools.map(::descriptor)
        val body = buildJsonObject {
            put("model", profile.model)
            put("instructions", frame.systemText)
            put("stream", true)
            // 0.53 design (revised) §T3/§T5: the user switch is the only behaviour switch. With it on, the official
            // `reasoning.effort` goes out on every request (tools included) and `max_output_tokens` is **omitted** so
            // the provider's documented 64K/128K default applies. With it off, no thinking field at all is sent.
            if (!profile.thinkingEnabled) put("max_output_tokens", profile.maxOutputTokens)
            if (profile.thinkingEnabled) {
                put("reasoning", buildJsonObject { put("effort", reasoningEffort(profile)) })
            }
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
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
                        SegmentOrigin.PERCEPTION, SegmentOrigin.SKILL_BODY -> add(message("user", segment.text))
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
        require(ProtocolIds.canonical(profile.protocolId) == support.id && budgeted.request.profileId == profile.id && budgeted.request.profileRevision == profile.revision)
        require(InputModality.TEXT in profile.inputs && OutputModality.TEXT in profile.outputs)
        require(budgeted.request.output == OutputModality.TEXT)
        // 0.25 §4.10: this version sends TEXT + IMAGE. An audio part is refused visibly rather than dropped, and the
        // profile must declare every modality the request actually carries — the same rule `CapabilityGate` applies
        // before the live path, kept here so the encoder is safe on its own.
        require(budgeted.request.input.all { it is InputPart.Text || it is InputPart.Image }) {
            "Responses input supports text and image parts only"
        }
        val requiredInputs = budgeted.request.input.mapTo(mutableSetOf()) { part ->
            if (part is InputPart.Image) InputModality.IMAGE else InputModality.TEXT
        }
        require(profile.inputs.containsAll(requiredInputs)) { "Profile does not declare a request input modality" }
        val tools = budgeted.request.tools.map(::descriptor)
        val body = buildJsonObject {
            put("model", profile.model)
            put("instructions", budgeted.request.instructions)
            put("stream", true)
            // 0.3 design §5.2: with the switch off the turn policy's cap wins, and the profile's value is only the
            // window reservation used when the request declares no cap of its own. 0.53 (revised) §T5: with the
            // switch on no `max_output_tokens` is sent at all, so the official 64K/128K default applies.
            if (!profile.thinkingEnabled) {
                put("max_output_tokens", budgeted.request.maxOutputTokens ?: profile.maxOutputTokens)
            }
            // Both overloads write the same mapping — no family/capability gate.
            if (profile.thinkingEnabled) {
                put("reasoning", buildJsonObject { put("effort", reasoningEffort(profile)) })
            }
            if (tools.isNotEmpty()) {
                // Measured, not guessed (T4 + the T5 ten-step run). Without `reasoning.effort = "none"` the turn comes
                // back `incomplete/max_output_tokens`: the deliberation is charged against the output budget and the
                // call never arrives. `tool_choice` is `auto` only — the endpoint answered HTTP 400 to `required`.
                put("tool_choice", "auto")
                put("tools", buildJsonArray { tools.forEach(::add) })
            }
            put("input", buildJsonArray {
                val replayReasoning = budgeted.request.tools.isNotEmpty()
                budgeted.request.history.forEach { item -> historyItems(item, replayReasoning).forEach { add(it) } }
                // The current turn. Text alone keeps the proven plain-string message shape; any image makes the whole
                // turn one user message whose content is the content-item array (text and images in caller order).
                val input = budgeted.request.input
                if (input.any { it is InputPart.Image }) {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            input.forEach { part ->
                                when (part) {
                                    is InputPart.Text -> add(inputTextPart(part.text))
                                    is InputPart.Image -> add(inputImagePart(resolveImage(part.localRef)))
                                    is InputPart.Audio -> error("audio is refused before encoding")
                                }
                            }
                        })
                    })
                } else {
                    input.forEach { add(message("user", (it as InputPart.Text).text)) }
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    /**
     * 0.53 design (revised) §T4: one history item becomes one or two wire items.
     *
     * When the request carries `tools`, an assistant item's captured `reasoning` is replayed as a `reasoning` input
     * item **immediately before** the assistant message it belongs to; without tools it is not replayed. The capture is
     * not gated on a capability guess (owner ruling A) — a turn whose switch was off may still have been given reasoning
     * by the server, and the next tool request must carry it. The item's inner shape is kept in the one [reasoningItem]
     * helper so a gateway-probe correction changes a single place.
     */
    private fun historyItems(item: ModelHistoryItem, replayReasoning: Boolean): List<JsonElement> = when (item) {
        is ModelHistoryItem.Text -> buildList {
            if (replayReasoning && item.role == MessageRole.ASSISTANT) {
                item.reasoning?.let { add(reasoningItem(it)) }
            }
            add(message(item.role.name.lowercase(Locale.ROOT), item.text))
        }
        // The wire shape the proven probe sent: the model's own function_call item, echoed verbatim.
        is ModelHistoryItem.ToolCall -> listOf(buildJsonObject {
            put("type", "function_call")
            put("id", item.itemId)
            put("call_id", item.callId)
            put("name", item.name)
            put("arguments", item.argsJson)
        })
        is ModelHistoryItem.ToolOutput -> listOf(buildJsonObject {
            put("type", "function_call_output")
            put("call_id", item.callId)
            // A text-only result is byte-for-byte the pre-0.25 item. A result with images becomes the content-item
            // array D9 documents: its text, then one `input_image` per image (the tool result is the only item that
            // may carry a tool's images back to the model).
            if (item.images.isEmpty()) put("output", item.output)
            else put("output", buildJsonArray {
                add(inputTextPart(item.output))
                item.images.forEach { image ->
                    val resolved = media?.resolveImage(image.artifactId)
                    // R71: a historical image whose artifact expired degrades visibly; the request still encodes.
                    if (resolved == null) add(inputTextPart(imageUnavailablePlaceholder(image.artifactId)))
                    else add(inputImagePart(resolved))
                }
            })
        })
    }

    /**
     * 0.53 design §T11: the reasoning input item. The official docs only state that plain-text content merges into the
     * adjacent assistant message; the inner shape below is the one the real-gateway probe accepts. It lives in this one
     * helper so a probe correction touches a single place (and its matching test).
     */
    private fun reasoningItem(reasoning: String) = buildJsonObject {
        put("type", "reasoning")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning_text")
                put("text", reasoning)
            })
        })
    }

    /**
     * One `input_text` content item of a Responses content-item array. Used only where a string is not enough (a
     * user turn or tool result that carries an image); everywhere else the plain-message shape is preserved.
     */
    private fun inputTextPart(text: String) = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    /**
     * One `input_image` content item. Responses takes the URL as a plain string (the nested `{"url": ...}` object is
     * the Chat Completions shape): a base64 `data:` URL keeps the bytes inline, and `detail` is stated explicitly as
     * `auto` (0.25 spec §4.10). The artifact store, not this encoder, owns the bytes.
     */
    private fun inputImagePart(image: ResolvedImage) = buildJsonObject {
        put("type", "input_image")
        put("image_url", "data:${image.mime};base64,${Base64.getEncoder().encodeToString(image.bytes)}")
        put("detail", "auto")
    }

    /** The current input image still refuses the request; only a historical one may degrade to a placeholder (R71). */
    private fun resolveImage(artifactId: String): ResolvedImage =
        media?.resolveImage(artifactId)
            ?: throw ContractError.CapabilityMissing("image ref unresolvable: $artifactId")

    /**
     * 0.53 design (revised) §T5: the official effort names for the three catalog levels. Only called when the user
     * switch is on; the switched-off request carries no reasoning field at all.
     */
    private fun reasoningEffort(profile: ModelProfile): String = when (profile.thinkingEffort) {
        ThinkingEffort.LOW -> "low"
        ThinkingEffort.HIGH -> "high"
        ThinkingEffort.MAX -> "max"
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
