package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle

/**
 * 0.52 design §5.8: the catalog is the persisted truth; [ModelProfile] is derived on the spot for the plugin contract.
 *
 * The derived id is `"{providerId}:{modelId}"`, so a selection change changes the profile identity without bumping a
 * revision, while any catalog content change bumps [Provider.revision] and cancels in-flight requests through the
 * existing request guard.
 */
object ModelProfileProjection {
    private const val SEPARATOR = ':'

    fun profileId(providerId: String, modelId: String): String = "$providerId$SEPARATOR$modelId"

    /** Splits on the first colon only: a model id may itself contain colons. A malformed value has no pair. */
    fun parseProfileId(profileId: String): Pair<String, String>? {
        val separator = profileId.indexOf(SEPARATOR)
        if (separator <= 0 || separator == profileId.lastIndex) return null
        val providerId = profileId.substring(0, separator)
        val modelId = profileId.substring(separator + 1)
        if (providerId.isBlank() || modelId.isBlank() || modelId.any { it.isISOControl() }) return null
        return providerId to modelId
    }

    fun toProfile(provider: Provider, entry: ModelEntry): ModelProfile {
        val secretId = provider.secretHandleId
            ?: throw ContractError.ConfigurationRejected("缺少 API Key")
        val safetyTokens = CatalogValidation.SAFETY_TOKENS
        val inputBudgetTokens = entry.contextWindowTokens - entry.maxOutputTokens - safetyTokens
        if (inputBudgetTokens <= 0) throw ContractError.ConfigurationRejected("模型的上下文预算无效")

        return ModelProfile(
            id = profileId(provider.id, entry.id),
            revision = provider.revision,
            name = entry.displayName,
            service = provider.name,
            baseUrl = provider.baseUrl,
            protocolId = provider.protocolId,
            model = entry.id,
            secret = object : SecretHandle {
                override val id = secretId
                override fun toString() = "SecretHandle(redacted)"
            },
            usableWindowTokens = entry.contextWindowTokens,
            inputBudgetTokens = inputBudgetTokens,
            maxOutputTokens = entry.maxOutputTokens,
            safetyTokens = safetyTokens,
            timeoutMillis = provider.timeoutMillis,
            inputs = entry.inputs,
            outputs = setOf(OutputModality.TEXT),
            verifications = entry.verifications,
            thinkingEnabled = entry.thinkingEnabled,
            thinkingEffort = entry.thinkingEffort,
            // R21: the adapter gate is a projection of the catalog capability, never a name/vendor heuristic.
            thinkingSupported = entry.thinkingSupported,
        )
    }
}
