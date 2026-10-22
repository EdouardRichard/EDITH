package com.sheen.rokid.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session loop is where a real-time audio path usually fails quietly: a run that captures but never sends, or
 * sends but never stops, still looks "running". These tests pin the exit paths, because the visible symptom of every
 * one of those failures is identical - no audio on the phone.
 */
class AudioUplinkSessionTest {
    /** Scripted microphone: yields the given reads, then reports that nothing is available. */
    private class ScriptedCapture(private val reads: MutableList<Int>, private val startResult: Boolean = true) :
        UplinkCapture {
        var started = 0
        var stopped = 0
        var readCalls = 0
        override fun start(): Boolean { started++; return startResult }
        override fun read(buffer: ByteArray): Int {
            readCalls++
            if (reads.isEmpty()) return 0
            val count = reads.removeAt(0)
            if (count < 0) return count
            // Fill with a recognisable non-silent pattern so a "captured but silent" bug cannot pass as success.
            for (index in 0 until count) buffer[index] = (index % 251).toByte()
            return count
        }
        override fun stop() { stopped++ }
    }

    private class Transport {
        val sent = mutableListOf<ByteArray>()
        var accept = true
        fun asFunction(): (ByteArray) -> Boolean = { bytes -> sent += bytes; accept }
    }

    private val selector = UplinkChannelSelector(sourceChannelCount = 8, selectedChannels = listOf(0, 1))

    private fun session(capture: UplinkCapture, transport: Transport) = AudioUplinkSession(
        capture = capture,
        sender = BoundedAudioUplinkSender(
            selector = selector, sessionTag = "s-1", sampleRateHz = 16_000, transport = transport.asFunction(),
        ),
        sessionTag = "s-1",
    )

    private fun frames(bytes: Int) = bytes // one 8-channel block of N bytes

    @Test fun aSuccessfulRunSendsAudioAndAnEndFrameThenStopsTheCapture() {
        val transport = Transport()
        val capture = ScriptedCapture(MutableList(40) { 4_096 })
        val outcome = session(capture, transport).run(durationMillis = 400)
        assertTrue(outcome.started)
        assertTrue(outcome.blocksRead > 0, "the loop must actually read audio")
        assertTrue(outcome.framesSent > 0, "captured audio must become sent frames")
        assertEquals(1, capture.stopped, "the microphone must be released on exit")
        val end = AudioUplinkCodec.decode(transport.sent.last()) as UplinkMessage.End
        assertEquals(UplinkEndReason.SourceEnded, end.reason, "the phone is told why the stream stopped")
    }

    @Test fun aCaptureThatRefusesToStartSendsNothingAtAll() {
        val transport = Transport()
        val capture = ScriptedCapture(MutableList(10) { 4_096 }, startResult = false)
        val outcome = session(capture, transport).run(durationMillis = 100)
        assertTrue(!outcome.started)
        assertTrue(transport.sent.isEmpty(), "no START may be announced for a microphone that never opened")
        assertEquals(UplinkEndReason.Error, outcome.endReason)
        assertEquals(0, capture.stopped, "a capture that never started has nothing to stop")
    }

    @Test fun aReadErrorStopsTheSessionAndStillTellsThePhone() {
        val transport = Transport()
        val capture = ScriptedCapture(mutableListOf(4_096, -1))
        val outcome = session(capture, transport).run(durationMillis = 400)
        assertEquals(UplinkEndReason.Error, outcome.endReason)
        assertEquals(1, capture.stopped)
        val end = AudioUplinkCodec.decode(transport.sent.last()) as UplinkMessage.End
        assertEquals(UplinkEndReason.Error, end.reason, "an error must be reported, not left as silence")
    }

    @Test fun aStopRequestEndsTheRunAndIsReportedAsUserStopped() {
        val transport = Transport()
        val capture = ScriptedCapture(MutableList(200) { 4_096 })
        var allowed = true
        val outcome = session(capture, transport).run(durationMillis = 5_000) {
            // Allow a few blocks, then ask the session to stop.
            if (allowed) { allowed = false; true } else false
        }
        assertEquals(UplinkEndReason.UserStopped, outcome.endReason)
        assertEquals(1, capture.stopped)
    }

    @Test fun aRefusedTransportStartDoesNotLeaveTheMicrophoneOpen() {
        val transport = Transport()
        transport.accept = false
        val capture = ScriptedCapture(MutableList(10) { 4_096 })
        val outcome = session(capture, transport).run(durationMillis = 200)
        assertTrue(!outcome.started, "a refused START must be reported as a failed session")
        assertEquals(1, capture.stopped, "and must not leak the microphone")
    }

    @Test fun aPartialReadIsForwardedAtItsRealLengthNotPadded() {
        val transport = Transport()
        // A short read must not be sent as if it were a full buffer: padding would insert silence into the stream.
        val capture = ScriptedCapture(mutableListOf(1_024, 1_024, 1_024, 1_024))
        val outcome = session(capture, transport).run(durationMillis = 300)
        assertTrue(outcome.bytesRead == 4_096L, "exactly the bytes read, not padded: ${outcome.bytesRead}")
        assertTrue(transport.sent.size >= 1)
    }

    @Test fun silenceOnlyCaptureStillSendsFramesSoThePhoneSeesAHealthyStream() {
        val transport = Transport()
        val capture = object : UplinkCapture {
            override fun start() = true
            override fun read(buffer: ByteArray): Int {
                // All-zero audio is a real capture result; the uplink must carry it rather than treat it as "no data".
                java.util.Arrays.fill(buffer, 0)
                return buffer.size
            }
            override fun stop() = Unit
        }
        val outcome = session(capture, transport).run(durationMillis = 300)
        assertTrue(outcome.framesSent > 0, "digital silence is still audio and must be delivered")
    }

    @Test fun aZeroDurationIsRefusedRatherThanRunningForever() {
        val transport = Transport()
        val capture = ScriptedCapture(MutableList(4) { 4_096 })
        val thrown = runCatching { session(capture, transport).run(durationMillis = 0) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "a non-positive duration is a programming error")
    }
}
