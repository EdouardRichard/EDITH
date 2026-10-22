package com.sheen.rokid.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The device bring-up cannot depend on a calibrated tone generator: a human sustained vowel is the only
 * signal the user can actually produce. [ControlledToneFormat.verifyVoiceBurst] therefore proves what a
 * human sound still proves — the captured byte count matches the declared 16 kHz mono PCM16LE byte rate
 * for the wall clock the audio actually arrived in, which a really stereo, 8-bit or 48 kHz stream cannot
 * satisfy — plus that the samples align to one coherent signed PCM16 stream at speech level.
 *
 * Channel count is not separately provable from an interleaved mono-like stimulus (two identical
 * channels interleave back into a stream indistinguishable from mono), so the harness deliberately does
 * not claim it; the byte rate is the channel/bit-depth evidence. Pitch is not asserted either:
 * zero-crossing pitch estimation is unusable for low male fundamentals, so a false assertion there would
 * reject a correct format.
 *
 * Two claims are deliberately separated by scope: the **byte rate** is checked against the wall clock for
 * the **whole capture**, while the **waveform** is checked over the strongest bounded (<= 3 s) window inside
 * it, whose offset is reported. Selecting a window is a detection decision - "this capture contains a signed
 * PCM16 speech waveform" - not an average-level claim about the whole capture.
 */
class VoiceBurstFormatTest {
    @Test fun sustainedVoicedBurstVerifiesTheDeclaredMonoByteRate() {
        val capture = buffer(voiced(220.0, millis = 1500))
        val verdict = ControlledToneFormat.verifyVoiceBurst(capture, measuredMillis = 1500)
        assertTrue(verdict.toString(), verdict.monoPcm16Le)
        assertNull(verdict.mismatch)
        val measurement = verdict.measurement!!
        assertEquals("measuredMillis", 1500L, measurement.measuredMillis)
        assertEquals("declaredBytes", 48000L, measurement.declaredBytes)
        assertEquals("declaredBytesPerSecond", 32000.0, measurement.declaredBytesPerSecond, 0.01)
        assertEquals("measuredBytesPerSecond", 32000.0, measurement.measuredBytesPerSecond, 0.01)
        assertEquals("crossingFrequencyHz", 220.0, measurement.crossingFrequencyHz, 22.0)
        assertTrue("speech-level rms", measurement.rms > MonoPcmFormat.MIN_SPEECH_RMS)
        assertTrue("frame activity", measurement.transitions > 0)
    }

    @Test fun aCaptureLongerThanTheAnalysisBoundIsMeasuredInsteadOfBeingRejected() {
        // Device reality: the harness records up to 12 s (384,000 B) because the AI-App authorisation and
        // the speaker's start are not synchronised, and every real device run produced 384,000 bytes. The
        // analyser may only measure a bounded window, but it must choose one rather than refuse the whole
        // capture - refusing it was reported to the operator as a format mismatch, which it never was.
        val capture = buffer(voiced(220.0, millis = 12_000))
        val verdict = ControlledToneFormat.verifyVoiceBurst(capture, measuredMillis = 12_000)
        assertTrue(verdict.toString(), verdict.monoPcm16Le)
        val measurement = verdict.measurement!!
        assertEquals("declaredBytes", 384_000L, measurement.declaredBytes)
        assertTrue("the analysed window must stay bounded", measurement.analysedWindowDurationMillis <= 3_000.0)
        assertTrue("the analysed window must lie inside the capture: ${verdict.measurement}",
            measurement.analysedWindowOffsetBytes in 0..(384_000 - 96_000))
    }

    @Test fun aVowelSustainedLateInTheCaptureIsStillFound() {
        // The speaker has up to 12 s to start; a vowel that only arrives in the last third must still be
        // the window the verdict is computed from.
        val silent = ByteArray(8 * 32_000)
        val spoken = voiced(220.0, millis = 4_000)
        val verdict = ControlledToneFormat.verifyVoiceBurst(buffer(silent + spoken), measuredMillis = 12_000)
        assertTrue(verdict.toString(), verdict.monoPcm16Le)
        assertTrue("the analysed window must sit where the speech is: ${verdict.measurement}",
            verdict.measurement!!.analysedWindowOffsetBytes >= 8 * 32_000)
    }

