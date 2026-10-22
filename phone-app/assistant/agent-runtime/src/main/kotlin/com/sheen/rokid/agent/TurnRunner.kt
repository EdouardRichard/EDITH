package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolSpec

/**
 * Assembles a [RequestFrame] for one model turn (spec §3.2 / Phase 3).
 *
 * The TurnRunner is the single owner of request assembly. It takes system segments (instructions,
 * skill content, memory policy), a conversation store snapshot, memory evidence, perception signals,
 * the current user input, and available tools — and produces a validated [RequestFrame].
 *
 * This replaces the old three-owner assembly where plugin, AgentLoop, and ModelClientGateway each
 * contributed parts of the request independently.
 *
 * @param systemSegments the system slot segments (INSTRUCTIONS, SKILL, MEMORY_POLICY only)
 */
class TurnRunner(
    private val systemSegments: List<Segment>,
) {
    init {
        require(systemSegments.isNotEmpty()) { "TurnRunner requires at least one system segment" }
    }

    /**
     * Assembles a [RequestFrame] for one model turn.
     *
     * Conversation flow ordering: store snapshot → perception → memory evidence → USER_INPUT.
     * This places memory adjacent to the user input (spec §3.5) while keeping the transcript first.
     *
     * @param storeSnapshot current conversation state from [ConversationStore.snapshot]
     * @param memoryEvidence retrieved memory segments (must have MEMORY_EVIDENCE origin)
     * @param perception host perception segments (must have PERCEPTION origin)
     * @param userInput the current turn's user text
     * @param tools available tool specs for this turn
     */
    fun assemble(
        storeSnapshot: List<Segment> = emptyList(),
        memoryEvidence: List<Segment> = emptyList(),
        perception: List<Segment> = emptyList(),
        userInput: String,
        tools: List<ToolSpec> = emptyList(),
    ): RequestFrame {
        val flow = mutableListOf<Segment>()
        flow.addAll(storeSnapshot)
        flow.addAll(perception)
        flow.addAll(memoryEvidence)
        flow.add(Segment(SegmentOrigin.USER_INPUT, trusted = true, text = userInput))
        return RequestFrame(systemSegments, flow, tools)
    }
}
