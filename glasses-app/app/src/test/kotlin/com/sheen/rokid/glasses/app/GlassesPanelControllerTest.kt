package com.sheen.rokid.glasses.app

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.glasses.display.BusyState
import com.sheen.rokid.glasses.display.ConversationView
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.output.ConversationRole
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
 * 0.24 S2 Task 8: the panel's state, cache and decisions, tested without Android.
 *
 * Task 7 moved the view tree out of `GlassesActivity`; this test pins the half that used to live beside it in the
 * Activity: the conversation cache, the selected route, the `SAID` ordering gate, the busy level, the BACK and pin
 * decisions, and the model the view renders. Every assertion reads the production controller through a fake
 * [PanelPort], so a decision that drifts fails here instead of needing the glasses on a desk.
 *
 * The official status/first-open rules were wired in Task 13; this test still covers the legacy `BUSY`-only world
 * (an old phone with no `STATE`/`IDLE`) as the spec's degraded mode, and the new official branches are pinned in
 * `OfficialConversationPanelTest`.
 */
class GlassesPanelControllerTest {

    // Android's KeyEvent values, spelled here so the controller stays Android-free. Same numbers the Activity passes.
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
            revision: Long = 1,
        ): Boolean = accept(
            HintCommand.Show(sessionId, generation, sequence = sequence, hintId = hintId, revision = revision,
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

        fun asr(
            route: AssistantRoute = AssistantRoute.WAKE,
            turnId: String = "turn-1",
            revision: Long = 1,
            text: String = "半句",
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(
            HintCommand.Asr(sessionId, generation, route, turnId = turnId, revision = revision, text = text),
        )

        fun answer(
            route: AssistantRoute = AssistantRoute.WAKE,
            turnId: String = "turn-1",
            hintId: String = "h-1",
            revision: Long = 1,
            text: String = "一半",
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(
            HintCommand.Answer(sessionId, generation, route, turnId = turnId, hintId = hintId,
                revision = revision, text = text),
        )

        fun busy(route: AssistantRoute = AssistantRoute.WAKE, busy: Boolean = true): Boolean =
            accept(HintCommand.Busy("s-1", 1, route, busy = busy))

        fun tool(
            route: AssistantRoute = AssistantRoute.WAKE,
            turnId: String = "turn-1",
            phase: ToolPhase = ToolPhase.START,
            label: String = "打开 App",
            sessionId: String = "s-1",
            generation: Long = 1,
        ): Boolean = accept(
            HintCommand.Tool(sessionId, generation, route, turnId = turnId, phase = phase, label = label),
        )

        fun state(
            status: OfficialStatus,
            sequence: Long,
            route: AssistantRoute = AssistantRoute.WAKE,
            turnId: String = "turn-$sequence",
        ): Boolean =
            accept(HintCommand.State("s-1", 1, route, turnId = turnId, status = status.wire,
                sequence = sequence))

        fun pin(): Boolean {
            controller.onKey(KEYCODE_ENTER, ACTION_DOWN)
            return controller.onKey(KEYCODE_ENTER, ACTION_UP)
        }
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
        assertEquals(OfficialConversation.TIPS_EXIT, model.tips)
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
        harness.pin()
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

    // ---------------------------------------------------------------- ASR partials (0.24 S4 Task 15)

    @Test
    fun anAsrRevisionUpdatesTheSameUserRowInPlace() {
        val harness = Harness()
        harness.hello()
        val rendersBefore = harness.port.models.size

        assertTrue(harness.asr(turnId = "turn-1", revision = 1, text = "帮我看"))
        assertTrue(harness.asr(turnId = "turn-1", revision = 2, text = "帮我看下"))

        val model = harness.port.lastModel()
        assertEquals("the selected conversation changed, so each revision renders once",
            rendersBefore + 2, harness.port.models.size)
        assertEquals("two revisions are one row, not two", 1, model.entries.size)
        val entry = model.entries.single()
        assertEquals("asr-turn-1", entry.entryId)
        assertEquals("turn-1", entry.turnId)
        assertEquals(ConversationRole.USER, entry.role)
        assertEquals("帮我看下", entry.text)
        assertEquals("the live row is not the answer", null, model.liveEntryId)
    }

    @Test
    fun anOutOfOrderAsrRevisionIsIgnored() {
        val harness = Harness()
        harness.hello()

        harness.asr(turnId = "turn-1", revision = 2, text = "后来的话")
        harness.asr(turnId = "turn-1", revision = 1, text = "迟到的话")

        assertEquals("the wire promises no ordering; a late revision must not overwrite the row",
            "后来的话", harness.controller.conversationText(AssistantRoute.WAKE))
        assertEquals(1, harness.port.lastModel().contentRevision)
        assertTrue(harness.diagnostics.any { it.contains("asr refused: revision") })
    }

    @Test
    fun aFinalSaidReplacesTheAsrRowAndKeepsItsTurn() {
        val harness = Harness()
        harness.hello()
        harness.asr(turnId = "turn-1", revision = 1, text = "帮我看")

        assertTrue(harness.said(sequence = 2, entryId = "asr-turn-1", text = "帮我看下日程"))

        val entry = harness.port.lastModel().entries.single()
        assertEquals("the final SAID must replace the partial, not append a second row",
            1, harness.port.lastModel().entries.size)
        assertEquals("asr-turn-1", entry.entryId)
        assertEquals("turn-1", entry.turnId)
        assertEquals(ConversationRole.USER, entry.role)
        assertEquals("帮我看下日程", entry.text)
    }

    @Test
    fun aLateAsrRevisionCannotOverwriteTheFinalSaid() {
        val harness = Harness()
        harness.hello()
        harness.asr(turnId = "turn-1", revision = 1, text = "帮我看")
        harness.said(sequence = 2, entryId = "asr-turn-1", text = "帮我看下日程")

        harness.asr(turnId = "turn-1", revision = 9, text = "迟到的半句")

        assertEquals("帮我看下日程", harness.controller.conversationText(AssistantRoute.WAKE))
        assertEquals(1, harness.port.lastModel().entries.size)
        assertTrue(harness.diagnostics.any { it.contains("asr refused: final") })
    }

    @Test
    fun aFinalSaidWithoutAnAsrRecoversTheTurnFromTheConventionId() {
        val harness = Harness()
        harness.hello()

        harness.said(sequence = 1, entryId = "asr-turn-1", text = "帮我看下日程")

        val entry = harness.port.lastModel().entries.single()
        assertEquals("asr-turn-1", entry.entryId)
        assertEquals("the documented asr-<turnId> id recovers the turn when no partial arrived",
            "turn-1", entry.turnId)
    }

    @Test
    fun anAsrForAnotherSessionNeverEntersTheConversation() {
        val harness = Harness()
        harness.hello("s-1", 1)

        harness.asr(sessionId = "s-other", turnId = "turn-1", revision = 1, text = "别的会话")

        assertEquals("", harness.controller.conversationText(AssistantRoute.WAKE))
        assertTrue(harness.diagnostics.any { it.contains("asr refused: session or generation mismatch") })
    }

    @Test
    fun aLateAsrRevisionForAnOlderTurnCannotOverwriteItsFinalAfterANewerTurnFinalized() {
        // 0.24 Task 15 review I1: the custom-command channel is unordered and ASR precedes SAID, so the delayed
        // revision of turn 1 can arrive after turn 2's final. The final text of turn 1 is authoritative.
        val harness = Harness()
        harness.hello()
        harness.asr(turnId = "turn-1", revision = 1, text = "第一轮半句")
        harness.said(sequence = 2, entryId = "asr-turn-1", text = "第一轮的最终话")
        harness.asr(turnId = "turn-2", revision = 1, text = "第二轮半句")
        harness.said(sequence = 3, entryId = "asr-turn-2", text = "第二轮的最终话")

        harness.asr(turnId = "turn-1", revision = 2, text = "迟到的一轮半句")

        assertEquals("a revision delayed across a turn boundary must not reopen the older final",
            "第一轮的最终话\n第二轮的最终话", harness.controller.conversationText(AssistantRoute.WAKE))
        assertEquals("no row was added or replaced", 2, harness.port.lastModel().entries.size)
        assertTrue(harness.diagnostics.any { it.contains("asr refused: final") })
    }

    @Test
    fun resetForCloseClearsTheAsrRevisionAndFinalityGates() {
        val harness = Harness()
        harness.hello()
        harness.asr(turnId = "turn-1", revision = 5, text = "关闭前的半句")
        harness.said(sequence = 2, entryId = "asr-turn-1", text = "关闭前的最终话")

        harness.controller.resetForClose()
        harness.asr(turnId = "turn-1", revision = 1, text = "新窗口的第一句")

        assertEquals("新窗口的第一句", harness.controller.conversationText(AssistantRoute.WAKE))
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
        assertEquals("the live row is the documented say-hintId answer entry", "say-h-p", model.liveEntryId)
        val entry = model.entries.single()
        assertEquals(ConversationRole.ASSISTANT, entry.role)
        assertEquals("say-h-p", entry.entryId)
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
        assertEquals("say-h-1", harness.port.lastModel().liveEntryId)

        harness.clock.nanos += 6_000_000_000L
        harness.scheduler.tasks.forEach { it() }

        val model = harness.port.lastModel()
        assertNull("the deadline must retire the live row even if the phone never sends CLEAR",
            model.liveEntryId)
        assertEquals("but the conversation the wearer reads back from must survive it",
            "答案在这里", harness.controller.conversationText(AssistantRoute.WAKE))
    }

    // ---------------------------------------------------------------- streamed answers (0.24 S4 Task 16)

    @Test
    fun streamedAnswerRevisionsUpdateOneAssistantRowInPlaceAndKeepTheirTurn() {
        val harness = Harness()
        harness.hello()

        harness.answer(revision = 1, text = "今天")
        harness.answer(revision = 2, text = "今天晴。")

        val model = harness.port.lastModel()
        val entry = model.entries.single()
        assertEquals("say-h-1", entry.entryId)
        assertEquals(ConversationRole.ASSISTANT, entry.role)
        assertEquals("the revision carries its turn so a screen can group the exchange", "turn-1", entry.turnId)
        assertEquals("the later revision replaces the row in place", "今天晴。", entry.text)
        assertTrue("a stored revision is new content the view must reveal", model.contentRevision > 0)
        assertNull("only a SHOW makes a row live", model.liveEntryId)
    }

    @Test
    fun theFinalShowReplacesTheStreamedAnswerRowAndUsesTheSameEntryIdentity() {
        val harness = Harness()
        harness.hello()
        harness.sync()

        harness.answer(revision = 2, text = "今天晴")
        harness.show(sequence = 1, hintId = "h-1", text = "今天晴。")

        val model = harness.port.lastModel()
        val entry = model.entries.single()
        assertEquals("the final answer is the same say-hintId row the revisions opened", "say-h-1", entry.entryId)
        assertEquals("the final keeps the turn its partial established", "turn-1", entry.turnId)
        assertEquals("今天晴。", entry.text)
        assertEquals("the live row names the entry id, not the bare hint id", "say-h-1", model.liveEntryId)
        val ack = harness.sent.filterIsInstance<HintCommand.Ack>().single()
        assertEquals("the ACK keeps the wire hint id", "h-1", ack.hintId)
    }

    @Test
    fun aFinalShowWithoutAnyRevisionStillUsesTheSayEntryIdentity() {
        val harness = Harness()
        harness.hello()
        harness.sync()

        harness.show(hintId = "h-9", text = "直接给出的答案")

        val model = harness.port.lastModel()
        val entry = model.entries.single()
        assertEquals("the documented convention is producer-independent", "say-h-9", entry.entryId)
        assertEquals("say-h-9", model.liveEntryId)
    }

    @Test
    fun aFinalOnlyNotifyAnswerJoinsTheAsrTurnNamedByTheAcceptedState() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.asr(turnId = "turn-1", revision = 1, text = "帮我看下日程")
        harness.state(OfficialStatus.ANSWERING, sequence = 1, turnId = "turn-1")

        // The production notify_user answer: no ANSWER partial precedes the final SHOW, so the STATE (published just
        // before the submit) is the only carrier of the turn.
        harness.show(sequence = 1, hintId = "h-1", text = "今天十点开会")

        val model = harness.port.lastModel()
        val answer = model.entries.single { it.role == ConversationRole.ASSISTANT }
        assertEquals("the final-only answer takes the turn the accepted STATE named", "turn-1", answer.turnId)
        val turn = ConversationView.turns(model.entries, model.liveEntryId).single()
        assertEquals("the wearer's line and the answer are one item", "帮我看下日程", turn.asrText)
        assertEquals("今天十点开会", turn.answerText)
        assertTrue(turn.answerLive)
        assertEquals("once the answer is in, the wearer's line drops to 70%",
            GlassesPanelSpec.GREEN_NORMAL_70, turn.asrTextColor)
    }

    @Test
    fun aFinalOnlyAnswerThatOvertookItsStateDoesNotJoinAnOlderTurn() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.asr(turnId = "turn-1", revision = 1, text = "第一问")
        harness.state(OfficialStatus.ANSWERING, sequence = 1, turnId = "turn-1")
        harness.show(sequence = 1, hintId = "h-1", text = "第一答")

        // Turn 2 has started (the wearer's line is in) but its STATE has not arrived: the stored level still names
        // turn 1. A SHOW for turn 2 that overtook its own STATE must not be attached to the answered turn 1.
        harness.asr(turnId = "turn-2", revision = 1, text = "第二问")
        harness.show(sequence = 2, hintId = "h-2", text = "第二答")

        val model = harness.port.lastModel()
        val second = model.entries.single { it.entryId == "say-h-2" }
        assertNull("an overtaking final must not join an older turn", second.turnId)
        val turns = ConversationView.turns(model.entries, model.liveEntryId)
        assertEquals("the older turn keeps its pair, the current line and the stray final stay separate", 3, turns.size)
        val first = turns.single { it.turnId == "turn-1" }
        assertEquals("第一问", first.asrText)
        assertEquals("第一答", first.answerText)
        assertEquals(GlassesPanelSpec.GREEN_NORMAL_70, first.asrTextColor)
        assertEquals("第二问", turns.single { it.turnId == "turn-2" }.asrText)
        assertTrue("the stray final is its own legacy item", turns.any { it.turnId == null && it.answerText == "第二答" })
    }

    @Test
    fun outOfOrderAnswerRevisionsAreDropped() {
        val harness = Harness()
        harness.hello()

        harness.answer(revision = 2, text = "答案二")
        harness.answer(revision = 1, text = "迟到的答案一")

        assertEquals("答案二", harness.controller.conversationText(AssistantRoute.WAKE))
    }

    @Test
    fun aStreamedRevisionAfterTheFinalShowIsDropped() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.answer(revision = 1, text = "答案")
        harness.show(sequence = 1, hintId = "h-1", text = "答案。")
        val rendersBefore = harness.port.models.size

        harness.answer(revision = 9, text = "迟到的答案")

        assertEquals("the final SHOW is authoritative", "答案。",
            harness.controller.conversationText(AssistantRoute.WAKE))
        assertEquals("a dropped revision does not repaint", rendersBefore, harness.port.models.size)
    }

    @Test
    fun theAnsweringOrbRetiresAtStatusFiveSoTheAutomaticNextRoundListens() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(hintId = "h-1", text = "答案在这里")

        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        assertEquals(OrbState.ANSWERING, orbStateOf(harness.port.lastModel()))

        harness.state(OfficialStatus.AUDIO_FINISHED, sequence = 2)
        val finished = harness.port.lastModel()
        assertNull("status five finalises the previous answer item", finished.liveEntryId)
        assertEquals("status five still plays the finish animation", OrbState.ANSWERING, orbStateOf(finished))

        harness.state(OfficialStatus.LISTENING, sequence = 3)
        val listening = harness.port.lastModel()
        assertNull("the next round has no live answer of its own yet", listening.liveEntryId)
        assertTrue(listening.listeningVisible)
        assertFalse("the automatic next round must not say Hi again", listening.hiVisible)
        assertEquals("SHOW -> 3 -> 5 -> 1 ends in a listening orb", OrbState.LISTENING, orbStateOf(listening))
    }

    @Test
    fun statusFourOutranksAPriorLiveAnswer() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(hintId = "h-1", text = "上一条答案")

        harness.state(OfficialStatus.ASR_END, sequence = 1)

        val model = harness.port.lastModel()
        assertNull("a thinking status is not the previous answer's state", model.liveEntryId)
        assertTrue(model.thinkingVisible)
        assertEquals("a prior live answer must not mask the thinking orb", OrbState.THINKING, orbStateOf(model))
    }

    @Test
    fun statusTwoRetiresAPriorLiveAnswerForTheListeningOrb() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(hintId = "h-1", text = "上一条答案")

        harness.state(OfficialStatus.ASR, sequence = 1)

        val model = harness.port.lastModel()
        assertNull("the wearer speaking ends the previous answer's live state", model.liveEntryId)
        assertEquals(OrbState.LISTENING, orbStateOf(model))
    }

    // ---------------------------------------------------------------- tool rows (0.24 S4 Task 17)

    @Test
    fun aToolStartAndEndUpdateOneToolRowAndAddNoConversationEntry() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.TOOL, sequence = 1, turnId = "turn-7")

        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "打开 App")
        val started = harness.port.lastModel()
        assertEquals("turn-7", started.toolTurnId)
        assertEquals("打开 App", started.toolLabel)
        assertEquals(ToolPhase.START, started.toolPhase)
        assertTrue("a tool row is not a conversation entry", started.entries.isEmpty())

        harness.tool(turnId = "turn-7", phase = ToolPhase.END, label = "打开 App")
        val ended = harness.port.lastModel()
        assertEquals("the end restyles the same turn row", "turn-7", ended.toolTurnId)
        assertEquals("打开 App", ended.toolLabel)
        assertEquals(ToolPhase.END, ended.toolPhase)
        assertEquals("still no second entry", 0, ended.entries.size)
    }

    @Test
    fun aSecondToolStartReplacesTheSameRowsLabelInsteadOfAddingARow() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.TOOL, sequence = 1, turnId = "turn-7")

        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "打开 App")
        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "打开设置")

        val started = harness.port.lastModel()
        assertEquals("a second START is the same turn's row, not an append", "打开设置", started.toolLabel)
        assertEquals(ToolPhase.START, started.toolPhase)
        assertTrue("and still not a conversation entry", started.entries.isEmpty())

        harness.tool(turnId = "turn-7", phase = ToolPhase.END, label = "打开设置")
        assertEquals(ToolPhase.END, harness.port.lastModel().toolPhase)
    }

    @Test
    fun theToolRowExistsOnlyAtStatusSeven() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ASR_END, sequence = 1, turnId = "turn-7")
        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "打开 App")

        val thinking = harness.port.lastModel()
        assertNull("status 4 is the thinking row, not the tool row", thinking.toolLabel)
        assertNull(thinking.toolPhase)

        harness.state(OfficialStatus.TOOL, sequence = 2, turnId = "turn-7")
        assertEquals("打开 App", harness.port.lastModel().toolLabel)

        harness.state(OfficialStatus.ANSWERING, sequence = 3, turnId = "turn-7")
        assertNull("the answer retires the tool row", harness.port.lastModel().toolLabel)
    }

    @Test
    fun aToolForAnotherTurnIsRefusedRatherThanReplacingTheCurrentToolRow() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.TOOL, sequence = 1, turnId = "turn-7")
        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "当前动作")

        harness.tool(turnId = "turn-old", phase = ToolPhase.END, label = "迟到动作")

        assertEquals("当前动作", harness.port.lastModel().toolLabel)
        assertEquals(ToolPhase.START, harness.port.lastModel().toolPhase)
        assertTrue(harness.diagnostics.any { it.contains("tool refused: turn") })
    }

    @Test
    fun aToolForAnotherSessionNeverReachesTheSelectedRows() {
        val harness = Harness()
        harness.hello("s-1", 1)
        harness.state(OfficialStatus.TOOL, sequence = 1, turnId = "turn-7")

        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "别人的动作", sessionId = "s-other")

        assertNull(harness.port.lastModel().toolLabel)
        assertTrue(harness.diagnostics.any { it.contains("tool refused: session or generation mismatch") })
    }

    @Test
    fun resetForCloseClearsTheToolAndStatusTurnState() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.TOOL, sequence = 1, turnId = "turn-7")
        harness.tool(turnId = "turn-7", phase = ToolPhase.START, label = "关闭前的动作")

        harness.controller.resetForClose()

        val after = harness.port.lastModel()
        assertNull(after.toolLabel)
        assertNull(after.toolTurnId)
        assertNull(after.toolPhase)
    }

    // ---------------------------------------------------------------- thinking per status (0.24 S4 Task 17)

    @Test
    fun statusThreeThinksUntilTheAnswerTextActuallyArrives() {
        val harness = Harness()
        harness.hello()
        harness.asr(turnId = "turn-7", revision = 1, text = "帮我看")
        harness.state(OfficialStatus.ANSWERING, sequence = 1, turnId = "turn-7")

        assertTrue("status 3 before any answer text still owns the thinking row",
            harness.port.lastModel().thinkingVisible)

        harness.answer(turnId = "turn-7", hintId = "h-7", revision = 1, text = "答案是")

        val answered = harness.port.lastModel()
        assertFalse("the answer row replaces the thinking row", answered.thinkingVisible)
        assertNull("a hidden thinking row names no turn", answered.thinkingTurnId)
        assertEquals("答案是", answered.entries.single { it.role == ConversationRole.ASSISTANT }.text)
    }

    @Test
    fun statusFourThinkingIsRetiredByAStreamedAnswerOfTheSameTurn() {
        val harness = Harness()
        harness.hello()
        harness.asr(turnId = "turn-7", revision = 1, text = "帮我看")
        harness.state(OfficialStatus.ASR_END, sequence = 1, turnId = "turn-7")
        assertTrue(harness.port.lastModel().thinkingVisible)

        harness.answer(turnId = "turn-7", hintId = "h-7", revision = 1, text = "答")

        assertFalse(harness.port.lastModel().thinkingVisible)
    }

    // ---------------------------------------------------------------- new-content revision (the reveal signal)

    @Test
    fun anIdenticalShowWithAHigherRevisionStillCountsAsNewContent() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(sequence = 1, hintId = "h-same", text = "同一句答案", revision = 1)
        val before = harness.port.lastModel()
        val rendersBefore = harness.port.models.size

        harness.show(sequence = 2, hintId = "h-same", text = "同一句答案", revision = 2)

        val after = harness.port.lastModel()
        assertEquals("the accepted SHOW must reach the port", rendersBefore + 1, harness.port.models.size)
        assertEquals("the log replaces an equal entry, so what is stored is unchanged",
            before.entries, after.entries)
        assertEquals("but a newer revision is still new content the view must reveal",
            before.contentRevision + 1, after.contentRevision)
        assertEquals("say-h-same", after.liveEntryId)
    }

    @Test
    fun aResentConfirmationHintReplacesItsRowInsteadOfAppendingASecondOne() {
        // 0.3 Phase 4B (B5) B4 carry-forward: the panel is created by PANEL_OPEN after the first SHOW, so the phone
        // re-sends the question on PANEL_SHOWN. With the confirmation's stable hint id the second SHOW must replace
        // `say-<hintId>`, not append a second "确认" row.
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(sequence = 1, hintId = "confirm-c-1", text = "卸载探针 App", revision = 1)
        harness.show(sequence = 2, hintId = "confirm-c-1", text = "卸载探针 App", revision = 2)

        val entries = harness.port.lastModel().entries
        assertEquals("one question is one assistant row, however often it is announced", 1,
            entries.count { it.role == ConversationRole.ASSISTANT })
        assertEquals("the row keeps the confirmation's stable id", "say-confirm-c-1", entries.single().entryId)
    }

    @Test
    fun anIdenticalSaidReplacementStillCountsAsNewContent() {        val harness = Harness()
        harness.hello()
        harness.said(sequence = 1, entryId = "said-same", text = "同一句话")
        val before = harness.port.lastModel()
        val rendersBefore = harness.port.models.size

        harness.said(sequence = 2, entryId = "said-same", text = "同一句话")

        val after = harness.port.lastModel()
        assertEquals("a stored selected-route SAID must reach the port", rendersBefore + 1,
            harness.port.models.size)
        assertEquals("the replacement is data-class equal, so the stored conversation is unchanged",
            before.entries, after.entries)
        assertEquals("but storing it is still new content the view must reveal",
            before.contentRevision + 1, after.contentRevision)
        assertEquals(ConversationRole.USER, after.entries.single().role)
    }

    @Test
    fun staleRefusedAndOtherRouteCommandsDoNotCountAsNewContent() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show(sequence = 1, hintId = "h-1", text = "答案在这里", revision = 5)
        harness.said(sequence = 2, entryId = "said-late", text = "先到的话")
        val baseline = harness.port.lastModel()
        val rendersBefore = harness.port.models.size

        // A revision at or below the one already shown is refused by the hint gate.
        harness.show(sequence = 2, hintId = "h-1", text = "答案在这里", revision = 5)
        // A SAID for the conversation that is not on screen is stored but must not move the view.
        assertTrue(harness.said(route = AssistantRoute.PROACTIVE, sequence = 1, entryId = "said-other",
            text = "别处的话"))
        // A SAID behind the ordering fence is refused.
        harness.said(sequence = 1, entryId = "said-stale", text = "迟到的话")
        // A SHOW for another session is refused.
        harness.show(sequence = 3, hintId = "h-other", text = "别人的答案", sessionId = "s-other")

        assertEquals("nothing on screen changed, so nothing was rendered", rendersBefore,
            harness.port.models.size)
        assertEquals("and none of it counted as new content for the view", baseline.contentRevision,
            harness.port.lastModel().contentRevision)
        assertEquals("only the accepted SHOW and SAID are stored", "答案在这里\n先到的话",
            harness.controller.conversationText(AssistantRoute.WAKE))
    }

    @Test
    fun aHintRetirementDoesNotCountAsNewContent() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show()
        val before = harness.port.lastModel().contentRevision

        harness.clock.nanos += 6_000_000_000L
        harness.scheduler.tasks.forEach { it() }

        val after = harness.port.lastModel()
        assertNull("the deadline retires the live row", after.liveEntryId)
        assertEquals("retiring the live row is not new content to reveal", before, after.contentRevision)
    }

    // ---------------------------------------------------------------- BUSY / the listening-Hi signal

    @Test
    fun aBusyWakeKeepsTheLegacyListeningRowsButTheOfficialExitTip() {
        val harness = Harness()
        harness.hello()

        harness.busy(busy = true)

        val model = harness.port.lastModel()
        assertFalse(model.idle)
        assertTrue("a busy wake before the wearer speaks is listening", model.listeningVisible)
        assertTrue("the legacy world still shows its Hi text", model.hiVisible)
        assertFalse(model.thinkingVisible)
        assertEquals("the official interrupt copy is reserved for pinned + status 3",
            OfficialConversation.TIPS_EXIT, model.tips)
    }

    @Test
    fun onceTheWearerHasSpokenThinkingReplacesListeningAndHi() {
        val harness = Harness()
        harness.hello()
        harness.busy(busy = true)

        harness.said()

        val model = harness.port.lastModel()
        assertFalse("the Hi/listening row is over once the wearer's line is in", model.listeningVisible)
        assertFalse("and the legacy Hi text with it", model.hiVisible)
        assertTrue("the thinking row takes over", model.thinkingVisible)
        assertEquals("the assistant is still working, but without a status the official interrupt copy is not claimed",
            OfficialConversation.TIPS_EXIT, model.tips)
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
        assertEquals(OfficialConversation.TIPS_EXIT, model.tips)
    }

    // ---------------------------------------------------------------- keys: ENTER pin / BACK

    @Test
    fun enterPinsOnTheUpAndASecondPressStaysPinned() {
        val harness = Harness()
        harness.hello()

        assertTrue("ENTER's down is consumed", harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertFalse("but it does not pin", harness.port.lastModel().pinned)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertTrue(harness.port.lastModel().pinned)
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_DOWN))
        assertTrue(harness.controller.onKey(KEYCODE_ENTER, ACTION_UP))
        assertTrue("a second press must not unpin", harness.port.lastModel().pinned)
        assertTrue("BACK's down is consumed by the panel too",
            harness.controller.onKey(KEYCODE_BACK, ACTION_DOWN))
        assertFalse("a direction key is the host's",
            harness.controller.onKey(KEYCODE_DPAD_DOWN, ACTION_DOWN))
    }

    @Test
    fun backWhilePinnedAndAnsweringSendsStopAndLeavesThePanelOpen() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        harness.pin()
        harness.sent.clear()

        assertTrue(harness.controller.onKey(KEYCODE_BACK, ACTION_UP))

        val stop = harness.sent.filterIsInstance<HintCommand.Stop>().single()
        assertEquals("s-1", stop.sessionId)
        assertEquals(1L, stop.generation)
        assertEquals("a STOP that was sent keeps the panel for the phone's IDLE", 0, harness.port.hides)
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=stop sent=true") })
    }

    @Test
    fun backWhenTheStopCannotBeSentHidesThePanelInsteadOfTrappingTheWearer() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.ANSWERING, sequence = 1)
        harness.pin()
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
        assertTrue(harness.diagnostics.any { it.contains("P5_GLASS_BACK action=exit") })
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

    // ---------------------------------------------------------------- close/reset (0.24 S2 Task 9)

    @Test
    fun resetForCloseClearsTheConversationTheGatesTheBusyLevelThePinAndTheRevision() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show()
        harness.said(sequence = 2, entryId = "said-after", text = "之后说的话")
        harness.busy(busy = true)
        harness.pin()
        val before = harness.port.lastModel()
        assertEquals("say-h-1", before.liveEntryId)
        assertTrue("the pin was set before the close", before.pinned)
        assertFalse("a busy level was reported before the close", before.idle)
        assertEquals(2, before.entries.size)
        assertTrue("the accepted content moved the reveal counter", before.contentRevision > 0)

        harness.controller.resetForClose()

        val after = harness.port.lastModel()
        assertEquals("a closed panel must not leave the conversation behind", "",
            harness.controller.conversationText(AssistantRoute.WAKE))
        assertTrue("no entry survives the close", after.entries.isEmpty())
        assertNull("no hint is live after the close", after.liveEntryId)
        assertTrue("the closed panel re-opens idle", after.idle)
        assertFalse("the reading pin is cleared", after.pinned)
        assertEquals("the reveal revision restarts with the cache", 0L, after.contentRevision)
        assertEquals("the session identity survives a panel close; only the cache is cleared", "s-1" to 1L,
            harness.controller.currentIdentity())

        // The SAID ordering gate is part of the cache: a new window may start its sequence domain at 1 again.
        harness.said(sequence = 1, entryId = "said-new", text = "新窗口第一句")
        assertEquals("新窗口第一句", harness.controller.conversationText(AssistantRoute.WAKE))
    }

    @Test
    fun aClosedPanelCanDisplayTheSameHintAgainAfterReopen() {
        // Task 9 carry to Task 11: a window close must not make the phone's retransmitted hint permanently undisplayable.
        // The session identity and clock mapping survive; only the previous window's ordering/revision gates are reset.
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show()

        harness.controller.resetForClose()
        harness.show()

        val reopened = harness.port.lastModel()
        assertEquals("the same hint id must be displayable in the new window", "say-h-1", reopened.liveEntryId)
        assertEquals("答案在这里", harness.controller.conversationText(AssistantRoute.WAKE))
        assertEquals("the session identity survives the window reset", "s-1" to 1L,
            harness.controller.currentIdentity())
    }

    // ---------------------------------------------------------------- official GIFs and Markdown (0.24 S4 Task 19)

    @Test
    fun theAsrWaitingGifFollowsOnlyTheOfficialWaitingStatuses() {
        val harness = Harness()
        harness.hello()
        harness.state(OfficialStatus.EMPTY, 1)
        assertTrue("status 0 is the waiting GIF", harness.port.lastModel().asrLoadingVisible)

        harness.state(OfficialStatus.LISTENING, 2)
        assertTrue("status 1 keeps the waiting GIF", harness.port.lastModel().asrLoadingVisible)

        harness.state(OfficialStatus.ASR, 3)
        assertFalse("status 2's wearer text replaces the GIF", harness.port.lastModel().asrLoadingVisible)

        harness.state(OfficialStatus.ASR_END, 4)
        assertFalse("status 4 is the thinking row, not the waiting GIF", harness.port.lastModel().asrLoadingVisible)
    }

    @Test
    fun onlyTheAcceptedShowBecomesTheMarkdownFinal() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.answer(turnId = "turn-md", hintId = "h-md", text = "**一半**")
        assertTrue("a streamed partial is not final", harness.port.lastModel().finalAnswerEntryIds.isEmpty())

        harness.show(sequence = 1, hintId = "h-md", text = "**完整**")
        assertTrue("the accepted SHOW is the final Markdown row",
            "say-h-md" in harness.port.lastModel().finalAnswerEntryIds)

        harness.state(OfficialStatus.AUDIO_FINISHED, 1, turnId = "turn-md")
        assertTrue("a later status does not downgrade an old final row",
            "say-h-md" in harness.port.lastModel().finalAnswerEntryIds)
    }

    @Test
    fun disconnectDropsTheIdentityAndRetiresAVisibleHint() {
        val harness = Harness()
        harness.hello()
        harness.sync()
        harness.show()
        assertEquals("say-h-1", harness.port.lastModel().liveEntryId)

        harness.controller.disconnect()

        assertNull("a destroyed screen must not keep the session it could accept commands for",
            harness.controller.currentIdentity())
        val model = harness.port.lastModel()
        assertNull("the visible hint is retired so no text is left behind", model.liveEntryId)
        assertEquals("the conversation itself is not erased by the teardown", "答案在这里",
            harness.controller.conversationText(AssistantRoute.WAKE))
    }
}
