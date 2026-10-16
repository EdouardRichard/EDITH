package edith.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ConversationStoreTest {

    @Test fun `append adds transcript segment with correct origin`() {
        val store = ConversationStore()
        store.appendUser("hello")

        val snapshot = store.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(SegmentOrigin.TRANSCRIPT, snapshot[0].origin)
        assertTrue(snapshot[0].trusted)
        assertEquals("hello", snapshot[0].text)
    }

    @Test fun `snapshot returns immutable copy — later appends don't affect earlier snapshot`() {
        val store = ConversationStore()
        store.appendUser("first")

        val before = store.snapshot()
        store.appendAssistant("second")

        assertEquals(1, before.size)
        assertEquals("first", before[0].text)

        val after = store.snapshot()
        assertEquals(2, after.size)
    }

    @Test fun `two stores are independent`() {
        val storeA = ConversationStore()
        val storeB = ConversationStore()

        storeA.appendUser("only in A")

        assertEquals(1, storeA.snapshot().size)
        assertEquals(0, storeB.snapshot().size)
    }

    @Test fun `replaceWithSummary marks summary with SUMMARY origin and clears previous`() {
        val store = ConversationStore()
        store.appendUser("old message 1")
        store.appendAssistant("old message 2")
        store.appendToolRound("old tool result")

        assertEquals(3, store.snapshot().size)

        store.replaceWithSummary("compressed context")

        val snapshot = store.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(SegmentOrigin.SUMMARY, snapshot[0].origin)
        assertEquals(false, snapshot[0].trusted)
        assertEquals("compressed context", snapshot[0].text)
    }

    @Test fun `appendUser and appendAssistant both produce TRANSCRIPT origin`() {
        val store = ConversationStore()
        store.appendUser("user says")
        store.appendAssistant("assistant replies")

        val snapshot = store.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(SegmentOrigin.TRANSCRIPT, snapshot[0].origin)
        assertEquals(SegmentOrigin.TRANSCRIPT, snapshot[1].origin)
        assertEquals("user says", snapshot[0].text)
        assertEquals("assistant replies", snapshot[1].text)
    }

    @Test fun `appendToolRound produces TOOL_ROUND origin`() {
        val store = ConversationStore()
        store.appendToolRound("tool executed successfully")

        val snapshot = store.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(SegmentOrigin.TOOL_ROUND, snapshot[0].origin)
        assertTrue(snapshot[0].trusted)
        assertEquals("tool executed successfully", snapshot[0].text)
    }
}
