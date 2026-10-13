package edith.host.systemone

import edith.plugin.ChoiceResult
import edith.plugin.ModelInfo
import edith.plugin.NoulResult
import edith.plugin.ScoreResult
import edith.plugin.SystemOneService
import edith.plugin.SystemOneStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * System-One decision model service implementation.
 *
 * Lifecycle:
 * - Construction starts async model loading (non-blocking)
 * - LOADING → READY on success (with warm-up), or LOADING → UNAVAILABLE on failure
 * - No pause/resume — model stays resident once loaded
 * - Thread-safe: inference calls are serialized internally
 *
 * @param modelPath Path to the Laya model file on disk
 */
class SystemOneServiceImpl(
    private val modelPath: String,
) : SystemOneService {

    private val _status = AtomicReference(SystemOneStatus.LOADING)
    private val _modelInfo = AtomicReference<ModelInfo?>(null)
    private val engine: LayaInferenceEngine = StubLayaEngine()
    private val lock = Any()

    override val isAvailable: Boolean get() = _status.get() == SystemOneStatus.READY
    override val status: SystemOneStatus get() = _status.get()
    override val modelInfo: ModelInfo? get() = _modelInfo.get()

    init {
        // Async model loading — never blocks the caller
        Thread({ loadModel() }, "SystemOne-Loader").apply {
            isDaemon = true
            start()
        }
    }

    private fun loadModel() {
        try {
            val loaded = ModelLoader.load(modelPath)
            if (loaded == null) {
                _status.set(SystemOneStatus.UNAVAILABLE)
                return
            }

            if (!engine.initialize(modelPath)) {
                _status.set(SystemOneStatus.UNAVAILABLE)
                return
            }

            // Warm-up: run a dummy choice to eliminate cold-start latency
            try {
                engine.choice("warmup", listOf("a", "b"))
            } catch (_: Exception) {
                // Warm-up failure is non-fatal; first real call will bear the cost
            }

            _modelInfo.set(
                ModelInfo(
                    name = loaded.name,
                    version = "unknown",
                    quantization = "unknown",
                    parameterCount = "unknown",
                    contextWindow = 512,
                )
            )
            _status.set(SystemOneStatus.READY)
        } catch (e: Exception) {
            System.err.println("[SystemOne] Load failed: ${e.message}")
            _status.set(SystemOneStatus.UNAVAILABLE)
        }
    }

    override fun choice(state: String, options: List<String>): ChoiceResult {
        synchronized(lock) {
            if (!isAvailable) {
                return ChoiceResult(selectedIndex = 0, probabilities = FloatArray(options.size.coerceAtLeast(1)))
            }
            val probs = engine.choice(state, options)
            val selected = probs.indices.maxByOrNull { probs[it] } ?: 0
            return ChoiceResult(selectedIndex = selected, probabilities = probs)
        }
    }

    override fun score(state: String, items: List<String>, scaleDescription: String): ScoreResult {
        synchronized(lock) {
            if (!isAvailable) {
                return ScoreResult(scores = FloatArray(items.size))
            }
            return ScoreResult(scores = engine.score(state, items, scaleDescription))
        }
    }

    override fun noul(state: String, statement: String): NoulResult {
        synchronized(lock) {
            if (!isAvailable) {
                return NoulResult(confidence = 0f)
            }
            return NoulResult(confidence = engine.noul(state, statement))
        }
    }
}
