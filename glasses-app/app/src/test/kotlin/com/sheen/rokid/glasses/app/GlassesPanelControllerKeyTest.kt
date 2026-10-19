package com.sheen.rokid.glasses.app

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R51: the ring's two keys while a confirmation question is up.
 *
 * Confirmation is a **modal key state**, not a second panel: ENTER allows, BACK denies, and neither may fall through to
 * the normal pin/STOP/exit decisions. The mode is a pause, not a reset - the wearer's reading pin survives it, and the
 * ordinary ring behaviour comes back the moment the phone says the question is over (`active=false`).
 *
 * The same numbers the Activity passes are spelled here so the controller stays Android-free.
 */
class GlassesPanelControllerKeyTest {

    private companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_ENTER = 66
    }

    private class FakeClock(var nanos: Long = 1_000_000_000L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class FakePort : PanelPort {
        val models = mutableListOf<PanelModel>()
        var hides = 0
        var wakes = 0
        var panelsShown = 0
        var uiClosed = 0

        override fun show(route: AssistantRoute) = Unit
        override fun hide() { hides++ }
        override fun render(model: PanelModel) { models += model }
        override fun wakeScreen() { wakes++ }
        override fun sendPanelShown() { panelsShown++ }
        override fun sendUiClosed() { uiClosed++ }

        fun lastModel(): PanelModel = models.lastOrNull() ?: error("the controller rendered no model")
    }

    private class Harness {
        val clock = FakeClock()
        val port = FakePort()
        val sent = mutableListOf<HintCommand>()
        val diagnostics = mutableListOf<String>()
        var sendResult = true

        val controller = GlassesPanelController(
            clock = clock,
            scheduler = FakeScheduler(),
            port = port,
            send = { command ->
                sent += command
                sendResult
            },
            diagnostics = { message -> diagnostics += message },
        )

        fun hello(sessionId: String = "s-1", generation: Long = 1): Boolean =
            accept(HintCommand.Hello(sessionId, generation))

        fun confirm(requestId: String = "c-1", active: Boolean = true,
            sessionId: String = "s-1", generation: Long = 1): Boolean =
            accept(HintCommand.ConfirmState(sessionId, generation, requestId, active))

        fun state(status: OfficialStatus, sequence: Long): Boolean =
            accept(HintCommand.State("s-1", 1, AssistantRoute.WAKE, turnId = "turn-$sequence",
                status = status.wire, sequence = sequence))

        fun pin(): Boolean {
            controller.onKey(KEYCODE_ENTER, ACTION_DOWN)
            return controller.onKey(KEYCODE_ENTER, ACTION_UP)
        }

        fun accept(command: HintCommand): Boolean = controller.accept(HintWireCodec.encode(command))

        fun decisions(): List<HintCommand.ConfirmDecision> = sent.filterIsInstance<HintCommand.ConfirmDecision>()
        fun pins(): List<HintCommand.Pin> = sent.filterIsInstance<HintCommand.Pin>()
        fun stops(): List<HintCommand.Stop> = sent.filterIsInstance<HintCommand.Stop>()
    }

    // ---------------------------------------------------------------- normal (non-confirmation) semantics

    @Test
    fun `outside a confirmation ENTER still pins and BACK still exits`() {
        val enter = Harness()
        enter.hello()

        assertTrue("ENTER's down is consumed", enter.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertTrue(enter.controller.onKey(KEYCODE_ENTER, ACTION_UP))

        assertTrue("the normal ring still pins", enter.port.lastModel().pinned)
        assertEquals(1, enter.pins().size)

        val back = Harness()
        back.hello()
        assertTrue(back.controller.onKey(KEYCODE_BACK, ACTION_DOWN))
        assertTrue(back.controller.onKey(KEYCODE_BACK, ACTION_UP))
        assertEquals("an idle BACK still exits", 1, back.port.hides)
        assertTrue("a direction key still belongs to the host outside a confirmation",
            !back.controller.onKey(KEYCODE_DPAD_DOWN, ACTION_DOWN))
    }

    // ---------------------------------------------------------------- the confirmation mode

    @Test
    fun `confirm mode consumes the down events and ENTER up allows without pinning`() {
        val harness = Harness()
        harness.hello()
        assertTrue(harness.confirm("c-1", active = true))
        assertEquals("c-1", harness.controller.confirmingRequestId)

        assertTrue("ENTER's down is consumed in confirm mode", harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertFalse("the down is not the decision", harness.port.lastModel().pinned)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))

        val decision = harness.decisions().single()
        assertEquals("s-1", decision.sessionId)
        assertEquals(1L, decision.generation)
        assertEquals("c-1", decision.requestId)
        assertTrue("ENTER is allow", decision.approved)
        assertTrue("confirm mode must not pin", harness.pins().isEmpty())
        assertFalse(harness.port.lastModel().pinned)
        assertNull("the key is consumed with its local state", harness.controller.confirmingRequestId)
    }

    @Test
    fun `confirm mode BACK up denies without sending STOP or closing the panel`() {
        val harness = Harness()
        harness.hello()
        // Make the ordinary BACK branch the interrupt path: pinned + answering would normally send STOP.
        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        harness.pin()
        harness.sent.clear()
        assertTrue(harness.port.lastModel().pinned)
        assertTrue(harness.confirm("c-1", active = true))

        assertTrue("BACK's down is consumed in confirm mode", harness.controller.onKey(KEYCODE_BACK, ACTION_DOWN))
        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))

        val decision = harness.decisions().single()
        assertFalse("BACK is deny", decision.approved)
        assertEquals("c-1", decision.requestId)
        assertTrue("confirm mode must never send the pause/STOP decision", harness.stops().isEmpty())
        assertEquals("and it must not close the panel", 0, harness.port.hides)
        assertTrue("the reading pin is paused, never cleared", harness.port.lastModel().pinned)
        assertNull(harness.controller.confirmingRequestId)
    }

    @Test
    fun `an inactive state restores the ordinary key semantics`() {
        val harness = Harness()
        harness.hello()
        harness.confirm("c-1", active = true)

        assertTrue(harness.confirm("c-1", active = false))

        assertNull(harness.controller.confirmingRequestId)
        assertTrue("ENTER is a pin again", harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertTrue(harness.port.lastModel().pinned)
        assertTrue(harness.pins().isNotEmpty())
        assertTrue("no decision was sent for an inactive question", harness.decisions().isEmpty())

        assertTrue("BACK exits again", harness.controller.onKey(KEYCODE_BACK, ACTION_UP))
        assertEquals(1, harness.port.hides)
    }

    @Test
    fun `a confirm state for another session is refused`() {
        val harness = Harness()
        harness.hello("s-1", 1)

        harness.confirm("c-1", active = true, sessionId = "s-other", generation = 1)

        assertNull(harness.controller.confirmingRequestId)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertTrue("the refused state left the normal pin in place", harness.port.lastModel().pinned)
        assertTrue(harness.decisions().isEmpty())
    }

    @Test
    fun `an inactive state for a different request does not close the live confirmation`() {
        val harness = Harness()
        harness.hello()
        harness.confirm("c-live", active = true)

        harness.confirm("c-old", active = false)

        assertEquals("a late withdrawal for another question cannot disarm this one",
            "c-live", harness.controller.confirmingRequestId)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertEquals("c-live", harness.decisions().single().requestId)
    }

    @Test
    fun `a repeated act of the same confirmation is idempotent`() {
        val harness = Harness()
        harness.hello()
        harness.confirm("c-1", active = true)
        harness.confirm("c-1", active = true)

        assertEquals("c-1", harness.controller.confirmingRequestId)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))

        assertEquals("one question, one decision", 1, harness.decisions().size)
        assertTrue("a repeated level must not leave a second key armed", harness.pins().isEmpty())
    }

    @Test
    fun `a new session clears the confirmation mode`() {
        val harness = Harness()
        harness.hello("s-1", 1)
        harness.confirm("c-1", active = true)

        harness.hello("s-2", 2)

        assertNull("a question from the previous session cannot answer in the new one",
            harness.controller.confirmingRequestId)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertTrue("the normal pin path is back", harness.port.lastModel().pinned)
        assertTrue(harness.decisions().isEmpty())
    }

    @Test
    fun `closing the panel clears the confirmation mode`() {
        val harness = Harness()
        harness.hello()
        harness.confirm("c-1", active = true)

        harness.controller.resetForClose()

        assertNull(harness.controller.confirmingRequestId)
        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))
        assertEquals("the ordinary close path is back", 1, harness.port.hides)
        assertTrue(harness.stops().isEmpty())
    }
}
