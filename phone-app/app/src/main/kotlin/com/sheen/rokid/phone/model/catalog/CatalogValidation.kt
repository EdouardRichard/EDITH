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

    /** 0.52 I7: `KeystoreVault.stage` admits at most this many characters. */
    const val MAX_API_KEY_CHARS = 8192

    const val API_KEY_REQUIRED = "API Key 不能为空"
    const val API_KEY_TOO_LONG = "API Key 过长"
    const val API_KEY_WHITESPACE = "API Key 不能包含空格"

    /**
     * 0.52 I7: one rule for the provider editor and the view model.
     *
     * A blank/whitespace-only value on **edit** means "keep the stored credential" (null), exactly like an empty field;
     * it is never forwarded to the vault where it would become a storage failure whose copy suggests resetting the
     * catalog. A whitespace-only value on create, an over-length value, or a value with embedded whitespace is a field
     * error. The caller owns zeroing the buffer.
     */
    fun apiKeyError(key: String?, editing: Boolean): String? =
        apiKeyError(key?.length ?: -1, editing) { key!![it] }

    /** CharArray overload: no secret copy is made just to validate it. */
    fun apiKeyError(key: CharArray?, editing: Boolean): String? =
        apiKeyError(key?.size ?: -1, editing) { key!![it] }

    private inline fun apiKeyError(length: Int, editing: Boolean, characterAt: (Int) -> Char): String? {
        if (length < 0) return if (editing) null else API_KEY_REQUIRED
        var allWhitespace = true
        var anyWhitespace = false
        for (index in 0 until length) {
            if (characterAt(index).isWhitespace()) anyWhitespace = true else allWhitespace = false
        }
        if (allWhitespace) return if (editing) null else API_KEY_REQUIRED
        if (length > MAX_API_KEY_CHARS) return API_KEY_TOO_LONG
        if (anyWhitespace) return API_KEY_WHITESPACE
        return null
    }

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
