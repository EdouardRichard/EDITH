package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesPanelController
import com.sheen.rokid.glasses.app.OrbRequest
import com.sheen.rokid.glasses.app.PanelModel
import com.sheen.rokid.glasses.app.PanelPort
import com.sheen.rokid.glasses.app.orbRequestOf
import com.sheen.rokid.output.BackAction
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialConversation
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
 * 0.24 S3 Task 13: the official panel decisions that used to be inferred from the legacy `BUSY` level.
 *
 * Task 12 makes the phone publish official status levels (`STATE`/`IDLE`); this test drives those through the production
 * controller and pins the four visible consequences the official source decides in one place:
 *
 * - ENTER pins **once** on `ACTION_UP` and never toggles; the `PIN` command is sent at most once.
 * - BACK is the official three-way matrix: unpinned exits, pinned while status 3 interrupts, pinned otherwise exits.
 * - The tip says "双击打断" only for pinned + status 3; every other combination says "双击退出".
 * - `Hi, 我在听` is a first-listening-turn signal; the automatic next turn (status 5 to 1) only gets the "…" row.
 *
 * The controller is Android-free, so all of this runs in a JVM test with a fake port. The legacy `BUSY`-only world is
 * still exercised for the old-phone degradation: it must keep the old rows but never invent the official interrupt
 * copy without a status.
 */
class OfficialConversationPanelTest {

    private companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
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
        var shows = 0
        var hides = 0
        var panelsShown = 0
        var uiClosed = 0

        override fun show(route: AssistantRoute) { shows++ }
        override fun hide() { hides++ }
        override fun render(model: PanelModel) { models += model }
        override fun wakeScreen() = Unit
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

        fun accept(command: HintCommand): Boolean = controller.accept(HintWireCodec.encode(command))

        fun hello(sessionId: String = "s-1", generation: Long = 1): Boolean =
            accept(HintCommand.Hello(sessionId, generation))

