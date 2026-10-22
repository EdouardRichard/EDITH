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
 * On which accelerators does each quantized SHAPE compile?
 *
 * The per-channel bisect left one property untested: how the weight-only graphs behave on the GPU,
 * whose arithmetic is fp16 internally. If a quantized-weight graph compiles and runs finite on the
 * GPU, that is the one place "int8 weights + fp16 compute" could still be a usable combination --
 * on the NPU the same graphs either fail to compile (per-channel) or output all-Inf (float
 * activations through the float path).
 *
 * Compile-only: the point is which accelerator accepts which shape. Logs, never asserts.
 */
@RunWith(AndroidJUnit4::class)
class LayaAcceleratorMatrix {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir get() = context.filesDir

    @Test
    fun everyAcceleratorIsProbedForEveryQuantizedShape() {
        val candidates = listOf(
            "laya_ml_s256_embeds_wfp16.tflite",             // publisher fp16, float activations
            "gate_bnd_14_weight_only_wi8_afp32.tflite",     // int8 per-channel weights, float acts
            "gate_bnd_14_weightonly_pertensor.tflite",      // int8 per-tensor weights, float acts
            "gate_bnd_14_static_wi8_ai8.tflite",            // int8 weights + INT8 activations
        )
        val accelerators = listOf(
            com.google.ai.edge.litert.Accelerator.NPU,
            com.google.ai.edge.litert.Accelerator.GPU,
            com.google.ai.edge.litert.Accelerator.CPU,
        )
        val libDir = context.applicationInfo.nativeLibraryDir
        val environment = com.google.ai.edge.litert.Environment.create(
            context,
            mapOf(
                com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
            ),
        )
        Assume.assumeTrue("no fixture graphs provisioned", File(filesDir, candidates[0]).isFile)

        for (name in candidates) {
            val f = File(filesDir, name)
            if (!f.isFile) {
                Log.w(TAG, "MATRIX $name MISSING")
                continue
            }
            for (accelerator in accelerators) {
                var model: com.google.ai.edge.litert.CompiledModel? = null
                try {
                    val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator).apply {
                        if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                            cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                        }
                    }
                    val t0 = System.nanoTime()
                    model = com.google.ai.edge.litert.CompiledModel.create(f.absolutePath, options, environment)
                    Log.i(TAG, "MATRIX ${name.padEnd(42)} ${accelerator.name.padEnd(4)} COMPILED " +
                        "ms=${"%.0f".format((System.nanoTime() - t0) / 1e6)}")
                } catch (failure: Throwable) {
                    Log.e(TAG, "MATRIX ${name.padEnd(42)} ${accelerator.name.padEnd(4)} FAILED " +
                        failure.javaClass.simpleName)
                } finally {
                    runCatching { model?.close() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "LayaAccelMatrix"
    }
}
