// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya
import android.content.Context
import com.laya.LayaEngine
import java.io.Closeable

/**
 * The app's single entry point to the publisher's Laya host.
 *
 * The host itself is vendored verbatim under `com.laya` (see `com/laya/LICENSES/`); this class exists only
 * to give the rest of the app a stable, narrow surface and to state the constraints that come with it.
 *
 * **Why the publisher's code and not our own.** `laya_host.py`'s behaviour is the contract: the prompt
 * template, the option cap, the marker positions, the temperature buckets, and a softmax that reproduces
 * NumPy's float32 pairwise reduction including its 8-accumulator order. The publisher validated the Kotlin
 * port against captured Python references on 402 rows, 300 tokenizer stress strings, and 118,554,624
 * embedding values with a maximum absolute error of 0. Transcribing that by hand would replace a verified
 * implementation with an unverified one.
 *
 * **Constraints this wrapper inherits, all of which are the publisher's and all of which the rest of the
 * app has to live with:**
 *
 * - **Files live in `context.filesDir`.** `LayaEngine` resolves its assets from the app's *internal* files
 *   directory, not the external one, and it checks for them at construction. The 679 MB phone set must be
 *   provisioned there. Making the location a user setting would mean patching the vendored host.
 * - **One `Environment` and one native thread per process** (`LayaProcessRuntime`), and compiled graphs are
 *   cached per `(window, backend, storage)`. That matches spec §2.5's "load once, stay resident" — and it
 *   matches the hardware limit measured on the K40, where a handful of `CompiledModel` instantiations in
 *   one process makes `create` fail outright.
 * - **Construction is not cheap and does real work**: it loads the tokenizer (~1.5 s in the sample's debug
 *   build), parses the calibration, and memory-maps the 375 MiB token table. It must not run on a UI thread.
 * - **`initialize(backend)` compiles the graph**, and a GPU compile is ~1.3 s for this graph, the first NPU
 *   compile about 40 s (cached afterwards).
 * - **The NPU needs its vendor's runtime libraries in the APK.** Qualcomm needs the QNN set; the delivery
 *   target — a Redmi K70 Ultra on MT6989 — is MediaTek, whose NeuroPilot dispatch and compiler plugin come
 *   from LiteRT 2.3.0 (`com.google.ai.edge.litert:litert-npu-runtime-mediatek`). Without a matching pair
 *   `initialize(NPU)` throws with a message pointing at the README, because the fallback that can answer is the
 *   GPU and pretending otherwise would be worse than refusing.
 */
class LayaOfficialHost(
    context: Context,
    storage: LayaEngine.Storage = LayaEngine.Storage.WFP16,
) : Closeable {

    /** The publisher's engine. Exposed so callers can reach its timings and raw tensors. */
    val engine = LayaEngine(context, storage)

    /** Tokenizer parse duration, reported by the host. */
    val tokenizerLoadMs: Double get() = engine.tokenizerLoadMs

    /** Token-table mapping duration, reported by the host. */
    val embeddingLoadMs: Double get() = engine.embeddingLoadMs

    /** Compiles (or fetches from cache) both graphs. Blocks; call off the main thread. */
    fun initialize(backend: LayaEngine.Backend = LayaEngine.Backend.CPU, window: Int = WINDOW) =
        engine.initialize(backend, window)

    /**
     * One yes/no question over a state — the shape D60 gives Laya: "does the user need help right now?".
     *
     * `statement` is the proposition being judged, `state` the ambient context. The returned value is the
     * calibrated probability that the statement holds, in [0, 1]; `null` criteria means the host renders
     * its own default true/false option text.
     */
    fun noul(
        state: Any?,
        statement: String,
        backend: LayaEngine.Backend = LayaEngine.Backend.CPU,
        questionId: String = "noul",
    ): Double {
        val result = engine.answer(state, question(statement), backend, questionId, WINDOW)
        return result.answer["noul"] as? Double
            ?: error("noul answer missing from ${result.answer.keys}")
    }

    /**
     * One multiple-choice question over a state.
     *
     * The criteria map is ordered and its keys are the labels the answer comes back under, so iteration
     * order is the contract: `LinkedHashMap`, as the publisher's `normalize` requires.
     */
    fun choice(
        state: Any?,
        instructions: String,
        criteria: Map<String, String>,
        backend: LayaEngine.Backend = LayaEngine.Backend.CPU,
        questionId: String = "choice",
    ): Map<String, Any?> {
        val result = engine.answer(
            state,
            linkedMapOf("type" to "choice", "instructions" to instructions, "criteria" to LinkedHashMap(criteria)),
            backend,
            questionId,
            WINDOW,
        )
        return result.answer
    }

    /**
     * One question in the contract's own shape (edith-0.2 Phase B, B11).
     *
     * The mapping is [officialQuestion] and the decode is [LayaAnswerDecoder], both pure — this method is the thin
     * half that needs the model. A map the question did not ask for is an error rather than a guess: the caller's
     * safe default is silence, and inventing an answer here would be inventing a decision.
     */
    fun ask(
        state: Any?,
        question: com.sheen.rokid.plugin.SystemOneQuestion,
        backend: LayaEngine.Backend = LayaEngine.Backend.CPU,
        questionId: String = "ask",
    ): com.sheen.rokid.plugin.SystemOneAnswer {
        val result = engine.answer(state, officialQuestion(question), backend, questionId, WINDOW)
        return LayaAnswerDecoder.decode(question, result.answer)
            ?: error("the publisher's answer does not match the question: ${result.answer.keys}")
    }

    private fun question(statement: String) =
        linkedMapOf<String, Any?>("type" to "noul", "instructions" to statement, "criteria" to null)

    override fun close() = engine.close()

    companion object {
        /**
         * 256 is the shortest published window and the one the publisher validated on device. The 512
         * variant costs 2.5-3x the latency for headroom this use case does not need, and the state is
         * right-truncated to fit, so the ordering rule is: put what the decision depends on first.
         */
        const val WINDOW = 256

        /** The files the host requires, in `filesDir`, for either weight storage. */
        val REQUIRED_FILES = LayaEngine.REQUIRED_FILES + "laya_ml_s256_embeds_wfp16.tflite"
    }
}
