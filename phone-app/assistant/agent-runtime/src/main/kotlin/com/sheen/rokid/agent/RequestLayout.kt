package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ToolSpec

/**
 * The layout rules of one request (0.3 spec §3.6/§5.3), kept as pure functions so the plugin and the gateway can
 * validate a frame without re-deriving the origin sets.
 *
 * The cacheable prefix is system text + ordered tool specs + history. Everything request-scoped — perception,
 * memory evidence, a skill body, runtime state, a retry instruction — is [dynamicTail] and must stay in the request
 * tail (before `USER_INPUT`); it never enters the system slot and never enters the prefix hash.
 */
object RequestLayout {

    private val SYSTEM_ORIGINS = setOf(
        SegmentOrigin.INSTRUCTIONS,
        SegmentOrigin.SKILL,
        SegmentOrigin.MEMORY_POLICY,
    )

    private val DYNAMIC_TAIL_ORIGINS = setOf(
        SegmentOrigin.PERCEPTION,
        SegmentOrigin.MEMORY_EVIDENCE,
        SegmentOrigin.SKILL_BODY,
        SegmentOrigin.RUNTIME_STATE,
        SegmentOrigin.RETRY,
    )

    /**
     * Rejects a system slot containing anything but the three stable origins, or any segment whose text still
     * carries a runtime placeholder.
     *
     * A plan is rendered once for the session (spec §4.1): a `{{slot}}` that reaches the system slot was never
     * substituted and would either be sent to the model as template syntax or become a prompt-injection surface.
     *
     * @throws IllegalArgumentException naming the offending origin when a dynamic segment tries to become system text,
     * or naming the placeholder-bearing origin when a system segment was never rendered.
     */
    fun validateSystem(segments: List<Segment>) {
        for (segment in segments) {
            require(segment.origin in SYSTEM_ORIGINS) {
                "System segments must only have INSTRUCTIONS, SKILL, or MEMORY_POLICY origin; found ${segment.origin}"
            }
            require("{{" !in segment.text && "}}" !in segment.text) {
                "System segments must not carry runtime placeholders; found one in a ${segment.origin} segment"
            }
        }
    }

    /**
     * The request-scoped context that belongs at the tail of the flow: dynamic-origin segments that appear before
     * `USER_INPUT`, returned in flow order. A flow with no `USER_INPUT` has no tail.
     */
    fun dynamicTail(flow: List<Segment>): List<Segment> {
        val inputIndex = flow.indexOfLast { it.origin == SegmentOrigin.USER_INPUT }
        if (inputIndex < 0) return emptyList()
        return flow.take(inputIndex).filter { it.origin in DYNAMIC_TAIL_ORIGINS }
    }

    /**
     * The prefix-cache identity of a request: [PrefixHash.of] over the stable system text, the ordered tool specs and
     * the history. Callers pass only the stable prefix (never [dynamicTail]); the digest is the only thing that may be
     * logged.
     */
    fun prefixHash(
        systemText: String,
        tools: List<ToolSpec>,
        history: List<ModelHistoryItem>,
    ): String = PrefixHash.of(systemText, tools, history)
}
