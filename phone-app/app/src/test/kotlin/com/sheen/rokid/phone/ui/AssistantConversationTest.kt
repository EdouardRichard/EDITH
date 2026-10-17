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
    }

    @Test fun `a fresh view starts at the newest entry`() {
        assertNull(initialScrollTarget(0))
        assertEquals(0, initialScrollTarget(1))
        assertEquals(2, initialScrollTarget(3))
    }
}
