package com.sheen.rokid.glasses.display

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.23 P3 (spec §5.1.1): the 30 px black alpha masks, decided without Android.
 *
 * The official assets (`icon_ai_top_mask` / `icon_ai_bottom_mask`) are 481x30 black gradients; the direction is the
 * whole of their meaning - the top fades content in from black, the bottom fades it out - so the two colour arrays are
 * pinned here and `GradientMaskView` only hands them to a `LinearGradient`.
 */
class MaskGradientTest {

    @Test
    fun theTopMaskIsOpaqueBlackAtTheTopAndClearAtTheBottom() {
        val colors = MaskGradient.colors(MaskEdge.TOP, MaskGradient.BLACK)
        assertEquals("the top edge must hide content scrolling under it",
            MaskGradient.BLACK, colors.first())
        assertEquals("the bottom edge must let content through", 0, colors.last())
    }

    @Test
    fun theBottomMaskIsTheMirrorImage() {
        val colors = MaskGradient.colors(MaskEdge.BOTTOM, MaskGradient.BLACK)
        assertEquals("the top edge of the bottom mask must let content through", 0, colors.first())
        assertEquals("the bottom edge must hide content scrolling into it",
            MaskGradient.BLACK, colors.last())
    }

    @Test
    fun theGradientRunsFromTheTopToTheBottom() {
        assertArrayEquals(floatArrayOf(0f, 1f), MaskGradient.positions(), 0.001f)
    }

    @Test
    fun theMaskHeightMatchesTheOfficialPixels() {
        assertEquals(30, MaskGradient.HEIGHT_PX)
        assertEquals(GlassesPanelSpec.MASK_HEIGHT_PX, MaskGradient.HEIGHT_PX)
    }
}
