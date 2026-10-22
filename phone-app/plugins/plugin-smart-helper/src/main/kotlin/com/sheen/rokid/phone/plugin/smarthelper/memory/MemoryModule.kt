package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.agent.Segment
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.phone.plugin.smarthelper.library.LibraryGovernor

/** Which ranking produced an injection. The device log names it, so a degraded order cannot read as a semantic one. */
enum class MemoryRanking { SEMANTIC, LEXICAL }

/**
 * One retrieval's answer plus what produced it: the entries, the parts handed to the prompt, which ranking ran, the
 * best semantic similarity (or `null` when the lexical order answered), and how many entries the vector index holds.
 */
data class MemoryInjection(
    val entries: List<MemoryEntry>,
    val parts: List<Segment>,
    val via: MemoryRanking,
    val topSimilarity: Double?,
    val indexed: Int,
    /** Phase 4 AC-4: count of entries dropped by the relevance threshold. Zero when threshold is disabled. */
    val below: Int = 0,
    /** Phase 4 AC-4: the threshold value as a string, or `"none"` when threshold is disabled. */
    val cutoff: String = "none",
)

/**
 * The memory facade: one object for writing a memory, finding one, and handing the result to a prompt (spec §5.1).
 *
 * It composes three things that used to be wired directly by the plugin — [MemoryLog] (the store),
 * [SemanticRetriever] (the vectors) and [SmartMemoryRetrieval]'s lexical ranking (the degradation) — so the turn
 * pipeline asks one question instead of three.
 *
 * **Retrieval costs one forward pass, always.** The query is embedded; the store is not. Entries become searchable
 * when they are *written*, so a memory learned this session is found by meaning, while one restored from storage
 * keeps the lexical order until something writes it again. That is a deliberate bound, not an oversight: see
 * [SemanticRetriever] for the arithmetic.
 *
 * **Degradation, in order.** No embedding service → lexical. The query cannot be embedded → lexical. A namespace
 * that refuses to be read → nothing at all, because inventing a memory to fill the gap would be worse than answering
 * from nothing. Writing never throws: a store that refuses answers `null` and the turn carries on (spec §5.5).
 */
