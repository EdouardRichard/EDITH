package com.sheen.rokid.phone.audio
import com.sheen.rokid.contracts.VerifiedPcm

/**
 * A captured audio segment bound to a single turn, held entirely in RAM.
 *
 * @param turnId the turn this segment belongs to
 * @param bytes the raw PCM bytes (empty when [truncated])
 * @param format the verified PCM format (16 kHz / mono / 16-bit on this platform)
 * @param durationMillis estimated duration from byte count and format
 * @param truncated true when the segment exceeded [AudioSegmentCapture.maxBytes] or [AudioSegmentCapture.maxMillis];
 *   the bytes are discarded rather than sent half-cut to the model (spec §A2: "绝不截半句")
 */
data class CapturedSegment(
    val turnId: String,
    val bytes: ByteArray,
    val format: VerifiedPcm?,
    val durationMillis: Int,
    val truncated: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedSegment) return false
        return turnId == other.turnId && bytes.contentEquals(other.bytes) &&
            format == other.format && durationMillis == other.durationMillis && truncated == other.truncated
    }
    override fun hashCode(): Int {
        var result = turnId.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (format?.hashCode() ?: 0)
        result = 31 * result + durationMillis
        result = 31 * result + truncated.hashCode()
        return result
    }
}

/**
 * Bounded per-turn audio segment capture (spec §A1/A2).
 *
 * Collects PCM frames while VAD reports speech, seals the most recent segment when silence follows, and discards it
 * when it exceeds [maxBytes] or [maxMillis]. Only the **latest** segment is kept; earlier ones are overwritten.
 *
 * Thread safety: all public methods are synchronized because [offer] runs on the ingress tick thread while [seal]
 * and [release] run on the host/plugin thread.
 */
class AudioSegmentCapture(
    private val maxMillis: Int = 30_000,
    private val maxBytes: Int = 2_000_000,
) {
    private val lock = Any()
    private var buffer = mutableListOf<ByteArray>()
    private var totalBytes = 0
    private var inSpeech = false
    private var oversized = false
    private var released = false
    // PCM16 / 16kHz / mono = 32000 bytes/sec
    private val byteRate = 32_000

    /**
     * Feed one frame. Frames marked as speech accumulate; a transition from speech to silence seals the current
     * segment internally so the next [seal] can return it.
     */
    fun offer(frame: ByteArray, isSpeech: Boolean) {
        synchronized(lock) {
            if (released) return
            if (isSpeech) {
                if (!inSpeech && buffer.isNotEmpty()) {
                    // New speech segment starting after a previous one was sealed internally but not yet consumed.
                    // Discard the old segment; only the latest matters (spec §A1: "只保留最近一个已定稿段落").
                    buffer.clear()
                    totalBytes = 0
                    oversized = false
                }
                inSpeech = true
                buffer += frame.copyOf()
                totalBytes += frame.size
                if (totalBytes > maxBytes || (totalBytes.toLong() * 1000 / byteRate) > maxMillis) {
                    oversized = true
                }
            } else {
                if (inSpeech) {
                    // Transition speech→silence: the segment is complete. Keep it for the next seal().
                    inSpeech = false
                }
                // Leading/trailing silence: ignore.
            }
        }
    }

    /**
     * Returns the most recently completed segment, or null if no speech was captured or [release] was called.
     * Resets internal state so the next call returns null until new speech arrives.
     */
    fun seal(turnId: String): CapturedSegment? {
        synchronized(lock) {
            if (released || buffer.isEmpty()) return null
            val bytes: ByteArray
            val truncated: Boolean
            if (oversized) {
                bytes = ByteArray(0)
                truncated = true
            } else {
                bytes = ByteArray(totalBytes)
                var offset = 0
                for (chunk in buffer) {
                    System.arraycopy(chunk, 0, bytes, offset, chunk.size)
                    offset += chunk.size
                }
                truncated = false
            }
            val durationMs = if (oversized) 0 else (totalBytes.toLong() * 1000 / byteRate).toInt()
            // Reset for the next segment
            buffer.clear()
            totalBytes = 0
            oversized = false
            inSpeech = false
            return CapturedSegment(turnId, bytes, null, durationMs, truncated)
        }
    }

    /**
     * Releases the segment for [turnId], making subsequent [seal] calls return null.
     */
    fun release(turnId: String) {
        synchronized(lock) {
            released = true
            buffer.clear()
            totalBytes = 0
        }
    }
}
