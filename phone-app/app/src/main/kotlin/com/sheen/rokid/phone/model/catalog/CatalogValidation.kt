package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.ModelEndpoint

/**
 * 0.52 design §§5.2/5.5/7.3: the field-level rules the catalog saves run.
 *
 * Every function returns a user-facing Chinese message, or `null` when the value is acceptable. The base-URL rule
 * deliberately delegates to [ModelEndpoint.models] so the catalog and the runtime endpoint cannot disagree about which
 * URLs are legal, and its message is fixed text: a rejected URL is never echoed back (a bad URL can carry a credential
 * in its userinfo or query).
 */
object CatalogValidation {
    /** 0.52 design §5.2: the reserve between the usable window and the input budget. */
    const val SAFETY_TOKENS = 256

    /** 0.52 design §5.2: provider names are 1-40 characters. */
    const val MAX_PROVIDER_NAME = 40

    fun providerNameError(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "名称不能为空"
            trimmed.length > MAX_PROVIDER_NAME -> "名称不能超过 $MAX_PROVIDER_NAME 个字符"
            name.any { it == '\n' || it == '\r' } -> "名称不能包含换行"
            else -> null
        }
    }

    fun baseUrlError(baseUrl: String, allowLocalHttp: Boolean = false): String? =
        try {
            ModelEndpoint.models(baseUrl, allowLocalHttp)
            null
        } catch (_: ContractError.ConfigurationRejected) {
            "Base URL 必须是 HTTPS 地址，且不能包含账号、查询参数或片段"
        }

    fun modelIdError(id: String): String? = when {
        id.isBlank() -> "模型 ID 不能为空"
        id.any { it.isISOControl() } -> "模型 ID 不能包含控制字符"
        else -> null
    }

    /**
     * A manual row's display name must satisfy the [ModelEntry] invariant (non-blank, no ISO control characters).
     * Gateway-provided names are filtered by `ModelCatalogMerge.sanitizedDisplayName` instead; a value the user
     * typed is reported as a field error rather than silently rewritten.
     */
    fun displayNameError(displayName: String): String? = when {
        displayName.isBlank() -> "显示名不能为空"
        displayName.any { it.isISOControl() } -> "显示名不能包含控制字符"
        else -> null
    }

    fun budgetError(contextWindowTokens: Int, maxOutputTokens: Int): String? = when {
        contextWindowTokens <= 0 || maxOutputTokens <= 0 -> "上下文窗口和最大输出必须大于 0"
        maxOutputTokens >= contextWindowTokens -> "最大输出必须小于上下文窗口"
        contextWindowTokens - maxOutputTokens <= SAFETY_TOKENS ->
            "上下文窗口减去最大输出和安全余量后必须大于 0"
        else -> null
    }
}
