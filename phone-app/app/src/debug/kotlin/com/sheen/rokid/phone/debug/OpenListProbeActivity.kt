package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 2026-10-20 instrument for P5/M1: call the production `open(list)` and let the production
 * `P3_TOOLARG`/`P3_TOOLCATALOG` lines be read from logcat. The Activity logs only the outcome; the catalogue body is
 * never copied here.
 *
 * ```
 * adb -s 2696813b shell am start -n com.sheen.rokid.phone/.debug.OpenListProbeActivity
 * ```
 */
class OpenListProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            try {
                val root = (application as EdithApplication).composition
                val result = root.executeToolForProbe("open", """{"list":true}""")
                Log.i(TAG, "P3_OPENLIST outcome=${result?.outcome?.name ?: "NO_CATALOG"}")
            } catch (failure: Throwable) {
                Log.i(TAG, "P3_OPENLIST outcome=THREW throw=${failure.javaClass.simpleName}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "edith-open-list-probe").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "EdithOpenListProbe"
    }
}
