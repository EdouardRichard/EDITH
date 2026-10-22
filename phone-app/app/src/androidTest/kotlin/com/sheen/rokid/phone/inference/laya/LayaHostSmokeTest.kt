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
 * Smoke test for the switched package: does the publisher's host load the recommended file set on this
 * device and return finite, sane answers?
 *
 * **What this is not.** It is not the publisher's 201-row parity gate. That gate exists - the sample
 * exposes it through `am start --ez gate true --es accel gpu --es storage wfp16`, writing
 * `files/laya_gate_<accel>_<window>.json`, and `fixtures/gate_rows_s256.json` holds the reference rows -
 * and it is worth running if this path is ever suspected. It is not run here because the deliverable is a
 * package switch, not a re-validation of the publisher's conversion, which their own card documents as
 * 81/81 argmax and max dp 0.0014 for this exact file set on a validated device.
 *
 * What a smoke test *is* for, and why it is still worth its two minutes: every previous step in this
 * session that skipped it produced a confident wrong reading - a 295x GPU speedup, an all-zero GPU output,
 * a "CPU never settles". Those were our own defects, and a cheap end-to-end call is what catches them.
 *
 * The publisher's files must already be in `filesDir`; see the provisioning note in the evidence file.
 * Missing files skip rather than fail, so this test does not block on a device that was never provisioned.
 */
