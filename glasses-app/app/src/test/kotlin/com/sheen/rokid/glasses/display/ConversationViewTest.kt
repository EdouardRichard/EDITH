package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.23 P3 (spec §5.1.3, U9): how the conversation is laid out, decided without Android so it can be tested off-device.
 *
 * The rule changed with 0.23: **every line is left-aligned**, including the wearer's own words; the official panel
 * separates the two roles by colour and size instead of by side. The Activity builds the `TextView`s; **which** rows
 * exist, which side each one sits on, and which one is the live hint are decisions, and they are here.
 */
class ConversationViewTest {

    private fun entry(id: String, role: ConversationRole, text: String = "第 $id 条") =
        ConversationEntry(id, AssistantRoute.WAKE, role, text)

    @Test
    fun onlyTheLiveHintIsEmphasised() {
        val rows = ConversationView.rows(
            listOf(entry("a", ConversationRole.ASSISTANT), entry("b", ConversationRole.ASSISTANT)),
            liveEntryId = "b")

        assertEquals(listOf(false, true), rows.map { it.live })
    }

    @Test
    fun anEmptyConversationProducesNoRowsRatherThanAPlaceholder() {
        assertTrue(ConversationView.rows(emptyList(), liveEntryId = null).isEmpty())
    }

    @Test
    fun theRowsKeepTheOrderTheyWereGiven() {
        // The order is the log's, and the log is the single authority both screens read; re-sorting here would let the
        // phone and the glasses disagree about what was said first.
        val rows = ConversationView.rows(
            listOf(entry("a", ConversationRole.USER), entry("b", ConversationRole.ASSISTANT),
                entry("c", ConversationRole.USER)),
            liveEntryId = null)

        assertEquals(listOf("第 a 条", "第 b 条", "第 c 条"), rows.map { it.entry.text })
    }

    // ---------------------------------------------------------------- ASR revisions (0.24 S4 Task 15)

    @Test
    fun asrRevisionsAreMonotonicWithinOneTurn() {
        val gate = AsrRevisionGate()

        assertTrue(gate.accept(AssistantRoute.WAKE, "t-1", 1))
        assertTrue(gate.accept(AssistantRoute.WAKE, "t-1", 2))
        assertFalse("a revision at or below the applied one is late", gate.accept(AssistantRoute.WAKE, "t-1", 1))
        assertFalse(gate.accept(AssistantRoute.WAKE, "t-1", 2))
        assertTrue("a new turn starts its own revision domain", gate.accept(AssistantRoute.WAKE, "t-2", 1))
    }

    @Test
    fun asrRevisionsAreIndependentPerRoute() {
        val gate = AsrRevisionGate()

        assertTrue(gate.accept(AssistantRoute.WAKE, "t-1", 5))
        assertTrue("the other conversation has its own revisions", gate.accept(AssistantRoute.PROACTIVE, "t-1", 1))
    }

    @Test
    fun aFinalizedUserEntryRejectsEveryLaterRevisionUntilTheWindowResets() {
        val gate = AsrRevisionGate()
        assertTrue(gate.accept(AssistantRoute.WAKE, "t-1", 1))

        gate.finalize(AssistantRoute.WAKE, AsrRevisionGate.userEntryId("t-1"))
        assertFalse("a final replaces the line; a late revision cannot reopen it",
            gate.accept(AssistantRoute.WAKE, "t-1", 9))
        assertTrue("a new turn is not the finalized entry", gate.accept(AssistantRoute.WAKE, "t-2", 1))

        gate.reset()
        assertTrue("a closed/reopened window starts clean", gate.accept(AssistantRoute.WAKE, "t-1", 1))
    }

    @Test
    fun aLateRevisionForAnOlderFinalizedTurnStaysRejectedAfterANewerTurnFinalizes() {
        // 0.24 Task 15 review I1: the channel is unordered, so after turn 2 finalizes a revision of turn 1 may still be
        // in flight. A single "last finalized id" forgets turn 1 when turn 2 claims the slot; a bounded set must not.
        val gate = AsrRevisionGate()
        assertTrue(gate.accept(AssistantRoute.WAKE, "t-1", 1))
        gate.finalize(AssistantRoute.WAKE, AsrRevisionGate.userEntryId("t-1"))
        assertTrue(gate.accept(AssistantRoute.WAKE, "t-2", 1))
        gate.finalize(AssistantRoute.WAKE, AsrRevisionGate.userEntryId("t-2"))

        assertFalse("a newer final must not forget that the older turn was finalized",
            gate.accept(AssistantRoute.WAKE, "t-1", 2))
        assertTrue("the older turn is still final", gate.isFinalized(AssistantRoute.WAKE, "t-1"))
        assertTrue("the newer final is remembered too", gate.isFinalized(AssistantRoute.WAKE, "t-2"))
    }

