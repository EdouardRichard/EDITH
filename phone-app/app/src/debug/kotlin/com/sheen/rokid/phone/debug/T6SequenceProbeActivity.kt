package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 2026-10-20 instrument for the replacement T6 corpus: observe the browser page through the **production**
 * device tool and print its observed screen text (the page is a synthetic test page, not user data). The probe does not
 * click; it exists to confirm whether the semantic buttons are exposed with clickable refs.
 */
class T6SequenceProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            try {
                val root = (application as EdithApplication).composition
                val result = root.executeToolForProbe("device", """{"action":"observe","filter":"all"}""")
                Log.i(TAG, "P3_T6SEQ observe=${result?.outcome?.name ?: "NO_CATALOG"} detail=${result?.detail}")
            } catch (failure: Throwable) {
                Log.i(TAG, "P3_T6SEQ outcome=THREW throw=${failure.javaClass.simpleName}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "edith-t6-seq-probe").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "EdithT6SeqProbe"
    }
}
