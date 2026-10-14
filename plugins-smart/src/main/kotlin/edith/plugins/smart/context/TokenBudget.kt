package edith.plugins.smart.context

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How one prompt's budget is split (spec §4.3).
 *
 * The six categories are a **partition** of [total], not six independent allowances: a partition is what lets the
 * caller safely fill every category at once. Allocating each ratio by rounding on its own does not partition — the
 * spec's own defaults round to 4097 out of 4096 — so [categories] uses largest-remainder allocation instead, which
 * lands every category as close to its ratio as an integer allows while the sum stays exact.
 *
 * @param total the whole budget in tokens, as the spec fixes it (4096).
 */
data class TokenBudget(
    val total: Int = 4096,
    val systemPromptRatio: Float = 0.10f,
    val skillRatio: Float = 0.25f,
    val memoryRatio: Float = 0.10f,
    val historyRatio: Float = 0.40f,
    val userInputRatio: Float = 0.05f,
    val outputReserveRatio: Float = 0.10f,
) {
    private val ratios: List<Float>
        get() = listOf(
            systemPromptRatio, skillRatio, memoryRatio,
            historyRatio, userInputRatio, outputReserveRatio,
        )

    init {
        require(total > 0) { "total must be positive, was $total" }
        require(ratios.all { it >= 0f }) { "ratios must not be negative, was $ratios" }
        val sum = ratios.sum()
        // A budget whose parts do not add up cannot partition the total, so this is a configuration error rather
        // than a rounding difference to absorb silently.
        require(abs(sum - 1.0f) < 0.0001f) { "the six ratios must sum to 1.0, was $sum" }
    }

    /** The six per-category allowances, summing to exactly [total]. */
    fun categories(): BudgetCategories {
        val exact = ratios.map { it.toDouble() * total }
        val allocated = exact.map { floor(it).toInt() }.toMutableList()
        var leftover = total - allocated.sum()
        // Largest remainder first; ties broken by declaration order, so the split is deterministic and a test can
        // pin exact numbers.
        val byRemainder = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - floor(exact[it]) }.thenBy { it },
        )
        for (index in byRemainder) {
            if (leftover <= 0) break
            allocated[index]++
            leftover--
        }
        return BudgetCategories(
            systemPrompt = allocated[0], skill = allocated[1], memory = allocated[2],
            history = allocated[3], userInput = allocated[4], outputReserve = allocated[5],
        )
    }

    fun historyBudgetTokens(): Int = categories().history
}

/** One category's allowance in tokens. */
data class BudgetCategories(
    val systemPrompt: Int,
    val skill: Int,
    val memory: Int,
    val history: Int,
    val userInput: Int,
    val outputReserve: Int,
)

/**
 * The history allowance **including** the SKILL and memory budget that went unused (spec §4.3, final line).
 *
 * An overrun is clamped at zero rather than subtracted: a caller that ignored a category cap has already broken the
 * budget, and shrinking history below its own allowance would compound that into a negative one.
 */
fun historyBudgetWithLeftovers(categories: BudgetCategories, skillsUsed: Int, memoryUsed: Int): Int =
    categories.history +
        (categories.skill - skillsUsed).coerceAtLeast(0) +
        (categories.memory - memoryUsed).coerceAtLeast(0)

/**
 * A **conservative** token estimate for a piece of text.
 *
 * The host's own `model-runtime` `ContextBudgeter` measures UTF-8 bytes; the plugin layer converts that same byte
 * fact into tokens rather than inventing a second, disagreeing unit. `ceil(bytes / 3)` is one token per CJK
 * character (3 bytes each) and over-counts ASCII (1 byte each) — the safe direction, because over-estimating trims
 * early and under-estimating would produce a prompt the endpoint rejects.
 *
 * This is an **estimate**: the true count is the provider's tokenizer's, which this layer has no access to.
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    return ceil(text.toByteArray(Charsets.UTF_8).size / 3.0).toInt()
}

/**
 * The text a history item contributes to an estimate.
 *
 * A tool call contributes its arguments and a tool output its payload; the identities are bookkeeping the model does
 * not read, so counting them would inflate an estimate without bounding anything real.
 */
internal fun edith.plugin.ModelHistoryItem.estimatedText(): String = when (this) {
    is edith.plugin.ModelHistoryItem.Text -> text
    is edith.plugin.ModelHistoryItem.ToolCall -> argsJson
    is edith.plugin.ModelHistoryItem.ToolOutput -> output
}

/**
 * The same contribution, as a **size**. `P3_CONTEXT` reports how much of a prompt a turn carried, and a size is what a
 * device log may hold: the text itself is the user's words and never appears in one.
 */
internal fun edith.plugin.ModelHistoryItem.estimatedLength(): Int = estimatedText().length
