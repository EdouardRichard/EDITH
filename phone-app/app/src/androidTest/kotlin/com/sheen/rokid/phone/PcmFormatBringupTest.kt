package com.sheen.rokid.phone
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.transport.ControlledToneCapture
import com.sheen.rokid.transport.ControlledToneFormat
import com.sheen.rokid.transport.MonoPcmFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * T17 controlled format bring-up against real glasses, hosted by the real App.
 *
 * The SDK hands the AI-App authorization result to the activity, so the capture has to run through the
 * app's own composition — a composition built by the test could never receive the token. The activity is
 * started through the shell because this device blocks instrumentation-driven background Activity starts
 * (T14 finding). Readiness is therefore read from the live CXR transport
 * ([HostCompositionRoot.cxrReady]); the app's session state machine is not started on this path.
 *
 * Contract (D33): the user is told in advance to sustain one non-sensitive fixed vowel; the capture is a
 * bounded <= 12 s RAM buffer zeroed on every exit path; nothing is written to disk and no raw audio
 * leaves the device. The verdict proves only what the real signal proves — the declared byte rate for the
 * wall clock the audio arrived in, plus sample alignment and speech-level plausibility. A verdict is
 * evidence for the controller, not a promotion: `PcmFormatEvidence.resolve` stays null until a verified
 * pairing is recorded.
 */
@RunWith(AndroidJUnit4::class)
class PcmFormatBringupTest {
    @Test fun controlledVowelBurstReportsTheRealCaptureFormat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as EdithApplication
        val capture = ControlledToneCapture(maxDurationMillis = CAPTURE_MILLIS)
        val chunkSizes = AtomicLongArray(CHUNK_SAMPLE)
        val chunks = AtomicInteger()
        // Armed before the composition is created, because the connection captures the observer once.
        FakePhoneApplication.bringUpRawPcmObserver = { data ->
            capture.append(data)
            val index = chunks.getAndIncrement()
            if (index < CHUNK_SAMPLE) chunkSizes.set(index, data.size.toLong())
        }
        val root = application.composition
        val failure = StringBuilder()
        ForegroundTestActivity.launch().use { foreground ->
            try {
                foreground.onActivity { activity ->
                    assertTrue("the app composition must expose its CXR entry point", root.connectCxr(activity))
                }
                assertTrue("the AI App authorization must complete before capture: $failure", awaitReady(root, failure))
                val epoch = InputEpoch(SessionId("p2-t17-controlled"), Generation(0), 1)
                val input = root.cxrInputPort()
                assertTrue("the live CXR transport must be reachable", input != null)
                foreground.onActivity { input!!.start(epoch) }
                val startedAt = System.nanoTime()
                // Stop on the announced RAM bound (or on the observe cap), so the wall clock handed to the
                // byte-rate check is the window the audio actually arrived in, not a fixed sleep.
                while (!capture.overflowed && capture.capturedMillis() < CAPTURE_MILLIS &&
                    elapsedMillis(startedAt) < OBSERVE_MILLIS) Thread.sleep(20)
                val measuredMillis = elapsedMillis(startedAt)
                // Snapshot while the capture is still open: transport.stop() revokes the epoch and the
                // capture must be read before any later teardown can run into the closed-state guard.
                val bytes = capture.bytes().size
                val verdict = ControlledToneFormat.verifyVoiceBurst(capture, measuredMillis = measuredMillis)
                report(chunkSizes, chunks.get(), bytes, measuredMillis, verdict)
                input!!.stop(epoch)
                assertTrue("no real PCM reached the RAM capture ($failure)", bytes > 0)
                assertTrue("captured stream is not the declared 16 kHz mono PCM16LE: $verdict", verdict.monoPcm16Le)
            } finally {
                runCatching { root.cxrInputPort()?.stop(InputEpoch(SessionId("p2-t17-controlled"), Generation(0), 1)) }
                capture.close()
            }
        }
        assertTrue("capture must be wiped on every exit path", capture.closed)
    }

    private fun awaitReady(root: HostCompositionRoot, failure: StringBuilder): Boolean {
        val deadline = System.nanoTime() + READY_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            // The transport is the only thing that knows about readiness: the AI-App authorization is
            // asynchronous and the app session state machine is not started on this path.
            if (root.cxrReady()) return true
            root.state.value.errorCode?.let { code ->
                if (failure.indexOf("error=$code") < 0) failure.append("error=").append(code).append(' ')
            }
            Thread.sleep(200)
        }
        return false
    }

    private fun report(chunkSizes: AtomicLongArray, chunks: Int, bytes: Int, measuredMillis: Long,
        verdict: com.sheen.rokid.transport.VoiceBurstVerdict) {
        val sizes = (0 until minOf(chunks, CHUNK_SAMPLE)).joinToString(",") { chunkSizes.get(it).toString() }
        println("T17_FORMAT_REPORT chunks=$chunks firstChunkSizes=[$sizes] bytes=$bytes " +
            "measuredMillis=$measuredMillis measuredBytesPerSecond=${if (measuredMillis > 0) bytes * 1000.0 / measuredMillis else 0.0} " +
            "declaredBytesPerSecond=${MonoPcmFormat.bytesPerSecond()} verdict=$verdict")
    }

    private fun elapsedMillis(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private companion object {
        /** The harness bound the user is told about in advance: at most 12 s of live CXR PCM in RAM. */
        const val CAPTURE_MILLIS = 12_000
        const val OBSERVE_MILLIS = 30_000L
        const val READY_TIMEOUT_MILLIS = 120_000L
        const val CHUNK_SAMPLE = 8
    }
}
