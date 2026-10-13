package edith.host.systemone

/**
 * Abstraction over the Laya decision model inference backend.
 *
 * In production this wraps LiteRT (or ONNX Runtime) and implements the Laya graph contract:
 * state tensor in → choice/score/noul output tensors.
 *
 * The current implementation is a stub that validates the architecture without requiring
 * actual model files or native dependencies. Real integration is completed during Phase A
 * performance verification when model artifacts are available.
 */
internal interface LayaInferenceEngine {
    fun initialize(modelPath: String): Boolean
    fun choice(state: String, options: List<String>): FloatArray
    fun score(state: String, items: List<String>, scaleDescription: String): FloatArray
    fun noul(state: String, statement: String): Float
    fun close()
}

/**
 * Stub engine for architecture validation. Returns deterministic outputs that satisfy
 * the interface contract without real inference.
 */
internal class StubLayaEngine : LayaInferenceEngine {
    private var initialized = false

    override fun initialize(modelPath: String): Boolean {
        // In production: load LiteRT model, allocate tensors, warm up
        initialized = true
        return true
    }

    override fun choice(state: String, options: List<String>): FloatArray {
        check(initialized) { "Engine not initialized" }
        // Uniform distribution as stub
        val uniform = 1.0f / options.size
        return FloatArray(options.size) { uniform }
    }

    override fun score(state: String, items: List<String>, scaleDescription: String): FloatArray {
        check(initialized) { "Engine not initialized" }
        // Mid-range scores as stub
        return FloatArray(items.size) { 0.5f }
    }

    override fun noul(state: String, statement: String): Float {
        check(initialized) { "Engine not initialized" }
        return 0.5f
    }

    override fun close() {
        initialized = false
    }
}
