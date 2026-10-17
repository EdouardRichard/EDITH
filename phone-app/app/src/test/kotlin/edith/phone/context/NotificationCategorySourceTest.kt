package edith.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 12: the notification **category** as one context key.
 *
 * The privacy rule (spec §8.2, D-P3-14) is what this class is shaped around: it takes exactly one string - the
 * notification's own `category` (or channel id) - and has no way to reach a title, a body or an `EXTRA_*` extra. The
 * absence of a `StatusBarNotification` (or of any `Notification`) in this type's inputs is the proof that no such
 * content can leak, and it is asserted here rather than promised in a comment.
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
}
