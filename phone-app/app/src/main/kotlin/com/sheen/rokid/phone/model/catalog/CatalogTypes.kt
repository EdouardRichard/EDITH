package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.model.ProtocolIds
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort

/** 0.52 design §5.2: where a catalog row came from. A manual row is never refreshed from the gateway. */
enum class ModelOrigin { GATEWAY, MANUAL }

/** 0.52 design §5.5: the fields a user edit protects from the next gateway refresh. */
enum class ModelField { DISPLAY_NAME, CONTEXT_WINDOW_TOKENS, MAX_OUTPUT_TOKENS, INPUTS }

/**
 * One model in a provider's catalog (0.52 design §5.2).
 *
 * The budget identity is enforced here, not only on save: `contextWindowTokens - maxOutputTokens > SAFETY_TOKENS` is
 * what makes the runtime projection legal, and a row that cannot be projected is useless.
 */
data class ModelEntry(
    val id: String,
    val displayName: String = id,
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val inputs: Set<InputModality> = setOf(InputModality.TEXT),
    val outputs: Set<OutputModality> = setOf(OutputModality.TEXT),
    val thinkingEnabled: Boolean = false,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.LOW,
    val thinkingSupported: Boolean = false,
    val origin: ModelOrigin,
    val overrides: Set<ModelField> = emptySet(),
    val missing: Boolean = false,
    val lastSeenAtEpochMillis: Long? = null,
    val verifications: List<CapabilityVerification> = emptyList(),
) {
    init {
        CatalogValidation.modelIdError(id)?.let { throw IllegalArgumentException(it) }
        require(displayName.isNotBlank() && displayName.none { it.isISOControl() }) { "invalid display name" }
        CatalogValidation.budgetError(contextWindowTokens, maxOutputTokens)?.let {
            throw IllegalArgumentException(it)
        }
        require(InputModality.TEXT in inputs) { "text input is mandatory" }
        require(outputs.isNotEmpty()) { "at least one output modality is required" }
        require(lastSeenAtEpochMillis == null || lastSeenAtEpochMillis >= 0)
    }
}

/**
 * 0.52 design §5.2/§5.4: a configured provider. [secretHandleId] is the opaque Keystore handle, never the key itself;
 * `null` means the provider is not usable for requests yet.
 */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    // 0.52 C1: the catalog default and the runtime support id are the same single-source vocabulary (ProtocolIds).
    val protocolId: String = ProtocolIds.DEFAULT,
    val timeoutMillis: Long = 30_000,
    val secretHandleId: String? = null,
    val revision: Long = 1,
    val lastSyncedAtEpochMillis: Long? = null,
    val models: List<ModelEntry> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        CatalogValidation.providerNameError(name)?.let { throw IllegalArgumentException(it) }
        // The URL rule is format validation, not an invariant: a Debug build accepts a local HTTP endpoint through
        // `baseUrlError(..., allowLocalHttp = BuildConfig.DEBUG)`, and the persisted row must stay loadable afterwards.
        require(baseUrl.isNotBlank())
        require(protocolId.isNotBlank())
        require(timeoutMillis > 0)
        require(revision >= 0)
        require(secretHandleId == null || secretHandleId.isNotBlank())
        require(lastSyncedAtEpochMillis == null || lastSyncedAtEpochMillis >= 0)
    }
}

/**
 * The persisted catalog (0.52 design §5.2/§5.3). [selectedModelId] is only meaningful inside [activeProviderId];
 * [lastSelectedModelIdByProvider] is what a provider switch restores.
 */
data class ModelCatalog(
    val activeProviderId: String? = null,
    val selectedModelId: String? = null,
    val providers: List<Provider> = emptyList(),
    val lastSelectedModelIdByProvider: Map<String, String> = emptyMap(),
) {
    fun activeProvider(): Provider? = providers.firstOrNull { it.id == activeProviderId }

    fun activeModel(): ModelEntry? {
        val provider = activeProvider() ?: return null
        val selected = selectedModelId ?: return null
        return provider.models.firstOrNull { it.id == selected }
    }

    fun activeProfile(): ModelProfile? {
        val provider = activeProvider() ?: return null
        val model = activeModel() ?: return null
        if (provider.secretHandleId == null) return null
        return ModelProfileProjection.toProfile(provider, model)
    }
}

/**
 * One model as the gateway's `GET /models` describes it (0.52 design §5.4). Every capability is optional because only
 * `id` is universal; a missing field falls through the known table and then the conservative defaults.
 */
data class GatewayModel(
    val id: String,
    val displayName: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputs: Set<InputModality>? = null,
    val ownedBy: String? = null,
    val thinkingSupported: Boolean = false,
)
