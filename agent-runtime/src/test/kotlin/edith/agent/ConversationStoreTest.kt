package edith.agent

import edith.plugin.MessageRole
import edith.plugin.ModelHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // ---- Phase 5 ----

    @Test fun `appendToolCall and appendToolOutput carry their identities`() {
        val store = ConversationStore()
        store.appendUser("ask")
        store.appendAssistant("calling")
        store.appendToolCall("c1", "record_note", """{"t":"x"}""", "fc_1")
        store.appendToolOutput("c1", "noted")

        val history = store.history()
        assertEquals(4, history.size)
        assertEquals("fc_1", (history[2] as ModelHistoryItem.ToolCall).itemId)
        assertEquals("noted", (history[3] as ModelHistoryItem.ToolOutput).output)
    }

    @Test fun `a summary is stored with SUMMARY origin and rendered with the boundary on the wire`() {
        val store = ConversationStore()
        store.appendUser("old")
        store.appendAssistant("reply")
        store.appendSummary("先前对话摘要")

        // 归属来自元数据，不是文本匹配。
        assertTrue(store.snapshot().any { it.origin == SegmentOrigin.SUMMARY })
        // 正文本身保持干净（库里不存边界）。
        assertTrue(store.snapshot().none { it.origin == SegmentOrigin.SUMMARY && it.text.contains(SummaryBoundary.OPEN) })
        // 线缆形状带上边界。
        val wireText = (store.history().last() as ModelHistoryItem.Text).text
        assertTrue(wireText.startsWith(SummaryBoundary.OPEN), wireText)
    }

    @Test fun `a user message that merely starts with the boundary text is not a summary`() {
        // Review Focus 1：归属不得由文本推断。
        val store = ConversationStore()
        store.appendUser("${SummaryBoundary.OPEN} 我说的是这个")

        assertEquals(SegmentOrigin.TRANSCRIPT, store.snapshot().single().origin)
        assertEquals(0, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY })
    }

    @Test fun `dropOldestTurns drops whole turns and never the last one`() {
        val store = ConversationStore()
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendUser("u2"); store.appendAssistant("a2")

        val dropped = store.dropOldestTurns(maxItems = 1)

        assertEquals(2, dropped, "one whole turn is two items")
        assertEquals(listOf("u2", "a2"), store.history().filterIsInstance<ModelHistoryItem.Text>().map { it.text })
    }

    @Test fun `a tool round inside a turn goes with its turn, never orphaned`() {
        // Review Focus 3：绝不发出孤儿 tool output。
        val store = ConversationStore()
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendToolCall("c1", "t", "{}"); store.appendToolOutput("c1", "x")
        store.appendUser("u2"); store.appendAssistant("a2")

        store.dropOldestTurns(maxItems = 2)

        val history = store.history()
        assertEquals(0, history.count { it is ModelHistoryItem.ToolCall })
        assertEquals(0, history.count { it is ModelHistoryItem.ToolOutput })
        assertEquals(MessageRole.USER, (history.first() as ModelHistoryItem.Text).role)
    }

    @Test fun `a summary at the head is not a user turn and survives eviction`() {
        // Review Focus 5：摘要不是回合，也不是"待裁的旧回合"。
        val store = ConversationStore()
        store.appendSummary("摘要")
        store.appendUser("u1"); store.appendAssistant("a1")

        store.dropOldestTurns(maxItems = 1)

        assertTrue(store.snapshot().any { it.origin == SegmentOrigin.SUMMARY })
    }

    @Test fun `a summary that is no longer at the head is superseded and dropped`() {
        // 不变式：SUMMARY 只能在首位。回合之后的摘要说明它前面还有更早的回合 ⇒ 已被更新的摘要取代。
        val store = ConversationStore()
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendSummary("旧摘要")
        store.appendUser("u2"); store.appendAssistant("a2")

        store.dropOldestTurns(maxItems = 3)

        assertEquals(0, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY })
    }

    @Test fun `the envelope is enforced on append`() {
        val store = ConversationStore(maxItems = 2)
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendUser("u2")

        assertEquals(2, store.size(), "the store must stay inside its envelope")
    }

    @Test fun `maxItems must be at least one`() {
        assertFailsWith<IllegalArgumentException> { ConversationStore(maxItems = 0) }
    }
}
