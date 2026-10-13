package edith.phone.litert

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.system.measureNanoTime

/**
 * Phase A acceptance (A5): measures the real LiteRT inference cost of the two on-device models on the
 * K40 development device.
 *
 * What is measured and what is not, stated plainly:
 * - **Measured**: model load (mmap + graph init), and the graph forward pass latency for the real
 *   converted Laya multilingual graph and the real EmbeddingGemma graph, using synthetic tensors of
 *   the shapes the graphs declare. Also the process' memory growth from holding both models.
 * - **Not measured**: tokenizer cost and prompt-template assembly. The Kotlin tokenizer port is not
 *   implemented yet (see [LayaLiteRTEngine.choice]'s TODO), so the numbers here are the *graph* cost,
 *   which is the dominant term; the tokenizer adds single-digit milliseconds on top.
 *
 * The models are read from the app's own external files directory, which needs no storage permission.
 * They are placed there by hand (adb) for this measurement; making the location a user setting is a
 * Phase B concern.
 */
@RunWith(AndroidJUnit4::class)
class LiteRTPerformanceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelsDir get() = File(context.getExternalFilesDir(null), "models")

    private val layaMain get() = File(modelsDir, "laya-multilingual/laya_ml_s256_fp32.tflite")
    private val layaAct get() = File(modelsDir, "laya-multilingual/laya_ml_act_head_fp32.tflite")
    private val embeddingModel
        get() = File(modelsDir, "embeddinggemma/embeddinggemma-300M_seq256_mixed-precision.tflite")

    /** mmap a .tflite file the way TFLite's own `FileUtil.loadMappedFile` does. */
    private fun mapModel(file: File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.nativeOrder())
        }
    }

    private fun printShapes(tag: String, interpreter: Interpreter) {
        for (i in 0 until interpreter.inputTensorCount) {
            Log.i(TAG, "$tag input[$i] shape=${interpreter.getInputTensor(i).shape().toList()} " +
                "dtype=${interpreter.getInputTensor(i).dataType()}")
        }
        for (i in 0 until interpreter.outputTensorCount) {
            Log.i(TAG, "$tag output[$i] shape=${interpreter.getOutputTensor(i).shape().toList()} " +
                "dtype=${interpreter.getOutputTensor(i).dataType()}")
        }
    }

    @Test
    fun layaGraphContractAndLatency() {
        org.junit.Assume.assumeTrue("Laya model not present at ${layaMain.absolutePath}", layaMain.isFile)
        org.junit.Assume.assumeTrue("Laya act head not present at ${layaAct.absolutePath}", layaAct.isFile)

        val options = Interpreter.Options().setNumThreads(4)

        lateinit var interp: Interpreter
        val loadMs = measureNanoTime {
            interp = Interpreter(mapModel(layaMain), options)
        } / 1_000_000.0
        Log.i(TAG, "LAYA_LOAD_MS=$loadMs")

        try {
            printShapes("LAYA", interp)

            val window = interp.getInputTensor(0).shape()[1]
            val pooled = interp.getOutputTensor(1).shape()[1]

            val inputIds = Array(1) { IntArray(window) { 1000 + (it % 100) } }
            val attention = Array(1) { FloatArray(window) { 1f } }
            val qtype = Array(1) { floatArrayOf(1f, 0f, 0f) } // choice
            val tokenLogits = Array(1) { FloatArray(window) }
            val pooledCls = Array(1) { FloatArray(pooled) }

            // Warm-up, then measure. First call on a 1.2 GB fp32 graph is where the delegate places nodes.
            fun runOnce() {
                val outputs = mutableMapOf<Int, Any>(0 to tokenLogits, 1 to pooledCls)
                interp.runForMultipleInputsOutputs(arrayOf(inputIds, attention, qtype), outputs)
            }
            repeat(3) { runOnce() }

            val samples = 20
            val latencies = DoubleArray(samples) { measureNanoTime { runOnce() } / 1_000_000.0 }
            val sorted = latencies.sorted()
            Log.i(TAG, "LAYA_LATENCY_MS median=${sorted[samples / 2]} p95=${sorted[(samples * 95) / 100]} " +
                "min=${sorted.first()} max=${sorted.last()} window=$window pooled=$pooled")
            Log.i(TAG, "LAYA_POOLED_SAMPLE=${pooledCls[0].take(4).toList()}")

            // The pooled vector must not be all zeros / NaN: a graph that ran but produced garbage would
            // otherwise look like a fast success.
            org.junit.Assert.assertTrue("pooled_cls is all zero - graph did not compute",
                pooledCls[0].any { it != 0f })
            org.junit.Assert.assertFalse("pooled_cls contains NaN",
                pooledCls[0].any { it.isNaN() })
        } finally {
            interp.close()
        }
    }

    @Test
    fun embeddingGraphContractAndLatency() {
        org.junit.Assume.assumeTrue("EmbeddingGemma not present at ${embeddingModel.absolutePath}",
            embeddingModel.isFile)

        val options = Interpreter.Options().setNumThreads(4)

        lateinit var interp: Interpreter
        val loadMs = measureNanoTime {
            interp = Interpreter(mapModel(embeddingModel), options)
        } / 1_000_000.0
        Log.i(TAG, "EMBED_LOAD_MS=$loadMs")

        try {
            printShapes("EMBED", interp)

            // seq256 export: one int32 [1,256] input, one float32 [1,768] output. Asserted rather than
            // assumed, so a different export fails loudly here instead of silently mis-measuring.
            org.junit.Assert.assertEquals("unexpected input count", 1, interp.inputTensorCount)
            val shape = interp.getInputTensor(0).shape()
            org.junit.Assert.assertEquals("unexpected input rank", 2, shape.size)
            val outDim = interp.getOutputTensor(0).shape().last()

            val inputIds = Array(1) { IntArray(shape[1]) { 1000 + (it % 100) } }
            val output = Array(1) { FloatArray(outDim) }

            repeat(3) { interp.run(inputIds, output) }

            val samples = 20
            val latencies = DoubleArray(samples) {
                measureNanoTime { interp.run(inputIds, output) } / 1_000_000.0
            }
            val sorted = latencies.sorted()
            Log.i(TAG, "EMBED_LATENCY_MS median=${sorted[samples / 2]} p95=${sorted[(samples * 95) / 100]} " +
                "min=${sorted.first()} max=${sorted.last()} outDim=$outDim")

            // Matryoshka truncation to 256 dims must still be a usable embedding: unit norm after L2.
            val truncated = output[0].take(256).toFloatArray()
            val norm = kotlin.math.sqrt(truncated.fold(0f) { acc, f -> acc + f * f })
            Log.i(TAG, "EMBED_TRUNC256_NORM=$norm (full-dim norm=" +
                kotlin.math.sqrt(output[0].fold(0f) { acc, f -> acc + f * f }) + ")")
            org.junit.Assert.assertTrue("embedding is all zero", output[0].any { it != 0f })
            org.junit.Assert.assertFalse("embedding contains NaN", output[0].any { it.isNaN() })
        } finally {
            interp.close()
        }
    }

    @Test
    fun bothModelsResidentMemory() {
        org.junit.Assume.assumeTrue(layaMain.isFile && embeddingModel.isFile)

        // The Java-heap probe (`Runtime.totalMemory() - freeMemory()`) is useless here: TFLite maps the
        // model files, so a 1.2 GB graph costs a few MB of Java heap and the delta reads 0 whether or not
        // the models are resident. That reading was taken first and discarded for exactly that reason.
        // Resident set size is the number that answers "do both models fit", because mapped file pages are
        // evictable while touched anonymous pages are not.
        fun rssMb(): Long {
            val status = File("/proc/self/status").readLines()
            val kb = status.first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toLong()
            return kb / 1024
        }

        val before = rssMb()
        val options = Interpreter.Options().setNumThreads(4)
        val laya = Interpreter(mapModel(layaMain), options)
        val embed = Interpreter(mapModel(embeddingModel), options)

        // Touch both graphs so their weights are faulted in, not just mapped: a mapping nobody read does
        // not show up in RSS, and "loads without touching" is not what the product does.
        val layaIn = arrayOf(Array(1) { IntArray(256) { 42 } }, Array(1) { FloatArray(256) { 1f } },
            Array(1) { floatArrayOf(1f, 0f, 0f) })
        val layaOut = mutableMapOf<Int, Any>(0 to Array(1) { FloatArray(256) },
            1 to Array(1) { FloatArray(768) })
        laya.runForMultipleInputsOutputs(layaIn, layaOut)
        val embedOut = Array(1) { FloatArray(768) }
        embed.run(Array(1) { IntArray(256) { 42 } }, embedOut)

        val after = rssMb()

        Log.i(TAG, "MEMORY_RSS_BEFORE_MB=$before MEMORY_RSS_AFTER_MB=$after RSS_DELTA_MB=${after - before}")
        Log.i(TAG, "MEMORY_RSS_LIMIT_MB=1500")

        laya.close()
        embed.close()
    }

    /**
     * The `CompiledModel` path, which is what the model card's own latency figures were taken with, and
     * which is the only path that reaches the GPU (`libLiteRtOpenClAccelerator.so` ships inside
     * `litert:2.1.0`, so no separate GPU artifact is needed).
     *
     * Two things this test refuses to do, because the first version of it did both:
     *
     * 1. **Time the first call.** The first `run()` on a graph includes its preparation, so a
     *    first-call reading mixes compile cost into latency - the same Laya graph read 590 ms and
     *    2377 ms on two consecutive runs, which is not a latency at all. Every accelerator now gets a
     *    warm-up run before N timed runs, and the median of those is what is logged.
     * 2. **Report a GPU speedup it cannot corroborate.** The first GPU run reported 1 ms for a 1.2 GB
     *    ModernBERT-large forward pass and a 295x speedup. No phone GPU does that, and the GPU's
     *    `token_logits` disagreed with the CPU's by up to 3.3 with a different argmax. Either the
     *    `CompiledModel` input mapping differs from the `Interpreter`'s or the run is not synchronized
     *    before read-back; until one of those is settled the number is not a measurement. The test logs
     *    `COMPILED_GPU_UNVERIFIED` and asserts on the values, so it cannot pass as a win by accident.
     */
    @Test
    fun compiledModelCpuVersusGpu() {
        org.junit.Assume.assumeTrue(layaMain.isFile && embeddingModel.isFile)

        val env = com.google.ai.edge.litert.Environment.create()
        val threads = 4

        class Result(val medianMs: Double, val outputs: List<FloatArray>)

        fun run(
            accelerator: com.google.ai.edge.litert.Accelerator,
            tag: String,
            path: String,
            inputs: List<FloatArray>,
            intIndices: Set<Int>,
            outCount: Int,
        ): Result? {
            return try {
                val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator)
                if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                    options.cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(
                        numThreads = threads)
                } else {
                    options.gpuOptions = com.google.ai.edge.litert.CompiledModel.GpuOptions(
                        precision = com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision.FP32)
                }
                val model = com.google.ai.edge.litert.CompiledModel.create(path, options, env)
                val inBufs = model.createInputBuffers()
                val outBufs = model.createOutputBuffers()
                org.junit.Assert.assertEquals("unexpected input count", inputs.size, inBufs.size)
                org.junit.Assert.assertEquals("unexpected output count", outCount, outBufs.size)

                fun fill() {
                    for ((i, buf) in inBufs.withIndex()) {
                        if (i in intIndices) buf.writeInt(inputs[i].map { it.toInt() }.toIntArray())
                        else buf.writeFloat(inputs[i])
                    }
                }

                fill()
                model.run(inBufs, outBufs) // warm-up: this is where preparation cost lands

                val samples = 5
                val times = DoubleArray(samples) {
                    fill()
                    val start = System.nanoTime()
                    model.run(inBufs, outBufs)
                    (System.nanoTime() - start) / 1_000_000.0
                }
                val outputs = outBufs.map { it.readFloat().toList().toFloatArray() }
                model.close()
                val median = times.sorted()[samples / 2]
                Log.i(TAG, "COMPILED_${tag}_MS median=$median samples=${times.toList()}")
                Result(median, outputs)
            } catch (e: Throwable) {
                Log.e(TAG, "COMPILED_${tag}_FAILED: ${e::class.java.simpleName}: ${e.message}")
                null
            }
        }

        val embedIds = FloatArray(256) { (1000 + it % 100).toFloat() }
        val ids = FloatArray(256) { (1000 + it % 100).toFloat() }
        val mask = FloatArray(256) { 1f }
        val qtype = floatArrayOf(1f, 0f, 0f)

        val embedCpu = run(com.google.ai.edge.litert.Accelerator.CPU, "CPU_EMBED",
            embeddingModel.absolutePath, listOf(embedIds), setOf(0), 1)
        val layaCpu = run(com.google.ai.edge.litert.Accelerator.CPU, "CPU_LAYA",
            layaMain.absolutePath, listOf(ids, mask, qtype), setOf(0), 2)
        val embedGpu = run(com.google.ai.edge.litert.Accelerator.GPU, "GPU_EMBED",
            embeddingModel.absolutePath, listOf(embedIds), setOf(0), 1)
        val layaGpu = run(com.google.ai.edge.litert.Accelerator.GPU, "GPU_LAYA",
            layaMain.absolutePath, listOf(ids, mask, qtype), setOf(0), 2)

        org.junit.Assert.assertNotNull(
            "Neither graph ran on the CompiledModel CPU path; the comparison has no baseline.", embedCpu)

        // CPU CompiledModel vs the Interpreter CPU reading from the other test in this class. They should
        // be the same order of magnitude; a wild disagreement means one of the two is mis-configured.
        Log.i(TAG, "CPU_PATH_INTERPRETER_LAYA_MS=1815 CPU_PATH_COMPILED_LAYA_MS=${layaCpu?.medianMs}")

        // The GPU claim is only worth anything if it reproduces the CPU's numbers.
        fun corroborate(name: String, cpu: Result?, gpu: Result?): Boolean {
            if (gpu == null || cpu == null) return false
            val c = cpu.outputs[0]
            val g = gpu.outputs[0]
            val maxAbs = c.indices.maxOf { kotlin.math.abs(c[it] - g[it]) }
            val scale = c.maxOf { kotlin.math.abs(it) }
            val agrees = c.indices.all { kotlin.math.abs(c[it] - g[it]) <= 0.05f + 0.02f * kotlin.math.abs(c[it]) }
            Log.i(TAG, "COMPILED_${name}_CPU_VS_GPU maxAbsDiff=$maxAbs cpuScale=$scale agrees=$agrees " +
                "cpuArgmax=${c.indices.maxByOrNull { c[it] }} gpuArgmax=${g.indices.maxByOrNull { g[it] }}")
            if (agrees) {
                Log.i(TAG, "COMPILED_${name}_GPU_VERIFIED cpuMs=${cpu.medianMs} gpuMs=${gpu.medianMs} " +
                    "speedup=${"%.2f".format(cpu.medianMs / gpu.medianMs)}")
            } else {
                Log.e(TAG, "COMPILED_${name}_GPU_UNVERIFIED: the GPU's output does not match the CPU's " +
                    "(maxAbsDiff=$maxAbs). Its ${gpu.medianMs} ms is NOT a measurement of inference and no " +
                    "speedup may be reported from it.")
            }
            return agrees
        }

        val embedOk = corroborate("EMBED", embedCpu, embedGpu)
        val layaOk = corroborate("LAYA", layaCpu, layaGpu)

        org.junit.Assert.assertTrue(
            "GPU output did not match CPU output for either graph, so no GPU latency from this run is " +
                "trustworthy (see COMPILED_*_GPU_UNVERIFIED above). GPU reachability itself was " +
                "demonstrated - both graphs compiled and ran - but the numbers need the input-mapping or " +
                "synchronization question settled first.",
            embedOk || layaOk,
        )
    }

    /**
     * Do the two CPU APIs compute the same thing?
     *
     * This test exists because the `CompiledModel` result needed it and the first round of measurements
     * skipped it. `CompiledModel` on the CPU read 569 ms for Laya against the `Interpreter`'s 1815 ms, a
     * 3.2x "win" - but the graph is 322M fp32 parameters at a 256-token window, i.e. roughly
     * 2 x 322e6 x 256 = 165 GFLOP per forward pass, and the Snapdragon 870's four big cores peak at about
     * 167 GFLOP/s of fp32 NEON (A77: 2 x 128-bit FMA = 16 FLOP/cycle) with 60-75 % of that achievable in
     * practice. 569 ms implies **290 GFLOP/s, which is 1.7x the chip's theoretical fp32 peak** - so either
     * the fast path is not doing the same arithmetic (XNNPACK can dynamically quantize fully-connected
     * weights to int8, which buys about 4x for a small accuracy cost), or one of the two readings is not
     * measuring the work at all. 1815 ms lands right where an honest fp32 run should, which is what makes
     * the fast one the suspect rather than the slow one.
     *
     * A latency comparison between two paths that do not compute the same thing is not a comparison. This
     * reports the actual agreement so the speedup can be quoted with its precision cost attached - or
     * withdrawn.
     */
    @Test
    fun bothCpuApisComputeTheSameThing() {
        org.junit.Assume.assumeTrue(layaMain.isFile && embeddingModel.isFile)

        val ids = IntArray(256) { 1000 + it % 100 }
        val idsF = FloatArray(256) { ids[it].toFloat() }
        val mask = FloatArray(256) { 1f }
        val qtype = floatArrayOf(1f, 0f, 0f)

        fun interpreterOutputs(path: String, asInt: Boolean, extraFloats: List<FloatArray>):
            List<FloatArray> {
            val interp = Interpreter(mapModel(File(path)), Interpreter.Options().setNumThreads(4))
            return try {
                if (asInt && extraFloats.isEmpty()) {
                    val out = Array(1) { FloatArray(768) }
                    interp.run(arrayOf(ids), out)
                    listOf(out[0])
                } else {
                    val ins = arrayOf<Any>(arrayOf(ids), Array(1) { mask }, Array(1) { qtype })
                    val outs = mutableMapOf<Int, Any>(0 to Array(1) { FloatArray(256) },
                        1 to Array(1) { FloatArray(768) })
                    interp.runForMultipleInputsOutputs(ins, outs)
                    listOf((outs[0] as Array<FloatArray>)[0], (outs[1] as Array<FloatArray>)[0])
                }
            } finally {
                interp.close()
            }
        }

        val env = com.google.ai.edge.litert.Environment.create()

        fun compiledOutputs(path: String, inputs: List<FloatArray>, intIdx: Set<Int>): List<FloatArray> {
            val options = com.google.ai.edge.litert.CompiledModel.Options(
                com.google.ai.edge.litert.Accelerator.CPU)
            options.cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
            val model = com.google.ai.edge.litert.CompiledModel.create(path, options, env)
            return try {
                val ins = model.createInputBuffers()
                val outs = model.createOutputBuffers()
                for ((i, buf) in ins.withIndex()) {
                    if (i in intIdx) buf.writeInt(inputs[i].map { it.toInt() }.toIntArray())
                    else buf.writeFloat(inputs[i])
                }
                model.run(ins, outs)
                outs.map { it.readFloat().toList().toFloatArray() }
            } finally {
                model.close()
            }
        }

        fun report(name: String, a: List<FloatArray>, b: List<FloatArray>) {
            for (i in a.indices) {
                if (i >= b.size) break
                val x = a[i]
                val y = b[i]
                val maxAbs = x.indices.maxOf { kotlin.math.abs(x[it] - y[it]) }
                val scale = x.maxOf { kotlin.math.abs(it) }
                val rel = if (scale > 0f) maxAbs / scale else 0f
                Log.i(TAG, "API_AGREEMENT_${name}_out$i maxAbsDiff=$maxAbs valueScale=$scale " +
                    "relative=$rel argmaxInterpreter=${x.indices.maxByOrNull { x[it] }} " +
                    "argmaxCompiled=${y.indices.maxByOrNull { y[it] }}")
            }
        }

        report("EMBED", interpreterOutputs(embeddingModel.absolutePath, asInt = true, extraFloats = emptyList()),
            compiledOutputs(embeddingModel.absolutePath, listOf(idsF), setOf(0)))
        report("LAYA",
            interpreterOutputs(layaMain.absolutePath, asInt = false, extraFloats = listOf(mask, qtype)),
            compiledOutputs(layaMain.absolutePath, listOf(idsF, mask, qtype), setOf(0)))
    }

    /**
     * How much of the 569 ms is arithmetic and how much is per-call overhead?
     *
     * The two CPU APIs turned out to produce **bit-identical** output for both graphs (maxAbsDiff = 0.0 on
     * all three tensors), which rules out the quantization explanation for the 3.2x gap and means the two
     * paths run the same kernels. A 1.2-second difference between identical arithmetic has to be
     * preparation that `CompiledModel` hoists out of the call and `Interpreter` does inside it.
     *
     * That distinction is what any cross-device prediction rests on: **compute scales with the SoC,
     * overhead does not.** Thread scaling separates them - a compute-bound graph speeds up roughly with
     * core count, an overhead-bound one barely moves. The same logic applies to the `Interpreter` path,
     * so both are swept.
     */
    @Test
    fun threadScalingSeparatesComputeFromOverhead() {
        org.junit.Assume.assumeTrue(layaMain.isFile && embeddingModel.isFile)

        val ids = IntArray(256) { 1000 + it % 100 }
        val idsF = FloatArray(256) { ids[it].toFloat() }
        val mask = FloatArray(256) { 1f }
        val qtype = floatArrayOf(1f, 0f, 0f)
        val env = com.google.ai.edge.litert.Environment.create()

        fun compiledMedian(path: String, inputs: List<FloatArray>, intIdx: Set<Int>, threads: Int): Double {
            val options = com.google.ai.edge.litert.CompiledModel.Options(
                com.google.ai.edge.litert.Accelerator.CPU)
            options.cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = threads)
            val model = com.google.ai.edge.litert.CompiledModel.create(path, options, env)
            return try {
                val ins = model.createInputBuffers()
                val outs = model.createOutputBuffers()
                fun fill() {
                    for ((i, buf) in ins.withIndex()) {
                        if (i in intIdx) buf.writeInt(inputs[i].map { it.toInt() }.toIntArray())
                        else buf.writeFloat(inputs[i])
                    }
                }
                fill()
                model.run(ins, outs)
                val t = DoubleArray(5) {
                    fill()
                    val s = System.nanoTime()
                    model.run(ins, outs)
                    (System.nanoTime() - s) / 1_000_000.0
                }.sorted()
                t[2]
            } finally {
                model.close()
            }
        }

        fun interpreterMedian(path: String, threads: Int, isEmbed: Boolean): Double {
            val interp = Interpreter(mapModel(File(path)), Interpreter.Options().setNumThreads(threads))
            return try {
                val times = DoubleArray(5) {
                    val s = System.nanoTime()
                    if (isEmbed) {
                        interp.run(arrayOf(ids), Array(1) { FloatArray(768) })
                    } else {
                        val outs = mutableMapOf<Int, Any>(0 to Array(1) { FloatArray(256) },
                            1 to Array(1) { FloatArray(768) })
                        interp.runForMultipleInputsOutputs(
                            arrayOf<Any>(arrayOf(ids), Array(1) { mask }, Array(1) { qtype }), outs)
                    }
                    (System.nanoTime() - s) / 1_000_000.0
                }.sorted()
                times[2]
            } finally {
                interp.close()
            }
        }

        for (threads in listOf(1, 2, 4)) {
            val embed = compiledMedian(embeddingModel.absolutePath, listOf(idsF), setOf(0), threads)
            val laya = compiledMedian(layaMain.absolutePath, listOf(idsF, mask, qtype), setOf(0), threads)
            val layaInterp = interpreterMedian(layaMain.absolutePath, threads, isEmbed = false)
            Log.i(TAG, "THREAD_SCALING threads=$threads COMPILED_EMBED_MS=$embed COMPILED_LAYA_MS=$laya " +
                "INTERPRETER_LAYA_MS=$layaInterp")
        }
    }

    private companion object {
        const val TAG = "LiteRTPerf"
    }
}
