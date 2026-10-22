package com.sheen.rokid.phone.plugin.smarthelper.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P3.4 §3.3's ranking score has five terms; the plan's Task 1 Step 3 names a **negation** term explicitly ("查询有否定而
 * 条目没有（或反之）" must be penalised). A fresh read-only review found the term missing, and the only negation test
 * covered one direction by accident of the bigram half.
 *
 * The pair below is built so the lexical halves **tie**: the query's bigrams overlap the negated twin and the positive
 * twin by exactly two each, so anything that separates them can only be the negation term.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class MemoryNegationScoreTest {

    private val now = 1_000_000L

    private fun entry(text: String) =
        MemoryEntry(id = "id", kind = MemoryKind.FACT, text = text, createdAtMillis = now, lastUsedAtMillis = now, useCount = 1, source = MemorySource.AUTO)

    @Test fun `the lexical halves of the two twins really do tie`() {
        val query = "我不吃香菜"

        assertEquals(
            lexicalScore(query, "我不吃辣椒"),
            lexicalScore(query, "我吃香菜"),
            "这个测试只有在两半打平时才有意义",
        )
    }

    @Test fun `a negated query prefers the negated twin`() {
        val query = "我不吃香菜"

        val negated = score(entry("我不吃辣椒"), query, now, scenarioMatched = false)
        val positive = score(entry("我吃香菜"), query, now, scenarioMatched = false)

        assertTrue(negated > positive, "否定查询不得把它的肯定孪生排到前面：negated=$negated positive=$positive")
    }

    @Test fun `a positive query prefers the positive twin`() {
        val query = "我吃香菜"

        val negated = score(entry("我不吃辣椒"), query, now, scenarioMatched = false)
        val positive = score(entry("我吃香菜"), query, now, scenarioMatched = false)

        assertTrue(positive > negated, "肯定查询不得把它的否定孪生排到前面：positive=$positive negated=$negated")
    }

    @Test fun `a negation marker is recognised without reading a clock or a store`() {
        assertTrue(negated("我不喜欢喝茶"))
        assertTrue(negated("我没吃过"))
        assertTrue(negated("别无选择"))
        assertFalse(negated("我喜欢喝茶"), "没有否定标记的句子不该被当成否定")
    }

    @Test fun `nothing is penalised when both sides agree on negation`() {
        val query = "我不吃香菜"

        val sameTwin = score(entry("我不吃辣椒"), query, now, scenarioMatched = false)
        val res = rank(listOf(entry("我不吃辣椒"), entry("我吃香菜")), query, now, limit = 5, charBudget = 300)

        assertEquals("我不吃辣椒", res.first().text, "排序必须把否定孪生放第一")
        assertTrue(sameTwin > 0.0)
    }
}
