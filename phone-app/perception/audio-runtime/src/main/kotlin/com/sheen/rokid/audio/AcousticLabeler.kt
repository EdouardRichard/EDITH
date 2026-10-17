package com.sheen.rokid.audio

import com.sheen.rokid.contracts.AcousticLabel
import kotlin.math.sqrt

/**
 * Derives the coarse [AcousticLabel] of the ingested audio from the RMS of each PCM frame plus the VAD's per-frame
 * speech decision. It reports **transitions only**: [accept] returns the new label once a condition has been held
 * long enough, and null on every other frame (including the frames that keep holding the current label).
 *
 * Rules (thresholds are the constructor defaults; all of them are uncalibrated):
 * - **Speech** — the VAD says `speech`, or (when the VAD does not) the frame RMS is at least `speechFloor`, held for
 *   `speechHoldNanos`. A frame in the noise band that is *not* steady also falls back to this rule.
 * - **Silence** — RMS below `silenceFloor`, held for `silenceHoldNanos`.
 * - **Noisy** — RMS at least `noiseFloor` with the variance of the frame RMS below `noiseVarianceFloor`
 *   (a steady loud signal), held for `noiseHoldNanos`.
 *
 * The VAD's `speech` flag has priority over the RMS bands, so a steady loud signal the VAD does not consider speech
 * is reported as [AcousticLabel.Noisy] rather than [AcousticLabel.Speech].
 *
 * **`Noisy` is a coarse guess and its thresholds are not calibrated against real rooms.** It cannot tell steady
 * noise from another person talking steadily; both land in the same band. P3.2 only uses it to refuse a proactive
 * turn, so the cost of the confusion is a missed hint, not a wrong one. Calibration is out of P3.2's scope.
 */
class AcousticLabeler(
    private val speechHoldNanos: Long = 200_000_000L,
    private val silenceHoldNanos: Long = 800_000_000L,
    private val noiseHoldNanos: Long = 2_000_000_000L,
    private val silenceFloor: Float = 0.01f,
    private val speechFloor: Float = 0.03f,
    private val noiseFloor: Float = 0.08f,
    private val noiseVarianceFloor: Float = 0.0005f,
) {
    private var label: AcousticLabel? = null
    private var speechSince: Long? = null
    private var silenceSince: Long? = null
    private var noiseSince: Long? = null
    private var noiseFrames = 0L
    private var noiseMean = 0.0
    private var noiseM2 = 0.0

    fun accept(frame: PcmFrame, speech: Boolean): AcousticLabel? {
        val rms = rms(frame.samples)
        val start = frame.startNanos
        val now = frame.endNanos
        val condition = condition(rms, speech)
        speechSince = if (condition == AcousticLabel.Speech) speechSince ?: start else null
        silenceSince = if (condition == AcousticLabel.Silence) silenceSince ?: start else null
        noiseSince = if (condition == AcousticLabel.Noisy) noiseSince ?: start else null
        val settled = when {
            speechSince?.let { now - it >= speechHoldNanos } == true -> AcousticLabel.Speech
            silenceSince?.let { now - it >= silenceHoldNanos } == true -> AcousticLabel.Silence
            noiseSince?.let { now - it >= noiseHoldNanos } == true -> AcousticLabel.Noisy
            else -> null
        }
        if (settled == null || settled == label) return null
        label = settled
        return settled
    }

    /**
     * Forgets the current label and every hold window.
     *
     * The pipeline calls this whenever it drops its accumulated state (a gap, a format change, a fault, or a close),
     * because a labeler that kept believing the last label would swallow the first transition after the break: the
     * frame after a gap is compared against a label derived from audio from before it. A fresh hold then has to elapse
     * again, which is what makes this a *reset* rather than a re-arm.
     */
    fun reset() {
        label = null
        speechSince = null
        silenceSince = null
        noiseSince = null
        resetNoise()
    }

    /** The condition this frame supports, or null for the mid band that supports none of the three labels. */    private fun condition(rms: Float, speech: Boolean): AcousticLabel? {
        val noiseBand = !speech && rms >= noiseFloor
        val steady = if (noiseBand) observeNoise(rms) else { resetNoise(); false }
        return when {
            speech -> AcousticLabel.Speech
            steady -> AcousticLabel.Noisy
            noiseBand -> AcousticLabel.Speech
            rms >= speechFloor -> AcousticLabel.Speech
            rms < silenceFloor -> AcousticLabel.Silence
            else -> null
        }
    }

    /** Welford over the frame RMS values inside the current noise window; true while that window looks steady. */
    private fun observeNoise(rms: Float): Boolean {
        noiseFrames++
        val delta = rms - noiseMean
        noiseMean += delta / noiseFrames
        noiseM2 += delta * (rms - noiseMean)
        return noiseFrames < 2 || noiseM2 / noiseFrames < noiseVarianceFloor
    }

    private fun resetNoise() {
        noiseFrames = 0
        noiseMean = 0.0
        noiseM2 = 0.0
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        samples.forEach { sum += it.toDouble() * it }
        return sqrt(sum / samples.size).toFloat()
    }
}
