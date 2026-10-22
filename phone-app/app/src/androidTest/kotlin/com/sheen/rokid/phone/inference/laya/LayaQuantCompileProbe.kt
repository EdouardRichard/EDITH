// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Compile-only probe for quantized variants, on both NPU and CPU.
 *
 * Purpose: when a quantized graph is refused by an accelerator, the useful question is WHICH
 * property of the graph the accelerator rejects. Weight-only quantization inserts a DEQUANTIZE
 * between the int8 weight and the consuming op, and the published recipe uses per-channel
 * (CHANNELWISE) weights. Both properties are separable, so variants that change exactly one of them
 * can localize the refusal:
 *
 *   gate_bnd_14_weight_only_wi8_afp32          per-channel weights, explicit dequantize
 *   gate_bnd_14_weightonly_pertensor           tensor-wise weights, same recipe otherwise
 *   gate_bnd_14_dynamic_wi8_afp32              dynamic-range (activation quantized on the fly)
 *
 * Nothing here asserts; the log line is the measurement.
 */
@RunWith(AndroidJUnit4::class)
class LayaQuantCompileProbe {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir get() = context.filesDir

    @Test
    fun everyVariantIsCompiledOnNpuAndCpu() {        val candidates = listOf(
            "gate_bnd_14_weight_only_wi8_afp32.tflite",
            "gate_bnd_14_weightonly_pertensor.tflite",
            "gate_bnd_14_weightonly_block32.tflite",
            "gate_bnd_14_dynamic_wi8_afp32.tflite",
            "gate_bnd_14_static_wi8_ai8.tflite",
            // Bisect of the per-channel refusal: the same per-channel weight-only recipe restricted
            // by module regex. Whichever subset turns the compile from FAILED to COMPILED localizes
            // the trigger, instead of leaving it as "the big graph is too big somehow".
            "gate_bnd_14_wo_ch_head.tflite",
            "gate_bnd_14_wo_ch_encoder.tflite",
            "gate_bnd_14_wo_ch_attn.tflite",
            // A different, tiny graph with the SAME per-channel weight-only recipe: if it is also
            // refused, the limitation is not specific to the Laya encoder; if it compiles, the
            // refusal is graph/pattern specific.
            "act_head_weight_only_CHANNEL.tflite",
        )
        val libDir = context.applicationInfo.nativeLibraryDir
        val environment = com.google.ai.edge.litert.Environment.create(
            context,
            mapOf(
                com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
            ),
        )
        for (name in candidates) {
            val f = File(filesDir, name)
            if (!f.isFile) {
                Log.w(TAG, "PROBE $name MISSING")
                continue
            }
            for (accelerator in listOf(com.google.ai.edge.litert.Accelerator.NPU,
                                       com.google.ai.edge.litert.Accelerator.CPU)) {
                var model: com.google.ai.edge.litert.CompiledModel? = null
                try {
                    val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator).apply {
                        if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                            cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                        }
                    }
                    val t0 = System.nanoTime()
                    model = com.google.ai.edge.litert.CompiledModel.create(f.absolutePath, options, environment)
                    Log.i(TAG, "PROBE $name ${accelerator.name} COMPILED ms=${"%.1f".format((System.nanoTime() - t0) / 1e6)}")
                } catch (failure: Throwable) {
                    Log.e(TAG, "PROBE $name ${accelerator.name} FAILED ${failure.javaClass.simpleName}: ${failure.message}")
                } finally {
                    runCatching { model?.close() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "LayaCompileProbe"
    }
}
