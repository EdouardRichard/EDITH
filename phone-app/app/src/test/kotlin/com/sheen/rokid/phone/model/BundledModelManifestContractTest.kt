package com.sheen.rokid.phone.model

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 whole-branch fix: the tracked build input `tools/scripts/p2/model-bundles.json` is a contract, and the unit
 * tests are where its invariants can be checked cheaply.
 *
 * - **The fifteen license/declaration texts are a delivery requirement.** Spec §6.5 requires the existing
 *   ASR/VAD/speaker declarations plus Laya/KWS/Gemma texts under `assets/models/licenses/`. The build copies
 *   whatever the manifest declares, so a silently dropped entry would shrink the APK without failing anything.
 * - **`bundleId` is the canonical digest over all 21 entries.** The build refuses a manifest whose declared id is
 *   not that digest; this test pins the same formula independently so a changed path/digest cannot keep the old id
 *   (and therefore cannot let Laya/Embedding receipts reuse stale weights) without someone deliberately updating it.
 *
 * The test locates the repository rather than embedding absolute paths, so it runs from the app module's default
 * Gradle working directory and from an IDE.
 */
class BundledModelManifestContractTest {

    private data class FileEntry(val group: String, val path: String, val sha256: String)

    private val repositoryRoot: File = findRepositoryRoot()
    private val manifestJson = Json
        .parseToJsonElement(File(repositoryRoot, MANIFEST_PATH).readText())
        .jsonObject

    private fun entries(): List<FileEntry> = manifestJson.getValue("files").jsonArray.map { raw ->
        val entry = raw.jsonObject
        FileEntry(
            group = entry.getValue("group").jsonPrimitive.content,
            path = entry.getValue("path").jsonPrimitive.content,
            sha256 = entry.getValue("sha256").jsonPrimitive.content,
        )
    }

    private fun licenses(): List<Pair<String, String>> = manifestJson.getValue("licenses").jsonArray.map { raw ->
        val entry = raw.jsonObject
        entry.getValue("file").jsonPrimitive.content to entry.getValue("source").jsonPrimitive.content
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** The canonical id: SHA-256 of `group/path:sha256` lines sorted by path, joined with `\n` (no trailing newline). */
    private fun canonicalBundleId(entries: List<FileEntry>): String = sha256(
        entries.map { "${it.group}/${it.path}:${it.sha256.lowercase()}" }.sorted().joinToString("\n"),
    )

    @Test
    fun thePinnedManifestDeclaresTheFifteenRequiredLicenseAndDeclarationTexts() {
        val declared = licenses().map { it.first }.toSet()

        assertEquals(
            "the APK must carry every spec §6.5 license/declaration text, was $declared",
            REQUIRED_LICENSE_FILES,
            declared,
        )
    }

    @Test
    fun everyDeclaredLicenseTextStillExistsInTheRepository() {
        val missing = licenses().filterNot { (_, source) -> File(repositoryRoot, source).isFile }

        assertTrue("declared license sources are missing: $missing", missing.isEmpty())
    }

    @Test
    fun thePinnedBundleIdIsTheCanonicalDigestOfAllTwentyOneEntries() {
        val declared = manifestJson.getValue("bundleId").jsonPrimitive.content
        val entries = entries()

        assertEquals("the manifest must pin all 21 model/KWS entries", 21, entries.size)
        assertEquals(
            "bundleId must be the canonical digest over group/path:sha256; a digest change needs a new id",
            declared,
            canonicalBundleId(entries),
        )
    }

    @Test
    fun changingAnyPinnedFileDigestChangesTheCanonicalBundleId() {
        val original = entries()
        val changed = original.mapIndexed { index, entry ->
            if (index == 0) entry.copy(sha256 = "0".repeat(64)) else entry
        }

        assertNotEquals(
            "a changed file digest without a changed bundleId would let Laya/Embedding receipts skip a re-copy",
            canonicalBundleId(original),
            canonicalBundleId(changed),
        )
    }

    private companion object {
        const val MANIFEST_PATH = "tools/scripts/p2/model-bundles.json"

        /**
         * The exact 15 texts the 0.4 delivery APK must carry: the ten already shipped by Task 1 plus the five
         * ASR/VAD/speaker declarations that were retained in `tools/scripts/p2/licenses/` but never packaged.
         */
        val REQUIRED_LICENSE_FILES = setOf(
            "3D-Speaker-current-project-Apache-2.0.txt",
            "Apache-2.0-standard.txt",
            "asr-model-card.md",
            "asr-upstream-model-card.md",
            "gemma-prohibited-use-policy.txt",
            "gemma-terms-of-use.txt",
            "kws-model-card.md",
            "laya-model-card.md",
            "laya-sample-NOTICE.txt",
            "onnxruntime-v1.28.2-MIT.txt",
            "onnxruntime-v1.28.2-ThirdPartyNotices.txt",
            "sherpa-v1.13.8-Apache-2.0.txt",
            "silero-v5-MIT.txt",
            "silero-v5-model-card.md",
            "speaker-v1.0.1-model-card.md",
        )

        fun findRepositoryRoot(): File {
            val start = System.getProperty("user.dir") ?: "."
            var directory = File(start).absoluteFile
            while (true) {
                if (File(directory, MANIFEST_PATH).isFile) return directory
                directory = directory.parentFile
                    ?: throw AssertionError("cannot find $MANIFEST_PATH above $start")
            }
        }
    }
}
