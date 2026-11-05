package com.sheen.rokid.model

/**
 * 0.52 C1: the one protocol vocabulary shared by the catalog UI, the persisted catalog and the runtime adapters.
 *
 * Before this type the catalog defaulted to `"OPENAI_RESPONSES"` while the Responses adapter's support id was
 * `"DEEPSEEK_RESPONSES"`, so a provider created through the UI failed `CapabilityGate` before a single byte left the
 * phone. The id below is the **protocol family** name (matching the plan/design §5.2 and `Provider.protocolId`), not the
 * vendor name.
 *
 * [LEGACY_DEEPSEEK_RESPONSES] is a documented, permanent alias: pre-0.5.2 debug profiles (`dev-model.properties`) and any
 * catalog blob written by an earlier 0.5.2 build carry the vendor-named id. They must keep working unchanged, so
 * [canonical] translates that one spelling everywhere the runtime compares protocol ids; nothing rewrites the persisted
 * value.
 */
object ProtocolIds {
    /** OpenAI Responses API (the catalog default; the DeepSeek adapter implements this family). */
    const val OPENAI_RESPONSES = "OPENAI_RESPONSES"

    /** OpenAI Chat Completions API. */
    const val OPENAI_CHAT_COMPLETIONS = "OPENAI_CHAT_COMPLETIONS"

    /**
     * The vendor-named Responses id persisted by pre-0.5.2 debug profiles and earlier 0.5.2 catalogs. Accepted as an
     * alias forever; new catalogs never write it.
     */
    const val LEGACY_DEEPSEEK_RESPONSES = "DEEPSEEK_RESPONSES"

    /** What the provider form selects when the user does not open 高级. */
    const val DEFAULT = OPENAI_RESPONSES

    /** Maps every accepted spelling to the family id the adapters declare; unknown ids pass through unchanged. */
    fun canonical(id: String): String = when (id) {
        LEGACY_DEEPSEEK_RESPONSES -> OPENAI_RESPONSES
        else -> id
    }

    fun isResponses(id: String): Boolean = canonical(id) == OPENAI_RESPONSES

    fun isChatCompletions(id: String): Boolean = canonical(id) == OPENAI_CHAT_COMPLETIONS
}
