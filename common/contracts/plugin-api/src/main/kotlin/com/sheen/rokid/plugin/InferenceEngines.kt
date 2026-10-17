package com.sheen.rokid.plugin

/**
 * Abstraction over a Laya decision model inference backend.
 *
 * Implementations wrap a specific runtime (LiteRT, ONNX Runtime, …). The interface is deliberately as narrow as
 * Laya itself: initialize once, put questions, close. It carries **no model path**, because the publisher's host
 * resolves its assets from `context.filesDir` and validates them at construction — a path parameter here would be
 * ignored by the only real implementation, and a parameter the implementation ignores is a lie about the contract.
 *
 * @see SystemOneQuestion
 * @see SystemOneAnswer
 */
interface LayaInferenceEngine {
    /** Loads/compiles the model. Returns true on success. Blocks; call off the main thread. */
    fun initialize(): Boolean

    /**
     * Puts one question to the model.
     *
     * Implementations may throw; [SystemOneService] owns translating a throw into a [SystemOneResult.Failure] so
     * that callers only ever see the contract's own failure vocabulary.
     */
    fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer

    /** Release resources. */
    fun close()
}

/**
 * Abstraction over an embedding model inference backend.
 *
 * Implementations wrap a specific runtime and handle tokenization and the forward pass. They return the model's
 * **own** vector at the graph's native width; the dimension policy — Matryoshka truncation to the service's 256,
 * together with the L2 re-normalization that truncation makes necessary — belongs to [EmbeddingService], whose
 * implementation keeps it in one place. An engine that truncated as well would truncate twice and normalize a
 * vector that is about to be normalized again.
 */
interface EmbeddingInferenceEngine {
    /** Initialize the engine with the given model path. Returns true on success. */
    fun initialize(modelPath: String): Boolean

    /** Encode text into a dense vector at the model's own (native) width. */
    fun embed(text: String): FloatArray

    /** Release resources. */
    fun close()
}
