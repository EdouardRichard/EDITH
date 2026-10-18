package com.sheen.rokid.glasses.display

/**
 * 0.23 P4 (spec §5.2.2, U10): the official auto-scroll engine, decided without Android.
 *
 * The official implementation (`AiAssistantDialog.startAutoScroll`, parsed from CFR Java) is a `ValueAnimator` with
 * `duration = Integer.MAX_VALUE` and an update listener that skips the first three frames and then calls
 * `scrollBy(0, 1)`. A page key is `min(remaining, viewport * 0.85)`. Those numbers are the whole contract, and they are
 * pinned here so the Activity only owns the `View` plumbing.
 *
 * `HintReadingBudget` is deliberately **not** consulted (D131): the phone's TTL stays the old conservative value, and
 * 0.23 does not couple the ticker change to a TTL calibration.
 */
object HintTicker {

    /** One pixel per frame, as the official listener does. */
    const val PIXELS_PER_FRAME = 1

    /** The first frames are skipped while the list settles after a rebuild. */
    const val SKIP_FRAMES = 3

    /** One page is 85% of the viewport, or the remaining distance when that is smaller. */
    const val PAGE_FRACTION = 0.85f

    /**
     * The offset after [frames] frames of the ticker, starting at [startOffset].
     *
     * The first [SKIP_FRAMES] frames do not move. After that it is one pixel per frame, clamped to `[0, maxOffset]`.
     */
    fun offsetAfterFrames(startOffset: Int, frames: Int, maxOffset: Int): Int {
        val max = maxOffset.coerceAtLeast(0)
        val start = startOffset.coerceIn(0, max)
        if (max == 0 || frames <= SKIP_FRAMES) return start
        return (start + (frames - SKIP_FRAMES) * PIXELS_PER_FRAME).coerceAtMost(max)
    }

    /** One page: the smaller of the remaining distance and 85% of the viewport. Negative input is the end. */
    fun pageStepPx(remainingPx: Int, viewportPx: Int): Int {
        val viewport = viewportPx.coerceAtLeast(0)
        return minOf(remainingPx.coerceAtLeast(0), (viewport * PAGE_FRACTION).toInt())
    }

    /** True while the list has somewhere further to go. */
    fun shouldScroll(offsetPx: Int, maxOffsetPx: Int): Boolean = offsetPx < maxOffsetPx

    /**
     * Whether the ticker may run.
     *
     * 0.24 spec §3.3/§5.3: **the pin is not a condition**. The official auto-scroll is stopped by the wearer's own page,
     * by reaching the end, or by the panel hiding - never by `holdAIAssistant()`. Kept here as one pure decision so the
     * host cannot quietly add a pin term back.
     */
    fun shouldRun(hasOverflow: Boolean, userPaused: Boolean, paused: Boolean): Boolean =
        hasOverflow && !userPaused && !paused
}
