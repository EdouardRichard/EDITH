package com.sheen.rokid.output

import com.sheen.rokid.plugin.PerceptionCoverage

/**
 * edith-0.3 Phase 2 Task 6: renders [PerceptionCoverage] as the one-line model-facing token string (spec §7.3).
 *
 * The exact shape is `screen=ok notifications=ok media=... calendar=... acoustic=... clipboard=... vision=...
 * glasses=... device=...`: every source is named even when it is unavailable, because "the host cannot see this
 * source" must be visible to the model instead of looking like an empty source. Tokens are the builder's own vocabulary
 * (`ok`/`empty`/`truncated`/`unavailable(reason)`/`disabled`/`partial`); the renderer only joins them and never
 * invents or normalises one.
 *
 * This is pure and content-free: a coverage line carries no screen text, title, clipboard value or bounds.
 */
object PerceptionCoverageRenderer {

    /** The exact, stable token line for [coverage]. */
    fun render(coverage: PerceptionCoverage): String = buildString {
        append("screen=").append(coverage.screen)
        append(" notifications=").append(coverage.notifications)
        append(" media=").append(coverage.media)
        append(" calendar=").append(coverage.calendar)
        append(" acoustic=").append(coverage.acoustic)
        append(" clipboard=").append(coverage.clipboard)
        append(" vision=").append(coverage.vision)
        append(" glasses=").append(coverage.glasses)
        append(" device=").append(coverage.device)
    }
}
