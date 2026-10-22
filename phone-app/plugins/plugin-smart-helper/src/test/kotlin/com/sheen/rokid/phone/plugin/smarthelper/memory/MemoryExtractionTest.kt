package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P3.4 §3.2: how a memory gets written - the explicit instruction, the automatic rule, and the habit that is counted
 * from what actually ran.
 *
 * The things that must not happen are the point of this file: an explicit "remember this" that is silently dropped, a
 * turn that writes an unbounded pile of entries, a habit inferred from one action, and **any number reaching the
 * store** (the `dial` category exists precisely so a phone number cannot).
 */
class MemoryExtractionTest {

    private val storage = SmartFakeStorage()
    private var clock = 1_000L

    private fun log() = MemoryLog(storage = storage, now = { clock }, log = { })

    private fun extraction(maxPerTurn: Int = 5) = MemoryExtraction(log = log(), maxPerTurn = maxPerTurn)

    @Test fun `an explicit remember instruction is stored as said and asks for an acknowledgement`() {
        val report = extraction().afterTurn("记住我不吃香菜", emptyList())

        assertEquals(1, report.written)
        assertTrue(report.explicit, "显式指令必须被认出来，好让插件回一句确认")
        val entry = log().entries().single()
        assertEquals("我不吃香菜", entry.text)
        assertEquals(MemorySource.EXPLICIT, entry.source)
    }

    @Test fun `the acknowledgement sentence carries no remembered content`() {
        assertEquals("好，我记下了。", MemoryExtraction.ACKNOWLEDGEMENT)
    }

    @Test fun `an explicit instruction with an identifier in it is refused like any other`() {
        val report = extraction().afterTurn("记住我的手机号是 13800138000", emptyList())

        assertEquals(0, report.written)
        assertTrue(log().entries().isEmpty(), "号码形态的串不得进记忆：${log().entries().map { it.text }}")
    }

    @Test fun `automatic extraction respects the per-turn cap`() {
        // Five *different* preferences: identical ones would be de-duplicated by design, which is a different property
        // (pinned in `MemoryLogTest`), and this test is about the cap.
        val utterance = listOf("我喜欢喝茶", "我喜欢咖啡", "我喜欢看书", "我喜欢跑步", "我喜欢音乐").joinToString("，")

        val report = extraction(maxPerTurn = 2).afterTurn(utterance, emptyList())

        assertEquals(2, report.written, "每回合写入上限必须真的约束住")
        assertEquals(2, log().entries().size)
    }

    @Test fun `a question or a time-qualified statement writes nothing`() {
        val extraction = extraction()

        assertEquals(0, extraction.afterTurn("我今天喜欢喝茶", emptyList()).written)
        assertEquals(0, extraction.afterTurn("我喜欢喝茶吗？", emptyList()).written)
        assertTrue(log().entries().isEmpty())
    }

    @Test fun `a habit is recorded only after three successful actions of the same kind`() {
        val extraction = extraction()

        extraction.afterTurn("天气不错", listOf("dial", "dial"))
        assertTrue(log().entries().none { it.kind == MemoryKind.ROUTINE }, "两次还不算习惯")

        extraction.afterTurn("天气不错", listOf("dial"))

        val routine = log().entries().single { it.kind == MemoryKind.ROUTINE }
        assertEquals("常打电话", routine.text)
    }

    @Test fun `a habit never records the argument it was called with`() {
        val log = log()
        val extraction = MemoryExtraction(log = log, maxPerTurn = 5)

        extraction.afterTurn("天气不错", listOf("dial", "dial", "dial"))

        assertTrue(
            log.entries().none { it.text.any(Char::isDigit) },
            "习惯类记忆不得含数字（号码永不可入库）：${log.entries().map { it.text }}",
        )
    }

    @Test fun `an unknown action never becomes a habit`() {
        extraction().afterTurn("天气不错", listOf("somethingElse", "somethingElse", "somethingElse"))

        assertTrue(log().entries().isEmpty(), "不认识的动作不得凭猜测归类")
    }

    @Test fun `an explicit instruction and an automatic one in the same turn both land`() {
        val report = extraction().afterTurn("记住我不吃香菜，我喜欢喝茶", emptyList())

        assertTrue(report.explicit)
        val texts = log().entries().map { it.text }
        assertTrue(texts.any { "不吃香菜" in it }, "显式内容必须在：$texts")
        assertTrue(texts.any { it == "我喜欢喝茶" }, "自动抽取的内容也必须在：$texts")
        assertFalse(texts.any { it.any(Char::isDigit) })
    }
}
