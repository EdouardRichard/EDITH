package com.sheen.rokid.phone.storage

import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelProfileProjection
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.storage.ProfileRepository

/**
 * 0.52 design §5.8: [ModelProfile] is derived from the catalog instead of being persisted on its own.
 *
 * The adapter keeps the existing [ProfileRepository] contract for the plugin and request-guard wiring. Providers
 * without a `secretRef` are skipped rather than given a fabricated credential, and the write methods are refused
 * because the catalog is the only model-configuration owner.
 */
class CatalogProfileRepository(private val catalog: ModelCatalogRepository) : ProfileRepository {
    override fun list(): List<ModelProfile> = catalog.catalog().providers
        .filter { it.secretHandleId != null }
        .flatMap { provider -> provider.models.map { ModelProfileProjection.toProfile(provider, it) } }

    override fun get(id: String): ModelProfile? {
        val identity = ModelProfileProjection.parseProfileId(id) ?: return null
        val provider = catalog.catalog().providers.firstOrNull { it.id == identity.first } ?: return null
        if (provider.secretHandleId == null) return null
        val model = provider.models.firstOrNull { it.id == identity.second } ?: return null
        return ModelProfileProjection.toProfile(provider, model)
    }

    override fun save(profile: ModelProfile): ModelProfile =
        throw UnsupportedOperationException("Catalog owns model configuration")

    override fun delete(id: String) {
        throw UnsupportedOperationException("Catalog owns model configuration")
    }

    override fun verify(id: String, result: CapabilityVerification) = catalog.verify(id, result)

    override fun listenChanges(listener: (String) -> Unit): CancellationHandle =
        catalog.listenChanges { ids -> ids.forEach(listener) }
}
