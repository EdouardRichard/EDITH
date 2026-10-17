package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolSpec

/**
 * The single request shape that replaces the old three-owner assembly (spec §3.2).
 *
 * [systemSegments] form the system slot; only INSTRUCTIONS, SKILL, and MEMORY_POLICY origins are allowed.
 * [conversationFlow] carries transcript, summary, memory evidence, perception, user input, tool rounds, and retries.
 * [tools] lists the tool specifications available for this turn.
 */
data class RequestFrame(
    val systemSegments: List<Segment>,
    val conversationFlow: List<Segment>,
    val tools: List<ToolSpec>,
) {
    /** Concatenation of all system segment texts, joined by newlines. */
    val systemText: String
        get() = systemSegments.joinToString("\n") { it.text }

    /** Memory evidence segments filtered from the conversation flow. */
    val memoryEvidenceSegments: List<Segment>
        get() = conversationFlow.filter { it.origin == SegmentOrigin.MEMORY_EVIDENCE }

    /** The single USER_INPUT segment in the conversation flow. */
    val userInputSegment: Segment
        get() = conversationFlow.single { it.origin == SegmentOrigin.USER_INPUT }

    init {
        require(systemSegments.isNotEmpty()) {
            "RequestFrame requires at least one system segment"
        }

        val allowedSystemOrigins = setOf(
            SegmentOrigin.INSTRUCTIONS,
            SegmentOrigin.SKILL,
            SegmentOrigin.MEMORY_POLICY,
        )
        for (segment in systemSegments) {
            require(segment.origin in allowedSystemOrigins) {
                "System segments must only have INSTRUCTIONS, SKILL, or MEMORY_POLICY origin; found ${segment.origin}"
            }
        }

        val userInputCount = conversationFlow.count { it.origin == SegmentOrigin.USER_INPUT }
        require(userInputCount == 1) {
            "Exactly one USER_INPUT segment must exist in conversationFlow; found $userInputCount"
        }

        require(conversationFlow.last().origin == SegmentOrigin.USER_INPUT) {
            "USER_INPUT must be the last element in conversationFlow"
        }
    }
}
