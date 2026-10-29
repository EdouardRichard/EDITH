package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5.1 (`C6`/`C13`): the pure geometry of the bubble stack above the ball.
 *
 * The stack is bottom-anchored one gap above the ball's top edge, never wider than 72% of the screen and never taller
 * than 45% of it. The gap is [BubbleLayout.GAP_DP], the same value the renderer uses and the one T7's `BallPlacement`
 * must consume for its vertical reserve. The 45% is also the vertical reserve the ball must keep free:
 * [BubbleLayout.reserveTopPx] is the line T7 clamps the ball to, and at that line the stack still gets its full height.
 * A tower of one bubble may show at most eight text lines, and fewer when the space cannot fit that many.
 */
class BubbleLayoutTest {

    @Test
    fun `a top-docked ball still gets a usable bubble stack height`() {
        val screenHeightPx = 2_400
        val radiusPx = 84f
        // The exposed gap at a 3x density: the test follows BubbleLayout.GAP_DP instead of restating 24 px.
        val gapPx = BubbleLayout.GAP_DP * 3f

        val ballTopPx = BubbleLayout.reserveTopPx(screenHeightPx, gapPx)
        assertEquals("the reserved line is the 45% stack plus the gap", 1_104f, ballTopPx, 0.01f)

        val anchorPx = BubbleLayout.anchorBottomPx(ballTopPx + radiusPx, radiusPx, gapPx)
        assertEquals("the stack bottom sits exactly on the 45% line", 1_080f, anchorPx, 0.01f)

        val heightPx = BubbleLayout.availableHeightPx(screenHeightPx, anchorPx)
        assertEquals("a top-docked ball still leaves the full 45% stack", 1_080f, heightPx, 0.01f)
        assertEquals("and eight lines fit", 8, BubbleLayout.maxLines(heightPx, lineHeightPx = 60f))
    }

    @Test
    fun `the stack is never taller than forty five percent of the screen`() {
        assertEquals(1_080f, BubbleLayout.availableHeightPx(2_400, anchorBottomPx = 2_000f), 0.01f)
        assertEquals(500f, BubbleLayout.availableHeightPx(2_400, anchorBottomPx = 500f), 0.01f)
        assertEquals(0f, BubbleLayout.availableHeightPx(2_400, anchorBottomPx = -20f), 0.01f)
    }

    @Test
    fun `the anchor is one gap above the ball top edge`() {
        assertEquals(1_000f, BubbleLayout.anchorBottomPx(centerY = 1_100f, radiusPx = 84f, gapPx = 16f), 0.01f)
        assertEquals(0f, BubbleLayout.anchorBottomPx(centerY = 10f, radiusPx = 40f, gapPx = 16f), 0.01f)
    }

    @Test
    fun `the bubble is at most seventy two percent of the screen wide`() {
        assertEquals(720f, BubbleLayout.availableWidthPx(1_000), 0.01f)
        assertEquals(0f, BubbleLayout.availableWidthPx(0), 0.01f)
    }

    @Test
    fun `text is clamped to eight lines and to the space the stack can show`() {
        assertEquals(8, BubbleLayout.maxLines(availableHeightPx = 1_080f, lineHeightPx = 60f))
        assertEquals(3, BubbleLayout.maxLines(availableHeightPx = 200f, lineHeightPx = 60f))
        assertEquals(1, BubbleLayout.maxLines(availableHeightPx = 10f, lineHeightPx = 60f))
        assertEquals(1, BubbleLayout.maxLines(availableHeightPx = 1_000f, lineHeightPx = 0f))
    }
}
