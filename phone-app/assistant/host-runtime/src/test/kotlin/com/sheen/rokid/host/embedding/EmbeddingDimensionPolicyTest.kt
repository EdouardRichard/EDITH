package com.sheen.rokid.host.embedding

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingInferenceEngine
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dimension policy belongs to the service, not the engine: the real EmbeddingGemma graph emits 768
 * dimensions (measured on K40, edith-0.2 Phase A), and the service is what promises 256.
 *
 * Matryoshka truncation is only valid together with re-normalization. The graph normalizes the full
 * 768-dim vector, but a prefix of a unit vector is not itself unit-length - the device measured a
 * 256-prefix norm of 0.643 on a real embedding. Cosine similarity against an un-normalized vector is
 * wrong by exactly that factor, so this is a correctness requirement, not a polish item.
 */
class EmbeddingDimensionPolicyTest {

    private class FixedEngine(private val output: FloatArray) : EmbeddingInferenceEngine {
        override fun initialize(modelPath: String) = true
        override fun embed(text: String) = output.copyOf()
        override fun close() = Unit
    }

    private fun norm(v: FloatArray) = sqrt(v.fold(0f) { acc, f -> acc + f * f })

    private fun loadedService(engine: EmbeddingInferenceEngine): EmbeddingServiceImpl {
        val temp = java.io.File.createTempFile("embed-model", ".tflite")
        temp.writeBytes(ByteArray(16))
        temp.deleteOnExit()
        val service = EmbeddingServiceImpl(modelPath = temp.absolutePath, engine = engine)
        // The service loads asynchronously; wait for READY rather than guessing a sleep.
        val deadline = System.currentTimeMillis() + 5_000
        while (!service.isAvailable && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(service.isAvailable, "service did not become available")
        return service
    }

    @Test
    fun `a 768-dim model output is truncated to 256 and re-normalized`() {
        // What the real graph returns: 768 dims, non-zero only in the first 256, with a prefix norm of 8.
        val engine = FixedEngine(FloatArray(768) { if (it < 256) 0.5f else 0f })
        val service = loadedService(engine)

        val result = service.embed("hello")
        assertTrue(result is EmbedResult.Success, "expected success, got $result")
        val vector = (result as EmbedResult.Success).vector

        assertEquals(256, vector.size, "service promises 256 dimensions")
        assertEquals(1.0f, norm(vector), 0.001f, "truncated vector must be re-normalized to unit length")
    }

    @Test
    fun `re-normalization preserves direction and truncation discards the tail`() {
        val prefixA = FloatArray(768) { if (it < 256) 0.5f else 0f }
        // Same 256-prefix as prefixA, wildly different tail: truncation must make these identical.
        val prefixASameTailDifferent = FloatArray(768) { if (it < 256) 0.5f else 99f }
        // Different 256-prefix: e^-x differs from uniform.
        val prefixB = FloatArray(768) { if (it < 256) (it + 1).toFloat() else 0f }

        val a = (loadedService(FixedEngine(prefixA)).embed("x") as EmbedResult.Success).vector
        val aTail = (loadedService(FixedEngine(prefixASameTailDifferent)).embed("x") as EmbedResult.Success).vector
        val b = (loadedService(FixedEngine(prefixB)).embed("x") as EmbedResult.Success).vector

        assertEquals(1.0f, cosine(a, aTail), 0.0001f, "truncation must discard the tail")
        assertTrue(cosine(a, b) < 0.999f, "a different truncated prefix must not read as the same vector")
        assertEquals(1.0f, norm(a), 0.001f)
    }

    @Test
    fun `an already-256-dim output is normalized and passed through`() {
        val engine = FixedEngine(FloatArray(256) { 2f })
        val vector = (loadedService(engine).embed("x") as EmbedResult.Success).vector
        assertEquals(256, vector.size)
        assertEquals(1.0f, norm(vector), 0.001f)
    }

    @Test
    fun `a zero vector does not produce NaN`() {
        val engine = FixedEngine(FloatArray(768))
        val vector = (loadedService(engine).embed("x") as EmbedResult.Success).vector
        assertEquals(256, vector.size)
        assertTrue(vector.none { it.isNaN() }, "zero input must not divide by a zero norm into NaN")
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot / (norm(a) * norm(b))
    }
}
