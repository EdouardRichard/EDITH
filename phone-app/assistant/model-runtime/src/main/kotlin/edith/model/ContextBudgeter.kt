package edith.model

import edith.plugin.*
import edith.contracts.ContractError

data class BudgetedRequest(val request: ModelRequest, val estimatedInputTokens: Int, val removedIds: List<String>, val estimated: Boolean)

class ContextBudgeter {
    fun prepare(profile: ModelProfile, request: ModelRequest): BudgetedRequest {
        fun rejected(): Nothing = throw ContractError.ConfigurationRejected("Explicit context exceeds safe budget or is malformed")
        if (request.profileId != profile.id || request.profileRevision != profile.revision) rejected()
        val input = request.input.toList()
        if (input.any { it !is InputPart.Text }) {
            throw ContractError.CapabilityMissing("Media estimator and encoder unavailable")
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
            fun itemCost(item: ModelHistoryItem): Long = when (item) {
                is ModelHistoryItem.Text -> message(item.text)
                is ModelHistoryItem.ToolCall -> message(item.name + item.argsJson)
                is ModelHistoryItem.ToolOutput -> message(item.output)
            }
            var total = Math.addExact(32L, message(request.instructions))
            input.forEach { total = Math.addExact(total, message((it as InputPart.Text).text)) }
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
            if (total > limit) rejected()
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

    fun estimateText(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
}
