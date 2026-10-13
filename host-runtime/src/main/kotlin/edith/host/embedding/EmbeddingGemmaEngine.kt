package edith.host.embedding

/**
 * Abstraction over the EmbeddingGemma inference backend.
 *
 * In production this wraps LiteRT and handles tokenization, forward pass,
 * and Matryoshka truncation + L2 normalization to the target dimension.
 *
 * Current implementation is a stub for architecture validation.
 */
internal interface EmbeddingEngine {
    fun initialize(modelPath: String): Boolean
    fun embed(text: String): FloatArray
    fun close()
}

/**
 * Stub engine returning deterministic unit-norm vectors of the configured dimension.
 */
internal class StubEmbeddingEngine(private val dimensions: Int) : EmbeddingEngine {
    private var initialized = false

    override fun initialize(modelPath: String): Boolean {
        initialized = true
        return true
    }

    override fun embed(text: String): FloatArray {
        check(initialized) { "Engine not initialized" }
        // Return a deterministic unit-norm vector based on text hash
        val raw = FloatArray(dimensions) { (text.hashCode().toFloat() + it) / dimensions }
        val norm = kotlin.math.sqrt(raw.map { it * it }.sum())
        return if (norm > 0f) raw.map { it / norm }.toFloatArray() else raw
    }

    override fun close() {
        initialized = false
    }
}
