package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort

/** 0.52 design §5.5: the refresh summary the UI turns into "新增 3 · 更新 2 · 未变 5 · 网关已移除 1". */
data class RefreshCounts(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val missing: Int = 0,
    val truncated: Boolean = false,
)

data class CatalogRefresh(val catalog: ModelCatalog, val counts: RefreshCounts)

/**
 * 0.52 design §5.5: the field-level protection rules for a gateway refresh and for "restore gateway defaults".
 *
 * The merge itself is pure: it never deletes a model, never touches a manual one beyond clearing `missing`, and never
 * writes a row whose budget cannot be projected. Persisting the result and bumping the provider revision belong to the
 * repository (Task 2/5).
 */
object ModelCatalogMerge {
    fun applyRefresh(
        catalog: ModelCatalog,
        providerId: String,
        gateway: List<GatewayModel>,
        nowEpochMillis: Long,
    ): CatalogRefresh {
        require(nowEpochMillis >= 0)
        val provider = catalog.providers.firstOrNull { it.id == providerId }
            ?: throw ContractError.ConfigurationRejected("提供商不存在")

        // Gateway ids are network input: skip any row the catalog itself could not store (blank or control characters).
        val offered = gateway.filter { CatalogValidation.modelIdError(it.id) == null }.distinctBy { it.id }
        val offeredById = offered.associateBy { it.id }
        val existingById = provider.models.associateBy { it.id }

        var added = 0
        var updated = 0
        var unchanged = 0
        var missing = 0
        val models = ArrayList<ModelEntry>(offered.size + provider.models.size)

        for (model in offered) {
            val previous = existingById[model.id]
            if (previous == null) {
                models += newEntry(model, nowEpochMillis)
                added += 1
            } else {
                val refreshed = refreshedEntry(previous, model, nowEpochMillis)
                models += refreshed
                if (sameContent(previous, refreshed)) unchanged += 1 else updated += 1
            }
        }
        for (previous in provider.models) {
            if (previous.id !in offeredById) {
                models += previous.copy(missing = true)
                missing += 1
            }
        }

        val merged = catalog.copy(
            providers = catalog.providers.map {
                if (it.id == providerId) provider.copy(models = models) else it
            },
        )
        return CatalogRefresh(merged, RefreshCounts(added = added, updated = updated, unchanged = unchanged, missing = missing))
    }

    /**
     * Reapplies the current gateway metadata (then the known table, then the conservative default) after clearing one
     * model's overrides. Unlike a refresh this does not clamp: metadata that cannot fit the safety reserve is reported
     * as a configuration rejection instead of silently adjusted.
     */
    fun restoreDefaults(
        catalog: ModelCatalog,
        providerId: String,
        modelId: String,
        gatewayForModel: GatewayModel?,
    ): ModelCatalog {
        val providerIndex = catalog.providers.indexOfFirst { it.id == providerId }
        if (providerIndex < 0) throw ContractError.ConfigurationRejected("提供商不存在")
        val provider = catalog.providers[providerIndex]
        val modelIndex = provider.models.indexOfFirst { it.id == modelId }
        if (modelIndex < 0) throw ContractError.ConfigurationRejected("模型不存在")
        val previous = provider.models[modelIndex]

        val known = KnownModelDefaults.forId(modelId)
        val conservative = KnownModelDefaults.conservativeDefaults()
        val window = gatewayForModel?.contextWindowTokens?.takeIf { it > 0 }
            ?: known?.contextWindowTokens
            ?: conservative.contextWindowTokens
        val output = gatewayForModel?.maxOutputTokens?.takeIf { it > 0 }
            ?: known?.maxOutputTokens
            ?: conservative.maxOutputTokens
        val inputs = gatewayForModel?.inputs?.takeIf { it.isNotEmpty() }?.plus(InputModality.TEXT)
            ?: known?.inputs
            ?: conservative.inputs

        CatalogValidation.budgetError(window, output)?.let { throw ContractError.ConfigurationRejected(it) }

        val restored = previous.copy(
            displayName = sanitizedDisplayName(gatewayForModel?.displayName) ?: modelId,
            contextWindowTokens = window,
            maxOutputTokens = output,
            inputs = inputs,
            thinkingSupported = previous.thinkingSupported ||
                gatewayForModel?.thinkingSupported == true ||
                known?.thinkingSupported == true,
            overrides = emptySet(),
        )
        val models = provider.models.toMutableList().also { it[modelIndex] = restored }
        val providers = catalog.providers.toMutableList().also { it[providerIndex] = provider.copy(models = models) }
        return catalog.copy(providers = providers)
    }

