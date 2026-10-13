package edith.host.embedding

import edith.plugin.EmbedResult
import edith.plugin.EmbeddingService
import edith.plugin.FailureReason
import edith.plugin.ModelInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * EmbeddingGemma service implementation.
 *
 * Lifecycle mirrors SystemOneServiceImpl: async load, no pause/resume, thread-safe inference.
 * Output dimension fixed at 256 via Matryoshka truncation.
 *
 * @param modelPath Path to the EmbeddingGemma model file on disk
 */
class EmbeddingServiceImpl(
    private val modelPath: String,
) : EmbeddingService {

    companion object {
        const val OUTPUT_DIMENSIONS = 256
    }

    private val _isAvailable = AtomicReference(false)
    private val _modelInfo = AtomicReference<ModelInfo?>(null)
    private val engine: EmbeddingEngine = StubEmbeddingEngine(OUTPUT_DIMENSIONS)
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
            if (!file.exists() || !file.isFile) {
                System.err.println("[Embedding] Model file not found: $modelPath")
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
                EmbedResult.Success(vector = engine.embed(text))
            } catch (e: Exception) {
                EmbedResult.Error(
                    reason = FailureReason.ENGINE_UNAVAILABLE,
                    message = e.message ?: "Unknown error",
                )
            }
        }
    }
}
