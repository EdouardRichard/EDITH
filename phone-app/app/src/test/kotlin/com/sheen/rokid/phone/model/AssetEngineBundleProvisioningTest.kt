package com.sheen.rokid.phone.model

import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.ModelArtifact
import com.sheen.rokid.contracts.ContractError
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 0.4 Task 2: the release-usable ASR import path over the APK's own `assets/models/asr/` tree.
 *
 * The bytes are trusted only because they are the APK's, so this seam's job is narrow but strict: every artifact
 * the passed [EngineManifest] declares must be read from `asr/<artifact.path>` and the store's own size/SHA check
 * must still run. Any missing asset or refused byte becomes `ENGINE_MISSING` and leaves the store without an
 * active pointer - never a partially activated bundle.
 */
class AssetEngineBundleProvisioningTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")

    private val contents: Map<String, ByteArray> = roles.associateWith { role -> "$role-model".toByteArray() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun manifest(): EngineManifest = EngineManifest("sherpa-onnx-1.13.8", roles.map { role ->
        val bytes = contents.getValue(role)
        ModelArtifact(role, "$role.bin", bytes.size.toLong(), sha256(bytes),
            "https://example.invalid/$role", "r1", "licenses/$role.txt")
    })

    /**
     * A map-backed [ModelAssetSource] whose keys are **asset-model-relative**, exactly as the production source
     * resolves them: an ASR artifact `encoder.bin` is `asr/encoder.bin`.
     */
    private class MapAssetSource(private val files: Map<String, ByteArray>) : ModelAssetSource {
        val opened = mutableListOf<String>()

        override fun open(path: String): InputStream {
            opened += path
            val bytes = files[path] ?: throw IOException("no asset $path")
            return ByteArrayInputStream(bytes)
        }
    }

    private fun assets(contents: Map<String, ByteArray> = this.contents): MapAssetSource =
        MapAssetSource(contents.mapKeys { (role, _) -> "asr/$role.bin" })

    private class Fixture(
        val source: MapAssetSource,
        val storeRoot: Path,
        val store: EngineModelStore,
        manifest: EngineManifest,
    ) {
        val provisioning = AssetEngineBundleProvisioning(source, store, manifest)
    }

    private fun fixture(contents: Map<String, ByteArray> = this.contents): Fixture {
        val root = temporary.newFolder("engine-models").toPath()
        return Fixture(assets(contents), root, EngineModelStore(root), manifest())
    }

    @Test
    fun importingTheBundledAsrAssetsActivatesTheStore() {
        val fixture = fixture()

        val bundle = fixture.provisioning.import()

        assertNotNull(bundle)
        assertSame("the imported bundle must be the store's active one", bundle, fixture.store.active())
        assertEquals("the assets are read from the models-root source under asr/", roles.toSet(),
            fixture.source.opened.map { it.removePrefix("asr/").removeSuffix(".bin") }.toSet())
        assertEquals("the store re-verified every artifact's size and digest",
            roles.toSet(), bundle.validatedPaths().keys)
    }

    @Test
    fun aMissingAssetIsEngineMissingAndActivatesNothing() {
        val fixture = fixture(contents - "vad")

        val thrown = runCatching { fixture.provisioning.import() }.exceptionOrNull()

        assertTrue("a missing bundled asset must be ENGINE_MISSING, was $thrown",
            thrown is ContractError.CapabilityMissing && thrown.message == "ENGINE_MISSING")
        assertNull("a refused import must not activate anything", fixture.store.active())
        assertFalse("a refused import must not leave an active pointer",
            Files.exists(fixture.storeRoot.resolve("active")))
    }

    @Test
    fun bytesThatDoNotMatchTheManifestAreRefusedAndActivateNothing() {
        // The store itself re-checks sizes and digests, so an asset source that silently serves different bytes
        // must not be able to activate a bundle either.
        val wrong = contents.toMutableMap()
        wrong["decoder"] = "not-the-declared-bytes".toByteArray()
        val fixture = fixture(wrong)

        val thrown = runCatching { fixture.provisioning.import() }.exceptionOrNull()

        assertTrue("a mismatching asset must be ENGINE_MISSING, was $thrown",
            thrown is ContractError.CapabilityMissing && thrown.message == "ENGINE_MISSING")
        assertNull("a refused import must not activate anything", fixture.store.active())
        assertFalse("a refused import must not leave an active pointer",
            Files.exists(fixture.storeRoot.resolve("active")))
    }

    @Test
    fun aNoSpaceFailureIsPropagatedInsteadOfBecomingEngineMissing() {
        // The provisioner's only signal for MODELS_NO_SPACE is the cause chain; wrapping a full disk in the generic
        // ENGINE_MISSING would relabel it as MODELS_ASR_FAILED. Every other refusal keeps the generic mapping.
        val root = temporary.newFolder("engine-models-nospace").toPath()
        val store = EngineModelStore(root)
        val noSpace = IOException("No space left on device")
        val source = ModelAssetSource { throw noSpace }

        val thrown = runCatching { AssetEngineBundleProvisioning(source, store, manifest()).import() }
            .exceptionOrNull()

        assertSame("a full disk must stay recognizable to the caller", noSpace, thrown)
        assertNull("a refused import must not activate anything", store.active())
        assertFalse("a refused import must not leave an active pointer", Files.exists(root.resolve("active")))
    }
}