    /** 0.52 design §5.5: gateway metadata → built-in table → conservative default, for a brand-new model. */
    private fun newEntry(gateway: GatewayModel, nowEpochMillis: Long): ModelEntry {
        val known = KnownModelDefaults.forId(gateway.id)
        val conservative = KnownModelDefaults.conservativeDefaults()
        val window = gateway.contextWindowTokens?.takeIf { it > CatalogValidation.SAFETY_TOKENS + 1 }
            ?: known?.contextWindowTokens
            ?: conservative.contextWindowTokens
        val requestedOutput = gateway.maxOutputTokens?.takeIf { it > 0 }
            ?: known?.maxOutputTokens
            ?: conservative.maxOutputTokens
        val output = minOf(requestedOutput, window - CatalogValidation.SAFETY_TOKENS - 1)
        val inputs = gateway.inputs?.takeIf { it.isNotEmpty() }?.plus(InputModality.TEXT)
            ?: known?.inputs
            ?: conservative.inputs
        return ModelEntry(
            id = gateway.id,
            displayName = sanitizedDisplayName(gateway.displayName) ?: gateway.id,
            contextWindowTokens = window,
            maxOutputTokens = output,
            inputs = inputs,
            outputs = setOf(OutputModality.TEXT),
            thinkingEnabled = false,
            thinkingEffort = ThinkingEffort.LOW,
            thinkingSupported = gateway.thinkingSupported || known?.thinkingSupported == true,
            origin = ModelOrigin.GATEWAY,
            lastSeenAtEpochMillis = nowEpochMillis,
        )
    }

    /** 0.52 design §5.5: update only fields the user did not override; manual rows are never overwritten. */
    private fun refreshedEntry(previous: ModelEntry, gateway: GatewayModel, nowEpochMillis: Long): ModelEntry {
        if (previous.origin == ModelOrigin.MANUAL) return previous.copy(missing = false)

        var window = previous.contextWindowTokens
        var output = previous.maxOutputTokens
        if (ModelField.CONTEXT_WINDOW_TOKENS !in previous.overrides) {
            gateway.contextWindowTokens?.takeIf { it > 0 }?.let { window = it }
        }
        if (ModelField.MAX_OUTPUT_TOKENS !in previous.overrides) {
            gateway.maxOutputTokens?.takeIf { it > 0 }?.let { output = it }
        }
        if (CatalogValidation.budgetError(window, output) != null) {
            val clamped = window - CatalogValidation.SAFETY_TOKENS - 1
            if (clamped > 0) output = clamped
        }
        if (CatalogValidation.budgetError(window, output) != null) {
            // Still illegal: keep the old values (the row was valid before) rather than save an unprojectable row.
            return previous.copy(missing = false, lastSeenAtEpochMillis = nowEpochMillis)
        }

        val displayName = if (ModelField.DISPLAY_NAME in previous.overrides) {
            previous.displayName
        } else {
            sanitizedDisplayName(gateway.displayName) ?: previous.displayName
        }
        val inputs = if (ModelField.INPUTS in previous.overrides) {
            previous.inputs
        } else {
            gateway.inputs?.takeIf { it.isNotEmpty() }?.plus(InputModality.TEXT) ?: previous.inputs
        }
        return previous.copy(
            displayName = displayName,
            contextWindowTokens = window,
            maxOutputTokens = output,
            inputs = inputs,
            thinkingSupported = previous.thinkingSupported || gateway.thinkingSupported,
            missing = false,
            lastSeenAtEpochMillis = nowEpochMillis,
        )
    }

    private fun sameContent(a: ModelEntry, b: ModelEntry): Boolean =
        a.copy(lastSeenAtEpochMillis = null) == b.copy(lastSeenAtEpochMillis = null)

    private fun sanitizedDisplayName(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it.none { character -> character.isISOControl() } }
}
