package com.sheen.rokid.model

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
 * Resolved image bytes ready for encoding into a model request (0.25 spec §4.10/§6.3).
 *
 * The encoded image is kept as the artifact store holds it; the adapter turns it into a `data:` URL or a provider
 * `file_id`, and the mime/size metadata rides along because the wire format states it explicitly.
 *
 * @param bytes the encoded image (JPEG/PNG bytes, not a raw bitmap)
 * @param mime the artifact's media type, e.g. "image/jpeg"
 * @param width the image's pixel width, 0 when the artifact's metadata does not carry one
 * @param height the image's pixel height, 0 when the artifact's metadata does not carry one
 */
data class ResolvedImage(val bytes: ByteArray, val mime: String, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedImage) return false
        return bytes.contentEquals(other.bytes) && mime == other.mime && width == other.width && height == other.height
    }
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * Host-owned port that resolves an opaque audio reference (from [com.sheen.rokid.plugin.AudioSegmentHandle.ref]) into actual
 * bytes at encode time. Unknown or expired refs return null so the adapter can refuse the request rather than send
 * an empty audio payload.
 */
interface MediaSource {
    fun resolveAudio(ref: String): ResolvedAudio?

    /**
     * Resolves an image artifact id (from [com.sheen.rokid.plugin.ImageRef.artifactId]) into bytes at encode time.
     *
     * Unknown or expired artifacts return null, and the encoder decides whether that is a visible text placeholder or
     * a refusal — never a silently sent empty image. Defaults to null so every existing audio-only implementation
     * keeps compiling and keeps its behaviour (0.25 spec §6.3).
     */
    fun resolveImage(artifactId: String): ResolvedImage? = null
}

/**
 * The visible text an encoder puts on the wire for a **historical** image whose artifact is gone (0.25 R71).
 *
 * The current input still refuses a missing image: that image is the thing being asked about, and silently replacing it
 * would answer a different question. A tool result from an earlier round, by contrast, is history — its text survives
 * and this line makes the loss visible instead of dropping the image silently.
 */
internal fun imageUnavailablePlaceholder(artifactId: String): String = "[image unavailable: $artifactId]"
