package com.sheen.rokid.phone.plugin.smarthelper.memory

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory cosine-similarity index over the memory vectors.
 *
 * Moved here from `host-runtime` (`VectorSearchBridge`), where it had **no production consumer** — its only reference
 * was its own test — while its only future consumer is this package. Leaving it in the host would have made the
 * plugin depend on the host's implementation to get sixty lines of arithmetic.
 *
 * A pure-Kotlin linear scan, sized for the store's own cap (1000 entries × 256 dimensions, spec §5.3). It is
 * deliberately not a vector database: the spec's `sqlite-vector` was replaced by `VectorSearchBridge` in Phase A, and
 * this class is the same replacement under a name that says what it is.
 *
 * **Concurrency, stated honestly.** The store is a `ConcurrentHashMap`, so an insert during a search cannot corrupt
 * anything — but a search is not a snapshot: an entry inserted while the scan is running may or may not appear in
 * that search's results. For a top-5 memory lookup that is the right trade (the alternative is a lock on the read
 * path to bound a race whose worst case is one memory appearing one turn later).
 */
class VectorIndex(private val dimensions: Int) {

    private val store = ConcurrentHashMap<String, FloatArray>()

    fun insert(id: String, vector: FloatArray) {
        require(vector.size == dimensions) {
            "Vector dimension mismatch: expected $dimensions, got ${vector.size}"
        }
        // A defensive copy: the caller keeps its vector and may reuse the buffer.
        store[id] = vector.copyOf()
    }

    fun delete(id: String) {
        store.remove(id)
    }

    /**
     * The ids whose vectors are closest to [query], most similar first.
     *
     * Answers an empty list for an empty store or a zero-norm query: a zero query has no direction, so every
     * similarity against it is arbitrary rather than meaningful.
     */
    fun search(query: FloatArray, limit: Int): List<String> = searchScored(query, limit).map { it.first }

    /**
     * The same order with the cosine similarity that produced it.
     *
     * The number is not part of the retrieval contract, so [search] stays the ordinary answer and this exists for the
     * one caller that turns a similarity into an **interval** for the device log (B5's ranking probe): a bucket is
     * enough to show that meaning decided the order, and it keeps a memory's own score out of the log.
     */
    fun searchScored(query: FloatArray, limit: Int): List<Pair<String, Float>> {
        require(query.size == dimensions) {
            "Query dimension mismatch: expected $dimensions, got ${query.size}"
        }
        if (store.isEmpty()) return emptyList()

        val queryNorm = norm(query)
        if (queryNorm == 0f) return emptyList()

        return store.entries
            .map { (id, vec) -> id to cosineSimilarity(query, vec, queryNorm) }
            .sortedByDescending { it.second }
            .take(limit)
    }

    val size: Int get() = store.size

    private fun cosineSimilarity(a: FloatArray, b: FloatArray, aNorm: Float): Float {
        var dot = 0f
        var bNormSq = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            bNormSq += b[i] * b[i]
        }
        val bNorm = sqrt(bNormSq)
        if (bNorm == 0f) return 0f
        return dot / (aNorm * bNorm)
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }
}
