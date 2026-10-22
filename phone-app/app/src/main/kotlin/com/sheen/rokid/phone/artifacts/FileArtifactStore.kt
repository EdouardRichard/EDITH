package com.sheen.rokid.phone.artifacts

import com.sheen.rokid.device.ArtifactStore
import com.sheen.rokid.device.StoredArtifact
import com.sheen.rokid.plugin.ImageRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * The production [ArtifactStore]: encoded images under the App's cache directory (0.25 spec §5.2; rulings R67/R72).
 *
 * **What persists.** One `.bin` per artifact and one small `.json` metadata document (`artifactId`, `mime`, width,
 * height, sha256, byte count, timestamps). The bytes never enter the JSON, a log line or a model request; a request
 * carries only the [ImageRef], and `MediaSource.resolveImage` turns it back into bytes at encode time.
 *
 * **Retention.** At most [maxCount] artifacts and [maxBytes] bytes, least-recently-used first (a [get] counts as use).
 * The newest [retainRecent] artifacts are a floor: the count/byte caps may never evict them, because the context may
 * still hold those K references (R72: K = 3 in `TurnTrimmer`). [evictBefore] is the explicit form of the same rule.
 *
 * **Why the App cache.** The artifact is device-local scratch data, not repository or evidence: a cache clear is a
 * legitimate loss that degrades to a visible `[image unavailable: …]` placeholder, and nothing here writes outside
 * [root].
 */
