package com.sheen.rokid.phone.plugin.smarthelper.memory

/**
 * P3.4 §3.2's write path: what one finished turn leaves behind.
 *
 * Two sources feed it. The **explicit** instruction ("记住…") is always honoured, and the report says so, because the
 * caller owes the user a short acknowledgement - a "remember this" that vanishes silently is worse than no feature.
 * The **automatic** rule is the same strict clause matcher the model uses ([extractCandidates]), capped per turn so one
 * long utterance cannot flood the store.
 *
 * Habits are counted, not inferred: an action category has to succeed [routineThreshold] times **in this session**
 * before it becomes a memory, and only the category is stored - which is why `dial` becomes "常打电话" and a phone
 * number can never reach the document (spec §3.2).
 */
class MemoryExtraction(
    private val log: MemoryLog,
    private val maxPerTurn: Int = MAX_PER_TURN,
    private val routineThreshold: Int = ROUTINE_THRESHOLD,
    /**
     * Where an entry goes. It defaults to the store itself, which is what P3.4 did and what every existing test
     * exercises. The Phase B wiring passes [MemoryModule.remember] instead, and that is not a detail: only the
     * module indexes an entry when it is written, so writing through the store alone leaves the semantic index
     * **permanently cold** and every retrieval silently degrades to the lexical order — a module that looks wired
     * and never once ranks by meaning.
     */
    private val write: (MemoryKind, String, MemorySource) -> MemoryEntry? = { kind, text, source ->
        log.record(kind, text, source)
    },
) {

    /** Successes per action category in this session, which is exactly what "a habit" is allowed to mean. */
    private val successes = mutableMapOf<String, Int>()

    /**
     * Writes what this turn earned and reports it.
     *
     * [written] counts entries that actually reached the store (a refused write counts nothing), and [explicit] says
     * whether an explicit instruction was among them - the caller uses it to send [ACKNOWLEDGEMENT].
     */
    fun afterTurn(utterance: String, executedActions: List<String>): ExtractReport {
        var written = 0
        var explicitWritten = false
        val explicit = extractExplicit(utterance)
        if (explicit != null && write(explicit.first, explicit.second, MemorySource.EXPLICIT) != null) {
            written++
            explicitWritten = true
        }

        for ((kind, text) in extractCandidates(utterance)) {
            if (written >= maxPerTurn) break
            if (write(kind, text, MemorySource.AUTO) != null) written++
        }

        for (category in countHabits(executedActions)) {
            val stored = written < maxPerTurn &&
                write(MemoryKind.ROUTINE, "常$category", MemorySource.AUTO) != null
            if (!stored) {
                // The slot was taken, or the store refused: the habit has **not** been recorded, so its count must not be
                // consumed. It fires exactly once, on the success that reaches the threshold - eating that event here
                // would lose the habit forever.
                successes[category] = (successes[category] ?: 1) - 1
                continue
            }
            written++
        }
        return ExtractReport(written = written, explicit = explicitWritten)
    }

    /** The categories that have just reached the threshold - each fires once, on the success that completed it. */
    private fun countHabits(executedActions: List<String>): List<String> {
        val reached = mutableListOf<String>()
        for (action in executedActions) {
            val category = ACTION_CATEGORIES[action] ?: continue
            val count = (successes[category] ?: 0) + 1
            successes[category] = count
            if (count == routineThreshold) reached += category
        }
        return reached
    }

    companion object {
        /** The sentence the plugin shows when the user asked for a memory. It carries no remembered content. */
        const val ACKNOWLEDGEMENT = "好，我记下了。"

        /** Spec §3.2: at most this many entries per turn, explicit one included. */
        const val MAX_PER_TURN = 5

        /** Spec §3.2: "同一 (action, 目标类别) 在一个会话里 ≥3 次成功 ⇒ 记一条常做任务". */
        const val ROUTINE_THRESHOLD = 3

        /**
         * The action names the registry ships, mapped to the **category** a memory may name. An action that is not here
         * is never guessed at: a memory saying "常做某事" about an action we do not understand would be a fact nobody
         * stated. `dial` is the reason this map exists - its own argument is a phone number.
         */
        private val ACTION_CATEGORIES = mapOf(
            "dial" to "打电话",
            "set_brightness" to "调亮度",
            "open_app" to "打开应用",
            "open_url" to "打开网页",
            "open_settings" to "打开设置",
            "set_alarm" to "设闹钟",
            "compose_note" to "记笔记",
        )
    }
}

/** What one turn wrote: how many entries, and whether the user asked for one. */
data class ExtractReport(val written: Int, val explicit: Boolean)
