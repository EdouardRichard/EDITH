package com.sheen.rokid.phone.plugin.smarthelper.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The six-category budget (spec §4.3).
 *
 * The load-bearing property is that the six categories are a **partition** of `total`: if they can sum to more than
 * the budget, then a prompt built from all six is over budget the moment every category is full, which is exactly
 * the case the budget exists to prevent.
 */
class TokenBudgetTest {

    @Test
    fun `the six ratios sum to one`() {
        val budget = TokenBudget()
        val sum = budget.systemPromptRatio + budget.skillRatio + budget.memoryRatio +
            budget.historyRatio + budget.userInputRatio + budget.outputReserveRatio
        assertEquals(1.0f, sum, 0.0001f)
    }

    @Test
    fun `the categories partition the total exactly`() {
        val budget = TokenBudget()
        val categories = budget.categories()

        assertEquals(4096, budget.total)
        assertEquals(
            budget.total,
            categories.systemPrompt + categories.skill + categories.memory +
                categories.history + categories.userInput + categories.outputReserve,
            "the six categories must partition the total, never over-allocate it",
        )
    }

    @Test
    fun `the default budget splits 4096 as 410 1024 410 1638 205 409`() {
        // Largest-remainder allocation, so each category lands as close to its ratio as an integer allows while the
        // sum stays exact. History keeps its full 0.40 (1638); the reserve is the one that gives up the odd token.
        val c = TokenBudget().categories()
        assertEquals(BudgetCategories(410, 1024, 410, 1638, 205, 409), c)
    }

    @Test
    fun `history exposes its own budget`() {
        assertEquals(1638, TokenBudget().historyBudgetTokens())
    }

    @Test
    fun `a non-positive total is refused`() {
        assertFailsWith<IllegalArgumentException> { TokenBudget(total = 0) }
        assertFailsWith<IllegalArgumentException> { TokenBudget(total = -1) }
    }

    @Test
    fun `a negative ratio is refused`() {
        assertFailsWith<IllegalArgumentException> { TokenBudget(skillRatio = -0.1f) }
    }

    @Test
    fun `ratios that do not sum to one are refused`() {
        // A budget whose parts do not add up cannot partition the total, so it is a configuration error, not a
        // rounding problem to be absorbed silently.
        assertFailsWith<IllegalArgumentException> {
            TokenBudget(systemPromptRatio = 0.5f, skillRatio = 0.5f, memoryRatio = 0.5f)
        }
    }

    @Test
    fun `an unused skill budget is handed to history`() {
        // Spec §4.3: when SKILL or memory occupy less than their allowance, the remainder goes to history.
        val c = TokenBudget().categories()
        assertEquals(1638 + 1024 + 410, historyBudgetWithLeftovers(c, skillsUsed = 0, memoryUsed = 0))
        assertEquals(1638 + 1024 + 410 - 10, historyBudgetWithLeftovers(c, skillsUsed = 10, memoryUsed = 0))
    }

    @Test
    fun `a category that overruns its allowance does not shrink history below its own budget`() {
        // Over-allocation is possible only if a caller ignores the category caps; history must not go negative.
        val c = TokenBudget().categories()
        assertEquals(1638, historyBudgetWithLeftovers(c, skillsUsed = 99_999, memoryUsed = 99_999))
    }

    @Test
    fun `estimation is about one token per cjk character and conservative for ascii`() {
        // UTF-8 bytes / 3, rounded up. CJK is 3 bytes/char, so one character is one token; ASCII is 1 byte/char, so
        // the estimate over-counts it - the safe direction, since the host cuts on real bytes.
        assertEquals(1, estimateTokens("中"))
        assertEquals(3, estimateTokens("你好吗"))
        assertTrue(estimateTokens("abcdefghijkl") >= 4, "ascii must not be under-estimated")
        assertEquals(0, estimateTokens(""))
    }

    @Test
    fun `a multi byte character is never counted as zero`() {
        // Rounding up matters: a prompt of many emoji must not estimate as 0 tokens. One emoji is 4 UTF-8 bytes.
        assertEquals(2, estimateTokens("😀"))
        assertEquals(4, estimateTokens("😀😀😀"))
    }
}
