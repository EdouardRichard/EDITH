package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 2026-10-20 instrument: write/clear the production lock-screen PIN without driving the Compose settings
 * page. The settings page is the user's path; this exists because the host cannot type a numeric PIN with the
 * accessibility probe (the probe has click/gesture, not text entry) and the acceptance needs the real Keystore store.
 *
 * ```
 * adb -s 2696813b shell am start -n com.sheen.rokid.phone/.debug.LockPinProbeActivity --es pin 123456
 * adb -s 2696813b shell am start -n com.sheen.rokid.phone/.debug.LockPinProbeActivity --ez clear true
 * ```
 */
class LockPinProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = (application as EdithApplication).composition
        val result = if (intent.getBooleanExtra("clear", false)) {
            root.clearLockPin()
            "cleared"
        } else {
            val pin = intent.getStringExtra("pin").orEmpty()
            if (root.saveLockPin(pin)) "saved" else "rejected"
        }
        Log.i(TAG, "P3_LOCKPIN_PROBE result=$result configured=${root.lockPinConfigured}")
        finish()
    }

    private companion object {
        const val TAG = "EdithLockPinProbe"
    }
}
