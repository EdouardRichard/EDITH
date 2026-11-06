package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.model.GatewayModelSnapshot
import com.sheen.rokid.model.ModelListError
import com.sheen.rokid.model.ModelListResult
import com.sheen.rokid.model.ModelListSource

/** 0.52 design §5.5: the result of one "refresh models" attempt, mapped to the UI's error/summary states. */
sealed interface RefreshOutcome {
    /** The merge counts; the UI renders "新增 3 · 更新 2 · 未变 5 · 网关已移除 1". */
    data class Success(val counts: RefreshCounts) : RefreshOutcome

    /** The gateway could not be read; the catalog was not touched. */
    data class Failed(val error: ModelListError) : RefreshOutcome

    /** The provider id is not in the catalog (deleted or stale UI state). */
    data object NoProvider : RefreshOutcome

    /** The provider has no credential; no request is attempted. */
    data object MissingSecret : RefreshOutcome
}

/**
 * 0.52 design §5.5: the orchestration half of a gateway refresh and of "restore gateway defaults".
 *
 * [refresh] is the only writer of gateway-fetched model rows. It consults [ModelCatalogMerge] for the field-level
 * protection rules, then persists the merged list through [ModelCatalogRepository.saveModels]. A refresh never deletes a
 * model the gateway no longer lists (the merge flags it `missing`), never changes the selected model, and only reports
 * counts: the user-visible summary belongs to the UI. A failed fetch ([RefreshOutcome.Failed]) leaves the stored catalog
 * and its `lastSyncedAt` untouched; the clock is read only after a successful fetch.
 *
 * [restoreDefaults] reapplies the metadata from the last successful refresh of the same provider. The cache is
 * process-local and intentionally dropped when another provider is refreshed: stale metadata from a different gateway
 * must not decide this provider's defaults. Without a cache entry the merge falls back to the known table and then to
 * the conservative defaults (design §5.5).
 */
class ModelCatalogService(
    private val repository: ModelCatalogRepository,
    private val source: ModelListSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastGatewayProviderId: String? = null
    private val lastGatewayById = HashMap<String, GatewayModel>()

    /**
     * Fetches `GET {baseUrl}/models`, merges the result, and stores it. The returned selection is the provider's previous
     * one, so a refresh cannot drop the active model; the truncated flag from the transport is preserved in the counts.
     */
    fun refresh(providerId: String): RefreshOutcome {
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { it.id == providerId }
            ?: return RefreshOutcome.NoProvider
        val secret = repository.providerSecret(providerId)
            ?: return RefreshOutcome.MissingSecret

        val success = when (val result = source.fetch(provider.baseUrl, secret)) {
            is ModelListResult.Failure -> return RefreshOutcome.Failed(result.error)
            is ModelListResult.Success -> result
        }

        val gateway = success.models.map(GatewayModelSnapshot::toGatewayModel)
        val now = clock()
        val merged = ModelCatalogMerge.applyRefresh(catalog, providerId, gateway, now)
        val models = merged.catalog.providers.first { it.id == providerId }.models
        repository.saveModels(
            providerId = providerId,
            models = models,
            selectedModelId = catalog.lastSelectedModelIdByProvider[providerId],
            lastSyncedAtEpochMillis = now,
        )

        lastGatewayProviderId = providerId
        lastGatewayById.clear()
        // First occurrence wins, matching the merge's distinctBy rule for duplicate gateway ids.
        gateway.distinctBy { it.id }.associateByTo(lastGatewayById) { it.id }

        return RefreshOutcome.Success(merged.counts.copy(truncated = success.truncated))
    }

    /**
     * Clears one model's user overrides and reapplies the freshest metadata available (last gateway response → known
     * table → conservative defaults), then stores it. The sync timestamp is passed as null so restoring defaults is not
     * recorded as a successful sync. Unknown provider/model returns null; metadata that cannot be projected throws
     * [com.sheen.rokid.contracts.ContractError.ConfigurationRejected] and stores nothing.
     */
    fun restoreDefaults(providerId: String, modelId: String): ModelEntry? {
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { it.id == providerId } ?: return null
        if (provider.models.none { it.id == modelId }) return null

        val gatewayForModel = if (lastGatewayProviderId == providerId) lastGatewayById[modelId] else null
        val restored = ModelCatalogMerge.restoreDefaults(catalog, providerId, modelId, gatewayForModel)
        val models = restored.providers.first { it.id == providerId }.models
        val saved = repository.saveModels(
            providerId = providerId,
            models = models,
            selectedModelId = catalog.lastSelectedModelIdByProvider[providerId],
            lastSyncedAtEpochMillis = null,
        )
        return saved.models.first { it.id == modelId }
    }
}

/** Transport type → catalog type; the mapping stays private to the service so the two layers never leak into each other. */
private fun GatewayModelSnapshot.toGatewayModel(): GatewayModel = GatewayModel(
    id = id,
    displayName = displayName,
    contextWindowTokens = contextWindowTokens,
    maxOutputTokens = maxOutputTokens,
    inputs = inputs,
    ownedBy = ownedBy,
    thinkingSupported = thinkingSupported,
)
