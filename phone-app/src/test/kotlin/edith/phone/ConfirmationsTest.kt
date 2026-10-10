package edith.phone

import edith.contracts.ConfirmationResult
import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.CancellationHandle
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host half of spec §17.4: it *presents* the question on both screens and turns the phone's tap into the
 * `ConfirmationResult` event that settles the plugin's gate.
 *
 * The host is deliberately **not** the authority on the answer: a timeout here only takes the question off the screens
 * and emits nothing, because "silence is a denial" is decided by the plugin's gate, on its own clock. That split is
 * what keeps a phone that went to sleep from looking like an approval.
 */
class ConfirmationsTest {

    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
    private val envelope = EventEnvelope(SessionId("s"), Generation(1), "e1", 0, 1)

    private class FakeSummary(var accepts: Boolean = true) : SummaryDisplayer {
        val shown = mutableListOf<Pair<String, Long>>()
        var failure: RuntimeException? = null
        override fun show(text: String, ttlMillis: Long): Boolean {
            failure?.let { throw it }
            shown += text to ttlMillis
            return accepts
        }
    }

    private class FakeUi : ConfirmationUi {
        val shown = mutableListOf<Pair<String, String>>()
        val hidden = mutableListOf<String>()
        override fun show(requestId: String, summary: String) { shown += requestId to summary }
        override fun hide(requestId: String) { hidden += requestId }
    }

    private class Rig {
        val summary = FakeSummary()
        val ui = FakeUi()
        val delivered = mutableListOf<ConfirmationResult>()
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val presenter = ConfirmationPresenter(
            summary = summary,
            ui = ui,
            deliver = { delivered += it },
            clock = clock,
            scheduler = scheduler,
            envelopeFor = { requestId ->
                EventEnvelope(SessionId("s"), Generation(1), requestId, 0, 1)
            },
        )
    }

    private fun rig() = Rig()

    @Test
    fun presentShowsTheSummaryOnBothScreensForLongerThanTheWindow() {
        val rig = rig()

        rig.presenter.present("c1", "卸载 App：edith.phone.a11yprobe", 10_000L)

        val (text, ttl) = rig.summary.shown.single()
        assertTrue("the wearer must see what the action does: $text", "edith.phone.a11yprobe" in text)
        assertTrue("the summary must outlive the decision window, ttl=$ttl", ttl > 10_000L)
        assertEquals(listOf("c1" to text), rig.ui.shown)
        assertEquals(1, rig.presenter.pendingCount)
    }

    @Test
    fun anAnswerEmitsAConfirmationResultWithTheSameRequestId() {
        val rig = rig()
        rig.presenter.present("c1", "卸载 App", 10_000L)

        val answered = rig.presenter.answer("c1", approved = true)

        assertTrue(answered)
        val result = rig.delivered.single()
        assertEquals("c1", result.requestId)
        assertTrue(result.approved)
        assertEquals(SessionId("s"), result.envelope.sessionId)
        assertEquals(Generation(1), result.envelope.generation)
        assertEquals(listOf("c1"), rig.ui.hidden)
        assertEquals(0, rig.presenter.pendingCount)
        assertFalse("a settled question takes no second answer", rig.presenter.answer("c1", approved = true))
        assertEquals(1, rig.delivered.size)
    }

    @Test
    fun aTimeoutTakesTheQuestionOffTheScreensAndEmitsNothing() {
        val rig = rig()
        rig.presenter.present("c1", "卸载 App", 10_000L)

        rig.clock.advance(9_999_000_000L)
        rig.scheduler.runDue()
        assertEquals("the question must survive until its window closes", 1, rig.presenter.pendingCount)

        rig.clock.advance(2_000_000L)
        rig.scheduler.runDue()

        assertEquals(listOf("c1"), rig.ui.hidden)
        assertTrue("the gate, not the host, decides what silence means", rig.delivered.isEmpty())
        assertEquals(0, rig.presenter.pendingCount)
    }

    @Test
    fun withdrawingARequestHidesItAndEmitsNothing() {
        val rig = rig()
        val handle = rig.presenter.present("c1", "卸载 App", 10_000L)

        handle.cancel()

        assertEquals(listOf("c1"), rig.ui.hidden)
        assertTrue(rig.delivered.isEmpty())
        assertFalse(rig.presenter.answer("c1", approved = false))
    }

    @Test
    fun aDisplayFailureStillPutsTheQuestionOnThePhone() {
        val rig = rig()
        rig.summary.failure = RuntimeException("glasses link down")

        runCatching { rig.presenter.present("c1", "卸载 App", 10_000L) }

        assertEquals("the phone is the fallback screen, not a second failure", 1, rig.ui.shown.size)
        assertTrue("the question is still answerable", rig.presenter.answer("c1", approved = false))
    }

    @Test
    fun aSecondRequestWithTheSameIdNeverReplacesALiveOne() {
        val rig = rig()
        rig.presenter.present("c1", "第一个动作", 10_000L)

        rig.presenter.present("c1", "第二个动作", 10_000L)

        assertEquals("only one question is live", 1, rig.presenter.pendingCount)
        assertEquals("the live question is the first one", listOf("c1" to "第一个动作"), rig.ui.shown)
        assertTrue(rig.presenter.answer("c1", approved = true))
        assertEquals(1, rig.delivered.size)
    }

    @Test
    fun anAnswerForAnUnknownRequestIdEmitsNothing() {
        val rig = rig()

        assertFalse(rig.presenter.answer("nope", approved = true))
        assertTrue(rig.delivered.isEmpty())
    }

    @Test
    fun anOverlongSummaryIsTruncatedBeforeItReachesTheGlasses() {
        val rig = rig()
        val long = "卸载 App：" + "x".repeat(300)

        rig.presenter.present("c1", long, 10_000L)

        val text = rig.summary.shown.single().first
        assertTrue("the summary must fit the hint gate, was ${text.length}", text.length <= ConfirmationPresenter.MAX_SUMMARY_CHARS)
        assertTrue("a truncation must be visible: $text", text.endsWith("…"))
    }

    @Test
    fun aBlankRequestIdOrNonPositiveWindowIsRejectedAtThePort() {
        val rig = rig()

        assertThrows(IllegalArgumentException::class.java) { rig.presenter.present("  ", "动作", 1_000L) }
        assertThrows(IllegalArgumentException::class.java) { rig.presenter.present("c1", "动作", 0L) }
        assertEquals(0, rig.presenter.pendingCount)
    }
}
