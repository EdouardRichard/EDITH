package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.actions.intent.PackageManagerAppCatalog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug-only 2026-10-20 instrument for P4: call the production `open("文件")` and report only the outcome, error code and
 * candidate count. No candidate label/package is copied into the probe log; the production `P3_TOOLARG` line carries the
 * argument shape.
 *
 * The probe reads [ProbeResultProjection]: `execution.code`/`execution.applied` are the structured facts and
 * `detail.candidates` is the tool-specific data.
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
                // Count-only evidence: the candidate labels/packages themselves never reach logcat.
                Log.i(TAG, "P3_APPCATALOG total=${apps.size} fileish=${matches.size}")
                val args = buildJsonObject { put("target", target) }.toString()
                val result = root.executeToolForProbe("open", args)
                // Task 8: the result is structured. The stable code/applied live in `execution`; the tool-specific
                // candidate list is `data.candidates` (i.e. the parsed `detail`), not an envelope member.
                val facts = ProbeResultProjection.of(result)
                Log.i(
                    TAG,
                    "P3_OPENTARGET outcome=${result?.outcome?.name ?: "NO_CATALOG"} code=${facts.executionCode ?: "none"} " +
                        "candidates=${facts.candidateCount} applied=${facts.applied}",
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
