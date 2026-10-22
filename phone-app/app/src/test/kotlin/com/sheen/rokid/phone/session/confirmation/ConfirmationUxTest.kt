package com.sheen.rokid.phone.session.confirmation

import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R51: one question, two screens, exactly one settlement.
 *
 * The phone card and the glasses keys are two input channels for the same question. This test pins the properties that
 * make that safe:
 * - the glasses are told when the question appears and when it goes away, so their keys are never mapped to a stale
 *   question;
 * - `answerFromGlasses` is the *same* path as the phone's tap, so which channel wins the race never changes the
 *   `ConfirmationResult` the plugin receives;
 * - a timeout takes the question down on both channels and delivers **nothing** - the plugin's gate owns "silence is a
 *   denial", and the presenter must not turn its own timer into an approval;
 * - the window is the caller's (the plugin's 120 s), not a second number invented here.
 */
class ConfirmationUxTest {

    private class FakeSummary(private val events: MutableList<String>? = null) : SummaryDisplayer {
        /** One `SHOW` the presenter asked for: the stable hint id, the text and the TTL. */
        data class Shown(val hintId: String, val text: String, val ttlMillis: Long)

        val shown = mutableListOf<Shown>()
        override fun show(hintId: String, text: String, ttlMillis: Long): Boolean {
            shown += Shown(hintId, text, ttlMillis)
            events?.add("show")
            return true
        }
    }

    private class FakeUi : ConfirmationUi {
        val shown = mutableListOf<Pair<String, String>>()
        val hidden = mutableListOf<String>()

        /**
         * 0.3 Phase 4B (B7): the rung this fake reports it used. Like the production composition, a `show` that
         * happened reports the surface that took the question; `null` models "no surface".
         */
        var presentedChannel: ConfirmationChannelKind? = ConfirmationChannelKind.DIALOG

        override fun show(requestId: String, summary: String): ConfirmationChannelKind? {
            shown += requestId to summary
            return presentedChannel
        }
        override fun hide(requestId: String) { hidden += requestId }
    }

    private class FakeGlasses(private val events: MutableList<String>? = null) : ConfirmationGlasses {
        val states = mutableListOf<Pair<String, Boolean>>()
        var accepts = true
        var canServe = true
        override fun available(): Boolean = canServe
        override fun show(requestId: String, active: Boolean): Boolean {
            states += requestId to active
            events?.add("state:$active")
            return accepts
        }
    }

    private class FakePanel(private val events: MutableList<String>? = null) : ConfirmationPanel {
        var opens = 0
        var accepts = true
        override fun open(): Boolean {
            opens++
            events?.add("panel")
            return accepts
        }
    }

    private class Rig {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val events = mutableListOf<String>()
        val summary = FakeSummary(events)
        val ui = FakeUi()
        val glasses = FakeGlasses(events)
        val panel = FakePanel(events)
        val delivered = mutableListOf<ConfirmationResult>()
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

    @Test
    fun `present puts the question on the phone and activates the glasses channel`() {
        val rig = Rig()

        rig.presenter.present("c1", "卸载 App：edith.phone.a11yprobe", 120_000L)

        assertEquals(1, rig.ui.shown.size)
        assertEquals(1, rig.summary.shown.size)
        assertEquals("present must tell the glasses which question the keys now answer",
            listOf("c1" to true), rig.glasses.states)
        assertEquals(1, rig.presenter.pendingCount)
        assertTrue("presenting is not answering", rig.delivered.isEmpty())
    }

    @Test
    fun `answering from the glasses settles once and delivers the same result`() {
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 120_000L)

        assertTrue(rig.presenter.answerFromGlasses("c1", approved = true))

        val result = rig.delivered.single()
        assertEquals("c1", result.requestId)
        assertTrue(result.approved)
        assertEquals(SessionId("s"), result.envelope.sessionId)
        assertEquals(Generation(1), result.envelope.generation)
        assertEquals(listOf("c1"), rig.ui.hidden)
        assertEquals("the glasses must be told the question is over",
            listOf("c1" to true, "c1" to false), rig.glasses.states)
        assertEquals(0, rig.presenter.pendingCount)

