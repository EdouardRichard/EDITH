package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sheen.rokid.phone.session.confirmation.ConfirmationSurfaceDebug

/**
 * Debug-only control for the **glasses rung** of the confirmation ladder.
 *
 * `adb -s 2696813b shell am broadcast -a edith.DEBUG_CONFIRM_SURFACE \
 *   -n com.sheen.rokid.phone/.debug.DebugConfirmationSurface --ez suppressGlasses true`
 *
 * This does not fabricate a confirmation surface: it only asks production `deliverToPhone` to treat the glasses answer
 * as unavailable for this process. The real notifier probe, page probe, answer path and audit remain production code.
 * It exists because a live CXR session cannot survive removing the glasses app, which makes notification/no-visible
 * device rows unreachable otherwise. Release builds contain neither this receiver nor a caller of the flag.
 */
class DebugConfirmationSurface : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val suppress = intent.getBooleanExtra(EXTRA_SUPPRESS_GLASSES, false)
        val suppressPhone = intent.getBooleanExtra(EXTRA_SUPPRESS_PHONE, false)
        ConfirmationSurfaceDebug.suppressGlasses = suppress
        ConfirmationSurfaceDebug.suppressPhone = suppressPhone
        // Counts/booleans only; no confirmation text, no tool arguments.
        Log.i(TAG, "P4_SURFACE suppressGlasses=$suppress suppressPhone=$suppressPhone")
    }

    private companion object {
        const val TAG = "EdithP4"
        const val EXTRA_SUPPRESS_GLASSES = "suppressGlasses"
        const val EXTRA_SUPPRESS_PHONE = "suppressPhone"
    }
}