        fun state(
            status: OfficialStatus,
            sequence: Long,
            route: AssistantRoute = AssistantRoute.WAKE,
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = stateWire(status.wire, sequence, route, sessionId, generation)

        fun stateWire(
            status: Int,
            sequence: Long,
            route: AssistantRoute = AssistantRoute.WAKE,
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(
            HintCommand.State(sessionId, generation, route, turnId = "turn-$sequence", status = status,
                sequence = sequence),
        )

        fun idle(
            sequence: Long,
            route: AssistantRoute = AssistantRoute.WAKE,
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(HintCommand.Idle(sessionId, generation, route, sequence))

        fun busy(route: AssistantRoute = AssistantRoute.WAKE, busy: Boolean = true): Boolean =
            accept(HintCommand.Busy("s-1", 1, route, busy = busy))

        fun pin(): Boolean {
            controller.onKey(KEYCODE_ENTER, ACTION_DOWN)
            return controller.onKey(KEYCODE_ENTER, ACTION_UP)
        }

        fun back(): Boolean = controller.onKey(KEYCODE_BACK, ACTION_UP)

        fun lastModel(): PanelModel = port.lastModel()
    }

    // ---------------------------------------------------------------- pin: one-way, ENTER on UP

    @Test
    fun enterIsConsumedOnTheDownButOnlyPinsOnTheUp() {
        val harness = Harness()
        harness.hello()

        assertTrue("the panel consumes ENTER's down so no other handler sees it",
            harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertFalse("the official hold happens in onKeyUp, not onKeyDown", harness.lastModel().pinned)
        assertTrue(harness.sent.none { it is HintCommand.Pin })

        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertTrue(harness.lastModel().pinned)
        val pin = harness.sent.filterIsInstance<HintCommand.Pin>().single()
        assertEquals("s-1", pin.sessionId)
        assertEquals(1L, pin.generation)
        assertEquals("the pin names the conversation the wearer is looking at", AssistantRoute.WAKE, pin.route)
    }

    @Test
    fun aSecondEnterStaysPinnedAndDoesNotSendASecondPin() {
        val harness = Harness()
        harness.hello()
        harness.pin()
        assertEquals(1, harness.sent.count { it is HintCommand.Pin })

        harness.pin()

        assertTrue("pin is one-way: a second click is not an unpin", harness.lastModel().pinned)
        assertEquals("PIN is set-only on the wire, so it must not be repeated", 1,
            harness.sent.count { it is HintCommand.Pin })
    }

    @Test
    fun enterPinsLocallyEvenBeforeAnIdentityExists() {
        val harness = Harness()

        harness.pin()

        assertTrue("the wearer's reading position does not depend on the link", harness.lastModel().pinned)
        assertTrue("there is nobody to name yet", harness.sent.none { it is HintCommand.Pin })
    }

    @Test
    fun aWindowResetClearsTheOneWayPin() {
        val harness = Harness()
        harness.hello()
        harness.pin()
        assertTrue(harness.lastModel().pinned)

        harness.controller.resetForClose()

        assertFalse(harness.lastModel().pinned)
    }

    // ---------------------------------------------------------------- BACK: the official three-state matrix

    @Test
    fun backWhileUnpinnedAlwaysExitsEvenWhileAnswering() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        assertEquals(BackAction.EXIT,
            OfficialConversation.backAction(OfficialStatus.ANSWERING, pinned = false))

        assertTrue(harness.back())

        assertEquals("an unpinned BACK always exits, even while answering", 1, harness.port.hides)
        assertTrue("there is nothing to interrupt", harness.sent.none { it is HintCommand.Stop })
    }

    @Test
    fun backWhilePinnedAndAnsweringStopsAndKeepsThePanel() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        harness.pin()
        harness.sent.clear()

        assertTrue(harness.back())

        val stop = harness.sent.filterIsInstance<HintCommand.Stop>().single()
        assertEquals("s-1", stop.sessionId)
        assertEquals(1L, stop.generation)
        assertEquals("a sent STOP keeps the panel for the phone's IDLE", 0, harness.port.hides)
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=stop sent=true") })
    }

    @Test
    fun backWhilePinnedButNotAnsweringExits() {
        OfficialStatus.entries.filter { it != OfficialStatus.ANSWERING }.forEach { status ->
            val harness = Harness()
            harness.hello()
            harness.state(status, sequence = 1)
            harness.pin()
            harness.sent.clear()

            assertTrue("$status", harness.back())

            assertEquals("$status: pinned BACK only interrupts in answering", 1, harness.port.hides)
            assertTrue("$status", harness.sent.none { it is HintCommand.Stop })
        }
    }

    @Test
    fun backAfterThePinnedIdleExitsTheRetainedPanel() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.LISTENING, sequence = 1)
        harness.pin()
        harness.idle(sequence = 2)
        assertTrue("the pinned idle keeps the pin", harness.lastModel().pinned)
        assertTrue(harness.lastModel().idle)

        harness.back()

