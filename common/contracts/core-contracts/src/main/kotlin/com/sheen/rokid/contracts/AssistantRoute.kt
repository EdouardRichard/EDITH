package com.sheen.rokid.contracts

/**
 * Which of the assistant's two conversations something belongs to (spec §1.3 C3).
 *
 * The wake route and the proactive route keep independent LLM contexts: separate histories, separate token
 * accounting, separate compression state, and — since Phase C — separate displays on the glasses. They share the
 * SKILL library, the memory store and the local model services, and nothing else.
 *
 * The type lives here, in `core-contracts`, because four modules have to agree on it: `output-runtime` puts its
 * name on the wire, `plugin-api` carries it across the plugin boundary, `glasses-app` uses it to key the two
 * display buffers, and `phone-app` uses it to filter the assistant tab. A copy per module would eventually
 * disagree, and the failure mode is a message shown under the wrong conversation.
 */
enum class AssistantRoute {
    WAKE,
    PROACTIVE,
    ;

    companion object {
        /**
         * The route named by [value], or `null` when this build does not know that name.
         *
         * Deliberately total: the wire is untrusted, and the only safe answer for a name we do not understand is
         * to refuse the message. Defaulting an unknown route to [WAKE] would file a proactive message — a sentence
         * the wearer was told while simply being perceived — into the conversation they opened by speaking.
         */
        fun fromWire(value: String?): AssistantRoute? = entries.firstOrNull { it.name == value }
    }
}