class FileArtifactStore(
    private val root: File,
    private val maxCount: Int = DEFAULT_MAX_COUNT,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val retainRecent: Int = DEFAULT_RETAIN_RECENT,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArtifactStore {

    init {
        require(maxCount >= 1) { "maxCount must be positive, was $maxCount" }
        require(retainRecent >= 1) { "retainRecent must be positive, was $retainRecent" }
        require(maxCount >= retainRecent) {
            "maxCount ($maxCount) must be at least the retained floor ($retainRecent)"
        }
        require(maxBytes > 0L) { "maxBytes must be positive, was $maxBytes" }
    }

    @Synchronized
    override fun put(bytes: ByteArray, mime: String, width: Int, height: Int): ImageRef {        require(bytes.isNotEmpty()) { "an empty artifact is not an image" }
        require(mime.isNotBlank()) { "an artifact needs a media type" }
        require(width >= 0 && height >= 0) { "artifact dimensions must not be negative" }
        ensureRoot()
        val created = clock()
        val artifactId = "img-$created-${UUID.randomUUID().toString().take(8)}"
        val sha256 = sha256Hex(bytes)
        val metadata = ArtifactMetadata(
            artifactId = artifactId,
            fileName = "$artifactId.bin",
            mime = mime,
            width = width,
            height = height,
            sha256 = sha256,
            byteCount = bytes.size.toLong(),
            createdMillis = created,
            lastUsedMillis = created,
        )
        writeAtomically(bytesFile(artifactId), bytes)
        writeAtomically(metadataFile(artifactId), encode(metadata).toByteArray())
        enforceLimits()
        return ImageRef(artifactId, mime, width, height, sha256)
    }

    @Synchronized
    override fun get(artifactId: String): StoredArtifact? {
        val metadata = readMetadata(artifactId) ?: return null
        val file = File(root, metadata.fileName)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // A truncated or replaced file is not the artifact the reference names: report it as missing, never send it.
        if (bytes.size.toLong() != metadata.byteCount || sha256Hex(bytes) != metadata.sha256) return null
        touch(metadata)
        return StoredArtifact(
            artifactId = metadata.artifactId,
            bytes = bytes,
            mime = metadata.mime,
            width = metadata.width,
            height = metadata.height,
            sha256 = metadata.sha256,
        )
    }

    @Synchronized
    override fun evictBefore(keep: Int) {
        require(keep >= 0) { "keep must not be negative, was $keep" }
        newestFirst().drop(keep).forEach(::delete)
    }

    // ── retention ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Enforces the count and byte caps, oldest first, without ever crossing the [retainRecent] floor.
     *
     * The floor is checked first: an impossible byte cap (smaller than K artifacts) simply leaves K artifacts in place,
     * because breaking a live reference is worse than exceeding cache scratch space.
     */
    private fun enforceLimits() {
        val ordered = newestFirst()
        var count = ordered.size
        var totalBytes = ordered.sumOf { it.byteCount }
        for (index in ordered.indices.reversed()) {
            if (count <= maxCount && totalBytes <= maxBytes) break
            if (count <= retainRecent) break
            val entry = ordered[index]
            if (delete(entry)) {
                count--
                totalBytes -= entry.byteCount
            }
        }
    }

    private fun newestFirst(): List<ArtifactMetadata> = allMetadata().sortedWith(
        compareByDescending<ArtifactMetadata> { it.lastUsedMillis }
            .thenByDescending { it.createdMillis }
            .thenByDescending { it.artifactId },
    )

    private fun touch(metadata: ArtifactMetadata) {
        val now = clock()
        if (metadata.lastUsedMillis == now) return
        runCatching {
            writeAtomically(metadataFile(metadata.artifactId), encode(metadata.copy(lastUsedMillis = now)).toByteArray())
        }
    }

    private fun delete(metadata: ArtifactMetadata): Boolean {
        val bytesGone = runCatching { bytesFile(metadata.artifactId).delete() }.getOrDefault(false)
        val metadataGone = runCatching { metadataFile(metadata.artifactId).delete() }.getOrDefault(false)
        return bytesGone || metadataGone
    }

    // ── storage ──────────────────────────────────────────────────────────────────────────────────

    private fun ensureRoot() {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw IOException("artifact cache is unavailable")
        }
    }

    private fun bytesFile(artifactId: String): File = File(root, "$artifactId.bin")

    private fun metadataFile(artifactId: String): File = File(root, "$artifactId.json")

    private fun readMetadata(artifactId: String): ArtifactMetadata? {
        val file = metadataFile(artifactId)
        if (!file.isFile) return null
        return decode(runCatching { file.readText() }.getOrNull())?.takeIf { it.artifactId == artifactId }
    }

    private fun allMetadata(): List<ArtifactMetadata> =
        root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file -> decode(runCatching { file.readText() }.getOrNull()) }
            .toList()

    /** The metadata is a plain JSON object written by hand: the App module carries no serialization compiler plugin. */
    private fun encode(metadata: ArtifactMetadata): String = buildJsonObject {
        put("artifactId", metadata.artifactId)
        put("fileName", metadata.fileName)
        put("mime", metadata.mime)
        put("width", metadata.width)
        put("height", metadata.height)
        put("sha256", metadata.sha256)
        put("byteCount", metadata.byteCount)
        put("createdMillis", metadata.createdMillis)
        put("lastUsedMillis", metadata.lastUsedMillis)
    }.toString()

    private fun decode(text: String?): ArtifactMetadata? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun string(name: String) = (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        fun int(name: String) = (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        fun long(name: String) = (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
        return ArtifactMetadata(
            artifactId = string("artifactId") ?: return null,
            fileName = string("fileName") ?: return null,
            mime = string("mime") ?: return null,
            width = int("width") ?: return null,
            height = int("height") ?: return null,
            sha256 = string("sha256") ?: return null,
            byteCount = long("byteCount") ?: return null,
            createdMillis = long("createdMillis") ?: return null,
            lastUsedMillis = long("lastUsedMillis") ?: return null,
        )
    }

    /** Writes through a temp file so a crash cannot leave a half-written artifact or index entry. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        ensureRoot()
        val temp = File(root, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (failure: IOException) {
            // A filesystem without atomic move support still gets the replace semantics; the temp file is cleaned up.
            temp.delete()
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The metadata document: ids and hashes only. Image bytes are never part of it. */
    internal data class ArtifactMetadata(
        val artifactId: String,
        val fileName: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val sha256: String,
        val byteCount: Long,
        val createdMillis: Long,
        val lastUsedMillis: Long,
    )

    companion object {
        /** R72's suggested cap, adjustable: 32 artifacts per session. */
        const val DEFAULT_MAX_COUNT: Int = 32

        /** R72's suggested cap, adjustable: 64 MiB per session. */
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024L * 1024L

        /** The K = 3 references `TurnTrimmer` keeps whole; retention may never break them. */
        const val DEFAULT_RETAIN_RECENT: Int = 3
    }
}
