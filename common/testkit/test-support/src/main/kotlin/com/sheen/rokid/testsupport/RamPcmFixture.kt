package com.sheen.rokid.testsupport

import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.VerifiedPcm

/**
 * Bounded, RAM-only PCM source for the T16 measurement runner.
 *
 * It never manufactures a [VerifiedPcm]: the caller passes whatever the T17 format resolver accepted.
 * With no verified format (or an unsupported encoding/geometry) [fromVerified] returns null and the
 * runner must not decode or measure the real path. The fixture takes ownership of the sample buffer and
 * zeroes it in [close], which must run on success, failure and cancellation paths.
 */
class RamPcmFixture private constructor(
    private val pcm: ByteArray,
    val format: AudioFormat.Pcm,
    val encoding: PcmEncoding,
    val evidenceId: String,
) : AutoCloseable {
    var closed: Boolean = false
        private set

    val byteCount: Int get() = pcm.size
    val sampleRateHz: Int get() = format.sampleRateHz
    val durationMillis: Double get() = byteCount / bytesPerMillis()

    fun window(startMillis: Int, durationMillis: Int): ByteArray {
        check(!closed) { "FIXTURE_CLOSED" }
        require(startMillis >= 0 && durationMillis > 0) { "INVALID_WINDOW" }
        val bytesPerMillis = bytesPerMillis()
        val first = (startMillis * bytesPerMillis).toInt()
        val length = (durationMillis * bytesPerMillis).toInt()
        require(length > 0 && first + length <= pcm.size) { "WINDOW_OUT_OF_RANGE" }
        return pcm.copyOfRange(first, first + length)
    }

    override fun close() {
        if (closed) return
        pcm.fill(0)
        closed = true
    }

    private fun bytesPerMillis(): Double =
        format.sampleRateHz * format.channels * (format.bitsPerSample / 8) / 1000.0

    companion object {
        const val MAX_SECONDS = 12

        fun fromVerified(format: VerifiedPcm?, samples: ByteArray): RamPcmFixture? {
            val verified = format ?: return null
            if (verified.encoding != PcmEncoding.SIGNED_LE) return null
            val pcm = verified.format
            if (pcm.bitsPerSample != 16) return null
            val bytesPerSample = pcm.bitsPerSample / 8
            val bytesPerSecond = pcm.sampleRateHz * pcm.channels * bytesPerSample
            if (samples.isEmpty() || samples.size > bytesPerSecond * MAX_SECONDS) return null
            if (samples.size % bytesPerSample != 0) return null
            return RamPcmFixture(samples, pcm, verified.encoding, verified.evidenceId)
        }
    }
}
