package com.sheen.rokid.glasses.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * The glasses-side accessibility service that lets EDITH draw above the vendor home HUD.
 *
 * Device measurement (2026-10-20): `TYPE_APPLICATION_OVERLAY` is at layer 121000 and the vendor HUD is above it at
 * 271000; `TYPE_ACCESSIBILITY_OVERLAY` is at 311000 and covers it. The service itself draws nothing and reads no
 * window content; it only makes its `WindowManager` available to [com.sheen.rokid.glasses.app.GlassesOverlayService],
 * which owns the panel and its controller. That keeps all panel state in the existing service.
 *
 * The glasses has no system settings page for this permission; enabling and verifying it is an ADB operation (see
 * `README.md`). If it is not enabled, the panel falls back to the existing `TYPE_APPLICATION_OVERLAY` and the vendor
 * HUD remains visible.
 */
class GlassesAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "A11Y connected package=$packageName")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            Log.i(TAG, "A11Y disconnected")
            onLost?.invoke()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EdithA11y"

        @Volatile
        private var instance: GlassesAccessibilityService? = null

        /** Called when the service goes away; the panel host uses it to remove an accessibility-overlay window. */
        @Volatile
        internal var onLost: (() -> Unit)? = null

        /** True once the system has bound this service; also read by the device test to skip when it is not enabled. */
        fun isConnected(): Boolean = instance != null

        /**
         * The accessibility service's own `WindowManager`, or null when the service is not enabled.
         *
         * A `TYPE_ACCESSIBILITY_OVERLAY` window must be added through the accessibility service's context; the
         * application overlay service cannot create one with its own token.
         */
        internal fun windowManagerOrNull(): WindowManager? =
            instance?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }
}
