package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.plugin.InputModality

/**
 * The capability fallbacks a model gets when the gateway response does not declare a field.
 *
 * [KnownModelDefaults] is step 2 of the 0.52 design §5.5 degradation order: the gateway metadata is step 1; the
 * conservative default is step 3. It is a starting point, never a whitelist - any field stays user-overridable.
 */
data class ModelDefaults(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val inputs: Set<InputModality>,
    val thinkingSupported: Boolean = false,
)

object KnownModelDefaults {
    // docs/V0.52/appendix/gateway-api-research.md §1: DeepSeek V4.1-Flash is 1M context / 384K output, text + image;
    // V4-Pro shares the window pair but is text only. 384K is 384 * 1024 = 393_216 tokens.
    private val DEEPSEEK_WINDOW = 1_048_576
    private val DEEPSEEK_OUTPUT = 393_216

    private val KNOWN: Map<String, ModelDefaults> = mapOf(
        "deepseek-flash" to ModelDefaults(
            contextWindowTokens = DEEPSEEK_WINDOW,
            maxOutputTokens = DEEPSEEK_OUTPUT,
            inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
            thinkingSupported = true,
        ),
        "deepseek-v4-pro" to ModelDefaults(
            contextWindowTokens = DEEPSEEK_WINDOW,
            maxOutputTokens = DEEPSEEK_OUTPUT,
            inputs = setOf(InputModality.TEXT),
            thinkingSupported = true,
        ),
    )

    /** Exact, case-insensitive id match. An unmatched model is not an error: it falls through to the defaults. */
    fun forId(id: String): ModelDefaults? = KNOWN[id.trim().lowercase()]

    /**
     * 0.52 design §5.5: 128K input / 8K output / text + image. The image capability is a product default, not a
     * verified capability; the UI must mark it unverified until the image probe passes.
     */
    fun conservativeDefaults(): ModelDefaults = ModelDefaults(
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        thinkingSupported = false,
    )
}
