// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya

import android.content.Context
import com.laya.LayaEngine
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.LayaInferenceEngine
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion

/**
 * The production Laya engine: the publisher's host behind the contract's port.
 *
 * **Construction is deferred into [initialize].** Building the host parses the tokenizer, parses the calibration and
 * memory-maps the token table — about 3.3 s on the delivery target — and must not happen on the UI thread.
 * `SystemOneServiceImpl` runs [initialize] on its own loader thread, so building it there is what keeps the
 * constraint satisfied without this class having to own a thread.
 *
 * **One host per process.** Compiled graphs are cached per `(window, backend, storage)` and the K40 measured that a
 * handful of `CompiledModel` instantiations in one process makes `create` fail outright, so this holds exactly one
 * and [close] is the only thing that releases it. Spec §2.5's "load once, stay resident" is not a preference here,
 * it is the hardware's condition.
 *
 * **The backend is the GPU, because the delivery target needs it.** The plan's Global Constraints and the A5 evidence
 * both say so, with numbers: 742 ms CPU against 231 ms GPU for this graph, which is the difference between fitting
 * D60's event-driven cadence and not. The GPU path additionally costs ~3.4 s of compile at startup and is FP32-only
 * (`LayaEngine.createGraph` sets `Precision.FP32`; FP16 measured as NaN). LiteRT is pinned to **2.3.0** as of
 * 2026-10-14: 2.1.0 computed the GPU graph wrong, and 2.3.0 is what publishes the MediaTek NeuroPilot NPU runtime
 * the delivery target (MT6989) needs — taken with Kotlin 2.4.0, because 2.3.0's `litert-api` carries mv=[2,4,0].
 *
 * **CPU remains the one allowed degradation, and taking it is recorded.** A GPU compile that cannot run on the
 * device must not cost the wearer the whole assistant when a working CPU path exists, so [initialize] retries once on
 * CPU and says so on the same `P3_LAYA` line the successful path uses. `effectiveBackend` is the backend actually in
 * use, so nothing downstream can mistake a degraded gate for the required one. Passing CPU explicitly is honoured
 * exactly: this class never promotes a caller's choice back to GPU.
 */
class LayaInferenceEngineImpl(
    private val context: Context,
    internal val requestedBackend: LayaEngine.Backend = DEFAULT_BACKEND,
) : LayaInferenceEngine {

    /**
     * The backend the compiled graph actually uses, or `null` until [initialize] has compiled one.
     *
     * Deliberately **not** initialised to the requested backend: before a compile succeeds (and after a total failure)
     * no graph exists, and a field reading `GPU` while nothing was built is exactly the kind of value a later reader
     * believes (review finding 3). Read by `ask` and by the JVM test that pins the delivery target's default.
     */
    @Volatile
    var effectiveBackend: LayaEngine.Backend? = null
        private set

    @Volatile
    private var host: LayaOfficialHost? = null

    override fun initialize(): Boolean {
        if (initializeWith(requestedBackend)) return true
        val fallback = cpuFallbackFor(requestedBackend) ?: return false
        // The requested backend could not compile on this device. Refusing service would take the whole assistant
        // down where a working CPU path exists, so one retry is spent here - and the failing attempt has already been
        // logged with its own backend name, so a degraded gate can never read as the required one.
        P3Log.line("P3_LAYA fallback from=${requestedBackend.name} to=${fallback.name}")
        return initializeWith(fallback)
    }

    /**
     * Compiles [backend] once and keeps the host only if that worked.
     *
     * The duration is logged because the plan budgets this cold start (~3.4 s of GPU compile on top of ~3.3 s of
     * tokenizer work) and "the graph compiled but took 20 s" is the difference between a scheduled startup and a
     * first-event stall. Counts and names only, like every other `P3_` line.
     */
    private fun initializeWith(backend: LayaEngine.Backend): Boolean {
        val started = System.nanoTime()
        val created = LayaOfficialHost(context)
        return try {
            created.initialize(backend, LayaOfficialHost.WINDOW)
            host = created
            effectiveBackend = backend
            P3Log.line("P3_LAYA init backend=${backend.name} ok=true ms=${elapsedMillis(started)}")
            true
        } catch (_: Throwable) {
            // `SystemOneServiceImpl` turns a false into UNAVAILABLE, which is exactly the honest answer when neither
            // backend compiles: the plugin then refuses service rather than answering from a model that never loaded
            // (spec §1.3 C2). The half-built host is released rather than left holding a native runtime.
            runCatching { created.close() }
            P3Log.line("P3_LAYA init backend=${backend.name} ok=false ms=${elapsedMillis(started)}")
            false
        }
    }

    override fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer {
        val current = host ?: error("Laya is not initialized")
        // Non-null whenever `host` is: the two are assigned together under the same successful compile.
        return current.ask(state, question, checkNotNull(effectiveBackend))
    }

    override fun close() {
        host?.close()
        host = null
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    companion object {
        /**
         * The delivery target's backend: GPU, per the plan's Global Constraints and the A5 evidence (231 ms vs 742 ms).
         *
         * Named rather than written inline so the JVM test that pins it and the KDoc that justifies it cannot drift
         * from the constructor's actual default.
         */
        val DEFAULT_BACKEND: LayaEngine.Backend = LayaEngine.Backend.GPU
    }
}

/**
 * Which backend to try after [requested] failed to compile, or `null` when there is nothing left to try.
 *
 * Pure and separate from the SDK calls so the **degradation policy** is pinned without a device: CPU is the only step
 * down, and an explicitly requested CPU has none (retrying it would just spend another ~3 s on the same failure).
 * Whether the retry itself compiles is the device's answer, not this function's.
 */
internal fun cpuFallbackFor(requested: LayaEngine.Backend): LayaEngine.Backend? =
    if (requested == LayaEngine.Backend.CPU) null else LayaEngine.Backend.CPU
