package edith.host.storage

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory vector search bridge with cosine similarity.
 *
 * This is a pure-Kotlin implementation suitable for ≤1000 entries × 256 dimensions.
 * Designed to be replaceable with sqlite-vector native extension when available,
 * without changing the public API.
 *
 * Thread-safe via ConcurrentHashMap + synchronized search.
 */
class VectorSearchBridge(private val dimensions: Int) {
    private val store = ConcurrentHashMap<String, FloatArray>()

    fun insert(id: String, vector: FloatArray) {
        require(vector.size == dimensions) {
            "Vector dimension mismatch: expected $dimensions, got ${vector.size}"
        }
        store[id] = vector.copyOf()
    }

    fun delete(id: String) {
        store.remove(id)
    }

    /**
     * Search for the closest vectors by cosine similarity.
     *
     * @param query Query vector (must match configured dimensions)
     * @param limit Maximum number of results
     * @return List of IDs ordered by descending similarity
     */
    fun search(query: FloatArray, limit: Int): List<String> {
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
            .map { it.first }
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
