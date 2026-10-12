package edith.phone

import edith.plugin.AudioSegmentHandle
import edith.plugin.AudioSegmentSource
import edith.plugin.AudioSubscription
import edith.contracts.AudioSegment

/**
 * Composite provider that satisfies both [AudioSubscription] (the existing audio capability) and
 * [AudioSegmentSource] (P5's segment handle service). Registered under [Capability.Audio] so the plugin can
 * obtain both through the same capability grant.
 */
internal class AudioWithSegments(
    private val subscription: AudioSubscription,
    private val segments: AudioSegmentSource,
) : AudioSubscription, AudioSegmentSource {
    override fun listen(listener: (AudioSegment) -> Unit) = subscription.listen(listener)
    override fun fetch(turnId: String): AudioSegmentHandle? = segments.fetch(turnId)
}
