package com.sheen.rokid.model

/**
 * 0.53: the single DeepSeek-family rule shared by the gateway parser, the app catalog and the adapters.
 *
 * A model belongs to the DeepSeek family when its id or the gateway's `owned_by` marker says so. That is the same
 * signal the 0.52 catalog used to decide whether to show the thinking switch, and it is deliberately the **only**
 * family rule: the two adapters send DeepSeek-native wire fields and replay `reasoning` only for a profile the
 * catalog marked as thinking-capable, so a second, differently-worded rule here would let the UI and the wire drift
 * apart.
 *
 * A gateway that declares reasoning effort metadata is handled by the parser itself (it has the JSON object); this
 * object answers the id/owner half for callers that only hold a model id.
 */
object ModelFamilies {
    fun isDeepSeekFamily(id: String, ownedBy: String? = null): Boolean =
        id.contains("deepseek", ignoreCase = true) ||
            ownedBy?.contains("deepseek", ignoreCase = true) == true
}
