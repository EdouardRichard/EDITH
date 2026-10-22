package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnTrimmerTest {

    private fun sys(text: String = "sys") = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, text))
    private fun frame(vararg flow: Segment) = RequestFrame(sys(), flow.toList(), emptyList())
    private fun user(text: String) = Segment(SegmentOrigin.USER_INPUT, true, text)
    private fun assistant(text: String) = Segment(SegmentOrigin.TRANSCRIPT, true, text, SegmentRole.ASSISTANT)
    private fun userTurn(text: String) = Segment(SegmentOrigin.TRANSCRIPT, true, text, SegmentRole.USER)

    @Test fun `the fixed order is declared and compression is last`() {
        // 0.25 Task 13 (R69): the type-aware cut ⓪ is inserted before D97's ①, as an explicit extension of D81.
        assertEquals(listOf("TYPE_AWARE", "OLDEST_TURNS", "MEMORY_EVIDENCE", "COMPRESSION"), TrimOrder.ORDER)
        assertEquals(
            listOf("TYPE_AWARE", "OLDEST_TURNS", "MEMORY_EVIDENCE"),
            TrimOrder.COMPRESSION_AFTER,
        )
    }

    @Test fun `a frame already inside the limit is untouched`() {
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitTokens = 1_000_000)
        assertEquals(f, r.kept)
        assertEquals(0, r.droppedTurns)
        assertEquals(emptyList(), r.droppedOrigins)
    }

    @Test fun `step one drops the OLDEST turns first and keeps the current input`() {
        // H2 + H3 步骤 ①
        // 口径复算（TokenMeter token 单位 + 32/条，另加固定 32）："u1"/"a1"/"u2"/"a2"/"now" 各 1 token。
        // 初始 = 32 + 33(sys) + 5×33 = 230；裁掉第一回合（u1+a1，66）后 = 164。
        // limitTokens 必须落在 [164, 229] 才只掉一回合 ⇒ 取 200。
        val f = frame(userTurn("u1"), assistant("a1"), userTurn("u2"), assistant("a2"), user("now"))
        val r = TurnTrimmer().trim(f, limitTokens = 200)

        assertEquals(1, r.droppedTurns)
        assertTrue(r.kept.conversationFlow.any { it.origin == SegmentOrigin.USER_INPUT })
        assertTrue(r.kept.conversationFlow.none { it.text == "u1" })
        assertTrue(r.kept.conversationFlow.any { it.text == "u2" }, "只裁最旧的一回合")
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.last().origin)
    }

    @Test fun `step one drops a second whole turn group when one cut is not enough`() {
        // 多组淘汰：检验降序删除的索引不漂（reviewer 点名的风险点）。
        // 口径复算：总 = 32 + 33(sys) + 6×33 = 263；裁第 1 组 = 197；裁第 2 组 = 131。
        // limitTokens 必须落在 [131, 196] 才会连裁两组却留下最后一组 ⇒ 取 180。
        val f = frame(
            userTurn("u1"), assistant("a1"),
            userTurn("u2"), assistant("a2"),
            userTurn("u3"), assistant("a3"),
            user("now"),
        )
        val r = TurnTrimmer().trim(f, limitTokens = 180)

        assertEquals(2, r.droppedTurns)
        assertEquals(listOf(userTurn("u3"), assistant("a3"), user("now")), r.kept.conversationFlow)
    }

    @Test fun `step two drops memory evidence whole, after step one is exhausted`() {
        // H3 步骤 ②
        // 口径复算：总 = 32 + 33(sys) + 107(「m」×300 = 75 token + 32) + 33(now) = 205；limit 120 ⇒ 掉记忆后 98。
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "m".repeat(300))
        val f = frame(memory, user("now"))
        val r = TurnTrimmer().trim(f, limitTokens = 120)

        assertEquals(0, r.droppedTurns, "没有旧回合可裁")
        assertTrue(r.droppedOrigins.contains(SegmentOrigin.MEMORY_EVIDENCE))
        assertTrue(r.kept.conversationFlow.none { it.origin == SegmentOrigin.MEMORY_EVIDENCE })
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.last().origin)
    }

    @Test fun `memory is dropped from the least relevant end first`() {
        // 检索是相似度降序 ⇒ 最不相关的在最后。
        // 口径复算（token 单位）："most"/"less" 各 1 token，"least" 是 5 个 ASCII ⇒ 2 token。
        // 初始 = 32 + 33(sys) + 33(most) + 33(less) + 34(least) + 33(now) = 198；掉 "least" 后 = 164。
        // limitTokens 必须落在 [164, 197] 才能既真的掉一条记忆、又留下 "most" ⇒ 取 180。
        val f = frame(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "most"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "less"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "least"),
            user("now"),
        )
        val r = TurnTrimmer().trim(f, limitTokens = 180)

        val keptMemory = r.kept.conversationFlow.filter { it.origin == SegmentOrigin.MEMORY_EVIDENCE }.map { it.text }
        assertTrue("least" !in keptMemory, keptMemory.toString())
        assertTrue("most" in keptMemory, keptMemory.toString())
    }

    @Test fun `the report says compression is owed when only the input fits`() {
        // H3 步骤 ③ 的触发条件：①②都做完了还超 ⇒ 压缩还欠着（Review Focus 2）。
        // 口径复算：总 = 32 + 33(sys) + 33(u1) + 33(a1) + 33(now) = 164；limit 60 ⇒ 只剩 now = 98。
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitTokens = 60)

        assertTrue(r.compressionOwed)
        assertEquals(1, r.kept.conversationFlow.size)
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.single().origin)
    }

    @Test fun `a summary is not dropped as an old turn but still counts toward the budget`() {
        // Review Focus 5：摘要落在“本轮发言之前”的回合组*内部*，① 会把它前面的整个回合组一起裁；
        // 但如果摘要**不计入 total()**，这一刀就不会落，摘要之外的保留集也更大。
        // 口径复算（token 单位）：总 = 32 + 33(sys) + 33(u1) + 34(摘要，2 token) + 33(a1) + 33(now) = 198；
        // ① 裁掉回合组 u1+a1（66）后 = 132。摘要计入时 132 > 110 ⇒ compressionOwed=true；
        // 摘要豁免预算时裁后 98 ≤ 110 ⇒ compressionOwed=false ⇒ 该断言就是判别点。
        val f = frame(
            userTurn("u1"),
            Segment(SegmentOrigin.SUMMARY, false, "摘要"),
            assistant("a1"),
            user("now"),
        )
        val r = TurnTrimmer().trim(f, limitTokens = 110)

        // 摘要本身绝不是被裁的那一条。
        assertTrue(r.kept.conversationFlow.any { it.origin == SegmentOrigin.SUMMARY },
            "摘要不得作为旧回合被裁：${r.kept.conversationFlow}")
        // 而它必须计入预算 —— 否则这一刀根本不会落。
        assertTrue(r.compressionOwed, "摘要必须计入 total()（未计入时不该还欠压缩）")
    }

    @Test fun `runtime state is never evicted even when whole turns are`() {
        // Task 12 (R59): RUNTIME_STATE is protected exactly like USER_INPUT, but it still counts toward the budget.
        // 口径复算（token 单位）：runtime 27 个 ASCII ⇒ 7 token + 32 = 39。
        // 总 = 32 + 33(sys) + 33(u1) + 33(a1) + 39(runtime) + 33(now) = 203；裁掉 u1+a1 后 = 137。
        // limitTokens 取 200 ⇒ 只掉一回合、runtime 与 now 仍在。
        val runtime = Segment(SegmentOrigin.RUNTIME_STATE, false, "foreground=com.x revision=4")
        val f = frame(userTurn("u1"), assistant("a1"), runtime, user("now"))

        val r = TurnTrimmer().trim(f, limitTokens = 200)

        assertEquals(1, r.droppedTurns)
        assertTrue(r.kept.conversationFlow.any { it.origin == SegmentOrigin.RUNTIME_STATE },
            "runtime state must survive turn eviction: ${r.kept.conversationFlow}")
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.last().origin)
    }

    @Test fun `the system slot is never trimmed`() {
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitTokens = 1)
        assertEquals(f.systemSegments, r.kept.systemSegments)
    }
}
