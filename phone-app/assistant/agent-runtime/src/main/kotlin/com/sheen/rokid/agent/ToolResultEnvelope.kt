package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one model-facing shape of every tool result: exactly `{execution, observation, data}`.
 *
 * The envelope is deliberately stable: the three top-level keys, every [ToolExecution] field and every
 * [ToolObservation] field are always present, so a model (and a golden test) never has to infer a field from its
 * absence. Unknown execution facts stay JSON `null`; an absent observation is JSON `null`; [data] is the tool-specific
 * payload parsed as JSON when it is valid JSON and encoded as a JSON string otherwise. `plugin-api` has no JSON
 * dependency, which is why this projection lives in `agent-runtime`.
 */
object ToolResultEnvelope {

    fun encode(execution: ToolExecution, observation: ToolObservation?, data: String): String =
        buildJsonObject {
            put("execution", encodeExecution(execution))
            put("observation", observation?.let(::encodeObservation) ?: JsonNull)
            put("data", parseData(data))
        }.toString()

    /**
     * edith-0.3 Phase 3 Task 4 (P3-9, design §7.2): the content-free fingerprint of [payload]'s JSON shape.
     *
     * Every string value becomes `""` and every number becomes `0`; booleans and `null` are kept, and object keys keep
     * their order. The masked element is compact-serialised and SHA-256'd, and the first eight lowercase hex characters
     * are returned. A payload that is not JSON yields `null`, so the caller reports `-` instead of a fabricated digest.
     *
     * This is the one proof a device log can carry that the runtime payload is the canonical envelope: the key set,
     * the tri-state nulls and the value types are the shape, while the values themselves never leave the request. The
     * algorithm is pinned by `ToolResultEnvelopeTest` against independently computed constants.
     */
    fun shapeDigest(payload: String): String? {
        val parsed = try {
            Json.parseToJsonElement(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return sha256Hex(maskShape(parsed).toString()).take(8)
    }

    /** Recursively replaces content with the value's type: strings -> `""`, numbers -> `0`; booleans/null stay. */
    private fun maskShape(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> maskShape(value) })
        is JsonArray -> JsonArray(element.map(::maskShape))
        is JsonPrimitive -> when {
            element is JsonNull -> JsonNull
            element.isString -> JsonPrimitive("")
            element.booleanOrNull != null -> element
            else -> JsonPrimitive(0)
        }
    }

    private fun encodeExecution(execution: ToolExecution): JsonObject = buildJsonObject {
        put("outcome", execution.outcome.wireValue)
        put("code", execution.code)
        put("message", execution.message)
        put("recoverable", execution.recoverable)
        put("accepted", execution.accepted)
        put("applied", execution.applied)
        put("verified", execution.verified)
        put("changed", execution.changed)
        put("uncertain", execution.uncertain)
        put("partial", execution.partial)
    }

    private fun encodeObservation(observation: ToolObservation): JsonObject = buildJsonObject {
        put("snapshotId", observation.snapshotId)
        put("capturedAtEpochMillis", observation.capturedAtEpochMillis)
        put("foregroundPackage", observation.foregroundPackage)
        put("foregroundActivity", observation.foregroundActivity)
        put("rootPackage", observation.rootPackage)
        put("coverage", observation.coverage)
        put("screen", observation.screen)
        put("truncated", observation.truncated)
        put("vision", observation.vision.wireValue)
        put("visionReason", observation.visionReason)
    }

    /**
     * Tool payloads are already JSON for most tools; a plain-text payload stays a JSON string instead of being
     * rejected. Parsing is strict (not lenient), so an unquoted word is text, whereas `{}`, `[]`, a number, `true` or
     * `null` are the JSON values they spell.
     */
    private fun parseData(data: String): JsonElement =
        try {
            Json.parseToJsonElement(data)
        } catch (_: IllegalArgumentException) {
            JsonPrimitive(data)
        }
}

/**
 * P3-9: the lowercase wire token an envelope and the count-only `P3_TOOLRESULT` line both carry.
 *
 * Top-level (rather than a member of [ToolResultEnvelope]) so `AgentLoop` can render the same vocabulary the model-facing
 * envelope uses without a second mapping that could drift.
 */
internal val ToolOutcome.wireValue: String
    get() = when (this) {
        ToolOutcome.Done -> "done"
        ToolOutcome.Refused -> "refused"
        ToolOutcome.Failed -> "failed"
        ToolOutcome.Timeout -> "timeout"
        ToolOutcome.Uncertain -> "uncertain"
        ToolOutcome.Cancelled -> "cancelled"
    }

/** P3-9: the lowercase wire token `P3_TOOLRESULT vision=` carries. */
internal val ToolVision.wireValue: String
    get() = when (this) {
        ToolVision.Ok -> "ok"
        ToolVision.Disabled -> "disabled"
        ToolVision.Unavailable -> "unavailable"
        ToolVision.NotRequested -> "not_requested"
    }
