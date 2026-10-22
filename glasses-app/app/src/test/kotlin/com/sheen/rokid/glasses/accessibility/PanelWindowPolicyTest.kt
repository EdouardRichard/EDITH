package com.sheen.rokid.glasses.accessibility

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-20: the vendor home HUD (time/temperature/signal/battery) is a system window at layer 271000. A
 * `TYPE_APPLICATION_OVERLAY` panel (121000) can never cover it; an accessibility overlay (311000) can. These tests pin
 * the decision so a later edit cannot silently fall back to the window type that leaves the HUD visible.
 */
class PanelWindowPolicyTest {

    @Test
    fun accessibilityServiceConnectedUsesTheHigherOverlayAndBackdrop() {
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, PanelWindowPolicy.type(true))
        assertTrue(PanelWindowPolicy.needsBackdrop(true))
    }

    @Test
    fun withoutAccessibilityThePanelFallsBackToTheApplicationOverlay() {
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, PanelWindowPolicy.type(false))
        assertFalse(PanelWindowPolicy.needsBackdrop(false))
    }

    @Test
    fun backdropCoversTheFullPanelCanvas() {
        assertEquals(480, PanelWindowPolicy.CANVAS_WIDTH_PX)
        assertEquals(640, PanelWindowPolicy.CANVAS_HEIGHT_PX)
    }
}
