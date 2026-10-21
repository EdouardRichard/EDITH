package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolObservation

/**
 * edith-0.3 Phase 2.2 Task 3: the host-neutral source of a **best-effort post-call observation**, used when a screen
 * tool's failure/timeout/throw/refusal left no observation of its own.
 *
 * The agent loop calls this only for a non-`Done` result whose descriptor declared `screenObservation = true`. A pure
 * tool never reaches it, so its result keeps `observation = null` instead of a fabricated screen.
 *
 * [deadlineMillis] is the whole budget for one attempt (the loop passes a short failure deadline). An implementation
 * must return by then rather than block the run: [observe] returns the observation it could capture, or `null` when it
 * has nothing honest to report. The loop treats `null` and a thrown exception the same way — it records
 * `vision = unavailable` with `visionReason = "observation_timeout"` — so throwing is not an error path for the run, but
 * returning `null` is cheaper and preferred. The source must never return an observation containing invented facts, and
 * it must not carry user content into logs.
 */
fun interface ToolObservationSource {
    fun observe(deadlineMillis: Long): ToolObservation?
}
