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
                fun readAll(): List<FloatArray> = outBufs.map { it.readFloat().toList().toFloatArray() }
                // Inputs are written on every run, which is both what the production path does and what
                // keeps this honest: an earlier revision of this block hoisted the write out of the settle
                // loop and ended up measuring the graphs on freshly-allocated zero buffers, then reporting
                // those reads as the outputs.
                fun runOnce() {
                    fill()
                    model.run(inBufs, outBufs)
                }

                // Quiesce before trusting anything: see runUntilSettled's doc comment.
                val settled = runUntilSettled(::runOnce, ::readAll)
                if (settled == null) {
                    Log.e(TAG, "COMPILED_${tag}_NEVER_SETTLED within 16 rounds; refusing to report a " +
                        "latency or an output for it")
                    model.close()
                    return null
                }
                val (rounds, outputs) = settled
                Log.i(TAG, "COMPILED_${tag}_SETTLED_AFTER_ROUNDS=$rounds")

                // Time only post-quiescence runs.
                repeat(rounds) { runOnce() }
                val samples = 5
                val times = DoubleArray(samples) {
                    val start = System.nanoTime()
                    runOnce()
                    (System.nanoTime() - start) / 1_000_000.0
                }
                model.close()
                val median = times.sorted()[samples / 2]
                Log.i(TAG, "COMPILED_${tag}_MS median=$median samples=${times.toList()}")
                return Result(median, outputs)
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

    /**
     * Why does the GPU's Laya output not match the CPU's? The K40 compiles and runs both graphs on the
     * Adreno 650, so "unmeasurable" is not an acceptable resting place - the question is answerable here.
     *
     * Two candidate causes, separated decisively by one experiment:
     *
     * 1. **`Precision.FP32` is ignored and the delegate runs fp16 anyway.** The model card documents this
     *    graph's fp16 hazards in detail (attention masks at -1e4, LayerNorm inputs whose squares reach
     *    1e8), and its 2026-09-29 revision exists specifically to stop those overflowing fp16. Avoiding
     *    *overflow* is not the same as fp16 *precision*: with activations in the 1e3 range and a 2^-11
     *    mantissa, absolute error in the units digit accumulates across 24 layers, which is the size of
     *    the mismatch actually seen (3.31).
     * 2. **The input buffers are in a different order than `Interpreter` reports.** `createInputBuffers()`
     *    follows the signature's order, which nothing documents for this graph.
     *
     * The discriminator: run the GPU twice, once declaring FP32 and once FP16, and compare both against
     * the CPU. Identical FP32 and FP16 results mean the setting is being ignored. Different results with
     * FP32 tracking the CPU mean the setting works and something else was wrong (the earlier GPU reading
     * was taken while submissions were still asynchronous, which the sample lists show).
     */
    @Test
    fun whyDoesTheGpuDisagree() {
        org.junit.Assume.assumeTrue(layaMain.isFile)

        val ids = FloatArray(256) { (1000 + it % 100).toFloat() }
        val mask = FloatArray(256) { 1f }
        val qtype = floatArrayOf(1f, 0f, 0f)
        val inputs = listOf(ids, mask, qtype)
        val env = com.google.ai.edge.litert.Environment.create()

        fun runGpu(precision: com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision): List<FloatArray> {
            val options = com.google.ai.edge.litert.CompiledModel.Options(
                com.google.ai.edge.litert.Accelerator.GPU)
            options.gpuOptions = com.google.ai.edge.litert.CompiledModel.GpuOptions(precision = precision)
            val model = com.google.ai.edge.litert.CompiledModel.create(layaMain.absolutePath, options, env)
            return try {
                val ins = model.createInputBuffers()
                val outs = model.createOutputBuffers()
                Log.i(TAG, "GPU_INPUT_COUNT=${ins.size} OUTPUT_COUNT=${outs.size}")
                // Buffer footprint discriminates the slots: [1,256] int32 and [1,256] float32 are both
                // 1024 B, while qtype_onehot [1,3] float32 is 12 B. Writing a wrong-width array throws, so
                // this probe both locates qtype and proves which slots accept which width.
                for ((i, buf) in ins.withIndex()) {
                    val intOk = try { buf.writeInt(IntArray(256)); true } catch (e: Throwable) { false }
                    val smallOk = try { buf.writeInt(IntArray(3)); true } catch (e: Throwable) { false }
                    val float256Ok = try { buf.writeFloat(FloatArray(256)); true } catch (e: Throwable) { false }
                    val float3Ok = try { buf.writeFloat(FloatArray(3)); true } catch (e: Throwable) { false }
                    Log.i(TAG, "GPU_INPUT[$i] accepts int256=$intOk int3=$smallOk float256=$float256Ok float3=$float3Ok")
                }
                for ((i, buf) in ins.withIndex()) {
                    if (i == 0) buf.writeInt(inputs[0].map { it.toInt() }.toIntArray()) else buf.writeFloat(inputs[i])
                }
                // Three runs: the first submissions return before the GPU has finished (see the GPU sample
                // lists elsewhere in this class), so the last one is the one whose buffers are complete.
                repeat(3) { model.run(ins, outs) }
                outs.map { it.readFloat().toList().toFloatArray() }
            } finally {
                model.close()
            }
        }

        fun cpuReference(): List<FloatArray> {
            val options = com.google.ai.edge.litert.CompiledModel.Options(
                com.google.ai.edge.litert.Accelerator.CPU)
            options.cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
            val model = com.google.ai.edge.litert.CompiledModel.create(layaMain.absolutePath, options, env)
            return try {
                val ins = model.createInputBuffers()
                val outs = model.createOutputBuffers()
                for ((i, buf) in ins.withIndex()) {
                    if (i == 0) buf.writeInt(inputs[0].map { it.toInt() }.toIntArray()) else buf.writeFloat(inputs[i])
                }
                model.run(ins, outs)
                outs.map { it.readFloat().toList().toFloatArray() }
            } finally {
                model.close()
            }
        }

        val cpu = cpuReference()
        val gpuFp32 = try { runGpu(com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision.FP32) }
            catch (e: Throwable) { Log.e(TAG, "GPU_FP32_FAILED: ${e.message}"); null }
        val gpuFp16 = try { runGpu(com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision.FP16) }
            catch (e: Throwable) { Log.e(TAG, "GPU_FP16_FAILED: ${e.message}"); null }

        fun diff(a: List<FloatArray>, b: List<FloatArray>, name: String) {
            for (i in a.indices) {
                if (i >= b.size) continue
                val maxAbs = a[i].indices.maxOf { kotlin.math.abs(a[i][it] - b[i][it]) }
                Log.i(TAG, "GPU_DIAG_$name out$i maxAbsDiff=$maxAbs " +
                    "scale=${a[i].maxOf { kotlin.math.abs(it) }} " +
                    "argmaxA=${a[i].indices.maxByOrNull { a[i][it] }} argmaxB=${b[i].indices.maxByOrNull { b[i][it] }}")
            }
        }

        if (gpuFp32 != null) diff(cpu, gpuFp32, "CPU_vs_GPU_FP32")
        if (gpuFp16 != null) diff(cpu, gpuFp16, "CPU_vs_GPU_FP16")
        if (gpuFp32 != null && gpuFp16 != null) {
            diff(gpuFp32, gpuFp16, "GPU_FP32_vs_GPU_FP16")
            val same = gpuFp32.indices.all { i ->
                gpuFp32[i].indices.all { gpuFp32[i][it] == gpuFp16[i][it] }
            }
            Log.i(TAG, "GPU_PRECISION_SETTING_HONOURED=${!same} " +
                "(FP32 and FP16 outputs bit-identical means the requested precision is ignored)")
        }
    }

    /**
     * Resolves the GPU mismatch by removing the guesswork instead of testing permutations.
     *
     * The previous experiment settled two things and left one open:
     * - **The precision setting is honoured.** Forcing `Precision.FP16` on the fp32-weight graph produces
     *   **NaN** - exactly the overflow the model card documents for this graph - and differs from the FP32
     *   output. So FP32 is not being silently downgraded, and precision is not the cause.
     * - **`qtype_onehot` is buffer [2]**, confirmed by its 12-byte footprint rejecting 256-element writes.
     * - **Buffers [0] and [1] accept any width, so their order is genuinely ambiguous.**
     *   `createInputBuffers()` follows the signature's order, which nothing documents. If `input_ids` and
     *   `attention_mask` are swapped relative to the `Interpreter`'s order, an int32 array written into a
     *   float32 buffer becomes denormal garbage and a float mask written into an int32 buffer becomes
     *   token ids around 1.06e9 - either of which would produce a mismatch of exactly the observed size.
     *
     * Rather than permute, this uses the name-keyed API: `createInputBuffer(signature, tensorName)` and the
     * `Map<String, TensorBuffer>` overload of `run`. Names come from the model card's graph contract, and
     * the test asserts the buffers were created rather than silently swallowing a wrong name.
     */
    @Test
    fun gpuByTensorName() {
        org.junit.Assume.assumeTrue(layaMain.isFile)

        val sig = "serving_default"
        val ids = IntArray(256) { 1000 + it % 100 }
        val mask = FloatArray(256) { 1f }
        val qtype = floatArrayOf(1f, 0f, 0f)
        val env = com.google.ai.edge.litert.Environment.create()

        // Report the names and element types the model actually declares, so a wrong guess fails loudly.
        runCatching {
            val interp = Interpreter(mapModel(layaMain), Interpreter.Options().setNumThreads(4))
            try {
                for (i in 0 until interp.inputTensorCount) {
                    Log.i(TAG, "NAMED_INPUT[$i] name='${interp.getInputTensor(i).name()}' " +
                        "dtype=${interp.getInputTensor(i).dataType()}")
                }
                for (i in 0 until interp.outputTensorCount) {
                    Log.i(TAG, "NAMED_OUTPUT[$i] name='${interp.getOutputTensor(i).name()}'")
                }
            } finally { interp.close() }
        }

        fun run(named: Boolean, accelerator: com.google.ai.edge.litert.Accelerator,
            precision: com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision?):
            List<FloatArray>? {
            return try {
                val options = com.google.ai.edge.litert.CompiledModel.Options(accelerator)
                if (accelerator == com.google.ai.edge.litert.Accelerator.CPU) {
                    options.cpuOptions = com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                } else if (precision != null) {
                    options.gpuOptions = com.google.ai.edge.litert.CompiledModel.GpuOptions(precision = precision)
                }
                val model = com.google.ai.edge.litert.CompiledModel.create(layaMain.absolutePath, options, env)
                try {
                    if (named) {
                        val ins = linkedMapOf(
                            "input_ids" to model.createInputBuffer(sig, "input_ids"),
                            "attention_mask" to model.createInputBuffer(sig, "attention_mask"),
                            "qtype_onehot" to model.createInputBuffer(sig, "qtype_onehot"),
                        )
                        val outs = linkedMapOf(
                            "token_logits" to model.createOutputBuffer(sig, "token_logits"),
                            "pooled_cls" to model.createOutputBuffer(sig, "pooled_cls"),
                        )
                        ins.getValue("input_ids").writeInt(ids)
                        ins.getValue("attention_mask").writeFloat(mask)
                        ins.getValue("qtype_onehot").writeFloat(qtype)
                        // Repeat: the first GPU submissions return before the work is done.
                        repeat(3) { model.run(ins, outs, sig) }
                        listOf(outs.getValue("token_logits").readFloat(),
                            outs.getValue("pooled_cls").readFloat())
                    } else {
                        val ins = model.createInputBuffers()
                        val outs = model.createOutputBuffers()
                        ins[0].writeInt(ids)
                        ins[1].writeFloat(mask)
                        ins[2].writeFloat(qtype)
                        repeat(3) { model.run(ins, outs) }
                        listOf(outs[0].readFloat(), outs[1].readFloat())
                    }
                } finally { model.close() }
            } catch (e: Throwable) {
                Log.e(TAG, "NAMED_RUN_FAILED named=$named accel=$accelerator: " +
                    "${e::class.java.simpleName}: ${e.message}")
                null
            }
        }

        fun compare(tag: String, a: List<FloatArray>?, b: List<FloatArray>?) {
            if (a == null || b == null) { Log.e(TAG, "NAMED_$tag unavailable"); return }
            for (i in a.indices) {
                val maxAbs = a[i].indices.maxOf { kotlin.math.abs(a[i][it] - b[i][it]) }
                Log.i(TAG, "NAMED_${tag} out$i maxAbsDiff=$maxAbs " +
                    "scale=${a[i].maxOf { kotlin.math.abs(it) }} " +
                    "argmaxA=${a[i].indices.maxByOrNull { a[i][it] }} " +
                    "argmaxB=${b[i].indices.maxByOrNull { b[i][it] }}")
            }
        }

        val cpuNamed = run(named = true, com.google.ai.edge.litert.Accelerator.CPU, null)
        val cpuPositional = run(named = false, com.google.ai.edge.litert.Accelerator.CPU, null)
        compare("CPU_NAMED_vs_CPU_POSITIONAL", cpuNamed, cpuPositional)

        val gpuNamed = run(named = true, com.google.ai.edge.litert.Accelerator.GPU,
            com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision.FP32)
        compare("CPU_NAMED_vs_GPU_NAMED", cpuNamed, gpuNamed)
    }

    /**
     * Runs [runOnce] until two consecutive reads of the outputs agree to within [tolerance], and reports
     * how many rounds that took.
     *
     * This exists because the GPU delegate's `run()` returning does **not** mean the work is done. The
     * per-sample lists elsewhere in this class show it plainly: the first submissions of every GPU graph
     * come back in about a millisecond and later ones take ~226 ms, i.e. the call blocks on *submission*
     * back-pressure, not on completion. Reading outputs right after `run()` therefore returns whatever is
     * in the buffer — which is why one run of this test found the embedding graph's GPU output matching
     * the CPU's and a later run of the same code did not.
     *
     * **The first version of this check demanded bit-identical reads and never settled — including on the
     * CPU**, which is the wrong criterion rather than a finding: XNNPACK's multi-threaded reductions
     * combine partial sums in a work-stealing order that varies between runs, so an fp32 result is
     * reproducible only to a tolerance. Agreement within [tolerance] is the property that can actually
     * hold, and a genuinely unwritten buffer (all zeros) still fails it.
     */
    private fun runUntilSettled(
        runOnce: () -> Unit,
        readAll: () -> List<FloatArray>,
        tolerance: Float = 1e-4f,
        maxRounds: Int = 16,
    ): Pair<Int, List<FloatArray>>? {
        var previous: List<FloatArray>? = null
        for (round in 1..maxRounds) {
            runOnce()
            val now = readAll()
            if (previous != null && agreesWithin(previous, now, tolerance)) return round to now
            previous = now
        }
        return null
    }

    /** True when every element of [b] is within [tolerance] of the corresponding element of [a]. */
    private fun agreesWithin(a: List<FloatArray>, b: List<FloatArray>, tolerance: Float): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].size != b[i].size) return false
            for (j in a[i].indices) {
                val x = a[i][j]
                val y = b[i][j]
                if (x.isNaN() || y.isNaN()) return false
                if (kotlin.math.abs(x - y) > tolerance) return false
            }
        }
        return true
    }

    private companion object {
        const val TAG = "LiteRTPerf"
    }
}
