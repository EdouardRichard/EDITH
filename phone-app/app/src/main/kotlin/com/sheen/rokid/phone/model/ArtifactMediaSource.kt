package com.sheen.rokid.phone.model

import com.sheen.rokid.device.ArtifactStore
import com.sheen.rokid.model.MediaSource
import com.sheen.rokid.model.ResolvedAudio
import com.sheen.rokid.model.ResolvedImage

/**
 * The production [MediaSource] for images (0.25 R67/R71): reads an artifact id from the **same** [ArtifactStore] the
 * device kernel writes screenshots into, and answers the bytes/mime/dimensions the encoders put on the wire.
 *
 * A missing artifact resolves to `null`, never an exception — the adapter decides the degradation (a visible
 * `[image unavailable: …]` placeholder for historical images; a `CapabilityMissing` refusal for the current input).
 *
 * Audio is delegated to the existing bridge when one is wired, so the two media kinds share the one source
 * `ControlledModelClient` hands to both adapters. In 0.25 only TEXT + IMAGE are required; this delegation keeps the
 * pre-existing audio path working unchanged.
 */
class ArtifactMediaSource(
    private val artifacts: ArtifactStore,
    private val audio: MediaSource? = null,
) : MediaSource {

    override fun resolveAudio(ref: String): ResolvedAudio? = audio?.resolveAudio(ref)

    override fun resolveImage(artifactId: String): ResolvedImage? {
        val stored = artifacts.get(artifactId) ?: return null
        return ResolvedImage(stored.bytes, stored.mime, stored.width, stored.height)
    }
}
