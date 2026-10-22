package com.sheen.rokid.audio

import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone-side half of the glasses audio uplink.
 *
 * It is deliberately strict about one thing above all: **no audio is offered to the pipeline before the sender has
 * declared the format.** Feeding undeclared bytes into ASR is the failure that costs the most, because the pipeline
 * happily consumes them and produces confident nonsense transcripts rather than an error.
 */
class AudioUplinkReceiverTest {
    private val clock = FakeUplinkClock()
    private val ingress = PcmIngress(clock)
    private val epoch = InputEpoch(SessionId("s-11111111-2222-3333-4444-555555555555"), Generation(1), 0L)

    private fun receiver(): AudioUplinkReceiver = AudioUplinkReceiver(ingress, clock)

    private fun openReceiver(): AudioUplinkReceiver = receiver().also {
        it.start(epoch)
        it.feed(AudioUplinkCodec.encodeStart("s-1", 16_000, 8, 16, listOf(0, 1)))
    }

    private fun pcm(samples: Int, value: Int = 1_000): ByteArray =
        ByteArray(samples * 2).also { bytes ->
            for (index in 0 until samples) {
                bytes[index * 2] = (value and 0xFF).toByte()
                bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }

    @Test fun audioBeforeTheFormatIsDeclaredIsRefusedNotForwarded() {
        val receiver = receiver()
        receiver.start(epoch)
        val outcome = receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160)))
        assertEquals(UplinkFeed.RefusedBeforeStart, outcome)
        assertEquals(0, receiver.stats.framesAccepted)
        assertNull(ingress.poll(), "nothing may reach the pipeline without a declared format")
    }

    @Test fun aDeclaredFormatIsReportedForTheIngressToOpen() {
        val receiver = openReceiver()
        val format = receiver.declaredFormat
        assertNotNull(format, "a START frame must publish the declared format")
        assertEquals(16_000, format.sampleRateHz)
        // The receiver reports the selection in the terms the ingress needs: 2 uplinked channels, not the capture's 8.
        assertEquals(2, format.selectedChannels.size)
        assertEquals(8, format.channelCount, "the capture geometry stays visible for diagnostics")
        assertEquals(16, format.bitsPerSample)
    }

    @Test fun anAcceptedFrameReachesTheIngressWithItsSequence() {
        val receiver = openReceiver()
        val outcome = receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(320)))
        assertEquals(UplinkFeed.Accepted, outcome)
        val chunk = ingress.poll()
        assertNotNull(chunk, "audio must reach the pipeline")
        // The ingress owns its own delivery sequence; the wire sequence is the receiver's gap-detection input.
        assertEquals(0L, chunk.sequence)
        assertEquals(640, chunk.bytes.size)
        assertEquals(1L, receiver.stats.framesAccepted)
    }

    @Test fun aGapInSequenceIsReportedSoLostAudioIsCountedNotHidden() {
        val receiver = openReceiver()
        receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160)))
        // Sequence 2 never arrives: the sender lost it, and the phone must be able to say so.
        val outcome = receiver.feed(AudioUplinkCodec.encodePcm(3L, 0L, pcm(160)))
        assertEquals(UplinkFeed.AcceptedWithGap, outcome)
        assertEquals(1L, receiver.stats.gaps)
        assertEquals(1L, receiver.stats.framesLost)
        // Both accepted frames must actually be in the pipeline.
        assertNotNull(ingress.poll())
        assertNotNull(ingress.poll(), "the frame after the gap is real audio and must be delivered")
        assertNull(ingress.poll())
    }

    @Test fun aRepeatedSequenceIsCountedAsDuplicateRatherThanReplayed() {
        val receiver = openReceiver()
        assertEquals(UplinkFeed.Accepted, receiver.feed(AudioUplinkCodec.encodePcm(5L, 0L, pcm(160))))
        // The outcome must name the duplicate specifically. Asserting only "not delivered twice" would also pass if
        // the frame were refused for an unrelated reason, which is exactly what a broken duplicate fence looks like.
        assertEquals(UplinkFeed.Duplicate, receiver.feed(AudioUplinkCodec.encodePcm(5L, 0L, pcm(160))))
        assertEquals(1L, receiver.stats.duplicates)
        assertEquals(1L, receiver.stats.framesAccepted, "only the first copy counts as accepted audio")
        assertNotNull(ingress.poll())
        assertNull(ingress.poll(), "a duplicate must not be delivered twice")
    }

    @Test fun anOutOfOrderFrameIsRefusedRatherThanRewindingTheAudio() {
        val receiver = openReceiver()
        receiver.feed(AudioUplinkCodec.encodePcm(7L, 0L, pcm(160)))
        // An older frame arriving late would rewind the transcript if it were delivered.
        assertEquals(UplinkFeed.Duplicate, receiver.feed(AudioUplinkCodec.encodePcm(4L, 0L, pcm(160))))
        assertEquals(1L, receiver.stats.duplicates)
        assertNotNull(ingress.poll())
        assertNull(ingress.poll(), "a late older frame must not reach the pipeline")
    }

    @Test fun aMalformedFrameIsRefusedInsteadOfBeingFedAsAudio() {
        val receiver = openReceiver()
        val outcome = receiver.feed(ByteArray(64) { 0x5A })
        assertEquals(UplinkFeed.Malformed, outcome)
        assertEquals(0L, receiver.stats.framesAccepted)
        assertNull(ingress.poll())
    }

    @Test fun aFormatChangeMidStreamIsRefusedBecauseItWouldReshapeTheAudio() {
        val receiver = openReceiver()
        receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160)))
        val outcome = receiver.feed(AudioUplinkCodec.encodeStart("s-2", 48_000, 8, 16, listOf(0, 1)))
        assertEquals(UplinkFeed.RefusedAfterStart, outcome)
        // The original format still governs the stream.
        assertEquals(16_000, receiver.declaredFormat?.sampleRateHz)
    }

    @Test fun theEndFrameReportsWhyTheUplinkStopped() {
        val receiver = openReceiver()
        receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160)))
        receiver.feed(AudioUplinkCodec.encodeEnd(2L, framesSent = 1L, reason = UplinkEndReason.Error))
        assertEquals(UplinkEndReason.Error, receiver.endedWith)
        assertEquals(1L, receiver.stats.framesAccepted)
    }

    @Test fun ingressOverflowIsSurfacedInsteadOfSilentlyDroppingAudio() {
        val receiver = openReceiver()
        // The ingress holds maxBufferMillis (2000 ms) at the declared geometry: 16000 Hz x 2 channels x 2 bytes
        // = 128000 bytes. Push well past that without draining, so the overflow is unambiguous.
        var sawOverflow = false
        for (sequence in 1L..800L) {
            if (receiver.feed(AudioUplinkCodec.encodePcm(sequence, 0L, pcm(160))) == UplinkFeed.RefusedByOverflow) {
                sawOverflow = true
            }
        }
        // Overflow resets the buffer, so a later frame can be accepted again; the report is that it happened at all,
        // and that it was returned rather than thrown out of the transport callback.
        assertTrue(sawOverflow, "a full buffer must be reported to the caller, not hidden")
        assertTrue(receiver.stats.overflowed, "and the counter must record it")
    }

    @Test fun stoppingTheReceiverClosesTheIngress() {
        val receiver = openReceiver()
        receiver.stop()
        assertEquals(UplinkFeed.RefusedBeforeStart, receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160))))
    }

    @Test fun aSecondStartOnTheSameReceiverIsRefused() {
        val receiver = openReceiver()
        receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160)))
        // Re-announcing the format mid-stream is a protocol error; the sender must restart the session.
        val outcome = receiver.feed(AudioUplinkCodec.encodeStart("s-1", 16_000, 8, 16, listOf(0, 1)))
        assertEquals(UplinkFeed.RefusedAfterStart, outcome)
        // A full stop and start is the supported way to begin a new stream: fresh format, fresh sequence space.
        receiver.stop()
        receiver.start(epoch)
        assertNull(receiver.declaredFormat, "a restarted receiver must wait for a new declaration")
        assertEquals(UplinkFeed.Accepted, receiver.feed(AudioUplinkCodec.encodeStart("s-2", 16_000, 8, 16, listOf(0, 1))))
        assertEquals(UplinkFeed.Accepted, receiver.feed(AudioUplinkCodec.encodePcm(1L, 0L, pcm(160))))
    }
}

class FakeUplinkClock(var nowNanos: Long = 0) : MonotonicClock {
    override fun nowNanos(): Long = nowNanos
    fun advance(delta: Long) { nowNanos = Math.addExact(nowNanos, delta) }
}
