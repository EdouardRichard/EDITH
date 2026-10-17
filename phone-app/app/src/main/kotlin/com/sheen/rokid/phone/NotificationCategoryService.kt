package com.sheen.rokid.phone
import com.sheen.rokid.phone.context.NotificationCategorySource
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The notification listener that feeds [com.sheen.rokid.phone.context.NotificationCategorySource] (P3.2 Stage B, Task 12).
 *
 * It reads exactly one field of one notification - `Notification.category` (falling back to the channel id when the
 * category is unset, because that is the same kind of opaque classification) - and keeps only the newest one in
 * memory. **No title, no text, no `EXTRA_*` extra is read, stored or forwarded**: `NotificationCategorySourceTest`
 * pins that the downstream type can only accept a single opaque string, and this class is the only thing that produces
 * it.
 *
 * `onListenerConnected`/`onListenerDisconnected` are what make "granted in Settings" observable: the system binds this
 * service only while the user has granted notification access, so a null instance is the honest "not granted" answer.
 * The reference is cleared on disconnect, so a revoked grant cannot leave a stale listener behind.
 */
class NotificationCategoryService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        // Category first, channel id second: both are platform classification strings, and neither carries content.
        val category = notification.category?.trim()?.takeIf { it.isNotEmpty() }
            ?: notification.channelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        latestCategory = category
    }

    companion object {
        @Volatile
        private var instance: NotificationCategoryService? = null

        @Volatile
        private var latestCategory: String? = null

        /** Whether the user has granted notification access (the service is bound). */
        fun isConnected(): Boolean = instance != null

        /** The category of the most recent categorised notification, or null. Never notification content. */
        fun latest(): String? = latestCategory
    }
}
