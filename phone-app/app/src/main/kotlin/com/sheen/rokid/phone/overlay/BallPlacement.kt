package com.sheen.rokid.phone.overlay

/** Which screen edge a docked floating ball is attached to. */
enum class BallEdge { LEFT, RIGHT }

/** A ball centre in screen pixels plus the edge it belongs to; the window is positioned around it. */
data class BallPosition(val edge: BallEdge, val centerX: Float, val centerY: Float)

/**
 * 0.5 (`D210`): the pure placement rules of the floating ball.
 *
 * The owner's approved interaction: the ball is draggable, snaps to the nearest left/right edge on release, and a docked
 * ball is **half hidden** - its centre sits on the screen edge, so exactly half of the icon is visible. The vertical
 * position is kept between the status bar and the navigation bar. All values are pixels; the Android service only
 * translates these decisions into window layout params.
 */
object BallPlacement {

    /** The default vertical dock: 62.5% down the screen, the preview's position. */
    const val DEFAULT_VERTICAL_FRACTION = 0.625f

    fun nearestEdge(centerX: Float, screenWidthPx: Int): BallEdge =
        if (centerX < screenWidthPx / 2f) BallEdge.LEFT else BallEdge.RIGHT

    /** The centre of a docked (half-hidden) ball: exactly the screen edge, so half of the icon is off-screen. */
    fun dockedCenterX(edge: BallEdge, screenWidthPx: Int): Float =
        if (edge == BallEdge.LEFT) 0f else screenWidthPx.toFloat()

    /**
     * The centre of a ball that must be fully visible (pressed, long-pressed, dragging): [marginPx] in from its edge.
     * The service slides a docked ball to this position on press and back to [dockedCenterX] on release.
     */
    fun visibleCenterX(edge: BallEdge, screenWidthPx: Int, radiusPx: Float, marginPx: Float): Float =
        if (edge == BallEdge.LEFT) radiusPx + marginPx else screenWidthPx - radiusPx - marginPx

    /**
     * Clamps a vertical centre so the whole ball stays between the top and bottom insets. The insets are the status
     * bar/navigation bar in pixels; a zero inset is a cutout-free screen.
     */
    fun clampCenterY(
        centerY: Float,
        screenHeightPx: Int,
        radiusPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): Float {
        val min = topInsetPx + radiusPx
        val max = screenHeightPx - bottomInsetPx - radiusPx
        if (max < min) return (screenHeightPx / 2f)
        return centerY.coerceIn(min, max)
    }

    /** The initial dock: the right edge at the default vertical fraction. */
    fun initial(
        screenWidthPx: Int,
        screenHeightPx: Int,
        radiusPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): BallPosition = BallPosition(
        edge = BallEdge.RIGHT,
        centerX = dockedCenterX(BallEdge.RIGHT, screenWidthPx),
        centerY = clampCenterY(
            screenHeightPx * DEFAULT_VERTICAL_FRACTION, screenHeightPx, radiusPx, topInsetPx, bottomInsetPx),
    )

    /**
     * Where a released drag lands: the nearest edge, half hidden, at the dragged vertical position (clamped). The caller
     * animates from [draggedX]/[draggedY] to the returned position.
     */
    fun snapAfterDrag(
        draggedX: Float,
        draggedY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        radiusPx: Float,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): BallPosition {
        val edge = nearestEdge(draggedX, screenWidthPx)
        return BallPosition(
            edge = edge,
            centerX = dockedCenterX(edge, screenWidthPx),
            centerY = clampCenterY(draggedY, screenHeightPx, radiusPx, topInsetPx, bottomInsetPx),
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
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
    ): Pair<Float, Float> = Pair(
        centerX.coerceIn(radiusPx, screenWidthPx - radiusPx),
        clampCenterY(centerY, screenHeightPx, radiusPx, topInsetPx, bottomInsetPx),
    )
}
