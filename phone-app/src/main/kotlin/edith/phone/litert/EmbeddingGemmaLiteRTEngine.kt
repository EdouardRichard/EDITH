package edith.phone.litert

import edith.plugin.EmbeddingInferenceEngine
import org.tensorflow.lite.Interpreter
import kotlin.math.sqrt

/**
 * LiteRT-backed EmbeddingGemma engine.
 *
 * Loads the EmbeddingGemma seq256 mixed-precision model and produces 256-dim embeddings
 * via Matryoshka truncation + L2 normalization.
 *
 * Current implementation: validates model loading. Full SentencePiece tokenization
 * is a follow-up task (requires sentencepiece.model from the model directory).
 */
class EmbeddingGemmaLiteRTEngine(
    private val outputDimensions: Int = 256,
) : EmbeddingInferenceEngine {
    private var interpreter: Interpreter? = null

    override fun initialize(modelPath: String): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = 4
            }

            val modelFile = java.io.File("$modelPath/embeddinggemma-300M_seq256_mixed-precision.tflite")
            if (!modelFile.exists()) {
                android.util.Log.e("EmbeddingGemma", "Model file not found: ${modelFile.absolutePath}")
                return false
            }

            interpreter = Interpreter(modelFile, options)
            android.util.Log.i("EmbeddingGemma", "Model loaded. Inputs: ${interpreter?.inputTensorCount}, Outputs: ${interpreter?.outputTensorCount}")
            true
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingGemma", "Failed to load model: ${e.message}", e)
            false
        }
    }

    override fun embed(text: String): FloatArray {
        // TODO: Implement full SentencePiece tokenization using sentencepiece.model
        // For now, return a deterministic unit-norm vector as placeholder
        // Real implementation needs:
        // 1. Tokenize text using SentencePiece (sentencepiece.model)
        // 2. Pad/truncate to seq256
        // 3. Run inference → get embedding vector (768-dim full output)
        // 4. Truncate to outputDimensions (Matryoshka)
        // 5. L2 normalize
        val raw = FloatArray(outputDimensions) { (text.hashCode().toFloat() + it) / outputDimensions }
        val norm = sqrt(raw.map { it * it }.sum())
        return if (norm > 0f) raw.map { it / norm }.toFloatArray() else raw
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
