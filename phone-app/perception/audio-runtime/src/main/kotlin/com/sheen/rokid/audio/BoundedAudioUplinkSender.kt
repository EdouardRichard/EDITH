package com.sheen.rokid.audio

/** What one offered capture block produced. */
enum class UplinkSend {
    /** A frame was built and handed to the transport. */
    Sent,

    /** Accumulating samples; no frame was due yet. */
    Buffered,

    /** A frame was due but the transport had not finished the previous one, so audio was dropped. */
    DroppedBackpressure,
}

data class UplinkSendStats(
    val framesSent: Long = 0,
    val framesDropped: Long = 0,
    val bytesSent: Long = 0,
    val samplesBuffered: Long = 0,
)

/**
 * Builds uplink frames from captured audio and hands them to a transport.
 *
 * Three decisions live here:
 *
 * - **Frames are sized by duration, not by the capture's callback size.** A capture callback may deliver any number
 *   of samples, but the receiver needs an even cadence to keep its buffer accounting meaningful.
 * - **Back-pressure drops audio instead of blocking the capture thread.** Blocking would stall the microphone and
 *   corrupt the device's own audio path; dropping is visible in [stats] so the loss is reportable rather than
 *   silent. This mirrors the documented warning that PCM must not reuse a video-style "drop the oldest frame"
 *   policy, since that causes audible gaps - the honest alternative is to count what was dropped.
 * - **The transport is a plain function**, so the sender is testable without CXR and the caller decides how the bytes
 *   reach the phone.
 */
class BoundedAudioUplinkSender(
    private val selector: UplinkChannelSelector,
    private val sessionTag: String,
    private val sampleRateHz: Int,
    private val bitsPerSample: Int = 16,
    private val frameMillis: Int = AudioUplinkCodec.DEFAULT_FRAME_MILLIS,
    private val transport: (ByteArray) -> Boolean,
) {
    init {
        require(sessionTag.isNotBlank()) { "SESSION_TAG_REQUIRED" }
        require(sampleRateHz > 0) { "SAMPLE_RATE_MUST_BE_POSITIVE" }
        require(frameMillis > 0) { "FRAME_MILLIS_MUST_BE_POSITIVE" }
    }

    private val frameSamples = Math.multiplyExact(sampleRateHz / 1000, frameMillis) * selector.outputChannelCount
    private val pending = java.io.ByteArrayOutputStream(frameSamples * 2)
    private var sequence = 0L
    private var counters = UplinkSendStats()
    private var started = false

    val stats: UplinkSendStats get() = counters

    /**
     * Sends the `START` frame that declares the geometry. Returns false when the transport refused it, in which case
     * no audio should be offered - the receiver would refuse it all anyway, and sending would only burn bandwidth.
     */
    fun start(): Boolean {
        val accepted = transport(
            AudioUplinkCodec.encodeStart(
                sessionTag = sessionTag,
                sampleRateHz = sampleRateHz,
                channelCount = selector.sourceChannelCount,
                bitsPerSample = bitsPerSample,
                selectedChannels = selector.selectedChannels,
            ),
        )
        started = accepted
        return accepted
    }

    /**
     * Offers one captured block. The block is de-interleaved, buffered to whole frames, and each due frame is handed
     * to the transport. Returns what happened to the last frame this call produced, so a caller can log loss.
     */
    fun offer(capture: ByteArray): UplinkSend {
        if (!started) return UplinkSend.Buffered
        val selected = selector.select(capture)
        if (selected.isNotEmpty()) {
            pending.write(selected)
            counters = counters.copy(samplesBuffered = counters.samplesBuffered + selected.size / 2)
        }
        var outcome = UplinkSend.Buffered
        while (pending.size() >= frameSamples * 2) {
            val all = pending.toByteArray()
            val frame = all.copyOfRange(0, frameSamples * 2)
            pending.reset()
            if (all.size > frame.size) pending.write(all, frame.size, all.size - frame.size)
            outcome = send(frame)
        }
        return outcome
    }

    /** Sends any whole frame still buffered, then the `END` frame. Partial frames are discarded, not padded. */
    fun finish(reason: UplinkEndReason): Boolean {
        if (!started) return false
        val all = pending.toByteArray()
        val wholeFrames = all.size / (frameSamples * 2)
        for (index in 0 until wholeFrames) {
            send(all.copyOfRange(index * frameSamples * 2, (index + 1) * frameSamples * 2))
        }
        pending.reset()
        started = false
        // The end frame is sent even if the last audio frame was dropped: the phone needs to know why the stream
        // stopped, and "no more frames" is indistinguishable from a link failure.
        return transport(AudioUplinkCodec.encodeEnd(sequence, counters.framesSent, reason))
    }

    /**
     * Hands one frame to the transport.
     *
     * The transport is retried on every frame rather than being latched off after a failure. Latching would mean a
     * single transient refusal silences the uplink permanently, which is worse than the gap it was meant to avoid:
     * the phone would see frames simply stop with no way to recover. Audio that could not be sent is counted as
     * dropped, so a link that is genuinely down is still visible in [stats].
     */
    private fun send(frame: ByteArray): UplinkSend {
        val encoded = AudioUplinkCodec.encodePcm(sequence, 0L, frame)
        val accepted = transport(encoded)
        sequence++
        return if (accepted) {
            counters = counters.copy(
                framesSent = counters.framesSent + 1,
                bytesSent = counters.bytesSent + encoded.size,
            )
            UplinkSend.Sent
        } else {
            counters = counters.copy(framesDropped = counters.framesDropped + 1)
            UplinkSend.DroppedBackpressure
        }
    }
}
