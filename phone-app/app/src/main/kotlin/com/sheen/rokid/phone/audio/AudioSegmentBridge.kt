package com.sheen.rokid.phone.audio

import com.sheen.rokid.model.MediaSource
import com.sheen.rokid.model.ResolvedAudio
import com.sheen.rokid.plugin.AudioSegmentHandle
import com.sheen.rokid.plugin.AudioSegmentSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges [AudioSegmentCapture] (which collects PCM frames from the pipeline) to the plugin-facing
 * [AudioSegmentSource] and the model-facing [MediaSource].
 *
 * Lifecycle:
 * - [sealForTurn] is called when a transcript becomes final, capturing the most recent speech segment.
 * - [handleFor] returns an opaque handle the plugin embeds in [InputPart.Audio].
 * - [resolveAudio] returns the actual bytes when the adapter encodes the request.
 * - [releaseTurn] frees the segment after the turn completes or the session ends.
 *
 * Thread safety: all maps are concurrent; seal/release may race with resolve but the worst case is a null return
 * (the adapter refuses the audio part rather than sending stale bytes).
 */
class AudioSegmentBridge(
    private val capture: AudioSegmentCapture,
) : AudioSegmentSource, MediaSource {

    /** turnId → ref mapping for handles we've issued. */
    private val turnToRef = ConcurrentHashMap<String, String>()
    /** ref → sealed segment data. */
    private val segments = ConcurrentHashMap<String, SealedSegment>()

    private data class SealedSegment(
        val bytes: ByteArray,
        val durationMillis: Int,
        val truncated: Boolean,
    )

    /**
     * Called when a transcript revision becomes final for [turnId]. Seals the capture and stores the segment.
     * Returns true if a non-truncated segment was captured.
     */
    fun sealForTurn(turnId: String): Boolean {
        val segment = capture.seal(turnId) ?: return false
        if (segment.truncated || segment.bytes.isEmpty()) return false
        val ref = "seg-${UUID.randomUUID()}"
        turnToRef[turnId] = ref
        segments[ref] = SealedSegment(segment.bytes, segment.durationMillis, false)
        return true
    }

    /**
     * Releases the segment for [turnId], freeing RAM.
     */
    fun releaseTurn(turnId: String) {
        val ref = turnToRef.remove(turnId)
        if (ref != null) segments.remove(ref)
        capture.release(turnId)
    }

    // --- AudioSegmentSource ---

    override fun fetch(turnId: String): AudioSegmentHandle? {
        val ref = turnToRef[turnId] ?: return null
        val seg = segments[ref] ?: return null
        return AudioSegmentHandle(
            ref = ref,
            turnId = turnId,
            durationMillis = seg.durationMillis,
            byteCount = seg.bytes.size,
            formatId = "pcm16-16k-mono",
        )
    }

    // --- MediaSource ---

    override fun resolveAudio(ref: String): ResolvedAudio? {
        val seg = segments[ref] ?: return null
        return ResolvedAudio(seg.bytes, "wav", seg.durationMillis)
    }
}
