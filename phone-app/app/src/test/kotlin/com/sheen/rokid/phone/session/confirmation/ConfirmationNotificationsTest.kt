package com.sheen.rokid.phone.session.confirmation

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private class FakeSurface(
        var canPost: Boolean = true,
        var reason: String? = null,
    ) : NotificationSurface {
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
        override fun unavailableReason(): String? = if (canPost) null else reason
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
        val surface = FakeSurface(canPost = false, reason = NotificationAvailability.PERMISSION_DENIED)
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

    // ---------------------- 0.3 Phase 4B (B5): the channel's own state, not just the App-level switch

    @Test
    fun `the availability rule separates permission, App switch and channel switch`() {
        // Spec §1.3/§5.4 drift 3: the surface only asked `areNotificationsEnabled()`, so a user who turned the
        // confirmation CHANNEL off still looked available and the question silently never reached the shade.
        assertNull(
            "granted + App on + channel high is the only available combination",
            NotificationAvailability.unavailableReason(
                permissionGranted = true,
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        assertEquals(
            NotificationAvailability.PERMISSION_DENIED,
            NotificationAvailability.unavailableReason(
                permissionGranted = false,
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        assertEquals(
            NotificationAvailability.APP_NOTIFICATIONS_DISABLED,
            NotificationAvailability.unavailableReason(
                permissionGranted = true,
                appNotificationsEnabled = false,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        assertEquals(
            "a channel the user turned off must not claim the shade can carry a question",
            NotificationAvailability.CHANNEL_DISABLED,
            NotificationAvailability.unavailableReason(
                permissionGranted = true,
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertFalse(
            NotificationAvailability.available(
                permissionGranted = true,
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_NONE,
            ),
        )
    }

    @Test
    fun `a channel that was never created yet is not a disabled channel`() {
        // The channel is created on the first post (`AndroidNotificationSurface.post`); before that the manager answers
        // `null`. Treating "not created" as disabled would make the first confirmation unable to ever post.
        assertNull(
            NotificationAvailability.unavailableReason(
                permissionGranted = true,
                appNotificationsEnabled = true,
                channelImportance = null,
            ),
        )
        assertNull(
            "any importance above NONE can still post",
            NotificationAvailability.unavailableReason(
                permissionGranted = true,
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Test
    fun `a channel-disabled surface logs the stable CHANNEL_DISABLED reason and posts nothing`() {
        val surface = FakeSurface(canPost = false, reason = NotificationAvailability.CHANNEL_DISABLED)
        val log = mutableListOf<String>()

        assertFalse(notifier(surface, log).post("c1", "调整屏幕亮度"))

        assertTrue(surface.posted.isEmpty())
        assertTrue(
            "the degradation must name the channel, not the old generic permission reason: $log",
            log.any { "P3_CONFIRM_NOTIFY" in it && "reason=CHANNEL_DISABLED" in it },
        )
    }

    @Test
    fun `reporting a skipped shade is count-only and carries the surface's own reason`() {
        val surface = FakeSurface(canPost = false, reason = NotificationAvailability.APP_NOTIFICATIONS_DISABLED)
        val log = mutableListOf<String>()

        notifier(surface, log).reportUnavailable("c1")

        assertTrue("log: $log", log.any { "reason=APP_NOTIFICATIONS_DISABLED" in it })
        assertTrue("no content may leak when the shade is skipped: $log", log.none { "调整" in it })
    }
}
