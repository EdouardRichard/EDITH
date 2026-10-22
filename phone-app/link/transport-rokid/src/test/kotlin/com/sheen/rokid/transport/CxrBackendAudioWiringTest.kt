package com.sheen.rokid.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend must install audio on the **glasses client**, and route what that client reports to the epoch.
 *
 * This test exists because the gap it closes is exactly the defect the device measurements exposed: the old wiring
 * installed the callback with `CxrSession.addAudioCallback`, and on real hardware that received **zero** bytes while
 * the vendor's client-level `setCXRAudioCbk` registration received 652,160 bytes on the same glasses at the same
 * instant. No test ever asserted *where* audio is installed, so the wrong target passed every check the project had.
 */
class CxrBackendAudioWiringTest {

    private class Sink : RokidBackend.Audio {
        val chunks = mutableListOf<ByteArray>()
        val streamStates = mutableListOf<Boolean>()
        var failures = 0
        override fun received(data: ByteArray) { chunks += data }
        override fun failed() { failures++ }
        override fun streaming(active: Boolean) { streamStates += active }
    }

    @Test fun startingAudioInstallsOnTheClientRatherThanTheSessionWrapper() {
        val rig = CxrBackendRig()
        rig.authorize()
        val sink = Sink()

        assertTrue("audio must start", rig.backend.start(sink))

        assertEquals("the client-level installer must be used exactly once", 1, rig.installer.installs)
    }

    @Test fun aRefusedInstallationIsReportedRatherThanLookingLikeAStartedStream() {
        // A silent false here would leave the epoch waiting for audio that can never arrive.
        val rig = CxrBackendRig()
        rig.authorize()
        rig.installer.installResult = false

        assertFalse("a refused installation must not report success", rig.backend.start(Sink()))
    }

    @Test fun audioReportedByTheClientReachesTheEpoch() {
        val rig = CxrBackendRig()
        rig.authorize()
        val sink = Sink()
        rig.backend.start(sink)

        rig.installer.deliver(ByteArray(640) { 9 })

        assertEquals(1, sink.chunks.size)
        assertEquals(640, sink.chunks.single().size)
    }

    @Test fun streamStateIsForwardedSoTheEpochCanBeNotified() {
        val rig = CxrBackendRig()
        rig.authorize()
        val sink = Sink()
        rig.backend.start(sink)

        rig.installer.events!!.streaming(true)

        assertEquals(listOf(true), sink.streamStates)
    }

    @Test fun stoppingAudioReleasesTheInstallationExactlyOnce() {
        // Releasing twice would tear down an installation a later epoch may already own.
        val rig = CxrBackendRig()
        rig.authorize()
        val sink = Sink()
        rig.backend.start(sink)

        rig.backend.stop(sink)

        assertEquals(1, rig.installer.releases)
    }

    @Test fun closingTheConnectionReleasesAnInstallationThatWasNeverStopped() {
        // Teardown must not leak a live client registration: the AI App keeps delivering to it otherwise.
        val rig = CxrBackendRig()
        rig.authorize()
        rig.backend.start(Sink())

        rig.backend.close()

        assertEquals(1, rig.installer.releases)
    }

    @Test fun audioArrivingAfterCloseIsNotDeliveredToADeadEpoch() {
        // The vendor callback runs on its own thread and can fire after teardown; delivering then would feed a
        // finished session.
        val rig = CxrBackendRig()
        rig.authorize()
        val sink = Sink()
        rig.backend.start(sink)
        val installer = rig.installer

        rig.backend.close()
        installer.deliver(ByteArray(320) { 1 })

        assertTrue("a closed backend must not deliver audio to its sink", sink.chunks.isEmpty())
    }

    @Test fun aFailureReportedByTheClientReachesTheEpochSoItCanInterrupt() {
        val rig = CxrBackendRig()
        rig.authorize()
        val sink = Sink()
        rig.backend.start(sink)

        rig.installer.events!!.failed()

        assertEquals(1, sink.failures)
    }
}
