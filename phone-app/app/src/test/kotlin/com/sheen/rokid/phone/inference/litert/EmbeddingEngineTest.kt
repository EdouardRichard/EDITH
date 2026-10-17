package com.sheen.rokid.phone.inference.litert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * edith-0.2 Phase B, task 6b step ②: the engine's own behaviour, provable without the 179 MB graph.
 *
 * What this file can pin on the JVM and what it deliberately does not:
 *
 * - **Pinned here**: the engine refuses to embed before it is initialized, a directory without the graph is
 *   not available, a failed load leaves the engine unable to embed, and the frame it feeds the graph is the
 *   tokenizer's own `<bos> … <eos>` frame at the window the graph was exported for (256).
 * - **Not pinnable here**: the forward pass. `org.tensorflow.lite.Interpreter` needs a device, so
 *   "the vector is semantic" is proven by `EmbeddingDepthSmokeTest` on the K40 and nowhere else. A JVM
 *   test that stubbed the graph would prove only that the stub was called.
 *
 * `sentencepiece.model` is gitignored vendor data, so the frame test skips — loudly, naming the path —
 * when it is absent, exactly as `EmbeddingTokenizerTest` does.
 */
class EmbeddingEngineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repository root not found from ${File("").absolutePath}")

    private val modelFile: File =
        File(repositoryRoot(), ".local/models/embeddinggemma/sentencepiece.model")

    private fun vocabulary(): SentencePieceBpe {
        assumeTrue(
            "sentencepiece.model not found at ${modelFile.absolutePath} — the vendor artifact is gitignored",
            modelFile.isFile,
        )
        return SentencePieceBpe.load(modelFile)
    }

    // ---------------------------------------------------------------- the engine before it is loaded

    @Test
    fun `the engine refuses to embed before it is initialized`() {
        // The placeholder this test replaced returned a unit-norm vector built from `text.hashCode()`, i.e. it
        // answered happily with nothing loaded. Silently producing a hash vector is what makes the hard
        // dependency's "available" a lie, so the unloaded engine must fail loudly instead.
        val engine = EmbeddingGemmaLiteRTEngine()

        assertThrows(IllegalStateException::class.java) { engine.embed("Hello World") }
    }

    @Test
    fun `an empty directory is not a loaded model`() {
        val engine = EmbeddingGemmaLiteRTEngine()

        assertFalse(engine.initialize(temporary.newFolder("embedding-empty").absolutePath))
    }

    @Test
    fun `a failed initialize does not leave the engine able to embed`() {
        // The trap this pins: `isAvailable` is what the plugin's hard dependency reads, so an engine that
        // half-loaded (vocabulary read, graph not) and then answered `embed` would be a green light on
        // vectors that never came from the model.
        val engine = EmbeddingGemmaLiteRTEngine()

        assertFalse(engine.initialize(temporary.newFolder("embedding-empty").absolutePath))
        assertThrows(IllegalStateException::class.java) { engine.embed("Hello World") }
    }

    // ---------------------------------------------------------------- the frame the graph is fed

    @Test
    fun `an injected vocabulary makes the frame available without the graph`() {
        // The seam is the *tokenizer*, not the model: it exists so a JVM test can pin the frame this engine feeds
        // the graph without a 179 MB file — and `embed()` still refuses, because no graph was ever loaded. Stated
        // as a test because a test seam that quietly makes the engine look functional is exactly the trap the
        // hard dependency is built to avoid.
        val engine = EmbeddingGemmaLiteRTEngine(vocabulary())

        assertEquals(256, engine.frame("Hello World").size)
        assertThrows(IllegalStateException::class.java) { engine.embed("Hello World") }
    }

    @Test
    fun `the frame fed to the graph is the tokenizer's frame at the graph's window`() {
        // Phase A measured the export: EMBED input[0] is `[1,256]` INT32 and output[0] is `[1,768]` FLOAT32
        // (`LiteRTPerformanceTest`). The engine must therefore hand the tokenizer the *graph's* window rather
        // than its own idea of a long-enough sequence, and this is where that is pinned.
        val engine = EmbeddingGemmaLiteRTEngine(vocabulary())

        val ids = engine.frame("Hello World")

        assertEquals(256, ids.size)
        assertEquals(2, ids[0])
        assertArrayEquals(intArrayOf(9259, 4109), ids.copyOfRange(1, 3))
        assertEquals(1, ids[3])
    }
}
