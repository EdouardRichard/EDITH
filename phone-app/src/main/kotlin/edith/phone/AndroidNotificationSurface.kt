package edith.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon

/**
 * The Android notification shade (spec §17.4).
 *
 * Three deliberate choices, each because a confirmation is a *time-critical question about something about to happen*:
 * - `IMPORTANCE_HIGH` on a dedicated channel, because a silent notification inside a 10 s window is a notification
 *   nobody sees;
 * - `VISIBILITY_PUBLIC`, so someone holding the locked phone can read **what** they are approving - a blind tap on a
 *   hidden notification would be worse than showing the summary;
 * - `setOngoing(true)`, so a stray swipe cannot hide a question that is still live. [ConfirmationNotifier.TTL_MILLIS]
 *   is the fallback that keeps a killed process from leaving a dead question in the shade.
 */
internal class AndroidNotificationSurface(private val context: Context) : NotificationSurface {

    private val manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)

    override fun available(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager?.areNotificationsEnabled() == true

    override fun post(
        requestId: String,
        title: String,
        text: String,
        ttlMillis: Long,
        actions: ConfirmationActions,
    ) {
        val target = manager ?: return
        target.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESCRIPTION
            },
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_edith_confirm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(ttlMillis)
            .apply { actions.all.forEach { addAction(action(it, requestId)) } }
            .build()
        target.notify(NOTIFICATION_ID, notification)
    }

    /** One question at a time by construction (`SmartConfirmation` refuses a second), so one id is the whole state. */
    override fun cancel(requestId: String) { manager?.cancel(NOTIFICATION_ID) }

    override fun cancelAll() { manager?.cancel(NOTIFICATION_ID) }

    private fun action(action: ConfirmationAction, requestId: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_edith_confirm),
            action.label,
            pendingIntent(action.id, requestId),
        ).build()

    private fun pendingIntent(actionId: String, requestId: String): PendingIntent {
        // Explicit and immutable: the intent names our own non-exported receiver, and the action string is what tells
        // the three buttons apart.
        val intent = Intent(context, ConfirmationActionReceiver::class.java)
            .setAction(actionId)
            .putExtra(ConfirmationActionReceiver.EXTRA_REQUEST_ID, requestId)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "edith-confirmations"
        const val NOTIFICATION_ID = 0x0ED17

        private const val CHANNEL_NAME = "动作确认"
        private const val CHANNEL_DESCRIPTION = "有动作需要你在手机上确认时提醒你"
    }
}
