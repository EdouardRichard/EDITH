package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.laya.LayaEngine
import com.sheen.rokid.phone.inference.laya.LayaOfficialHost

/**
 * Debug-only instrument: run the Laya gate on the CPU and then on the NPU, logging one comparison per backend.
 *
 * Why this exists instead of an instrumentation test: on the K40, MIUI's installer refuses the androidTest APK
 * (`com.sheen.rokid.phone.test` gets `INSTALL_CANCELED_BY_USER` about 30 ms after its confirmation activity draws, before
 * a human can touch it), while the debug main APK installs. So the measurement comes from the app itself,
 * driven from the host shell, over the same exported-receiver pattern the other debug instruments use.
 *
 *   adb shell am broadcast -a edith.DEBUG_LAYA -n com.sheen.rokid.phone/.DebugLayaProbe
 *
 * **The CPU arm is a control, not a repeat measurement.** The NPU arm of an earlier revision killed the
 * process inside `CompiledModel.create`, and a crash alone cannot say whether the fault is the NPU path or the
 * probe: a backend that initialises cleanly in the same run, from the same host object, is what makes the
 * other arm's failure attributable.
 *
 * `finite=false` is the finding on a backend that DOES return; a process death is the finding on one that does
 * not. Neither is a latency reading, and a latency from a graph that computed nothing would not be one.
 */
class DebugLayaProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Thread {
            val app = context.applicationContext
            var host: LayaOfficialHost? = null
            try {
                Log.i(TAG, "PROBE start vendor=${LayaEngine.npuVendor(app) ?: "none"}")
                host = LayaOfficialHost(app, LayaEngine.Storage.WFP16)
                Log.i(TAG, "PROBE loaded tokenizerMs=${host.tokenizerLoadMs} embeddingsMs=${host.embeddingLoadMs}")

                for (backend in listOf(LayaEngine.Backend.CPU, LayaEngine.Backend.NPU)) {
                    try {
                        val compileStart = System.nanoTime()
                        host.initialize(backend)
                        val compileMs = (System.nanoTime() - compileStart) / 1_000_000.0
                        // One untimed call warms the path, then five timed ones: a single sample is not a
                        // latency reading, and the publisher's own figures are medians of 200.
                        var probability = host.noul(STATE, STATEMENT, backend)
                        val samples = (1..5).map {
                            val t = System.nanoTime()
                            probability = host.noul(STATE, STATEMENT, backend)
                            (System.nanoTime() - t) / 1_000_000.0
                        }.sorted()
                        Log.i(
                            TAG,
                            "PROBE ${backend.name} compileMs=%.1f finite=%s probability=%s medianMs=%.1f samples=%s"
                                .format(compileMs, probability.isFinite(), probability, samples[2], samples),
                        )
                    } catch (failure: Throwable) {
                        // A refusal is a result too: NPU_UNAVAILABLE means this APK carries no runtime for this SoC.
                        Log.e(TAG, "PROBE ${backend.name} FAILED ${failure.javaClass.name}: ${failure.message}")
                    }
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "PROBE SETUP_FAILED ${failure.javaClass.name}: ${failure.message}")
            } finally {
                runCatching { host?.close() }
                Log.i(TAG, "PROBE done")
            }
        }.start()
    }

    private companion object {
        const val TAG = "LayaDebugProbe"
        const val ACTION = "edith.DEBUG_LAYA"

        /**
         * A realistic Chinese state and NO time field: D60/C10 makes the gate a pure function of the state, so
         * a time feature would contradict the decision the gate implements.
         */
        const val STATE =
            "{\"foreground\":\"com.tencent.mm\",\"notification_category\":\"msg\"," +
                "\"recent_transcript\":\"\u8fd9\u4e2a\u4f18\u60e0\u5238\u600e\u4e48\u7528\u554a\"}"

        /** The proposition the gate judges; the deployment wording, pinned by the fine-tune spec. */
        const val STATEMENT = "\u7528\u6237\u6b64\u523b\u9700\u8981\u5e2e\u52a9"
    }
}