    @Test fun aSilentTwelveSecondCaptureIsStillRejectedAsASpectrumMismatch() {
        // Now that a long capture is actually measurable, SPECTRUM_MISMATCH means what its name says:
        // no signed waveform in any window - not "the window was too long to look at".
        val verdict = ControlledToneFormat.verifyVoiceBurst(buffer(ByteArray(384_000)), measuredMillis = 12_000)
        assertFalse(verdict.monoPcm16Le)
        assertEquals(ControlledToneFormat.SPECTRUM_MISMATCH, verdict.mismatch)
    }

    @Test fun theByteRateCheckStillCoversTheWholeCaptureNotOnlyTheAnalysedWindow() {
        // Bounding the waveform window must not weaken the duration claim: the byte count is compared with
        // the wall clock the audio arrived in, for the whole capture.
        val capture = buffer(voiced(220.0, millis = 12_000))
        val verdict = ControlledToneFormat.verifyVoiceBurst(capture, measuredMillis = 8_000)
        assertFalse(verdict.monoPcm16Le)
        assertEquals(ControlledToneFormat.BYTE_RATE_MISMATCH, verdict.mismatch)
    }

    @Test fun theAnalysisBoundItselfIsStillEnforcedOnOneWindow() {
        // The bound moved from "the capture must be short" to "one measured window must be short"; the
        // analyser still refuses an over-long single window, so the cost of a verdict stays bounded.
        assertNull(PcmFormatEvidence.measureWindow(ByteArray(96_002), bitsPerSample = 16))
    }

    @Test fun declaredByteRateIsHeldToTheWallClockNotToItself() {
        val declared = MonoPcmFormat.bytesPerSecond()
        assertTrue(ControlledToneFormat.byteRateReport(declared * 3, 3000, sampleAligned = true).matches)
        val truncated = ControlledToneFormat.byteRateReport(declared / 100, 1000, sampleAligned = true)
        assertFalse(truncated.matches)
        assertEquals(ControlledToneFormat.BYTE_RATE_MISMATCH, truncated.mismatch)
        val misaligned = ControlledToneFormat.byteRateReport(declared, 1000, sampleAligned = false)
        assertFalse(misaligned.matches)
        assertEquals(ControlledToneFormat.UNALIGNED_SAMPLES, misaligned.mismatch)
        assertFalse("a zero-length window cannot be compared", ControlledToneFormat.byteRateReport(0, 0, sampleAligned = true).matches)
    }

    @Test fun aStreamAtTwiceTheDeclaredRateCannotPassAsSixteenKilohertzMono() {
        val captured = buffer(voiced(220.0, millis = 3000))
        val verdict = ControlledToneFormat.verifyVoiceBurst(captured, measuredMillis = 1000)
        assertFalse(verdict.monoPcm16Le)
        assertEquals(ControlledToneFormat.BYTE_RATE_MISMATCH, verdict.mismatch)
    }

    @Test fun emptyCaptureIsReportedAsNoAudioRatherThanAsAMismatch() {
        val verdict = ControlledToneFormat.verifyVoiceBurst(ControlledToneCapture(), measuredMillis = 1000)
        assertEquals(ControlledToneFormat.NO_AUDIO, verdict.mismatch)
    }

    @Test fun silentCaptureIsRejectedEvenWhenTheByteRateLooksRight() {
        val verdict = ControlledToneFormat.verifyVoiceBurst(buffer(ByteArray(48000)), measuredMillis = 1500)
        assertFalse(verdict.monoPcm16Le)
        assertEquals(ControlledToneFormat.SPECTRUM_MISMATCH, verdict.mismatch)
    }

