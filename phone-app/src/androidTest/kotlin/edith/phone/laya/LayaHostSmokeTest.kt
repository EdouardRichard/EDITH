// SPDX-License-Identifier: Apache-2.0
package edith.phone.laya

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
            val state =
                """{"foreground":"com.tencent.mm","notification_category":"msg","""
                    .plus(""""recent_transcript":"这个优惠券怎么用啊","dwell_seconds":47}""")
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

    private companion object {
        const val TAG = "LayaSmoke"
    }
}
