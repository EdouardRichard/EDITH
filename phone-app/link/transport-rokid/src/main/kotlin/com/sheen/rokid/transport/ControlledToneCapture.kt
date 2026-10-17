package com.sheen.rokid.transport

import kotlin.math.sqrt

/**
 * T17 controlled-format bring-up seam: a bounded, RAM-only sink for raw CXR PCM.
 *
 * D33 allows a preannounced non-sensitive fixed tone in phone RAM only. This class therefore never
 * touches disk, never returns the retained buffer itself, and zeroes the buffer in [close], which the
 * caller must run on success, failure and cancellation paths alike.
 */
class ControlledToneCapture(private val maxDurationMillis: Int = MAX_DURATION_MILLIS) : AutoCloseable {
    init { require(maxDurationMillis >= 0) }

    private val buffer = ByteArray((maxDurationMillis.toLong() * MonoPcmFormat.bytesPerSecond() / 1000).toInt())
    private var filled = 0

    var closed: Boolean = false
        private set
    var overflowed: Boolean = false
        private set

    var captured: Boolean = false
        private set

    fun append(data: ByteArray) {
        checkOpen()
        if (data.isEmpty()) return
        val room = buffer.size - filled
        val take = minOf(room, data.size)
        if (take > 0) {
            data.copyInto(buffer, filled, 0, take)
            filled += take
            captured = true
        }
        if (take < data.size) overflowed = true
    }

    fun bytes(): ByteArray {
        checkOpen()
        return buffer.copyOf(filled)
    }

    fun capturedMillis(): Double = filled.toDouble() * 1000 / MonoPcmFormat.bytesPerSecond()

    override fun close() {
        if (closed) return
        buffer.fill(0)
        filled = 0
        closed = true
    }

    private fun checkOpen() = check(!closed) { "CAPTURE_CLOSED" }

    companion object { const val MAX_DURATION_MILLIS = 12_000 }
}

/** The D35 developer declaration this project verifies; it is a candidate, never a verified pairing. */
object MonoPcmFormat {
    const val SAMPLE_RATE_HZ = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val sampleRateHz = SAMPLE_RATE_HZ
    const val channels = CHANNELS
    const val bitsPerSample = BITS_PER_SAMPLE
    /** A sustained voiced sound is well above this; room noise and a dead capture are below it. */
    const val MIN_SPEECH_RMS = 300.0
    /** Peak-to-peak toggles per sample; real voice sustains sit well inside this band. */
    const val MIN_FRAME_ACTIVITY = 0.0005
    const val MAX_FRAME_ACTIVITY = 0.5
    fun bytesPerSecond(): Long = SAMPLE_RATE_HZ.toLong() * CHANNELS * BYTES_PER_SAMPLE
}

/**
 * Outcome of one controlled measurement. [verifiedMonoPcm16Le] is true only when duration, spectrum and
 * the mono channel interpretation all agree; otherwise it records the first dimension that disagreed.
 * A true verdict is evidence, not a promotion — only the controller may write a [VerifiedPcm] pairing.
 */
data class ControlledToneVerdict(
    val verifiedMonoPcm16Le: Boolean,
    val measurement: ToneMeasurement?,
    val mismatch: String?,
) {
    init { require(verifiedMonoPcm16Le == (measurement != null && mismatch == null)) }
}

object ControlledToneFormat {
    const val NO_AUDIO = "NO_AUDIO"
    const val DURATION_MISMATCH = "DURATION_MISMATCH"
    const val SPECTRUM_MISMATCH = "SPECTRUM_MISMATCH"
    const val BYTE_RATE_MISMATCH = "BYTE_RATE_MISMATCH"
    const val UNALIGNED_SAMPLES = "UNALIGNED_SAMPLES"
    const val LEVEL_MISMATCH = "LEVEL_MISMATCH"
    const val ACTIVITY_MISMATCH = "ACTIVITY_MISMATCH"

    /** Validates the [capture] against the declared mono 16 kHz signed-LE PCM16 interpretation. */
    fun verify(capture: ControlledToneCapture, expectedMillis: Int, toneHz: Int): ControlledToneVerdict {
        if (!capture.captured) return ControlledToneVerdict(false, null, NO_AUDIO)
        val pcm = capture.bytes()
        if (pcm.isEmpty()) return ControlledToneVerdict(false, null, NO_AUDIO)
        val measurement = PcmFormatEvidence.validateControlledTone(pcm, expectedMillis, toneHz)
            ?: return ControlledToneVerdict(false, null, mismatch(pcm.size, expectedMillis))
        return ControlledToneVerdict(true, measurement, null)
    }

