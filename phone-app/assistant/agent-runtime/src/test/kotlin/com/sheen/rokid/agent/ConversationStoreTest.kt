package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
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

    @Test fun `a second summary replaces the first in the same call, leaving exactly one at the head`() {
        // 不变式：SUMMARY 只能在首位，且任何时刻最多一条。本用例钉住**取代是原子的**：`appendSummary` 在**同一次
        // 调用**里把被取代的旧摘要删掉，所以 `[新, 旧, …]` 这个中间态对任何观察者都不存在（第二次压缩后紧接着的
        // 那一回合因此也不会拿到一条被错标成 assistant 回合的旧摘要）。变异：把 `insertSummaryAtHead` 里的
        // `dropSupersededSummaries()` 变成 no-op，本例与 `StoreSessionWiringTest` 的「第二次压缩后那一回合」都会红。
        val store = ConversationStore()
        store.appendSummary("旧摘要")

        assertEquals(0, store.snapshot().indexOfFirst { it.origin == SegmentOrigin.SUMMARY },
            "刚落地的摘要在首位")

        store.appendSummary("更新的摘要")

        // 被取代的旧摘要在**同一次 appendSummary** 里就没了 —— 不是"被挤到 index 1 等下一次 sweep"。
        assertEquals(1, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY },
            "两次压缩之间不能观察到两条摘要：${store.snapshot()}")
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

        // 取代是同一次调用完成的（见上一个用例），所以"落地的那条摘要在自己的 append 里不被删掉"必须显式钉住：
        // 任何"先删光所有摘要、再插入新的"或把 index 0 也算进 sweep 的写法都会在这里红。
        assertEquals(1, store.snapshot().count { it.origin == SegmentOrigin.SUMMARY },
            "落地的摘要不能被**它自己那次** append 扫掉：${store.snapshot()}")

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

    @Test fun `two summaries cannot coexist, and the newer one is the only stored summary`() {
        // **State re-derived after atomic supersession.** 旧前提（"两次 `appendSummary` 会留下一条 index 1 的摘要等
        // sweep 删"）在原子取代下不复存在：第二次调用就删掉了第一次那条。所以这里钉住的判断是"较新的摘要是唯一的
        // 幸存者，且它从落地起就在首位"—— 而不是"旧摘要曾经在 index 1 待过"。断言本身与原子化之前逐字相同。
        val store = ConversationStore()
        store.appendSummary("旧摘要")
        store.appendSummary("更新的摘要")
        store.appendUser("u2")

        val summaries = store.snapshot().filter { it.origin == SegmentOrigin.SUMMARY }
        assertEquals(1, summaries.size, "被取代的摘要必须被删除，不能留在表里：${store.snapshot()}")
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

    @Test fun `removeOldestTurns finds a single atomic summary and counts no turn`() {
        // **State re-derived after atomic supersession.** 旧用例的前提（"两次 `appendSummary` 会留下一条 index 1 的
        // 摘要，由 `removeOldestTurns` 的 sweep 移除并计入返回值"）随原子取代消失：第二次调用已删掉旧摘要。表里
        // 只有摘要、没有回合，所以 count-based 移除什么都不该拿走、返回 0 —— 这保住了原来的判断"摘要不是回合，
        // 两个方向都不计入"。为将来可能出现非首位摘要的路径保留的 sweep 仍在 `removeOldestTurns` 里（经公开 API
        // 如今不可达）。
        val store = ConversationStore()
        store.appendSummary("旧摘要")
        store.appendSummary("更新的摘要")

        val removed = store.removeOldestTurns(1)

        assertEquals(0, removed, "只有一条摘要、没有回合：count-based 移除不该拿走任何东西")
        val survivors = store.snapshot().filter { it.origin == SegmentOrigin.SUMMARY }
        assertEquals(1, survivors.size, "留下的只能是更新的那条：${store.snapshot()}")
        assertEquals(SummaryBoundary.render("更新的摘要"), survivors[0].text)
        assertEquals(listOf(SummaryBoundary.render("更新的摘要")), texts(store))
    }

    @Test fun `removeOldestTurns keeps the head summary and counts only the turn items`() {
        // Contract-mandated shape: a head summary plus its turns, evicted by count. Its counterpart — the same call
        // with only a summary present instead — is `removeOldestTurns finds a single atomic summary and counts no turn`.
        // **Label re-derived after atomic supersession**: the count is the turn items alone, because there is no longer
        // a displaced summary to sweep into it (the old name "still counts a swept one" asserted a mechanism that can
        // no longer happen). The assertions are byte-identical, so the judgement — a head summary survives and is never
        // counted as a turn — is preserved. Same body as `removeOldestTurns keeps a head summary and its turns` above;
        // kept rather than merged, since existing cases may be reconciled but not deleted.
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
