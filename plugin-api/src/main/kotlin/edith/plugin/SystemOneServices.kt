package edith.plugin

/**
 * System-One local decision model service interface.
 *
 * System-One is a decision model (not a text generation model). It accepts structured state input
 * and returns typed probabilistic decisions: choice (select from candidates), score (rate items),
 * or noul (judge whether a statement holds).
 *
 * See spec §2.2 for full contract.
 */
interface SystemOneService {
    /**
     * Choice: select the best matching option from a candidate list.
     *
     * @param state Text describing the current context/state
     * @param options List of candidate options to choose from
     * @return Selected index and probability distribution across all options
     */
    fun choice(state: String, options: List<String>): ChoiceResult

    /**
     * Score: rate items on an ordered scale.
     *
     * @param state Text describing the current context/state
     * @param items List of items to score
     * @param scaleDescription Description of the scoring scale
     * @return Float score for each item
     */
    fun score(state: String, items: List<String>, scaleDescription: String): ScoreResult

    /**
     * Noul: judge whether a statement holds (Bernoulli).
     *
     * @param state Text describing the current context/state
     * @param statement The statement to evaluate
     * @return Confidence value between 0 and 1
     */
    fun noul(state: String, statement: String): NoulResult

    /** Whether the service is ready to accept inference calls. */
    val isAvailable: Boolean

    /** Current service status for UI display. */
    val status: SystemOneStatus

    /** Metadata about the loaded model, or null if not loaded. */
    val modelInfo: ModelInfo?
}

/** Result of a choice operation. */
data class ChoiceResult(
    /** Index of the selected option in the input list. */
    val selectedIndex: Int,
    /** Probability distribution across all options. Sum should be ~1.0. */
    val probabilities: FloatArray,
)

/** Result of a score operation. */
data class ScoreResult(
    /** One score per input item, in the same order. */
    val scores: FloatArray,
)

/** Result of a noul (Bernoulli) operation. */
data class NoulResult(
    /** Confidence that the statement holds, in [0, 1]. */
    val confidence: Float,
)

/** Service lifecycle status visible to the user. */
enum class SystemOneStatus {
    /** Model is being loaded asynchronously. */
    LOADING,
    /** Model is loaded and ready for inference. */
    READY,
    /** Model failed to load or was unloaded by the system. */
    UNAVAILABLE,
}

/** Metadata about a loaded local model. */
data class ModelInfo(
    val name: String,
    val version: String,
    val quantization: String,
    val parameterCount: String,
    val contextWindow: Int,
)

/** Reasons a local model operation can fail. */
enum class FailureReason {
    MODEL_NOT_LOADED,
    ENGINE_UNAVAILABLE,
    INFERENCE_TIMEOUT,
    INVALID_INPUT,
    RESOURCE_EXHAUSTED,
}
