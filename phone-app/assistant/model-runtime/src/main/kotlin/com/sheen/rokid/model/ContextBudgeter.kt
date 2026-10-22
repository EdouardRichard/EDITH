package com.sheen.rokid.model

import com.sheen.rokid.agent.TokenMeter
import com.sheen.rokid.plugin.*
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.ImageBudget

/**
 * 0.25 spec §4.9 (ruling R68): one image's cost in the budgeter's unit — token units, the unit `inputBudgetTokens` is
 * treated as now that text is priced by [TokenMeter] (0.3 design §5.2). The single source is
 * [ImageBudget.UNITS_PER_IMAGE] in core-contracts, which `TurnTrimmer` charges too: two pre-send stages that priced an
 * image differently would let the trimmer hand the budgeter a request the budgeter rejects.
 */
private val IMAGE_BUDGET_UNITS = ImageBudget.UNITS_PER_IMAGE

/**
 * [estimatedInputTokens] keeps its name for source compatibility with the gateway/adapter callers, but it counts
 * **token units** ([TokenMeter]), not UTF-8 bytes.
 */
data class BudgetedRequest(val request: ModelRequest, val estimatedInputTokens: Int, val removedIds: List<String>, val estimated: Boolean)

class ContextBudgeter {
    fun prepare(profile: ModelProfile, request: ModelRequest): BudgetedRequest {
        // Malformed shapes and configuration races stay `ConfigurationRejected`. Only "the request does not fit the
        // window" gets the dedicated signal (R101), so the gateway's overflow recovery cannot mistake a profile
        // revision mismatch, an invalid endpoint or a client-state refusal for an overflow and retry it.
        fun rejected(): Nothing = throw ContractError.ConfigurationRejected("Explicit context exceeds safe budget or is malformed")
        fun overflowed(): Nothing = throw ContractError.ContextWindowExceeded("Context window exceeded")
        if (request.profileId != profile.id || request.profileRevision != profile.revision) rejected()
        val input = request.input.toList()
        // TEXT and IMAGE are the modalities this version sends. Audio encoding is not part of it, so a request that
        // carries an audio part is still refused exactly as before — visibly, never by silently dropping the part.
        // Images are admitted and priced below.
        if (input.any { it is InputPart.Audio }) {
            throw ContractError.CapabilityMissing("Audio estimator and encoder unavailable")
        }
        val history = request.history.toList()
        // Text alternates USER/ASSISTANT from a leading USER, with one deliberate exception: an ASSISTANT text may
        // directly follow a tool item, because a real assistant turn spans several tool rounds and is only then closed
        // (this is how the endpoint's stateless `input` array looks). Every consecutive run of tool items must still be
        // preceded by an ASSISTANT text item, so a call is never orphaned from the assistant turn that produced it and
        // never starts the history. A group opens only at a `Text(USER)`, so one group is exactly one user turn —
        // every tool round its assistant went through stays inside it — and removing the oldest group always drops a
        // whole turn: the survivor still starts with a `USER` text, so eviction can never lose the user turn from the
        // wire or leave a leading assistant text behind.
        val groups = mutableListOf<MutableList<ModelHistoryItem>>()
        var lastTextRole: MessageRole? = null
        var inToolRun = false
        for (item in history) {
            when (item) {
                is ModelHistoryItem.Text -> {
                    when {
                        // The transcript opens with the user's own turn.
                        lastTextRole == null -> if (item.role != MessageRole.USER) rejected()
                        // After a tool item the user may reply, or the same assistant turn may keep working.
                        inToolRun -> Unit
                        lastTextRole == MessageRole.USER -> if (item.role != MessageRole.ASSISTANT) rejected()
                        else -> if (item.role != MessageRole.USER) rejected()
                    }
                    if (item.role == MessageRole.USER) groups += mutableListOf(item) else groups.last() += item
                    lastTextRole = item.role
                    inToolRun = false
                }
                is ModelHistoryItem.ToolCall, is ModelHistoryItem.ToolOutput -> {
                    if (!inToolRun && lastTextRole != MessageRole.ASSISTANT) rejected()
                    inToolRun = true
                    groups.last() += item
                }
            }
        }
        // Every group opens at a `Text(USER)`, so a group of one is the pending user turn with no reply: a half
        // exchange that whole-group eviction could never drop. (An assistant text that closes a tool round is not a
        // group of its own; it joins the group its user turn opened.)
        if (groups.isNotEmpty() && groups.last().size == 1) rejected()
        try {
            fun message(text: String) = Math.addExact(estimateText(text), 32L)
            fun imageCost(images: List<ImageRef>) = Math.multiplyExact(images.size.toLong(), IMAGE_BUDGET_UNITS)
            fun partCost(part: InputPart): Long = when (part) {
                is InputPart.Text -> message(part.text)
                // An image part carries no text of its own: it charges exactly the per-image unit, which is what makes
                // the whole-request identity "text cost + images × IMAGE_BUDGET_UNITS" true for both image carriers.
                is InputPart.Image -> IMAGE_BUDGET_UNITS
                // Unreachable: audio was refused above, before the history was inspected.
                is InputPart.Audio -> throw ContractError.CapabilityMissing("Audio estimator and encoder unavailable")
            }
            fun itemCost(item: ModelHistoryItem): Long = when (item) {
                is ModelHistoryItem.Text -> message(item.text)
                is ModelHistoryItem.ToolCall -> message(item.name + item.argsJson)
                is ModelHistoryItem.ToolOutput -> Math.addExact(message(item.output), imageCost(item.images))
            }
            var total = Math.addExact(32L, message(request.instructions))
            input.forEach { total = Math.addExact(total, partCost(it)) }
            history.forEach { total = Math.addExact(total, itemCost(it)) }
            val limit = minOf(profile.inputBudgetTokens.toLong(),
                profile.usableWindowTokens.toLong() - profile.maxOutputTokens.toLong() - profile.safetyTokens.toLong())
            val removed = mutableListOf<String>()
            var removedGroups = 0
            while (total > limit && removedGroups < groups.size) {
                groups[removedGroups].forEach { total = Math.subtractExact(total, itemCost(it)) }
                removed += "history:$removedGroups"
                removedGroups++
            }
            if (total > limit) overflowed()
            val removedCount = groups.take(removedGroups).sumOf { it.size }
            val keptHistory = history.drop(removedCount)
            // `ModelRequest` requires a request to carry something. A history-only continuation (empty `input`) whose
            // one user turn had to go would leave neither `input` nor `history`, and `copy` would throw a raw
            // `IllegalArgumentException` from the contract type; the request that fits nothing is rejected here.
            if (input.isEmpty() && keptHistory.isEmpty()) rejected()
            return BudgetedRequest(request.copy(input = input, history = keptHistory), total.toInt(), removed.toList(), true)
        } catch (_: ArithmeticException) {
            rejected()
        }
    }

    /**
     * The token-unit estimate for one text, in the same unit the profile fields speak and the same one `TurnTrimmer`
     * charges through [TokenMeter] (0.3 design §5.2). The previous implementation counted UTF-8 bytes, which
     * over-priced CJK up to threefold and made the profile's `inputBudgetTokens` name untrue.
     */
    fun estimateText(text: String): Long = TokenMeter.estimate(text).toLong()
}
