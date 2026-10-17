package com.sheen.rokid.phone.context
import com.sheen.rokid.phone.NotificationCategoryService

/**
 * The notification **category** as one context signal, published under [KEY] (P3.2 Stage B, Task 12).
 *
 * Spec §8.2 keeps the notification layer to the category by default, and the shape of this type is that rule: its only
 * input is one opaque platform string. There is no `Notification`, no `StatusBarNotification` and no `Bundle` to read
 * a title or a body from - so "the assistant never sees notification content" is a property of the type rather than a
 * promise about its implementation. The listener service that feeds it maps `Notification.category` and nothing else
 * (`NotificationCategoryService`).
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
