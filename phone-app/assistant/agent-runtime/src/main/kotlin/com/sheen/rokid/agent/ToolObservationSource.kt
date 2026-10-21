package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision

/**
 * edith-0.3 Phase 2.2 Task 3: the host-neutral source of a **best-effort post-call observation**, used when a screen
 * tool's failure/timeout/throw/refusal left no observation of its own.
 *
 * The agent loop calls this only for a non-`Done` result whose descriptor declared `screenObservation = true`. A pure
 * tool never reaches it, so its result keeps `observation = null` instead of a fabricated screen.
 *
 * [deadlineMillis] is the whole budget for one attempt (the loop passes a short failure deadline). An implementation
 * must return by then rather than block the run. When it knows the screen cannot be observed, it returns an explicit
 * `ToolObservation(vision = ToolVision.Unavailable, visionReason = ...)` — that reason is more useful to the model than
 * the loop's generic one. Returning `null` means the attempt timed out or the cause is unknown, not "the screen was
 * fine"; the loop maps `null` to `vision = unavailable` with `visionReason = "observation_timeout"`. A thrown
 * [Exception] gets that same mapping and is not an error path for the run, while a non-[Exception] `Throwable` is not
 * swallowed. The source must never return an observation containing invented facts, and it must not carry user content
 * into logs.
 *
 * This loop-level channel carries no image payload (`ToolObservation` has no image member; images belong to the tool's
 * own `ActionResult`). A failure capture therefore reports what it actually saw — normally [ToolVision.NotRequested] or
 * [ToolVision.Unavailable] — and must not claim [ToolVision.Ok] with no picture behind it.
 */
fun interface ToolObservationSource {
    fun observe(deadlineMillis: Long): ToolObservation?
}
