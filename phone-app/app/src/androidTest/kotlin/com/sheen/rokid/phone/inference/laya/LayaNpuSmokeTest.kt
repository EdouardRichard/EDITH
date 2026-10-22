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
 * The NPU measurement on whichever device is attached (2026-10-14, K70 Ultra / MT6989).
 *
 * **Why this exists.** CPU and GPU were measured on the delivery target on 2026-10-13 (742 ms / 231 ms of
 * graph per question). The SoC also has an NPU, the publisher's card reports 36.5 ms for this exact file on
 * *its* NPU (Qualcomm Hexagon v81), and that number has never been reproduced on a MediaTek part. Publisher
 * validation explicitly excludes other NPUs ("Other Android GPUs and NPUs have not been validated here"), so
 * this is a first measurement, not a re-check.
 *
 * **What is measured, and what is not.** Graph time per question on the NPU against the CPU on the same state
 * and the same process, plus the answer agreement between them, because a latency figure without an output
 * comparison is not a measurement of anything (that is how LiteRT 2.1.0's GPU miscompute was caught). The
 * compile is *not* warmed away: a JIT NPU compile is a one-time cost that the plan budgets for the cold start,
 * so it is reported separately from the per-question figure.
 *
 * **A failed NPU compile is a result, not a broken test.** It is reported as a failure carrying the SDK's own
 * message, because "the vendor plugin refused this graph" is exactly the decision this run has to support.
 * The CPU/GPU paths are untouched by it.
 */
@RunWith(AndroidJUnit4::class)
class LayaNpuSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir get() = context.filesDir

    /** The same D60-shaped, time-free Chinese state the CPU/GPU test uses. */
    private val realisticState =
        """{"foreground":"com.tencent.mm","notification_category":"msg",""" +
            """"recent_transcript":"这个优惠券怎么用啊"}"""

    private val statement = "用户此刻需要帮助"

    @Test
    fun npuCompilesAndAgreesWithCpu() {
        val missing = LayaOfficialHost.REQUIRED_FILES.filterNot { File(filesDir, it).isFile }
        Assume.assumeTrue(
            "publisher files not provisioned in filesDir; missing ${missing.joinToString()}",
            missing.isEmpty(),
        )

        val vendor = LayaEngine.npuVendor(context)
        Assume.assumeTrue(
            "this APK carries no NPU runtime (Qualcomm HTP or MediaTek NeuroPilot); see the NPU section of the README",
            vendor != null,
        )

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        Log.i(TAG, "NPU_VENDOR=${vendor!!.name} LIB_DIR=$libDir")
        LayaEngine.NpuVendor.MEDIATEK.libraries.forEach {
            Log.i(TAG, "MEDIATEK_LIB_${it}=${File(libDir, it).isFile}")
        }
        Log.i(TAG, "ADAPTER_IN_APP_DIR=${File(libDir, "libneuronusdk_adapter.mtk.so").isFile}")

        val question =
            linkedMapOf<String, Any?>("type" to "noul", "instructions" to statement, "criteria" to null)

        LayaOfficialHost(context, LayaEngine.Storage.WFP16).use { host ->
            Log.i(TAG, "LOAD tokenizerMs=${host.tokenizerLoadMs} embeddingsMs=${host.embeddingLoadMs}")

            // ---- CPU: the reference answer and the same-device baseline.
            val cpuCompileStart = System.nanoTime()
            host.initialize(LayaEngine.Backend.CPU)
            val cpuCompileMs = elapsed(cpuCompileStart)
            val cpuProbability = host.noul(realisticState, statement, LayaEngine.Backend.CPU)
            val cpuRows = (1..5).map {
                val started = System.nanoTime()
                host.engine.answer(realisticState, question, LayaEngine.Backend.CPU, "cpu$it", LayaOfficialHost.WINDOW)
                    .also { Log.i(TAG, "CPU_RUN_MS=${elapsed(started)}") }
            }
            report("CPU", cpuCompileMs, cpuProbability, cpuRows.sortedBy { it.totalMs }[2])

            // ---- NPU: compiled, then measured. A refusal here is the measurement.
            val npuCompileStart = System.nanoTime()
            val compileFailure =
                try {
                    host.initialize(LayaEngine.Backend.NPU)
                    null
                } catch (failure: Throwable) {
                    failure
                }
            val npuCompileMs = elapsed(npuCompileStart)
            if (compileFailure != null) {
                Log.e(TAG, "NPU_COMPILE_FAILED ms=$npuCompileMs type=${compileFailure.javaClass.name}")
                Log.e(TAG, "NPU_COMPILE_MESSAGE=${compileFailure.message}")
                throw AssertionError(
                    "NPU compile failed after ${npuCompileMs}ms on ${vendor.name}: ${compileFailure.message}",
                    compileFailure,
                )
            }
            Log.i(TAG, "NPU_COMPILE_MS=$npuCompileMs")

            // ---- Before timing anything: what does the NPU actually return?
            //
            // The first device run (2026-10-14 21:16) compiled all 1809 ops onto the APU in one partition and
            // then returned non-finite output, so `answer()` refused it. `runRaw` is used here on purpose: it
            // hands back the tensors **including** non-finite values, so the failure has a shape (which tensor,
            // NaN or Inf, how much of it, and how large the finite values are) instead of just "non-finite".
            // That distinction is what a next step can act on: Inf means the fp16 arithmetic overflowed, NaN
            // without Inf means it came from the data plumbing (padding/stride/dtype) or from a 0/0.
            val sequence = host.engine.prepare(realisticState, question, LayaOfficialHost.WINDOW, "npu-diag")
            val raw = host.engine.runRaw(sequence, LayaEngine.Backend.NPU, LayaOfficialHost.WINDOW)
            Log.i(
                TAG,
                "NPU_RAW tokenLogits[${describe(raw.tokenLogits)}] pooledCls[${describe(raw.pooledCls)}] " +
                    "markers[${describe(raw.markerLogits)}] actFeatures[${describe(raw.actFeatures)}] " +
                    "actLogits[${describe(raw.actLogits)}] mainMs=${raw.mainTiming.totalMs} " +
                    "actMs=${raw.actTiming?.totalMs} finite=${raw.finite}",
            )
            Log.i(TAG, "NPU_RAW_TOKEN_HEAD=${raw.tokenLogits.take(8).toList()}")
            Log.i(TAG, "NPU_RAW_POOLED_HEAD=${raw.pooledCls.take(8).toList()}")
            if (!raw.finite) {
                throw AssertionError(
                    "NPU returned non-finite output on ${vendor.name}: tokenLogits[${describe(raw.tokenLogits)}] " +
                        "pooledCls[${describe(raw.pooledCls)}]",
                )
            }

            val npuProbability = host.noul(realisticState, statement, LayaEngine.Backend.NPU)
            val npuRows = (1..5).map {
                val started = System.nanoTime()
                host.engine.answer(realisticState, question, LayaEngine.Backend.NPU, "npu$it", LayaOfficialHost.WINDOW)
                    .also { Log.i(TAG, "NPU_RUN_MS=${elapsed(started)}") }
            }
            report("NPU", npuCompileMs, npuProbability, npuRows.sortedBy { it.totalMs }[2])

            Log.i(
                TAG,
                "AGREEMENT cpu=$cpuProbability npu=$npuProbability " +
                    "absDiff=${kotlin.math.abs(cpuProbability - npuProbability)}",
            )
            org.junit.Assert.assertTrue("NPU probability not finite: $npuProbability", npuProbability.isFinite())
            org.junit.Assert.assertTrue(
                "NPU and CPU disagree on the noul probability; the NPU number is not usable until they " +
                    "agree (this is how the LiteRT 2.1.0 GPU miscompute was caught)",
                kotlin.math.abs(cpuProbability - npuProbability) <= 0.01,
            )
        }
    }

    /**
     * The fp32-weight form of the same checkpoint, on the same NPU: the **attribution** half.
     *
     * The wfp16 file stores its 99 FULLY_CONNECTED weights as float16 and DEQUANTIZEs them; this one keeps them
     * float32. If only the wfp16 form failed, the failing ingredient would be the weight storage, and the fix
     * would be a re-export rather than a different accelerator. Both forms otherwise compute in float32, and the
     * NPU relaxes float32 computation to float16 on its own (`NeuronModel_relaxComputationFloat32toFloat16`), so
     * "fp32 weights" is a storage change, **not** an fp32 arithmetic path.
     *
     * This probe **logs rather than asserts**: the decisive assertion lives in the wfp16 test above, and this one
     * exists to say whether the failure is specific to the fp16 weight storage. It skips unless the fp32 graph was
     * provisioned beside the wfp16 set.
     */
    @Test
    fun fp32StorageOnNpuIsLoggedForAttribution() {
        Assume.assumeTrue(
            "fp32 graph not provisioned in filesDir",
            File(filesDir, "laya_ml_s256_embeds_fp32.tflite").isFile,
        )
        val vendor = LayaEngine.npuVendor(context)
        Assume.assumeTrue("this APK carries no NPU runtime", vendor != null)

        val question =
            linkedMapOf<String, Any?>("type" to "noul", "instructions" to statement, "criteria" to null)

        LayaOfficialHost(context, LayaEngine.Storage.FP32).use { host ->
            val compileStart = System.nanoTime()
            host.initialize(LayaEngine.Backend.NPU)
            val compileMs = elapsed(compileStart)
            val sequence = host.engine.prepare(realisticState, question, LayaOfficialHost.WINDOW, "fp32-npu")
            val raw = host.engine.runRaw(sequence, LayaEngine.Backend.NPU, LayaOfficialHost.WINDOW)
            Log.i(
                TAG,
                "FP32_NPU compileMs=$compileMs tokenLogits[${describe(raw.tokenLogits)}] " +
                    "pooledCls[${describe(raw.pooledCls)}] mainMs=${raw.mainTiming.totalMs} finite=${raw.finite}",
            )
            if (raw.finite) {
                val probability = host.noul(realisticState, statement, LayaEngine.Backend.NPU)
                Log.i(TAG, "FP32_NPU_PROBABILITY=$probability")
            }
        }
    }

    /**
     * Is the Inf caused by the input's magnitude, or is it there whatever we feed?
     *
     * The distinction decides whether the NPU path is *potentially* fixable by re-exporting the graph (a range
     * problem) or broken for a structural reason (a constant path, an op, the compiler's own transformation) that
     * re-exporting may or may not touch. It runs the same `CompiledModel` **once** and feeds a fixed input
     * *pattern* scaled over five magnitudes, so the only variable is amplitude.
     *
     * A constant embedding value would be a bad probe here: LayerNorm of a constant row has zero variance and
     * normalizes to zero at every scale, which would hide exactly the effect being measured. The pattern below
     * varies per position.
     *
     * Logs rather than asserts; it exists to characterise a failure the wfp16 test above already pins.
     */
    @Test
    fun mainGraphOnTheNpuIsProbedAcrossInputMagnitudes() {
        val vendor = LayaEngine.npuVendor(context)
        Assume.assumeTrue("this APK carries no NPU runtime", vendor != null)
        val mainFile = File(filesDir, "laya_ml_s256_embeds_wfp16.tflite")
        Assume.assumeTrue("wfp16 main graph not provisioned in filesDir", mainFile.isFile)

        val libDir = context.applicationInfo.nativeLibraryDir
        val environment =
            com.google.ai.edge.litert.Environment.create(
                context,
                mapOf(
                    com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                    com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
                ),
            )
        var model: com.google.ai.edge.litert.CompiledModel? = null
        val buffers = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
        try {
            val started = System.nanoTime()
            model =
                com.google.ai.edge.litert.CompiledModel.create(
                    mainFile.absolutePath,
                    com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.NPU),
                    environment,
                )
            Log.i(TAG, "NPU_SWEEP_COMPILE_MS=${elapsed(started)}")

            fun buffer(name: String, input: Boolean): com.google.ai.edge.litert.TensorBuffer {
                val created =
                    if (input) model!!.createInputBuffer(name, SIGNATURE)
                    else model!!.createOutputBuffer(name, SIGNATURE)
                buffers += created
                return created
            }
            val embeds = buffer("inputs_embeds", input = true)
            val mask = buffer("attention_mask", input = true)
            val qtype = buffer("qtype_onehot", input = true)
            val tokenLogits = buffer("token_logits", input = false)
            val pooledCls = buffer("pooled_cls", input = false)

            mask.writeFloat(FloatArray(LayaOfficialHost.WINDOW) { if (it < 20) 1f else 0f })
            qtype.writeFloat(floatArrayOf(0f, 0f, 1f))

            // A fixed, varying pattern; only its amplitude changes between runs.
            val pattern = FloatArray(97) { ((it % 11) - 5) * 0.2f }
            for (scale in listOf(0.0f, 0.001f, 0.01f, 0.1f, 1.0f)) {
                val row = FloatArray(LayaOfficialHost.WINDOW * 768) { scale * pattern[it % pattern.size] }
                embeds.writeFloat(row)
                val runStart = System.nanoTime()
                model.run(
                    mapOf(
                        "inputs_embeds" to embeds,
                        "attention_mask" to mask,
                        "qtype_onehot" to qtype,
                    ),
                    mapOf("token_logits" to tokenLogits, "pooled_cls" to pooledCls),
                    SIGNATURE,
                )
                Log.i(
                    TAG,
                    "NPU_SWEEP scale=$scale runMs=${elapsed(runStart)} " +
                        "tokenLogits[${describe(tokenLogits.readFloat())}] " +
                        "pooledCls[${describe(pooledCls.readFloat())}]",
                )
            }

            // The same zero input through a CPU-compiled copy of the *same file*. This is the control that
            // keeps "the write never happened and we read a stale buffer" from being an alternative
            // explanation of the zero-input result above: a finite CPU answer on this input proves the input
            // is valid and the buffer plumbing works, so an Inf on the NPU side is the NPU's own doing.
            val cpuModel =
                com.google.ai.edge.litert.CompiledModel.create(
                    mainFile.absolutePath,
                    com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.CPU)
                        .apply {
                            cpuOptions =
                                com.google.ai.edge.litert.CompiledModel.CpuOptions(numThreads = 4)
                        },
                    environment,
                )
            val cpuBuffers = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
            try {
                val cpuEmbeds = cpuModel.createInputBuffer("inputs_embeds", SIGNATURE).also { cpuBuffers += it }
                val cpuMask = cpuModel.createInputBuffer("attention_mask", SIGNATURE).also { cpuBuffers += it }
                val cpuQtype = cpuModel.createInputBuffer("qtype_onehot", SIGNATURE).also { cpuBuffers += it }
                val cpuLogits = cpuModel.createOutputBuffer("token_logits", SIGNATURE).also { cpuBuffers += it }
                val cpuPooled = cpuModel.createOutputBuffer("pooled_cls", SIGNATURE).also { cpuBuffers += it }
                cpuEmbeds.writeFloat(FloatArray(LayaOfficialHost.WINDOW * 768))
                cpuMask.writeFloat(FloatArray(LayaOfficialHost.WINDOW) { if (it < 20) 1f else 0f })
                cpuQtype.writeFloat(floatArrayOf(0f, 0f, 1f))
                val cpuStart = System.nanoTime()
                cpuModel.run(
                    mapOf(
                        "inputs_embeds" to cpuEmbeds,
                        "attention_mask" to cpuMask,
                        "qtype_onehot" to cpuQtype,
                    ),
                    mapOf("token_logits" to cpuLogits, "pooled_cls" to cpuPooled),
                    SIGNATURE,
                )
                Log.i(
                    TAG,
                    "CPU_ZERO_INPUT runMs=${elapsed(cpuStart)} " +
                        "tokenLogits[${describe(cpuLogits.readFloat())}] " +
                        "pooledCls[${describe(cpuPooled.readFloat())}]",
                )
            } finally {
                cpuBuffers.forEach { runCatching { it.close() } }
                runCatching { cpuModel.close() }
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "NPU_SWEEP_FAILED ${failure.javaClass.name}: ${failure.message}")
        } finally {
            buffers.forEach { runCatching { it.close() } }
            runCatching { model?.close() }
        }
    }

    /**
     * A 4-op graph on the same NPU: the **platform** probe.
     *
     * If the act head also comes back non-finite, the failure is the accelerator path itself rather than
     * anything about Laya's 1809-op encoder (its rotary tables, its scaled LayerNorms, its rank-4 attention).
     * It runs the act head on the NPU deliberately, although the app keeps that graph on the CPU.
     *
     * Logs rather than asserts: the decisive assertion is in the wfp16 test above.
     */
    @Test
    fun tinyActHeadOnTheNpuIsProbed() {
        val vendor = LayaEngine.npuVendor(context)
        Assume.assumeTrue("this APK carries no NPU runtime", vendor != null)
        val actFile = File(filesDir, "laya_ml_act_head_fp32.tflite")
        Assume.assumeTrue("act head not provisioned in filesDir", actFile.isFile)

        val libDir = context.applicationInfo.nativeLibraryDir
        val environment =
            com.google.ai.edge.litert.Environment.create(
                context,
                mapOf(
                    com.google.ai.edge.litert.Environment.Option.DispatchLibraryDir to libDir,
                    com.google.ai.edge.litert.Environment.Option.CompilerPluginLibraryDir to libDir,
                ),
            )
        val started = System.nanoTime()
        var model: com.google.ai.edge.litert.CompiledModel? = null
        var feats: com.google.ai.edge.litert.TensorBuffer? = null
        var pooled: com.google.ai.edge.litert.TensorBuffer? = null
        var logits: com.google.ai.edge.litert.TensorBuffer? = null
        try {
            val options =
                com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.NPU)
            model = com.google.ai.edge.litert.CompiledModel.create(actFile.absolutePath, options, environment)
            Log.i(TAG, "NPU_ACT_HEAD_COMPILE_MS=${elapsed(started)}")
            feats = model.createInputBuffer("feats", SIGNATURE)
            pooled = model.createInputBuffer("pooled_cls", SIGNATURE)
            logits = model.createOutputBuffer("act_logits", SIGNATURE)
            feats.writeFloat(FloatArray(4) { 0.1f * (it + 1) })
            pooled.writeFloat(FloatArray(768) { 0.05f })
            model.run(
                mapOf("feats" to feats, "pooled_cls" to pooled),
                mapOf("act_logits" to logits),
                SIGNATURE,
            )
            val values = logits.readFloat()
            Log.i(TAG, "NPU_ACT_HEAD logits[${describe(values)}] values=${values.toList()}")
        } catch (failure: Throwable) {
            Log.e(TAG, "NPU_ACT_HEAD_FAILED ${failure.javaClass.name}: ${failure.message}")
        } finally {
            listOf(feats, pooled, logits).forEach { runCatching { it?.close() } }
            runCatching { model?.close() }
        }
    }

    /**
     * The split behind one backend's total, so a graph figure can be compared with the publisher's published
     * *graph* number (51.9 ms GPU / 36.5 ms NPU on the S26) without quoting a total that also contains the
     * host's tokenizer, table lookup and decode.
     */
    private fun report(backend: String, compileMs: Double, probability: Double, row: LayaEngine.AnswerResult) {
        val actMs = row.raw.actTiming?.totalMs
        Log.i(
            TAG,
            "SPLIT_$backend compileMs=$compileMs probability=$probability total=${row.totalMs} " +
                "graphMain=${row.raw.mainTiming.totalMs} graphAct=$actMs " +
                "graphTotal=${row.raw.mainTiming.totalMs + (actMs ?: 0.0)} " +
                "prepare=${row.prepareMs} embedLookup=${row.raw.embeddingLookupMs} " +
                "decode=${row.decodeMs} finite=${row.raw.finite}",
        )
    }

    private fun elapsed(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    /**
     * The shape of one tensor, with NaN and Inf counted separately.
     *
     * Separate counters are the point: `Inf` is an fp16 overflow (the published failure mode for this graph in
     * fp16), while `NaN` with no `Inf` at all is what bad data plumbing or a 0/0 looks like. `maxAbsFinite` says
     * how close the arithmetic came to the fp16 ceiling of 65504.
     */
    private fun describe(values: FloatArray): String {
        var nan = 0
        var inf = 0
        var maxAbs = 0.0
        for (value in values) {
            when {
                value.isNaN() -> nan++
                value.isInfinite() -> inf++
                else -> maxAbs = maxOf(maxAbs, kotlin.math.abs(value.toDouble()))
            }
        }
        return "n=${values.size} nan=$nan inf=$inf maxAbsFinite=$maxAbs"
    }

    private companion object {
        const val TAG = "LayaNpu"
        const val SIGNATURE = "serving_default"
    }
}
