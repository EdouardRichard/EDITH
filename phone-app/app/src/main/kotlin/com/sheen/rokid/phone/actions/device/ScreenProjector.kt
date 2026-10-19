package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationDiff
import com.sheen.rokid.device.ObservationRenderer
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.ScreenFilter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R41: the spec §4.6 `data.screen` projection.
 *
 * The kernel deliberately does not render (see [com.sheen.rokid.device.DeviceEnvelope]); this is
 * the one place the observation becomes the model-facing JSON:
 *
 * ```
 * {"screen":{"rev":42,"app":"com.android.settings","activity":null,"ime":false,
 *            "truncated":false,
 *            "changes":{"mode":"full","added":[],"updated":[],"removed":[]},
 *            "text":"#n16 button ..."}}
 * ```
 *
 * **Full vs diff.** The first projection and any [ObservationDiff] that answers `full` render the
 * whole screen (`text` non-null, empty change arrays). Every later projection is a diff: `added` and
 * `updated` are node lines rendered by the same renderer (same format, same shared registry), and
 * `removed` holds the **old** ref strings (`#n5`), because the node is gone from the new
 * observation. The diff's own >60-line/50%-change threshold is obeyed as-is (R41).
 *
 * **Failure.** A renderer exception is a data problem, never a tool crash: the projection becomes
 * `{"screen":null,"diagnostic":"..."}` — one line, never screen text.
 *
 * **State.** [project] keeps the previous observation so a sequence of calls produces diffs. The
 * same instance must be used for one turn's device calls (the kernel's auto-observation included);
 * [reset] starts a new full render.
 */
class ScreenProjector(
    private val registry: RefRegistry,
    private val renderFull: (Observation, ScreenFilter) -> String,
    private val renderChanged: (Observation, Collection<String>) -> String,
) {

    /**
     * The production shape: the renderer must have been built with the kernel's shared [registry],
     * or the refs in the diff lines would be unresolved by the kernel (R36).
     */
    constructor(renderer: ObservationRenderer, registry: RefRegistry) : this(
        registry = registry,
        renderFull = { observation, filter ->
            renderer.render(
                observation,
                filter,
                ObservationRenderer.DEFAULT_BUDGET_BYTES,
                ObservationRenderer.DEFAULT_MAX_LINES,
            )
        },
        renderChanged = { observation, paths -> renderer.renderNodes(observation, paths) },
    )

    private var previous: Observation? = null

    /** Forgets the previous snapshot, so the next projection is a full render again. */
    fun reset() {
        previous = null
    }

    /**
     * Projects [observation] as the `data` object of a device result.
     *
     * [filter] is the render filter of the operation that produced the observation (the observe
     * filter, or the kernel's last observation filter for an action's auto-observation).
     */
    fun project(observation: Observation, filter: ScreenFilter): JsonObject = try {
        buildJsonObject { put("screen", screen(observation, filter)) }
    } catch (t: Throwable) {
        buildJsonObject {
            put("screen", JsonNull)
            put("diagnostic", diagnostic(t))
        }
    }

    private fun screen(observation: Observation, filter: ScreenFilter): JsonObject {
        val last = previous
        val diff = last?.let { ObservationDiff.between(it, observation) }
        val full = diff == null || diff.mode == FULL_MODE
        val changes: JsonObject
        val text: String?
        if (full) {
            text = renderFull(observation, filter)
            changes = buildJsonObject {
                put("mode", FULL_MODE)
                put("added", buildJsonArray { })
                put("updated", buildJsonArray { })
                put("removed", buildJsonArray { })
            }
        } else {
            text = null
            val oldRefs = registry.assign(last!!)
            changes = buildJsonObject {
                put("mode", DIFF_MODE)
                put("added", lines(renderChanged(observation, diff.added)))
                put("updated", lines(renderChanged(observation, diff.updated)))
                put(
                    "removed",
                    buildJsonArray {
                        diff.removed.forEach { path ->
                            oldRefs[path]?.value?.let { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    },
                )
            }
        }
        previous = observation
        val app = applicationPackage(observation)
        return buildJsonObject {
            put("rev", observation.revision)
            if (app == null) put("app", JsonNull) else put("app", app)
            // The observation model has no activity class (AccessibilityPort.foreground does), so the
            // honest answer here is always null; Task 10's brief fixes that shape (R41).
            put("activity", JsonNull)
            put("ime", observation.windows.any { it.type == IME_WINDOW_TYPE })
            put("truncated", observation.truncated)
            put("changes", changes)
            if (text == null) put("text", JsonNull) else put("text", text)
        }
    }

    private fun applicationPackage(observation: Observation): String? =
        observation.windows
            .firstOrNull { it.type == APPLICATION_WINDOW_TYPE }
            ?.packageName
            ?.takeIf { it.isNotBlank() }

    private fun lines(rendered: String) = buildJsonArray {
        rendered.lines()
            .filter { it.isNotEmpty() }
            .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }

    private fun diagnostic(t: Throwable): String {
        val message = t.message?.lineSequence()?.firstOrNull()?.take(MAX_DIAGNOSTIC_CHARS)
        return message?.takeIf { it.isNotBlank() } ?: "screen projection failed: ${t.javaClass.simpleName}"
    }

    private companion object {
        const val FULL_MODE = "full"
        const val DIFF_MODE = "diff"
        const val APPLICATION_WINDOW_TYPE = "application"
        const val IME_WINDOW_TYPE = "ime"
        const val MAX_DIAGNOSTIC_CHARS = 200
    }
}
