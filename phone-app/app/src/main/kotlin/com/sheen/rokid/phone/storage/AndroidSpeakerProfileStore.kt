package com.sheen.rokid.phone.storage

import com.sheen.rokid.plugin.SpeakerProfileRef
import com.sheen.rokid.storage.SpeakerProfileStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AndroidSpeakerProfileStore(private val blobs: EncryptedBlobStore) : SpeakerProfileStore {
    @Synchronized override fun save(embedding: FloatArray): SpeakerProfileRef {
        require(embedding.size in 1..4096 && embedding.all { it.isFinite() } && embedding.any { it != 0f })
        val ref = SpeakerProfileRef("speaker-${UUID.randomUUID()}", 0)
        val data = ByteBuffer.allocate(16 + embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(1).putLong(ref.revision).putInt(embedding.size); embedding.forEach { data.putFloat(it) }
        try { safely { blobs.write(ref.id, data.array()) } } finally { data.array().fill(0) }
        return ref
    }
    @Synchronized override fun load(ref: SpeakerProfileRef): FloatArray? {
        if (!valid(ref)) return null
        val bytes = safely { blobs.read(ref.id) } ?: return null
        return try { safely {
            require(bytes.size in 20..16400)
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.int == 1 && data.long == ref.revision)
            val size = data.int; require(size in 1..4096 && data.remaining() == size * 4)
            FloatArray(size) { data.float }.also { require(it.all { value -> value.isFinite() } && it.any { value -> value != 0f }) }
        } } finally { bytes.fill(0) }
    }
    @Synchronized override fun delete(ref: SpeakerProfileRef) { if (valid(ref)) safely { blobs.delete(ref.id) } }
    private fun valid(ref: SpeakerProfileRef) = ref.revision == 0L && ref.id.matches(Regex("speaker-[0-9a-f-]{36}"))
}
