package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

/**
 * edith-0.2 Phase B (B5): makes the memory facade satisfy the agent runtime's retrieval port.
 *
 * The two interfaces do not line up on purpose — [MemoryModule] is the plugin's own storage-and-ranking facade, and
 * `com.sheen.rokid.agent.MemoryRetrieval` is the boundary `ModelClientGateway` asks. This class is the single place where a
 * **production turn's** memory parts acquire their semantic ordering. Without it, building the module would change
 * nothing: the gateway would keep asking a port that the lexical retriever implements, which is exactly the state
 * B5 was in — a module with tests and no caller.
 *
 * The line carries **counts, a character total, which ranking ran, an interval and ids — never content** (spec §5.3).
 * The two ranking fields are what B5 was missing on the device: the part handed to the prompt has no score, so without
 * them "ranked by meaning" and "ranked by characters" produce the same line. The similarity is reported as a **bucket**
 * (`similarityBucket`), which is the owner's rule for this probe ("只记分数区间") and keeps a memory's own score out of
 * the log while still showing that meaning decided the order.
 */
class MemoryModuleRetrieval(
    private val memory: MemoryModule,
    private val logger: (String) -> Unit = { P3Log.line(it) },
) : MemoryRetrieval {

    override fun retrieve(query: String, limit: Int): List<Segment> {
        val injection = memory.injectionProbe(query, limit)
        // A blank query, an empty store or a namespace that refused answers nothing and logs nothing: saying
        // `inject=n=0` would look like a measurement of something that never happened.
        if (injection.parts.isNotEmpty()) {
            val thresholdFields = if (injection.below > 0 || injection.cutoff != "none") {
                " below=${injection.below} cutoff=${injection.cutoff}"
            } else ""
            logger(
                "P3_MEMORY inject=n=${injection.parts.size} chars=${injection.parts.sumOf { it.text.length }} " +
                    "via=${injection.via.name.lowercase()} bucket=${similarityBucket(injection.topSimilarity)} " +
                    "indexed=${injection.indexed} ids=${injection.entries.joinToString(",") { it.id }}" +
                    thresholdFields,
            )
        }
        return injection.parts
    }
}

/**
 * Which half-open interval a cosine similarity falls in, as a label — or `none` when the ranking was lexical.
 *
 * Buckets rather than the number, on purpose: the observable this probe needs is "the nearest memory was a paraphrase
 * (0.8–0.9) and the unrelated one was not (0.3–0.4)", which is the difference the K40's own embedding measurements
 * showed (0.8586 against 0.3701). The raw similarity of a memory about the wearer is not something the device log
 * needs to carry.
 */
internal fun similarityBucket(similarity: Double?): String {
    if (similarity == null) return "none"
    if (similarity < 0.0) return "<0.0"
    if (similarity >= 1.0) return "0.9-1.0"
    val lower = (similarity * 10).toInt().coerceIn(0, 9)
    return "${lower / 10.0}-${(lower + 1) / 10.0}"
}
