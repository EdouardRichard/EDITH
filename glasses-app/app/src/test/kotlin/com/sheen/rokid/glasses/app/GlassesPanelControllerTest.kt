package com.sheen.rokid.glasses.app

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.display.BusyState
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24 S2 Task 8: the panel's state, cache and decisions, tested without Android.
 *
 * Task 7 moved the view tree out of `GlassesActivity`; this test pins the half that used to live beside it in the
 * Activity: the conversation cache, the selected route, the `SAID` ordering gate, the busy level, the BACK and pin
 * decisions, and the model the view renders. Every assertion reads the production controller through a fake
 * [PanelPort], so a decision that drifts fails here instead of needing the glasses on a desk.
 *
 * The official first-open Hi rule is **not** wired in this task; `hiVisible` carries the existing listening signal
 * unchanged and this test pins that transitional value (Task 13/17 replaces it with
 * `OfficialConversation.hiVisible(status, firstOpen)`).
 */
class GlassesPanelControllerTest {

    // Android's KeyEvent values, spelled here so the controller stays Android-free. Same numbers the Activity passes.
    private companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_BACK = 4
        const val KEYCODE_ENTER = 66
    }

    private class FakeClock(var nanos: Long = 1_000_000_000L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        val deadlines = mutableListOf<Long>()
        val tasks = mutableListOf<() -> Unit>()

        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            deadlines += deadlineNanos
            tasks += task
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    private class FakePort : PanelPort {
        val models = mutableListOf<PanelModel>()
        var shows = 0
        var hides = 0
        var wakes = 0
        var panelsShown = 0
        var uiClosed = 0

        override fun show(route: AssistantRoute) { shows++ }
        override fun hide() { hides++ }
        override fun render(model: PanelModel) { models += model }
        override fun wakeScreen() { wakes++ }
        override fun sendPanelShown() { panelsShown++ }
        override fun sendUiClosed() { uiClosed++ }

        fun lastModel(): PanelModel = models.lastOrNull() ?: error("the controller rendered no model")
    }

    private class Harness {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val port = FakePort()
        val sent = mutableListOf<HintCommand>()
        val diagnostics = mutableListOf<String>()
        var sendResult = true

        val controller = GlassesPanelController(
            clock = clock,
            scheduler = scheduler,
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

        fun sync(syncId: String = "sync-1"): Boolean = accept(
            HintCommand.Sync(syncId, offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                validUntilGlassNanos = clock.nanos + 30_000_000_000L),
        )

        fun show(
            sequence: Long = 1,
            hintId: String = "h-1",
            text: String = "答案在这里",
            route: AssistantRoute = AssistantRoute.WAKE,
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(
            HintCommand.Show(sessionId, generation, sequence = sequence, hintId = hintId, revision = 1,
                syncId = "sync-1", phoneSentNanos = clock.nanos, remainingTtlMillis = 5_000,
                text = text, route = route),
        )

        fun said(
            route: AssistantRoute = AssistantRoute.WAKE,
            sequence: Long = 1,
            entryId: String = "said-1",
            text: String = "现在几点了",
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(
            HintCommand.Said(sessionId, generation, sequence = sequence, route = route,
                entryId = entryId, text = text),
        )

        fun busy(route: AssistantRoute = AssistantRoute.WAKE, busy: Boolean = true): Boolean =
            accept(HintCommand.Busy("s-1", 1, route, busy = busy))
    }

    // ---------------------------------------------------------------- Hello and identity

    @Test
    fun helloEstablishesTheSessionAndAnEmptyIdleModel() {
        val harness = Harness()

        assertTrue("a HELLO is always understood", harness.hello())
        assertEquals("the BACK identity comes from the accepted HELLO", "s-1" to 1L,
            harness.controller.currentIdentity())

        val model = harness.port.lastModel()
        assertEquals(AssistantRoute.WAKE, model.route)
        assertTrue("a new session starts with no words", model.entries.isEmpty())
        assertNull(model.liveEntryId)
        assertNull("Task 8 has no official status yet", model.status)
        assertTrue("no busy level was reported, so the panel is idle", model.idle)
        assertFalse(model.pinned)
        assertFalse(model.listeningVisible)
        assertFalse(model.hiVisible)
        assertFalse(model.thinkingVisible)
        assertNull(model.toolLabel)
        assertEquals(GlassesPanelSpec.TIPS_EXIT, model.tips)
    }

    @Test
    fun currentIdentityIsNullBeforeHelloAndAfterANewSessionAdoptsIt() {
        val harness = Harness()
        assertNull(harness.controller.currentIdentity())

        harness.hello("s-1", 1)
        harness.hello("s-2", 5)
        assertEquals("a HELLO for another session is adopted", "s-2" to 5L,
            harness.controller.currentIdentity())
    }

    @Test
    fun aRepeatedHelloForTheSameSessionKeepsTheConversation() {
        val harness = Harness()
        harness.hello()
        harness.said()

        harness.hello()

        assertEquals("the phone repeats its opening HELLO; it must not wipe the words already shown",
            "现在几点了", harness.controller.conversationText(AssistantRoute.WAKE))
    }

    @Test
    fun aHelloForANewSessionClearsTheConversationTheRouteAndThePin() {
        val harness = Harness()
        harness.hello("s-1", 1)
        harness.said()
        harness.accept(HintCommand.Ping("sync-old", p0 = 10L))
        harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN)
        assertTrue("the pin was set before the session changed", harness.port.lastModel().pinned)

        harness.hello("s-2", 1)

        val model = harness.port.lastModel()
        assertTrue("a new session must not open on the last one's words", model.entries.isEmpty())
        assertEquals("a new window opens on wake", AssistantRoute.WAKE, model.route)
        assertFalse("and is unpinned", model.pinned)
        assertNull(model.liveEntryId)
        assertEquals("", harness.controller.conversationText(AssistantRoute.WAKE))
    }

    // ---------------------------------------------------------------- SAID

    @Test
    fun saidIsFiledInItsOwnRouteAndDoesNotMoveTheView() {
        val harness = Harness()
        harness.hello()
        val rendersBefore = harness.port.models.size

        assertTrue(harness.said(route = AssistantRoute.PROACTIVE, text = "我待会儿要开会"))

        assertEquals("the utterance must be in its own conversation", "我待会儿要开会",
            harness.controller.conversationText(AssistantRoute.PROACTIVE))
        assertEquals("and never in the other one", "", harness.controller.conversationText(AssistantRoute.WAKE))
        assertEquals("an utterance is not something to look at, so the view stays where it was",
            AssistantRoute.WAKE, harness.port.lastModel().route)
        assertEquals("nothing on screen changed, so no render was needed", rendersBefore, harness.port.models.size)
    }

    @Test
    fun saidForTheSelectedRouteReachesTheModelAsAUserRow() {
        val harness = Harness()
        harness.hello()
        val rendersBefore = harness.port.models.size

        assertTrue(harness.said())

        val model = harness.port.lastModel()
        assertEquals("the selected conversation changed, so the panel was rendered once", rendersBefore + 1,
            harness.port.models.size)
        val entry = model.entries.single()
        assertEquals(ConversationRole.USER, entry.role)
        assertEquals("现在几点了", entry.text)
        assertNull("an utterance is not the live answer", model.liveEntryId)
    }

    @Test
    fun saidForAnotherSessionNeverEntersTheConversation() {
        val harness = Harness()
        harness.hello("s-1", 1)

        harness.said(sessionId = "s-other")

        assertEquals("", harness.controller.conversationText(AssistantRoute.WAKE))
        assertTrue(harness.diagnostics.any { it.contains("said refused: session or generation mismatch") })
    }

    @Test
    fun anOlderSaidSequenceIsRefusedByTheGate() {
        val harness = Harness()
        harness.hello()

        harness.said(sequence = 2, text = "后来的话")
        harness.said(sequence = 1, text = "迟到的话")

        assertEquals("the custom-command channel promises no ordering, so a late utterance is dropped",
            "后来的话", harness.controller.conversationText(AssistantRoute.WAKE))
        assertTrue(harness.diagnostics.any { it.contains("said refused: sequence") })
    }

    // ---------------------------------------------------------------- SHOW and the live row

    @Test
    fun aShowBecomesLiveAndBringsItsOwnRouteForward() {
        val harness = Harness()
        harness.hello()
        harness.sync()

        assertTrue(harness.show(route = AssistantRoute.PROACTIVE, hintId = "h-p", text = "主动提醒一句"))

        val model = harness.port.lastModel()
        assertEquals("a SHOW is what brings a route to the front", AssistantRoute.PROACTIVE, model.route)
        assertEquals("h-p", model.liveEntryId)
        val entry = model.entries.single()
        assertEquals(ConversationRole.ASSISTANT, entry.role)
        assertEquals("主动提醒一句", entry.text)
        val ack = harness.sent.filterIsInstance<HintCommand.Ack>().single()
        assertEquals("the ACK follows the committed row", "h-p", ack.hintId)
    }

    @Test
    fun aHintExpiryRetiresTheLiveRowButKeepsTheConversation() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(text = "答案在这里")
        assertEquals("h-1", harness.port.lastModel().liveEntryId)

        harness.clock.nanos += 6_000_000_000L
        harness.scheduler.tasks.forEach { it() }

        val model = harness.port.lastModel()
        assertNull("the deadline must retire the live row even if the phone never sends CLEAR",
            model.liveEntryId)
        assertEquals("but the conversation the wearer reads back from must survive it",
            "答案在这里", harness.controller.conversationText(AssistantRoute.WAKE))
    }

    // ---------------------------------------------------------------- BUSY / the listening-Hi signal

    @Test
    fun aBusyWakeShowsTheListeningHiSignalAndTheInterruptTip() {
        val harness = Harness()
        harness.hello()

        harness.busy(busy = true)

        val model = harness.port.lastModel()
        assertFalse(model.idle)
        assertTrue("a busy wake before the wearer speaks is listening", model.listeningVisible)
        assertTrue("Task 8 keeps the current Hi behaviour; the view reads this field", model.hiVisible)
        assertFalse(model.thinkingVisible)
        assertEquals(GlassesPanelSpec.TIPS_STOP, model.tips)
    }

    @Test
    fun onceTheWearerHasSpokenThinkingReplacesListeningAndHi() {
        val harness = Harness()
        harness.hello()
        harness.busy(busy = true)

        harness.said()

        val model = harness.port.lastModel()
        assertFalse("the Hi/listening row is over once the wearer's line is in", model.listeningVisible)
        assertFalse("and the Hi text stays transitional with it", model.hiVisible)
        assertTrue("the thinking row takes over", model.thinkingVisible)
        assertEquals("the assistant is still working, so the tip still says interrupt",
            GlassesPanelSpec.TIPS_STOP, model.tips)
    }

    @Test
    fun aBusyLevelArmsOneLocalDeadlineOnlyWhileThePanelIsRunning() {
        val harness = Harness()
        harness.hello()
        harness.busy(busy = true)
        assertEquals("a command that arrives before onStart must not arm the bound yet", 0,
            harness.scheduler.tasks.size)

        harness.controller.onStart()
        assertEquals("onStart applies the level and arms the bound", 1, harness.scheduler.tasks.size)

        harness.busy(busy = true)
        assertEquals("re-stating the same level must not keep pushing the deadline back", 1,
            harness.scheduler.tasks.size)
    }

    @Test
    fun theLocalBusyBoundRecomputesThePanelWhenItFires() {
        val harness = Harness()
        harness.hello()
        harness.controller.onStart()
        harness.busy(busy = true)
        assertFalse(harness.port.lastModel().idle)

        harness.clock.nanos += BusyState.MAX_MILLIS * 1_000_000L + 1
        harness.scheduler.tasks.forEach { it() }

        val model = harness.port.lastModel()
        assertTrue("a busy level that is never cleared must expire by itself", model.idle)
        assertEquals(GlassesPanelSpec.TIPS_EXIT, model.tips)
    }

    // ---------------------------------------------------------------- keys: ENTER pin / BACK

    @Test
    fun enterTogglesThePinDecision() {
        val harness = Harness()
        harness.hello()

        assertTrue("ENTER is consumed on the down action", harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertTrue(harness.port.lastModel().pinned)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertFalse(harness.port.lastModel().pinned)
        assertFalse("a key the controller does not own is not consumed", harness.controller.onKey(KEYCODE_BACK,
            ACTION_DOWN))
    }

    @Test
    fun backWhileBusySendsStopAndLeavesThePanelOpen() {
        val harness = Harness()
        harness.hello()
        harness.busy(busy = true)
        harness.sent.clear()

        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))

        val stop = harness.sent.filterIsInstance<HintCommand.Stop>().single()
        assertEquals("s-1", stop.sessionId)
        assertEquals(1L, stop.generation)
        assertEquals("a STOP that was sent keeps the panel for the phone's BUSY=false", 0, harness.port.hides)
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=stop sent=true") })
    }

    @Test
    fun backWhenTheStopCannotBeSentHidesThePanelInsteadOfTrappingTheWearer() {
        val harness = Harness()
        harness.hello()
        harness.busy(busy = true)
        harness.sendResult = false

        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))

        assertEquals("a STOP that did not go out is not an interrupt", 1, harness.port.hides)
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=exit reason=stop_unsent") })
    }

    @Test
    fun backWhenIdleHidesThePanel() {
        val harness = Harness()
        harness.hello()
        harness.sent.clear()

        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))

        assertEquals(1, harness.port.hides)
        assertTrue("nothing to interrupt while idle", harness.sent.none { it is HintCommand.Stop })
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=exit busy=false identity=true") })
    }

    @Test
    fun backWithNoSessionIdentityExitsRatherThanSendingAStop() {
        val harness = Harness()

        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))

        assertEquals(1, harness.port.hides)
        assertTrue(harness.sent.none { it is HintCommand.Stop })
    }

    // ---------------------------------------------------------------- moved handshake and decode seams

    @Test
    fun pingIsAnsweredWithPongAndLeavesTheViewAlone() {
        val harness = Harness()
        harness.hello()
        val rendersBefore = harness.port.models.size

        assertTrue(harness.controller.accept(HintWireCodec.encode(HintCommand.Ping("sync-1", p0 = 42L))))

        val pong = harness.sent.filterIsInstance<HintCommand.Pong>().single()
        assertEquals("sync-1", pong.syncId)
        assertEquals(42L, pong.p0)
        assertEquals("a PING is a clock exchange, not a render", rendersBefore, harness.port.models.size)
    }

    @Test
    fun anUndecodableMessageIsReportedAndNotUnderstood() {
        val harness = Harness()

        assertFalse(harness.controller.accept("not json at all"))
        assertTrue(harness.diagnostics.any { it.contains("inbound undecodable chars=") })
        assertTrue(harness.port.models.isEmpty())
    }

    @Test
    fun disconnectDropsTheIdentityAndRetiresAVisibleHint() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show()
        assertEquals("h-1", harness.port.lastModel().liveEntryId)

        harness.controller.disconnect()

        assertNull("a destroyed screen must not keep the session it could accept commands for",
            harness.controller.currentIdentity())
        val model = harness.port.lastModel()
        assertNull("the visible hint is retired so no text is left behind", model.liveEntryId)
        assertEquals("the conversation itself is not erased by the teardown", "答案在这里",
            harness.controller.conversationText(AssistantRoute.WAKE))
    }
}
