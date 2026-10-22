package edith.phone.wakelockprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * One high-importance notification carrying a full-screen intent to [ProbeActivity].
 *
 * This is the D6 approximation of the production "confirmation on the lock screen" path: the system, not
 * the app, sends the PendingIntent, so it is the part of the chain that also has to survive MIUI's own
 * lock-screen policy. Both the foreground service and the receiver fallback post the same object so a
 * PASS/FAIL can be attributed to the notification mechanism rather than to two different notification
 * shapes.
 */
object FsiNotifier {

    private const val TAG = "KEYGUARD_PROBE"
    const val CHANNEL_ID = "edith.keyguard.probe"
    const val NOTIFICATION_ID = 6401
    private const val PENDING_INTENT_REQUEST = 6402

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EDITH keyguard probe",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "D6 throwaway keyguard probe"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, mode: String, trigger: String): Notification {
        ensureChannel(context)
        val intent = Intent(context, ProbeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ProbeActivity.EXTRA_MODE, mode)
            putExtra(ProbeActivity.EXTRA_TRIGGER, trigger)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("EDITH keyguard probe")
            .setContentText("mode=$mode trigger=$trigger")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
    }

    fun post(context: Context, mode: String, trigger: String, source: String): Boolean {
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager == null) {
                Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=fsiPostFailed source=$source error=no_notification_manager")
                return false
            }
            manager.notify(NOTIFICATION_ID, buildNotification(context, mode, trigger))
            Log.i(TAG, "KEYGUARD_PROBE_TRIGGER event=fsiPosted source=$source mode=$mode id=$NOTIFICATION_ID")
            true
        } catch (t: Throwable) {
            Log.i(
                TAG,
                "KEYGUARD_PROBE_TRIGGER event=fsiPostFailed source=$source " +
                    "error=${t.javaClass.simpleName}:${t.message}",
            )
            false
        }
    }
}
