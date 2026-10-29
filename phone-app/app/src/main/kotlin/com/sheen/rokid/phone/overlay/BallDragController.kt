package com.sheen.rokid.phone.overlay

/** One screen-space pointer delta in pixels; the service adds it to the ball's centre. */
data class DragDelta(val dx: Float, val dy: Float)

/**
 * 0.5.1 (`C11`): translates the ball window's pointer stream into **screen-space** drag deltas.
 *
 * The service moves the ball window under the finger while dragging. A pointer's local position is therefore not a
 * stable source of motion: once the window has followed the finger, the local coordinate barely changes, and when the
 * window catches up with a pending move it changes backwards. The service records the ball window's origin, and this
 * controller combines it with the local pointer position so the reported delta is the finger's movement on screen.
 *
 * The down sample is the baseline. Movement below the gesture machine's slop is accumulated and delivered by
 * [beginDrag] in one delta, so the first drag includes every pixel travelled since the finger touched down instead of
 * dropping the sub-slop part. [BallGestureMachine] still owns tap/long-press/snap decisions; this class only reports
 * motion.
 *
 * The origin passed for every sample must be the view's **actual** on-screen location (`getLocationOnScreen`), not the
 * requested `WindowManager.LayoutParams`: the requested layout origin lags `updateViewLayout`, and a stale origin makes
 * the computed screen position (and therefore the ball) jump.
 */
class BallDragController {

    /** Whether [beginDrag] has been called for the current pointer sequence. */
    var isDragging: Boolean = false
        private set

    private var tracking = false
    private var lastScreenX = 0f
    private var lastScreenY = 0f

    /** Movement seen while the gesture machine had not decided a drag yet, in screen pixels. */
    private var pendingDx = 0f
    private var pendingDy = 0f

    /**
     * Starts one pointer sequence at the pointer's local position [localX]/[localY] inside a window whose screen origin
     * is [windowOriginX]/[windowOriginY]. Every later sample must be combined with the **current** origin: the window
     * moves during the drag.
     */
    fun onPointerDown(localX: Float, localY: Float, windowOriginX: Float, windowOriginY: Float) {
        tracking = true
        isDragging = false
        lastScreenX = windowOriginX + localX
        lastScreenY = windowOriginY + localY
        pendingDx = 0f
        pendingDy = 0f
    }

    /**
     * Records one pointer move at the current window origin. Returns the screen delta since the previous sample once
     * [beginDrag] has started the drag, or `null` while the sequence is still below the gesture machine's slop (the
     * movement is kept for [beginDrag]).
     */
    fun onPointerMove(
        localX: Float,
        localY: Float,
        windowOriginX: Float,
        windowOriginY: Float,
    ): DragDelta? {
        if (!tracking) return null
        val screenX = windowOriginX + localX
        val screenY = windowOriginY + localY
        val dx = screenX - lastScreenX
        val dy = screenY - lastScreenY
        lastScreenX = screenX
        lastScreenY = screenY
        if (!isDragging) {
            pendingDx += dx
            pendingDy += dy
            return null
        }
        return DragDelta(dx, dy)
    }

    /**
     * Called once when [BallGestureMachine] turns the sequence into a drag: returns the whole screen movement since
     * the down sample (including the sub-slop part) and switches [onPointerMove] to per-sample deltas.
     */
    fun beginDrag(): DragDelta {
        isDragging = true
        val delta = DragDelta(pendingDx, pendingDy)
        pendingDx = 0f
        pendingDy = 0f
        return delta
    }

    /** Ends the pointer sequence; the next [onPointerDown] starts a fresh baseline. */
    fun onPointerUp() {
        tracking = false
        isDragging = false
        pendingDx = 0f
        pendingDy = 0f
    }
}
