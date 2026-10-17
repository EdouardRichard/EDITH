package com.sheen.rokid.audio

/** The microphone as the uplink sees it: raw interleaved 16-bit PCM, or a refusal to start. */
interface UplinkCapture {
    /** True once the device is actually recording; false means no audio will follow. */
    fun start(): Boolean

    /**
     * Reads up to [buffer]'s capacity of interleaved 16-bit PCM. Returns the number of bytes read, 0 when nothing
     * was available in time, or a negative value on error. Never blocks indefinitely.
     */
    fun read(buffer: ByteArray): Int

    fun stop()
}

/**
 * Drives one bounded capture-and-send session: it reads from [capture], de-interleaves to the uplink's channels,
 * frames the audio and hands it to the transport, then stops.
 *
 * It exists as a separate, transport-free unit because this loop is where a real-time audio path usually fails
 * quietly: a session that captures but never sends, or sends but never stops, still looks "running". Every exit path
 * therefore stops the capture and reports why the session ended.
 */
class AudioUplinkSession(
    private val capture: UplinkCapture,
    private val sender: BoundedAudioUplinkSender,
    private val sessionTag: String,
    private val readBufferBytes: Int = 4_096,
) {
    data class Outcome(
        val started: Boolean,
        val blocksRead: Long,
        val bytesRead: Long,
        val framesSent: Long,
        val framesDropped: Long,
        val endReason: UplinkEndReason,
    )

    /**
     * Captures for at most [durationMillis], or until [shouldContinue] returns false, then stops and sends the end
     * frame. Returns what happened so the caller can report it rather than infer it.
     */
    fun run(durationMillis: Long, shouldContinue: () -> Boolean = { true }): Outcome {
        require(durationMillis > 0) { "DURATION_MUST_BE_POSITIVE" }
        val started = capture.start()
        if (!started) {
            // Nothing was captured, so no stream was ever announced; sending an END would be meaningless.
            return Outcome(false, 0, 0, 0, 0, UplinkEndReason.Error)
        }
        // The START frame is sent only after the microphone is live, so the receiver never opens a stream that has
        // no audio behind it.
        if (!sender.start()) {
            capture.stop()
            return Outcome(false, 0, 0, 0, 0, UplinkEndReason.Error)
        }
        val buffer = ByteArray(readBufferBytes)
        var blocks = 0L
        var bytes = 0L
        val deadline = System.currentTimeMillis() + durationMillis
        var reason = UplinkEndReason.SourceEnded
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!shouldContinue()) {
                    reason = UplinkEndReason.UserStopped
                    break
                }
                val read = capture.read(buffer)
                if (read < 0) {
                    reason = UplinkEndReason.Error
                    break
                }
                if (read == 0) {
                    // A read timeout is normal during silence; yielding keeps the loop from spinning the CPU.
                    Thread.sleep(READ_IDLE_MILLIS)
                    continue
                }
                blocks++
                bytes += read
                sender.offer(if (read == buffer.size) buffer else buffer.copyOf(read))
            }
        } catch (error: Throwable) {
            reason = UplinkEndReason.Error
        } finally {
            // Stopping the capture on every path is what keeps the microphone from being held after a failure.
            runCatching { capture.stop() }
        }
        // The END frame is sent even after an error, so the phone learns the stream stopped rather than merely
        // observing that frames ceased.
        runCatching { sender.finish(reason) }
        val stats = sender.stats
        return Outcome(true, blocks, bytes, stats.framesSent, stats.framesDropped, reason)
    }

    private companion object {
        const val READ_IDLE_MILLIS = 5L
    }
}
