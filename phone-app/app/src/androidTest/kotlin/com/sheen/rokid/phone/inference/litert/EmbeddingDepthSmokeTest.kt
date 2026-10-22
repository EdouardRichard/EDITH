package com.sheen.rokid.phone.inference.litert
import com.sheen.rokid.phone.storage.unavailable

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.host.embedding.EmbeddingServiceImpl
import com.sheen.rokid.plugin.EmbedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * edith-0.2 Phase B, task 6b step ④: does the embedding model actually carry meaning on the K40?
 *
 * This is the only test in the task that can answer that. Every other one stops short of the graph: the JVM suite
 * pins the tokenizer against the publisher's own encoder and pins the engine's frame, but a JVM test cannot build an
 * `Interpreter` over a 179 MB graph, and a *stub* graph would only prove that the stub was called. The question this
 * file exists for is the one the plan words as "a paraphrase must be closer than an unrelated sentence" — dimension,
 * norm and NaN checks are necessary and **not sufficient**, because the `text.hashCode()` placeholder satisfied all
 * three.
 *
 * Read the raw readings, not just the pass/fail: the cosine values are logged as `SMOKE_COSINE` and are what the
 * evidence file records. A margin of 0.001 would pass this test and still be noise.
 *
 * The model is provisioned by hand with adb (Phase A) and is gitignored, so the class skips — naming the path — when
 * it is absent. `ANDROID_SERIAL=2696813b` must be set when running it: `connectedDebugAndroidTest` otherwise targets
 * every attached device, and the glasses are attached too.
 *
 * **The method names are camelCase on purpose.** The debug androidTest APK is R8-minified here, and R8 rejects a
 * method whose name contains a space when the DEX version is below 040 — a backticked Kotlin test name fails the
 * build at `minifyDebugAndroidTestWithR8`, before any device is involved. The JVM unit tests in this module keep
 * their backticked names because their APK is never minified.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingDepthSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelDir get() = File(context.getExternalFilesDir(null), "models/embeddinggemma")

    /**
     * One service for the whole class: each `EmbeddingServiceImpl` starts its own loader thread and holds its own
     * `Interpreter` over the same file, so one per test method would mean four copies of a 179 MB graph for no gain.
     */
    private fun service(): EmbeddingServiceImpl {
        assumeTrue(
            "EmbeddingGemma not present at ${modelDir.absolutePath}",
            EmbeddingGemmaLiteRTEngine.GRAPH_FILE.let { File(modelDir, it).isFile } &&
                File(modelDir, EmbeddingGemmaLiteRTEngine.VOCABULARY_FILE).isFile,
        )
        shared?.let { return it }

        val created = EmbeddingServiceImpl(
            modelPath = modelDir.absolutePath,
            engine = EmbeddingGemmaLiteRTEngine(),
        )
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (!created.isAvailable && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue(
            "the service reported unavailable after ${LOAD_TIMEOUT_MS} ms; the model is at " +
                "${modelDir.absolutePath} — is `sentencepiece.model` next to the graph?",
            created.isAvailable,
        )
        Log.i(TAG, "SMOKE_MODEL_INFO=${created.modelInfo} dimensions=${created.dimensions}")
        shared = created
        return created
    }

    private fun vector(text: String): FloatArray = when (val result = service().embed(text)) {
        is EmbedResult.Success -> result.vector
        is EmbedResult.Error -> throw AssertionError("embed(\"$text\") failed: ${result.reason} ${result.message}")
    }

    // ---------------------------------------------------------------- the acceptance the plan names

    @Test
    fun aParaphraseIsCloserThanAnUnrelatedSentence() {
        val original = vector("我不吃香菜")
        val paraphrase = vector("我不喜欢香菜")
        val unrelated = vector("明天下午三点开会")

        val near = cosine(original, paraphrase)
        val far = cosine(original, unrelated)
        Log.i(TAG, "SMOKE_COSINE paraphrase=$near unrelated=$far margin=${near - far}")

        assertTrue(
            "a paraphrase ($near) must be closer than an unrelated sentence ($far); " +
                "hash vectors and a mis-tokenized frame both produce an order that looks random",
            near > far,
        )
    }

    @Test
    fun everyEmbeddingIs256DimensionalUnitNormAndFreeOfNaN() {
        for (text in SENTENCES) {
            val v = vector(text)

            assertEquals("dimension for \"$text\"", 256, v.size)
            assertFalse("\"$text\" produced NaN", v.any { it.isNaN() })
            assertTrue("\"$text\" is all zero — the graph did not compute", v.any { it != 0f })
            val norm = sqrt(v.fold(0f) { acc, f -> acc + f * f })
            Log.i(TAG, "SMOKE_VECTOR text=\"$text\" size=${v.size} norm=$norm")
            assertTrue("norm for \"$text\" is $norm, not a unit vector", abs(norm - 1f) <= 1e-3f)
        }
    }

    @Test
    fun theSameTextTwiceProducesTheSameVector() {
        // "Are the vectors consistent run to run?" had no measurement before this test. Exact equality is not
        // asserted and should not be: XNNPACK's multi-threaded reductions combine partial sums in an order that
        // varies between runs, so an fp32 result is reproducible only to a tolerance (the same reason
        // LiteRTPerformanceTest.runUntilSettled exists). The tolerance and the observed difference are both logged.
        val first = vector("EDITH 语义检索")
        val second = vector("EDITH 语义检索")

        val maxDiff = first.indices.maxOf { abs(first[it] - second[it]) }
        val cosine = cosine(first, second)
        Log.i(TAG, "SMOKE_DETERMINISM maxAbsDiff=$maxDiff cosine=$cosine")

        assertTrue("the same text moved by $maxDiff between two runs", maxDiff <= 1e-4f)
        assertTrue("the same text's cosine with itself is $cosine", abs(cosine - 1f) <= 1e-4f)
    }

    // ---------------------------------------------------------------- the layering, on the real graph

    @Test
    fun theEngineReturnsTheGraphsOwn768Floats() {
        // Pins the boundary the owner ruled on: `embed()` returns the graph's native width, and
        // `EmbeddingServiceImpl.toOutputDimensions` is the single place that truncates to 256 and re-normalizes.
        // Measured on the K40 in Phase A: full-dim norm 0.9999998, first-256 norm 0.64299405 — i.e. truncation
        // without re-normalization would hand cosine similarity a vector that is off by that factor.
        assumeTrue("EmbeddingGemma graph not present at ${modelDir.absolutePath}", File(
            modelDir, EmbeddingGemmaLiteRTEngine.GRAPH_FILE).isFile)

        val engine = EmbeddingGemmaLiteRTEngine()
        try {
            assertTrue("engine initialize failed for ${modelDir.absolutePath}", engine.initialize(modelDir.absolutePath))
            val raw = engine.embed("我不吃香菜")

            assertEquals("the graph's native width", EmbeddingGemmaLiteRTEngine.GRAPH_OUTPUT_DIMENSIONS, raw.size)
            assertFalse("raw graph output contains NaN", raw.any { it.isNaN() })
            val rawNorm = sqrt(raw.fold(0f) { acc, f -> acc + f * f })
            val truncatedNorm = sqrt(raw.take(256).fold(0f) { acc, f -> acc + f * f })
            Log.i(TAG, "SMOKE_RAW768_NORM=$rawNorm SMOKE_TRUNC256_NORM=$truncatedNorm")
            assertTrue("the graph's own vector is not unit norm: $rawNorm", abs(rawNorm - 1f) <= 2e-2f)
        } finally {
            engine.close()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private companion object {
        const val TAG = "EmbeddingDepth"
        const val LOAD_TIMEOUT_MS = 60_000L

        val SENTENCES = listOf("我不吃香菜", "我不喜欢香菜", "明天下午三点开会")

        /** The service is process-wide; the JVM builds one instance of this class per test method. */
        var shared: EmbeddingServiceImpl? = null
    }
}
