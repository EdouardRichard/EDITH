package edith.phone.wakelockprobe

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service that posts [FsiNotifier]'s full-screen notification. This matches how a production
 * app would already be running in the background when the trigger fires; the D6 round also records what
 * happens when Android 13 refuses to *start* the foreground service from a broadcast (the receiver then
 * falls back to posting the same notification directly, and the fallback is labeled in logcat).
 */
class WakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FsiNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(ProbeActivity.EXTRA_MODE)?.trim().orEmpty()
            .ifEmpty { "activity+dismiss" }
        Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=wakeServiceStart mode=$mode")
        try {
            startForeground(FsiNotifier.NOTIFICATION_ID, FsiNotifier.buildNotification(this, mode, "fsi"))
            Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=fsiPosted source=foreground_service mode=$mode")
        } catch (t: Throwable) {
            Log.i(
                TAG,
                "KEYGUARD_PROBE_TRIGGER event=fsiForegroundFailed " +
                    "error=${t.javaClass.simpleName}:${t.message}",
            )
            FsiNotifier.post(this, mode, "fsi", "service_fallback")
        }
        // The probe only needs the notification to be posted; keep the service briefly, then let it go.
        handler.postDelayed({ stopSelf() }, 15_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "KEYGUARD_PROBE"
    }
}