class MemoryModule(
    private val log: MemoryLog,
    private val embeddings: EmbeddingService?,
    private val lexicon: SmartMemoryRetrieval,
    private val retriever: SemanticRetriever = SemanticRetriever(embeddings),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Phase 4 AC-4 (§3.9 6f): entries with semantic similarity below this value are dropped whole.
     * `null` (the default) disables filtering entirely — behavior is identical to pre-Phase-4 code.
     */
    private val similarityThreshold: Double? = null,
    /**
     * Design D-11: the memory side's lifecycle seam, **default off**.
     *
     * When a governor is present, every entry that reaches the prompt is recorded as a pull. The default (`null`) is
     * the production path: memory retirement needs real recall-outcome data — opening it here would turn an
     * uncalibrated number into an operating point, so production must not wire one. With `null`, retrieval and
     * rendering are byte-identical to the pre-seam module; the seam only counts, and no ranking, threshold or limit
     * reads the governor's state.
     */
    private val governor: LibraryGovernor? = null,
    /**
     * Phase 7 F4 (§3.1): the **measurement-only** rendering seam, defaulting to [ProductionMemoryEvidenceRenderer].
     *
     * Production passes only [ProductionMemoryEvidenceRenderer] - the very object this default names - so its output
     * stays byte-identical to the pre-seam module. The seam exists so the F4 measurement path can select
     * [LegacyRawMemoryEvidenceRenderer]; no production path does, and no ranking, threshold or limit reads it.
     */
    private val renderer: MemoryEvidenceRenderer = ProductionMemoryEvidenceRenderer,
) {

    /** Writes one raw message and indexes it (spec §5.5: the original text, never a summary). */
    fun remember(text: String, kind: MemoryKind, source: MemorySource): MemoryEntry? {
        val entry = log.record(kind, text, source) ?: return null
        retriever.index(entry)
        return entry
    }

    /**
     * Up to [limit] memories related to [query], most relevant first, marking them used so §5.6's eviction can see
     * that they earned their place.
     */
    fun retrieve(query: String, limit: Int = INJECT_LIMIT): List<MemoryEntry> =
        injectionProbe(query, limit).entries

    /**
     * One retrieval with the facts a **device** probe needs: which ranking ran, and how close the best semantic match
     * was.
     *
     * Why this exists (B5, 2026-10-14): the JVM proves the semantic order with a fake embedding, but on the device the
     * injected part carries no score, so "ranked by meaning" and "ranked by characters" produce an identical
     * `P3_MEMORY inject=` line. [topSimilarity] is `null` whenever the lexical order answered — no embedding service,
     * a query that could not be embedded, or nothing indexed yet — which is exactly the degradation spec §5.1 names.
     */
    fun injectionProbe(query: String, limit: Int = INJECT_LIMIT): MemoryInjection {
        val asked = query.trim()
        if (asked.isEmpty()) return MemoryInjection(emptyList(), emptyList(), MemoryRanking.LEXICAL, null, retriever.indexedCount)
        val snapshot = log.snapshot()
        if (!snapshot.readOk) return MemoryInjection(emptyList(), emptyList(), MemoryRanking.LEXICAL, null, retriever.indexedCount)
        val entries = snapshot.entries
        if (entries.isEmpty()) return MemoryInjection(emptyList(), emptyList(), MemoryRanking.LEXICAL, null, retriever.indexedCount)

        val scored = retriever.rankScored(asked, entries)
        // An empty `scored` is NOT a semantic ranking: it means the ranking was available and placed **none** of these
        // entries (a cold index - the store was restored from storage and only what this process wrote is indexed).
        // The device produced exactly that on 2026-10-14 (`n=5 … via=semantic bucket=none`), which would have claimed a
        // meaning-based order for a purely lexical one; the label now follows what actually placed the entries.
        val semanticHits = scored?.takeIf { it.isNotEmpty() }
        val ranked = if (semanticHits == null) {
            lexical(entries, asked, limit)
        } else {
            // Entries the index has never seen keep the lexical order and follow the semantically ranked ones.
            val rankedIds = semanticHits.mapTo(mutableSetOf()) { it.first.id }
            (semanticHits.map { it.first } + lexical(entries.filterNot { it.id in rankedIds }, asked, limit)).take(limit)
        }

        // Phase 4 AC-4 (§3.9 6f): drop entries below the relevance threshold. Only applies when the ranking
        // produced semantic scores; a lexical ranking has no similarity to compare against, so the threshold
        // stays inert and the counting probes report zero/none.
        val threshold = similarityThreshold
        val filteredRanked: List<MemoryEntry>
        val belowCount: Int
        val cutoffLabel: String
        if (threshold != null && semanticHits != null) {
            val scoredMap = semanticHits.associate { it.first.id to it.second.toDouble() }
            val above = ranked.filter { entry ->
                val s = scoredMap[entry.id]
                s != null && s >= threshold
            }
            filteredRanked = above
            belowCount = ranked.size - above.size
            cutoffLabel = threshold.toString()
        } else {
            filteredRanked = ranked
            belowCount = 0
            cutoffLabel = "none"
        }

        if (filteredRanked.isNotEmpty()) log.markUsed(filteredRanked.map { it.id })
        // Design D-11: the counting position. An entry that is handed to the prompt has been "pulled" in the
        // governor's vocabulary. The governor is null in production (the default), so this line is unreachable there;
        // it records counts only and must never feed back into ranking, thresholding or the limit above.
        governor?.let { g -> filteredRanked.forEach { g.recordPull(it.id) } }
        val parts = renderer.render(filteredRanked)
        return MemoryInjection(
            entries = filteredRanked,
            parts = parts,
            via = if (semanticHits == null) MemoryRanking.LEXICAL else MemoryRanking.SEMANTIC,
            // The best semantic match, which is the first of a similarity-descending list. `null` when nothing was
            // placed by similarity, because then there is no similarity to report rather than a zero to misread.
            topSimilarity = semanticHits?.firstOrNull()?.second?.toDouble(),
            indexed = retriever.indexedCount,
            below = belowCount,
            cutoff = cutoffLabel,
        )
    }

    /** The form `buildPrompt` injects: memory evidence segments for the request frame (§5.7). */
    fun injectionFor(query: String, limit: Int = INJECT_LIMIT): List<Segment> =
        injectionProbe(query, limit).parts

    fun snapshot(): MemorySnapshot = log.snapshot()

    fun entries(): List<MemoryEntry> = log.entries()

    /** Best effort, matching the lexical retriever's own contract: a namespace that refuses yields no memories. */
    private fun lexical(entries: List<MemoryEntry>, query: String, limit: Int): List<MemoryEntry> {
        if (entries.isEmpty()) return emptyList()
        return rank(entries, query, now(), limit, CHAR_BUDGET)
    }

    companion object {
        /** Spec §5.7: the top-5 related memories are injected. */
        const val INJECT_LIMIT = 5

        /** Matches `SmartMemoryRetrieval.CHAR_BUDGET`, so the two orders are bounded by the same number. */
        const val CHAR_BUDGET = 300

        private const val ID_PREFIX = "memory:"
        private const val MAX_IMPORTANCE = 10
    }
}
