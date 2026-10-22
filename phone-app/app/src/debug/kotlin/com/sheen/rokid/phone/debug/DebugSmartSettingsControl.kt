package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.platform.P3Log

/**
 * Debug-only smart-settings control for the Phase 4 closure corpus.
 *
 * The whitelist and the risk ceiling are read once at process start. Driving the Compose fields through shell input is
 * brittle (the probe input path does not support arbitrary text, and `adb input text` mangles `|`), so this receiver
 * calls the same production `storedSettings()`/`saveSmartSettings()` path with a validated draft. It changes only the
 * two fields named by the caller, and it logs counts/levels only - never the whitelist entries themselves.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_SMART_SETTINGS -n com.sheen.rokid.phone/.debug.DebugSmartSettingsControl \
 *     --es maxLevel L2 --es whitelist 'set_brightness|device'
 * ```
 *
 * Debug source set only; release builds contain no receiver and no caller.
 */
class DebugSmartSettingsControl : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val composition = (context.applicationContext as? EdithApplication)?.composition
        if (composition == null) return report("refused reason=NO_COMPOSITION")
        val requestedLevel = intent.getStringExtra(EXTRA_MAX_LEVEL)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val requestedWhitelist = intent.getStringExtra(EXTRA_WHITELIST)
            ?.let { raw -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        val committed = runCatching {
            val current = composition.storedSettings()
            val next = current.copy(
                maxLevel = requestedLevel ?: current.maxLevel,
                whitelist = requestedWhitelist ?: current.whitelist,
            )
            composition.saveSmartSettings(next)
            composition.storedSettings()
        }.getOrElse { failure ->
            return report("refused reason=SETTINGS_FAILED type=${failure.javaClass.simpleName}")
        }
        report("state=committed maxLevel=${committed.maxLevel} whitelistCount=${committed.whitelist.size}")
    }

    private fun report(fields: String) = P3Log.line("P4_SETTINGS $fields")

    companion object {
        const val ACTION = "edith.DEBUG_SMART_SETTINGS"
        const val EXTRA_MAX_LEVEL = "maxLevel"
        const val EXTRA_WHITELIST = "whitelist"
    }
}
