package com.sheen.rokid.phone.model

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A read-only source of the files packaged under the APK's `assets/models/` directory.
 *
 * The seam exists only so the JVM tests can feed a map of bytes instead of a real [AssetManager]; production
 * always goes through [from]. Paths are relative to `assets/models`, always `/`-separated (an asset path is not a
 * host path), and a missing asset surfaces as an exception from [open].
 */
fun interface ModelAssetSource {
    fun open(path: String): InputStream

    companion object {
        /** The production source over the APK's `assets/models/` tree. */
        fun from(assets: AssetManager): ModelAssetSource = ModelAssetSource { path -> assets.open("models/$path") }
    }
}

/** One file entry of `assets/models/models-manifest.json`. */
data class BundledModelFile(
    /** Build-manifest grouping (`asr`, `laya`, `embeddinggemma`). Informational: only [path] is operational. */
    val group: String,
    /** The build manifest's role/name for the file. Informational, like [group]. */
    val role: String,
    /** Asset-model-relative path (`group/file`); the key [ModelAssetSource.open] receives. */
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * The manifest the 0.4 build writes to `assets/models/models-manifest.json`.
 *
 * **`bundleId` is opaque at runtime and must stay that way.** The build verifies it as the canonical digest over
 * all 21 entries of `tools/scripts/p2/model-bundles.json` (KWS included) and refuses a manifest whose declared id
 * is not that digest, so a changed path or file digest cannot silently keep the old id. The manifest lists the 15
 * files the APK actually emits (KWS ships under `assets/kws/` and is not part of this list); recomputing the id
 * from [files] would therefore disagree with the value the build declared and with the receipts that record it, so
 * [parse] returns the declared string untouched.
 *
 * [verify] is the runtime gate: it fails when a declared file is missing, larger/smaller than declared, or hashes
 * differently. Every refusal names the offending [BundledModelFile.path]. It deliberately does not require
 * [files] to be a complete set - a manifest is only ever checked against what the same build wrote.
 *
 * [manifestDigest] is the receipt-side digest over exactly these 15 emitted entries. It exists because receipts
 * written by older builds contain only the id; recording this digest as well means a file-digest change forces a
 * re-materialization even before the next build's id check (and gives the migration a one-copy upgrade path).
 */
data class BundledModelsManifest(val bundleId: String, val files: List<BundledModelFile>) {

    /** Verifies every declared file against [source]; throws [IllegalArgumentException] naming the first refusal. */
    fun verify(source: ModelAssetSource) {
        files.forEach { file -> verifyFile(source, file) }
    }

    /**
     * SHA-256 over [files], canonicalised as `group/path:sha256` lines sorted by path and joined with `\n`.
     *
     * The Laya/Embedding receipt records this value next to [bundleId]; a changed file digest therefore changes
     * the receipt even if a build ever failed to update its id, and [BundledModelProvisioner] copies again.
     */
    fun manifestDigest(): String {
        val canonical = files
            .map { "${it.group}/${it.path}:${it.sha256.lowercase()}" }
            .sorted()
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun verifyFile(source: ModelAssetSource, file: BundledModelFile) {
        val input = try {
            source.open(file.path)
        } catch (missing: Exception) {
            throw IllegalArgumentException("bundled model asset is missing: ${file.path}", missing)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            input.use { stream ->
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= file.bytes) {
                            "bundled model file ${file.path} is larger than the declared ${file.bytes} bytes"
                        }
                        digest.update(buffer, 0, count)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        } catch (unreadable: IOException) {
            throw IllegalArgumentException("bundled model file ${file.path} could not be read", unreadable)
        }
        require(total == file.bytes) {
            "bundled model file ${file.path} has $total bytes, expected ${file.bytes}"
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual.equals(file.sha256, ignoreCase = true)) {
            "bundled model file ${file.path} has SHA-256 $actual, expected ${file.sha256}"
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /**
         * Parses the manifest's JSON text.
         *
         * Unknown fields are ignored (forward compatibility); `group`/`role` values are not constrained because
         * they are build metadata. A duplicate [BundledModelFile.path], a negative [BundledModelFile.bytes], or a
         * digest that is not 64 hex characters is refused, because each would make [verify] ambiguous or unsafe.
         */
        fun parse(json: String): BundledModelsManifest {
            val root = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("bundled model manifest is not a valid JSON object", invalid)
            }
            require(root.integer("schemaVersion") == SCHEMA_VERSION) {
                "unsupported bundled model manifest schemaVersion"
            }
            val bundleId = root.string("bundleId")
            require(bundleId.isNotBlank()) { "bundled model manifest has a blank bundleId" }
            val files = root.array("files").mapIndexed { index, element ->
                try {
                    parseFile(element)
                } catch (invalid: IllegalArgumentException) {
                    throw IllegalArgumentException("bundled model manifest files[$index]: ${invalid.message}", invalid)
                }
            }
            val duplicates = files.groupBy { it.path }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "bundled model manifest has duplicate paths: ${duplicates.sorted()}"
            }
            return BundledModelsManifest(bundleId, files)
        }

        private fun parseFile(element: JsonElement): BundledModelFile {
            val entry = element.jsonObject
            val group = entry.string("group")
            val role = entry.string("role")
            val path = entry.string("path")
            require(group.isNotBlank()) { "group must not be blank" }
            require(role.isNotBlank()) { "role must not be blank" }
            require(path.isNotBlank()) { "path must not be blank" }
            val bytes = requireNotNull(entry.literal("bytes").longOrNull) { "bytes must be an integer" }
            require(bytes >= 0) { "bytes must not be negative" }
            val sha256 = entry.string("sha256")
            require(SHA256.matches(sha256)) { "sha256 is not a SHA-256 hex digest" }
            return BundledModelFile(group = group, role = role, path = path, bytes = bytes, sha256 = sha256)
        }

        private fun JsonObject.string(key: String): String {
            val value = field(key).jsonPrimitive
            require(value.isString) { "$key must be a string" }
            return value.content
        }

        private fun JsonObject.array(key: String): JsonArray = field(key).jsonArray

        private fun JsonObject.literal(key: String): JsonPrimitive {
            val value = field(key).jsonPrimitive
            require(!value.isString) { "$key must be a literal" }
            return value
        }

        private fun JsonObject.integer(key: String) = requireNotNull(literal(key).intOrNull) { "$key must be an integer" }

        private fun JsonObject.field(key: String) = requireNotNull(get(key)) { "Missing $key" }
    }
}
