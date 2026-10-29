package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5 (`D210`): the dock/snap geometry, including the half-hidden edge rule. */
class BallPlacementTest {

    private val width = 1080
    private val height = 2400

    @Test
    fun theNearestEdgeIsTheSideTheCentreIsOn() {
        assertEquals(BallEdge.LEFT, BallPlacement.nearestEdge(100f, width))
        assertEquals(BallEdge.RIGHT, BallPlacement.nearestEdge(900f, width))
        assertEquals("the exact middle belongs to the right edge", BallEdge.RIGHT,
            BallPlacement.nearestEdge(540f, width))
    }

    @Test
    fun aDockedBallIsHalfHiddenWithItsCentreOnTheEdge() {
        assertEquals(0f, BallPlacement.dockedCenterX(BallEdge.LEFT, width))
        assertEquals(1080f, BallPlacement.dockedCenterX(BallEdge.RIGHT, width))
    }

    @Test
    fun aVisibleBallSitsOneRadiusPlusMarginInFromItsEdge() {
        assertEquals(100f + 20f, BallPlacement.visibleCenterX(BallEdge.LEFT, width, radiusPx = 100f, marginPx = 20f))
        assertEquals(1080f - 100f - 20f,
            BallPlacement.visibleCenterX(BallEdge.RIGHT, width, radiusPx = 100f, marginPx = 20f))
    }

    @Test
    fun theVerticalPositionStaysBetweenTheSystemInsets() {
        val r = 100f
        assertEquals(60f + r, BallPlacement.clampCenterY(0f, height, r, topInsetPx = 60f, bottomInsetPx = 80f))
        assertEquals(height - 80f - r,
            BallPlacement.clampCenterY(10_000f, height, r, topInsetPx = 60f, bottomInsetPx = 80f))
    }

    @Test
    fun anInitialDockIsTheRightEdgeAtTheDefaultFraction() {
        val initial = BallPlacement.initial(width, height, radiusPx = 84f)

        assertEquals(BallEdge.RIGHT, initial.edge)
        assertEquals(1080f, initial.centerX)
        assertEquals(2400f * 0.625f, initial.centerY, 0.001f)
    }

    @Test
    fun aReleasedDragSnapsToTheNearestEdgeAndKeepsTheVerticalPosition() {
        val left = BallPlacement.snapAfterDrag(200f, 900f, width, height, radiusPx = 84f)
        assertEquals(BallEdge.LEFT, left.edge)
        assertEquals(0f, left.centerX)
        assertEquals(900f, left.centerY)

        val right = BallPlacement.snapAfterDrag(800f, 1900f, width, height, radiusPx = 84f)
        assertEquals(BallEdge.RIGHT, right.edge)
        assertEquals(1080f, right.centerX)
        assertEquals(1900f, right.centerY)
    }

    @Test
    fun aDragKeepsTheWholeBallOnScreen() {
        val (x, y) = BallPlacement.clampDragCenter(-50f, 10_000f, width, height, radiusPx = 84f)
        assertEquals(84f, x)
        assertEquals(2400f - 84f, y)

        val (x2, y2) = BallPlacement.clampDragCenter(5_000f, -50f, width, height, radiusPx = 84f)
        assertEquals(1080f - 84f, x2)
        assertEquals(84f, y2)
    }

    @Test
    fun aTinyScreenWithImpossibleInsetsFallsBackToTheMiddle() {
        assertEquals(150f, BallPlacement.clampCenterY(0f, 300, radiusPx = 200f, topInsetPx = 100f,
            bottomInsetPx = 100f))
    }

    @Test
    fun theHalfHiddenRuleMeansExactlyHalfTheDiameterIsOnScreen() {
        val radius = 84f
        val center = BallPlacement.dockedCenterX(BallEdge.RIGHT, 1080)
        val visible = 1080f - (center - radius)
        assertEquals("visible width equals one radius", radius, visible, 0.001f)
        assertFalse("the other half is off-screen", visible == radius * 2f)
        assertTrue(visible > 0f)
    }
}
