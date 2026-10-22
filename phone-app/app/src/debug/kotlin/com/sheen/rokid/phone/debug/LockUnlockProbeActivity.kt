package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 2026-10-20 instrument: run the production lock-screen coordinator from a host-started Activity, so the
 * swipe/PIN path can be measured without arming a model turn (and without the MIUI broadcast queue).
 *
 * ```
 * adb -s 2696813b shell am start -n com.sheen.rokid.phone/.debug.LockUnlockProbeActivity
 * adb -s 2696813b logcat -d -v time | Select-String P3_UNLOCKPROBE
 * ```
 */
class LockUnlockProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            try {
                val root = (application as EdithApplication).composition
                val outcome = root.ensureUnlockedForProbe()
                Log.i(TAG, "P3_UNLOCKPROBE outcome=${outcome.name} pinConfigured=${root.lockPinConfigured}")
            } catch (failure: Throwable) {
                Log.i(TAG, "P3_UNLOCKPROBE outcome=THREW throw=${failure.javaClass.simpleName}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "edith-lock-unlock-probe").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "EdithUnlockProbe"
    }
}
