package com.sheen.rokid.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class PcmFormatEvidenceTest {
    @Test fun declaredSignedLittleEndianMonoToneHasCorrectDurationAndSpectrum() {
        val measurement = PcmFormatEvidence.validateControlledTone(tone(), 1000, 1000)
        assertNotNull("Signed LE 16k mono controlled tone must validate", measurement)
        assertEquals(1000.0, measurement!!.durationMillis, 0.01)
        assertEquals(1000.0, measurement.frequencyHz, 2.0)
        assertTrue(measurement.spectralPurity > 0.95)
        assertNull(PcmFormatEvidence.resolve("1.26.011", "1.1.2"))
    }
    @Test fun rejectsWrongEndianDurationChannelInterpretationAndSilence() {
        assertNull(PcmFormatEvidence.validateControlledTone(tone(ByteOrder.BIG_ENDIAN), 1000, 1000))
        assertNull(PcmFormatEvidence.validateControlledTone(tone(), 2000, 1000))
        assertNull(PcmFormatEvidence.validateControlledTone(tone() + tone(), 1000, 1000))
        assertNull(PcmFormatEvidence.validateControlledTone(ByteArray(32000), 1000, 1000))
        assertNull(PcmFormatEvidence.validateControlledTone(ByteArray(96002), 3000, 1000))
        assertNull(PcmFormatEvidence.validateControlledTone(ByteArray(3), 1000, 1000))
    }
    private fun tone(order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val buffer = ByteBuffer.allocate(32000).order(order)
        repeat(16000) { buffer.putShort((12000 * sin(2 * PI * 1000 * it / 16000)).toInt().toShort()) }
        return buffer.array()
    }
}
