package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.app.MainActivity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.provider.Settings

/**
 * The Android notification shade (spec §17.4).
 *
 * The shape follows from what the question *is* - a time-boxed approval of something about to happen - and from what
 * the device measured (owner directives 2026-10-10):
 * - a dedicated channel at `IMPORTANCE_HIGH` with an **explicit sound and vibration**; the first version relied on the
 *   platform defaults, and on this phone that meant no vibration at all and a question nobody noticed;
 * - `VISIBILITY_PUBLIC`, so someone holding the locked phone can read **what** they are approving - a blind tap on a
 *   hidden notification would be worse than showing the summary;
 * - `setOngoing(true)`, so a stray swipe cannot hide a question that is still live, with
 *   [ConfirmationNotifier.TTL_MILLIS] as the fallback that keeps a killed process from leaving a dead question behind;
 * - a **full-screen intent** plus a content intent onto the session page: with the screen locked or dark the question
 *   comes to the front instead of waiting in the shade, and with the screen on the heads-up is the surface.
 */
internal class AndroidNotificationSurface(private val context: Context) : NotificationSurface {

    private val manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)

    override fun available(): Boolean = unavailableReason() == null

    /**
     * 0.3 Phase 4B (B5, spec §1.3 drift 3): the three live device answers behind [available].
     *
     * The channel is created lazily by [post] at [ConfirmationChannel.IMPORTANCE], so before the first post
     * `getNotificationChannel` answers `null` - "not created yet", **not** a disable. Once it exists, the user may have
     * turned it off; `IMPORTANCE_NONE` is Android's own "this channel does not post" and must be reported as
     * [NotificationAvailability.CHANNEL_DISABLED] rather than hidden behind the App-level switch.
     *
     * The reads are device-only (`NotificationManager` cannot be instantiated on the JVM); the **decision** is the
     * pure [NotificationAvailability] rule and is what the unit tests pin.
     */
    override fun unavailableReason(): String? = NotificationAvailability.unavailableReason(
        permissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        appNotificationsEnabled = manager?.areNotificationsEnabled() == true,
        channelImportance = channelImportance(),
    )

    /** The confirmation channel's live importance, or `null` when it has not been created (or cannot be read). */
    private fun channelImportance(): Int? = runCatching {
        manager?.getNotificationChannel(ConfirmationChannel.ID)?.importance
    }.getOrNull()

    override fun post(
        requestId: String,
        title: String,
        text: String,
        ttlMillis: Long,
        actions: ConfirmationActions,
    ) {
        val target = manager ?: return
        target.createNotificationChannel(channel())
        // The first channel's sound and vibration cannot be changed in place (Android only updates name/description on
        // an existing id), so this one is a new id - and the old one is removed rather than left as a dead row in the
        // user's notification settings.
        runCatching { target.deleteNotificationChannel(ConfirmationChannel.LEGACY_ID) }

        val openApp = openAppIntent()
        val notification = Notification.Builder(context, ConfirmationChannel.ID)
            .setSmallIcon(R.drawable.ic_edith_confirm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(ttlMillis)
            .setContentIntent(openApp)
            .setFullScreenIntent(openApp, true)
            .apply { actions.all.forEach { addAction(action(it, requestId)) } }
            .build()
        target.notify(NOTIFICATION_ID, notification)
    }

    /** One question at a time by construction (`SmartConfirmation` refuses a second), so one id is the whole state. */
    override fun cancel(requestId: String) { manager?.cancel(NOTIFICATION_ID) }

    override fun cancelAll() { manager?.cancel(NOTIFICATION_ID) }

    private fun channel(): NotificationChannel =
        NotificationChannel(ConfirmationChannel.ID, ConfirmationChannel.NAME, ConfirmationChannel.IMPORTANCE).apply {
            description = ConfirmationChannel.DESCRIPTION
            // Explicit on purpose: a channel vibrates only if it is asked to, and an unnoticed question is a denied one.
            enableVibration(true)
            vibrationPattern = ConfirmationChannel.VIBRATION_PATTERN
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

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

    /** The session page, where the question's own card waits - the target of both the tap and the full-screen intent. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_CONFIRMATION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 0x0ED17
    }
}
