package com.sheen.rokid.audio

/**
 * Whether an `AudioRecord` configuration was accepted, in terms the caller can act on.
 *
 * The distinction matters because the two failures need different responses: a refused configuration is a
 * programming or device-capability problem, while an accepted configuration that then yields silence is the
 * documented "audio no data" case the vendor warns about. Collapsing them would hide which one happened.
 */
enum class CaptureStart { Started, ConfigRefused }

/**
 * The `AudioRecord` settings the glasses microphone needs.
 *
 * Kept as a plain value so the geometry is stated once and can be asserted on the JVM: the documented device mask
 * selects eight channels, the uplink carries only the processed pair, and 16 kHz / 16-bit is the format the vendor
 * documents for this microphone path.
 */
data class CaptureConfig(
    /** Documented 8-channel device mask: 0/1 processed audio, 2-5 raw microphones, 6/7 hardware echo. */
    val channelMask: Int = DEVICE_EIGHT_CHANNEL_MASK,
    val sampleRateHz: Int = 16_000,
    val bitsPerSample: Int = 16,
    val sourceChannelCount: Int = 8,
    /** 40 ms of audio: enough per read that the sender rarely waits, small enough to stay responsive. */
    val readMillis: Int = 40,
) {
    init {
        require(sampleRateHz > 0) { "SAMPLE_RATE_MUST_BE_POSITIVE" }
        require(bitsPerSample in setOf(8, 16, 24, 32)) { "UNSUPPORTED_BIT_DEPTH" }
        require(sourceChannelCount > 0) { "SOURCE_CHANNELS_MUST_BE_POSITIVE" }
        require(readMillis in 1..1_000) { "READ_MILLIS_OUT_OF_RANGE" }
    }

    /** Bytes one read returns when the device delivers the full geometry. */
    fun readBufferBytes(): Int = Math.multiplyExact(sampleRateHz / 1000, readMillis) *
        sourceChannelCount * (bitsPerSample / 8)

    companion object {
        const val DEVICE_EIGHT_CHANNEL_MASK = 0x6000FC
    }
}
