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

    @Test fun `a summary is not dropped as an old turn`() {
        // Review Focus 5
        val f = frame(Segment(SegmentOrigin.SUMMARY, false, "摘要"), userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 120)

        assertTrue(r.kept.conversationFlow.any { it.origin == SegmentOrigin.SUMMARY }, r.kept.conversationFlow.toString())
    }

    @Test fun `the system slot is never trimmed`() {
        val f = frame(userTurn("u1"), assistant("a1"), user("now"))
        val r = TurnTrimmer().trim(f, limitBytes = 1)
        assertEquals(f.systemSegments, r.kept.systemSegments)
    }
}
