package com.sheen.rokid.phone
import com.sheen.rokid.phone.model.EngineBundleProvisioning

import com.sheen.rokid.audio.android.EngineManifestLoader
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.contracts.ContractError
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The App-owned import seam for a local engine bundle.
 *
 * It must never silently fall back to an unvalidated bundle: the ASR path would then run on bytes nobody checked,
 * which is exactly the failure mode the P2/T17 import pipeline was built to prevent. Every refusal below therefore
 * has to come back as `ENGINE_MISSING` and must leave the store without an active bundle.
 */
class EngineBundleProvisioningTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")
    private lateinit var store: EngineModelStore
    private lateinit var provisioning: EngineBundleProvisioning

    /** A fresh bundle root plus the store the import must activate into. */
    private fun bundleRoot(): File {
        store = EngineModelStore(temporary.newFolder("engine-models").toPath())
        provisioning = EngineBundleProvisioning(store, EngineManifestLoader)
        return temporary.newFolder("imported-bundle")
    }

    private fun fileName(role: String) = "$role.bin"

    private fun writeArtifacts(root: File): Map<String, ByteArray> {
        val contents = roles.associateWith { role -> "$role-model".toByteArray() }
        contents.forEach { (role, bytes) -> File(root, fileName(role)).writeBytes(bytes) }
        return contents
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun writeManifest(root: File, contents: Map<String, ByteArray>,
        sizes: Map<String, Long> = emptyMap(), digests: Map<String, String> = emptyMap()) {
        val entries = roles.joinToString(",\n") { role ->
            val bytes = contents.getValue(role)
            val size = sizes[role] ?: bytes.size.toLong()
            val sha = digests[role] ?: sha256(bytes)
            """{"role":"$role","path":"${fileName(role)}","bytes":$size,"sha256":"$sha",""" +
                """"sourceUrl":"https://example.invalid/$role","revision":"r1","licensePath":"licenses/$role.txt"}"""
        }
        File(root, "engine-manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "runtime": {"sha256": "633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96"},
              "runtimeVersion": "sherpa-onnx-1.13.8",
              "boundaryOwner": "VAD",
              "recognizerEndpointEnabled": false,
              "vadWindowSamples": 512,
              "vadThreshold": 0.5,
              "vadMinSpeechSeconds": 0.25,
              "vadMaxSpeechSeconds": 20.0,
              "sampleRateHz": 16000,
              "threadCount": 2,
              "provider": "cpu",
              "frameMillis": 20,
              "endpointMillis": 350,
              "artifacts": [$entries]
            }
            """.trimIndent())
    }

    private fun refused(root: File): Throwable? = runCatching { provisioning.importBundle(root) }.exceptionOrNull()

    private fun assertEngineMissing(thrown: Throwable?) {
        assertTrue("a refusal must be ENGINE_MISSING, was $thrown",
            thrown is ContractError.CapabilityMissing && thrown.message == "ENGINE_MISSING")
        assertNull("a refused import must not activate anything", store.active())
    }

    @Test
    fun importingAValidatedBundleActivatesItInTheStore() {
        val root = bundleRoot()
        writeManifest(root, writeArtifacts(root))

        val description = provisioning.importBundle(root)

        assertTrue("a successful import must describe the active pointer, was $description",
            description.startsWith("ACTIVE:"))
        assertNotNull("the imported bundle must be the store's active one", store.active())
    }

    @Test
    fun aMissingManifestIsRefused() {
        val root = bundleRoot()
        writeArtifacts(root)

        assertEngineMissing(refused(root))
    }

    @Test
    fun aFileWhoseSizeDiffersIsRefused() {
        val root = bundleRoot()
        val contents = writeArtifacts(root)
        writeManifest(root, contents, sizes = mapOf("encoder" to contents.getValue("encoder").size + 1L))

        assertEngineMissing(refused(root))
    }

    @Test
    fun aFileWhoseDigestDiffersIsRefused() {
        val root = bundleRoot()
        val contents = writeArtifacts(root)
        writeManifest(root, contents, digests = mapOf("encoder" to sha256("not the imported bytes".toByteArray())))

        assertEngineMissing(refused(root))
    }

    @Test
    fun aValidatedPathThatIsNotARegularFileIsRefused() {
        val root = bundleRoot()
        val contents = writeArtifacts(root)
        // A directory at an artifact path is not a regular file, so the "validated path" precondition fails.
        File(root, fileName("encoder")).delete()
        File(root, fileName("encoder")).mkdirs()
        writeManifest(root, contents)

        assertEngineMissing(refused(root))
    }

    @Test
    fun aValidatedPathThatEscapesTheBundleRootIsRefused() {
        val root = bundleRoot()
        val contents = writeArtifacts(root)
        val outside = File(temporary.newFolder("outside"), fileName("encoder"))
        outside.writeBytes(contents.getValue("encoder"))
        val link = File(root, fileName("encoder")).toPath()
        Files.delete(link)
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (unsupported: Exception) {
            Assume.assumeTrue("this host cannot create symlinks: ${unsupported.message}", false)
        }
        writeManifest(root, contents)

        assertEngineMissing(refused(root))
    }

    @Test
    fun anUnparseableManifestIsRefusedWithoutActivatingAnything() {
        val root = bundleRoot()
        writeArtifacts(root)
        File(root, "engine-manifest.json").writeText("""{"schemaVersion": 1}""")

        assertEngineMissing(refused(root))
    }
}
