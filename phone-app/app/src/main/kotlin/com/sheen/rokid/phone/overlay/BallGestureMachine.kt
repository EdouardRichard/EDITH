package com.sheen.rokid.phone.overlay

/** What one touch sequence decided; the service translates these into dictation/window/app actions. */
sealed interface BallGestureAction {
    /** The finger has been held past the threshold: show the microphone and start the ball's ASR. */
    data object LongPressStart : BallGestureAction

    /** The long-press finger lifted (or was cancelled): stop the ASR lease. */
    data object LongPressEnd : BallGestureAction

    /** A short press with no drag: no app action (the owner did not design tap-to-open). */
    data object Tap : BallGestureAction

    /** One drag delta in screen pixels; the service moves the window by it. */
    data class Drag(val dx: Float, val dy: Float) : BallGestureAction

    /** The finger's absolute screen position; the ball's centre follows it while dragging. */
    data class DragTo(val x: Float, val y: Float) : BallGestureAction

    /** The dragged finger lifted: dock to the nearest edge, half hidden. */
    data object Snap : BallGestureAction
}

/**
 * 0.5 (`D210`): the floating ball's touch state machine, extracted from Compose so the rules are JVM-testable.
 *
 * - a press that lifts inside [longPressMillis] without moving is a **tap** (open the App);
 * - moving more than [slopPx] before that turns the sequence into a **drag** and permanently cancels the long press;
 * - holding still past [longPressMillis] is a **long press**: the Compose layer's timer calls [longPressStart], the
 *   service shows the microphone and starts ASR, and the finger's later movement does not drag the ball;
 * - lifting after a drag emits [BallGestureAction.Snap]; a cancel emits a [BallGestureAction.LongPressEnd] if the ASR
 *   lease was open, never a tap.
 *
 * The machine reads only the coordinates/timestamps passed in; it holds no Android type. `now` is a monotonic
 * millisecond clock.
 */
class BallGestureMachine(
    private val slopPx: Float = 24f,
    private val longPressMillis: Long = 500L,
) {
    enum class Phase { IDLE, PRESSED, DRAGGING, LONG_PRESSED }

    var phase: Phase = Phase.IDLE
        private set

    private var downAtMillis = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun down(x: Float, y: Float, nowMillis: Long): List<BallGestureAction> {
        if (phase != Phase.IDLE) return emptyList()
        phase = Phase.PRESSED
        downAtMillis = nowMillis
        downX = x
        downY = y
        lastX = x
        lastY = y
        return emptyList()
    }

    fun move(x: Float, y: Float, nowMillis: Long): List<BallGestureAction> = when (phase) {
        Phase.PRESSED -> {
            if (distance(x, y) > slopPx) {
                phase = Phase.DRAGGING
                val action = BallGestureAction.Drag(x - lastX, y - lastY)
                lastX = x
                lastY = y
                listOf(action)
            } else {
                emptyList()
            }
        }
        Phase.DRAGGING -> {
            val action = BallGestureAction.Drag(x - lastX, y - lastY)
            lastX = x
            lastY = y
            listOf(action)
        }
        // A long press owns the gesture: the finger's movement must not turn it into a drag.
        Phase.LONG_PRESSED, Phase.IDLE -> emptyList()
    }

    /** The Compose layer's long-press timer asks this before calling [longPressStart]. */
    fun longPressDue(nowMillis: Long): Boolean =
        phase == Phase.PRESSED && nowMillis - downAtMillis >= longPressMillis

    /** Called by the timer when [longPressDue] was true; emits the action the service must act on. */
    fun longPressStart(nowMillis: Long): List<BallGestureAction> {
        if (!longPressDue(nowMillis)) return emptyList()
        phase = Phase.LONG_PRESSED
        return listOf(BallGestureAction.LongPressStart)
    }

    fun up(nowMillis: Long): List<BallGestureAction> = when (phase) {
        Phase.PRESSED -> {
            phase = Phase.IDLE
            if (nowMillis - downAtMillis < longPressMillis) {
                listOf(BallGestureAction.Tap)
            } else {
                // The timer never got to run (a stalled frame); still honour the hold as a complete long press.
                listOf(BallGestureAction.LongPressStart, BallGestureAction.LongPressEnd)
            }
        }
        Phase.DRAGGING -> {
            phase = Phase.IDLE
            listOf(BallGestureAction.Snap)
        }
        Phase.LONG_PRESSED -> {
            phase = Phase.IDLE
            listOf(BallGestureAction.LongPressEnd)
        }
        Phase.IDLE -> emptyList()
    }

    fun cancel(): List<BallGestureAction> {
        val wasLong = phase == Phase.LONG_PRESSED
        phase = Phase.IDLE
        return if (wasLong) listOf(BallGestureAction.LongPressEnd) else emptyList()
    }

    private fun distance(x: Float, y: Float): Float {
        val dx = x - downX
        val dy = y - downY
        return kotlin.math.hypot(dx, dy)
    }
}

/**
 * 0.5.1 (review Important #2 / C4): settles one ball pointer sequence when it ends without a lift.
 *
 * Compose's `awaitEachGesture` cancels its block when the pointer is cancelled - a focus change, a system gesture, the
 * overlay window being torn down - so the composable's normal `up()` path never runs. The composable calls this from its
 * `finally`, which is also the exit used when the pointer's change vanishes from the event stream (`break`). Without it
 * the machine stayed PRESSED/LONG_PRESSED/DRAGGING and the drag controller kept its baseline: the service never received
 * [BallGestureAction.LongPressEnd], and the BALL press it had accepted stayed open - dictation kept listening, the
 * microphone indicator stayed visible, and a late ambient final could still be sent - until the dictation deadline.
 *
 * The cleanup is deliberately [BallGestureMachine.cancel], never `up()`: a cancelled pointer is not a lift, so it must
 * not open the App or snap/dock the ball. It is a no-op after a normal `up()` (the machine is already IDLE), so the
 * `finally` cannot report the lift twice.
 */
internal fun cancelBallPointerSequence(
    gesture: BallGestureMachine,
    drag: BallDragController,
): List<BallGestureAction> {
    drag.onPointerUp()
    return gesture.cancel()
}
