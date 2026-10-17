package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §17.4: the notification is the surface for a question the user **cannot see on the page**, and its buttons answer
 * through the composition's own path - never a second one.
 *
 * The composition's rules are pinned here on the JVM (a fake [NotificationSurface]), so "which surface carries the
 * question, and when" is decided by a test rather than by watching a phone.
 */
class ConfirmationNotificationWiringTest {

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    private class FakeSurface : NotificationSurface {
        val posted = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        var cancelAlls = 0
        var canPost = true

        override fun available(): Boolean = canPost
        override fun post(
            requestId: String,
            title: String,
            text: String,
            ttlMillis: Long,
            actions: ConfirmationActions,
        ) { posted += requestId to text }
        override fun cancel(requestId: String) { cancelled += requestId }
        override fun cancelAll() { cancelAlls++ }
    }

    private class Rig {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val surface = FakeSurface()
        val delivered = mutableListOf<String>()
        val root: HostCompositionRoot

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = scheduler,
            )
            root = HostCompositionRoot(
                HostRuntime(1, environment), MemoryProfileRepository(), UnusedModel(), environment,
            )
            root.notificationSurface = surface
        }

        /** A question registered in the presenter (which is what the answer path goes through) and on the page. */
        fun present(requestId: String, summary: String) {
            root.confirmationPresenter = ConfirmationPresenter(
                summary = { _, _ -> true },
                ui = object : ConfirmationUi {
                    override fun show(requestId: String, summary: String) = root.showConfirmation(requestId, summary)
                    override fun hide(requestId: String) = root.hideConfirmation(requestId)
                },
                deliver = { delivered += it.requestId },
                clock = clock,
                scheduler = scheduler,
                envelopeFor = { id -> EventEnvelope(SessionId("s"), Generation(1), id, 0, 1) },
            )
            root.confirmationPresenter!!.present(requestId, summary, 10_000L)
        }

        fun close() = root.close()
    }

    @Test
    fun theShadeOnlyCarriesAQuestionTheUserCannotSeeOnThePage() {
        val rig = Rig()
        try {
            rig.present("c1", "调整屏幕亮度")
            assertTrue("with the page on screen the card is the surface: ${rig.surface.posted}", rig.surface.posted.isEmpty())

            rig.root.uiVisible(false)
            assertEquals(listOf("c1" to "调整屏幕亮度"), rig.surface.posted)

            rig.root.uiVisible(true)
            assertEquals("coming back to the page must take the duplicate out of the shade",
                listOf("c1"), rig.surface.cancelled)

            rig.root.uiVisible(false)
            assertEquals("leaving again must re-post it while the question is still live", 2, rig.surface.posted.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aQuestionThatSettlesLeavesTheShade() {
        val rig = Rig()
        try {
            rig.present("c1", "调整屏幕亮度")
            rig.root.uiVisible(false)

            assertTrue(rig.root.answerConfirmation("c1", approved = true))

            assertEquals("a question that was answered must not keep offering buttons",
                listOf("c1"), rig.surface.cancelled)
            assertEquals(listOf("c1"), rig.delivered)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aTapNamingADifferentQuestionSettlesNothing() {
        val rig = Rig()
        try {
            rig.present("c1", "调整屏幕亮度")

            assertFalse("a stale notification must not answer whatever is pending now",
                rig.root.answerConfirmation("c9", approved = true))
            assertTrue("the question it does name still answers normally",
                rig.root.answerConfirmation("c1", approved = true))
            assertEquals(listOf("c1"), rig.delivered)
        } finally {
            rig.close()
        }
    }

    @Test
    fun stoppingTheSessionClearsTheShade() {
        val rig = Rig()
        try {
            rig.present("c1", "调整屏幕亮度")
            rig.root.uiVisible(false)

            rig.root.stop()

            assertEquals("a dead session's question must not stay in the shade", 1, rig.surface.cancelAlls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aHideThatNamesAnAlreadySettledQuestionLeavesTheLiveOneInTheShade() {
        // The shade holds one notification for every question, so "which question does this take down?" is a real
        // question: a late hide(c1) after c2 went up must not remove c2's only surface, because the user is away from
        // the page and would never see c2 at all (leftover review of this round, Important 1).
        val rig = Rig()
        try {
            rig.present("c1", "调整屏幕亮度")
            rig.root.uiVisible(false)
            assertEquals(listOf("c1" to "调整屏幕亮度"), rig.surface.posted)

            rig.root.showConfirmation("c2", "卸载 App：probe")
            rig.surface.cancelled.clear()

            rig.root.hideConfirmation("c1")

            assertTrue("a late hide for c1 must not remove c2's notification: ${rig.surface.cancelled}",
                rig.surface.cancelled.isEmpty())
            assertEquals("the live question must survive the late hide",
                "c2", rig.root.state.value.pendingConfirmation?.requestId)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aQuestionNobodyCanSeeRaisesThePageAsWellAsTheShade() {
        // Owner directive 2026-10-10: the question must reach the user like a system popup, not as a shade entry they
        // have to go looking for. The page is raised *and* the notification posted: the popup is what makes it
        // unmissable, the notification carries the sound/vibration and stays as the fallback.
        val rig = Rig()
        try {
            var raised = 0
            rig.root.raiseConfirmationUi = { raised++; true }

            rig.present("c1", "调整屏幕亮度")
            assertEquals("with the card on screen there is nothing to raise", 0, raised)

            rig.root.uiVisible(false)
            rig.root.showConfirmation("c2", "卸载 App：probe")

            assertEquals("a question the user cannot see must be raised to the front", 1, raised)
            assertEquals("and still reach the shade as the fallback", listOf("c1" to "调整屏幕亮度", "c2" to "卸载 App：probe"),
                rig.surface.posted)
        } finally {
            rig.close()
        }
    }

    @Test
    fun theQuestionCarriesTheDeadlineTheUiCountsDown() {
        // Owner directive 2026-10-10: the phone shows a countdown and closes the question when it runs out, so the
        // state the UI reads has to carry the real window - not a number the UI invents for itself.
        val rig = Rig()
        try {
            rig.present("c1", "调整屏幕亮度")

            val pending = requireNotNull(rig.root.state.value.pendingConfirmation)
            val remaining = pending.expiresAtMillis - System.currentTimeMillis()
            assertTrue("the deadline must be the window the plugin enforces, was ${remaining}ms left",
                remaining in (ConfirmationNotifier.WINDOW_MILLIS - 2_000L)..ConfirmationNotifier.WINDOW_MILLIS)
        } finally {
            rig.close()
        }
    }

    @Test
    fun anUnavailableShadeLeavesTheQuestionOnThePageOnly() {
        val rig = Rig()
        try {
            rig.surface.canPost = false
            rig.present("c1", "调整屏幕亮度")

            rig.root.uiVisible(false)

            assertTrue("no permission means no notification, never a refused question", rig.surface.posted.isEmpty())
            assertEquals("the card still holds it", "c1", rig.root.state.value.pendingConfirmation?.requestId)
        } finally {
            rig.close()
        }
    }
}
