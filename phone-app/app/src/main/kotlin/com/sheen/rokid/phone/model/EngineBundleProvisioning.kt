package com.sheen.rokid.phone.model

import com.sheen.rokid.audio.android.EngineManifestLoader
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.contracts.ContractError
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Imports a locally produced engine bundle (P2/T17) into the App's model store.
 *
 * The store's own import re-checks sizes and digests while it copies, and this seam verifies the **bundle side**
 * before a single byte is copied, because a bundle is untrusted input: it can be replaced, truncated, or made to
 * point outside its own directory. The rule it exists to keep is that a bundle either verifies completely or is
 * refused as `ENGINE_MISSING` - never silently accepted, and never a partial activation.
 *
 * Refusals (all `ContractError.CapabilityMissing("ENGINE_MISSING")`):
 * - no readable `engine-manifest.json` in [importBundle]'s root, or a manifest the bundled loader rejects;
 * - an artifact whose path is not a regular file inside the bundle root (missing file, directory, or a link whose
 *   target leaves the root);
 * - an artifact whose size or SHA-256 differs from the manifest.
 */
class EngineBundleProvisioning(
    private val store: EngineModelStore,
    private val manifestLoader: EngineManifestLoader,
) {

    /**
     * Validates the bundle at [root] and activates it in the store.
     *
     * @return a description of the store's `active` pointer, for the caller's log or UI.
     * @throws ContractError.CapabilityMissing `ENGINE_MISSING` for any failed verification.
     */
    fun importBundle(root: File): String {
        val canonical = canonicalRoot(root)
        val manifest = loadManifest(canonical)
        manifest.artifacts.forEach { artifact -> verify(canonical, artifact.path, artifact.bytes, artifact.sha256) }
        val sources: Map<String, () -> InputStream> = manifest.artifacts.associate { artifact ->
            artifact.role to { Files.newInputStream(canonical.resolve(artifact.path)) }
        }
        val bundle = try {
            store.import(manifest, sources)
        } catch (refused: Exception) {
            refuse()
        }
        return "ACTIVE:${bundle.directory}"
    }

    private fun canonicalRoot(root: File): Path = try {
        root.toPath().toRealPath()
    } catch (unreadable: Exception) {
        refuse()
    }

    private fun loadManifest(canonical: Path) = try {
        val manifestFile = canonical.resolve(MANIFEST_FILE)
        if (!Files.isRegularFile(manifestFile, NOFOLLOW_LINKS)) refuse()
        Files.newInputStream(manifestFile).use(manifestLoader::load)
    } catch (refused: ContractError) {
        throw refused
    } catch (invalid: Exception) {
        refuse()
    }

    /** A bundle path is trusted only when it is a regular file whose fully resolved location is inside the root. */
    private fun verify(canonical: Path, path: String, bytes: Long, sha256: String) {
        val file = canonical.resolve(path)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) refuse()
        if (file.toRealPath().parent != canonical) refuse()
        if (Files.size(file) != bytes) refuse()
        if (!digest(file).equals(sha256, ignoreCase = true)) refuse()
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            } finally {
                buffer.fill(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun refuse(): Nothing = throw ContractError.CapabilityMissing("ENGINE_MISSING")

    private companion object {
        const val MANIFEST_FILE = "engine-manifest.json"
    }
}
