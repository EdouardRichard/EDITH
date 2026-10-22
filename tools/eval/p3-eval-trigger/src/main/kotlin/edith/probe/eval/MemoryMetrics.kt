package edith.probe.eval

/** One query the retrieval was asked, the memory it should have found, and the ids it actually ranked, best first. */
data class QueryOutcome(val query: String, val expectedId: String, val rankedIds: List<String>)

/**
 * Of the memories the extractor wrote, the share that is worth keeping (spec §6.3's 记忆抽取精度).
 *
 * `keep` is the set of texts a human judged worth remembering; anything else the extractor stored counts against it.
 * **Nothing written scores `0.0`**, not the empty-set convention of `1.0`: an extractor that stored nothing has produced
 * no evidence that it stores the right things, and a perfect score for a feature that never ran is exactly the kind of
 * number this phase must not be able to quote.
 */
fun extractionPrecision(written: List<String>, keep: Set<String>): Double =
    if (written.isEmpty()) 0.0 else written.count { it in keep }.toDouble() / written.size

/**
 * The share of queries whose expected memory made the top [k] (spec §6.3's 记忆检索命中率).
 *
 * The bound is applied here rather than trusted from the caller, because `k` is the phase's injection limit - a hit at
 * rank six is a memory the turn would not have injected. No queries scores `0.0` for the same reason as above.
 */
fun retrievalHitRate(queries: List<QueryOutcome>, k: Int = 5): Double =
    if (queries.isEmpty()) 0.0 else queries.count { it.expectedId in it.rankedIds.take(k.coerceAtLeast(0)) }.toDouble() / queries.size
