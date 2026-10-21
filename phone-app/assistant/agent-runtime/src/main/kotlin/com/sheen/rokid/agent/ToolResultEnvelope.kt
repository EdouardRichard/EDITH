package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val ToolOutcome.wireValue: String
        get() = when (this) {
            ToolOutcome.Done -> "done"
            ToolOutcome.Refused -> "refused"
            ToolOutcome.Failed -> "failed"
            ToolOutcome.Timeout -> "timeout"
            ToolOutcome.Uncertain -> "uncertain"
            ToolOutcome.Cancelled -> "cancelled"
        }

    private val ToolVision.wireValue: String
        get() = when (this) {
            ToolVision.Ok -> "ok"
            ToolVision.Disabled -> "disabled"
            ToolVision.Unavailable -> "unavailable"
            ToolVision.NotRequested -> "not_requested"
        }
}
