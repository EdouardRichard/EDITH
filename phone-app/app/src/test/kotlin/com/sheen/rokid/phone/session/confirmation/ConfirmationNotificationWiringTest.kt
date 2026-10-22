package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.withP3Sink

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
        var reason: String? = null

        override fun available(): Boolean = canPost
        override fun unavailableReason(): String? = if (canPost) null else reason
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

        /** The presenter wired the way the composition wires it, kept so a test can read its live state. */
        var presenter: ConfirmationPresenter? = null

        /** How many times the composition asked the overlay path to raise the Edith page. */
        var raised = 0

        var summaryAvailable = true
        var summaryAccepts = true
        var glassesAvailable = false
        var glassesAccepts = false

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = scheduler,
            )
            root = HostCompositionRoot(
                HostRuntime(1, environment), MemoryProfileRepository(), UnusedModel(), environment,
            )
            root.notificationSurface = surface
        }

        /** Makes the overlay path real: both the refusal probe and the raise action, as production wires them. */
        fun allowRaise(accepted: Boolean = true) {
            root.canRaiseConfirmationUi = { accepted }
            root.raiseConfirmationUi = { raised++; accepted }
        }

        fun newPresenter(): ConfirmationPresenter {
            val built = ConfirmationPresenter(
                summary = object : SummaryDisplayer {
                    override fun available(): Boolean = summaryAvailable
                    override fun show(hintId: String, text: String, ttlMillis: Long): Boolean = summaryAccepts
                },
                // The **production** phone surface rule, not a copy: the whole B5 point is that the composition
                // existing is not the same thing as the wearer being reachable.
                ui = HostCompositionRoot.phoneConfirmationUi { root },
                glasses = object : ConfirmationGlasses {
                    override fun available(): Boolean = glassesAvailable
                    override fun show(requestId: String, active: Boolean): Boolean = glassesAccepts
                },
                deliver = { delivered += it.requestId },
                clock = clock,
                scheduler = scheduler,
                envelopeFor = { id -> EventEnvelope(SessionId("s"), Generation(1), id, 0, 1) },
            )
            presenter = built
            root.confirmationPresenter = built
            return built
        }

        /** A question registered in the presenter (which is what the answer path goes through) and on the page. */
        fun present(requestId: String, summary: String) {
            newPresenter().present(requestId, summary, 10_000L)
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
    fun aQuestionNobodyCanSeeChoosesTheShadeAndDoesNotDragTheWearerIntoEdith() {
        // 0.3 Phase 4B (B5, spec §5.4/D191): the priority is glasses > notification > voice > Edith dialog. With the
        // page hidden and the shade available, the notification is the chosen channel - the Edith page is only the
        // fallback, so it must not be raised on top (that was the old always-raise behaviour).
        val rig = Rig()
        try {
            rig.allowRaise()

            rig.present("c1", "调整屏幕亮度")
            assertEquals("with the card on screen there is nothing to raise", 0, rig.raised)

            rig.root.uiVisible(false)

            assertEquals("a question the wearer cannot see goes to the shade",
                listOf("c1" to "调整屏幕亮度"), rig.surface.posted)
            assertEquals("the shade answered the priority question; the Edith fallback stays unused", 0, rig.raised)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aQuestionNobodyCanSeeFallsBackToRaisingTheEdithPageWhenTheShadeCannotPost() {
        val rig = Rig()
        try {
            rig.surface.canPost = false
            rig.surface.reason = NotificationAvailability.CHANNEL_DISABLED
            rig.allowRaise()

            rig.present("c1", "调整屏幕亮度")
            withP3Sink { lines ->
                rig.root.uiVisible(false)
                assertTrue("no postable shade means no notification", rig.surface.posted.isEmpty())
                assertEquals("the Edith page is the last fallback", 1, rig.raised)
                assertTrue(
                    "the degradation must name the channel switch with its stable reason: $lines",
                    lines.any { "P3_CONFIRM_NOTIFY" in it && "reason=CHANNEL_DISABLED" in it },
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun theGlassesChannelStopsTheLadderSoTheShadeAndThePageAreNotAlsoUsed() {
        val rig = Rig()
        try {
            rig.glassesAvailable = true
            rig.glassesAccepts = true
            rig.allowRaise()

            rig.present("c1", "调整屏幕亮度")
            rig.surface.posted.clear()
            rig.root.uiVisible(false)

            assertTrue("the glasses already carry the question: ${rig.surface.posted}", rig.surface.posted.isEmpty())
            assertEquals("and nothing drags the wearer into Edith", 0, rig.raised)
            assertTrue("the same question is still answerable on the glasses",
                requireNotNull(rig.presenter).answerFromGlasses("c1", approved = true))
            assertEquals(listOf("c1"), rig.delivered)
        } finally {
            rig.close()
        }
    }

    @Test
    fun thePhoneSurfaceIsTruthfulAboutThePageTheOverlayAndTheShade() {
        // B4 review: `ConfirmationUi.available()` was `rootHolder[0] != null`, i.e. true for the whole life of the
        // composition - so the presenter never reported unavailable and AgentLoop's early refusal was unreachable.
        val rig = Rig()
        try {
            assertTrue("the page on screen is a phone surface", rig.root.canPresentOnPhone())

            rig.surface.canPost = false
            rig.root.uiVisible(false)
            assertFalse("hidden page, no overlay and no shade: the phone cannot show the question",
                rig.root.canPresentOnPhone())

            rig.surface.canPost = true
            assertTrue("a postable shade reaches the wearer", rig.root.canPresentOnPhone())

            rig.surface.canPost = false
            rig.root.canRaiseConfirmationUi = { true }
            assertTrue("a page that can be raised over another app is a surface", rig.root.canPresentOnPhone())
        } finally {
            rig.close()
        }
    }

    @Test
    fun aPresenterWithNoUsableSurfaceReportsUnavailableInsteadOfClaimingTheCompositionExists() {
        // Locked/background, notifications off, no overlay and no glasses: the production phone surface must report
        // false, which is what makes AgentLoop refuse at once with CONFIRMATION_UNAVAILABLE (its own boundary test
        // pins the refusal; this test pins that production can reach it).
        val rig = Rig()
        try {
            rig.summaryAvailable = false
            rig.glassesAvailable = false
            rig.surface.canPost = false
            rig.root.uiVisible(false)
            val presenter = rig.newPresenter()

            assertFalse("a hidden page + no shade + no overlay + no glasses is nobody to ask", presenter.available())
            assertFalse("and the production phone surface agrees",
                HostCompositionRoot.phoneConfirmationUi { rig.root }.available())
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
