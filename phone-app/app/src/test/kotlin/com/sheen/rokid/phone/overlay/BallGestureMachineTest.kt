package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5 (`D210`): tap / drag / long-press arbitration for the floating ball. */
class BallGestureMachineTest {

    private val machine = BallGestureMachine(slopPx = 24f, longPressMillis = 500L)

    @Test
    fun aShortPressWithoutMovementIsATap() {
        assertEquals(emptyList<BallGestureAction>(), machine.down(100f, 100f, 0L))
        assertEquals(listOf(BallGestureAction.Tap), machine.up(200L))
        assertEquals(BallGestureMachine.Phase.IDLE, machine.phase)
    }

    @Test
    fun aHoldPastTheThresholdIsALongPressAndTheLiftEndsIt() {
        machine.down(100f, 100f, 0L)
        assertFalse(machine.longPressDue(499L))
        assertTrue(machine.longPressDue(500L))

        assertEquals(listOf(BallGestureAction.LongPressStart), machine.longPressStart(500L))
        assertEquals(BallGestureMachine.Phase.LONG_PRESSED, machine.phase)
        assertEquals(listOf(BallGestureAction.LongPressEnd), machine.up(900L))
    }

    @Test
    fun movingPastTheSlopBecomesADragAndCancelsTheLongPress() {
        machine.down(100f, 100f, 0L)
        assertEquals(emptyList<BallGestureAction>(), machine.move(110f, 110f, 100L))
        assertTrue(machine.phase == BallGestureMachine.Phase.PRESSED)

        val actions = machine.move(140f, 100f, 200L)
        assertEquals("the first drag delta starts at the down point, so the sub-slop movement is not lost", listOf(
            BallGestureAction.Drag(40f, 0f),
        ), actions)
        assertEquals(BallGestureMachine.Phase.DRAGGING, machine.phase)
        assertFalse("a drag can never become a long press", machine.longPressDue(900L))

        assertEquals(listOf(BallGestureAction.Drag(10f, 20f)), machine.move(150f, 120f, 300L))
        assertEquals(listOf(BallGestureAction.Snap), machine.up(600L))
    }

    @Test
    fun aLongPressedBallIgnoresLaterMovement() {
        machine.down(100f, 100f, 0L)
        machine.longPressStart(600L)
        assertEquals(emptyList<BallGestureAction>(), machine.move(400f, 700f, 700L))
        assertEquals(listOf(BallGestureAction.LongPressEnd), machine.up(1_000L))
    }

    @Test
    fun aTimerThatNeverRanStillHonoursTheHoldOnLift() {
        machine.down(100f, 100f, 0L)

        assertEquals(
            listOf(BallGestureAction.LongPressStart, BallGestureAction.LongPressEnd),
            machine.up(800L),
        )
    }

    @Test
    fun aCancelEndsAnOpenLongPressAndDoesNotTap() {
        machine.down(100f, 100f, 0L)
        machine.longPressStart(600L)

        assertEquals(listOf(BallGestureAction.LongPressEnd), machine.cancel())
        assertEquals(BallGestureMachine.Phase.IDLE, machine.phase)

        machine.down(100f, 100f, 1_000L)
        assertEquals(emptyList<BallGestureAction>(), machine.cancel())
    }

    @Test
    fun aSecondDownWhilePressedIsIgnored() {
        machine.down(100f, 100f, 0L)
        assertEquals(emptyList<BallGestureAction>(), machine.down(300f, 300f, 10L))
        assertEquals(BallGestureMachine.Phase.PRESSED, machine.phase)
    }
}
