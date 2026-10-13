package edith.host.systemone

import edith.plugin.LayaInferenceEngine

/**
 * Stub engine for JVM unit tests. Returns deterministic outputs that satisfy
 * the interface contract without real inference.
 *
 * Production uses LiteRT-backed implementations injected from phone-app.
 */
internal class StubLayaEngine : LayaInferenceEngine {
    private var initialized = false

    override fun initialize(modelPath: String): Boolean {
        initialized = true
        return true
    }

    override fun choice(state: String, options: List<String>): FloatArray {
        check(initialized) { "Engine not initialized" }
        val uniform = 1.0f / options.size
        return FloatArray(options.size) { uniform }
    }

    override fun score(state: String, items: List<String>, scaleDescription: String): FloatArray {
        check(initialized) { "Engine not initialized" }
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
