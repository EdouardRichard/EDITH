package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.storage.AndroidProfileRepository

/**
 * Debug-only 「脱离眼镜」 gate setter and state readback (0.4 Task 9).
 *
 * The switch itself lives at the bottom of a long Compose settings page. The installed accessibility probe can click a
 * visible label, but it has no "scroll the current app" primitive, so at the native 1080×2400 viewport the switch is
 * below the fold (`A1CLICK_VERDICT outcome=no_match ... polls=47`). In the Task 9 run the switch **was** reached by
 * temporarily enlarging the logical display and was toggled by the probe itself (real `Switch` -> production
 * `DetachGate.set`), so D3 was not blocked; this receiver is kept as a clearly labelled fallback for downstream work,
 * and that run used it for **readback only**.
 *
 * It calls the production [DetachGate.set] and the production settings store, so its own persist/stop-before-save
 * behaviour is the shipped one; it just skips the switch's tap target.
 *
 * It is **not** a UI verification and must never be quoted as one. Every line it emits is prefixed `P4_DETACH_DEBUG`
 * or `P4_*_READBACK` so a grep of the device log can tell the instrument from a user action.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_DETACH -n com.sheen.rokid.phone/.debug.DebugDetachControl --ez on true
 * adb shell am broadcast -a edith.DEBUG_DETACH -n com.sheen.rokid.phone/.debug.DebugDetachControl --ez readback true
 * ```
 *
 * Readback logs the **stored** values through the same `storedSettings()` seam the settings page reads, plus the live
 * effective proactive value and the session state - booleans, a state name and a profile id, never a secret.
 */
class DebugDetachControl : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return
        val root = (context.applicationContext as? EdithApplication)?.composition
            ?: return line("P4_DETACH_DEBUG state=refused reason=NO_COMPOSITION")

        if (intent.getBooleanExtra(EXTRA_READBACK, false)) return readback(root)
        if (!intent.hasExtra(EXTRA_ON)) return line("P4_DETACH_DEBUG state=refused reason=MISSING_ON")

        val requested = intent.getBooleanExtra(EXTRA_ON, false)
        val result = root.detachGate.set(requested)
        // The production gate has already emitted `P4_DETACH state=... effective_proactive=... audio=...`; this line
        // only records that the fallback instrument (not the switch) was the caller, and what it returned.
        line(
            "P4_DETACH_DEBUG requested=$requested saved=${result.saved} stopped=${result.stoppedSession} " +
                "error=${result.error ?: "none"} live=${root.detachGate.enabled.value}",
        )
    }

    private fun readback(root: HostCompositionRoot) {
        val settings = runCatching { root.storedSettings() }.getOrNull()
        val selected = (root.profiles as? AndroidProfileRepository)?.selections()?.get("smart-assistant")
        line(
            "P4_SETTINGS_READBACK glasses_detached=${settings?.glassesDetached} " +
                "proactive_stored=${settings?.proactiveEnabled} " +
                "proactive_effective=${root.proactiveArming.effectiveProactiveArmed} " +
                "speech=${settings?.speechEnabled} profile=${selected ?: "none"}",
        )
        val state = root.state.value
        line("P4_SESSION_READBACK session=${state.session} error=${state.errorCode ?: "none"}")
    }

    private fun line(text: String) = P3Log.line(text)

    companion object {
        const val ACTION_CONTROL = "edith.DEBUG_DETACH"
        const val EXTRA_ON = "on"
        const val EXTRA_READBACK = "readback"
    }
}
