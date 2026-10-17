package com.sheen.rokid.phone
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T17 dual-end run: does a frame uplinked by the glasses actually reach the phone?
 *
 * Every other piece of the uplink is either unit tested or verified on hardware, but **no frame has ever been received
 * by the phone on a device**. This test is the only thing that closes that link, so it is deliberately narrow: it
 * opens the CXR session, waits, and reports what the receiver counted.
 *
 * It captures nothing and asserts nothing about audio content. The glasses side must be running its uplink at the
 * same time, so the test waits for a window rather than driving both ends from one place - and it **fails** if no
 * frame arrives, because a silent pass here would be indistinguishable from a working uplink.
 */
@RunWith(AndroidJUnit4::class)
class UplinkReceptionTest {
    @Test fun framesSentByTheGlassesAreReceivedByThePhone() {
        // Printed before anything else so an orchestrator can prove this observer is actually running before it
        // triggers the glasses. Without it, a run where the instrumentation never started looks identical to a run
        // where the uplink delivered nothing - and frames sent with no listener prove nothing either way.
        println("UPLINK_RECEPTION observerStarted")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as EdithApplication
        // The fake application builds a composition without a real CXR factory unless asked for one, and only the app's
        // own composition can receive the AI-App authorization result through its Activity. This is the same bring-up
        // seam the controlled-format test uses. The observer is intentionally a no-op: this test needs the frame
        // counters, not the samples, and retaining samples would make it easy to persist audio D33 keeps in RAM.
        FakePhoneApplication.bringUpRawPcmObserver = { }
        val root = application.composition

        val ready: Boolean
        // The SDK hands the authorization result to the Activity, so the session must be opened from the app's own
        // RESUMED Activity - this device blocks instrumentation-driven background Activity starts.
        ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity ->
                assertTrue("the app composition must expose its CXR entry point", root.connectCxr(activity))
            }
            ready = awaitReady(root)
        }

        // Without a session the glasses has no peer and the run is meaningless, so it is reported rather than
        // silently producing a false negative.
        if (!ready) {
            println("UPLINK_RECEPTION notReady error=${root.state.value.errorCode}")
        }
        assumeTrue("a CXR session is required before the uplink can be observed", ready)

        // Wait for the glasses side to uplink. The window covers the probe's own capture run.
        val deadline = System.nanoTime() + OBSERVE_MILLIS * 1_000_000L
        var report = root.cxrUplinkReport()
        while (System.nanoTime() < deadline && (report == null || !report.receivedAnything)) {
            Thread.sleep(POLL_MILLIS)
            report = root.cxrUplinkReport()
        }

        val observed = report ?: error("the live connection must expose an uplink receiver")
        println("UPLINK_RECEPTION report=${observed.summary()}")
        assertTrue(
            "the phone must receive at least one uplinked frame; report=${observed.summary()}",
            observed.receivedAnything,
        )
        // A received frame whose declared geometry is wrong would mean the sender and receiver disagree about the
        // format, which is worse than receiving nothing because it produces plausible noise.
        assertTrue(
            "the declared geometry must match what the glasses captured; report=${observed.summary()}",
            observed.declaredSampleRateHz == 16_000 && observed.declaredChannelCount == 2,
        )
    }

    private fun awaitReady(root: HostCompositionRoot): Boolean {
        val deadline = System.nanoTime() + READY_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (root.cxrReady()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private companion object {
        const val POLL_MILLIS = 200L
        const val OBSERVE_MILLIS = 45_000L
        const val READY_TIMEOUT_MILLIS = 120_000L
    }
}
