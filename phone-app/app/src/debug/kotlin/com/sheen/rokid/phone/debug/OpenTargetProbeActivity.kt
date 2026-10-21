package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.actions.intent.PackageManagerAppCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debug-only 2026-10-20 instrument for P4: call the production `open("文件")` and report only the outcome, error code and
 * candidate count. The candidate labels/packages are not copied into the probe log; the production `P3_TOOLARG` line
 * carries the shape.
 */
class OpenTargetProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            try {
                val target = intent.getStringExtra("target") ?: "文件"
                val root = (application as EdithApplication).composition
                val catalog = PackageManagerAppCatalog(applicationContext)
                val apps = catalog.visibleApps()
                val pm = applicationContext.packageManager
                val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                Log.i(
                    TAG,
                    "P3_PACKAGEVIS installed=${pm.getInstalledApplications(0).size} " +
                        "installedAll=${pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_ALL).size} " +
                        "launcher=${pm.queryIntentActivities(launcherIntent, 0).size} " +
                        "settingsLaunchable=${pm.getLaunchIntentForPackage("com.android.settings") != null} " +
                        "fileLaunchable=${pm.getLaunchIntentForPackage("com.android.fileexplorer") != null}",
                )
                val matches = apps.filter {
                    it.label.contains(target, ignoreCase = true) ||
                        it.packageName.contains("file", ignoreCase = true) ||
                        it.packageName.contains("document", ignoreCase = true)
                }
                Log.i(
                    TAG,
                    "P3_APPCATALOG total=${apps.size} fileish=${matches.size} " +
                        "packages=${matches.joinToString(",") { "${it.packageName}=${it.label}" }}",
                )
                val args = buildJsonObject { put("target", target) }.toString()
                val result = root.executeToolForProbe("open", args)
                // 0.3 Phase 2.2 Task 8: the unified envelope. The stable code and applied fact live in `execution`; the
                // tool-specific candidate list is still `data.candidates`.
                val body = result?.detail?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                val execution = body?.get("execution") as? kotlinx.serialization.json.JsonObject
                val code = (execution?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val applied = (execution?.get("applied") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val data = body?.get("data") as? kotlinx.serialization.json.JsonObject
                val candidates = (data?.get("candidates") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                Log.i(
                    TAG,
                    "P3_OPENTARGET outcome=${result?.outcome?.name ?: "NO_CATALOG"} code=${code ?: "none"} " +
                        "candidates=$candidates applied=$applied",
                )
            } catch (failure: Throwable) {
                Log.i(TAG, "P3_OPENTARGET outcome=THREW throw=${failure.javaClass.simpleName}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "edith-open-target-probe").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "EdithOpenTargetProbe"
    }
}
