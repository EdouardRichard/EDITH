package com.sheen.rokid.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses audio callback must deliver exactly the declared byte range, survive bad ranges, and go quiet on detach.
 *
 * Why this seam exists at all is a device result, not a preference: on the same glasses at the same instant, the
 * vendor's own reference implementation - which installs its callback with `CXRLink.setCXRAudioCbk` - received
 * 652,160 bytes of microphone audio, while our transport, which installed via `CxrSession.addAudioCallback`, received
 * none, despite the glasses transmitting and our session reporting `startAudioStream: OK`. The two registrations are
 * not equivalent, so the installation now happens on the client and this class carries the rules that can be proven
 * without a device.
 */
class CxrAudioCallbackWiringTest {

    private class Sink : CxrAudioAdapter.Events {
        val chunks = mutableListOf<ByteArray>()
        val streamStates = mutableListOf<Boolean>()
        var failures = 0
        override fun received(data: ByteArray) { chunks += data }
        override fun streaming(active: Boolean) { streamStates += active }
        override fun failed() { failures++ }
    }

    @Test fun theDeclaredRangeIsWhatGetsDeliveredNotTheWholeReusedBuffer() {
        // Measured on device: a reused 192000-byte buffer of which only 640 bytes are valid.
        val sink = Sink()
        val adapter = CxrAudioAdapter().apply { attach(sink) }
        val buffer = ByteArray(192_000) { 0x5A }
        val valid = ByteArray(640) { 7 }
        System.arraycopy(valid, 0, buffer, 0, valid.size)

        adapter.onAudioReceived(buffer, 0, 640)

        assertEquals("exactly one chunk must be delivered", 1, sink.chunks.size)
        assertEquals("only the declared range may be delivered", 640, sink.chunks[0].size)
        assertArrayEquals(valid, sink.chunks[0])
    }

    @Test fun aNonZeroOffsetIsHonouredSoAlignedFramesAreNotPrefixedWithStaleBytes() {
        val sink = Sink()
        val adapter = CxrAudioAdapter().apply { attach(sink) }
        val buffer = ByteArray(1024) { 0x11 }
        val payload = ByteArray(100) { 0x42 }
        System.arraycopy(payload, 0, buffer, 300, payload.size)

        adapter.onAudioReceived(buffer, 300, 100)

        assertArrayEquals(payload, sink.chunks.single())
    }

    @Test fun aRangeThatWouldRunPastTheBufferIsClampedRatherThanThrowing() {
        // The SDK's own sample guards this because out-of-range values have been observed. Throwing here would escape
        // into the vendor's callback thread and kill the session, so clamping is the required behaviour.
        val sink = Sink()
        val adapter = CxrAudioAdapter().apply { attach(sink) }
        val buffer = ByteArray(64) { 1 }

        adapter.onAudioReceived(buffer, 60, 100)

        assertEquals("the deliverable remainder must still arrive", 4, sink.chunks.single().size)
    }

    @Test fun anEmptyOrInvalidRangeIsDroppedInsteadOfDeliveringNothingAsAudio() {
        val sink = Sink()
        val adapter = CxrAudioAdapter().apply { attach(sink) }

        adapter.onAudioReceived(ByteArray(0), 0, 0)
        adapter.onAudioReceived(ByteArray(10), 5, 0)
        adapter.onAudioReceived(ByteArray(10), -1, 4)
        adapter.onAudioReceived(ByteArray(10), 10, 4)
        adapter.onAudioReceived(null, 0, 4)

        assertTrue("no empty or invalid chunk may be delivered as audio", sink.chunks.isEmpty())
    }

    @Test fun streamStateAndErrorsAreForwardedSoTheHostCanStopOnFailure() {
        val sink = Sink()
        val adapter = CxrAudioAdapter().apply { attach(sink) }

        adapter.onAudioStreamStateChanged(true)
        adapter.onAudioStreamStateChanged(false)
        adapter.onAudioError(7, "boom")

        assertEquals(listOf(true, false), sink.streamStates)
        assertEquals("an error must reach the host so the epoch can be interrupted", 1, sink.failures)
    }

    @Test fun detachIsIdempotentAndStopsDeliverySoALaterEpochCannotBeFedStaleAudio() {
        val sink = Sink()
        val adapter = CxrAudioAdapter().apply { attach(sink) }

        adapter.detach()
        adapter.detach()
        adapter.onAudioReceived(ByteArray(10) { 1 }, 0, 10)
        adapter.onAudioStreamStateChanged(true)

        assertTrue("no audio may be delivered after detach", sink.chunks.isEmpty())
        assertTrue("no stream state may be reported after detach", sink.streamStates.isEmpty())
        assertFalse("detach must not be reportable as a failure", sink.failures > 0)
    }

    @Test fun reattachingReplacesTheSinkRatherThanDeliveringToOneOfTwoEpochs() {
        // Two epochs must never both receive the same chunk; the newest sink is the live one.
        val first = Sink()
        val second = Sink()
        val adapter = CxrAudioAdapter().apply { attach(first) }

        adapter.attach(second)
        adapter.onAudioReceived(ByteArray(8) { 3 }, 0, 8)

        assertTrue("the superseded sink must be silent", first.chunks.isEmpty())
        assertEquals(1, second.chunks.size)
    }
}
