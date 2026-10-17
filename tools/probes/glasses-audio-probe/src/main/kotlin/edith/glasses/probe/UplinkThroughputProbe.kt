package edith.glasses.probe

/**
 * Measures whether the CXR bridge's generic message channel can carry real-time audio.
 *
 * This exists because the uplink has no dedicated audio-send API: `CXRServiceBridge` exposes only
 * `sendMessage(name, caps[, bytes])`, so the whole design rests on an assumption nobody has tested - that a generic
 * message channel sustains 16 kHz 16-bit audio. The required rate for the two processed channels is 64,000 bytes per
 * second, which is far above what a request/response command channel usually carries.
 *
 * The measurement is deliberately separated from the sending so the arithmetic is verifiable without a device: it
 * decides, it does not send. Whether the channel accepts the bytes is a separate question that only hardware answers.
 */
object UplinkThroughputProbe {
    /** Bytes per second needed for 16 kHz, 16-bit audio on [channels] channels. */
    fun requiredBytesPerSecond(channels: Int, sampleRateHz: Int = 16_000, bitsPerSample: Int = 16): Int {
        require(channels > 0) { "CHANNELS_MUST_BE_POSITIVE" }
        require(sampleRateHz > 0) { "SAMPLE_RATE_MUST_BE_POSITIVE" }
        return sampleRateHz * channels * (bitsPerSample / 8)
    }

    /**
     * Frames a run must send per second to keep up, and the payload each carries, for a given frame duration.
     * A run that sends fewer than [framesPerSecond] frames, or smaller frames, cannot carry real-time audio even if
     * every frame is accepted.
     */
    fun framesPerSecond(frameMillis: Int): Int {
        require(frameMillis in 1..1_000) { "FRAME_MILLIS_OUT_OF_RANGE" }
        return 1000 / frameMillis
    }

    /** Payload bytes in one frame of [frameMillis] on [channels] channels at the given geometry. */
    fun payloadBytesPerFrame(channels: Int, frameMillis: Int, sampleRateHz: Int = 16_000,
        bitsPerSample: Int = 16): Int {
        require(channels > 0) { "CHANNELS_MUST_BE_POSITIVE" }
        require(frameMillis > 0) { "FRAME_MILLIS_MUST_BE_POSITIVE" }
        return Math.multiplyExact(sampleRateHz / 1000, frameMillis) * channels * (bitsPerSample / 8)
    }

    /**
     * The verdict for one measured run. [acceptedFrames] counts frames the transport acknowledged; [elapsedMillis]
     * is wall time for the whole run. A rate below the requirement means the channel cannot carry this audio
     * configuration, whatever the acknowledgements say.
     */
    data class Verdict(
        val acceptedFrames: Long,
        val attemptedFrames: Long,
        val payloadBytes: Long,
        val elapsedMillis: Long,
        val requiredBytesPerSecond: Int,
    ) {
        val achievedBytesPerSecond: Double
            get() = if (elapsedMillis <= 0) 0.0 else payloadBytes.toDouble() * 1000.0 / elapsedMillis

        /** True only when the measured rate meets the requirement with headroom, not merely on average. */
        val sustainsRealTime: Boolean
            get() = achievedBytesPerSecond >= requiredBytesPerSecond

        /** Fraction of attempted frames the transport refused; a high value means the channel is rejecting, not slow. */
        val refusalRatio: Double
            get() = if (attemptedFrames == 0L) 0.0 else (attemptedFrames - acceptedFrames).toDouble() / attemptedFrames

        /**
         * The single line a device run should report. Kept explicit so a partially working channel cannot be read as
         * a pass: an average rate that meets the target while refusing frames is still a failure.
         */
        fun summary(): String = buildString {
            append("accepted=").append(acceptedFrames).append('/').append(attemptedFrames)
            append(" payloadBytes=").append(payloadBytes)
            append(" elapsedMillis=").append(elapsedMillis)
            append(" achieved=").append((achievedBytesPerSecond / 1000).toInt()).append("KB/s")
            append(" required=").append(requiredBytesPerSecond / 1000).append("KB/s")
            append(" refusalRatio=").append((refusalRatio * 100).toInt()).append('%')
            append(" verdict=").append(
                when {
                    attemptedFrames == 0L -> "NO_FRAMES_ATTEMPTED"
                    refusalRatio > 0.0 -> "TRANSPORT_REFUSED_FRAMES"
                    sustainsRealTime -> "SUSTAINS_REAL_TIME"
                    else -> "TOO_SLOW_FOR_REAL_TIME"
                },
            )
        }
    }
}
