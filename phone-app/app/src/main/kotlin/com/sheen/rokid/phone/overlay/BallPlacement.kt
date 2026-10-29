package com.sheen.rokid.phone.overlay

import kotlin.math.abs

/** Which screen edge a docked floating ball is attached to. */
enum class BallEdge { LEFT, RIGHT }

/** A ball centre in screen pixels plus the edge it belongs to; the window is positioned around it. */
data class BallPosition(val edge: BallEdge, val centerX: Float, val centerY: Float)

/**
 * 0.5 (`D210`) / 0.5.1 (`C13`/`C15`): the pure placement rules of the floating ball.
 *
 * The owner's approved interaction: the ball is draggable, snaps to the nearest left/right edge on release, and a docked
 * ball is **half hidden** - its centre sits on the screen edge, so exactly half of the icon is visible. All values are
 * pixels; the Android service only translates these decisions into window layout params.
 *
 * 0.5.1 (`C13`): the vertical range keeps the whole bubble stack above the ball. [stackReservePx] is the topmost the
 * ball's top edge may be - the 45% stack line plus one [BubbleLayout.GAP_DP] gap - so a ball dragged to the limit still
 * leaves the stack its full height. It is derived from T6's [BubbleLayout.reserveTopPx]; this object restates neither
 * the fraction nor the gap. The bottom limit is the real navigation inset the service supplies; the top limit also
 * respects a real status-bar inset if it is ever lower on the screen than the stack reserve.
 *
 * 0.5.1 (`C15`): [SNAP_DURATION_MILLIS] is the owner-approved snap animation duration.
 */
object BallPlacement {

    /** The default vertical dock: 62.5% down the screen, the preview's position. */
    const val DEFAULT_VERTICAL_FRACTION = 0.625f

    /** The dock animation duration; `C15` requires it between 250 and 350 ms (the 0.5 value was 220). */
    const val SNAP_DURATION_MILLIS = 300L

    fun nearestEdge(centerX: Float, screenWidthPx: Int): BallEdge =
        if (centerX < screenWidthPx / 2f) BallEdge.LEFT else BallEdge.RIGHT

    /** The centre of a docked (half-hidden) ball: exactly the screen edge, so half of the icon is off-screen. */
    fun dockedCenterX(edge: BallEdge, screenWidthPx: Int): Float =
        if (edge == BallEdge.LEFT) 0f else screenWidthPx.toFloat()

    /**
     * The topmost the ball's top edge may be: the 45% bubble-stack line plus [gapPx] (the stack is anchored one gap
     * above the ball). At this line [BubbleLayout.anchorBottomPx] gives the stack exactly its full 45% height. The gap
     * must be [BubbleLayout.GAP_DP] converted at the current density; it is passed in because this object is pure
     * pixels and has no display density.
     */
    fun stackReservePx(screenHeightPx: Int, gapPx: Float): Float =
        BubbleLayout.reserveTopPx(screenHeightPx, gapPx)

    /**
     * The centre of a ball that must be fully visible (pressed, long-pressed, dragging): [marginPx] in from its edge.
     * The service slides a docked ball to this position on press and back to [dockedCenterX] on release.
     */
    fun visibleCenterX(edge: BallEdge, screenWidthPx: Int, radiusPx: Float, marginPx: Float): Float =
        if (edge == BallEdge.LEFT) radiusPx + marginPx else screenWidthPx - radiusPx - marginPx

    /**
     * Clamps a vertical centre so the whole ball stays inside the usable screen: the top is the bubble-stack reserve
     * line (or the status-bar inset, if that is lower on the screen), the bottom is the navigation-bar inset. The
     * insets are real platform values in pixels; [gapPx] is the bubble gap in pixels.
     */
    fun clampCenterY(
        centerY: Float,
        screenHeightPx: Int,
        radiusPx: Float,
        gapPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): Float {
        val min = minCenterY(screenHeightPx, radiusPx, gapPx, topInsetPx)
        val max = screenHeightPx - bottomInsetPx - radiusPx
        if (max < min) return (screenHeightPx / 2f)
        return centerY.coerceIn(min, max)
    }

    /** The top of the vertical range: the stack reserve line (or a lower status bar) plus one radius. */
    private fun minCenterY(
        screenHeightPx: Int,
        radiusPx: Float,
        gapPx: Float,
        topInsetPx: Float,
    ): Float = maxOf(topInsetPx, stackReservePx(screenHeightPx, gapPx)) + radiusPx

    /** The initial dock: the right edge at the default vertical fraction, inside the same vertical range. */
    fun initial(
        screenWidthPx: Int,
        screenHeightPx: Int,
        radiusPx: Float,
        gapPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): BallPosition = BallPosition(
        edge = BallEdge.RIGHT,
        centerX = dockedCenterX(BallEdge.RIGHT, screenWidthPx),
        centerY = clampCenterY(
            screenHeightPx * DEFAULT_VERTICAL_FRACTION, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx),
    )

    /**
     * Where a released drag lands. It snaps to the nearest edge only when the released centre is within
     * [snapThresholdPx] of that edge; a release in the middle keeps the dragged centre (fully visible) with the
     * nearest edge label. The caller animates from [draggedX]/[draggedY] to the returned position.
     */
    fun snapAfterDrag(
        draggedX: Float,
        draggedY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        radiusPx: Float,
        gapPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
        snapThresholdPx: Float = Float.MAX_VALUE,
    ): BallPosition {
        val edge = nearestEdge(draggedX, screenWidthPx)
        val docked = dockedCenterX(edge, screenWidthPx)
        val centerX = if (abs(draggedX - docked) <= snapThresholdPx) {
            docked
        } else {
            draggedX.coerceIn(radiusPx, screenWidthPx - radiusPx)
        }
        return BallPosition(
            edge = edge,
            centerX = centerX,
            centerY = clampCenterY(draggedY, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx),
        )
    }

    /**
     * A drag keeps the ball fully on screen while the finger is down, so the user can grab it from either edge and the
     * icon never disappears under the finger; only the release hides it again.
     */
    fun clampDragCenter(
        centerX: Float,
        centerY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        radiusPx: Float,
        gapPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): Pair<Float, Float> = Pair(
        centerX.coerceIn(radiusPx, screenWidthPx - radiusPx),
        clampCenterY(centerY, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx),
    )
}
