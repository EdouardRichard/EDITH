package com.sheen.rokid.phone.storage

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelProfileProjection
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.SecretHandle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The encrypted `model-catalog` v2 repository (0.52 design §5.3, rulings R15/R16/R17).
 *
 * The blob write is the only commit point: a credential is staged before the write, the old handle is revoked only
 * after it succeeded, and any failure revokes the staged handle and leaves the previous catalog untouched. All
 * read-modify-write operations hold one lock, so concurrent saves cannot interleave.
 */
class AndroidModelCatalogRepository(
    private val blobs: EncryptedBlobStore,
    private val credentials: CatalogCredentialStore,
) : ModelCatalogRepository {
    private val lock = Any()
    private class Subscription(val callback: (Set<String>) -> Unit) {
        val active = AtomicBoolean(true)
    }
    private val changes = linkedSetOf<Subscription>()

    override fun catalog(): ModelCatalog = synchronized(lock) { load() }

    override fun saveProvider(provider: Provider, newSecret: CharArray?): Provider {
        var staged: SecretHandle? = null
        var committed = false
        var oldHandleId: String? = null
        val saved: Provider
        val affected: Set<String>
        val deliveries: List<Subscription>
        try {
            synchronized(lock) {
                val previous = load()
                val index = previous.providers.indexOfFirst { it.id == provider.id }
                val old = previous.providers.getOrNull(index)
                oldHandleId = old?.secretHandleId
                if (newSecret != null) staged = credentials.stage(newSecret)
                val revision = if (old == null) 1L else Math.addExact(old.revision, 1L)
                saved = provider.copy(
                    secretHandleId = staged?.id ?: oldHandleId,
                    revision = revision,
                    // A revision bump invalidates every verification bound to the previous revision (design §5.2).
                    models = if (old == null) {
                        provider.models.toList()
                    } else {
                        provider.models.map { it.copy(verifications = emptyList()) }
                    },
                )
                val providers = if (index < 0) {
                    previous.providers + saved
                } else {
                    previous.providers.toMutableList().also { it[index] = saved }
                }
                ModelCatalogJson.write(blobs, previous.copy(providers = providers))
                committed = true
                affected = affectedIds(old) + affectedIds(saved)
                deliveries = changes.toList()
            }
        } catch (error: Exception) {
            if (!committed) staged?.let { runCatching { credentials.revoke(it) } }
            throw storageFailure(error)
        }
        val stagedHandle = staged
        if (stagedHandle != null) oldHandleId?.takeIf { it != stagedHandle.id }?.let(::revokeQuietly)
        notify(affected, deliveries)
        return saved
    }

    override fun deleteProvider(providerId: String) {
        var removed: Provider? = null
        val deliveries: List<Subscription>
        synchronized(lock) {
            val previous = load()
            removed = previous.providers.firstOrNull { it.id == providerId } ?: return
            val wasActive = previous.activeProviderId == providerId
            ModelCatalogJson.write(
                blobs,
                previous.copy(
                    activeProviderId = if (wasActive) null else previous.activeProviderId,
                    selectedModelId = if (wasActive) null else previous.selectedModelId,
                    providers = previous.providers.filterNot { it.id == providerId },
                    lastSelectedModelIdByProvider = previous.lastSelectedModelIdByProvider - providerId,
                ),
            )
            deliveries = changes.toList()
        }
        val old = removed ?: return
        old.secretHandleId?.let(::revokeQuietly)
        notify(affectedIds(old), deliveries)
    }

    override fun saveModels(
        providerId: String,
        models: List<ModelEntry>,
        selectedModelId: String?,
        lastSyncedAtEpochMillis: Long?,
    ): Provider {
        val saved: Provider
        val affected: Set<String>
        val deliveries: List<Subscription>
        synchronized(lock) {
            val previous = load()
            val index = previous.providers.indexOfFirst { it.id == providerId }
            if (index < 0) throw ContractError.ConfigurationRejected("提供商不存在")
            val old = previous.providers[index]
            val changed = modelsContentChanged(old.models, models)
            val nextModels = if (changed) {
                // Evidence is bound to the provider revision; a content change makes all of it stale.
                models.map { it.copy(verifications = emptyList()) }
            } else {
                val evidence = old.models.associateBy { it.id }
                models.map { model -> model.copy(verifications = evidence[model.id]?.verifications ?: model.verifications) }
            }
            saved = old.copy(
                models = nextModels,
                revision = if (changed) Math.addExact(old.revision, 1L) else old.revision,
                lastSyncedAtEpochMillis = lastSyncedAtEpochMillis ?: old.lastSyncedAtEpochMillis,
            )
            var next = previous.copy(providers = previous.providers.toMutableList().also { it[index] = saved })
            if (selectedModelId != null) {
                next = next.copy(
                    lastSelectedModelIdByProvider = next.lastSelectedModelIdByProvider + (providerId to selectedModelId),
                )
            }
            if (next.activeProviderId == providerId) next = next.copy(selectedModelId = selectedModelId)
            ModelCatalogJson.write(blobs, next)
            affected = when {
                changed -> affectedIds(old) + affectedIds(saved)
                // A selection-only save invalidates nothing; report exactly the old and new derived profiles.
                previous.activeProviderId == providerId && previous.selectedModelId != selectedModelId ->
                    linkedSetOf<String>().apply {
                        previous.selectedModelId?.let { add(ModelProfileProjection.profileId(providerId, it)) }
                        selectedModelId?.let { add(ModelProfileProjection.profileId(providerId, it)) }
                    }
                else -> emptySet()
            }
            deliveries = changes.toList()
        }
        notify(affected, deliveries)
        return saved
    }

    override fun selectModel(modelId: String) {
        val affected: Set<String>
        val deliveries: List<Subscription>
        synchronized(lock) {
            val previous = load()
            val provider = previous.activeProvider()
                ?: throw ContractError.ConfigurationRejected("尚未选择提供商")
            if (provider.models.none { it.id == modelId }) {
                throw ContractError.ConfigurationRejected("模型不存在")
            }
            val oldModelId = previous.selectedModelId
            if (oldModelId == modelId) return
            ModelCatalogJson.write(
                blobs,
                previous.copy(
                    selectedModelId = modelId,
                    lastSelectedModelIdByProvider = previous.lastSelectedModelIdByProvider + (provider.id to modelId),
                ),
            )
            affected = linkedSetOf<String>().apply {
                oldModelId?.let { add(ModelProfileProjection.profileId(provider.id, it)) }
                add(ModelProfileProjection.profileId(provider.id, modelId))
            }
            deliveries = changes.toList()
        }
        notify(affected, deliveries)
    }

    override fun verify(profileId: String, result: CapabilityVerification) {
        synchronized(lock) {
            val identity = ModelProfileProjection.parseProfileId(profileId) ?: return
            val previous = load()
            val provider = previous.providers.firstOrNull { it.id == identity.first } ?: return
            if (result.profileRevision != provider.revision) return
            val modelIndex = provider.models.indexOfFirst { it.id == identity.second }
            if (modelIndex < 0) return
            val model = provider.models[modelIndex]
            val updated = provider.copy(
                models = provider.models.toMutableList().also {
                    it[modelIndex] = model.copy(verifications = model.verifications + result)
                },
            )
            ModelCatalogJson.write(
                blobs,
                previous.copy(providers = previous.providers.map { if (it.id == provider.id) updated else it }),
            )
        }
    }

    override fun providerSecret(providerId: String): SecretHandle? = synchronized(lock) {
        load().providers.firstOrNull { it.id == providerId }?.secretHandleId?.let(::opaqueSecret)
    }

    override fun isCredentialReferenced(handleId: String): Boolean = synchronized(lock) {
        load().providers.any { it.secretHandleId == handleId }
    }

    override fun reset() {
        val deliveries: List<Subscription>
        val derived: Set<String>
        synchronized(lock) {
            derived = runCatching { derivedIds(load()) }.getOrDefault(emptySet())
            deliveries = changes.toList()
        }
        // R17: best-effort notification, then the blob is the only thing deleted.
        runCatching { notify(derived, deliveries) }
        synchronized(lock) {
            changes.clear()
            safely { blobs.delete(ModelCatalogJson.BLOB_ID) }
        }
    }

    override fun listenChanges(listener: (Set<String>) -> Unit): CancellationHandle = synchronized(lock) {
        val entry = Subscription(listener)
        changes += entry
        object : CancellationHandle {
            override fun cancel() {
                entry.active.set(false)
                synchronized(lock) { changes -= entry }
            }
        }
    }

    private fun load(): ModelCatalog = ModelCatalogJson.read(blobs)

    /** The derived profile ids of [provider]; a provider without a credential has no usable profiles. */
    private fun affectedIds(provider: Provider?): Set<String> =
        if (provider?.secretHandleId == null) emptySet() else derivedIdsOf(provider)

    private fun derivedIds(catalog: ModelCatalog): Set<String> =
        catalog.providers.filter { it.secretHandleId != null }
            .flatMapTo(linkedSetOf()) { derivedIdsOf(it) }

    private fun derivedIdsOf(provider: Provider): Set<String> =
        provider.models.mapTo(linkedSetOf()) { ModelProfileProjection.profileId(provider.id, it.id) }

    private fun modelsContentChanged(previous: List<ModelEntry>, next: List<ModelEntry>): Boolean {
        if (previous.size != next.size) return true
        val previousById = previous.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        if (previousById.keys != nextById.keys) return true
        return nextById.any { (id, model) -> contentOf(model) != contentOf(previousById.getValue(id)) }
    }

    /** Sync metadata and evidence are not model content: they change without invalidating a request. */
    private fun contentOf(entry: ModelEntry): ModelEntry =
        entry.copy(missing = false, lastSeenAtEpochMillis = null, verifications = emptyList())

    private fun revokeQuietly(handleId: String) {
        runCatching { credentials.revoke(opaqueSecret(handleId)) }
    }

    private fun notify(ids: Set<String>, deliveries: List<Subscription>) {
        if (ids.isEmpty()) return
        var failure: Throwable? = null
        val delivered = ids.toSet()
        deliveries.forEach { entry ->
            if (entry.active.get()) {
                try {
                    entry.callback(delivered)
                } catch (error: Throwable) {
                    val current = failure
                    if (current == null) failure = error else if (current !== error) current.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun storageFailure(error: Exception): Nothing =
        if (error is ContractError.ConfigurationRejected) throw error else unavailable()
}

/** The production credential store: the repository sees handles only, never key material. */
class KeystoreCatalogCredentialStore(private val vault: KeystoreVault) : CatalogCredentialStore {
    override fun stage(value: CharArray): SecretHandle = vault.stage(value)
    override fun revoke(handle: SecretHandle) = vault.revoke(handle)
}
