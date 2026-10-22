package com.sheen.rokid.transport

import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.VerifiedPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object PcmFormatEvidence {
    /**
     * The longest window a single waveform verdict may be computed from: 96,000 bytes = 3 s of 16 kHz mono
     * PCM16, the announced sustained-vowel window. The **capture** may be longer than this (the device
     * harness records up to 12 s so the AI-App authorisation and the speaker's start need not be
     * synchronised); the analyser selects a bounded window inside it rather than refusing the capture.
     */
    internal const val MAX_ANALYSIS_WINDOW_BYTES = 96_000

    /**
     * The format resolved for the pairing the product ships on, on the user's explicit decision of 2026-10-08.
     *
     * **Read the evidence id before citing this as verification.** The basis is the vendor's documented CXR audio
     * contract (16 kHz, mono, 16 bit PCM) together with their own Demo running on this exact hardware, plus our own
     * device measurements on this pairing: **exactly 32,000 B/s with `bytes == 2 x samples`, measured twice**.
     *
     * It is deliberately **not** a controlled-vowel capture. The device attempts were refused as
     * `SPECTRUM_MISMATCH`, but that refusal turned out to be **this analyser's own defect** - it refused to
     * measure any capture longer than [MAX_ANALYSIS_WINDOW_BYTES] and reported the refusal in the same words
     * as a real waveform mismatch (see `evidence/2026-10-08-p2-t17-spectrum-mismatch-root-cause.md`). The user
     * then directed that the official Demo is sufficient support and that no further verification tests should
     * be run, so no controlled capture was taken. The id says `official-spec+user-decision` rather than naming a
     * measurement, so a later reader cannot mistake this for a laboratory result. If a controlled capture is
     * ever obtained, it should replace this entry with one whose id names that measurement.
     *
     * Resolution stays **per pairing** on purpose: an unrecognised firmware or SDK returns null rather than
     * inheriting a decision made for different bytes on the wire.
     */
    private val SHIPPING_EVIDENCE_ID = "official-spec+user-decision"

    private val RESOLVED = mapOf(
        // firmware to sdk coordinate -> the format that pairing delivers
        "1.26.011-20260929-150201" to mapOf(
            "1.1.2" to VerifiedPcm(
                format = AudioFormat.Pcm(sampleRateHz = MonoPcmFormat.SAMPLE_RATE_HZ,
                    channels = MonoPcmFormat.CHANNELS, bitsPerSample = MonoPcmFormat.BITS_PER_SAMPLE),
                encoding = PcmEncoding.SIGNED_LE,
                evidenceId = SHIPPING_EVIDENCE_ID,
            ),
        ),
    )

    /** The resolved format for [firmware] + [sdk], or null when that pairing has no decision recorded. */
    fun resolve(firmware: String, sdk: String): VerifiedPcm? = RESOLVED[firmware]?.get(sdk)
    /** RAM-only check of the D35 candidate; never promotes a pairing to VerifiedPcm. */
    fun validateControlledTone(pcm: ByteArray, expectedMillis: Int, toneHz: Int): ToneMeasurement? {
        if (expectedMillis !in 100..3000 || toneHz !in 100..4000 || pcm.isEmpty() || pcm.size > MAX_ANALYSIS_WINDOW_BYTES) return null
        val window = measureWindow(pcm, 16, probeHz = toneHz.toDouble()) ?: return null
        val samples = window.samples
        if (abs(window.durationMillis - expectedMillis) > expectedMillis * 0.02) return null
        if (!window.positive || !window.negative || window.total == 0.0) return null
        val rms = sqrt(window.total / samples)
        val purity = 2 * (window.real * window.real + window.imaginary * window.imaginary) / (samples * window.total)
        val frequency = window.crossings * 16000.0 / samples
        if (rms < 100 || abs(window.sum / samples) > rms * 0.05 || purity < 0.95 || abs(frequency - toneHz) > toneHz * 0.02) return null
        return ToneMeasurement(window.durationMillis, frequency, purity.coerceAtMost(1.0))
    }
    /**
     * The same physical stream read as unsigned 8-bit PCM. A real signed PCM16 capture never satisfies
     * both readings, so a non-null result here means the bit-depth interpretation is still ambiguous.
     */
    fun validateControlledToneAsUnsigned8(pcm: ByteArray, expectedMillis: Int, toneHz: Int): ToneMeasurement? {
        if (expectedMillis !in 100..3000 || toneHz !in 100..4000 || pcm.isEmpty() || pcm.size > MAX_ANALYSIS_WINDOW_BYTES) return null
        val window = measureWindow(pcm, 8, probeHz = toneHz.toDouble()) ?: return null
        if (abs(window.durationMillis - expectedMillis) > expectedMillis * 0.02) return null
        if (!window.positive || !window.negative || window.total == 0.0) return null
        val rms = sqrt(window.total / window.samples)
        val purity = 2 * (window.real * window.real + window.imaginary * window.imaginary) / (window.samples * window.total)
        val frequency = window.crossings * 16000.0 / window.samples
        if (rms < 100 || purity < 0.95 || abs(frequency - toneHz) > toneHz * 0.02) return null
        return ToneMeasurement(window.durationMillis, frequency, purity.coerceAtMost(1.0))
    }
    /**
     * Selects the bounded window a waveform verdict is computed from, so a capture longer than
     * [MAX_ANALYSIS_WINDOW_BYTES] is measurable at all.
     *
     * The window is a detection decision, not an average-level claim: the caller asserts "this capture
     * contains a signed PCM16 speech waveform", so the strongest window is the right statistic, and its
     * offset is reported back so a reviewer can see which part of the capture was judged. Candidates are
     * 1 s apart, which is dense enough that a vowel sustained anywhere inside the announced window is
     * covered whole; ties keep the earliest window so the verdict is deterministic.
     *
     * Returns null only for input the sample format cannot describe at all (empty, or not a whole number
     * of samples), which the callers gate on separately.
     */
    internal fun selectAnalysisWindow(pcm: ByteArray, bitsPerSample: Int, sampleRateHz: Int = 16_000,
        maxBytes: Int = MAX_ANALYSIS_WINDOW_BYTES): AnalysisWindow? {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4 || pcm.isEmpty() || pcm.size % bytesPerSample != 0) return null
        val windowBytes = (minOf(maxBytes, pcm.size) / bytesPerSample) * bytesPerSample
        if (windowBytes <= 0) return null
        if (pcm.size == windowBytes) return AnalysisWindow(0, windowBytes)
        val hopBytes = maxOf(bytesPerSample, sampleRateHz * bytesPerSample)
        var bestOffset = 0
        var bestEnergy = -1.0
        var offset = 0
        while (offset + windowBytes <= pcm.size) {
            val energy = windowEnergy(pcm, offset, windowBytes, bytesPerSample)
            if (energy > bestEnergy) {
                bestEnergy = energy
                bestOffset = offset
            }
            offset += hopBytes
        }
        // A capture whose length is not a whole number of hops would otherwise never try its own tail.
        val tail = pcm.size - windowBytes
        if (tail > bestOffset && windowEnergy(pcm, tail, windowBytes, bytesPerSample) > bestEnergy) {
            bestOffset = tail
        }
        return AnalysisWindow(bestOffset, windowBytes)
    }

    /** Mean square of one sample-aligned window; cheap enough to scan candidates before measuring one. */
    private fun windowEnergy(pcm: ByteArray, offsetBytes: Int, lengthBytes: Int, bytesPerSample: Int): Double {
        val input = ByteBuffer.wrap(pcm, offsetBytes, lengthBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = lengthBytes / bytesPerSample
        var total = 0.0
        repeat(samples) {
            val value = if (bytesPerSample == 1) input.get().toInt() and 0xFF else input.short.toInt()
            total += value.toDouble() * value
        }
        return total / samples
    }

    /**
     * Frequency-agnostic physical properties of one declared sample format. Only a window up to
     * [MAX_ANALYSIS_WINDOW_BYTES] is measured - a whole 12 s capture is deliberately refused here and
     * bounded by [selectAnalysisWindow] instead, so the cost of one verdict stays bounded.
     */
    internal fun measureWindow(pcm: ByteArray, bitsPerSample: Int, probeHz: Double = 1000.0,
        sampleRateHz: Int = 16000, offsetBytes: Int = 0, lengthBytes: Int = pcm.size - offsetBytes): WindowMeasurement? {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4 || pcm.isEmpty() || pcm.size % bytesPerSample != 0) return null
        if (offsetBytes < 0 || lengthBytes <= 0 || offsetBytes + lengthBytes > pcm.size) return null
        if (lengthBytes % bytesPerSample != 0 || lengthBytes > MAX_ANALYSIS_WINDOW_BYTES) return null
        val samples = lengthBytes / bytesPerSample
        val input = ByteBuffer.wrap(pcm, offsetBytes, lengthBytes).order(ByteOrder.LITTLE_ENDIAN)
        var total = 0.0; var sum = 0.0; var real = 0.0; var imaginary = 0.0
        var positive = false; var negative = false; var crossings = 0; var previous = 0
        var transitions = 0; var previousSign = 0
        repeat(samples) { index ->
            val value = when (bitsPerSample) {
                8 -> input.get().toInt() and 0xFF
                16 -> input.short.toInt()
                else -> return null
            }
            positive = positive || value > 0; negative = negative || value < 0
            if (previous <= 0 && value > 0) crossings++
            previous = value
            val sign = if (value > 0) 1 else if (value < 0) -1 else 0
            if (sign != 0) { if (previousSign != 0 && sign != previousSign) transitions++; previousSign = sign }
            sum += value; total += value.toDouble() * value
            val angle = 2 * PI * probeHz * index / sampleRateHz
            real += value * cos(angle); imaginary += value * sin(angle)
        }
        return WindowMeasurement(samples * 1000.0 / sampleRateHz, samples, sum, total, real, imaginary, crossings,
            transitions, positive, negative)
    }
}

/** One bounded, sample-aligned slice of a capture: where the waveform verdict was computed. */
internal data class AnalysisWindow(val offsetBytes: Int, val lengthBytes: Int)

internal data class WindowMeasurement(val durationMillis: Double, val samples: Int, val sum: Double, val total: Double,
    val real: Double, val imaginary: Double, val crossings: Int, val transitions: Int,
    val positive: Boolean, val negative: Boolean)

data class ToneMeasurement(val durationMillis: Double, val frequencyHz: Double, val spectralPurity: Double)
