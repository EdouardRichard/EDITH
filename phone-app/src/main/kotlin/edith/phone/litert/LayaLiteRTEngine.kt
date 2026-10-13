package edith.phone.litert

import edith.plugin.LayaInferenceEngine
import org.tensorflow.lite.Interpreter

/**
 * LiteRT-backed Laya decision model engine.
 *
 * Implements the Laya graph contract for on-device inference on Android.
 * Uses the LiteRT Interpreter API with CPU delegate (GPU delegate can be enabled later).
 *
 * Graph contract (from https://huggingface.co/litert-community/laya-LiteRT):
 * - Main graph signature `serving_default`:
 *   input_ids [1,N] int32, attention_mask [1,N] float32, qtype_onehot [1,3] float32
 *   → token_logits [1,N] float32, pooled_cls [1,D] float32 (D=768 multilingual)
 * - Act head: pooled_cls [1,D] + feats [1,4] → act_logits [1,2]
 *
 * Current implementation: validates model loading and basic inference.
 * Full tokenizer + prompt template integration is a follow-up task.
 */
class LayaLiteRTEngine : LayaInferenceEngine {
    private var mainInterpreter: Interpreter? = null
    private var actInterpreter: Interpreter? = null
    private var windowSize: Int = 256
    private var pooledDim: Int = 768 // multilingual mmBERT-base

    override fun initialize(modelPath: String): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = 4
                // GPU delegate can be added later:
                // addDelegate(GpuDelegate())
            }

            // Load main graph
            val mainModelPath = "$modelPath/laya_ml_s256_fp32.tflite"
            mainInterpreter = Interpreter(java.io.File(mainModelPath), options)

            // Load act head
            val actModelPath = "$modelPath/laya_ml_act_head_fp32.tflite"
            actInterpreter = Interpreter(java.io.File(actModelPath), options)

            android.util.Log.i("LayaLiteRT", "Models loaded successfully. Main inputs: ${mainInterpreter?.inputTensorCount}, outputs: ${mainInterpreter?.outputTensorCount}")
            true
        } catch (e: Exception) {
            android.util.Log.e("LayaLiteRT", "Failed to load models: ${e.message}", e)
            false
        }
    }

    override fun choice(state: String, options: List<String>): FloatArray {
        // TODO: Implement full tokenizer + prompt template
        // For now, return uniform distribution as placeholder
        // Real implementation needs:
        // 1. Tokenize state + options using multilingual/tokenizer.json (BPE)
        // 2. Build prompt: [CLS] <choice> question: ... [SEP] [MASK] opt0 [MASK] opt1 ... [SEP] <state> [SEP]
        // 3. Pad/truncate to windowSize
        // 4. Run main graph → get token_logits at MASK positions
        // 5. Apply temperature calibration → softmax → probabilities
        val uniform = 1.0f / options.size.coerceAtLeast(1)
        return FloatArray(options.size.coerceAtLeast(1)) { uniform }
    }

    override fun score(state: String, items: List<String>, scaleDescription: String): FloatArray {
        // TODO: Same as choice but with score qtype_onehot
        return FloatArray(items.size) { 0.5f }
    }

    override fun noul(state: String, statement: String): Float {
        // TODO: Same as choice but with noul qtype_onehot
        return 0.5f
    }

    override fun close() {
        mainInterpreter?.close()
        actInterpreter?.close()
        mainInterpreter = null
        actInterpreter = null
    }
}