    @Test fun aStreamTooQuietToBeSpeechIsRejectedOnLevel() {
        val quiet = ByteBuffer.allocate(48000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(24000) { index -> quiet.putShort((if (index % 64 < 32) 60 else -60).toShort()) }
        val verdict = ControlledToneFormat.verifyVoiceBurst(buffer(quiet.array()), measuredMillis = 1500)
        assertFalse(verdict.monoPcm16Le)
        assertEquals(ControlledToneFormat.LEVEL_MISMATCH, verdict.mismatch)
    }

    @Test fun anAliasedPathologicalStreamIsRejectedOnFrameActivity() {
        // Alternating full-scale samples: no real 16 kHz mono speech path produces this.
        val aliased = ByteBuffer.allocate(48000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(24000) { index -> aliased.putShort((if (index % 2 == 0) 12000 else -12000).toShort()) }
        val verdict = ControlledToneFormat.verifyVoiceBurst(buffer(aliased.array()), measuredMillis = 1500)
        assertFalse(verdict.monoPcm16Le)
        assertEquals(ControlledToneFormat.ACTIVITY_MISMATCH, verdict.mismatch)
    }

    @Test fun theInterpretationCheckIsNotOnlyAPureToneCheck() {
        // A sustained voiced sound with a real direct-current offset: the strict single-tone path rejects
        // it (its energy sits in harmonics, not at one probe frequency), while the burst interpretation
        // check must accept the same bytes as the declared signed PCM16 stream.
        val biased = voiced(220.0, millis = 1500, offset = 1200)
        assertNull("a voiced harmonic spectrum is not a single tone",
            PcmFormatEvidence.validateControlledTone(biased, 1500, 220))
        val verdict = ControlledToneFormat.verifyVoiceBurst(buffer(biased), measuredMillis = 1500)
        assertTrue(verdict.toString(), verdict.monoPcm16Le)
        assertNull(verdict.mismatch)
    }

    @Test fun aLopsidedSingleToneIsRejectedByBothInterpretationsSoItCanNeverBePromoted() {
        // A single 250 Hz tone with a 29 % direct-current offset: the signed PCM16 reading rejects the
        // lopsided level, and the unsigned 8-bit reading of the same bytes (values 57..185, straddling
        // the unsigned midpoint) has almost no energy at the probe frequency. Both readings reject it,
        // which is the conservative outcome: an ambiguous capture must never be promoted to verified.
        val lopsided = pulse(fundamentalHz = 250.0, millis = 1000, high = 10000, low = -10000, duty = 0.5, offset = 3000)
        assertNull("the signed PCM16 reading must reject this much offset",
            PcmFormatEvidence.validateControlledTone(lopsided, 1000, 250))
        assertNull("the unsigned 8-bit reading of the same bytes must not validate either",
            PcmFormatEvidence.validateControlledToneAsUnsigned8(lopsided, 1000, 250))
    }

    @Test fun readingAProperSignedSixteenBitCaptureAsUnsignedEightBitIsRejected() {
        // The strict tone path needs an actual single tone: a square wave carries its energy in odd
        // harmonics, not at its own fundamental, so it would be rejected for the wrong reason.
        val sine = sine(500.0, millis = 1000, amplitude = 12000)
        assertNotNull("a clean single sine must validate as the declared tone",
            PcmFormatEvidence.validateControlledTone(sine, 1000, 500))
        assertNull("the same bytes cannot also be the unsigned 8-bit reading of that tone",
            PcmFormatEvidence.validateControlledToneAsUnsigned8(sine, 1000, 500))
    }

    private fun buffer(pcm: ByteArray): ControlledToneCapture =
        ControlledToneCapture().also { it.append(pcm) }

    private fun sine(frequencyHz: Double, millis: Int, amplitude: Int): ByteArray {
        val samples = 16 * millis
        val buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { index ->
            buffer.putShort((amplitude * kotlin.math.sin(2 * Math.PI * frequencyHz * index / 16000)).toInt().toShort())
        }
        return buffer.array()
    }

    /** Voiced but deliberately harmonic-rich: stands in for a human sustained vowel. */
    private fun voiced(fundamentalHz: Double, millis: Int, offset: Int = 0): ByteArray =
        pulse(fundamentalHz, millis, high = 11000, low = -9000, duty = 0.3, offset = offset)

    /** Two-level periodic wave. A duty cycle away from 0.5 gives the harmonics a voiced character. */
    private fun pulse(fundamentalHz: Double, millis: Int, high: Int, low: Int, duty: Double, offset: Int = 0): ByteArray {
        val samples = 16 * millis
        val buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { index ->
            val phase = (index * fundamentalHz / 16000) % 1.0
            buffer.putShort(((if (phase < duty) high else low) + offset).toShort())
        }
        return buffer.array()
    }
}
