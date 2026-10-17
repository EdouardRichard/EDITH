// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * On-device accuracy gate for the quantized fine-tuned graphs.
 *
 * The synthetic-input smoke tests answer "does it run"; they cannot answer "does it still decide
 * correctly", because the question is whether quantization moved the marker logits. This test feeds
 * the real 201-row fixture to the graphs the NPU accepts and compares the DECISIONS.
 *
 * Inputs are rebuilt ON THE DEVICE from the canonical 7-file set (the same files the Kotlin host
 * uses): `token_embeddings_fp16.bin` is read with RandomAccessFile at `id * 768 * 2`, so no
 * multi-hundred-megabyte blob has to be assembled or shipped for the test. `device_spec.json`
 * carries only what cannot be looked up: sequence ids, marker positions, the publisher's base
 * probabilities, and the fine-tuned float reference logits (gen_device_spec.py).
 *
 * There is no int16-activation arm: LiteRT's Kotlin `TensorBuffer` exposes int/float/int8/bool/long
 * only (verified with javap against litert-api 2.3.0), so an int16-I/O graph cannot be driven from
 * this host API. That is a host-API limit, not a statement about the NPU.
 *
 * Probability conversion follows laya_host.probabilities(): marker logits divided by
 * temperature_by_options[bucket] (empty in the shipped calibration) falling back to
 * temperature[qtype], then softmax. Comparing raw softmax instead inflates differences ~30x and
 * produced a false "the graph is broken" verdict once in this session.
 *
 * Logs rather than asserts.
 */
