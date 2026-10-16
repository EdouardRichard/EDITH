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
        // 线缆形状是同一份文本：两个视图不再有一份"干净正文"。摘要在**首位**（它必须在那儿才不被取代），
        // 所以按归属找到它在线缆上的那一份，而不是取 `last()`。
        val wireText = (store.history().single { it is ModelHistoryItem.Text && it.text == summary.text }
            as ModelHistoryItem.Text).text
        assertEquals(summary.text, wireText)
        assertEquals(SegmentOrigin.SUMMARY, store.snapshot().first().origin)
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
        // 不变式：SUMMARY 只能在首位，且任何时刻最多一条。`appendSummary` 把摘要插到首位，所以**正常的
        // append 序列不再能产生非首位摘要**：后来落地的摘要在首位，先落地的那条被挤到 index 1 —— 这（外加
        // 一个紧随的回合，触发 `add` 的 sweep）是唯一合法的构造，也是本用例钉住的形状。移除它是 `add` 的
        // sweep 的职责：删掉 `enforceEnvelope` 里的 `dropSupersededSummaries()`，压在 index 1 的旧摘要会留下。
        val store = ConversationStore()
        store.appendSummary("旧摘要")

        assertEquals(0, store.snapshot().indexOfFirst { it.origin == SegmentOrigin.SUMMARY },
            "刚落地的摘要在首位")

        store.appendSummary("更新的摘要")

        assertEquals(2, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY },
            "旧摘要没有被删，只是被挤到 index 1（`appendSummary` 跳过自己的 sweep）")
        assertEquals(SummaryBoundary.render("更新的摘要"), store.snapshot()[0].text,
            "首位是更新的那条")

        store.appendUser("u2")

        val summaries = store.snapshot().filter { it.origin == SegmentOrigin.SUMMARY }
        assertEquals(1, summaries.size, "最多只允许一条 SUMMARY：${store.snapshot()}")
        assertEquals(SummaryBoundary.render("更新的摘要"), summaries[0].text, "留下的必须是更新的那条")
        assertEquals(SegmentOrigin.SUMMARY, store.snapshot().first().origin, "且仍在首位")
    }

    @Test fun `a landed summary survives the next append because it lands at the head`() {
        // 这正是 ③ 这一刀的意义：摘要是为了让**下一轮**从压缩态起步。如果摘要在尾部落地，下一次 append 会
        // 由 `enforceEnvelope` 把它当"已被取代"扫掉，store 常常一个摘要都不剩。
        val store = ConversationStore()
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendSummary("摘要")

        val landed = store.snapshot()
        assertEquals(SegmentOrigin.SUMMARY, landed[0].origin, "落地的摘要必须在首位：$landed")
        // 摘要存的是**渲染过**的文本（边界在入库时渲染一次），所以这里比较渲染串而不是裸正文。
        assertEquals(listOf(SummaryBoundary.render("摘要"), "u1", "a1"), texts(store))

        // 下一轮开始：新回合 append 会触发 sweep + 淘汰，摘要必须还在。
        store.appendUser("u2"); store.appendAssistant("a2")

        assertTrue(store.snapshot().any { it.origin == SegmentOrigin.SUMMARY },
            "刚落地的摘要不能被下一次 append 扫掉：${store.snapshot()}")
        assertEquals(SegmentOrigin.SUMMARY, store.snapshot().first().origin, "摘要仍在首位")
        assertEquals(listOf(SummaryBoundary.render("摘要"), "u1", "a1", "u2", "a2"), texts(store))
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

    @Test fun `a superseded summary is removed by the sweep, never promoted to the head`() {
        // 旧形状 `u1,a1,appendSummary(旧),u2,a2,appendSummary(新)` 在首位插入下不再产生非首位摘要 —— 实测
        // （见 task-5 报告）：摘要落地后 append 回合，摘要仍在 index 0。所以"非首位摘要"只能由**更新的摘要
        // 抢占首位**造出来，也只有那一次 append 会把它扫掉。这里由它后面的那个 append 触发。
        val store = ConversationStore()
        store.appendSummary("旧摘要")
        store.appendSummary("更新的摘要")
        store.appendUser("u2")

        val summaries = store.snapshot().filter { it.origin == SegmentOrigin.SUMMARY }
        assertEquals(1, summaries.size, "被取代的摘要必须被 sweep 掉，不能留在表里：${store.snapshot()}")
        assertEquals(SummaryBoundary.render("更新的摘要"), summaries[0].text)
        assertEquals(listOf(SummaryBoundary.render("更新的摘要"), "u2"), texts(store))
    }

    // ---- Phase 5 Task 5 review fixes: count-based removal ----

    /** The text shape of a store, for the assertions below. */
    private fun texts(store: ConversationStore): List<String> =
        store.history().filterIsInstance<ModelHistoryItem.Text>().map { it.text }

    /** Two finished turns plus a pending third: the shape a compressor meets at a turn's end. */
    private fun threeTurnStore(): ConversationStore = ConversationStore().also {
        it.appendUser("u1"); it.appendAssistant("a1")
        it.appendUser("u2"); it.appendAssistant("a2")
        it.appendUser("u3")
    }

    @Test fun `removeOldestTurns drops exactly the requested count of whole turns`() {
        val store = threeTurnStore()

        val removed = store.removeOldestTurns(1)

        assertEquals(2, removed, "one whole turn is two items")
        assertEquals(listOf("u2", "a2", "u3"), texts(store))
    }

    @Test fun `removeOldestTurns is a count, not a target size`() {
        // The distinction this method exists for: `dropOldestTurns(1)` means "until size <= 1" and leaves one
        // message; `removeOldestTurns(1)` means "exactly one turn" and leaves three. Passing a count to the former
        // over-compacts, and over-compaction is what silently loses messages that were never written to memory.
        val bySize = threeTurnStore()
        val byCount = threeTurnStore()

        assertEquals(4, bySize.dropOldestTurns(1))
        assertEquals(listOf("u3"), texts(bySize))

        assertEquals(2, byCount.removeOldestTurns(1))
        assertEquals(listOf("u2", "a2", "u3"), texts(byCount))
    }

    @Test fun `removeOldestTurns never drops the last user turn`() {
        val store = threeTurnStore()

        val removed = store.removeOldestTurns(99)

        assertEquals(4, removed, "only the two turns before the pending one may go")
        assertEquals(listOf("u3"), texts(store))
    }

    @Test fun `removeOldestTurns of zero changes nothing`() {
        val store = threeTurnStore()

        assertEquals(0, store.removeOldestTurns(0))
        assertEquals(5, store.size())
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3"), texts(store))
    }

    @Test fun `removeOldestTurns takes a tool round with its turn`() {
        val store = ConversationStore()
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendToolCall("c1", "t", "{}"); store.appendToolOutput("c1", "x")
        store.appendUser("u2"); store.appendAssistant("a2")

        val removed = store.removeOldestTurns(1)

        assertEquals(4, removed)
        assertEquals(0, store.history().count { it is ModelHistoryItem.ToolCall })
        assertEquals(0, store.history().count { it is ModelHistoryItem.ToolOutput })
        assertEquals(MessageRole.USER, (store.history().first() as ModelHistoryItem.Text).role)
        assertEquals(listOf("u2", "a2"), texts(store))
    }

    @Test fun `removeOldestTurns keeps a head summary and its turns`() {
        // The shape production actually reaches: a summary at the head survives the sweep of a count-based removal,
        // and the returned count is the turn items that left — the summary is not a turn.
        val store = ConversationStore()
        store.appendSummary("摘要")
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendUser("u2"); store.appendAssistant("a2")

        val removed = store.removeOldestTurns(1)

        assertEquals(2, removed, "一个整回合两条消息；摘要不是回合，不受影响")
        assertEquals(1, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY })
        assertEquals(SegmentOrigin.SUMMARY, store.snapshot().first().origin)
        // `texts()` would see the summary too: it is a Text item. Only the transcript turns moved.
        val transcript = store.snapshot().filter { it.origin == SegmentOrigin.TRANSCRIPT }.map { it.text }
        assertEquals(listOf("u2", "a2"), transcript)
        assertEquals(listOf(SummaryBoundary.render("摘要"), "u2", "a2"), texts(store))
    }

    @Test fun `removeOldestTurns also sweeps a superseded summary`() {
        // **State re-derived after the head-insert fix.** The old premise ("a trailing summary is the shape
        // `appendSummary` leaves behind") no longer exists: `appendSummary` inserts at index 0, and appending turns
        // after it leaves it at index 0 too (probed). The reachable non-head shape is a summary **displaced by a
        // newer one**, and there `removeOldestTurns`'s sweep is what removes it — with no user turn in the store the
        // sweep is the sole remover, so the returned count of 1 is entirely the swept summary.
        val store = ConversationStore()
        store.appendSummary("旧摘要")
        store.appendSummary("更新的摘要")

        val removed = store.removeOldestTurns(1)

        assertEquals(1, removed, "被取代的那条摘要由 sweep 移除，且计入返回条数")
        val survivors = store.snapshot().filter { it.origin == SegmentOrigin.SUMMARY }
        assertEquals(1, survivors.size, "留下的只能是更新的那条：${store.snapshot()}")
        assertEquals(SummaryBoundary.render("更新的摘要"), survivors[0].text)
        assertEquals(listOf(SummaryBoundary.render("更新的摘要")), texts(store))
    }

    @Test fun `removeOldestTurns keeps the head summary and still counts a swept one`() {
        // Contract-mandated shape: a head summary plus its turns, evicted by count. Its counterpart — the same call
        // with a *displaced* summary present instead — is `removeOldestTurns also sweeps a superseded summary`.
        val store = ConversationStore()
        store.appendSummary("摘要")
        store.appendUser("u1"); store.appendAssistant("a1")
        store.appendUser("u2"); store.appendAssistant("a2")

        val removed = store.removeOldestTurns(1)

        assertEquals(2, removed, "一个整回合两条消息；摘要不是回合，不受影响")
        assertEquals(1, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY })
        assertEquals(SegmentOrigin.SUMMARY, store.snapshot().first().origin)
        // `texts()` would see the summary too: it is a Text item. Only the transcript turns moved.
        val transcript = store.snapshot().filter { it.origin == SegmentOrigin.TRANSCRIPT }.map { it.text }
        assertEquals(listOf("u2", "a2"), transcript)
        assertEquals(listOf(SummaryBoundary.render("摘要"), "u2", "a2"), texts(store))
    }
}
