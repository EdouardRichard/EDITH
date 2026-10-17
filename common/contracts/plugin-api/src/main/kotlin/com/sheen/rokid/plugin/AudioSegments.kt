package com.sheen.rokid.plugin

/**
 * An opaque handle to a captured audio segment. Plugins pass this through [InputPart.Audio] so the model adapter
 * can resolve the actual bytes at encode time; the plugin never sees raw audio.
 *
 * @param ref an opaque identifier the host uses to resolve bytes via [MediaSource] (model-runtime)
 * @param turnId the turn this segment belongs to
 * @param durationMillis estimated duration
 * @param byteCount size of the raw PCM in bytes
 * @param formatId a short format tag (e.g. "pcm16-16k-mono") for the adapter to pick the right encoder
 */
data class AudioSegmentHandle(
    val ref: String,
    val turnId: String,
    val durationMillis: Int,
    val byteCount: Int,
    val formatId: String,
) {
    /** Never include bytes or paths in toString — logs must not leak audio content. */
    override fun toString(): String =
        "AudioSegmentHandle(ref=$ref, turnId=$turnId, durationMs=$durationMillis, bytes=$byteCount, fmt=$formatId)"
}

/**
 * Host-owned service that lets plugins retrieve an opaque handle to the current turn's audio segment.
 *
 * The plugin calls [fetch] with its turn id; the host returns a handle if a segment exists for that turn in the
 * current session generation, or null otherwise. The handle is then embedded in [InputPart.Audio] and resolved
 * by the model adapter at encode time.
 */
interface AudioSegmentSource {
    fun fetch(turnId: String): AudioSegmentHandle?
}
