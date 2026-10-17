package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import kotlin.math.roundToInt

/**
 * P3.4 §3.3: the plugin's long-term-memory boundary - the one place that turns stored memories into `context parts` for
 * a request.
 *
 * Three rules, each with a test:
 *
 *  - **The limits are real.** At most [INJECT_LIMIT] parts and at most [charBudget] characters, and both are enforced
 *    here rather than trusted from the caller, because this is the last place before the wire.
 *  - **A hit is marked used.** Otherwise "least recently used" would evict exactly the memories the assistant keeps
 *    needing.
 *  - **The log carries no content.** `P3_MEMORY inject=…` prints the count, the character total, the top score and the
 *    ids; the sentences themselves only ever travel to the model - and only on a request that the session's upload
 *    switch already allowed (spec §5.3).
 *
 * A query that is blank, a store that is empty, or a ranking that fits nothing all answer an **empty list and no log
 * line**: nothing was injected, and saying `inject=n=0` would look like a measurement of something.
 */
class SmartMemoryRetrieval(
    private val log: MemoryLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val charBudget: Int = CHAR_BUDGET,
    private val logger: (String) -> Unit = { P3Log.line(it) },
) : MemoryRetrieval {

    override fun retrieve(query: String, limit: Int): List<Segment> {
        val asked = query.trim()
        if (asked.isEmpty()) return emptyList()
        val snapshot = log.snapshot()
        if (!snapshot.readOk) {
            // Spec §3.3: a namespace that refused to be read is named as such. It is **not** the same fact as "the user
            // has no memories", and the turn proceeds either way.
            logger("P3_MEMORY inject=0 reason=READ_FAILED")
            return emptyList()
        }
        val entries = snapshot.entries
        if (entries.isEmpty()) return emptyList()
        val stamp = now()
        val top = rank(entries, asked, stamp, limit.coerceAtMost(INJECT_LIMIT), charBudget)
        if (top.isEmpty()) return emptyList()
        val scores = top.map { score(it, asked, stamp, scenarioMatched = false) }
        log.markUsed(top.map { it.id })
        logger(
            "P3_MEMORY inject=n=${top.size} chars=${top.sumOf { it.text.length }} " +
                "topScore=${(scores.max() * 100).roundToInt() / 100.0} " +
                "ids=${top.joinToString(",") { it.id }}",
        )
        return top.map { entry ->
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, entry.text)
        }
    }

    /** What a turn asks for: the same call, with the phase's own limit. Named so the call site reads as a decision. */
    fun injectionFor(utterance: String): List<Segment> = retrieve(utterance, INJECT_LIMIT)

    companion object {
        /** Spec §3.3: at most this many parts per request. */
        const val INJECT_LIMIT = 5

        /** Spec §3.3: at most this many characters of memory per request. */
        const val CHAR_BUDGET = 300

        private const val ID_PREFIX = "memory:"
    }
}
