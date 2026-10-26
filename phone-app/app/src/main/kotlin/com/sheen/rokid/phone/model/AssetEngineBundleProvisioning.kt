package com.sheen.rokid.phone.model

import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.ValidatedModelBundle
import com.sheen.rokid.contracts.ContractError
import java.io.InputStream

/**
 * Activates the ASR engine bundle the App itself ships under `assets/models/asr/`.
 *
 * This is the release path: the bytes are the APK's own, verified by the build against
 * `tools/scripts/p2/model-bundles.json`, and they are imported through the same [EngineModelStore] the debug drop
 * and the P2/T17 pipeline use - so an active bundle's provenance does not change the store's guarantees. The
 * [manifest] is the trusted ASR manifest (the bundled `asr/engine-manifest.json`, identical in content to the
 * `assets/engine-manifest.json` the composition already loads); it is passed in rather than read here so there is
 * exactly one trust source per process.
 *
 * Every refusal - a missing asset, an unreadable stream, or a size/digest mismatch the store's own import checks
 * catch - becomes `ContractError.CapabilityMissing("ENGINE_MISSING")`. Nothing outside `assets/models/asr/` is
 * ever read, and a refused import leaves the store without an active pointer.
 */
class AssetEngineBundleProvisioning(
    private val source: ModelAssetSource,
    private val store: EngineModelStore,
    private val manifest: EngineManifest,
) {

    /**
     * Imports every artifact the [manifest] declares from `asr/<artifact.path>` and activates the resulting bundle.
     *
     * @throws ContractError.CapabilityMissing `ENGINE_MISSING` for any missing, unreadable or mismatching asset.
     */
    fun import(): ValidatedModelBundle = try {
        // Lazy per-role streams: the store opens each source while it copies and re-checks the bytes it reads, so
        // an asset is touched only after its turn in the import and a missing one can never be skipped silently.
        val sources: Map<String, () -> InputStream> = manifest.artifacts.associate { artifact ->
            artifact.role to { source.open(ASSET_PREFIX + artifact.path) }
        }
        store.import(manifest, sources)
    } catch (_: Exception) {
        throw ContractError.CapabilityMissing("ENGINE_MISSING")
    }

    companion object {
        /** Directory under `assets/models/` the build writes the ASR artifacts and their manifest into. */
        const val ASSET_PREFIX = "asr/"
    }
}