    @Test
    fun theFinalizedIdMemoryIsBoundedAndEvictsTheOldest() {
        // Bounded like the phone's display: a long session must not grow this set without end. Dropping the oldest only
        // means a revision older than that many finalized turns could reopen its row (the documented trade-off).
        val gate = AsrRevisionGate()
        val cap = ConversationLog.MAX_ENTRIES
        repeat(cap + 1) { index ->
            gate.finalize(AssistantRoute.WAKE, AsrRevisionGate.userEntryId("t-$index"))
        }

        assertFalse("the oldest final is forgotten once a whole log's worth of turns has finalized",
            gate.isFinalized(AssistantRoute.WAKE, "t-0"))
        assertTrue("the newest final is remembered", gate.isFinalized(AssistantRoute.WAKE, "t-$cap"))
    }

    @Test
    fun theAsrEntryIdConventionAndTurnRecoveryAreExplicit() {
        assertEquals("asr-t-1", AsrRevisionGate.userEntryId("t-1"))
        assertEquals("t-1", AsrRevisionGate.turnIdOf("asr-t-1"))
        assertEquals("asr-1", AsrRevisionGate.turnIdOf("asr-asr-1"))
        assertEquals(null, AsrRevisionGate.turnIdOf("said-1"))
        assertEquals(null, AsrRevisionGate.turnIdOf("asr-"))
    }

    // ---------------------------------------------------------------- answer revisions (0.24 S4 Task 16)

    @Test
    fun answerRevisionsAreMonotonicPerHint() {
        val gate = AnswerRevisionGate()

        assertTrue(gate.accept(AssistantRoute.WAKE, "h-1", 1))
        assertTrue(gate.accept(AssistantRoute.WAKE, "h-1", 2))
        assertFalse("a revision at or below the applied one is late", gate.accept(AssistantRoute.WAKE, "h-1", 2))
        assertFalse(gate.accept(AssistantRoute.WAKE, "h-1", 1))
        assertTrue("a new answer entry starts its own revision domain", gate.accept(AssistantRoute.WAKE, "h-2", 1))
        assertTrue("the other conversation has its own revisions", gate.accept(AssistantRoute.PROACTIVE, "h-1", 1))
    }

    @Test
    fun aFinalizedAnswerEntryRejectsEveryLaterRevisionUntilTheWindowResets() {
        val gate = AnswerRevisionGate()
        assertTrue(gate.accept(AssistantRoute.WAKE, "h-1", 1))

        gate.finalize(AssistantRoute.WAKE, AnswerRevisionGate.answerEntryId("h-1"))
        assertFalse("the final SHOW replaces the line; a late revision cannot reopen it",
            gate.accept(AssistantRoute.WAKE, "h-1", 9))
        assertTrue("a new hint is not the finalized entry", gate.accept(AssistantRoute.WAKE, "h-2", 1))

        gate.reset()
        assertTrue("a closed/reopened window starts clean", gate.accept(AssistantRoute.WAKE, "h-1", 1))
    }

    @Test
    fun aLateAnswerRevisionForAnOlderFinalizedHintStaysRejectedAfterANewerHintFinalizes() {
        // Same unordered-channel rule as ASR: after answer 2 finalizes, a delayed revision of answer 1 may still be in
        // flight, so the gate keeps a bounded set of finalized answer entries rather than only the latest.
        val gate = AnswerRevisionGate()
        assertTrue(gate.accept(AssistantRoute.WAKE, "h-1", 1))
        gate.finalize(AssistantRoute.WAKE, AnswerRevisionGate.answerEntryId("h-1"))
        assertTrue(gate.accept(AssistantRoute.WAKE, "h-2", 1))
        gate.finalize(AssistantRoute.WAKE, AnswerRevisionGate.answerEntryId("h-2"))

        assertFalse("a newer final must not forget the older answer",
            gate.accept(AssistantRoute.WAKE, "h-1", 2))
        assertTrue(gate.isFinalized(AssistantRoute.WAKE, "h-1"))
        assertTrue(gate.isFinalized(AssistantRoute.WAKE, "h-2"))
    }

