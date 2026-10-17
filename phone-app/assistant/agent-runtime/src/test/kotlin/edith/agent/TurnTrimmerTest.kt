package edith.agent

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
        assertEquals(listOf("OLDEST_TURNS", "MEMORY_EVIDENCE", "COMPRESSION"), TrimOrder.ORDER)
        assertEquals(listOf("OLDEST_TURNS", "MEMORY_EVIDENCE"), TrimOrder.COMPRESSION_AFTER)
    }

    @Test fun `a frame already inside the limit is untouched`() {
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 1_000_000)
        assertEquals(f, r.kept)
        assertEquals(0, r.droppedTurns)
        assertEquals(emptyList(), r.droppedOrigins)
    }

    @Test fun `step one drops the OLDEST turns first and keeps the current input`() {
        // H2 + H3 步骤 ①
        // 口径复算（UTF-8 字节 + 32/条，另加固定 32）：初始 = 32 + 35(sys) + 4×34 + 35(now) = 238；
        // 裁掉第一回合（u1+a1）后 = 170。limitBytes 必须落在 (170, 238) 才只掉一回合 ⇒ 取 200。
        val f = frame(userTurn("u1"), assistant("a1"), userTurn("u2"), assistant("a2"), user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 200)

        assertEquals(1, r.droppedTurns)
        assertTrue(r.kept.conversationFlow.any { it.origin == SegmentOrigin.USER_INPUT })
        assertTrue(r.kept.conversationFlow.none { it.text == "u1" })
        assertTrue(r.kept.conversationFlow.any { it.text == "u2" }, "只裁最旧的一回合")
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.last().origin)
    }

    @Test fun `step one drops a second whole turn group when one cut is not enough`() {
        // 多组淘汰：检验降序删除的索引不漂（reviewer 点名的风险点）。
        // 口径复算：总 = 32 + 35(sys) + 6×34(u1..a3) + 35(now) = 306；裁第 1 组 = 238；裁第 2 组 = 170。
        // limitBytes 必须落在 [170, 238) 才会连裁两组却留下最后一组 ⇒ 取 200。
        val f = frame(
            userTurn("u1"), assistant("a1"),
            userTurn("u2"), assistant("a2"),
            userTurn("u3"), assistant("a3"),
            user("now"),
        )
        val r = TurnTrimmer().trim(f, limitBytes = 200)

        assertEquals(2, r.droppedTurns)
        assertEquals(listOf(userTurn("u3"), assistant("a3"), user("now")), r.kept.conversationFlow)
    }

    @Test fun `step two drops memory evidence whole, after step one is exhausted`() {
        // H3 步骤 ②
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "m".repeat(300))
        val f = frame(memory, user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 120)

        assertEquals(0, r.droppedTurns, "没有旧回合可裁")
        assertTrue(r.droppedOrigins.contains(SegmentOrigin.MEMORY_EVIDENCE))
        assertTrue(r.kept.conversationFlow.none { it.origin == SegmentOrigin.MEMORY_EVIDENCE })
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.last().origin)
    }

    @Test fun `memory is dropped from the least relevant end first`() {
        // 检索是相似度降序 ⇒ 最不相关的在最后。
        // 口径复算：初始 = 32 + 35(sys) + 36(most) + 36(less) + 37(least) + 35(now) = 211；
        // 掉 "least" 后 = 174。limitBytes 必须落在 [174, 210] 才能既真的掉一条记忆、又留下 "most" ⇒ 取 180。
        // （brief 原值 80 < 只剩 input 的地板 102，会把三条记忆全裁光，`"most" in keptMemory` 必失败 ⇒ 测试空转。）
        val f = frame(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "most"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "less"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "least"),
            user("now"),
        )
        val r = TurnTrimmer().trim(f, limitBytes = 180)

        val keptMemory = r.kept.conversationFlow.filter { it.origin == SegmentOrigin.MEMORY_EVIDENCE }.map { it.text }
        assertTrue("least" !in keptMemory, keptMemory.toString())
        assertTrue("most" in keptMemory, keptMemory.toString())
    }

    @Test fun `the report says compression is owed when only the input fits`() {
        // H3 步骤 ③ 的触发条件：①②都做完了还超 ⇒ 压缩还欠着（Review Focus 2）。
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 60)

        assertTrue(r.compressionOwed)
        assertEquals(1, r.kept.conversationFlow.size)
        assertEquals(SegmentOrigin.USER_INPUT, r.kept.conversationFlow.single().origin)
    }

    @Test fun `a summary is not dropped as an old turn but still counts toward the budget`() {
        // Review Focus 5：摘要落在“本轮发言之前”的回合组*内部*，① 会把它前面的整个回合组一起裁；
        // 但如果摘要**不计入 total()**，这一刀就不会落，摘要之外的保留集也更大。
        // 口径复算：总 = 32 + 35(sys) + 34(u1) + 38(摘要, 6 字节) + 34(a1) + 35(now) = 208；
        // ① 裁掉回合组 u1+a1（68）后 = 140。摘要计入时 140 > 110 ⇒ compressionOwed=true；
        // 摘要豁免预算时初始 170、裁后 102 ≤ 110 ⇒ compressionOwed=false ⇒ 该断言就是判别点。
        val f = frame(
            userTurn("u1"),
            Segment(SegmentOrigin.SUMMARY, false, "摘要"),
            assistant("a1"),
            user("now"),
        )
        val r = TurnTrimmer().trim(f, limitBytes = 110)

        // 摘要本身绝不是被裁的那一条。
        assertTrue(r.kept.conversationFlow.any { it.origin == SegmentOrigin.SUMMARY },
            "摘要不得作为旧回合被裁：${r.kept.conversationFlow}")
        // 而它必须计入预算 —— 否则这一刀根本不会落。
        assertTrue(r.compressionOwed, "摘要必须计入 total()（未计入时不该还欠压缩）")
    }

    @Test fun `the system slot is never trimmed`() {
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 1)
        assertEquals(f.systemSegments, r.kept.systemSegments)
    }
}
