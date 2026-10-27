package com.sheen.rokid.phone.model

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 2: the `assets/models/models-manifest.json` parser/verifier.
 *
 * Two rules are load-bearing and easy to get wrong:
 * - **`bundleId` is opaque.** It is the canonical id over the 21 entries pinned by
 *   `tools/scripts/p2/model-bundles.json`, while the APK emits 15 files (KWS is packaged separately under
 *   `assets/kws/`). A parser that recomputed it from `files[]` would disagree with the build and with the
 *   Laya/Embedding receipts, so the declared value must be returned untouched.
 * - **A verified manifest means the bytes really are the declared ones.** `verify` is what stands between the
 *   APK's assets and a model store that would otherwise copy whatever is there.
 */
class BundledModelsManifestTest {

    private val contents: Map<String, ByteArray> = linkedMapOf(
        "asr/encoder.onnx" to "encoder-bytes".toByteArray(),
        "laya/graph.tflite" to "graph-bytes".toByteArray(),
        "embeddinggemma/tokenizer.model" to "tokenizer-bytes".toByteArray(),
    )

    private data class Entry(
        val group: String,
        val role: String,
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun entries(contents: Map<String, ByteArray> = this.contents): List<Entry> =
        contents.map { (path, bytes) ->
            Entry(
                group = path.substringBefore('/'),
                role = "role-${path.substringAfter('/')}",
                path = path,
                bytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
            )
        }

    private fun manifestJson(
        bundleId: String = "f06c6a5beaa21ed39dd5ed189e77bc3b078796d53a74436ce549693127455565",
        schemaVersion: Int = 1,
        files: List<Entry> = entries(),
    ): String = buildString {
        append("""{"schemaVersion":$schemaVersion,"bundleId":"$bundleId","files":[""")
        files.forEachIndexed { index, file ->
            if (index > 0) append(',')
            append(
                """{"group":"${file.group}","role":"${file.role}","path":"${file.path}",""" +
                    """"bytes":${file.bytes},"sha256":"${file.sha256}"}""",
            )
        }
        append("]}")
    }

    /** A map-backed [ModelAssetSource] rooted at `assets/models`: keys are the manifest's `path` values. */
    private class MapAssetSource(private val files: Map<String, ByteArray>) : ModelAssetSource {
        override fun open(path: String): InputStream =
            files[path]?.let { ByteArrayInputStream(it) } ?: throw IOException("no asset $path")
    }

    private fun source(contents: Map<String, ByteArray> = this.contents) = MapAssetSource(contents)

    @Test
    fun theDeclaredBundleIdIsPreservedEvenThoughItDoesNotDescribeOnlyTheEmittedFiles() {
        // The real value is a hash over 21 entries while `files[]` carries 15; a recomputing parser would reject
        // this. An opaque id can also be any non-blank token: the parser must not validate its shape.
        val declared = "canonical-21-entry-bundle-id"

        val manifest = BundledModelsManifest.parse(manifestJson(bundleId = declared))

        assertEquals("the build's canonical bundleId must be returned untouched", declared, manifest.bundleId)
        assertEquals("every emitted file is parsed", 3, manifest.files.size)
        val encoder = manifest.files.single { it.path == "asr/encoder.onnx" }
        assertEquals("asr", encoder.group)
        assertEquals("role-encoder.onnx", encoder.role)
        assertEquals(contents.getValue("asr/encoder.onnx").size.toLong(), encoder.bytes)
        assertEquals(sha256(contents.getValue("asr/encoder.onnx")), encoder.sha256)
    }

    @Test
    fun theManifestDigestCoversEveryEmittedFileAndIsStableAcrossEntryOrder() {
        val files = entries().map { BundledModelFile(it.group, it.role, it.path, it.bytes, it.sha256) }
        val first = BundledModelsManifest("any-id", files)
        val reordered = BundledModelsManifest("any-id", files.reversed())

        assertEquals("the receipt digest must not depend on manifest array order",
            first.manifestDigest(), reordered.manifestDigest())
        assertEquals(64, first.manifestDigest().length)
    }

    @Test
    fun changingAFileDigestChangesTheManifestDigestEvenWithTheSameBundleId() {
        val original = BundledModelsManifest(
            "unchanged-id",
            entries().map { BundledModelFile(it.group, it.role, it.path, it.bytes, it.sha256) },
        )
        val changed = BundledModelsManifest(
            "unchanged-id",
            entries().map {
                if (it.path == "laya/graph.tflite") {
                    BundledModelFile(it.group, it.role, it.path, it.bytes, sha256("different bytes".toByteArray()))
                } else {
                    BundledModelFile(it.group, it.role, it.path, it.bytes, it.sha256)
                }
            },
        )

        assertTrue("a changed file digest must invalidate the receipt digest",
            original.manifestDigest() != changed.manifestDigest())
    }

    @Test
    fun aMissingAssetIsRefusedAndThePathIsNamed() {
        val manifest = BundledModelsManifest.parse(manifestJson())
        val missing = "laya/graph.tflite"

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            manifest.verify(source(contents - missing))
        }

        assertTrue("the refusal must name the missing asset path, was ${thrown.message}",
            thrown.message.orEmpty().contains(missing))
    }

