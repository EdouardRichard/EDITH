package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 2026-10-20 instrument for the replacement T6 corpus: observe the browser page through the **production**
 * device tool and print its observed screen text (the page is a synthetic test page, not user data). The probe does not
 * click; it exists to confirm whether the semantic buttons are exposed with clickable refs.
 *
 * The probe reads [ProbeResultProjection]: the compact tree is `observation.screen` on the structured result; `detail`
 * is tool-specific data now, not a rendered envelope.
 */
class T6SequenceProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            try {
                val root = (application as EdithApplication).composition
                val result = root.executeToolForProbe("device", """{"action":"observe","filter":"all"}""")
                // 0.3 Phase 2.2 Task 8: count-only evidence. The tree is `observation.screen`; the body is never logged.
                val screen = ProbeResultProjection.of(result).screen
                Log.i(
                    TAG,
                    "P3_T6SEQ observe=${result?.outcome?.name ?: "NO_CATALOG"} lines=" +
                        "${screen?.lineSequence()?.count { it.isNotBlank() } ?: 0} chars=${screen?.length ?: 0}",
                )
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
