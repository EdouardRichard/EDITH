package com.sheen.rokid.phone.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 0.4 Task 6 (`D199`, spec §4.2/§4.5): **which** permission a session start needs, decided in one place.
 *
 * Before this object there was one gate - Bluetooth, for the CXR link - baked into `SessionService` and
 * `MainActivity` separately. Detached mode adds a second, disjoint requirement: the phone microphone. Keeping the
 * predicate here means the page's request, the service's admission and the composition's refusal code are all the
 * same decision:
 *
 * | mode | runtime permission requested | refusal code |
 * | --- | --- | --- |
 * | detached | `RECORD_AUDIO` | `MIC_PERMISSION_MISSING` |
 * | glasses (API 31+) | `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` | `BLUETOOTH_PERMISSION_DENIED` |
 * | glasses (API < 31) | none | none |
 *
 * `POST_NOTIFICATIONS` is requested with either mode where the platform has it, because the session runs in a
 * foreground service whose visible notification is the user's only control. It is deliberately **not** part of
 * [startRefusal]: the existing start has never been refused for it, and denying a notification is not a reason to
 * refuse a session.
 *
 * `RECORD_AUDIO` is never requested in glasses mode and Bluetooth is never required in detached mode (`D199`: 蓝牙权限缺失
 * 不影响启动). The API level is a parameter, not read from [Build] inside the decision, so the JVM tests pin the
 * platform boundaries (30/31/33) without a device.
 */
internal object SessionPermissions {

    /** The detached start's refusal code: the phone microphone was not granted. */
    const val MIC_PERMISSION_MISSING = "MIC_PERMISSION_MISSING"

    /** The glasses start's refusal code, unchanged from the pre-0.4 path. */
    const val BLUETOOTH_PERMISSION_DENIED = "BLUETOOTH_PERMISSION_DENIED"

    /**
     * The runtime permissions a start should ask for in [detached] mode on [apiLevel].
     *
     * Detached asks for the microphone; glasses mode keeps the exact pre-0.4 set (Bluetooth on API 31+, notifications
     * on API 33+). Nothing in the other mode's set appears, so the dialog the user sees matches the mode they chose.
     */
    fun requiredForStart(detached: Boolean, apiLevel: Int): List<String> = buildList {
        if (detached) {
            add(Manifest.permission.RECORD_AUDIO)
        } else if (apiLevel >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (apiLevel >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * The stable refusal code for a start in [detached] mode, or null when it may proceed.
     *
     * [granted] answers whether one permission is currently granted. The glasses branch reproduces the existing
     * `SessionService.bluetoothGranted` gate exactly (both Bluetooth permissions, API 31+ only), so nothing about the
     * glasses path changes; the detached branch asks only about `RECORD_AUDIO`.
     */
    fun startRefusal(detached: Boolean, apiLevel: Int, granted: (String) -> Boolean): String? =
        if (detached) {
            if (granted(Manifest.permission.RECORD_AUDIO)) null else MIC_PERMISSION_MISSING
        } else if (apiLevel < 31) {
            null
        } else if (!granted(Manifest.permission.BLUETOOTH_CONNECT) ||
            !granted(Manifest.permission.BLUETOOTH_SCAN)
        ) {
            BLUETOOTH_PERMISSION_DENIED
        } else {
            null
        }

    /** [startRefusal] against this device's real grants and API level; what the Activity and Service call. */
    fun grantedForStart(context: Context, detached: Boolean): Boolean =
        startRefusal(detached, Build.VERSION.SDK_INT) { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } == null
}
