package com.sheen.rokid.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * T17 controlled format verification: bounded RAM capture plus mono PCM16LE interpretation check.
 * No production code exists for these symbols yet — this run is the behavioral RED.
 */
class ControlledToneCaptureTest {
    @Test fun bufferStopsAtTwelveSecondsAndReportsOverflow() {
        val capture = ControlledToneCapture()
        capture.append(ByteArray(32000))
        capture.append(ByteArray(32000))
        capture.append(ByteArray(32000))
        assertFalse(capture.overflowed)
        assertEquals(32000 * 3, capture.bytes().size)
        capture.append(ByteArray(320_000))
        assertTrue(capture.overflowed)
        assertEquals(384_000, capture.bytes().size)
        assertEquals(12_000.0, capture.capturedMillis(), 0.001)
    }

    @Test fun closeWipesTheOnlyBufferAndRejectsFurtherUse() {
        val capture = ControlledToneCapture()
        capture.append(tone(1000))
        assertTrue(capture.bytes().any { it.toInt() != 0 })
        capture.close()
        assertTrue(capture.closed)
        assertThrows(IllegalStateException::class.java) { capture.bytes() }
        assertThrows(IllegalStateException::class.java) { capture.append(ByteArray(2)) }
        capture.close()
    }

    @Test fun returnedBytesAreASnapshotAndDoNotAliasTheBuffer() {
        val capture = ControlledToneCapture()
        capture.append(tone(500))
        val snapshot = capture.bytes()
        snapshot.fill(7)
        assertTrue("snapshot edits must not reach the retained capture", capture.bytes().all { it.toInt() == 0 || it.toInt() != 7 })
        assertNotSame(snapshot, capture.bytes())
    }

    @Test fun zeroCapacityCaptureDropsEveryChunk() {
        val capture = ControlledToneCapture(maxDurationMillis = 0)
        capture.append(ByteArray(2))
        assertTrue(capture.overflowed)
        assertEquals(0, capture.bytes().size)
        assertEquals(0.0, capture.capturedMillis(), 0.001)
    }

    @Test fun oneSecondSignedLittleEndianMonoToneVerifiesAsDeclaredMonoPcm() {
        val capture = ControlledToneCapture()
        capture.append(tone(1000))
        val verdict = ControlledToneFormat.verify(capture, 1000, 1000)
        assertTrue(verdict.verifiedMonoPcm16Le)
        val measurement = verdict.measurement!!
        assertEquals(1000.0, measurement.durationMillis, 0.01)
        assertEquals(1000.0, measurement.frequencyHz, 2.0)
        assertEquals(1, MonoPcmFormat.channels)
        assertEquals(16000, MonoPcmFormat.sampleRateHz)
        assertEquals(16, MonoPcmFormat.bitsPerSample)
    }

    @Test fun wrongDurationIsReportedAsADurationMismatchAndNeverAsVerified() {
        val verdict = ControlledToneFormat.verify(buffer(tone(1000)), 2000, 1000)
        assertFalse(verdict.verifiedMonoPcm16Le)
        assertNull(verdict.measurement)
        assertEquals(ControlledToneFormat.DURATION_MISMATCH, verdict.mismatch)
    }

    @Test fun bigEndianOrInterleavedStereoStreamIsReportedAsANonMonoInterpretation() {
        val bigEndian = ControlledToneFormat.verify(buffer(tone(1000, ByteOrder.BIG_ENDIAN)), 1000, 1000)
        assertFalse(bigEndian.verifiedMonoPcm16Le)
        assertEquals(ControlledToneFormat.SPECTRUM_MISMATCH, bigEndian.mismatch)

        val stereo = ByteBuffer.allocate(64000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16000) { index ->
            val value = (12000 * sin(2 * PI * 1000 * index / 16000)).toInt().toShort()
            stereo.putShort(value); stereo.putShort(value)
        }
        val interleaved = ControlledToneFormat.verify(buffer(stereo.array()), 2000, 1000)
        assertFalse("two 16 kHz mono channels interleaved must not read as one mono 16 kHz stream", interleaved.verifiedMonoPcm16Le)
        assertEquals(ControlledToneFormat.SPECTRUM_MISMATCH, interleaved.mismatch)
    }

    @Test fun emptyCaptureIsReportedAsNoAudio() {
        val verdict = ControlledToneFormat.verify(ControlledToneCapture(), 1000, 1000)
        assertFalse(verdict.verifiedMonoPcm16Le)
        assertEquals(ControlledToneFormat.NO_AUDIO, verdict.mismatch)
    }

    private fun buffer(pcm: ByteArray): ControlledToneCapture =
        ControlledToneCapture().also { it.append(pcm) }

    private fun tone(millis: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val samples = 16 * millis
        val buffer = ByteBuffer.allocate(samples * 2).order(order)
        repeat(samples) { buffer.putShort((12000 * sin(2 * PI * 1000 * it / 16000)).toInt().toShort()) }
        return buffer.array()
    }
}
