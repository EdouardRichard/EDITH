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
        // I-2: the boundary is rendered at **store-insert** time, so `snapshot()` returns the bounded text — the same
        // bytes `history()` sends. A clean body here would be measured clean and sent rendered (+64 B for the
        // boundary against a 32 B margin), which is a latent `ConfigurationRejected`.
        assertEquals(SummaryBoundary.render("compressed context"), snapshot[0].text)
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

    @Test fun `a summary is stored bounded and both views carry the same rendered text`() {
        val store = ConversationStore()
        store.appendUser("old")
        store.appendAssistant("reply")
        store.appendSummary("先前对话摘要")

        // 归属来自元数据，不是文本匹配。
        val summary = store.snapshot().single { it.origin == SegmentOrigin.SUMMARY }
        // I-2: 边界在入库时渲染 ⇒ snapshot() 就是线上那份文本（测量值 == 发送值）。
        assertEquals(SummaryBoundary.render("先前对话摘要"), summary.text)
        assertTrue(summary.text.contains(SummaryBoundary.OPEN), summary.text)
        // 线缆形状是同一份文本：两个视图不再有一份"干净正文"。
        val wireText = (store.history().last() as ModelHistoryItem.Text).text
        assertEquals(summary.text, wireText)
    }

    @Test fun `a body that already carries the boundary is not wrapped twice`() {
        // I-2: `appendSummary` 的 isRendered 守卫 —— 已带边界的正文必须原样入库一次。
        val store = ConversationStore()
        val bounded = SummaryBoundary.render("先前对话摘要")
        store.appendSummary(bounded)

        val text = store.snapshot().single().text
        assertEquals(bounded, text)
        assertEquals(1, text.split(SummaryBoundary.OPEN).size - 1, "边界只能出现一次：$text")
        assertEquals(1, text.split(SummaryBoundary.CLOSE).size - 1, "边界只能出现一次：$text")
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

    @Test fun `the envelope is enforced on append and drops whole turns only`() {
        val store = ConversationStore(maxItems = 4)
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendUser("u2"); store.appendAssistant("a2")
        store.appendUser("u3")

        assertEquals(3, store.size(), "the envelope must be enforced")
        // 丢掉的必须是最旧的那个**整回合**，不是半条。
        val texts = store.history().filterIsInstance<ModelHistoryItem.Text>().map { it.text }
        assertEquals(listOf("u2", "a2", "u3"), texts, "只有最旧的整回合可以走")
    }

    @Test fun `maxItems must be at least one`() {
        assertFailsWith<IllegalArgumentException> { ConversationStore(maxItems = 0) }
    }

    // ---- Phase 5 review fixes ----

    @Test fun `a stale summary after the last turn is removed by the sweep`() {
        // 唯一由 sweep（而非回合淘汰）移除摘要的形状 —— Finding 3 的漏洞就在这。
        val store = ConversationStore()
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendSummary("旧摘要")
        store.appendUser("u2"); store.appendAssistant("a2")
        store.appendSummary("更新的摘要")

        val dropped = store.dropOldestTurns(0)

        assertEquals(0, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY },
            "非首位摘要必须由 sweep 移除")
        assertEquals(3, dropped, "sweep 摘掉的摘要也要计入返回的条数")
    }

    @Test fun `an append that evicts also sweeps a superseded summary instead of promoting it`() {
        // Finding 1 的具体反例：maxItems=3 时 append 触发淘汰，不能把旧摘要顶到首位。
        // 合法终止态只能是 3 条 [u1,a1,u2]（sweep 掉摘要）或 1 条 [u2]（丢掉整个回合）；
        // 2 条需要按条裁剪（丢掉 u1 而留下孤立的 a1），整回合淘汰下不可达、也被禁止。
        val store = ConversationStore(maxItems = 3)
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendSummary("旧摘要")
        store.appendUser("u2")

        assertEquals(0, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY },
            "append 触发的淘汰必须同时 sweep，不能把被取代的摘要留成首位")
        assertEquals(3, store.size())
    }
}
