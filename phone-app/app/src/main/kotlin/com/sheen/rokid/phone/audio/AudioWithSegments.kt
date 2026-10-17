package com.sheen.rokid.phone.audio

import com.sheen.rokid.plugin.AudioSegmentHandle
import com.sheen.rokid.plugin.AudioSegmentSource
import com.sheen.rokid.plugin.AudioSubscription
import com.sheen.rokid.contracts.AudioSegment

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
