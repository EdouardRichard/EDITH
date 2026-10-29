package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5 (`D210`) / 0.5.1 (`C13`/`C15`): the dock/snap geometry, including the half-hidden edge rule and the top reserve
 * that keeps the whole bubble stack above the ball.
 *
 * All placement is pixels. The gap between the ball and the stack is [BubbleLayout.GAP_DP] converted at the current
 * density, never restated here; [BallPlacement.stackReservePx] is the one line the vertical clamps use.
 */
class BallPlacementTest {

    private val width = 1080
    private val height = 2400
    private val gapPx = BubbleLayout.GAP_DP * 3f

    @Test
    fun `the nearest edge is the side the centre is on`() {
        assertEquals(BallEdge.LEFT, BallPlacement.nearestEdge(100f, width))
        assertEquals(BallEdge.RIGHT, BallPlacement.nearestEdge(900f, width))
        assertEquals("the exact middle belongs to the right edge", BallEdge.RIGHT,
            BallPlacement.nearestEdge(540f, width))
    }

    @Test
    fun `a docked ball is half hidden with its centre on the edge`() {
        assertEquals(0f, BallPlacement.dockedCenterX(BallEdge.LEFT, width))
        assertEquals(1080f, BallPlacement.dockedCenterX(BallEdge.RIGHT, width))
    }

    @Test
    fun `a visible ball sits one radius plus margin in from its edge`() {
        assertEquals(100f + 20f, BallPlacement.visibleCenterX(BallEdge.LEFT, width, radiusPx = 100f, marginPx = 20f))
        assertEquals(1080f - 100f - 20f,
            BallPlacement.visibleCenterX(BallEdge.RIGHT, width, radiusPx = 100f, marginPx = 20f))
    }

    @Test
    fun `the vertical range is the stack reserve down to the supplied navigation inset`() {
        val r = 100f
        // The line itself, derived from T6's fraction and gap rather than from BallPlacement's own helper.
        val reserve = height * BubbleLayout.STACK_FRACTION + gapPx
        assertEquals(reserve + r,
            BallPlacement.clampCenterY(0f, height, r, gapPx = gapPx, topInsetPx = 60f, bottomInsetPx = 80f),
            0.01f)
        assertEquals(height - 80f - r,
            BallPlacement.clampCenterY(10_000f, height, r, gapPx = gapPx, topInsetPx = 60f, bottomInsetPx = 80f),
            0.01f)
    }

    @Test
    fun `the ball stops below the reserved 45 percent stack line`() {
        val radius = 84f
        val reserve = BallPlacement.stackReservePx(height, gapPx)

        assertEquals("the reserve is the 45% stack plus the one gap", 1_104f, reserve, 0.01f)
        assertEquals(
            "a drag far above the line is clamped to it",
            reserve + radius,
            BallPlacement.clampCenterY(-5_000f, height, radius, gapPx = gapPx),
            0.01f,
        )
        val (_, dragY) = BallPlacement.clampDragCenter(500f, -5_000f, width, height, radius, gapPx = gapPx)
        assertEquals("and the drag clamp uses the same line", reserve + radius, dragY, 0.01f)
        assertEquals(
            "a release snaps to the same line",
            reserve + radius,
            BallPlacement.snapAfterDrag(800f, -5_000f, width, height, radius, gapPx = gapPx).centerY,
            0.01f,
        )

        // The T6 anchor math audited from this side: at this line the bubble stack still gets its full 45% height.
        val anchor = BubbleLayout.anchorBottomPx(reserve + radius, radius, gapPx)
        assertEquals(height * BubbleLayout.STACK_FRACTION, BubbleLayout.availableHeightPx(height, anchor), 0.01f)
        assertEquals(8, BubbleLayout.maxLines(BubbleLayout.availableHeightPx(height, anchor), lineHeightPx = 60f))
    }

    @Test
    fun `the initial dock is pulled below the reserve line on a short screen`() {
        // 62.5% of 400 px is above the reserve (180 + 24 + 84), so even the first position respects the stack.
        val shortHeight = 400
        val gap = BubbleLayout.GAP_DP * 3f
        val initial = BallPlacement.initial(width, shortHeight, radiusPx = 84f, gapPx = gap)

        assertEquals(180f + gap + 84f, initial.centerY, 0.01f)
    }

