package com.sheen.rokid.glasses.overlayspike

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * S1 throwaway probe overlay content.
 *
 * The gate asks whether a third-party `TYPE_APPLICATION_OVERLAY` window that is **focusable but not touchable**
 * receives the side keys while it floats over the launcher. So this view is deliberately the smallest thing that can
 * answer that:
 *
 * - `isFocusable = true` and `isFocusableInTouchMode = true`, and the service requests focus after the window is added;
 * - [dispatchKeyEvent] logs **every** key that reaches the view as `SPIKE_KEY code=<n> action=<n>` and consumes the
 *   side keys the product will bind (ENTER / BACK / DPAD_*) so a key that is only seen by the launcher leaves no line;
 * - [onWindowFocusChanged] is logged because "focusable window" and "window that actually has focus" are different
 *   states, and the overlay claim must name the one that was measured.
 *
 * The on-screen text is evidence, not decoration: it lets the screenshot prove this specific window was on the panel
 * and lets a later reader see which key last arrived.
 */
class SpikeOverlayView(context: Context) : FrameLayout(context) {

    private lateinit var keyStatus: TextView

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(0xE6101410.toInt())
                setStroke(dp(2), 0xFF00E676.toInt())
            }
        }

        panel.addView(
            TextView(context).apply {
                text = "EDITH S1 SPIKE"
                setTextColor(0xFF00E676.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            },
        )
        panel.addView(
            TextView(context).apply {
                text = "TYPE_APPLICATION_OVERLAY  480x380\n" +
                    "focusable=true  NOT_TOUCHABLE\n" +
                    "LAYOUT_NO_LIMITS|LAYOUT_IN_SCREEN|KEEP_SCREEN_ON"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
            },
        )
        keyStatus = TextView(context).apply {
            text = "last key: none"
            setTextColor(0xFFFFEB3B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        panel.addView(keyStatus)

        addView(
            panel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "SPIKE_KEY code=${event.keyCode} action=${event.action} repeat=${event.repeatCount} windowFocus=${hasWindowFocus()}")
        keyStatus.text = "last key: code=${event.keyCode} action=${event.action}"
        // The side keys under test are consumed here: a line proves the overlay got the key, and consuming it proves
        // the overlay is the key target rather than a passive echo of what the launcher also saw.
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> true
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.i(TAG, "SPIKE_FOCUS hasWindowFocus=$hasWindowFocus focused=${isFocused} windowVisible=${isShown}")
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "SpikeOverlay"
    }
}
