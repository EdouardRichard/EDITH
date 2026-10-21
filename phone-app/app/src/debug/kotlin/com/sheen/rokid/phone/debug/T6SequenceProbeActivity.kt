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
                // 0.3 Phase 2.2 Task 8: count-only evidence. The tree is `observation.screen`; the body is never logged.
                val body = result?.detail?.let {
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull()
                }
                val observation = (body as? kotlinx.serialization.json.JsonObject)
                    ?.get("observation") as? kotlinx.serialization.json.JsonObject
                val screen = (observation?.get("screen") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
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
