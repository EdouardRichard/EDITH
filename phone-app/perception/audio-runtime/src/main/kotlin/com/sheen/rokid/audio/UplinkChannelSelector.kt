package com.sheen.rokid.audio

/**
 * De-interleaves a multi-channel capture down to the channels the uplink will carry.
 *
 * The device delivers 8 interleaved channels at 16 kHz, but only channels 0/1 are the processed audio; carrying all
 * eight costs four times the bandwidth for data the pipeline does not need. Selecting here, on the glasses, is the
 * throughput lever - and it is also the step most likely to be silently wrong, because a mis-selection still yields
 * audio of the right length and level. It would simply transcribe the wrong microphone.
 *
 * A trailing partial frame is dropped rather than emitted: emitting it would place one channel's sample where
 * another's belongs for every subsequent frame.
 */
class UplinkChannelSelector(val sourceChannelCount: Int, val selectedChannels: List<Int>) {
    init {
        require(sourceChannelCount > 0) { "SOURCE_CHANNEL_COUNT_MUST_BE_POSITIVE" }
        require(selectedChannels.isNotEmpty()) { "NO_CHANNELS_SELECTED" }
        require(selectedChannels.all { it in 0 until sourceChannelCount }) { "SELECTED_CHANNEL_OUT_OF_RANGE" }
        require(selectedChannels.distinct().size == selectedChannels.size) { "DUPLICATE_SELECTED_CHANNEL" }
    }

    val outputChannelCount: Int get() = selectedChannels.size

    /** Whole source frames available in [capture]; a partial trailing frame is not counted because it is not used. */
    fun framesIn(capture: ByteArray): Int = (capture.size / 2) / sourceChannelCount

    /**
     * Returns the selected channels as interleaved 16-bit little-endian PCM, preserving sample sign and frame order.
     * Offsets are computed rather than streamed so a partial trailing frame is naturally excluded.
     */
    fun select(capture: ByteArray): ByteArray {
        val frames = framesIn(capture)
        if (frames == 0) return ByteArray(0)
        val out = ByteArray(frames * outputChannelCount * 2)
        var target = 0
        for (frame in 0 until frames) {
            val frameBase = frame * sourceChannelCount * 2
            for (channel in selectedChannels) {
                val source = frameBase + channel * 2
                out[target++] = capture[source]
                out[target++] = capture[source + 1]
            }
        }
        return out
    }
}
