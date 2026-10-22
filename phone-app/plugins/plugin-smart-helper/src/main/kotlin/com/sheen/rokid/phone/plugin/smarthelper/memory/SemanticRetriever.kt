package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns memory texts into vectors and answers "which of these are closest to this query" (spec §5.4).
 *
 * **It embeds the query and that is all it ever does on a read path.** Embedding the store to answer one question is
 * not affordable — the Phase A measurement is ~190 ms per forward pass on the K40, so a full store would cost
 * minutes per lookup — so [rerank] searches only what is already indexed, and entries are indexed when they are
 * written. A cold index therefore answers nothing rather than stalling, and the caller keeps its lexical order for
 * those entries.
 *
 * Nothing is persisted: [MemoryEntry.embedding] explains why the vector is not worth a multi-megabyte rewrite on
 * every store write. The consequence is stated rather than hidden — an index is warm for what this process wrote and
 * cold for everything else, and there is no network call anywhere in this class.
 *
 * **Dual indexing (Phase 4 §3.9 6e):** when an entry carries a non-blank [MemoryEntry.normalizedText] that differs
 * from its [MemoryEntry.text], both forms are embedded and indexed. Retrieval takes the **higher** cosine similarity
 * of the two, so a query that matches the normalized form better than the original still finds the entry. Entries
 * without a normalized form (old/backward-compat) fall back to original-only behavior, preserving B5's verified
 * sorting conclusion.
 */
class SemanticRetriever(
    private val embeddings: EmbeddingService?,
    private val index: VectorIndex = VectorIndex(DEFAULT_DIMENSIONS),
) {

    private val indexedIds = ConcurrentHashMap.newKeySet<String>()

    /** Tracks which entry IDs have a second (normalized) vector stored under [normalizedKey]. */
    private val hasNormalizedVector = ConcurrentHashMap.newKeySet<String>()

    /** Embeds and indexes one entry. Answers whether it is now searchable. */
    fun index(entry: MemoryEntry): Boolean {
        val service = embeddings ?: return false
        val vector = (service.embed(entry.text) as? EmbedResult.Success)?.vector ?: return false
        index.insert(entry.id, vector)
        indexedIds += entry.id

        // Dual indexing: embed and store the normalized form when it exists and differs.
        val norm = entry.normalizedText
        if (norm.isNotBlank() && norm != entry.text) {
            val normVector = (service.embed(norm) as? EmbedResult.Success)?.vector
            if (normVector != null) {
                index.insert(normalizedKey(entry.id), normVector)
                hasNormalizedVector += entry.id
            }
        }
        return true
    }

    /** The entry was deleted or evicted; its vector must not keep answering. */
    fun forget(id: String) {
        index.delete(id)
        if (hasNormalizedVector.remove(id)) {
            index.delete(normalizedKey(id))
        }
        indexedIds -= id
    }

    /** How many entries are searchable right now. Not a contract, but the honest measure of how warm the index is. */
    val indexedCount: Int get() = indexedIds.size

    /**
     * The [entries] that are indexed, ordered by cosine similarity to [query], or `null` when no semantic ranking
     * is possible at all (no embedding service, or the query could not be embedded).
     *
     * `null` and an empty list are different answers: `null` means "use your own order", an empty list means "the
     * semantic ranking is available and it found none of these" — which is what a cold index returns.
     */
    fun rerank(query: String, entries: List<MemoryEntry>): List<MemoryEntry>? =
        rankScored(query, entries)?.map { it.first }

    /**
     * The same answer with each entry's cosine similarity to [query], for the caller that reports an **interval**
     * instead of a score (B5's device ranking probe). `null` keeps [rerank]'s meaning exactly: no semantic ranking is
     * possible, so the caller must use its own order.
     *
     * **Dual-index scoring:** for each entry, the score is the **maximum** of the cosine similarities against the
     * original-text vector and the normalized-text vector (when present). This ensures that a query matching either
     * form well will surface the entry, while entries without a normalized form retain their original-only score.
     */
    fun rankScored(query: String, entries: List<MemoryEntry>): List<Pair<MemoryEntry, Float>>? {
        val service = embeddings ?: return null
        val vector = (service.embed(query) as? EmbedResult.Success)?.vector ?: return null
        if (indexedIds.isEmpty()) return emptyList()
        val byId = entries.associateBy { it.id }

        // Search the index with enough results to cover all entries plus their normalized counterparts.
        // Each entry may have up to 2 vectors, so we request 2× entries to ensure we capture all scores.
        val rawResults = index.searchScored(vector, entries.size * 2)

        // Group scores by entry ID, taking the max across original and normalized vectors.
        val maxScores = mutableMapOf<String, Float>()
        for ((key, score) in rawResults) {
            val entryId = originalId(key)
            val existing = maxScores[entryId]
            if (existing == null || score > existing) {
                maxScores[entryId] = score
            }
        }

        return maxScores.entries
            .mapNotNull { (id, score) -> byId[id]?.let { it to score } }
            .sortedByDescending { it.second }
    }

    companion object {
        /** Spec §2.7 / §5.2: EmbeddingGemma truncated to 256 dimensions. */
        const val DEFAULT_DIMENSIONS = 256

        /** Prefix for normalized-form vector keys in the index. */
        private const val NORM_PREFIX = "\u0000norm:"

        private fun normalizedKey(entryId: String): String = "$NORM_PREFIX$entryId"

        private fun originalId(key: String): String =
            if (key.startsWith(NORM_PREFIX)) key.removePrefix(NORM_PREFIX) else key
    }
}
