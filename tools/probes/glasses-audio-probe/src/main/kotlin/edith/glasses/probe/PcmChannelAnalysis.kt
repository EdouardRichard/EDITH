package edith.glasses.probe

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Per-channel level analysis for one captured PCM block.
 *
 * Kept free of Android types so the threshold logic can be unit tested on the JVM: the probe's whole purpose is
 * to distinguish "the glasses microphone produced audio" from "we captured digital silence", and a bug in that
 * judgement would produce a confident wrong answer about the format.
 *
 * Samples are 16-bit little-endian, interleaved across `channelCount` channels.
 */
class PcmChannelAnalysis private constructor(
    val channelCount: Int,
    val frameCount: Long,
    val rmsPermille: IntArray,
    val peakPermille: IntArray,
    /**
     * Highest energy of any 500 ms window, per channel. A whole-capture average cannot tell "the wearer never
     * spoke" from "the microphone is deaf", because a few hundred milliseconds of speech barely move a 6 s mean.
     * The windowed maximum can.
     */
    val windowPeakPermille: IntArray,
) {
    /** Digital silence is the failure we must never mistake for a measurement. */
    val silentChannels: List<Int> get() = rmsPermille.indices.filter { rmsPermille[it] == 0 }

    /**
     * Whether *any* channel carried signal. One live channel is enough to prove the microphone worked, but the
     * per-channel array still shows which one, because the documented layout puts processed audio on 0/1 and raw
     * microphones on 2-5.
     */
    val hasSignal: Boolean get() = rmsPermille.any { it > 0 }

    /** Channels at or above [SPEECH_RMS_PERMILLE] are loud enough to be plausible speech rather than noise. */
    fun speechLikeChannels(): List<Int> = rmsPermille.indices.filter { rmsPermille[it] >= SPEECH_RMS_PERMILLE }

    /**
     * Channels whose *best* 500 ms window reached the speech threshold. This is the honest test of "did the
     * microphone hear a person", and it is deliberately separate from [speechLikeChannels] so a short utterance in
     * a long capture is still recognised instead of being averaged away.
     */
    fun windowSpeechChannels(): List<Int> =
        windowPeakPermille.indices.filter { windowPeakPermille[it] >= SPEECH_RMS_PERMILLE }

    fun rmsText(): List<String> = rmsPermille.map { permilleText(it) }
    fun peakText(): List<String> = peakPermille.map { permilleText(it) }
    fun windowPeakText(): List<String> = windowPeakPermille.map { permilleText(it) }

    companion object {
        /** 16-bit full scale. */
        private const val PEAK = 32768.0

        /**
         * ~3% of full scale. Chosen well above the noise floor of a quiet room but far below normal speech, so it
         * separates "microphone works" from "microphone returns silence" without claiming a calibrated level.
         */
        const val SPEECH_RMS_PERMILLE = 30

        /** 500 ms at 16 kHz: long enough to contain a syllable, short enough to isolate an utterance. */
        const val WINDOW_FRAMES = 8_000

        val EMPTY = PcmChannelAnalysis(0, 0, IntArray(0), IntArray(0), IntArray(0))

        /**
         * [pcm] is interleaved 16-bit little-endian. [channelCount] must be positive; a non-multiple length has its
         * trailing partial frame ignored rather than distorted into the statistics.
         */
        fun of(pcm: ByteArray, channelCount: Int): PcmChannelAnalysis {
            require(channelCount > 0) { "channelCount must be positive" }
            val frames = (pcm.size / 2) / channelCount
            if (frames == 0) {
                return PcmChannelAnalysis(channelCount, 0, IntArray(channelCount), IntArray(channelCount),
                    IntArray(channelCount))
            }
            val sumSquares = DoubleArray(channelCount)
            val peaks = IntArray(channelCount)
            val windowPeaks = IntArray(channelCount)
            val windowSquares = DoubleArray(channelCount)
            var windowFrames = 0
            for (frame in 0 until frames) {
                for (channel in 0 until channelCount) {
                    val index = (frame * channelCount + channel) * 2
                    // Little-endian: low byte first, as the platform microphone delivers it.
                    val low = pcm[index].toInt() and 0xFF
                    val high = pcm[index + 1].toInt()
                    val sample = (high shl 8) or low
                    val squared = sample.toDouble() * sample
                    sumSquares[channel] += squared
                    windowSquares[channel] += squared
                    val magnitude = abs(sample)
                    if (magnitude > peaks[channel]) peaks[channel] = magnitude
                }
                windowFrames++
                if (windowFrames == WINDOW_FRAMES) {
                    for (channel in 0 until channelCount) {
                        val level = (sqrt(windowSquares[channel] / windowFrames) / PEAK * 1000).toInt()
                        if (level > windowPeaks[channel]) windowPeaks[channel] = level
                        windowSquares[channel] = 0.0
                    }
                    windowFrames = 0
                }
            }
            // A trailing partial window still counts: a short capture must not lose its only utterance.
            if (windowFrames > 0) {
                for (channel in 0 until channelCount) {
                    val level = (sqrt(windowSquares[channel] / windowFrames) / PEAK * 1000).toInt()
                    if (level > windowPeaks[channel]) windowPeaks[channel] = level
                }
            }
            val rms = IntArray(channelCount) { channel ->
                val value = sqrt(sumSquares[channel] / frames) / PEAK * 1000
                value.toInt().coerceIn(0, 1000)
            }
            val peak = IntArray(channelCount) { channel -> (peaks[channel] / PEAK * 1000).toInt().coerceIn(0, 1000) }
            return PcmChannelAnalysis(channelCount, frames.toLong(), rms, peak, windowPeaks)
        }

        /** Integer permille keeps the report JSON byte-stable across runs, unlike floating point. */
        private fun permilleText(value: Int) = "${value / 10}.${value % 10}"
    }
}
