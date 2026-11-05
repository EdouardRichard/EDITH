package com.sheen.rokid.model

import com.sheen.rokid.plugin.InputModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 0.52 design §5.4: one refresh keeps at most 500 models and reports whether the gateway had more. */
internal const val MAX_MODEL_LIST_SIZE = 500

/**
 * One model as the gateway's `GET /models` describes it (0.52 design §5.4).
 *
 * Only `id` is universal. Every capability is optional because the field names differ between gateways
 * (`docs/V0.52/appendix/gateway-api-research.md` §3); a missing value stays null/false and the merge layer
 * falls through to its known table and conservative defaults.
 */
data class GatewayModelSnapshot(
    val id: String,
    val displayName: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputs: Set<InputModality>? = null,
    val ownedBy: String? = null,
    val thinkingSupported: Boolean = false,
)

/**
 * Parse a `/models` body: `{"data":[...]}` or a top-level array.
 *
 * Pure and total: malformed JSON, a missing `data` array and an empty result are reported as [ModelListResult.Failure],
 * never thrown. Blank/duplicate ids are dropped (the first occurrence of an id wins) and at most
 * [MAX_MODEL_LIST_SIZE] models are kept.
 */
internal fun parseModelList(body: String): ModelListResult {
    val root = try {
        Json.parseToJsonElement(body)
    } catch (_: Exception) {
        return ModelListResult.Failure(ModelListError.INVALID_RESPONSE)
    } catch (_: StackOverflowError) {
        return ModelListResult.Failure(ModelListError.INVALID_RESPONSE)
    }
    val entries = when (root) {
        is JsonArray -> root
        is JsonObject -> root["data"] as? JsonArray
            ?: return ModelListResult.Failure(ModelListError.INVALID_RESPONSE)
        else -> return ModelListResult.Failure(ModelListError.INVALID_RESPONSE)
    }
    val models = ArrayList<GatewayModelSnapshot>(minOf(entries.size, MAX_MODEL_LIST_SIZE))
    val ids = HashSet<String>(minOf(entries.size, MAX_MODEL_LIST_SIZE))
    var truncated = false
    for (entry in entries) {
        val model = entry as? JsonObject ?: continue
        val id = model.string("id")?.takeIf { it.isNotBlank() } ?: continue
        if (!ids.add(id)) continue
        if (models.size == MAX_MODEL_LIST_SIZE) {
            truncated = true
            break
        }
        models += model.toSnapshot(id)
    }
    return if (models.isEmpty()) ModelListResult.Failure(ModelListError.EMPTY)
    else ModelListResult.Success(models, truncated)
}

private fun JsonObject.toSnapshot(id: String): GatewayModelSnapshot {
    val ownedBy = string("owned_by")?.takeIf { it.isNotBlank() }
    return GatewayModelSnapshot(
        id = id,
        displayName = (string("name") ?: string("display_name"))?.takeIf { it.isNotBlank() },
        contextWindowTokens = positiveInt("context_window")
            ?: positiveInt("context_length")
            ?: positiveInt("max_model_len"),
        maxOutputTokens = positiveInt("max_output_tokens")
            ?: positiveInt("max_completion_tokens")
            ?: objectAt("top_provider")?.positiveInt("max_completion_tokens")
            ?: positiveInt("max_tokens"),
        inputs = modalities(),
        ownedBy = ownedBy,
        // 0.53 design (revised) §T8: the system does not guess a model family. Only the gateway's own explicit
        // reasoning metadata marks a model as thinking-capable; an id or `owned_by` that merely says "deepseek" is not
        // evidence (owner ruling A). The user's switch decides what a request asks for either way.
        thinkingSupported = declaresReasoningEffort(),
    )
}

/**
 * Input modalities exist under three shapes in the wild (research §3): a top-level `input_modalities` array,
 * OpenRouter's `architecture.input_modalities`, or LiteLLM's `supports_vision`/`supports_audio_input` booleans.
 * The boolean fallback always implies text; a missing field stays null so the known table can answer.
 */
private fun JsonObject.modalities(): Set<InputModality>? {
    arrayAt("input_modalities")?.let { return it.toModalities() }
    objectAt("architecture")?.arrayAt("input_modalities")?.let { return it.toModalities() }
    val vision = boolean("supports_vision")
    val audio = boolean("supports_audio_input")
    if (vision == null && audio == null) return null
    val result = linkedSetOf(InputModality.TEXT)
    if (vision == true) result += InputModality.IMAGE
    if (audio == true) result += InputModality.AUDIO
    return result
}

private fun JsonArray.toModalities(): Set<InputModality> {
    val result = linkedSetOf<InputModality>()
    for (element in this) {
        val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: continue
        when (name) {
            "text" -> result += InputModality.TEXT
            "image" -> result += InputModality.IMAGE
            "audio" -> result += InputModality.AUDIO
        }
    }
    return result
}

/**
 * 0.53 design (revised) §T8: only the **gateway's own** reasoning metadata marks a model as thinking-capable. The
 * system does not infer a DeepSeek family from the id or `owned_by` (owner ruling A); those are reported gateway fields
 * and nothing else. `thinkingSupported` also no longer decides any request: the user's switch does.
 */
private fun JsonObject.declaresReasoningEffort(): Boolean =
    this["effort"] is JsonObject ||
        objectAt("reasoning")?.get("supported_efforts") is JsonArray

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.positiveInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it > 0 }

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.arrayAt(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject
