package com.sheen.rokid.host.embedding

import com.sheen.rokid.plugin.EmbeddingInferenceEngine

/**
 * Stub engine for JVM unit tests. Returns deterministic unit-norm vectors.
 *
 * Production uses LiteRT-backed implementation injected from phone-app.
 */
internal class StubEmbeddingEngine(private val dimensions: Int) : EmbeddingInferenceEngine {
    private var initialized = false

    override fun initialize(modelPath: String): Boolean {
        initialized = true
        return true
    }

    override fun embed(text: String): FloatArray {
        check(initialized) { "Engine not initialized" }
        val raw = FloatArray(dimensions) { (text.hashCode().toFloat() + it) / dimensions }
        val norm = kotlin.math.sqrt(raw.map { it * it }.sum())
        return if (norm > 0f) raw.map { it / norm }.toFloatArray() else raw
    }

    override fun close() {
        initialized = false
    }
}
