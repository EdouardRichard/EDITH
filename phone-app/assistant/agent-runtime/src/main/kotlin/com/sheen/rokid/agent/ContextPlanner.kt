package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem

/**
 * The history a plan hands back, plus the bookkeeping that keeps truncation visible.
 *
 * [droppedItems] counts **items** (not groups) the plan removed. [noteToModel] is non-null **exactly when**
 * [droppedItems] > 0: spec §6.1 forbids silent truncation, so the two fields always move together.
 */
data class PlannedContext(
    val history: List<ModelHistoryItem>,
    val droppedItems: Int,
    val noteToModel: String?,
)

private const val SUMMARY_MARKER = "\u2026(summarized)"

/**
 * Fits a history into a context budget using the spec §6.1 degradation order, in order:
 *
 * 1. within budget — [plan] returns the caller's own list, untouched;
 * 2. **summarize tool results** — every [ModelHistoryItem.ToolOutput] longer than [summarizeAbove] characters becomes
 *    its first [summarizeAbove] characters plus a `…(summarized)` marker; nothing else is touched;
 * 3. **drop completed steps' intermediate products, keep conclusions** — whole tool rounds (a `ToolCall` and its
 *    matching `ToolOutput`) are removed, all but the most recent [keepLastToolRounds];
 * 4. **truncate the oldest whole groups and say so** — with the long-term-memory store not implemented yet (§6.5), the
 *    overflow degrades to truncation: the oldest whole groups go and [PlannedContext.noteToModel] records how many
 *    items were dropped. Truncation is never silent.
 *
 * The planner knows nothing about HTTP, tokens or Android: [budget] and [cost] are supplied by the caller (Task 14
 * wires `model-runtime`'s `ContextBudgeter` and the profile's window budget in), so a plan is deterministic and
 * testable with a plain character cost. Whatever [plan] returns still satisfies the shipped history shape the
 * `ContextBudgeter` validates (text alternating from `USER`, except that an `ASSISTANT` text may directly follow a
 * tool item because one assistant turn spans several tool rounds; a tool run is opened by an `ASSISTANT` text; the
 * history never ends on a lone pending `USER` text): step 3 only removes tool items, and step 4 only removes whole
 * groups **from the oldest end, where a group opens at a `Text(USER)`** — so a survivor still begins at the user turn
 * it belongs to and keeps that shape.
 */
class ContextPlanner(
    private val budget: Int,
    private val cost: (ModelHistoryItem) -> Int,
    private val summarizeAbove: Int = 400,
    private val keepLastToolRounds: Int = 2,
) {
    /** Returns the largest history this planner can fit, with the drop count and the model-facing note. */
    fun plan(history: List<ModelHistoryItem>): PlannedContext {
        // ① Within budget: nothing changes.
        if (total(history) <= budget) return PlannedContext(history, 0, null)

        // ② Summarize long tool results. The caller's cost sees the shortened text, so this can be enough on its own.
        val summarized = history.map { item ->
            if (item is ModelHistoryItem.ToolOutput && item.output.length > summarizeAbove) {
                ModelHistoryItem.ToolOutput(item.callId, item.output.take(summarizeAbove) + SUMMARY_MARKER)
            } else {
                item
            }
        }
        if (total(summarized) <= budget) return PlannedContext(summarized, 0, null)

        // ③ Keep conclusions: drop the call/output pairs of all but the most recent rounds. No `Text` item is ever
        // removed here, so the `USER`/`ASSISTANT` alternation is preserved exactly.
        val droppedRoundIndices = toolRounds(summarized)
            .dropLast(keepLastToolRounds.coerceAtLeast(0))
            .flatten()
            .toSet()
        var remaining = summarized.filterIndexed { index, _ -> index !in droppedRoundIndices }
        var droppedItems = droppedRoundIndices.size

        // ④ Still over: truncate the oldest whole groups until it fits, and report every item that went.
        if (total(remaining) > budget) {
            val groups = ArrayDeque(groupsOf(remaining))
            while (total(remaining) > budget && groups.isNotEmpty()) {
                droppedItems += groups.removeFirst().size
                remaining = groups.flatten()
            }
        }
        return PlannedContext(remaining, droppedItems, noteFor(droppedItems))
    }

    /**
     * Tool call/output pairs in order, each as the indices of its `ToolCall` and matching `ToolOutput`. A `ToolOutput`
     * with no opening call keeps its own single-item round, so an orphan is still dropped as one unit.
     */
    private fun toolRounds(history: List<ModelHistoryItem>): List<List<Int>> {
        val rounds = mutableListOf<MutableList<Int>>()
        val openRounds = mutableMapOf<String, Int>()
        history.forEachIndexed { index, item ->
            when (item) {
                is ModelHistoryItem.ToolCall -> {
                    rounds += mutableListOf(index)
                    openRounds[item.callId] = rounds.lastIndex
                }
                is ModelHistoryItem.ToolOutput -> {
                    val round = openRounds.remove(item.callId)
                    if (round == null) rounds += mutableListOf(index) else rounds[round] += index
                }
                is ModelHistoryItem.Text -> Unit
            }
        }
        return rounds
    }

    /**
     * Splits history the way `model-runtime`'s `ContextBudgeter` validates it: text alternates from `USER`, except
     * that an `ASSISTANT` text may directly follow a tool item (the same assistant turn continuing across tool
     * rounds). A group opens **only at a `Text(USER)`**, so one group is exactly one user turn — every tool round the
     * assistant went through stays inside it, and removing the oldest group always drops a whole turn whose first item
     * is a `USER` text. A history that opens with anything else (input this module never produces) starts a group
     * anyway rather than throwing, so [plan] stays total.
     */
    private fun groupsOf(history: List<ModelHistoryItem>): List<List<ModelHistoryItem>> {
        val groups = mutableListOf<MutableList<ModelHistoryItem>>()
        for (item in history) {
            when (item) {
                is ModelHistoryItem.Text -> {
                    if (groups.isEmpty() || item.role == MessageRole.USER) groups += mutableListOf(item)
                    else groups.last() += item
                }
                is ModelHistoryItem.ToolCall, is ModelHistoryItem.ToolOutput -> {
                    if (groups.isEmpty()) groups += mutableListOf(item) else groups.last() += item
                }
            }
        }
        return groups
    }

    private fun total(history: List<ModelHistoryItem>): Int = history.sumOf { cost(it) }

    private fun noteFor(droppedItems: Int): String? =
        if (droppedItems > 0) "($droppedItems earlier steps were dropped to fit the context window)" else null
}
