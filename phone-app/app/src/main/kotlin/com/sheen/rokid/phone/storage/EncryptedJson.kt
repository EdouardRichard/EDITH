package com.sheen.rokid.phone.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The encrypted-blob JSON helpers shared by every storage component in this module.
 *
 * 0.52 Task 2 extracted these from `AndroidProfileRepository.kt` (which Task 7 deletes) so the catalog repository, the
 * namespace store and the legacy janitor all read and write the same way: a read failure or an unparseable document
 * becomes [unavailable], and the plaintext buffer is zeroed after the store copies it.
 */
internal inline fun <T> safely(block: () -> T): T = try {
    block()
} catch (_: Exception) {
    unavailable()
}

internal fun readJson(blobs: EncryptedBlobStore, id: String): JsonObject? {
    val bytes = safely { blobs.read(id) } ?: return null
    return try {
        safely { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
    } finally {
        bytes.fill(0)
    }
}

internal fun writeJson(blobs: EncryptedBlobStore, id: String, value: JsonObject) {
    val bytes = value.toString().toByteArray(Charsets.UTF_8)
    try {
        safely { blobs.write(id, bytes) }
    } finally {
        bytes.fill(0)
    }
}
