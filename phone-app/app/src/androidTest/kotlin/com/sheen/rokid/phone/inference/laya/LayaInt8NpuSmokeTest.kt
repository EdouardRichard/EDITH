// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laya.LayaEngine
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Does the **INT8-quantized** Laya encoder *run* on this MediaTek NPU?
 *
 * This question is deliberately separate from accuracy. The float graph compiled onto the APU in one
 * partition and then returned all-Inf (see `LayaNpuSmokeTest`); an int8-activation graph is a different
 * computation type, and whether NeuroPilot compiles/executes it is not implied by either result.
 *
 * The quantized graph's signature inputs/outputs are INT8, and LiteRT's Kotlin API does not expose a
 * tensor's quantization parameters, so the scales/zero points below are **read from the model file**
 * (`.local/npu-research/laya-int8/...`, flatbuffer, on the Windows side) and pinned here:
 *
 *   inputs_embeds   scale=0.002889476018026471 zp=-18
 *   attention_mask  scale=0.003921568859368563 zp=-128   (== 1/255, maps 0/1 onto int8's full range)
 *   qtype_onehot    scale=0.003921568859368563 zp=-128
 *   token_logits    scale=0.031179068610072136 zp=-40
 *   pooled_cls      scale=6.529629230499268    zp=7
 *
 * Logs rather than asserts; a refusal or an Inf is the measurement.
 */
@RunWith(AndroidJUnit4::class)
class LayaInt8NpuSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir get() = context.filesDir

    private val modelName = "laya_ml_s256_embeds_int8_ai8.tflite"

    // (scale, zero point) as read from the quantized model's flatbuffer.
    private val embedsQ = 0.002889476018026471 to -18
    private val maskQ = 0.003921568859368563 to -128
    private val qtypeQ = 0.003921568859368563 to -128
    private val tokenLogitsQ = 0.031179068610072136 to -40
    private val pooledClsQ = 6.529629230499268 to 7

    @Test
    fun int8GraphIsProbedOnTheNpu() {
        val vendor = LayaEngine.npuVendor(context)
        Assume.assumeTrue("this APK carries no NPU runtime", vendor != null)
        val modelFile = File(filesDir, modelName)
        Assume.assumeTrue("$modelName not provisioned in filesDir", modelFile.isFile)

        val libDir = context.applicationInfo.nativeLibraryDir
        val environment =
            com.google.ai.edge.litert.Environment.create(
                context,
                mapOf(
                    com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                    com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
                ),
            )
        Log.i(TAG, "INT8_MODEL_BYTES=${modelFile.length()} VENDOR=${vendor!!.name}")

        val embedsFloat = FloatArray(LayaOfficialHost.WINDOW * 768) { i -> ((i % 11) - 5) * 0.02f }
        val maskFloat = FloatArray(LayaOfficialHost.WINDOW) { if (it < 20) 1f else 0f }
        val qtypeFloat = floatArrayOf(0f, 0f, 1f)
        val embedsI8 = quantize(embedsFloat, embedsQ)
        val maskI8 = quantize(maskFloat, maskQ)
        val qtypeI8 = quantize(qtypeFloat, qtypeQ)

        for (accelerator in listOf(com.google.ai.edge.litert.Accelerator.NPU, com.google.ai.edge.litert.Accelerator.CPU)) {
            var model: com.google.ai.edge.litert.CompiledModel? = null
            val buffers = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
            try {
                val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator).apply {
                    if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                        cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                    }
                }
                val compiled = System.nanoTime()
                model = com.google.ai.edge.litert.CompiledModel.create(modelFile.absolutePath, options, environment)
                val compileMs = elapsed(compiled)

                fun buf(name: String, input: Boolean) =
                    (if (input) model!!.createInputBuffer(name, SIGNATURE) else model!!.createOutputBuffer(name, SIGNATURE))
                        .also { buffers += it }

                val embeds = buf("inputs_embeds", true)
                val mask = buf("attention_mask", true)
                val qtype = buf("qtype_onehot", true)
                val tokenLogits = buf("token_logits", false)
                val pooledCls = buf("pooled_cls", false)
                embeds.writeInt8(embedsI8)
                mask.writeInt8(maskI8)
                qtype.writeInt8(qtypeI8)

                val started = System.nanoTime()
                model.run(
                    mapOf("inputs_embeds" to embeds, "attention_mask" to mask, "qtype_onehot" to qtype),
                    mapOf("token_logits" to tokenLogits, "pooled_cls" to pooledCls),
                    SIGNATURE,
                )
                val firstRunMs = elapsed(started)

                // One warm-up, then five timed main-graph runs: a single sample is not a latency reading
                // (the publisher's own figures are medians of 200, and this project's CPU/GPU numbers are
                // medians of 5).
                val samples = (1..5).map {
                    val t = System.nanoTime()
                    model.run(
                        mapOf("inputs_embeds" to embeds, "attention_mask" to mask, "qtype_onehot" to qtype),
                        mapOf("token_logits" to tokenLogits, "pooled_cls" to pooledCls),
                        SIGNATURE,
                    )
                    elapsed(t)
                }.sorted()
                val logits = dequantize(tokenLogits.readInt8(), tokenLogitsQ)
                val pooled = dequantize(pooledCls.readInt8(), pooledClsQ)
                Log.i(
                    TAG,
                    "INT8_${accelerator.name} compileMs=$compileMs firstRunMs=$firstRunMs " +
                        "medianMs=${samples[2]} minMs=${samples.first()} maxMs=${samples.last()} " +
                        "samples=$samples",
                )
                Log.i(
                    TAG,
                    "INT8_${accelerator.name} tokenLogits[${describe(logits)}] pooledCls[${describe(pooled)}]",
                )
                Log.i(TAG, "INT8_${accelerator.name}_TOKEN_HEAD=${logits.take(8).toList()}")
            } catch (failure: Throwable) {
                Log.e(TAG, "INT8_${accelerator.name}_FAILED ${failure.javaClass.name}: ${failure.message}")
            } finally {
                buffers.forEach { runCatching { it.close() } }
                runCatching { model?.close() }
            }
        }
    }

    private fun quantize(values: FloatArray, quant: Pair<Double, Int>): ByteArray {
        val (scale, zero) = quant
        return ByteArray(values.size) { i ->
            val q = Math.round(values[i] / scale).toInt() + zero
            q.coerceIn(-128, 127).toByte()
        }
    }

    private fun dequantize(values: ByteArray, quant: Pair<Double, Int>): FloatArray {
        val (scale, zero) = quant
        return FloatArray(values.size) { i -> ((values[i].toInt() - zero) * scale).toFloat() }
    }

    private fun describe(values: FloatArray): String {
        var nan = 0
        var inf = 0
        var maxAbs = 0.0
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (v in values) {
            when {
                v.isNaN() -> nan++
                v.isInfinite() -> inf++
                else -> {
                    maxAbs = maxOf(maxAbs, kotlin.math.abs(v.toDouble()))
                    minV = minOf(minV, v.toDouble())
                    maxV = maxOf(maxV, v.toDouble())
                }
            }
        }
        return "n=${values.size} nan=$nan inf=$inf min=${if (minV == Double.MAX_VALUE) 0 else minV} " +
            "max=${if (maxV == -Double.MAX_VALUE) 0 else maxV} maxAbsFinite=$maxAbs"
    }

    private fun elapsed(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private companion object {
        const val TAG = "LayaInt8Npu"
        const val SIGNATURE = "serving_default"
    }
}
