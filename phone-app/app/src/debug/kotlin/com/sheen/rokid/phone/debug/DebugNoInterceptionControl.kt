package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.platform.P3Log

/**
 * Debug-only B8 instrument for the 「不拦截」 mode.
 *
 * B8 needs the mode's real persisted setting and live gate, but the settings switch is a Compose control whose
 * clicks cannot be driven by `edith.phone.a11yprobe` reliably (the probe's own window becomes the active window).
 * This receiver therefore calls the **production** `storedSettings()`/`saveSmartSettings()` path: the same blob, the
 * same store, the same `NoInterceptionGate` update and the same validation as the settings page. It does not write the
 * preference directly and it does not bypass the gate.
 *
 * The UI's second confirmation is a separate property, already pinned by `NoInterceptionUiTest`; this instrument
 * intentionally does not represent it. Debug source set only - a release APK contains no receiver.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_NO_INTERCEPTION -n com.sheen.rokid.phone/.debug.DebugNoInterceptionControl \
 *     --ez enabled true
 * ```
 *
 * It logs only the requested/committed booleans and the store's presence; never any user text.
 */
class DebugNoInterceptionControl : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val composition = (context.applicationContext as? EdithApplication)?.composition
        if (composition == null) return report("refused reason=NO_COMPOSITION")
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val committed = runCatching {
            val current = composition.storedSettings()
            composition.saveSmartSettings(current.copy(noInterception = enabled))
            composition.storedSettings().noInterception
        }.getOrElse { failure ->
            return report("refused reason=SETTINGS_FAILED type=${failure.javaClass.simpleName}")
        }
        report("state=committed requested=$enabled persisted=$committed")
    }

    private fun report(fields: String) = P3Log.line("P4_NO_INTERCEPTION $fields")

    companion object {
        const val ACTION = "edith.DEBUG_NO_INTERCEPTION"
        const val EXTRA_ENABLED = "enabled"
    }
}
