package com.sheen.rokid.phone.debug

import com.sheen.rokid.plugin.ActionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Debug-only projection of one production [ActionResult] onto the facts the host-started probes log.
 *
 * 0.3 Phase 2.2 Task 8 moved the production result from a flat detail string to the structured contract:
 * [ActionResult.observation] carries the compact screen, [ActionResult.execution] the stable code/applied facts, and
 * [ActionResult.detail] is now **tool-specific data** (`open`'s `candidates`, `shell`'s exit/stdout, ...), not the
 * rendered envelope. The probes must read those members; parsing `detail` as an envelope silently produced no screen.
 *
 * This helper exists so the projection is a pure, JVM-testable function instead of three untested Activity bodies.
 * It is in the `debug` source set and must never be used by production code.
 */
internal object ProbeResultProjection {

    /** Counts and identifiers only: no screen text or tool body may be logged by a probe. */
    data class Facts(
        val screen: String?,
        val executionCode: String?,
        val applied: Boolean?,
        val candidateCount: Int,
    )

    fun of(result: ActionResult?): Facts {
        if (result == null) return Facts(screen = null, executionCode = null, applied = null, candidateCount = 0)
        val data = result.detail.takeIf { it.isNotBlank() }?.let { detail ->
            runCatching { Json.parseToJsonElement(detail) as? JsonObject }.getOrNull()
        }
        return Facts(
            screen = result.observation?.screen,
            executionCode = result.execution?.code,
            applied = result.execution?.applied,
            candidateCount = (data?.get("candidates") as? JsonArray)?.size ?: 0,
        )
    }
}
