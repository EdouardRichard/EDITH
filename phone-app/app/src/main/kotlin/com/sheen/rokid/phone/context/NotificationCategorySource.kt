package com.sheen.rokid.phone.context
import com.sheen.rokid.phone.context.NotificationCategoryService

/**
 * The notification **category** as one context signal, published under [KEY] (P3.2 Stage B, Task 12).
 *
 * D179 supersedes the old category-only rule for perception, but it does not widen this type: its only input is one
 * opaque platform string. There is no `Notification`, no `StatusBarNotification` and no `Bundle` here to read a title or
 * a body from, so the value this source publishes can only ever be the category. Notification **content** is a separate
 * source ([NotificationContentSource]); keeping it separate is what preserves the single-string contract of
 * `notification.category` while the perception pipeline gets the content D179 allows it to see.
 *
 * Degradation follows Task 11's rule: not connected, or no category, contributes **no key** - never a placeholder.
 */
internal class NotificationCategorySource(
    private val connected: () -> Boolean,
    private val latest: () -> String?,
) {

    /** Whether the notification listener is currently bound; the settings screen's copy reads this. */
    val isConnected: Boolean get() = runCatching { connected() }.getOrDefault(false)

    fun values(): Map<String, String> {
        if (!isConnected) return emptyMap()
        val category = runCatching { latest() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return mapOf(KEY to category)
    }

    companion object {
        const val KEY = "notification.category"
    }
}
