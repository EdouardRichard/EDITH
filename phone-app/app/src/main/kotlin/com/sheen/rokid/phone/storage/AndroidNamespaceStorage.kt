package com.sheen.rokid.phone.storage

import com.sheen.rokid.plugin.StorageHandle
import kotlinx.serialization.json.*
import java.util.Base64

class AndroidNamespaceStorage(private val namespace: String, private val blobs: EncryptedBlobStore) : StorageHandle {
    init { validate(namespace) }
    private val id = "namespace-$namespace"
    private fun entries(): Map<String, String> {
        val json = readJson(blobs, id) ?: return emptyMap()
        return safely { require(json["version"]!!.jsonPrimitive.int == 1); json["entries"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } }
    }
    private fun publish(values: Map<String, String>) = writeJson(blobs, id, buildJsonObject {
        put("version", 1); put("entries", buildJsonObject { values.forEach { (key, value) -> put(key, value) } })
    })
    override fun read(key: String): ByteArray? { validate(key); return synchronized(blobs) { entries()[key]?.let { safely { Base64.getDecoder().decode(it) } } } }
    override fun write(key: String, value: ByteArray) { validate(key); synchronized(blobs) { publish(entries() + (key to Base64.getEncoder().encodeToString(value))) } }
    override fun delete(key: String) { validate(key); synchronized(blobs) { publish(entries() - key) } }
    override fun clear() { synchronized(blobs) { safely { blobs.delete(id) } } }
    private fun validate(value: String) { require(value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) }
}
