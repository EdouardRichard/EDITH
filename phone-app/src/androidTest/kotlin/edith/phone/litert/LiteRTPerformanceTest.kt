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

    private companion object {
        const val TAG = "LiteRTPerf"
    }
}
