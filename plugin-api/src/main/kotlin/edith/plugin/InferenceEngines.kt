package edith.plugin

/**
 * Abstraction over a Laya decision model inference backend.
 *
 * Implementations wrap a specific runtime (LiteRT, ONNX Runtime, etc.) and implement
 * the Laya graph contract: state tensor in → choice/score/noul output tensors.
 *
 * See spec §2.2 and https://huggingface.co/litert-community/laya-LiteRT for graph contract details.
 */
interface LayaInferenceEngine {
    /** Initialize the engine with the given model path. Returns true on success. */
    fun initialize(modelPath: String): Boolean

    /** Choice: select from options. Returns probability distribution across all options. */
    fun choice(state: String, options: List<String>): FloatArray

    /** Score: rate items on a scale. Returns one score per item. */
    fun score(state: String, items: List<String>, scaleDescription: String): FloatArray

    /** Noul: judge whether statement holds. Returns confidence in [0, 1]. */
    fun noul(state: String, statement: String): Float

    /** Release resources. */
    fun close()
}

/**
 * Abstraction over an embedding model inference backend.
 *
 * Implementations wrap a specific runtime and handle tokenization, forward pass,
 * and Matryoshka truncation + L2 normalization to the target dimension.
 */
interface EmbeddingInferenceEngine {
    /** Initialize the engine with the given model path. Returns true on success. */
    fun initialize(modelPath: String): Boolean

    /** Encode text into a dense vector of the configured dimension. */
    fun embed(text: String): FloatArray

    /** Release resources. */
    fun close()
}
