package edith.plugins.smart.memory

import edith.plugin.EmbedResult
import edith.plugin.EmbeddingService
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
 */
class SemanticRetriever(
    private val embeddings: EmbeddingService?,
    private val index: VectorIndex = VectorIndex(DEFAULT_DIMENSIONS),
) {

    private val indexedIds = ConcurrentHashMap.newKeySet<String>()

    /** Embeds and indexes one entry. Answers whether it is now searchable. */
    fun index(entry: MemoryEntry): Boolean {
        val service = embeddings ?: return false
        val vector = (service.embed(entry.text) as? EmbedResult.Success)?.vector ?: return false
        index.insert(entry.id, vector)
        indexedIds += entry.id
        return true
    }

    /** The entry was deleted or evicted; its vector must not keep answering. */
    fun forget(id: String) {
        index.delete(id)
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
    fun rerank(query: String, entries: List<MemoryEntry>): List<MemoryEntry>? {
        val service = embeddings ?: return null
        val vector = (service.embed(query) as? EmbedResult.Success)?.vector ?: return null
        if (indexedIds.isEmpty()) return emptyList()
        val byId = entries.associateBy { it.id }
        return index.search(vector, entries.size).mapNotNull { byId[it] }
    }

    companion object {
        /** Spec §2.7 / §5.2: EmbeddingGemma truncated to 256 dimensions. */
        const val DEFAULT_DIMENSIONS = 256
    }
}
