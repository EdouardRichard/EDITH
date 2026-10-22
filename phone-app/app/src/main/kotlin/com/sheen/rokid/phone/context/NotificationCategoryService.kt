package com.sheen.rokid.phone.context

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The notification listener that feeds both notification context signals (P3.2 Stage B, Task 12; 0.3 Phase 2 Task 3).
 *
 * D179 supersedes the old category-only rule: the assistant is allowed to perceive notification content, so this
 * service maps each posted notification to a [NotificationContent] (package, app label, title/text/subText/bigText,
 * category, post time, ongoing flag and action labels) and keeps the newest [NOTIFICATION_CONTENT_LIMIT] in memory for
 * [NotificationContentSource]. The content stays in this app process's memory only: no log line prints it, nothing is
 * written to disk, and only action **titles** are read - never an action's `PendingIntent` or intent.
 *
 * [NotificationCategorySource] remains a deliberately narrower signal. It reads only [latest] - the opaque
 * category/channel string - so the existing `notification.category` key keeps its single-string shape while content is
 * served separately.
 *
 * `onListenerConnected` seeds the ring from `activeNotifications` when the system exposes them, oldest-first, so a
 * process that binds after notifications already exist still has content. `onListenerDisconnected` clears both the ring
 * and [latest]: a revoked grant cannot leave stale content readable. The system binds this service only while the user
 * has granted notification access, so a null instance is the honest "not granted" answer.
 */
class NotificationCategoryService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        seedFromActiveNotifications()
    }

    override fun onListenerDisconnected() {
        // A stale instance's disconnect must not wipe the content of the instance that replaced it.
        if (instance !== this) return
        instance = null
        content.clear()
        latestCategory = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        // Category first, channel id second: both are platform classification strings and neither carries content.
        val category = categoryOf(notification)
        if (category != null) latestCategory = category
        // A mapping failure costs one ring entry, never the listener or the category update above.
        contentOf(sbn)?.let(content::add)
    }

    /** The active notifications as mapped content, oldest-first; anything unreadable is skipped. */
    private fun seedFromActiveNotifications() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val contents = runCatching {
            active.mapNotNull { sbn -> contentOf(sbn) }.sortedBy { it.postedAtEpochMillis }
        }.getOrDefault(emptyList())
        content.seed(contents)
    }

    /** One notification mapped to [NotificationContent], or `null` when the platform object is unreadable. */
    private fun contentOf(sbn: StatusBarNotification?): NotificationContent? {
        val notification = sbn?.notification ?: return null
        val category = categoryOf(notification)
        return runCatching {
            NotificationContentMapper.map(
                packageName = sbn.packageName,
                appLabel = applicationLabel(sbn.packageName),
                extras = contentExtras(notification),
                category = category,
                postedAtEpochMillis = sbn.postTime,
                ongoing = sbn.isOngoing,
                actionTitles = notification.actions?.mapNotNull { it.title?.toString() }.orEmpty(),
            )
        }.getOrNull()
    }

    /** The category, or the channel id as the same kind of opaque classification string. */
    private fun categoryOf(notification: Notification): String? =
        notification.category?.trim()?.takeIf { it.isNotEmpty() }
            ?: notification.channelId?.trim()?.takeIf { it.isNotEmpty() }

    /** Only the four content extras are copied out of the platform bundle; nothing else is read. */
    private fun contentExtras(notification: Notification): Map<String, String?> {
        val extras = notification.extras ?: return emptyMap()
        return mapOf(
            NotificationContentMapper.KEY_TITLE to
                extras.getCharSequence(NotificationContentMapper.KEY_TITLE)?.toString(),
            NotificationContentMapper.KEY_TEXT to
                extras.getCharSequence(NotificationContentMapper.KEY_TEXT)?.toString(),
            NotificationContentMapper.KEY_SUB_TEXT to
                extras.getCharSequence(NotificationContentMapper.KEY_SUB_TEXT)?.toString(),
            NotificationContentMapper.KEY_BIG_TEXT to
                extras.getCharSequence(NotificationContentMapper.KEY_BIG_TEXT)?.toString(),
        )
    }

    /** The posting app's label; any resolution failure is `null`, never a package-name substitute. */
    private fun applicationLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    companion object {
        @Volatile
        private var instance: NotificationCategoryService? = null

        @Volatile
        private var latestCategory: String? = null

        private val content = NotificationContentRing()

        /** Whether the user has granted notification access (the service is bound). */
        fun isConnected(): Boolean = instance != null

        /**
         * The opaque category/channel string of the last categorised notification; content is served by
         * [recentContent].
         */
        fun latest(): String? = latestCategory

        /** The newest captured notifications, newest-first; the seam [NotificationContentSource] delegates to. */
        internal fun recentContent(limit: Int): List<NotificationContent> = content.snapshot(limit)
    }
}
