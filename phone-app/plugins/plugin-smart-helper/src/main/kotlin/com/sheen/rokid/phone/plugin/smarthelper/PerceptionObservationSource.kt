package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ToolObservationSource
import com.sheen.rokid.output.PerceptionToolObservation
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision

/** The one source a failure observation reads: the compact screen, never the notification/calendar/... bulk. */
private val FAILURE_OBSERVATION_SOURCES: Set<PerceptionSource> = setOf(PerceptionSource.SCREEN)

/**
 * edith-0.3 Phase 2.2 Task 6: the plugin's loop-level [ToolObservationSource], backed by the session's existing
 * [PerceptionProvider].
 *
 * The agent loop asks it only after a **non-`Done`** result from a descriptor that declared `screenObservation = true`
 * and that came back without an observation of its own (a policy/confirmation refusal, a confirmed or uncertain timeout,
 * a thrown tool). Tool-level failures (Tasks 4-6) already carry their own richer observation including a picture, so
 * this source is the fallback for the paths a tool cannot observe from inside.
 *
 * It is deliberately **cache-only**: the turn boundary opened the provider at the start of the run and every host
 * action refreshes it, so `snapshot()` projects the current turn without a second expensive device read - the loop's
 * failure budget is 250 ms and this source must honour it (the platform reads also cannot be preempted). The result
 * carries the compact screen text, foreground, coverage and the vision fact; it carries **no image**, because
 * [ToolObservation] has no image member and this channel has no `ImageRef`. A loop-level timeout/throw image is therefore
 * `NotMeasured` (recorded by the device evidence), not fabricated here: `vision` stays `NotRequested` for an observed
 * screen and becomes `Unavailable(reason)` when the screen source itself did not answer (for example
 * `no_accessibility`).
 *
 * A provider that throws (or a session without a port) yields `null`, which the loop records as an explicit
 * `unavailable/observation_timeout` or `unavailable/no_source` - never a silent null for a screen tool. The plugin never
 * invents a screen: the coverage token is the provider's own answer, and a blind snapshot reports `screen = null`.
 */
internal fun perceptionToolObservationSource(provider: PerceptionProvider?): ToolObservationSource? =
    provider?.let { port ->
        ToolObservationSource { _ ->
            // Only an Exception is a failed read. A non-Exception Throwable is not swallowed here for the same reason
            // the loop does not swallow it: an Error is the run owner's problem, not an observation failure.
            val snapshot = try {
                port.snapshot(FAILURE_OBSERVATION_SOURCES, PerceptionFilter.ALL, null)
            } catch (_: Exception) {
                null
            }
            snapshot?.let { facts ->
                val unavailableReason = PerceptionToolObservation.unavailableReason(facts.coverage.screen)
                PerceptionToolObservation.render(
                    facts,
                    vision = if (unavailableReason != null) ToolVision.Unavailable else ToolVision.NotRequested,
                    visionReason = unavailableReason,
                )
            }
        }
    }
