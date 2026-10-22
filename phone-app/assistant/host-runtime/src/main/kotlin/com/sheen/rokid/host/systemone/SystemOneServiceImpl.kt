package com.sheen.rokid.host.systemone

import com.sheen.rokid.plugin.FailureReason
import com.sheen.rokid.plugin.LayaInferenceEngine
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.SystemOneCriterion
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * System-One decision model service implementation.
 *
 * Lifecycle:
 * - Construction starts async model loading (non-blocking)
 * - LOADING → READY on success (with warm-up), or LOADING → UNAVAILABLE on failure
 * - No pause/resume — model stays resident once loaded
 * - Thread-safe: inference calls are serialized internally
 *
 * The service's job is everything **around** the model: provisioning, the status machine, serialization, the
 * warm-up, and translating a backend throw into the contract's own [FailureReason] vocabulary. It no longer
 * assembles positional arguments — the question is data, and [LayaInferenceEngine] takes it whole.
 *
 * @param modelPath The service's own provisioning assertion. The publisher's host resolves its assets from
 *   `context.filesDir` and re-validates them in its constructor, so this path is what the *host* process checks
 *   before handing the engine over; making the location configurable is a Phase B todo carried by the service, not
 *   by an engine parameter.
 * @param engine Inference engine implementation (defaults to [StubLayaEngine] for JVM tests; production injects an
 *   engine backed by the publisher's host from phone-app)
 */
class SystemOneServiceImpl(
    private val modelPath: String,
    private val engine: LayaInferenceEngine = StubLayaEngine(),
) : SystemOneService {

    private val _status = AtomicReference(SystemOneStatus.LOADING)
    private val _modelInfo = AtomicReference<ModelInfo?>(null)
    private val lock = Any()

    override val isAvailable: Boolean get() = _status.get() == SystemOneStatus.READY
    override val status: SystemOneStatus get() = _status.get()
    override val modelInfo: ModelInfo? get() = _modelInfo.get()

    init {
        Thread({ loadModel() }, "SystemOne-Loader").apply {
            isDaemon = true
            start()
        }
    }

    private fun loadModel() {
        try {
            val loaded = ModelLoader.load(modelPath)
            if (loaded == null) {
                _status.set(SystemOneStatus.UNAVAILABLE)
                return
            }

            if (!engine.initialize()) {
                _status.set(SystemOneStatus.UNAVAILABLE)
                return
            }

            warmUp()

            _modelInfo.set(
                ModelInfo(
                    name = loaded.name,
                    version = "unknown",
                    quantization = "unknown",
                    parameterCount = "unknown",
                    contextWindow = WINDOW,
                )
            )
            _status.set(SystemOneStatus.READY)
        } catch (e: Exception) {
            System.err.println("[SystemOne] Load failed: ${e.message}")
            _status.set(SystemOneStatus.UNAVAILABLE)
        }
    }

    /**
     * One dummy choice before the service reports READY, to pay the graph's first-call cost here rather than on the
     * proactive gate's deadline.
     *
     * A failed warm-up is **not** fatal: the graph may compile lazily, and refusing to become READY because a dummy
     * question failed would turn a cold first call into no service at all.
     */
    private fun warmUp() {
        try {
            engine.ask(WARMUP_STATE, WARMUP_QUESTION)
        } catch (_: Exception) {
            // Non-fatal by design; see above.
        }
    }

    override fun ask(state: String, question: SystemOneQuestion): SystemOneResult {
        synchronized(lock) {
            if (!isAvailable) {
                return SystemOneResult.Failure(
                    reason = FailureReason.MODEL_NOT_LOADED,
                    message = "System-One model is not loaded",
                )
            }
            val started = System.nanoTime()
            return try {
                SystemOneResult.Success(engine.ask(state, question), elapsedMillis(started))
            } catch (failure: Exception) {
                // Class name only. `message` is a contract field a caller may log, and the publisher's own decoder
                // builds its error text out of the question's criterion labels — which are the caller's vocabulary,
                // not ours to leak.
                SystemOneResult.Failure(
                    reason = FailureReason.ENGINE_UNAVAILABLE,
                    message = failure::class.simpleName ?: failure.javaClass.name,
                )
            }
        }
    }

    private fun elapsedMillis(startedNanos: Long): Double = (System.nanoTime() - startedNanos) / 1_000_000.0

    private companion object {
        /** The window the publisher validated on device (256); the 512 variant costs 2.5-3x the latency. */
        const val WINDOW = 512

        const val WARMUP_STATE = "warmup"

        /** A choice form: the graph answers it without needing a real state, and it is the cheapest to decode. */
        val WARMUP_QUESTION = SystemOneQuestion(
            type = SystemOneQuestionType.CHOICE,
            instructions = "warmup",
            criteria = listOf(SystemOneCriterion("a"), SystemOneCriterion("b")),
        )
    }
}
