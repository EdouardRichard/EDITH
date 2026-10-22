package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*

data class PcmFrame(val epoch: InputEpoch, val samples: FloatArray, val sampleRateHz: Int,
    val range: SampleRange, val startNanos: Long, val endNanos: Long)

class PcmFramer(private val format: VerifiedPcm, private val frameMillis: Int = 20) {
    private val rate = format.format.sampleRateHz
    private val frameSamples: Int
    private var pending = ByteArray(0)
    private var continuity: String? = null
    private var epoch: InputEpoch? = null
    private var anchor = 0L
    private var offset = 0L

    init {
        if (format.format.channels != 1 || format.format.bitsPerSample != 16) {
            throw ContractError.CapabilityMissing("PCM16 mono converter required")
        }
        require(frameMillis in 20..40)
        require(rate.toLong() * frameMillis % 1000 == 0L)
        frameSamples = rate * frameMillis / 1000
    }

    fun accept(chunk: PcmChunk): List<PcmFrame> {
        if (chunk.continuityId != continuity || chunk.epoch != epoch) {
            reset(); continuity = chunk.continuityId; epoch = chunk.epoch; anchor = chunk.receivedNanos
        }
        val bytes = pending + chunk.bytes
        val frameBytes = frameSamples * 2
        val frames = mutableListOf<PcmFrame>()
        var position = 0
        while (bytes.size - position >= frameBytes) {
            val samples = FloatArray(frameSamples) { index ->
                val a = bytes[position + index * 2].toInt() and 255
                val b = bytes[position + index * 2 + 1].toInt() and 255
                val integer = if (format.encoding == PcmEncoding.SIGNED_LE) (b shl 8) or a else (a shl 8) or b
                integer.toShort().toFloat() / 32768f
            }
            val end = Math.addExact(offset, frameSamples.toLong())
            frames += PcmFrame(chunk.epoch, samples, rate, SampleRange(chunk.continuityId, offset, end), time(offset), time(end))
            offset = end; position += frameBytes
        }
        pending = bytes.copyOfRange(position, bytes.size)
        return frames
    }

    private fun time(samples: Long): Long = Math.addExact(anchor,
        Math.addExact(Math.multiplyExact(samples / rate, 1_000_000_000),
            (samples % rate) * 1_000_000_000 / rate))
    fun reset() { pending = ByteArray(0); continuity = null; epoch = null; offset = 0; anchor = 0 }
}

interface PcmConverter { fun convert(frame: PcmFrame, targetSampleRateHz: Int): PcmFrame }