@RunWith(AndroidJUnit4::class)
class LayaQuantDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir get() = context.filesDir
    private val temperatures = floatArrayOf(0.8428140878677368f, 1.0f, 0.85593581199646f)

    private data class Row(
        val id: String,
        val ids: IntArray,
        val markers: IntArray,
        val qtype: Int,
        val pBase: FloatArray,
        val refLogits: FloatArray,
    )

    @Test
    fun quantizedGraphsAreScoredOnTheRealFixture() {
        val spec = File(filesDir, "device_spec.json")
        val table = File(filesDir, "token_embeddings_fp16.bin")
        Assume.assumeTrue("device_spec.json missing", spec.isFile)
        Assume.assumeTrue("token_embeddings_fp16.bin missing", table.isFile)

        val rows = readSpec(spec)
        Log.i(TAG, "GATE_SPEC rows=${rows.size} tableBytes=${table.length()}")

        score("FT_AI8", "gate_bnd_14_static_wi8_ai8.tflite", rows, table, int8Io = true)
        // blockwise-32 keeps more weight precision than per-tensor while still compiling, so it is
        // the remaining weight-only candidate that could be both accurate and NPU-eligible.
        score("FT_WO_BLOCK32", "gate_bnd_14_weightonly_block32.tflite", rows, table, int8Io = false)
        // The float claim, on THIS device, in THIS session, with per-value output. The earlier
        // "nonFiniteRows=201" came from this harness but did not say whether the values are NaN or
        // Inf; and the "all +Inf, independent of input magnitude" statement in the evidence was
        // measured by a different session, so it is re-measured here rather than inherited.
        dumpValues("FT_WFP16_DUMP", "laya_ml_s256_embeds_wfp16.tflite", rows, table)
    }

    /** Run the first fixture row on the NPU and log the actual numbers: counts, min/max, samples. */
    private fun dumpValues(tag: String, modelName: String, rows: List<Row>, tableFile: File) {
        val modelFile = File(filesDir, modelName)
        if (!modelFile.isFile) {
            Log.w(TAG, "$tag MISSING $modelName")
            return
        }
        val libDir = context.applicationInfo.nativeLibraryDir
        val environment = com.google.ai.edge.litert.Environment.create(
            context,
            mapOf(
                com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
            ),
        )
        var model: com.google.ai.edge.litert.CompiledModel? = null
        val buffers = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
        RandomAccessFile(tableFile, "r").use { table ->
            try {
                model = com.google.ai.edge.litert.CompiledModel.create(
                    modelFile.absolutePath,
                    com.google.ai.edge.litert.CompiledModel.Options(
                        com.google.ai.edge.litert.Accelerator.NPU),
                    environment)
                val inEmbeds = model.createInputBuffer("inputs_embeds", SIGNATURE).also { buffers += it }
                val inMask = model.createInputBuffer("attention_mask", SIGNATURE).also { buffers += it }
                val inQtype = model.createInputBuffer("qtype_onehot", SIGNATURE).also { buffers += it }
                val outLogits = model.createOutputBuffer("token_logits", SIGNATURE).also { buffers += it }
                val outPooled = model.createOutputBuffer("pooled_cls", SIGNATURE).also { buffers += it }

                // row 0 AND an all-zero input: the second is what makes "independent of input
                // magnitude" a measurement rather than an inheritance.
                for ((label, zero) in listOf("ROW0" to false, "ZERO_INPUT" to true)) {
                    val embeds = FloatArray(WINDOW * WIDTH)
                    val mask = FloatArray(WINDOW)
                    val qtype = FloatArray(3)
                    if (!zero) {
                        val row = rows[0]
                        for (j in row.ids.indices) {
                            table.seek(row.ids[j].toLong() * WIDTH * 2)
                            for (c in 0 until WIDTH) embeds[j * WIDTH + c] = halfToFloat(table.readUnsignedShort())
                            mask[j] = 1.0f
                        }
                        qtype[row.qtype] = 1.0f
                    }
                    inEmbeds.writeFloat(embeds)
                    inMask.writeFloat(mask)
                    inQtype.writeFloat(qtype)
                    model.run(mapOf("inputs_embeds" to inEmbeds, "attention_mask" to inMask,
                                    "qtype_onehot" to inQtype),
                              mapOf("token_logits" to outLogits, "pooled_cls" to outPooled), SIGNATURE)
                    Log.i(TAG, "$tag $label token_logits ${describeExact(outLogits.readFloat())}")
                    Log.i(TAG, "$tag $label pooled_cls  ${describeExact(outPooled.readFloat())}")
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "$tag NPU_FAILED ${failure.javaClass.name}: ${failure.message}")
            } finally {
                buffers.forEach { runCatching { it.close() } }
                runCatching { model?.close() }
            }
        }
    }

    /** Explicit NaN/Inf counts and the first few raw values, so "all Inf" is verifiable. */
    private fun describeExact(v: FloatArray): String {
        var nan = 0
        var posInf = 0
        var negInf = 0
        var finite = 0
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (x in v) {
            when {
                x.isNaN() -> nan++
                x == Float.POSITIVE_INFINITY -> posInf++
                x == Float.NEGATIVE_INFINITY -> negInf++
                else -> {
                    finite++
                    minV = minOf(minV, x.toDouble())
                    maxV = maxOf(maxV, x.toDouble())
                }
            }
        }
        return "n=${v.size} nan=$nan +Inf=$posInf -Inf=$negInf finite=$finite " +
            "min=${if (finite > 0) minV else Double.NaN} max=${if (finite > 0) maxV else Double.NaN} " +
            "first8=${v.take(8)}"
    }

    private fun score(
        tag: String,
        modelName: String,
        rows: List<Row>,
        tableFile: File,
        int8Io: Boolean,
        accelerators: List<com.google.ai.edge.litert.Accelerator> =
            listOf(com.google.ai.edge.litert.Accelerator.NPU),
    ) {
        val modelFile = File(filesDir, modelName)
        if (!modelFile.isFile) {
            Log.w(TAG, "$tag MISSING $modelName")
            return
        }
        val libDir = context.applicationInfo.nativeLibraryDir
        val environment = com.google.ai.edge.litert.Environment.create(
            context,
            mapOf(
                com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
            ),
        )
        var model: com.google.ai.edge.litert.CompiledModel? = null
        val buffers = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
        RandomAccessFile(tableFile, "r").use { table ->
            for (accelerator in accelerators) {
            var model: com.google.ai.edge.litert.CompiledModel? = null
            val buffers = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
            val accelTag = "$tag[${accelerator.name}]"
            try {
                val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator).apply {
                    if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                        cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                    }
                }
                val t0 = System.nanoTime()
                model = com.google.ai.edge.litert.CompiledModel.create(
                    modelFile.absolutePath, options, environment)
                val compileMs = elapsed(t0)

                val inEmbeds = model.createInputBuffer("inputs_embeds", SIGNATURE).also { buffers += it }
                val inMask = model.createInputBuffer("attention_mask", SIGNATURE).also { buffers += it }
                val inQtype = model.createInputBuffer("qtype_onehot", SIGNATURE).also { buffers += it }
                val outLogits = model.createOutputBuffer("token_logits", SIGNATURE).also { buffers += it }
                val outPooled = model.createOutputBuffer("pooled_cls", SIGNATURE).also { buffers += it }

                var flips = 0
                var maxDprob = 0.0
                var sumDprob = 0.0
                var worst = ""
                var baseAgree = 0
                var nonFinite = 0
                val rowMs = ArrayList<Double>(rows.size)

                for (row in rows) {
                    val embeds = FloatArray(WINDOW * WIDTH)
                    val mask = FloatArray(WINDOW)
                    for (j in row.ids.indices) {
                        // Exact row lookup: the same little-endian fp16 gather the host performs.
                        table.seek(row.ids[j].toLong() * WIDTH * 2)
                        for (c in 0 until WIDTH) {
                            embeds[j * WIDTH + c] = halfToFloat(table.readUnsignedShort())
                        }
                        mask[j] = 1.0f
                    }
                    val qtype = FloatArray(3)
                    qtype[row.qtype] = 1.0f

                    if (int8Io) {
                        inEmbeds.writeInt8(quantize8(embeds, EMBEDS_Q))
                        inMask.writeInt8(quantize8(mask, MASK_Q))
                        inQtype.writeInt8(quantize8(qtype, QTYPE_Q))
                    } else {
                        inEmbeds.writeFloat(embeds)
                        inMask.writeFloat(mask)
                        inQtype.writeFloat(qtype)
                    }
                    val ts = System.nanoTime()
                    model.run(
                        mapOf("inputs_embeds" to inEmbeds, "attention_mask" to inMask,
                              "qtype_onehot" to inQtype),
                        mapOf("token_logits" to outLogits, "pooled_cls" to outPooled), SIGNATURE)
                    rowMs.add(elapsed(ts))

                    val lg = if (int8Io) dequantize8(outLogits.readInt8(), TOKEN_LOGITS_Q)
                             else outLogits.readFloat()
                    if (lg.any { !it.isFinite() }) nonFinite++
                    val marker = FloatArray(row.markers.size) { j -> lg[row.markers[j]] }
                    val t = temperatures[row.qtype]
                    val p = softmaxTemp(marker, t)
                    val pref = softmaxTemp(row.refLogits, t)
                    val d = maxAbs(p, pref)
                    sumDprob += d
                    if (d > maxDprob) { maxDprob = d; worst = row.id }
                    if (argmax(p) != argmax(pref)) flips++
                    if (argmax(p) == argmax(row.pBase)) baseAgree++
                }
                rowMs.sort()
                Log.i(TAG, "$tag NPU compileMs=$compileMs rows=${rows.size} " +
                    "medianRowMs=${rowMs[rowMs.size / 2]} maxRowMs=${rowMs.last()}")
                Log.i(TAG, "$tag FLIPS_vs_float=$flips/${rows.size} nonFiniteRows=$nonFinite " +
                    "maxDprob=$maxDprob meanDprob=${sumDprob / rows.size} worst=$worst")
                Log.i(TAG, "$tag argmaxEqualsBaseFixture=$baseAgree/${rows.size}")
            } catch (failure: Throwable) {
                Log.e(TAG, "$tag NPU_FAILED ${failure.javaClass.name}: ${failure.message}")
            } finally {
                buffers.forEach { runCatching { it.close() } }
                runCatching { model?.close() }
            }
        }
    }

    /**
     * Is the weight-only graph's failure specific to the NPU delegate, or does the graph fail
     * everywhere?
     *
     * This matters for how the finding should be recorded. If the same file compiles on the CPU
     * accelerator and fails only through the MediaTek dispatch path, the fault is isolated to that
     * path (and is a vendor-side blocker for weight-only on this device). If it fails on the CPU
     * too, the artifact itself is broken and the vendor attribution is wrong. Same file, same
     * process, same instantiation code -- only the accelerator changes.
     */
    @Test
    fun weightOnlyCompileIsProbedOnEveryAccelerator() {
        val modelFile = File(filesDir, "gate_bnd_14_weight_only_wi8_afp32.tflite")
        Assume.assumeTrue("weight-only graph not provisioned", modelFile.isFile)
        val libDir = context.applicationInfo.nativeLibraryDir
        val environment = com.google.ai.edge.litert.Environment.create(
            context,
            mapOf(
                com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
            ),
        )
        for (accelerator in listOf(com.google.ai.edge.litert.Accelerator.NPU,
                                   com.google.ai.edge.litert.Accelerator.CPU,
                                   com.google.ai.edge.litert.Accelerator.GPU)) {
            var model: com.google.ai.edge.litert.CompiledModel? = null
            try {
                val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator).apply {
                    if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                        cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                    }
                }
                val t0 = System.nanoTime()
                model = com.google.ai.edge.litert.CompiledModel.create(
                    modelFile.absolutePath, options, environment)
                Log.i(TAG, "WO_ACCEL ${accelerator.name} COMPILED ms=${"%.1f".format(elapsed(t0))}")
            } catch (failure: Throwable) {
                Log.e(TAG, "WO_ACCEL ${accelerator.name} FAILED " +
                    "${failure.javaClass.name}: ${failure.message}")
            } finally {
                runCatching { model?.close() }
            }
        }
    }

    /** IEEE-754 binary16 -> float, so the device reads the table exactly as the host does. */
    private fun halfToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 1
        val exp = (bits ushr 10) and 0x1F
        val frac = bits and 0x3FF
        val value: Float = when {
            exp == 0 -> (frac / 1024.0f) * Math.pow(2.0, -14.0).toFloat()
            exp == 31 -> if (frac == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1.0f + frac / 1024.0f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
        }
        return if (sign == 1) -value else value
    }

    private fun quantize8(values: FloatArray, q: Pair<Double, Int>): ByteArray {
        val (scale, zero) = q
        return ByteArray(values.size) {
            val v = Math.round(values[it] / scale).toInt() + zero
            v.coerceIn(-128, 127).toByte()
        }
    }

    private fun dequantize8(v: ByteArray, q: Pair<Double, Int>): FloatArray {
        val (scale, zero) = q
        return FloatArray(v.size) { ((v[it].toInt() - zero) * scale).toFloat() }
    }

    private fun softmaxTemp(values: FloatArray, t: Float): FloatArray {
        val scale = max(1e-3f, t)
        var m = Float.NEGATIVE_INFINITY
        for (v in values) m = max(m, v / scale)
        var s = 0.0
        val e = DoubleArray(values.size)
        for (i in values.indices) { e[i] = exp((values[i] / scale - m).toDouble()); s += e[i] }
        return FloatArray(values.size) { (e[it] / s).toFloat() }
    }

    private fun argmax(v: FloatArray): Int {
        var b = 0
        for (i in v.indices) if (v[i] > v[b]) b = i
        return b
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Double {
        var m = 0.0
        for (i in a.indices) m = max(m, abs((a[i] - b[i]).toDouble()))
        return m
    }

    private fun readSpec(file: File): List<Row> {
        val root = JSONObject(file.readText())
        val arr = root.getJSONArray("rows")
        val out = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            fun ints(name: String): IntArray {
                val a = o.getJSONArray(name)
                return IntArray(a.length()) { a.getInt(it) }
            }
            fun floats(name: String): FloatArray {
                val a = o.getJSONArray(name)
                return FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            out += Row(o.getString("id"), ints("ids"), ints("markers"), o.getInt("qtype"),
                floats("pBase"), floats("refLogits"))
        }
        return out
    }

    private fun elapsed(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private companion object {
        const val TAG = "LayaQuantDevice"
        const val SIGNATURE = "serving_default"
        const val WINDOW = 256
        const val WIDTH = 768
        val EMBEDS_Q = 0.002889476018026471 to -18
        val MASK_Q = 0.003921568859368563 to -128
        val QTYPE_Q = 0.003921568859368563 to -128
        val TOKEN_LOGITS_Q = 0.031179068610072136 to -40
    }
}