    @Test
    fun aFileWhoseDigestDiffersIsRefusedAndThePathIsNamed() {
        val wrong = entries().map {
            if (it.path == "asr/encoder.onnx") it.copy(sha256 = sha256("not the packaged bytes".toByteArray()))
            else it
        }
        val manifest = BundledModelsManifest.parse(manifestJson(files = wrong))

        val thrown = assertThrows(IllegalArgumentException::class.java) { manifest.verify(source()) }

        assertTrue("the refusal must name the mismatching path, was ${thrown.message}",
            thrown.message.orEmpty().contains("asr/encoder.onnx"))
    }

    @Test
    fun aFileWhoseSizeDiffersIsRefusedAndThePathIsNamed() {
        val sizes = entries().map {
            if (it.path == "embeddinggemma/tokenizer.model") {
                it.copy(bytes = contents.getValue(it.path).size.toLong() + 1)
            } else {
                it
            }
        }
        val manifest = BundledModelsManifest.parse(manifestJson(files = sizes))

        val thrown = assertThrows(IllegalArgumentException::class.java) { manifest.verify(source()) }

        assertTrue("the refusal must name the mismatching path, was ${thrown.message}",
            thrown.message.orEmpty().contains("embeddinggemma/tokenizer.model"))
    }

    @Test
    fun aDuplicatePathIsRefused() {
        val duplicate = entries().first().copy(role = "a-second-role")
        val files = entries() + duplicate

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            BundledModelsManifest.parse(manifestJson(files = files))
        }

        assertTrue("the refusal must name the duplicated path, was ${thrown.message}",
            thrown.message.orEmpty().contains("asr/encoder.onnx"))
    }

    @Test
    fun malformedJsonIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BundledModelsManifest.parse("""{"schemaVersion":1,"bundleId":"x","files":[""")
        }
    }

    @Test
    fun aNegativeByteCountIsRefused() {
        val files = entries().mapIndexed { index, entry ->
            if (index == 0) entry.copy(bytes = -1) else entry
        }

        assertThrows(IllegalArgumentException::class.java) {
            BundledModelsManifest.parse(manifestJson(files = files))
        }
    }

    @Test
    fun aDigestThatIsNotSha256IsRefused() {
        val files = entries().mapIndexed { index, entry ->
            if (index == 0) entry.copy(sha256 = "not-a-sha256") else entry
        }

        assertThrows(IllegalArgumentException::class.java) {
            BundledModelsManifest.parse(manifestJson(files = files))
        }
    }

    @Test
    fun anUnknownGroupIsAcceptedBecauseTheGroupFieldIsInformational() {
        // `group` is build metadata (the `model-bundles.json` grouping); a future group must not make an otherwise
        // valid manifest unreadable. Only `path` is operational.
        val files = entries().mapIndexed { index, entry ->
            if (index == 0) entry.copy(group = "future-group") else entry
        }

        val manifest = BundledModelsManifest.parse(manifestJson(files = files))

        assertEquals("future-group", manifest.files.first().group)
    }
}
