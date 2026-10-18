package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
