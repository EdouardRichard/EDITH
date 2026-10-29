package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 0.5.1 Important #2: a pointer cancelled by Compose (focus loss, a system gesture) must still settle the ball press.
 *
 * `awaitEachGesture` cancels its block with a `CancellationException`, so the composable's event loop exits without its
 * normal `up()` path. The composable's `finally` then runs [cancelBallPointerSequence]; without it the machine stayed
 * LONG_PRESSED, the service never received [BallGestureAction.LongPressEnd], and its accepted BALL lease (dictation
 * and the microphone indicator) lived until the dictation deadline; a late ambient final could still be sent.
 *
 * These are pure tests of the cleanup the composable runs. The Compose `pointerInput` call site itself (the `finally`
 * that guarantees the call on the cancellation path) has no Robolectric/Compose test rig in this module and is
 * device/T12.
 */
class BallPointerSequenceTest {

    private fun machine() = BallGestureMachine(slopPx = 24f, longPressMillis = 500L)

    @Test
    fun `cancelling an open long press ends the lease and resets the drag`() {
        val gesture = machine()
        val drag = BallDragController()
        gesture.down(100f, 100f, 0L)
        gesture.longPressStart(500L)
        drag.onPointerDown(50f, 50f, 10f, 10f)
        drag.beginDrag()

        val actions = cancelBallPointerSequence(gesture, drag)

        assertEquals(
            "cancellation ends the lease but is not a lift, so there is no tap or snap",
            listOf(BallGestureAction.LongPressEnd),
            actions,
        )
        assertFalse("the drag baseline must not leak into the next sequence", drag.isDragging)
        assertEquals(BallGestureMachine.Phase.IDLE, gesture.phase)
    }

    @Test
    fun `cancelling a drag does not snap and does not tap`() {
        // Cleanup must not be implemented as `up()`: a cancelled drag must not dock/snap the ball, and a cancelled
        // short press must not open the App.
        val gesture = machine()
        val drag = BallDragController()
        gesture.down(100f, 100f, 0L)
        gesture.move(140f, 100f, 100L)
        drag.onPointerDown(100f, 100f, 0f, 0f)
        drag.onPointerMove(140f, 100f, 0f, 0f)

        val actions = cancelBallPointerSequence(gesture, drag)

        assertEquals("a cancel is neither a tap nor a snap", emptyList<BallGestureAction>(), actions)
        assertEquals(BallGestureMachine.Phase.IDLE, gesture.phase)
        assertFalse(drag.isDragging)
    }

    @Test
    fun `cleanup after a normal lift does not repeat the lift actions`() {
        // The composable's `finally` runs on every exit, including the normal `changedToUp` path that already emitted
        // LongPressEnd. The second cleanup must be inert or the service would end the press twice.
        val gesture = machine()
        val drag = BallDragController()
        gesture.down(100f, 100f, 0L)
        gesture.longPressStart(500L)
        drag.onPointerDown(50f, 50f, 10f, 10f)
        assertEquals(listOf(BallGestureAction.LongPressEnd), gesture.up(900L))

        val cleanup = cancelBallPointerSequence(gesture, drag)

        assertEquals("the normal lift was already reported", emptyList<BallGestureAction>(), cleanup)
        assertEquals(BallGestureMachine.Phase.IDLE, gesture.phase)
    }
}