        assertFalse("a settled question accepts no second answer",
            rig.presenter.answerFromGlasses("c1", approved = false))
        assertFalse("and the phone's tap for the same question loses too",
            rig.presenter.answer("c1", approved = false))
        assertEquals("only one ConfirmationResult may ever be delivered", 1, rig.delivered.size)
        assertEquals("the glasses see exactly one deactivation", 2, rig.glasses.states.size)
    }

    @Test
    fun `a phone tap and a glasses key race and the second one settles nothing`() {
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 120_000L)

        assertTrue(rig.presenter.answer("c1", approved = false))
        assertFalse(rig.presenter.answerFromGlasses("c1", approved = true))

        val result = rig.delivered.single()
        assertEquals("the first decision wins, whichever screen made it", false, result.approved)
        assertEquals(listOf("c1" to true, "c1" to false), rig.glasses.states)
    }

    @Test
    fun `a timeout withdraws the question on both channels and delivers no approval`() {
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 120_000L)

        rig.clock.advance(120_000_000_000L - 1)
        rig.scheduler.runDue()
        assertEquals("the question must survive until the window closes", 1, rig.presenter.pendingCount)
        assertEquals(listOf("c1" to true), rig.glasses.states)

        rig.clock.advance(1)
        rig.scheduler.runDue()

        assertEquals(listOf("c1"), rig.ui.hidden)
        assertEquals("the glasses must leave confirm mode when the window closes",
            listOf("c1" to true, "c1" to false), rig.glasses.states)
        assertTrue("the presenter's timeout is a withdrawal, never an approval", rig.delivered.isEmpty())
        assertEquals(0, rig.presenter.pendingCount)
        assertFalse("a key pressed after the timeout answers nothing",
            rig.presenter.answerFromGlasses("c1", approved = true))
    }

    @Test
    fun `the window is the caller's so the presenter owns no fixed 120 seconds`() {
        // The plugin passes SmartConfirmation.DEFAULT_TIMEOUT_MILLIS (120 s); the presenter must arm exactly what it is
        // given. A short window here would survive a hardcoded 120 s.
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 5_000L)

        rig.clock.advance(5_000_000_000L - 1)
        rig.scheduler.runDue()
        assertEquals(1, rig.presenter.pendingCount)

        rig.clock.advance(1)
        rig.scheduler.runDue()
        assertEquals(0, rig.presenter.pendingCount)
        assertEquals(listOf("c1" to true, "c1" to false), rig.glasses.states)
        assertTrue(rig.delivered.isEmpty())
    }

    @Test
    fun `the phone notification window still matches the plugins one hundred twenty seconds`() {
        assertEquals(120_000L, ConfirmationNotifier.WINDOW_MILLIS)
    }

    @Test
    fun `withdraw all takes every open question off the glasses`() {
        val rig = Rig()
        rig.presenter.present("c1", "动作一", 120_000L)
        rig.presenter.present("c2", "动作二", 120_000L)

        rig.presenter.withdrawAll()

        assertEquals(listOf("c1", "c2"), rig.ui.hidden)
        assertEquals(
            listOf("c1" to true, "c2" to true, "c1" to false, "c2" to false),
            rig.glasses.states,
        )
        assertTrue(rig.delivered.isEmpty())
        assertEquals(0, rig.presenter.pendingCount)
    }

    @Test
    fun `a duplicate present for a live id does not activate the glasses twice`() {
        val rig = Rig()
        rig.presenter.present("c1", "第一个动作", 120_000L)

        rig.presenter.present("c1", "第二个动作", 120_000L)

        assertEquals("the duplicate never displaces the live question", listOf("c1" to "第一个动作"), rig.ui.shown)
        assertEquals("and it must not send a second activation", listOf("c1" to true), rig.glasses.states)
    }

    @Test
    fun `a glasses send failure never stops the phone from asking`() {
        val rig = Rig()
        rig.glasses.accepts = false

        rig.presenter.present("c1", "卸载 App", 120_000L)

        assertEquals("the phone is the fallback screen, not a second failure", 1, rig.ui.shown.size)
        assertTrue("the question is still answerable on the phone", rig.presenter.answer("c1", approved = true))
        assertEquals(1, rig.delivered.size)
    }

    // ------------------------- 0.3 Phase 4B (B4): open the hidden panel first, then announce the question

    @Test
    fun `the panel is opened before the question is shown and the keys are armed`() {
        val rig = Rig()

        rig.presenter.present("c1", "卸载 App：edith.phone.a11yprobe", 120_000L)

        assertEquals(
            "a hidden panel receives nothing, so PANEL_OPEN must come before SHOW/CONFIRM_STATE",
            listOf("panel", "show", "state:true"),
            rig.events,
        )
        assertEquals(1, rig.presenter.pendingCount)
    }

    @Test
    fun `a panel shown later re-announces the live question and does not clear it`() {
        // The panel can be created after `present` already sent its first SHOW/CONFIRM_STATE (a cold appStart, or a
        // hidden window). Its first layout is therefore the moment to announce the same question again - and it must
        // not behave like a settlement: the question stays answerable and the phone card is not raised twice.
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 120_000L)
        rig.events.clear()

        rig.presenter.panelShownArrived()

        assertEquals(
            "the new panel must receive the live question: SHOW and the active CONFIRM_STATE",
            listOf("show", "state:true"),
            rig.events,
        )
        assertEquals("reopening must not clear an unsettled question", 1, rig.presenter.pendingCount)
        assertEquals("the phone already shows the card; a panel layout is not a second question", 1, rig.ui.shown.size)
        assertTrue("the re-announced question is still live", rig.summary.shown.last().ttlMillis > 0L)
        assertTrue("and the keys still answer it", rig.presenter.answerFromGlasses("c1", approved = true))
        assertEquals(1, rig.delivered.size)
    }

    @Test
    fun `a panel shown after the question settled re-announces nothing`() {
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 120_000L)
        assertTrue(rig.presenter.answer("c1", approved = true))
        rig.events.clear()

        rig.presenter.panelShownArrived()

        assertEquals("a settled question must not be put back on the glasses", emptyList<String>(), rig.events)
        assertEquals(1, rig.delivered.size)
    }

    @Test
    fun `a panel shown later re-announces the same hint id so the panel replaces its row`() {
        // B4 carry-forward: the composition minted a fresh hint id per SHOW, so reopening the panel appended a second
        // "确认" row. The id belongs to the *question*: every announcement of it must carry the same one.
        val rig = Rig()
        rig.presenter.present("c1", "卸载 App", 120_000L)
        val first = rig.summary.shown.single().hintId

        rig.presenter.panelShownArrived()

        assertEquals(
            "SHOW + the panel's re-SHOW must name one row, not two",
            listOf(first, first),
            rig.summary.shown.map { it.hintId },
        )
        assertTrue("the hint id is a real identity, not an empty placeholder", first.isNotBlank())
    }

    @Test
    fun `two confirmations get two different stable hint ids`() {
        val rig = Rig()
        rig.presenter.present("c1", "动作一", 120_000L)
        val first = rig.summary.shown.single().hintId
        assertTrue(rig.presenter.answer("c1", approved = false))

        rig.presenter.present("c2", "动作二", 120_000L)
        val second = rig.summary.shown.last().hintId

        assertNotEquals("a new question must not overwrite the previous question's row", first, second)
    }

    @Test
    fun `the panel is not requested when the glasses surface cannot serve`() {
        val rig = Rig()
        rig.glasses.canServe = false

        rig.presenter.present("c1", "卸载 App", 120_000L)

        assertEquals("no glasses surface means nothing to open", 0, rig.panel.opens)
    }
}
