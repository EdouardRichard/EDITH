package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationEntry
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
    fun everyLineSitsOnTheLeftLikeTheOfficialPanel() {
        val rows = ConversationView.rows(
            listOf(entry("a", ConversationRole.USER), entry("b", ConversationRole.ASSISTANT)), liveEntryId = null)

        assertTrue("the wearer's own words are left-aligned as well in 0.23: $rows",
            rows.all { !it.alignedToEnd })
    }

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

    @Test
    fun theHeaderNamesTheRouteAndSaysWhenTheAssistantIsWorking() {
        assertEquals("WAKE 上下文", ConversationView.headerText(AssistantRoute.WAKE, busy = false))
        assertEquals("PROACTIVE 上下文", ConversationView.headerText(AssistantRoute.PROACTIVE, busy = false))
        assertEquals("WAKE 上下文 · 处理中", ConversationView.headerText(AssistantRoute.WAKE, busy = true))
    }

    @Test
    fun thePromptLineSaysSomethingDifferentWhenThereIsNothingToShow() {
        assertEquals("等待提示", ConversationView.statusText(0))
        assertFalse(ConversationView.statusText(3) == ConversationView.statusText(0))
    }
}
