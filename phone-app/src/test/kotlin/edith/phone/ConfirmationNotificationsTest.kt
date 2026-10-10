package edith.phone

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §17.4's phone half, the surface P3.3 shipped without: when the App is **not** in front of the user, the question
 * still has to reach them - and answering it must go through the same settlement path as the on-screen card.
 *
 * The notification builder is Android's, so the *rules* live behind a narrow port and are pinned here: what gets
 * posted, what a missing permission means (a degradation, never a refusal), and what the log line may carry.
 */
class ConfirmationNotificationsTest {

    private class FakeSurface(var canPost: Boolean = true) : NotificationSurface {
        data class Posted(
            val requestId: String,
            val title: String,
            val text: String,
            val ttlMillis: Long,
            val actions: ConfirmationActions,
        )

        val posted = mutableListOf<Posted>()
        val cancelled = mutableListOf<String>()
        var cancelAlls = 0

        override fun available(): Boolean = canPost
        override fun post(
            requestId: String,
            title: String,
            text: String,
            ttlMillis: Long,
            actions: ConfirmationActions,
        ) {
            posted += Posted(requestId, title, text, ttlMillis, actions)
        }
        override fun cancel(requestId: String) { cancelled += requestId }
        override fun cancelAll() { cancelAlls++ }
    }

    private fun notifier(surface: FakeSurface, log: MutableList<String> = mutableListOf()) =
        ConfirmationNotifier(surface, log = { log += it })

    @Test
    fun aQuestionPostsOneNotificationCarryingTheSummaryAndThreeDistinctActions() {
        val surface = FakeSurface()

        assertTrue(notifier(surface).post("c1", "调整屏幕亮度"))

        val posted = surface.posted.single()
        assertEquals("c1", posted.requestId)
        assertEquals("需要你确认的动作", posted.title)
        assertEquals("the notification must say what would happen, exactly as the card and the glasses do",
            "调整屏幕亮度", posted.text)
        assertEquals(listOf("允许", "拒绝", "急停"), posted.actions.all.map { it.label })
        assertEquals("the three buttons must be distinguishable, or the receiver cannot tell them apart",
            3, posted.actions.all.map { it.id }.toSet().size)
        assertTrue("the fallback cleanup must outlive the question window (${ConfirmationNotifier.WINDOW_MILLIS} ms), " +
            "or it would hide a live question", posted.ttlMillis > ConfirmationNotifier.WINDOW_MILLIS)
    }

    @Test
    fun theChannelIsBuiltToBeNoticed() {
        // Owner directive 2026-10-10: the first channel had no vibration on this device and was noticed by nobody, so a
        // time-boxed question simply expired. Android cannot change an existing channel's sound or vibration, which is
        // why the perceivable shape lives on a new id and why that fact is pinned here.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ConfirmationChannel.IMPORTANCE)
        assertNotEquals(ConfirmationChannel.LEGACY_ID, ConfirmationChannel.ID)
        assertTrue("a channel that does not vibrate is a channel nobody notices in a pocket",
            ConfirmationChannel.VIBRATION_PATTERN.isNotEmpty())
        assertTrue("the buzz must last long enough to be felt",
            ConfirmationChannel.VIBRATION_PATTERN.sum() >= 500L)
    }

    @Test
    fun aSurfaceThatCannotPostDegradesToTheCardInsteadOfRefusingTheAction() {
        val surface = FakeSurface(canPost = false)
        val log = mutableListOf<String>()

        val posted = notifier(surface, log).post("c1", "调整屏幕亮度")

        assertFalse("no notification permission is a degraded surface, not a failed question", posted)
        assertTrue(surface.posted.isEmpty())
        assertTrue("the refusal must be visible in the log: $log", log.any { "P3_CONFIRM_NOTIFY" in it && "PERMISSION_DENIED" in it })
    }

    @Test
    fun theConfirmationLogLineNeverCarriesTheSummary() {
        val surface = FakeSurface()
        val log = mutableListOf<String>()

        notifier(surface, log).post("c1", "卸载 App：edith.phone.a11yprobe")
        notifier(surface).cancel("c1")

        assertTrue(log.isNotEmpty())
        assertTrue("§17.5: the log may name the request and the outcome, never the content: $log",
            log.none { "a11yprobe" in it || "卸载" in it })
        assertTrue("the request id is what makes the line useful: $log", log.all { "c1" in it })
    }

    @Test
    fun theAllowButtonCarriesTheAllowActionAndTheDenyButtonTheDenyAction() {
        // Fail-dangerous binding (leftover review of this round, Important 2): swapping these two would make a user's
        // 拒绝 approve an L3/L4 destructive action, and every other test in this file would still pass. The labels are
        // pinned in order above; this pins which action each one carries.
        assertEquals(ConfirmationNotifier.ACTION_ALLOW, ConfirmationNotifier.ACTIONS.allow.id)
        assertEquals(ConfirmationNotifier.ACTION_DENY, ConfirmationNotifier.ACTIONS.deny.id)
        assertEquals(ConfirmationNotifier.ACTION_STOP, ConfirmationNotifier.ACTIONS.stop.id)
        assertEquals("允许", ConfirmationNotifier.ACTIONS.allow.label)
        assertEquals("拒绝", ConfirmationNotifier.ACTIONS.deny.label)
        assertEquals("急停", ConfirmationNotifier.ACTIONS.stop.label)
    }

    @Test
    fun cancellingForwardsToTheSurfaceSoNoDeadQuestionIsLeftInTheShade() {
        val surface = FakeSurface()
        val subject = notifier(surface)

        subject.cancel("c1")
        subject.cancel("c1")
        subject.cancelAll()

        assertEquals(listOf("c1", "c1"), surface.cancelled)
        assertEquals("a stop must be able to take every question down", 1, surface.cancelAlls)
    }
}
