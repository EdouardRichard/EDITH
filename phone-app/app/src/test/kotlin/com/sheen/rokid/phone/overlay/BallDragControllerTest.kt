package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 (`C11`): the ball drag is a **screen-space** pointer stream.
 *
 * The service moves the ball window under the finger while dragging, so the pointer's local position is not a stable
 * source of motion: after the window follows the finger, the local coordinate barely changes (or moves backwards when
 * the window catches up). The controller therefore combines the window origin the service recorded with the local
 * pointer position and reports absolute screen deltas. The down sample is the baseline, so the movement below the
 * gesture machine's slop is delivered with the first drag delta instead of being dropped.
 */
class BallDragControllerTest {

    @Test
    fun `a drag delta is the screen movement, not the local position change`() {
        val controller = BallDragController()
        controller.onPointerDown(localX = 50f, localY = 50f, windowOriginX = 100f, windowOriginY = 200f)
        controller.onPointerMove(localX = 60f, localY = 57f, windowOriginX = 100f, windowOriginY = 200f)
        assertEquals(DragDelta(10f, 7f), controller.beginDrag())

        // The service moved the window down/right by the finger's own movement; the window is now (110, 207). The
        // finger moves another (6, 5) on screen, so the local position only changes by (-4, -2). Reading the local
        // position change would drag the ball backwards (or not at all); the screen delta is (6, 5).
        assertEquals(DragDelta(6f, 5f), controller.onPointerMove(56f, 55f, 110f, 207f))
    }

    @Test
    fun `movement below the slop is delivered with the first drag delta`() {
        val controller = BallDragController()
        controller.onPointerDown(localX = 0f, localY = 0f, windowOriginX = 0f, windowOriginY = 0f)

        assertNull("the machine has not decided a drag yet", controller.onPointerMove(3f, 4f, 0f, 0f))
        assertNull("still below the slop", controller.onPointerMove(6f, 8f, 0f, 0f))
        assertFalse(controller.isDragging)

        assertEquals("the first drag delta includes every pixel since down", DragDelta(6f, 8f), controller.beginDrag())
        assertTrue(controller.isDragging)
        // After the drag began, only the new movement is delivered, so the ball is not moved twice.
        assertEquals(DragDelta(1f, 1f), controller.onPointerMove(7f, 9f, 0f, 0f))
    }

    @Test
    fun `window movement alone is not finger movement`() {
        val controller = BallDragController()
        controller.onPointerDown(localX = 40f, localY = 40f, windowOriginX = 100f, windowOriginY = 100f)
        controller.onPointerMove(localX = 50f, localY = 50f, windowOriginX = 100f, windowOriginY = 100f)
        controller.beginDrag()

        // The window catches up with the drag request while the finger holds still: the origin moves by the same
        // amount the local position loses, so the screen position is unchanged and the ball must not move again.
        assertEquals(DragDelta(0f, 0f), controller.onPointerMove(40f, 40f, 110f, 110f))
    }

    @Test
    fun `the actual origin is required, a stale requested origin jumps`() {
        val actual = BallDragController()
        actual.onPointerDown(localX = 10f, localY = 10f, windowOriginX = 0f, windowOriginY = 0f)
        actual.onPointerMove(localX = 15f, localY = 10f, windowOriginX = 5f, windowOriginY = 0f)
        assertEquals("with the actual 5px window move the finger moved 10px", DragDelta(10f, 0f), actual.beginDrag())

        val stale = BallDragController()
        stale.onPointerDown(localX = 10f, localY = 10f, windowOriginX = 0f, windowOriginY = 0f)
        stale.onPointerMove(localX = 15f, localY = 10f, windowOriginX = 10f, windowOriginY = 0f)
        assertEquals("a stale 10px origin plus the 15px local reports 15px, not the finger's 10px",
            DragDelta(15f, 0f), stale.beginDrag())
    }

    @Test
    fun `a new press starts a fresh baseline and a move without a down is ignored`() {
        val controller = BallDragController()
        assertNull("no pointer is down", controller.onPointerMove(50f, 50f, 10f, 10f))

        controller.onPointerDown(localX = 50f, localY = 50f, windowOriginX = 10f, windowOriginY = 10f)
        controller.onPointerUp()
        assertFalse(controller.isDragging)
        assertNull("a move after the lift is not a drag", controller.onPointerMove(50f, 50f, 10f, 10f))

        // A second press elsewhere in screen space must not report the distance from the previous gesture's samples.
        controller.onPointerDown(localX = 1f, localY = 1f, windowOriginX = 900f, windowOriginY = 1_000f)
        assertNull(controller.onPointerMove(5f, 5f, 900f, 1_000f))
        assertEquals(DragDelta(4f, 4f), controller.beginDrag())
    }
}