    /**
     * The device bring-up cannot depend on a calibrated tone generator, so this proves exactly what a
     * human sustained vowel still proves and claims nothing more:
     *
     *  - the captured byte count matches the declared 16 kHz mono PCM16LE byte rate for the wall clock
     *    the audio actually arrived in, which a really stereo, 8-bit or 48 kHz stream cannot satisfy;
     *  - the samples align to one signed PCM16 stream with plausible speech level and frame activity.
     *
     * Channel count is not separately provable from an interleaved mono-like stimulus, so this does not
     * claim it: the byte rate is the channel and bit-depth evidence. Pitch is deliberately not asserted,
     * because zero-crossing pitch estimation is unusable for low male fundamentals. Device callers must
     * pass the wall clock the audio actually arrived in; the default derives it from the declared byte
     * rate, which makes the byte-rate comparison tautological and is only useful for fixture tests.
     */
    fun verifyVoiceBurst(capture: ControlledToneCapture, measuredMillis: Long = capture.capturedMillis().toLong()): VoiceBurstVerdict {
        val pcm = if (capture.captured) capture.bytes() else ByteArray(0)
        if (pcm.isEmpty()) return VoiceBurstVerdict(false, null, NO_AUDIO)
        val rate = byteRateReport(pcm.size.toLong(), measuredMillis, pcm.size % MonoPcmFormat.BYTES_PER_SAMPLE == 0)
        if (!rate.matches) return VoiceBurstVerdict(false, null, rate.mismatch)
        // The byte rate is judged over the whole capture; the waveform over one bounded window inside it.
        // The capture may legitimately be longer than one announced vowel (the device harness records up to
        // 12 s), and refusing to look at it was reported as SPECTRUM_MISMATCH - a stimulus verdict the
        // analyser had never actually reached. Both invariants below are established by the gates above.
        val selected = checkNotNull(PcmFormatEvidence.selectAnalysisWindow(pcm, MonoPcmFormat.BITS_PER_SAMPLE)) {
            "no analysis window for a sample-aligned non-empty capture"
        }
        val window = checkNotNull(PcmFormatEvidence.measureWindow(pcm, MonoPcmFormat.BITS_PER_SAMPLE,
            offsetBytes = selected.offsetBytes, lengthBytes = selected.lengthBytes)) {
            "selected window is not measurable"
        }
        if (!window.positive || !window.negative) return VoiceBurstVerdict(false, null, SPECTRUM_MISMATCH)
        val rms = sqrt(window.total / window.samples)
        val activity = window.transitions.toDouble() / window.samples
        if (rms < MonoPcmFormat.MIN_SPEECH_RMS || rms > Short.MAX_VALUE.toDouble()) return VoiceBurstVerdict(false, null, LEVEL_MISMATCH)
        if (activity < MonoPcmFormat.MIN_FRAME_ACTIVITY || activity > MonoPcmFormat.MAX_FRAME_ACTIVITY) {
            return VoiceBurstVerdict(false, null, ACTIVITY_MISMATCH)
        }
        val measurement = VoiceBurstMeasurement(
            measuredMillis = measuredMillis,
            declaredBytes = pcm.size.toLong(),
            declaredBytesPerSecond = MonoPcmFormat.bytesPerSecond().toDouble(),
            measuredBytesPerSecond = pcm.size * 1000.0 / measuredMillis,
            rms = rms,
            transitions = window.transitions,
            crossingFrequencyHz = window.crossings * MonoPcmFormat.SAMPLE_RATE_HZ / window.samples.toDouble(),
            analysedWindowOffsetBytes = selected.offsetBytes,
            analysedWindowDurationMillis = window.durationMillis)
        return VoiceBurstVerdict(true, measurement, null)
    }

    /** Compares the captured byte count with what the declared byte rate predicts for the same window. */
    fun byteRateReport(measuredBytes: Long, measuredMillis: Long, sampleAligned: Boolean): ByteRateReport {
        if (!sampleAligned) return ByteRateReport(false, UNALIGNED_SAMPLES)
        if (measuredMillis <= 0) return ByteRateReport(false, BYTE_RATE_MISMATCH)
        val expected = measuredMillis * MonoPcmFormat.bytesPerSecond() / 1000
        val matches = measuredBytes >= expected * 98 / 100 && measuredBytes <= expected * 102 / 100
        return ByteRateReport(matches, if (matches) null else BYTE_RATE_MISMATCH)
    }

    /**
     * Distinguishes the two ways the mono interpretation can fail. A wrong byte rate is a duration
     * mismatch; the right byte rate with the wrong waveform is the spectrum/channel mismatch, where
     * interleaved stereo read as mono shows up as a doubled crossing rate.
     */
    private fun mismatch(bytes: Int, expectedMillis: Int): String {
        val expectedBytes = expectedMillis.toLong() * MonoPcmFormat.bytesPerSecond() / 1000
        val sampleAligned = bytes % MonoPcmFormat.BYTES_PER_SAMPLE == 0
        val durationMatches = sampleAligned && bytes.toLong() >= (expectedBytes * 98) / 100 &&
            bytes.toLong() <= (expectedBytes * 102) / 100
        return if (durationMatches) SPECTRUM_MISMATCH else DURATION_MISMATCH
    }
}

data class ByteRateReport(val matches: Boolean, val mismatch: String?)

/** Frequency-independent measurement of one declared PCM16LE mono window. */
data class VoiceBurstMeasurement(
    val measuredMillis: Long,
    val declaredBytes: Long,
    val declaredBytesPerSecond: Double,
    val measuredBytesPerSecond: Double,
    val rms: Double,
    val transitions: Int,
    val crossingFrequencyHz: Double,
    /** Where inside the capture the measured window sat, so a reviewer can see which part was judged. */
    val analysedWindowOffsetBytes: Int = 0,
    val analysedWindowDurationMillis: Double = 0.0,
)

/**
 * [monoPcm16Le] is true only when the captured byte count and waveform are physically consistent with
 * the declared mono PCM16LE stream at the wall clock the audio arrived in.
 */
data class VoiceBurstVerdict(
    val monoPcm16Le: Boolean,
    val measurement: VoiceBurstMeasurement?,
    val mismatch: String?,
) {
    init { require(monoPcm16Le == (measurement != null && mismatch == null)) }
}
