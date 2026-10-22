package com.sheen.rokid.host.embedding

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingInferenceEngine
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.FailureReason
import com.sheen.rokid.plugin.ModelInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * EmbeddingGemma service implementation.
 *
 * Lifecycle mirrors SystemOneServiceImpl: async load, no pause/resume, thread-safe inference.
 * Output dimension fixed at 256 via Matryoshka truncation.
 *
 * @param modelPath Path to the EmbeddingGemma model file on disk
 * @param engine Inference engine implementation (defaults to StubEmbeddingEngine for JVM tests;
 *        production injects LiteRT-backed engine from phone-app)
 */
class EmbeddingServiceImpl(
    private val modelPath: String,
    private val engine: EmbeddingInferenceEngine = StubEmbeddingEngine(OUTPUT_DIMENSIONS),
) : EmbeddingService {

    companion object {
        const val OUTPUT_DIMENSIONS = 256
    }

    private val _isAvailable = AtomicReference(false)
    private val _modelInfo = AtomicReference<ModelInfo?>(null)
    private val lock = Any()

    override val isAvailable: Boolean get() = _isAvailable.get()
    override val modelInfo: ModelInfo? get() = _modelInfo.get()
    override val dimensions: Int get() = OUTPUT_DIMENSIONS

    init {
        Thread({ loadModel() }, "Embedding-Loader").apply {
            isDaemon = true
            start()
        }
    }

    private fun loadModel() {
        try {
            val file = java.io.File(modelPath)
            // A model may be one file or a directory of artifacts (EmbeddingGemma ships a graph plus its
            // SentencePiece model), so the check is "the path the engine will load from exists", not "it is a file".
            if (!file.exists()) {
                System.err.println("[Embedding] Model path not found: $modelPath")
                return
            }

            if (!engine.initialize(modelPath)) {
                System.err.println("[Embedding] Engine initialization failed")
                return
            }

            _modelInfo.set(
                ModelInfo(
                    name = file.nameWithoutExtension,
                    version = "unknown",
                    quantization = "unknown",
                    parameterCount = "308M",
                    contextWindow = 2048,
                )
            )
            _isAvailable.set(true)
        } catch (e: Exception) {
            System.err.println("[Embedding] Load failed: ${e.message}")
        }
    }

    override fun embed(text: String): EmbedResult {
        synchronized(lock) {
            if (!isAvailable) {
                return EmbedResult.Error(
                    reason = FailureReason.MODEL_NOT_LOADED,
                    message = "Embedding model not available",
                )
            }
            return try {
                EmbedResult.Success(vector = toOutputDimensions(engine.embed(text)))
            } catch (e: Exception) {
                EmbedResult.Error(
                    reason = FailureReason.ENGINE_UNAVAILABLE,
                    message = e.message ?: "Unknown error",
                )
            }
        }
    }

    /**
     * Applies the service's dimension policy to whatever the engine returned.
     *
     * The real EmbeddingGemma graph emits 768 dimensions with the full vector already L2-normalized
     * (measured on K40; the graph's own norm reads 1.0000). Truncating that to the 256 dimensions this
     * service promises yields a vector whose norm is **not** 1 - the same measurement read 0.643 - and
     * cosine similarity against it is off by exactly that factor. So truncation and re-normalization are
     * one operation here, not two independent ones.
     *
     * A zero vector is returned unchanged rather than divided by its zero norm into NaN; callers get an
     * all-zero embedding they can recognize, not a poisoned one.
     */
    private fun toOutputDimensions(raw: FloatArray): FloatArray {
        val truncated = if (raw.size == OUTPUT_DIMENSIONS) raw.copyOf() else raw.copyOf(OUTPUT_DIMENSIONS)
        var sumSquares = 0f
        for (v in truncated) sumSquares += v * v
        if (sumSquares == 0f) return truncated
        val norm = kotlin.math.sqrt(sumSquares)
        for (i in truncated.indices) truncated[i] /= norm
        return truncated
    }
}
