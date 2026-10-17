package com.sheen.rokid.storage

import com.sheen.rokid.plugin.SpeakerProfileRef
import java.util.UUID

interface SpeakerProfileStore {
    fun save(embedding: FloatArray): SpeakerProfileRef
    fun load(ref: SpeakerProfileRef): FloatArray?
    fun delete(ref: SpeakerProfileRef)
}

class MemorySpeakerProfileStore : SpeakerProfileStore {
    private val profiles = mutableMapOf<SpeakerProfileRef, FloatArray>()

    @Synchronized override fun save(embedding: FloatArray): SpeakerProfileRef {
        require(embedding.isNotEmpty() && embedding.all { it.isFinite() } && embedding.any { it != 0f })
        val ref = SpeakerProfileRef(UUID.randomUUID().toString(), 0)
        profiles[ref] = embedding.copyOf()
        return ref
    }

    @Synchronized override fun load(ref: SpeakerProfileRef): FloatArray? = profiles[ref]?.copyOf()

    @Synchronized override fun delete(ref: SpeakerProfileRef) {
        profiles.remove(ref)?.fill(0f)
    }
}
