package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.app.EdithApplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryMeasurementForm

/**
 * Debug-only memory-form switch for Phase 7 F4 (design §3.1/§3.2).
 *
 * The F4 experiment alternates group A (legacy raw memory text) and group B (the production block) between turns. That
 * must be a **runtime** switch so both groups share one APK and one memory document; this receiver is the A/B driver's
 * only way in. It sets the form on the composition's own plugin instance, and the plugin reads it once when the next
 * session activates - so the caller sets the form **before** starting a session (after a cold start, or after
 * 「停止」/「开始」 when the plugin was already activated).
 *
 * ```
 * adb -s <phone> shell am broadcast -a edith.DEBUG_MEMORY_FORM \
 *     -n com.sheen.rokid.phone/.DebugMemoryForm --es form A
 * ```
 *
 * `A` = [MemoryMeasurementForm.LEGACY_RAW], `B` = [MemoryMeasurementForm.PRODUCTION]. Anything else is refused and
 * logged as `form=REFUSED`, rather than silently defaulting a typo into the wrong experimental slot.
 *
 * It exists **only in the debug source set** (see `src/debug/AndroidManifest.xml`) and is `exported` so `am broadcast`
 * can reach it; a release build contains no receiver. The `setMemoryMeasurementForm` setter it calls remains in
 * `src/main` as a measurement seam, but it has no release caller, so release has no path that selects the legacy
 * renderer. The log line carries the form name and whether a plugin took it - never memory text or any other content.
 */
class DebugMemoryForm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FORM) return
        val form = parse(intent.getStringExtra(EXTRA_FORM))
        val plugin = (context.applicationContext as? EdithApplication)?.composition?.smartPluginForProbe
        val applied = if (form != null && plugin != null) {
            plugin.setMemoryMeasurementForm(form)
            true
        } else {
            false
        }
        // The form name and the outcome only. A refused value is named as such so a typo is visible in the transcript
        // instead of looking like a successful slot that ran the wrong form.
        P3Log.line("P3_F4FORM form=${form?.name ?: "REFUSED"} applied=$applied")
    }

    companion object {
        /** The explicit action, so the receiver is reachable without a matching implicit broadcast. */
        const val ACTION_FORM = "edith.DEBUG_MEMORY_FORM"

        /** The short form selector: `A` legacy raw, `B` production. */
        const val EXTRA_FORM = "form"

        /**
         * Maps the broadcast's `form` extra to a measurement form, or `null` when the value is absent or unknown.
         *
         * Pure so the mapping is pinned on the JVM without a device or a `BroadcastReceiver`. Only the two documented
         * letters are accepted, in either case; the F4 protocol's A/B labels are part of the pre-registered design, so
         * guessing at anything else could silently move a turn into the wrong group.
         */
        fun parse(raw: String?): MemoryMeasurementForm? = when (raw) {
            "A", "a" -> MemoryMeasurementForm.LEGACY_RAW
            "B", "b" -> MemoryMeasurementForm.PRODUCTION
            else -> null
        }
    }
}
