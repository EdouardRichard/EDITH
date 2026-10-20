package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debug-only 2026-10-20 instrument for the replacement T6: drive the **production** `device` tool on the semantic page
 * by reading the refs from the production `observe` output. It proves whether `device.act` can click the page's buttons;
 * it is not the corpus model and its result is not a model PASS.
 */
class T6ClickProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EdithApplication
        finish()
        Thread({
            // Finish this probe, then bring the page's browser to the front; otherwise observe reads systemui/home or the
            // app's own page and the WebView buttons are not the active window.
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://127.0.0.1:${T6PageServer.PORT}/"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Thread.sleep(1500)
            val root = app.composition
            val observe = root.executeToolForProbe("device", """{"action":"observe","filter":"interactive"}""")
            val refs = refsFor(observe?.detail)
            val text = screenText(observe?.detail)
            Log.i(TAG, "P3_T6CLICK_TEXT len=${text?.length ?: 0} hasRed=${text?.contains("红")} hasButton=${text?.contains("button")}")
            text?.take(1800)?.chunked(400)?.forEachIndexed { i, part -> Log.i(TAG, "P3_T6CLICK_CHUNK$i $part") }
            val outcome = mutableListOf<String>()
            for (label in listOf("红", "绿", "蓝", "提交")) {
                val ref = refs[label]
                if (ref == null) {
                    outcome += "$label=noRef"
                    break
                }
                val act = root.executeToolForProbe(
                    "device",
                    """{"action":"act","kind":"tap","ref":"$ref"}""",
                )
                outcome += "$label=$ref:${act?.outcome?.name}"
                Thread.sleep(250)
            }
            Log.i(TAG, "P3_T6CLICK ${outcome.joinToString(" ")}")
            runOnUiThread { finish() }
        }, "edith-t6-click-probe").apply { isDaemon = true }.start()
    }

    private fun screenText(detail: String?): String? {
        val body = detail?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val screen = body["data"]?.jsonObject?.get("screen")?.jsonObject ?: return null
        return screen["text"]?.jsonPrimitive?.contentOrNull
    }

    private fun refsFor(detail: String?): Map<String, String> {
        val text = screenText(detail) ?: return emptyMap()
        return listOf("红", "绿", "蓝", "提交").mapNotNull { label ->
            val line = text.lineSequence().firstOrNull {
                it.contains(" button \"$label\"") && it.contains("clickable")
            } ?: return@mapNotNull null
            val ref = line.substringAfter("#").substringBefore(" ")
            label to "#$ref"
        }.toMap()
    }

    private companion object {
        const val TAG = "EdithT6ClickProbe"
    }
}
