package com.sheen.rokid.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sender turns captured audio into uplink frames and hands them to a transport.
 *
 * The rules that matter are about what happens when the link cannot keep up: the capture thread must never block
 * (that would stall the microphone), and dropped audio must be **counted** rather than silently discarded, because a
 * hole in the stream is otherwise indistinguishable from a quiet room.
 */
class BoundedAudioUplinkSenderTest {
    /** Records every datagram the sender hands over, and can be told to refuse. */
    private class RecordingTransport(private var accept: (Int) -> Boolean = { true }) {
        val sent = mutableListOf<ByteArray>()
        fun asFunction(): (ByteArray) -> Boolean = { bytes ->
            sent += bytes
            accept(sent.size)
        }
        fun refuseAfter(count: Int) { accept = { index -> index <= count } }
        fun refuseAll() { accept = { false } }
    }

    private val selector = UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(0, 1))

    /** One 10 ms block of 8-channel silence at 16 kHz: 160 frames x 8 channels x 2 bytes. */
    private fun block(frames: Int = 160, value: Int = 1_000): ByteArray =
        ByteArray(frames * 8 * 2).also { bytes ->
            for (index in bytes.indices step 2) {
                bytes[index] = (value and 0xFF).toByte()
                bytes[index + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }

    private fun sender(transport: RecordingTransport) = BoundedAudioUplinkSender(
        selector = selector, sessionTag = "s-1", sampleRateHz = 16_000, transport = transport.asFunction(),
    )

    @Test fun startDeclaresTheSelectedGeometrySoTheReceiverNeverGuesses() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        assertTrue(subject.start())
        val start = AudioUplinkCodec.decode(transport.sent.single()) as UplinkMessage.Start
        assertEquals("s-1", start.sessionTag)
        assertEquals(16_000, start.sampleRateHz)
        assertEquals(8, start.channelCount, "the capture geometry is reported for diagnostics")
        assertEquals(listOf(0, 1), start.selectedChannels)
    }

    @Test fun noAudioIsSentWhenTheStartWasRefused() {
        val transport = RecordingTransport()
        transport.refuseAll()
        val subject = sender(transport)
        assertTrue(!subject.start(), "a refused START must be reported")
        assertEquals(UplinkSend.Buffered, subject.offer(block()))
        assertEquals(0L, subject.stats.framesSent, "audio must not flow before the receiver has the geometry")
    }

    @Test fun samplesAreAccumulatedIntoWholeFramesNotSentPerCaptureBlock() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        // 40 ms frames need 640 samples per channel. A 10 ms block is a quarter of that, so nothing is due yet.
        assertEquals(UplinkSend.Buffered, subject.offer(block(frames = 160)))
        assertEquals(1, transport.sent.size, "only the START frame so far")
        assertEquals(UplinkSend.Buffered, subject.offer(block(frames = 160)))
        assertEquals(UplinkSend.Buffered, subject.offer(block(frames = 160)))
        assertEquals(UplinkSend.Sent, subject.offer(block(frames = 160)), "the fourth block completes a frame")
        assertEquals(2, transport.sent.size)
        assertEquals(1L, subject.stats.framesSent)
    }

    @Test fun aSentFrameCarriesTheSelectedChannelsAndItsSequence() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        repeat(4) { subject.offer(block(frames = 160)) }
        val frame = AudioUplinkCodec.decode(transport.sent[1]) as UplinkMessage.Pcm
        assertEquals(640 * 2 * 2, frame.pcm.size, "40 ms of 2-channel 16-bit audio")
        assertEquals(0L, frame.sequence)
    }

    @Test fun sequencesAdvanceByOnePerFrameSoThePhoneCanDetectLoss() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        repeat(12) { subject.offer(block(frames = 160)) }
        val sequences = transport.sent.drop(1).map { (AudioUplinkCodec.decode(it) as UplinkMessage.Pcm).sequence }
        assertEquals(listOf(0L, 1L, 2L), sequences)
    }

    @Test fun aRefusedFrameIsCountedAsDroppedAndTheNextOneIsNotQueuedBehindIt() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        transport.refuseAfter(1) // accept START, refuse the first audio frame
        val first = subject.offer(block(frames = 640))
        assertEquals(UplinkSend.DroppedBackpressure, first)
        assertEquals(1L, subject.stats.framesDropped)
        // The following frame must also be refused rather than queued: otherwise the backlog grows without bound.
        val second = subject.offer(block(frames = 640))
        assertEquals(UplinkSend.DroppedBackpressure, second)
        assertEquals(2L, subject.stats.framesDropped)
        assertEquals(0L, subject.stats.framesSent)
    }

    @Test fun theSenderRecoversOnceTheTransportAcceptsAgain() {
        val transport = RecordingTransport()
        var allow = true
        val subject = BoundedAudioUplinkSender(
            selector = selector, sessionTag = "s-1", sampleRateHz = 16_000,
            transport = { bytes -> allow && transport.asFunction()(bytes) },
        )
        assertTrue(subject.start(), "START must be accepted so the stream is live")
        allow = false
        repeat(4) { subject.offer(block(frames = 160)) }
        assertTrue(subject.stats.framesDropped > 0, "a stalled transport must show up as dropped audio")
        allow = true
        val outcome = subject.offer(block(frames = 640))
        assertEquals(UplinkSend.Sent, outcome, "once the link recovers, audio flows again")
        assertTrue(subject.stats.framesSent > 0)
    }

    @Test fun theCaptureThreadIsNeverBlockedByTheTransport() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        // A transport that refuses everything must not make offer() loop or wait; it returns promptly, every time.
        transport.refuseAll()
        val startedAt = System.nanoTime()
        repeat(50) { subject.offer(block(frames = 640)) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMillis < 2_000, "offering must return promptly, took ${elapsedMillis}ms")
        assertEquals(0L, subject.stats.framesSent)
    }

    @Test fun finishSendsWholeFramesAndThenSaysWhyItStopped() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        // Two whole frames plus a partial third that must be discarded rather than padded.
        subject.offer(block(frames = 1_400))
        assertTrue(subject.finish(UplinkEndReason.UserStopped))
        val end = AudioUplinkCodec.decode(transport.sent.last()) as UplinkMessage.End
        assertEquals(UplinkEndReason.UserStopped, end.reason)
        assertEquals(2L, end.framesSent, "the partial trailing frame is not sent")
    }

    @Test fun theEndFrameIsSentEvenAfterAudioWasDropped() {
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        transport.refuseAfter(1)
        repeat(4) { subject.offer(block(frames = 640)) }
        // Allow the END through: the phone must learn the stream stopped, not merely that frames ceased.
        transport.refuseAfter(transport.sent.size + 1)
        assertTrue(subject.finish(UplinkEndReason.Error))
        val end = AudioUplinkCodec.decode(transport.sent.last()) as UplinkMessage.End
        assertEquals(UplinkEndReason.Error, end.reason)
    }

    @Test fun aFrameIsNeverSawnFromTwoCaptureBlocksOutOfOrder() {
        // Two blocks with distinguishable channel values: the sent frame must contain the first block's samples
        // first. A sender that reordered here would scramble audio while keeping the byte count identical.
        val transport = RecordingTransport()
        val subject = sender(transport)
        subject.start()
        subject.offer(block(frames = 320, value = 111))
        subject.offer(block(frames = 320, value = 222))
        val frame = AudioUplinkCodec.decode(transport.sent[1]) as UplinkMessage.Pcm
        val samples = (frame.pcm.indices step 2).map { index ->
            val low = frame.pcm[index].toInt() and 0xFF
            val high = frame.pcm[index + 1].toInt()
            (high shl 8) or low
        }
        assertEquals(640 * 2, samples.size)
        assertEquals(111, samples.first(), "the first block's audio comes first")
        assertEquals(222, samples.last(), "the second block's audio comes last")
        assertNotNull(frame.pcm)
        assertNull(null)
    }
}