    @Test
    fun theAnswerEntryIdConventionIsExplicit() {
        assertEquals("say-hint-9", AnswerRevisionGate.answerEntryId("hint-9"))
    }

    // ---------------------------------------------------------------- per-turn items (0.24 S4 Task 17)

    private fun turnEntry(
        id: String,
        role: ConversationRole,
        turnId: String?,
        text: String = "第 $id 条",
    ) = ConversationEntry(id, AssistantRoute.WAKE, role, text, turnId)

    @Test
    fun turnsGroupTheWearersLineAndItsAnswerByTurnId() {
        val turns = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1", "问一"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
                turnEntry("asr-t2", ConversationRole.USER, "t-2", "问二"),
            ),
        )

        assertEquals("one item per turn, in the order the turns first appeared",
            listOf("t-1", "t-2"), turns.map { it.turnId })
        assertEquals("问一", turns[0].asrText)
        assertEquals("答一", turns[0].answerText)
        assertEquals("问二", turns[1].asrText)
        assertEquals(null, turns[1].answerText)
    }

    @Test
    fun entriesWithoutATurnIdStayOneItemEach() {
        // Spec §5.6: an old/no-turn producer is rendered one entry per item rather than being guessed into a group.
        val turns = ConversationView.turns(
            listOf(
                turnEntry("said-1", ConversationRole.USER, null, "一"),
                turnEntry("said-2", ConversationRole.ASSISTANT, null, "二"),
                turnEntry("said-3", ConversationRole.USER, null, "三"),
            ),
        )

        assertEquals(3, turns.size)
        assertTrue("a null turn id is never merged with another null entry", turns.all { it.turnId == null })
        assertEquals(listOf("一", "二", "三"), turns.map { it.asrText ?: it.answerText })
    }

    @Test
    fun aMixedLogKeepsTheFirstAppearanceOrderAndTheNullEntriesInPlace() {
        val turns = ConversationView.turns(
            listOf(
                turnEntry("asr-t2", ConversationRole.USER, "t-2", "第二轮"),
                turnEntry("said-legacy", ConversationRole.ASSISTANT, null, "旧回答"),
                turnEntry("asr-t1", ConversationRole.USER, "t-1", "第一轮"),
            ),
        )

        assertEquals("no sorting by turn id or timestamp", listOf("t-2", null, "t-1"), turns.map { it.turnId })
        assertEquals(listOf("第二轮", "旧回答", "第一轮"), turns.map { it.asrText ?: it.answerText })
    }

    @Test
    fun theLiveAnswerIsMarkedOnItsOwnTurnOnly() {
        val turns = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
                turnEntry("say-h2", ConversationRole.ASSISTANT, "t-2", "答二"),
            ),
            liveEntryId = "say-h2",
        )

        assertFalse(turns[0].answerLive)
        assertTrue("a final SHOW marks the same turn row the partial opened", turns[1].answerLive)
    }

    @Test
    fun turnRowsCarryTheOfficialRoleStyles() {
        val beforeAnswer = ConversationView.turns(
            listOf(turnEntry("asr-t1", ConversationRole.USER, "t-1")),
        ).single()
        assertEquals(GlassesPanelSpec.USER_TEXT_SIZE_PX, beforeAnswer.asrTextSizePx)
        assertEquals("while recognising, the wearer's line is full green",
            GlassesPanelSpec.GREEN_NORMAL, beforeAnswer.asrTextColor)

        val afterAnswer = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
            ),
        ).single()
        assertEquals("once the answer is in, the wearer's line drops to 70%",
            GlassesPanelSpec.GREEN_NORMAL_70, afterAnswer.asrTextColor)
        assertEquals(GlassesPanelSpec.ASSISTANT_TEXT_SIZE_PX, afterAnswer.answerTextSizePx)
        assertEquals(GlassesPanelSpec.GREEN_NORMAL, afterAnswer.answerTextColor)
    }

    @Test
    fun theProductionNotifyAnswerShapePairsWhenTheAnswerCarriesTheStateTurn() {
        // The production notify_user answer has no streamed ANSWER partial: the controller takes the turn from the
        // accepted STATE and writes it onto the say-${hintId} entry. With that turn the exchange is one item.
        val paired = ConversationView.turns(
            listOf(
                turnEntry("asr-turn-1", ConversationRole.USER, "turn-1", "帮我看下日程"),
                turnEntry("say-h-1", ConversationRole.ASSISTANT, "turn-1", "今天十点开会"),
            ),
            liveEntryId = "say-h-1",
        ).single()
        assertEquals("帮我看下日程", paired.asrText)
        assertEquals("今天十点开会", paired.answerText)
        assertTrue(paired.answerLive)
        assertEquals("once the answer is in, the wearer's line drops to 70%",
            GlassesPanelSpec.GREEN_NORMAL_70, paired.asrTextColor)

        // The turn-less (legacy/proactive) final is the shape that must stay one item per entry (spec §5.6); the
        // controller fallback must never be allowed to mint this pairing by guessing.
        val unpaired = ConversationView.turns(
            listOf(
                turnEntry("asr-turn-1", ConversationRole.USER, "turn-1", "帮我看下日程"),
                turnEntry("say-h-1", ConversationRole.ASSISTANT, null, "今天十点开会"),
            ),
        )
        assertEquals(2, unpaired.size)
        assertEquals(GlassesPanelSpec.GREEN_NORMAL, unpaired[0].asrTextColor)
        assertNull(unpaired[1].asrText)
    }

    @Test
    fun thinkingAndToolRowsAttachToTheTurnTheyBelongTo() {
        val turns = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
                turnEntry("asr-t2", ConversationRole.USER, "t-2"),
            ),
            thinkingVisible = true,
            thinkingTurnId = "t-2",
            tool = ToolRowState("t-2", "打开 App", ToolPhase.START),
        )

        assertFalse(turns[0].thinkingVisible)
        assertNull(turns[0].toolLabel)
        assertTrue("status 4 thinking belongs to the turn being worked on", turns[1].thinkingVisible)
        assertEquals("打开 App", turns[1].toolLabel)
        assertEquals(ToolPhase.START, turns[1].toolPhase)
    }

    @Test
    fun aTransientRowWithNoEntryYetIsAppendedAtTheEnd() {
        val turns = ConversationView.turns(
            listOf(turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一")),
            thinkingVisible = true,
            thinkingTurnId = "t-2",
            tool = ToolRowState("t-2", "尚未映射的动作", ToolPhase.END),
        )

        assertEquals(listOf("t-1", "t-2"), turns.map { it.turnId })
        assertTrue("a new turn may think before its utterance is stored", turns[1].thinkingVisible)
        assertEquals("尚未映射的动作", turns[1].toolLabel)
        assertEquals(ToolPhase.END, turns[1].toolPhase)
    }

    // ---------------------------------------------------------------- the incremental render plan (0.24 S4 Task 17)

    @Test
    fun anAnswerRevisionIsAnInPlaceUpdateRatherThanARebuild() {
        val first = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "今天"),
            ),
        )
        val second = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "今天晴。"),
            ),
        )

        val plan = ConversationView.plan(first, second)

        assertTrue("partial answer text must patch the row, never rebuild the list: $plan",
            plan is ConversationView.RenderPlan.Update)
        assertTrue((plan as ConversationView.RenderPlan.Update).textChanged)
    }

    @Test
    fun theFinalShowReplacesTheSameRowAsAnUpdate() {
        val partial = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "今天晴"),
            ),
        )
        val final = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "今天晴。"),
            ),
            liveEntryId = "say-h1",
        )

        val plan = ConversationView.plan(partial, final)

        assertTrue("the final SHOW is the same entry row: $plan", plan is ConversationView.RenderPlan.Update)
        val update = plan as ConversationView.RenderPlan.Update
        assertTrue(update.textChanged)
        assertTrue("the final only adds the live emphasis", update.liveChanged)
    }

    @Test
    fun aNewRowNeedsARebuildButAnIdenticalModelDoesNot() {
        val one = ConversationView.turns(listOf(turnEntry("asr-t1", ConversationRole.USER, "t-1")))
        val two = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
            ),
        )

        assertTrue(ConversationView.plan(null, one) is ConversationView.RenderPlan.Rebuild)
        assertTrue("a new answer row changes the list structure",
            ConversationView.plan(one, two) is ConversationView.RenderPlan.Rebuild)
        assertTrue(ConversationView.plan(two, two) is ConversationView.RenderPlan.Unchanged)
    }

    @Test
    fun theSameAsrTextUpdateIsNotCountedAsNewText() {
        val first = ConversationView.turns(listOf(turnEntry("asr-t1", ConversationRole.USER, "t-1", "帮我看")))
        val same = ConversationView.turns(listOf(turnEntry("asr-t1", ConversationRole.USER, "t-1", "帮我看")))

        assertTrue(ConversationView.plan(first, same) is ConversationView.RenderPlan.Unchanged)
    }

    @Test
    fun theRevealPassIsCoalescedToTheOfficialFiftyMillisecondCadence() {
        val coalescer = RevealCoalescer()

        assertTrue("the first revision always passes", coalescer.allow(1_000_000_000L))
        assertFalse("a burst inside 50 ms does not run a second layout/reveal pass",
            coalescer.allow(1_020_000_000L))
        assertFalse(coalescer.allow(1_049_000_000L))
        assertTrue("at the official 50 ms boundary the pass runs again",
            coalescer.allow(1_050_000_000L))
        assertTrue("the cap is the task's ≤100 ms bound", RevealCoalescer.INTERVAL_NANOS <= 100_000_000L)
    }

    // ---------------------------------------------------------------- official GIFs and Markdown (Task 19)

    @Test
    fun theAsrLoadingRowIsATransientItemInTheWaitingStatusesOnly() {
        // Status 0/1 have no utterance yet: the official `imv_asr_loading` is the whole current item. Status 2 replaces
        // it with the wearer's text, so the transient row disappears (spec §5.6).
        val waiting = ConversationView.turns(emptyList(), asrLoadingVisible = true)
        assertEquals(1, waiting.size)
        assertTrue(waiting.single().asrLoadingVisible)
        assertNull(waiting.single().asrText)
        assertNull(waiting.single().answerText)

        val answered = ConversationView.turns(
            listOf(turnEntry("asr-t1", ConversationRole.USER, "t-1", "帮我看")),
            asrLoadingVisible = false,
        )
        assertEquals("status 2 starts with the stored wearer line", 1, answered.size)
        assertFalse(answered.single().asrLoadingVisible)
        assertTrue("appearing/disappearing is structural",
            ConversationView.plan(waiting, answered) is ConversationView.RenderPlan.Rebuild)
    }

    @Test
    fun theAsrLoadingRowFollowsTheLatestTurnWithoutReorderingExistingItems() {
        val turns = ConversationView.turns(
            listOf(
                turnEntry("asr-t1", ConversationRole.USER, "t-1", "第一轮"),
                turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
            ),
            asrLoadingVisible = true,
        )

        assertEquals("the previous answer is not moved", listOf("t-1", null), turns.map { it.turnId })
        assertFalse(turns[0].asrLoadingVisible)
        assertTrue("the waiting indicator belongs to the current (not-yet-stored) turn",
            turns[1].asrLoadingVisible)
        assertEquals("答一", turns[0].answerText)
    }

    @Test
    fun theFinalAnswerIsMarkedForMarkdownWhileThePartialStaysPlain() {
        val entries = listOf(
            turnEntry("asr-t1", ConversationRole.USER, "t-1", "帮我总结"),
            turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "**重点**"),
        )
        val partial = ConversationView.turns(entries)
        val final = ConversationView.turns(entries, finalAnswerEntryIds = setOf("say-h1"))

        assertFalse("a streamed revision is not the Markwon row", partial.single().answerFinal)
        assertTrue("the final SHOW marks the same entry", final.single().answerFinal)
        assertEquals("the markdown render is style only: the row structure is unchanged",
            ConversationView.RenderPlan.Update::class.java,
            ConversationView.plan(partial, final)::class.java)
        val update = ConversationView.plan(partial, final) as ConversationView.RenderPlan.Update
        assertTrue("the final flag is part of the in-place update", update.answerFinalChanged)
    }

    @Test
    fun aFinalAnswerStaysMarkedAfterLaterTurnsAndDuplicateRenders() {
        val first = listOf(
            turnEntry("asr-t1", ConversationRole.USER, "t-1", "问一"),
            turnEntry("say-h1", ConversationRole.ASSISTANT, "t-1", "答一"),
        )
        val later = first + listOf(turnEntry("asr-t2", ConversationRole.USER, "t-2", "问二"))
        val turn = ConversationView.turns(later, finalAnswerEntryIds = setOf("say-h1")).first()

        assertTrue("the already-final answer keeps its Markdown path when a later turn arrives", turn.answerFinal)
        val model = ConversationView.turns(first, finalAnswerEntryIds = setOf("say-h1"))
        assertTrue("a duplicate render is not new content",
            ConversationView.plan(model, model) is ConversationView.RenderPlan.Unchanged)
    }
}
