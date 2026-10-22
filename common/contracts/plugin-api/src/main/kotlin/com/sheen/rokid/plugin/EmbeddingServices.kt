package com.sheen.rokid.plugin

/**
 * Local embedding model service for semantic search.
 *
 * Uses EmbeddingGemma (308M params) with Matryoshka truncation to 256 dimensions.
 * See spec §2.7 for full contract.
 */
interface EmbeddingService {
    /**
     * Encode text into a dense vector representation.
     *
     * @param text Input text to embed
     * @return EmbedResult containing the vector or an error
     */
    fun embed(text: String): EmbedResult

    /** Whether the service is ready to accept embedding calls. */
    val isAvailable: Boolean

    /** Metadata about the loaded model, or null if not loaded. */
    val modelInfo: ModelInfo?

    /** Output vector dimension (fixed at 256 via Matryoshka truncation). */
    val dimensions: Int
}

sealed class EmbedResult {
    data class Success(val vector: FloatArray) : EmbedResult()
    data class Error(val reason: FailureReason, val message: String) : EmbedResult()
}
