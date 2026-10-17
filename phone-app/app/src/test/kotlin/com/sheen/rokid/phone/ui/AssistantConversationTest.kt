package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantConversationTest {

    @Test fun `the assistant is on the left and the wearer on the right`() {
        assertEquals(BubbleSide.START, sideFor(ConversationRole.ASSISTANT))
        assertEquals(BubbleSide.END, sideFor(ConversationRole.USER))
    }

    @Test fun `the chat header names the route in full`() {
        assertEquals("唤醒对话", routeLabel(AssistantRoute.WAKE))
        assertEquals("主动辅助对话", routeLabel(AssistantRoute.PROACTIVE))
    }

    @Test fun `the refresh icon has a description`() {
        assertEquals("刷新对话", REFRESH_DESCRIPTION)
    }

    @Test fun `each side carries a visible role description for its avatar`() {
        assertEquals("助手", roleDescription(ConversationRole.ASSISTANT))
        assertEquals("你", roleDescription(ConversationRole.USER))
    }

    @Test fun `the bubble speaks its speaker for screen readers`() {
        // The avatar is visible, but a screen reader must not depend on a decorative icon or on alignment/colour.
        assertEquals("助手说：你好", spokenMessage(ConversationRole.ASSISTANT, "你好"))
        assertEquals("你说：走吧", spokenMessage(ConversationRole.USER, "走吧"))
    }

    @Test fun `bubbles may take at most eighty percent of the row`() {
        assertEquals(0.8f, BUBBLE_MAX_WIDTH_FRACTION, 0.0001f)
    }

    @Test fun `the list follows only when the reader is already at the bottom`() {
        assertTrue(shouldAutoScroll(atBottom = true, previousCount = 2, newCount = 3))
        assertFalse(shouldAutoScroll(atBottom = false, previousCount = 2, newCount = 3))
        assertFalse(shouldAutoScroll(atBottom = true, previousCount = 3, newCount = 3))
        assertFalse(shouldAutoScroll(atBottom = true, previousCount = 3, newCount = 2))
    }

    @Test fun `a new entry is followed even when the bounded log keeps forty entries`() {
        // The cap evicts the oldest entry, so the count stops rising; the last id is what still changes.
        assertTrue(shouldFollowNewEntry(atBottom = true, previousLastId = "h-40", currentLastId = "h-41"))
        assertFalse(shouldFollowNewEntry(atBottom = true, previousLastId = "h-41", currentLastId = "h-41"))
        assertFalse(shouldFollowNewEntry(atBottom = false, previousLastId = "h-40", currentLastId = "h-41"))
        assertFalse(shouldFollowNewEntry(atBottom = true, previousLastId = null, currentLastId = null))
        assertTrue(shouldFollowNewEntry(atBottom = true, previousLastId = null, currentLastId = "h-1"))
    }

    @Test fun `the reader is at the bottom when the previous last entry is still visible`() {
        // Empty log: the first entry must be followed.
        assertTrue(wasAtBottom(emptyList(), previousLastId = null))
        assertTrue(wasAtBottom(listOf("h-40"), previousLastId = "h-40"))
        assertFalse(wasAtBottom(listOf("h-38"), previousLastId = "h-40"))
        // A list that is not laid out yet is not a reader who is reading older messages; only the route-opening
        // effect scrolls then, and the follow gate is not the authority for the first frame.
        assertFalse(wasAtBottom(emptyList(), previousLastId = "h-40"))
    }

    @Test fun `a fresh view starts at the newest entry`() {
        assertNull(initialScrollTarget(0))
        assertEquals(0, initialScrollTarget(1))
        assertEquals(2, initialScrollTarget(3))
    }
}
