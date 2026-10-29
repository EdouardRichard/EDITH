package com.sheen.rokid.phone.overlay

/**
 * 0.5.1 (`C6`/`C13`): the pure geometry of the bubble stack above the ball.
 *
 * All values are pixels; the Compose content converts them to dp. The stack is bottom-anchored one gap above the
 * ball's top edge, never wider than [WIDTH_FRACTION] of the screen and never taller than [STACK_FRACTION] of it. The
 * same 45% is the vertical reserve the ball must keep free: [reserveTopPx] is the line T7 clamps the ball to, and at
 * that line the stack still gets its full height. [MAX_LINES] is the per-bubble text cap, reduced when the available
 * height cannot fit that many lines (large font scale / small screens).
 */
object BubbleLayout {

    /**
     * The one gap between the ball's top edge and the bubble stack's bottom (the same gap separates two bubbles).
     *
     * T7's `BallPlacement` must take its vertical reserve gap from this constant through [reserveTopPx] instead of
     * restating `8.dp`, so the ball clamp and the stack anchor cannot drift apart. Defined in dp because the callers
     * convert it with the current density; this object stays free of Compose types/pixels.
     */
    const val GAP_DP = 8

    /** The design's per-message text cap (`D217`); an over-long answer is ellipsized. */
    const val MAX_LINES = 8

    /** The stack's max width as a fraction of the screen (0.5's 72%). */
    const val WIDTH_FRACTION = 0.72f

    /** The stack's max height as a fraction of the screen; also the top reserve the ball must leave free. */
    const val STACK_FRACTION = 0.45f

    /**
     * The topmost the ball's top edge may be: the 45% stack plus the gap, so the stack's bottom lands exactly on the
     * 45% line. T7 consumes this as `BallPlacement`'s vertical reserve; the bubble stays above the ball.
     */
    fun reserveTopPx(screenHeightPx: Int, gapPx: Float): Float =
        (screenHeightPx * STACK_FRACTION + gapPx).coerceIn(0f, screenHeightPx.toFloat())

    /** The stack's bottom edge: one [gapPx] above the ball's top edge, never above the screen's top. */
    fun anchorBottomPx(centerY: Float, radiusPx: Float, gapPx: Float): Float =
        (centerY - radiusPx - gapPx).coerceAtLeast(0f)

    /** The usable stack height: the space above [anchorBottomPx], capped at [STACK_FRACTION] of the screen. */
    fun availableHeightPx(screenHeightPx: Int, anchorBottomPx: Float): Float =
        anchorBottomPx.coerceIn(0f, (screenHeightPx * STACK_FRACTION).coerceAtLeast(0f))

    /** The bubble's max width. */
    fun availableWidthPx(screenWidthPx: Int): Float = (screenWidthPx * WIDTH_FRACTION).coerceAtLeast(0f)

    /** The text line cap: the design's eight lines, reduced when [availableHeightPx] cannot show that many. */
    fun maxLines(availableHeightPx: Float, lineHeightPx: Float): Int {
        if (availableHeightPx <= 0f || lineHeightPx <= 0f) return 1
        return (availableHeightPx / lineHeightPx).toInt().coerceIn(1, MAX_LINES)
    }
}
