package com.sheen.rokid.phone.settings

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.sheen.rokid.phone.ui.settings.HelpPopupPositionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 Task 8 fix: the popover side and the arrow position must be derived from the **same** placement data the popup
 * window actually uses.
 *
 * The round-1 implementation compared a popup-window-local `boundsInWindow()` bottom with the icon's app-window top and
 * subtracted a popup-local `left` from an app-window icon centre. That mixed two coordinate spaces, so a popup placed
 * *below* an icon whose top was larger than the popup height was reported as "above" (arrow flipped and shifted).
 *
 * [HelpPopupPositionProvider] receives the anchor bounds and the popup content size in parent-window coordinates and
 * returns the popup origin in that same space, so it is the one place where the above/below relation and the arrow
 * offset can be derived without any cross-window arithmetic. These JVM cases pin that geometry; the Compose case in
 * `HelpPopoverTest` pins the same value on a real `Popup` window on the K40.
 */
class HelpPopoverGeometryTest {

    private val gapPx = 10
    private val marginPx = 30
    private val window = IntSize(1080, 2400)
    private val popup = IntSize(700, 800)

    private fun provider() = HelpPopupPositionProvider(gapPx, marginPx)

    private fun HelpPopupPositionProvider.place(anchor: IntRect): IntOffset =
        calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

    /** The reported bug's geometry: the icon top (1200) is larger than the popup height (800), and the popup is below. */
    @Test
    fun aBelowPlacementIsNotReportedAsAboveWhenTheIconTopExceedsThePopupHeight() {
        val anchor = IntRect(400, 1200, 496, 1296)
        val provider = provider()

        val position = provider.place(anchor)

        // Still positioned under the icon, exactly as before the fix.
        assertEquals(IntOffset(98, 1306), position)
        val placement = assertNotNull(provider.placement)
        assertFalse("a popup under the icon must stay on the below side", placement.above)
        // Icon centre (448) relative to the popup left (98): the arrow sits on the popup centre line, not off-screen.
        assertEquals(350f, placement.arrowOffsetX, 0f)
    }

    /** No room below: the provider flips the popup above the icon, so the arrow must be on the bottom edge. */
    @Test
    fun noRoomBelowFlipsThePlacementAbove() {
        val anchor = IntRect(400, 2100, 496, 2196)
        val provider = provider()

        val position = provider.place(anchor)

        assertEquals(IntOffset(98, 1290), position)
        val placement = assertNotNull(provider.placement)
        assertTrue("the popup above the icon must report the above side", placement.above)
        assertEquals(350f, placement.arrowOffsetX, 0f)
    }

    /** Near the window edge the popup left is clamped; the arrow offset must follow the clamped left, not the ideal one. */
    @Test
    fun theArrowOffsetFollowsTheClampedPopupLeft() {
        val anchor = IntRect(0, 1200, 96, 1296)
        val provider = provider()

        val position = provider.place(anchor)

        assertEquals(IntOffset(marginPx, 1306), position)
        val placement = assertNotNull(provider.placement)
        assertFalse(placement.above)
        // Icon centre (48) relative to the clamped popup left (30); it stays inside the popup's width.
        assertEquals(18f, placement.arrowOffsetX, 0f)
    }

    /** A reposition (rotation, scroll, new anchor) must replace the previous side instead of keeping the first one. */
    @Test
    fun theReportedSideFollowsEachNewPlacement() {
        val provider = provider()

        provider.place(IntRect(400, 1200, 496, 1296))
        assertFalse(assertNotNull(provider.placement).above)

        provider.place(IntRect(400, 2100, 496, 2196))
        assertTrue(assertNotNull(provider.placement).above)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return requireNotNull(value)
    }
}
