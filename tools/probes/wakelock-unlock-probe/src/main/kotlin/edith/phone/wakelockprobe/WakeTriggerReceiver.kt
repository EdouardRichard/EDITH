package edith.phone.wakelockprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * D6 background trigger. `confirm=true` is required so this exported receiver cannot start an activity
 * because some stray intent arrived.
 *
 * Triggers:
 *  - `broadcast` (default): call `startActivity` straight from the receiver. Without SAW this is expected
 *    to hit the Android 10+ background-activity-start restriction; the point is to observe that, not to
 *    work around it.
 *  - `fsi`: start [WakeService] (foreground service + full-screen-intent notification). If Android 13
 *    refuses the background foreground-service start, fall back to posting the same FSI notification
 *    directly and say so in logcat.
 *  - `fsi-direct`: post the FSI notification directly from the receiver (no service), which separates
 *    "FSI works" from "the service could be started".
 */
class WakeTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_CONFIRM, false) != true) {
            Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=ignored reason=confirm_not_true")
            return
        }
        val mode = intent.getStringExtra(ProbeActivity.EXTRA_MODE)?.trim().orEmpty()
            .ifEmpty { "activity+dismiss" }
        val trigger = intent.getStringExtra(ProbeActivity.EXTRA_TRIGGER)?.trim().orEmpty()
            .ifEmpty { "broadcast" }
        Log.i(
            TAG,
            "KEYGUARD_PROBE_TRIGGER event=receiver trigger=$trigger mode=$mode " +
                "pkg=${context.packageName}",
        )

        when (trigger) {
            "fsi", "fsi-direct" -> startFsi(context, mode, trigger)
            else -> startProbeActivity(context, mode, trigger)
        }
    }

    private fun startProbeActivity(context: Context, mode: String, trigger: String) {
        val intent = Intent(context, ProbeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ProbeActivity.EXTRA_MODE, mode)
            putExtra(ProbeActivity.EXTRA_TRIGGER, trigger)
        }
        try {
            context.startActivity(intent)
            Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=startActivityIssued trigger=$trigger mode=$mode")
        } catch (t: Throwable) {
            Log.i(
                TAG,
                "KEYGUARD_PROBE_TRIGGER event=startActivityFailed trigger=$trigger " +
                    "error=${t.javaClass.simpleName}:${t.message}",
            )
        }
    }

    private fun startFsi(context: Context, mode: String, trigger: String) {
        if (trigger == "fsi-direct") {
            FsiNotifier.post(context, mode, "fsi", "receiver_direct")
            return
        }
        try {
            val serviceIntent = Intent(context, WakeService::class.java).apply {
                putExtra(ProbeActivity.EXTRA_MODE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=fsiServiceStartIssued mode=$mode")
        } catch (t: Throwable) {
            Log.i(
                TAG,
                "KEYGUARD_PROBE_TRIGGER event=fsiServiceStartBlocked " +
                    "error=${t.javaClass.simpleName}:${t.message}",
            )
            // Same notification, same PendingIntent; only the posting component differs.
            FsiNotifier.post(context, mode, "fsi", "receiver_fallback")
        }
    }

    private companion object {
        const val TAG = "KEYGUARD_PROBE"
        const val EXTRA_CONFIRM = "confirm"
    }
}
