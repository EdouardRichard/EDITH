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
        val context = request.context.toList()
        if (history.size % 2 != 0 || history.chunked(2).any {
            it[0].role != MessageRole.USER || it[1].role != MessageRole.ASSISTANT
        }) rejected()
        try {
            fun message(text: String) = Math.addExact(estimateText(text), 32L)
            var total = Math.addExact(32L, message(request.instructions))
            input.forEach { total = Math.addExact(total, message((it as InputPart.Text).text)) }
            history.forEach { total = Math.addExact(total, message(it.text)) }
            context.forEach { total = Math.addExact(total, message(it.text)) }
            val limit = minOf(profile.inputBudgetTokens.toLong(),
                profile.usableWindowTokens.toLong() - profile.maxOutputTokens.toLong() - profile.safetyTokens.toLong())
            val removed = mutableListOf<String>()
            var removedPairs = 0
            while (total > limit && removedPairs < history.size / 2) {
                val offset = removedPairs * 2
                total = Math.subtractExact(total, Math.addExact(message(history[offset].text), message(history[offset + 1].text)))
                removed += "history:$removedPairs"
                removedPairs++
            }
            val removedContext = mutableSetOf<Int>()
            for (index in context.indices.sortedWith(compareBy<Int> { context[it].importance }.thenBy { it })) {
                if (total <= limit) break
                total = Math.subtractExact(total, message(context[index].text))
                removed += context[index].id
                removedContext += index
            }
            if (total > limit) rejected()
            return BudgetedRequest(request.copy(input = input, history = history.drop(removedPairs * 2),
                context = context.filterIndexed { index, _ -> index !in removedContext }), total.toInt(), removed.toList(), true)
        } catch (_: ArithmeticException) {
            rejected()
        }
    }

    fun estimateText(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
}
