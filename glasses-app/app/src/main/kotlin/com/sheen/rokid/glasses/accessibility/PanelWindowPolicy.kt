package com.sheen.rokid.glasses.accessibility

import android.view.WindowManager

/**
 * 2026-10-20: which window type the panel uses, and whether it needs an opaque full-screen backdrop.
 *
 * Device measurement: the vendor home HUD (time/temperature/signal/battery) is a system window at base layer 271000;
 * `TYPE_APPLICATION_OVERLAY` sits at 121000 and can never cover it. An accessibility overlay sits at 311000 and can.
 * The transparent CXR scene Activity must stay transparent (D170), so the backdrop is a separate window that exists
 * only while the panel is visible; nothing here changes the Activity theme.
 */
internal object PanelWindowPolicy {

    /** The vendor panel canvas. */
    const val CANVAS_WIDTH_PX = 480
    const val CANVAS_HEIGHT_PX = 640

    /** The panel type; `connected` is whether [GlassesAccessibilityService] is bound. */
    fun type(accessibilityConnected: Boolean): Int =
        if (accessibilityConnected) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** Only the accessibility overlay needs the opaque backdrop that hides the vendor HUD. */
    fun needsBackdrop(accessibilityConnected: Boolean): Boolean = accessibilityConnected
}
