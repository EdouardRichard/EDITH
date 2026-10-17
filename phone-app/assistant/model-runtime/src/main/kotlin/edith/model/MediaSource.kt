package edith.model

/**
 * Resolved audio bytes ready for encoding into a model request.
 *
 * @param bytes the raw PCM data (never cached across turns)
 * @param format a short tag like "wav" or "pcm16-16k-mono" telling the adapter how to encode
 * @param durationMillis estimated playback duration
 */
data class ResolvedAudio(val bytes: ByteArray, val format: String, val durationMillis: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedAudio) return false
        return bytes.contentEquals(other.bytes) && format == other.format && durationMillis == other.durationMillis
    }
    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + format.hashCode()) + durationMillis
}

/**
 * Host-owned port that resolves an opaque audio reference (from [edith.plugin.AudioSegmentHandle.ref]) into actual
 * bytes at encode time. Unknown or expired refs return null so the adapter can refuse the request rather than send
 * an empty audio payload.
 */
interface MediaSource {
    fun resolveAudio(ref: String): ResolvedAudio?
}
