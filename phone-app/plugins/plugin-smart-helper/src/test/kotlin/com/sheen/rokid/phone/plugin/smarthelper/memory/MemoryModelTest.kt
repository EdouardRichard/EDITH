package com.sheen.rokid.phone.plugin.smarthelper.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3.4 §3.2/§3.3: the memory record, its lexical scoring, its de-duplication rule, its eviction rule and the
 * utterance→candidate extraction rules. All pure Kotlin: no Android, no storage, no clock.
 *
 * The two properties that decide whether this feature is usable are pinned here first, because they are the two that
 * a naive implementation gets wrong: a **negated** preference must not be confused with its positive twin (Chinese has
 * no word boundaries, so a bag-of-words score would call them the same statement), and **nothing that looks like an
 * identifier may be extracted at all**.
 *
 * Assertion arguments follow this module's `kotlin.test` order: message **last**.
 */
class MemoryModelTest {

    private fun entry(
        id: String,
        text: String,
        kind: MemoryKind = MemoryKind.PREFERENCE,
        createdAt: Long = 0L,
        lastUsedAt: Long = 0L,
        useCount: Int = 1,
    ) = MemoryEntry(id, kind, text, createdAt, lastUsedAt, useCount, MemorySource.AUTO)

    @Test fun `a preference and its negation are distinct entries`() {
        val similarity = similarity("我喜欢喝茶", "我不喜欢喝茶")

        assertTrue(similarity < 0.85, "否定必须把两条记忆分开，实测相似度=$similarity")
    }

    @Test fun `two statements that differ only in a number are not merged`() {
        // The tokenizer must not throw single digits away: `我的工位是3楼` and `我的工位是4楼` are different facts, and a
        // merge here loses one of them for good (the store keeps what it wrote, and nobody asked it to forget).
        val similarity = similarity("我的工位是3楼", "我的工位是4楼")

        assertTrue(similarity < 0.85, "只差一个数字的两条事实不得被合并，实测相似度=$similarity")
    }

    @Test fun `a query that negates must not surface the positive entry`() {
        val positive = entry("1", "我喜欢喝茶")
        val negated = entry("2", "我不喜欢喝茶")

        val ranked = rank(listOf(positive, negated), "我不喜欢喝茶", nowMillis = 1_000L, limit = 5, charBudget = 300)

        assertEquals("2", ranked.first().id, "被否定的那条必须排在前面")
    }

    @Test fun `numbers and identifier-shaped strings are never extracted`() {
        assertEquals(emptyList(), extractCandidates("我的手机号是 13800138000"))
        assertEquals(emptyList(), extractCandidates("我的身份证 110101199001011234"))
        assertEquals(emptyList(), extractCandidates("我的银行卡是 6222 0212 3456 7890"))
    }

    @Test fun `an explicit preference statement yields one preference candidate`() {
        assertEquals(
            listOf(MemoryKind.PREFERENCE to "我喜欢喝茶"),
            extractCandidates("我喜欢喝茶。"),
        )
        assertEquals(
            listOf(MemoryKind.PREFERENCE to "我不喜欢喝茶"),
            extractCandidates("我不喜欢喝茶。"),
        )
    }

    @Test fun `a self-description yields one fact candidate`() {
        assertEquals(
            listOf(MemoryKind.FACT to "我的爱好是喝茶"),
            extractCandidates("我的爱好是喝茶。"),
        )
    }

    @Test fun `a question is never extracted`() {
        assertEquals(emptyList(), extractCandidates("我喜欢喝茶吗？"))
    }

    @Test fun `a time-qualified or one-off statement is never extracted`() {
        assertEquals(emptyList(), extractCandidates("我今天喜欢喝茶"))
        assertEquals(emptyList(), extractCandidates("我昨天加班到很晚"))
    }

    @Test fun `an utterance with nothing stable in it yields nothing`() {
        assertEquals(emptyList(), extractCandidates("天气不错"))
        assertEquals(emptyList(), extractCandidates(""))
    }

    @Test fun `a clause that is too long to be self-contained is not stored`() {
        val long = "我喜欢" + "很".repeat(200) + "长的句子"

        assertEquals(emptyList(), extractCandidates(long))
    }

    @Test fun `ranking respects both the limit and the character budget`() {
        val many = (1..8).map { entry("$it", "爱好是喝茶$it", kind = MemoryKind.FACT) }

        val ranked = rank(many, "喝茶", nowMillis = 1_000L, limit = 5, charBudget = 12)

        assertTrue(ranked.sumOf { it.text.length } <= 12, "字符预算必须真的约束住：${ranked.sumOf { it.text.length }}")
        assertTrue(ranked.size <= 5, "条数上限必须真的约束住：${ranked.size}")
        assertTrue(ranked.isNotEmpty(), "预算够放至少一条，不该一条都不给")
    }

    @Test fun `a frequently used entry outranks an equal-text one that was never used`() {
        val fresh = entry("fresh", "喜欢喝茶", lastUsedAt = 0L, useCount = 1)
        val used = entry("used", "喜欢喝茶", lastUsedAt = 0L, useCount = 40)

        val ranked = rank(listOf(fresh, used), "喜欢喝茶", nowMillis = 0L, limit = 5, charBudget = 300)

        assertEquals("used", ranked.first().id, "用过很多次的那条应排前")
    }

    @Test fun `eviction drops the least recently used when use counts tie and reports the count`() {
        // Spec §5.6 orders by useCount first, then by lastUsedAtMillis - so this pins the tie-break. `nowMillis` is
        // far past every creation time, which is what keeps the seven-day protection out of the way.
        val entries = listOf(
            entry("1", "x1", lastUsedAt = 10L),
            entry("2", "x2", lastUsedAt = 20L),
            entry("3", "x3", lastUsedAt = 30L),
        )

        val (kept, dropped) = evictByUsage(entries, cap = 2, nowMillis = Long.MAX_VALUE / 2)

        assertEquals(1, dropped)
        assertEquals(listOf("2", "3"), kept.map { it.id })
    }

    @Test fun `eviction of a list within the cap changes nothing`() {
        val entries = listOf(entry("1", "x1"), entry("2", "x2"))

        val (kept, dropped) = evictByUsage(entries, cap = 2, nowMillis = Long.MAX_VALUE / 2)

        assertEquals(0, dropped)
        assertEquals(listOf("1", "2"), kept.map { it.id })
    }
}