@RunWith(AndroidJUnit4::class)
class LayaHostSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir get() = context.filesDir

    /**
     * A realistic Chinese state - and deliberately without any time field.
     *
     * D60/C10 forbids time features in Laya's state, because it makes the gate a pure function of the
     * state (so an unchanged state cannot produce a new answer, which is why the gate is event-driven with
     * no heartbeat). An earlier revision of this fixture carried dwell_seconds and contradicted the
     * decision; it is exactly the kind of drift a fine-tuning session would copy as the reference state
     * shape, so the reason is pinned here rather than left to the reader.
     */
    private val realisticState =
        """{"foreground":"com.tencent.mm","notification_category":"msg",""" +
            """"recent_transcript":"这个优惠券怎么用啊"}"""

    private fun provisioned(): Boolean =
        LayaOfficialHost.REQUIRED_FILES.all { File(filesDir, it).isFile }

    @Test
    fun theOfficialHostLoadsAndAnswersOnThisDevice() {
        val missing = LayaOfficialHost.REQUIRED_FILES.filterNot { File(filesDir, it).isFile }
        Assume.assumeTrue(
            "publisher files not provisioned in filesDir; missing ${missing.joinToString()}",
            missing.isEmpty(),
        )

        val host = LayaOfficialHost(context, LayaEngine.Storage.WFP16)
        try {
            Log.i(TAG, "LOAD tokenizerMs=${host.tokenizerLoadMs} embeddingsMs=${host.embeddingLoadMs}")

            val compileStart = System.nanoTime()
            host.initialize(LayaEngine.Backend.CPU)
            Log.i(TAG, "COMPILE_CPU_MS=${(System.nanoTime() - compileStart) / 1_000_000.0}")

            // One noul question - the shape D60 gives Laya. Chinese state, because that is the deployment
            // language and the multilingual checkpoint is the reason this package was chosen.
            val state = realisticState
            val probability = host.noul(state, "用户此刻需要帮助")
            Log.i(TAG, "NOUL_PROBABILITY=$probability")
            org.junit.Assert.assertTrue(
                "noul probability outside [0,1]: $probability",
                probability.isFinite() && probability >= 0.0 && probability <= 1.0,
            )

            // One choice question, which exercises the marker gathering and the multi-option decode path
            // that noul does not.
            val answer = host.choice(
                state,
                "What should happen next?",
                linkedMapOf("intervene" to "offer help now", "silence" to "stay quiet"),
            )
            Log.i(TAG, "CHOICE_ANSWER=$answer")
            val chosen = answer["choice"]
            org.junit.Assert.assertTrue(
                "choice answer is not one of the criteria: $chosen",
                chosen == "intervene" || chosen == "silence",
            )
            @Suppress("UNCHECKED_CAST")
            val probabilities = answer["probabilities"] as? Map<String, Any?>
            org.junit.Assert.assertNotNull("choice answer carried no probabilities", probabilities)
            val sum = probabilities!!.values.filterIsInstance<Double>().sum()
            Log.i(TAG, "CHOICE_PROBABILITY_SUM=$sum")
            org.junit.Assert.assertEquals("probabilities do not sum to 1", 1.0, sum, 0.01)
        } finally {
            host.close()
        }
    }

    /**
     * CPU against GPU on whichever device is attached, with the answers cross-checked.
     *
     * The cross-check is the point, not ceremony. On the K40 with LiteRT 2.1.0 the GPU silently disagreed
     * with the CPU by 3.31 on a logit scale of 4.57 while running 4x faster, and that went unnoticed until
     * a value comparison was added. On 2.2.0 the same comparison agrees to 7.6e-6. A latency figure without
     * an output comparison is not a measurement of anything, so every timing below is paired with the
     * question "did it compute the same answer".
     *
     * One engine instance serves both backends: `LayaEngine` caches compiled graphs per
     * `(window, backend, storage)`, and one `Environment` per process is the publisher's design (and the
     * measured hardware limit on the K40, where a handful of instantiations makes `create` fail).
     */
    @Test
    fun cpuAndGpuAgreeAndHowFast() {
        val missing = LayaOfficialHost.REQUIRED_FILES.filterNot { File(filesDir, it).isFile }
        Assume.assumeTrue("publisher files not provisioned: missing ${missing.joinToString()}", missing.isEmpty())

        val state = realisticState
        val statement = "用户此刻需要帮助"

        val host = LayaOfficialHost(context, LayaEngine.Storage.WFP16)
        try {
            val results = linkedMapOf<LayaEngine.Backend, List<Double>>()
            val answers = linkedMapOf<LayaEngine.Backend, Double>()

            for (backend in listOf(LayaEngine.Backend.CPU, LayaEngine.Backend.GPU)) {
                val compiled = System.nanoTime()
                host.initialize(backend)
                val compileMs = (System.nanoTime() - compiled) / 1_000_000.0

                // One untimed call warms the path, then five timed ones.
                answers[backend] = host.noul(state, statement, backend)
                val times = (1..5).map {
                    val started = System.nanoTime()
                    val p = host.noul(state, statement, backend)
                    answers[backend] = p
                    (System.nanoTime() - started) / 1_000_000.0
                }.sorted()
                results[backend] = times
                Log.i(TAG, "${backend.name}_COMPILE_MS=$compileMs ${backend.name}_MEDIAN_MS=${times[2]} " +
                    "${backend.name}_SAMPLES=$times")
            }

            val cpu = answers.getValue(LayaEngine.Backend.CPU)
            val gpu = answers.getValue(LayaEngine.Backend.GPU)
            Log.i(TAG, "AGREEMENT cpu=$cpu gpu=$gpu absDiff=${kotlin.math.abs(cpu - gpu)}")

            org.junit.Assert.assertTrue("CPU probability not finite: $cpu", cpu.isFinite())
            org.junit.Assert.assertTrue("GPU probability not finite: $gpu", gpu.isFinite())
            org.junit.Assert.assertEquals(
                "GPU and CPU disagree on the noul probability; the GPU number is not usable until they " +
                    "agree (this is how the LiteRT 2.1.0 miscompute was caught)",
                cpu, gpu, 0.01,
            )

            val cpuMedian = results.getValue(LayaEngine.Backend.CPU)[2]
            val gpuMedian = results.getValue(LayaEngine.Backend.GPU)[2]
            Log.i(TAG, "RATIO cpuOverGpu=${"%.2f".format(cpuMedian / gpuMedian)}")
        } finally {
            host.close()
        }
    }

    /**
     * Where the per-question time actually goes.
     *
     * This exists because the end-to-end figure is not comparable with anything published. The publisher
     * quotes **graph** time (51.9 ms GPU, 163 ms CPU on an S26); the K40 measurements in this session were
     * also graph-only (`CompiledModel.run`). A `LayaEngine.answer` call additionally tokenizes, builds the
     * prompt, gathers 256 rows out of the 375 MiB table and decodes, so quoting its total against those
     * figures would overstate the cost of the part being compared - the same category error as comparing
     * GPU and CPU latencies measured in different thermal states.
     *
     * The split comes from the host's own timers, not from instrumentation around it.
     */
    @Test
    fun whereTheTimeGoes() {
        val missing = LayaOfficialHost.REQUIRED_FILES.filterNot { File(filesDir, it).isFile }
        Assume.assumeTrue("publisher files not provisioned: missing ${missing.joinToString()}", missing.isEmpty())

        val state = realisticState
        val question = linkedMapOf<String, Any?>("type" to "noul", "instructions" to "用户此刻需要帮助",
            "criteria" to null)

        LayaOfficialHost(context, LayaEngine.Storage.WFP16).use { host ->
            for (backend in listOf(LayaEngine.Backend.CPU, LayaEngine.Backend.GPU)) {
                host.initialize(backend)
                host.engine.answer(state, question, backend, "warm", LayaOfficialHost.WINDOW) // warm

                val rows = (1..5).map { host.engine.answer(state, question, backend, "t$it", LayaOfficialHost.WINDOW) }
                val median = rows.sortedBy { it.totalMs }[2]
                val graphMs = median.raw.mainTiming.totalMs
                val actMs = median.raw.actTiming?.totalMs
                Log.i(TAG, "SPLIT_${backend.name} total=${median.totalMs} " +
                    "graphMain=${graphMs} graphAct=$actMs " +
                    "graphTotal=${graphMs + (actMs ?: 0.0)} " +
                    "prepare=${median.prepareMs} embedLookup=${median.raw.embeddingLookupMs} " +
                    "decode=${median.decodeMs} finite=${median.raw.finite}")
            }
        }
    }

    private companion object {
        const val TAG = "LayaSmoke"
    }
}
