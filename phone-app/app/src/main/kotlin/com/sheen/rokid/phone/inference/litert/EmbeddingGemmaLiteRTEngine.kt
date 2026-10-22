package com.sheen.rokid.phone.inference.litert
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.plugin.EmbeddingInferenceEngine
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * LiteRT-backed EmbeddingGemma engine: the tokenizer and the forward pass, and nothing else.
 *
 * The graph is the publisher's `embeddinggemma-300M_seq256_mixed-precision.tflite`, exported for a fixed window:
 * one INT32 `[1,256]` input, one FLOAT32 `[1,768]` output (measured on the K40 in Phase A,
 * `LiteRTPerformanceTest.embeddingGraphContractAndLatency`). [embed] builds that frame from text and returns the
 * graph's own 768 floats **unchanged**.
 *
 * ## Why it returns 768 and not the service's 256
 *
 * Dimension policy is not this class's job. `EmbeddingServiceImpl.toOutputDimensions` truncates the graph's 768 to
 * 256 and re-normalizes in the same step, because the graph's own 768-dim vector is unit-norm (measured 1.0000)
 * while its first 256 dimensions are not (0.643). Truncating here as well would truncate the *second* time — 768 →
 * 256 → 256 — and normalize a vector the class below is about to normalize again. The class comment on
 * `EmbeddingInferenceEngine` used to claim the engine owned both steps; that described the placeholder
 * implementation, and it was corrected to match this layering.
 *
 * ## What this class does not do
 *
 * It does not decide whether the model is provisioned, and it does not pretend to work without it: [embed] before a
 * successful [initialize] throws rather than answering with a vector, because a plausible-looking vector is what
 * turns the plugin's hard dependency into a green light on garbage. `isAvailable` on `EmbeddingServiceImpl` is the
 * only place that answers "can this be used", and it is set from whether [initialize] returned true.
 *
 * The vocabulary is read from `<modelPath>/sentencepiece.model` — the publisher's own file, parsed per
 * [SentencePieceBpe] — rather than shipped as a derived asset, so there is no second copy of 4.7 MB to keep in step.
 *
 * @param injectedVocabulary test seam: the vocabulary to encode with. Production passes nothing and
 *        [initialize] loads the one next to the graph. Injectable because a JVM unit test cannot build an
 *        [Interpreter] over a 179 MB graph, and the frame is the part of `embed` that can be pinned without one.
 */
class EmbeddingGemmaLiteRTEngine(
    private val injectedVocabulary: SentencePieceBpe? = null,
) : EmbeddingInferenceEngine {

    /**
     * Written by the loader thread in [initialize] and read by whatever thread calls [embed] afterwards.
     *
     * `@Volatile` because `EmbeddingServiceImpl` publishes `isAvailable` through an `AtomicReference` **after**
     * `initialize` returns, so without volatile a reader could legally observe `isAvailable == true` with a stale
     * null here and get one spurious `ENGINE_UNAVAILABLE` out of a service whose whole job is to be dependable.
     */
    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var vocabulary: SentencePieceBpe? = injectedVocabulary

    override fun initialize(modelPath: String): Boolean {
        return try {
            val graph = File(modelPath, GRAPH_FILE)
            val loadedVocabulary = vocabulary
                ?: File(modelPath, VOCABULARY_FILE).takeIf { it.isFile }?.let { SentencePieceBpe.load(it) }
            if (!graph.isFile || loadedVocabulary == null) {
                // Both artifacts are required, and both are checked before the graph is touched: a directory that
                // has the vocabulary but not the graph must report *unavailable*, not "loaded, will fail later".
                System.err.println(
                    "[EmbeddingGemma] Not provisioned at $modelPath " +
                        "(needs $GRAPH_FILE and $VOCABULARY_FILE)",
                )
                return false
            }

            val loaded = Interpreter(graph, Interpreter.Options().apply { numThreads = THREADS })
            // A second `initialize` (not something production does, but the interface allows it) must not leak the
            // graph it replaces: a 179 MB `Interpreter` is not reclaimed by the garbage collector.
            interpreter?.close()
            interpreter = loaded
            vocabulary = loadedVocabulary
            true
        } catch (e: Exception) {
            System.err.println("[EmbeddingGemma] Load failed: ${e.message}")
            false
        }
    }

    /**
     * The `[1, 256]` INT32 frame this engine feeds the graph: `<bos>`, the pieces, `<eos>`, padded to the window
     * the graph was exported for.
     *
     * Public because it is the half of [embed] a JVM test can observe — the other half needs the graph — and
     * because the window is a property of the export rather than of this class's taste.
     */
    fun frame(text: String): IntArray {
        val loaded = vocabulary ?: error("EmbeddingGemmaLiteRTEngine.frame before initialize")
        return loaded.encodeForModel(text, SEQUENCE_LENGTH)
    }

    override fun embed(text: String): FloatArray {
        val loaded = interpreter ?: error("EmbeddingGemmaLiteRTEngine.embed before initialize")
        val output = Array(1) { FloatArray(GRAPH_OUTPUT_DIMENSIONS) }
        // Serialized by the caller: `EmbeddingServiceImpl.embed` holds its lock across this call. `Interpreter` is
        // not safe to run concurrently, so that lock is the contract rather than a courtesy.
        loaded.run(arrayOf(frame(text)), output)
        return output[0]
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        vocabulary = injectedVocabulary
    }

    companion object {
        /** The window the checkpoint was exported for; the graph rejects any other shape. */
        const val SEQUENCE_LENGTH = 256

        /** The graph's own output width, before the service applies Matryoshka truncation. */
        const val GRAPH_OUTPUT_DIMENSIONS = 768

        const val GRAPH_FILE = "embeddinggemma-300M_seq256_mixed-precision.tflite"
        const val VOCABULARY_FILE = "sentencepiece.model"

        private const val THREADS = 4
    }
}