        assertEquals("IDLE is not status 0; BACK from it is the exit branch", 1, harness.port.hides)
    }

    @Test
    fun aStopThatCouldNotBeSentExitsInsteadOfTrappingTheWearer() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        harness.pin()
        harness.sendResult = false

        harness.back()

        assertEquals("a STOP that did not go out is not an interrupt", 1, harness.port.hides)
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=exit reason=stop_unsent") })
    }

    @Test
    fun backIsConsumedOnTheDownButOnlyActsOnTheUp() {
        val harness = Harness()
        harness.hello()

        assertTrue("the down is consumed so the framework does not leave the window",
            harness.controller.onKey(KEYCODE_BACK, ACTION_DOWN))
        assertEquals("but BACK does not exit on the down", 0, harness.port.hides)

        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))
        assertEquals(1, harness.port.hides)
    }

    // ---------------------------------------------------------------- tips: exactly pinned + answering

    @Test
    fun onlyPinnedAnsweringSaysInterrupt() {
        OfficialStatus.entries.forEach { status ->
            listOf(false, true).forEach { pinned ->
                val harness = Harness()
                harness.hello()
                harness.state(status, sequence = 1)
                if (pinned) harness.pin()

                val expected = if (pinned && status == OfficialStatus.ANSWERING) {
                    OfficialConversation.TIPS_STOP
                } else {
                    OfficialConversation.TIPS_EXIT
                }
                assertEquals("$status pinned=$pinned", expected, harness.lastModel().tips)
                assertEquals("the official copy is one source", OfficialConversation.tipsText(status, pinned),
                    harness.lastModel().tips)
            }
        }
        assertEquals("滑动翻页 / 双击退出", OfficialConversation.TIPS_EXIT)
        assertEquals("滑动翻页 / 双击打断", OfficialConversation.TIPS_STOP)
    }

    @Test
    fun legacyBusyKeepsTheOldRowsAndUsesThePinAsTheOnlyInterruptGate() {
        // The spec keeps the new glasses able to display an old phone's BUSY level (no STATE/IDLE): it degrades to the
        // old rows. It must not say "双击打断" while unpinned; once pinned, legacy busy is the closest evidence of
        // "answering", so that is where the official copy can appear.
        val harness = Harness()
        harness.hello()
        harness.busy(busy = true)

        val model = harness.lastModel()
        assertTrue("the old listening row still comes from the BUSY level", model.listeningVisible)
        assertTrue("and the old Hi text with it", model.hiVisible)
        assertNull("no official status has arrived", model.status)
        assertEquals(OfficialConversation.TIPS_EXIT, model.tips)

        harness.pin()
        assertEquals("legacy busy is the closest answer state available", OfficialConversation.TIPS_STOP,
            harness.lastModel().tips)
    }

    // ---------------------------------------------------------------- Hi and the "…" row

    @Test
    fun hiAppearsOnTheFirstListeningTurnAndNotOnTheAutomaticNextTurn() {
        val harness = Harness()
        harness.hello()

        harness.state(OfficialStatus.LISTENING, sequence = 1)
        assertTrue("first open: the wearer is greeted", harness.lastModel().hiVisible)
        assertTrue(harness.lastModel().listeningVisible)

        harness.state(OfficialStatus.ASR, sequence = 2)
        assertFalse("status 2 hides Hi", harness.lastModel().hiVisible)
        assertFalse("and the \"…\" row", harness.lastModel().listeningVisible)

        harness.state(OfficialStatus.ANSWERING, sequence = 3)
        harness.state(OfficialStatus.AUDIO_FINISHED, sequence = 4)

        harness.state(OfficialStatus.LISTENING, sequence = 5)
        assertTrue("the automatic next turn listens", harness.lastModel().listeningVisible)
        assertFalse("but \"showListenerUI\" never restores Hi", harness.lastModel().hiVisible)
    }

    @Test
    fun statusZeroAndOneKeepHiForTheFirstTurnButStatusFiveToZeroWouldNotReopenIt() {
        val first = Harness()
        first.hello()
        first.state(OfficialStatus.EMPTY, sequence = 1)
        assertTrue(first.lastModel().hiVisible)
        first.state(OfficialStatus.LISTENING, sequence = 2)
        assertTrue("the same first listening turn keeps the greeting from status 0", first.lastModel().hiVisible)

        val later = Harness()
        later.hello()
        later.state(OfficialStatus.LISTENING, sequence = 1)
        later.state(OfficialStatus.AUDIO_FINISHED, sequence = 2)
        later.state(OfficialStatus.EMPTY, sequence = 3)
        assertFalse("showNoneView late in the window is not a new first open", later.lastModel().hiVisible)
    }

    @Test
    fun theAsrLoadingRowIsVisibleOnlyForStatusZeroAndOne() {
        OfficialStatus.entries.forEach { status ->
            val harness = Harness()
            harness.hello()
            harness.state(status, sequence = 1)
            val expected = status == OfficialStatus.EMPTY || status == OfficialStatus.LISTENING
            assertEquals("$status", expected, harness.lastModel().listeningVisible)
        }
    }

    @Test
    fun idleHidesHiAndTheListeningRowAndDoesNotResurrectHi() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.LISTENING, sequence = 1)
        harness.idle(sequence = 2)

        val idle = harness.lastModel()
        assertTrue(idle.idle)
        assertNull("IDLE is not status 0", idle.status)
        assertFalse(idle.hiVisible)
        assertFalse(idle.listeningVisible)

        harness.state(OfficialStatus.LISTENING, sequence = 3)
        assertTrue(harness.lastModel().listeningVisible)
        assertFalse("a post-idle listening row must not say Hi again", harness.lastModel().hiVisible)
    }

    @Test
    fun aClosedWindowShowsHiAgainOnItsFirstListeningTurn() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.LISTENING, sequence = 1)
        harness.controller.resetForClose()

        harness.state(OfficialStatus.LISTENING, sequence = 2)

        assertTrue("a new window is a new first open", harness.lastModel().hiVisible)
    }

    // ---------------------------------------------------------------- state levels: route, order, status 6

    @Test
    fun aStatusForAnotherRouteDoesNotMoveTheView() {
        val harness = Harness()
        harness.hello()

        harness.state(OfficialStatus.ANSWERING, sequence = 1, route = AssistantRoute.PROACTIVE)

        assertEquals("only SHOW brings a route to the front", AssistantRoute.WAKE, harness.lastModel().route)
        assertNull("the wake conversation has no status yet", harness.lastModel().status)

        harness.state(OfficialStatus.LISTENING, sequence = 1)
        assertEquals(OfficialStatus.LISTENING, harness.lastModel().status)
    }

    @Test
    fun aStaleStatusSequenceIsDropped() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ANSWERING, sequence = 2)

        harness.state(OfficialStatus.LISTENING, sequence = 1)

        assertEquals("a late status must not overwrite a newer one", OfficialStatus.ANSWERING,
            harness.lastModel().status)
        assertTrue(harness.diagnostics.any { it.contains("state refused: sequence") })
    }

    @Test
    fun theOutOfScopeJsuiStatusSixIsIgnoredWithoutChangingTheLevel() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.LISTENING, sequence = 1)

        harness.stateWire(status = 6, sequence = 2)

        assertEquals("status 6 is not an OfficialStatus in this build", OfficialStatus.LISTENING,
            harness.lastModel().status)
        assertTrue(harness.diagnostics.any { it.contains("state status=6 ignored") })
    }

    // ---------------------------------------------------------------- key layout: only the panel's own keys

    @Test
    fun theDirectionKeysAreLeftToTheHost() {
        val harness = Harness()
        harness.hello()

        listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT).forEach { code ->
            assertFalse("$code down", harness.controller.onKey(code, ACTION_DOWN))
            assertFalse("$code up", harness.controller.onKey(code, ACTION_UP))
        }
    }

    // ------------------------------------------------ no-work idle vs the official showIdleStatus command (Task 18 I1)

    @Test
    fun aFreshEmptyPanelIsNoWorkIdleAndDoesNotAskForTheFinishTail() {
        val harness = Harness()
        harness.hello()

        val fresh = harness.lastModel()
        assertTrue("no work is still the idle model", fresh.idle)
        assertFalse("no showIdleStatus command has arrived yet", fresh.officialIdle)
        assertEquals("a fresh panel must stop/hold, not play finish_last",
            OrbRequest.Legacy(OrbState.IDLE), orbRequestOf(fresh))
    }

    @Test
    fun onlyTheOfficialIdleCommandAsksForTheFinishTail() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.LISTENING, sequence = 1)

        harness.idle(sequence = 2)

        val idle = harness.lastModel()
        assertTrue("IDLE is the official showIdleStatus state", idle.idle)
        assertTrue("the explicit official idle is distinguishable from no-work idle", idle.officialIdle)
        assertNull("IDLE is not status 0", idle.status)
        assertEquals("only showIdleStatus asks the orb for finish_last",
            OrbRequest.OfficialIdle, orbRequestOf(idle))
    }

    @Test
    fun aLaterStateClearsTheOfficialIdleMarkAndNamesItsSegment() {
        val harness = Harness()
        harness.hello()
        harness.idle(sequence = 1)
        assertTrue(harness.lastModel().officialIdle)

        harness.state(OfficialStatus.ANSWERING, sequence = 2)

        val answering = harness.lastModel()
        assertFalse("a later STATE leaves the official idle", answering.officialIdle)
        assertEquals("an official status asks for its exact PAG segment",
            OrbRequest.Status(OfficialStatus.ANSWERING), orbRequestOf(answering))
    }
}
