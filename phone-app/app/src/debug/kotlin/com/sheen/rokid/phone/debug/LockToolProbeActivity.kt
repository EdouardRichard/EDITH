package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 2026-10-20 instrument: run the **production tool catalog** while the phone is locked, so the actual gate
 * (not just the unlock helper) is measured. `device.status` is read-only and needs the accessibility channel, which is
 * exactly the path that used to fail on a locked phone.
 *
 * ```
 * adb -s 2696813b shell am start -n com.sheen.rokid.phone/.debug.LockToolProbeActivity
 * adb -s 2696813b logcat -d -v time | Select-String 'P3_UNLOCK|P3_LOCKTOOL'
 * ```
 */
class LockToolProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            try {
                val root = (application as EdithApplication).composition
                val result = root.executeToolForProbe("device", """{"action":"status"}""")
                // Task 8 privacy: `detail` is tool data (status includes device/notification content), so the probe
                // logs only the outcome and the stable execution code - never the data body.
                Log.i(
                    TAG,
                    "P3_LOCKTOOL name=device outcome=${result?.outcome?.name ?: "NO_CATALOG"} " +
                        "code=${ProbeResultProjection.of(result).executionCode ?: "none"}",
                )
            } catch (failure: Throwable) {
                Log.i(TAG, "P3_LOCKTOOL name=device outcome=THREW throw=${failure.javaClass.simpleName}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "edith-lock-tool-probe").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "EdithLockToolProbe"
    }
}
