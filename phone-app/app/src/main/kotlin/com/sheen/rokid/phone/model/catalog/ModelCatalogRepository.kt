package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.SecretHandle

/**
 * 0.52 design §5.3: the encrypted catalog is the only persisted model configuration.
 *
 * Every mutation is a read-modify-write of one atomic blob, so a failed write leaves the previous catalog untouched.
 * [Provider.revision] is the request guard: it changes when any provider or model content changes (selection changes do
 * not), and each change is published as the affected derived profile ids (`providerId:modelId`) through
 * [listenChanges].
 */
interface ModelCatalogRepository {
    /** The stored catalog. A missing blob is an empty catalog; an unreadable or unsupported one is rejected. */
    fun catalog(): ModelCatalog

    /**
     * Creates or edits one provider. A new provider starts at revision 1; an edit keeps the stored credential handle when
     * [newSecret] is null, stages a replacement first and revokes the old handle only after the blob write committed.
     */
    fun saveProvider(provider: Provider, newSecret: CharArray? = null): Provider

    /** Deletes one provider, its selection history, and revokes its credential. */
    fun deleteProvider(providerId: String)

    /**
     * Replaces one provider's model list (gateway refresh or UI edit) and, when the provider is active, its selection.
     * Model content changes bump the revision and clear every verification bound to the previous revision.
     */
    fun saveModels(
        providerId: String,
        models: List<ModelEntry>,
        selectedModelId: String?,
        lastSyncedAtEpochMillis: Long? = null,
    ): Provider

    /** Selects [modelId] inside the active provider without bumping the revision. */
    fun selectModel(modelId: String)

    /**
     * Switches the active provider without bumping any provider revision. The selection is restored from
     * [ModelCatalog.lastSelectedModelIdByProvider] when that model still exists in the provider, otherwise the first
     * image-capable model, otherwise the first model, otherwise nothing. Reports the old and new derived profile ids.
     */
    fun setActiveProvider(providerId: String)

    /** Appends evidence only when [result] was produced for the provider's current revision. */
    fun verify(profileId: String, result: CapabilityVerification)

    /** The stored handle id rebuilt as a [SecretHandle], or null when the provider has no credential. Never reads the key. */
    fun providerSecret(providerId: String): SecretHandle?

    /** The vault authority predicate (ruling R13): true while any provider's `secretRef` is [handleId]. */
    fun isCredentialReferenced(handleId: String): Boolean

    /** Deletes only the catalog blob after a best-effort notification, so in-flight requests can cancel. */
    fun reset()

    /** Reports the derived profile ids affected by a successful mutation. */
    fun listenChanges(listener: (Set<String>) -> Unit): CancellationHandle
}

/**
 * The credential side of the catalog. The repository owns the handle lifecycle; [stage] takes ownership of the caller's
 * mutable key buffer and must zero it, and the staged handle becomes reachable only once the catalog blob commits
 * (ruling R15).
 */
interface CatalogCredentialStore {
    fun stage(value: CharArray): SecretHandle
    fun revoke(handle: SecretHandle)
}