    @Test
    fun `a released drag snaps using the supplied insets`() {
        val radius = 84f
        val bottomInset = 96f
        val low = BallPlacement.snapAfterDrag(
            900f, 10_000f, width, height, radius,
            gapPx = gapPx, bottomInsetPx = bottomInset,
        )
        assertEquals(height - bottomInset - radius, low.centerY, 0.01f)
        assertEquals(BallEdge.RIGHT, low.edge)

        // The bottom limit follows the supplied inset, not a hardcoded 96 dp: no inset allows the full bottom.
        val noInset = BallPlacement.snapAfterDrag(900f, 10_000f, width, height, radius, gapPx = gapPx)
        assertEquals(height - radius, noInset.centerY, 0.01f)
    }

    @Test
    fun `the snap duration stays within the owner approved 250 to 350 ms`() {
        assertTrue(BallPlacement.SNAP_DURATION_MILLIS >= 250L)
        assertTrue(BallPlacement.SNAP_DURATION_MILLIS <= 350L)
    }

    @Test
    fun `a large supplied status bar inset still wins over a small reserve`() {
        // 300 px screen: the reserve is 135 + 24 = 159 px; a 200 px status bar is lower, so it is the top limit.
        assertEquals(200f + 10f, BallPlacement.clampCenterY(0f, 300, 10f, gapPx = 24f, topInsetPx = 200f), 0.01f)
    }

    @Test
    fun `an initial dock is the right edge at the default fraction`() {
        val initial = BallPlacement.initial(width, height, radiusPx = 84f, gapPx = gapPx)

        assertEquals(BallEdge.RIGHT, initial.edge)
        assertEquals(1080f, initial.centerX)
        assertEquals(2400f * 0.625f, initial.centerY, 0.001f)
    }

    @Test
    fun `a released drag snaps to the nearest edge and keeps the vertical position`() {
        // Both dragged positions are inside the C13 vertical range (the top is the stack reserve line).
        val left = BallPlacement.snapAfterDrag(200f, 1_200f, width, height, radiusPx = 84f, gapPx = gapPx)
        assertEquals(BallEdge.LEFT, left.edge)
        assertEquals(0f, left.centerX)
        assertEquals(1_200f, left.centerY)

        val right = BallPlacement.snapAfterDrag(800f, 1900f, width, height, radiusPx = 84f, gapPx = gapPx)
        assertEquals(BallEdge.RIGHT, right.edge)
        assertEquals(1080f, right.centerX)
        assertEquals(1900f, right.centerY)
    }

    @Test
    fun `a release near an edge snaps and a middle release stays`() {
        val near = BallPlacement.snapAfterDrag(60f, 1_200f, width, height, radiusPx = 84f, gapPx = gapPx,
            snapThresholdPx = 100f)
        assertEquals(BallEdge.LEFT, near.edge)
        assertEquals(0f, near.centerX, 0f)

        val middle = BallPlacement.snapAfterDrag(500f, 1_200f, width, height, radiusPx = 84f, gapPx = gapPx,
            snapThresholdPx = 100f)
        assertEquals(BallEdge.LEFT, middle.edge)
        assertEquals("a release in the middle keeps the dragged centre", 500f, middle.centerX, 0f)
    }

    @Test
    fun `a drag keeps the whole ball on screen`() {
        val reserve = height * BubbleLayout.STACK_FRACTION + gapPx
        val (x, y) = BallPlacement.clampDragCenter(-50f, 10_000f, width, height, radiusPx = 84f, gapPx = gapPx)
        assertEquals(84f, x)
        assertEquals(2400f - 84f, y)

        val (x2, y2) = BallPlacement.clampDragCenter(5_000f, -50f, width, height, radiusPx = 84f, gapPx = gapPx)
        assertEquals(1080f - 84f, x2)
        assertEquals("the top of the drag range is the stack reserve, not the screen top", reserve + 84f, y2, 0.01f)
    }

    @Test
    fun `a tiny screen with impossible insets falls back to the middle`() {
        assertEquals(150f, BallPlacement.clampCenterY(0f, 300, radiusPx = 200f, gapPx = 24f,
            topInsetPx = 100f, bottomInsetPx = 100f))
    }

    @Test
    fun `the half hidden rule means exactly half the diameter is on screen`() {
        val radius = 84f
        val center = BallPlacement.dockedCenterX(BallEdge.RIGHT, 1080)
        val visible = 1080f - (center - radius)
        assertEquals("visible width equals one radius", radius, visible, 0.001f)
        assertFalse("the other half is off-screen", visible == radius * 2f)
        assertTrue(visible > 0f)
    }
}
