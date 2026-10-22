package com.sheen.rokid.phone.session.confirmation

import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val shown = mutableListOf<Triple<String, String, Long>>()
        var failure: RuntimeException? = null
        var canServe = true
        override fun available(): Boolean = canServe
        override fun show(hintId: String, text: String, ttlMillis: Long): Boolean {
            failure?.let { throw it }
            shown += Triple(hintId, text, ttlMillis)
            return accepts
        }
    }

    private class FakeUi : ConfirmationUi {
        val shown = mutableListOf<Pair<String, String>>()
        val hidden = mutableListOf<String>()
        var canServe = true
        override fun available(): Boolean = canServe
        override fun show(requestId: String, summary: String) { shown += requestId to summary }
        override fun hide(requestId: String) { hidden += requestId }
    }

    private class FakeGlasses : ConfirmationGlasses {
        val states = mutableListOf<Pair<String, Boolean>>()
        var canServe = true
        override fun available(): Boolean = canServe
        override fun show(requestId: String, active: Boolean): Boolean {
            states += requestId to active
            return canServe
        }
    }

    private class FakePanel : ConfirmationPanel {
        var opens = 0
        var accepts = true
        override fun open(): Boolean {
            opens++
            return accepts
        }
    }

    private class Rig {
        val summary = FakeSummary()
        val ui = FakeUi()
        val glasses = FakeGlasses()
        val panel = FakePanel()
        val delivered = mutableListOf<ConfirmationResult>()
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val presenter = ConfirmationPresenter(
            summary = summary,
            ui = ui,
            glasses = glasses,
            panel = panel,
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

        val (_, text, ttl) = rig.summary.shown.single()
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

        val text = rig.summary.shown.single().second
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

    @Test
    fun theIdentityIsReadWhenTheQuestionGoesUpAndNeverAgainOnTheAnswerPath() {
        // P3.3 review, Minor 5. `answer` runs on the session page's UI thread, and the identity round trip it used to
        // make there (`HostRuntime.command` -> `FutureTask.get`) is the same stack frame the P3.3 ANR was caught in - it
        // only ever survived because the host's dispatcher happened to be idle. The question already knows which
        // session asked it, so the answer must not go back to the host to ask again.
        var reads = 0
        val delivered = mutableListOf<ConfirmationResult>()
        val presenter = ConfirmationPresenter(
            summary = FakeSummary(),
            ui = FakeUi(),
            deliver = { delivered += it },
            clock = clock,
            scheduler = scheduler,
            envelopeFor = { requestId ->
                reads++
                EventEnvelope(SessionId("s"), Generation(7), requestId, 0, 1)
            },
        )

        presenter.present("c1", "卸载探针 App", 10_000L)
        assertEquals("the question must carry the identity of the session that asked it", 1, reads)

        assertTrue(presenter.answer("c1", approved = true))
        assertEquals("answering must not ask the host for an identity again", 1, reads)
        assertEquals(Generation(7), delivered.single().envelope.generation)
        assertEquals("c1", delivered.single().requestId)
    }

    // ---------------------------------------- 0.3 Phase 4B (B4): channel availability and the glasses panel

    @Test
    fun `a presenter whose every surface is unavailable is unavailable`() {
        val rig = rig()
        rig.summary.canServe = false
        rig.ui.canServe = false
        rig.glasses.canServe = false

        assertFalse(
            "a host with no screen and no glasses keys has nobody to ask; the loop must refuse instead of waiting",
            rig.presenter.available(),
        )
    }

    @Test
    fun `any one available surface makes the presenter available`() {
        val glassesOnly = rig()
        glassesOnly.ui.canServe = false
        glassesOnly.summary.canServe = true
        glassesOnly.glasses.canServe = true
        assertTrue("display + keys is the answerable glasses surface", glassesOnly.presenter.available())

        val phoneOnly = rig()
        phoneOnly.summary.canServe = false
        phoneOnly.glasses.canServe = false
        phoneOnly.ui.canServe = true
        assertTrue("the phone card is the fallback answer surface", phoneOnly.presenter.available())

        val keysWithoutDisplay = rig()
        keysWithoutDisplay.summary.canServe = false
        keysWithoutDisplay.ui.canServe = false
        keysWithoutDisplay.glasses.canServe = true
        assertFalse(
            "keys alone are not an answer path: the wearer must first be shown what they are answering",
            keysWithoutDisplay.presenter.available(),
        )

        val displayWithoutKeys = rig()
        displayWithoutKeys.summary.canServe = true
        displayWithoutKeys.ui.canServe = false
        displayWithoutKeys.glasses.canServe = false
        assertFalse(
            "a question shown on the glasses with no keys mapped to it cannot be settled there",
            displayWithoutKeys.presenter.available(),
        )
    }

    @Test
    fun `the panel is requested before the first question and only when the glasses keys can serve`() {
        val withGlasses = rig()
        withGlasses.presenter.present("c1", "卸载 App", 10_000L)
        assertEquals("a presentable question must open the hidden panel first", 1, withGlasses.panel.opens)

        val withoutGlasses = rig()
        withoutGlasses.glasses.canServe = false
        withoutGlasses.presenter.present("c1", "卸载 App", 10_000L)
        assertEquals("no glasses surface means no panel to open", 0, withoutGlasses.panel.opens)
    }

    // ------------------------------- 0.3 Phase 4B (B5): only available channels, in priority order

    @Test
    fun `present only announces on surfaces that report themselves available`() {
        // Spec §5.4/D191: an unavailable channel must not be claimed. The glasses keys are gone and the phone page is
        // not there, so the glasses display is the only surface left - and the question must go to exactly that one.
        val rig = rig()
        rig.ui.canServe = false
        rig.glasses.canServe = false

        rig.presenter.present("c1", "卸载 App", 10_000L)

        assertEquals("the one available surface carries the question", 1, rig.summary.shown.size)
        assertTrue("an unavailable phone page is not asked to show anything", rig.ui.shown.isEmpty())
        assertTrue("no keys are armed on a glasses surface that cannot serve", rig.glasses.states.isEmpty())
        assertEquals("no panel is opened for a surface that cannot serve", 0, rig.panel.opens)
        assertEquals("the question still exists (the plugin's gate owns the timeout)", 1, rig.presenter.pendingCount)
    }

    @Test
    fun `a glasses channel is only serving when the display accepted the question and the keys were armed`() {
        // The composition's fallback ladder reads this: "the glasses already have it" may only be true when the SHOW
        // really left the phone. A refused SHOW must not suppress the notification / Edith fallback.
        val rig = rig()
        rig.presenter.present("c1", "卸载 App", 10_000L)
        assertTrue("display accepted + keys armed: the glasses carry the question",
            rig.presenter.isServingOnGlasses("c1"))

        val refusedShow = rig()
        refusedShow.summary.accepts = false
        refusedShow.presenter.present("c1", "卸载 App", 10_000L)
        assertFalse("a SHOW the glasses refused is not a serving glasses channel",
            refusedShow.presenter.isServingOnGlasses("c1"))
        assertTrue("and the keys must not be armed behind a display that never took the text",
            refusedShow.glasses.states.isEmpty())

        val noKeys = rig()
        noKeys.glasses.canServe = false
        noKeys.presenter.present("c1", "卸载 App", 10_000L)
        assertFalse("a display without the keys cannot settle the question", noKeys.presenter.isServingOnGlasses("c1"))
    }

    @Test
    fun `the channel ladder is glasses then notification then the Edith dialog`() {
        // D191's order. Voice is absent on purpose: §5.4 says a spoken phrase only settles a confirmation **already
        // presented**, so voice can never be the surface that makes the question visible.
        assertEquals(
            ConfirmationChannelKind.GLASSES,
            confirmationPresentationChannel(glassesServing = true, notificationAvailable = true, dialogAvailable = true),
        )
        assertEquals(
            ConfirmationChannelKind.NOTIFICATION,
            confirmationPresentationChannel(glassesServing = false, notificationAvailable = true, dialogAvailable = true),
        )
        assertEquals(
            ConfirmationChannelKind.DIALOG,
            confirmationPresentationChannel(glassesServing = false, notificationAvailable = false, dialogAvailable = true),
        )
        assertNull(
            "with no surface at all there is no channel, and the loop must refuse rather than wait",
            confirmationPresentationChannel(glassesServing = false, notificationAvailable = false, dialogAvailable = false),
        )
    }

    @Test
    fun `the phone can present only through a visible page, a raisable page or a postable shade`() {
        assertTrue("the page on screen is a surface",
            ConfirmationPhoneSurface.available(pageVisible = true, canRaisePage = false, notificationsAvailable = false))
        assertTrue("a page that can be raised over another app is a surface",
            ConfirmationPhoneSurface.available(pageVisible = false, canRaisePage = true, notificationsAvailable = false))
        assertTrue("the shade reaches a wearer who is not looking at the page",
            ConfirmationPhoneSurface.available(pageVisible = false, canRaisePage = false, notificationsAvailable = true))
        assertFalse(
            "background, no overlay and notifications off is no phone surface at all - the B4 aggregate said true here",
            ConfirmationPhoneSurface.available(
                pageVisible = false, canRaisePage = false, notificationsAvailable = false,
            ),
        )
    }
}
