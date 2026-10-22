package com.sheen.rokid.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 12: the notification **category** as one context key; 0.3 Phase 2 Task 3 keeps that contract.
 *
 * D179 supersedes the old category-only rule for perception, but it does not widen this type: its only input is one
 * opaque platform string - the notification's own `category` (or channel id) - and there is no `StatusBarNotification`
 * (or any `Notification`/`Bundle`) to read a title or a body from. Notification **content** is deliberately a separate
 * source ([NotificationContentSource]); the assertion below is what keeps this key's published map to a single opaque
 * value while that content path exists.
 */
class NotificationCategorySourceTest {

    @Test
    fun aConnectedListenerWithACategorisedNotificationNamesTheCategory() {
        val source = NotificationCategorySource(connected = { true }, latest = { "msg" })

        assertEquals(mapOf("notification.category" to "msg"), source.values())
    }

    @Test
    fun withoutTheListenerGrantTheKeyIsAbsent() {
        val source = NotificationCategorySource(connected = { false }, latest = { "msg" })

        assertTrue(source.values().isEmpty())
        assertFalse(source.isConnected)
    }

    @Test
    fun aNotificationWithoutACategoryAddsNoKey() {
        assertTrue(NotificationCategorySource(connected = { true }, latest = { null }).values().isEmpty())
        assertTrue(NotificationCategorySource(connected = { true }, latest = { "   " }).values().isEmpty())
        assertTrue(
            "a throwing listener is a missing value, never a crash in the context path",
            NotificationCategorySource(connected = { true }, latest = { error("dead binder") }).values().isEmpty(),
        )
    }

    @Test
    fun theCategoryIsNormalisedButNeverInvented() {
        // A category is an opaque platform string; trimming is all this source may do to it.
        assertEquals(mapOf("notification.category" to "alarm"),
            NotificationCategorySource(connected = { true }, latest = { "  alarm  " }).values())
    }

    @Test
    fun theCategorySourceStillPublishesOnlyTheOpaqueCategory() {
        // The content path is separate by type: this source is wired to the category string alone, so even when content
        // is available elsewhere, its map can only ever be the one `notification.category` entry.
        val content = NotificationContentSource(
            connected = { true },
            recent = {
                listOf(
                    NotificationContent(
                        packageName = "com.synthetic.mail",
                        appLabel = "Synthetic Mail",
                        title = "Synthetic subject",
                        text = "Synthetic body",
                        subText = "Synthetic subtext",
                        bigText = "Synthetic big body",
                        category = "msg",
                        postedAtEpochMillis = 1L,
                        ongoing = false,
                        actionLabels = listOf("Reply"),
                    ),
                )
            },
            category = { "msg" },
        )
        val categorySource = NotificationCategorySource(connected = { true }, latest = content::latestCategory)

        assertEquals(mapOf("notification.category" to "msg"), categorySource.values())
        assertEquals(1, categorySource.values().size)
        assertEquals(
            "the content path is the separate place those fields can be read",
            "Synthetic subject",
            content.recent(1).single().title,
        )
    }
}
