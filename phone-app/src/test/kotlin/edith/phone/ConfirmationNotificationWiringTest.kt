package edith.phone

import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.host.HostRuntime
import edith.plugin.CancellationHandle
import edith.plugin.ModelClient
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.storage.MemoryProfileRepository
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import edith.testsupport.RecordingInputPort
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
