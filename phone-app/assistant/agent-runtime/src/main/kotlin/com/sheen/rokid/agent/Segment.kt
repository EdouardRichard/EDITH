package com.sheen.rokid.agent

/**
 * The ten origins a request segment can have (spec §3.2).
 *
 * Each segment carries its origin as structured metadata — never inferred from rendered text.
 * Attribution is supplied when the fragment is produced, not reverse-engineered later.
 */
enum class SegmentOrigin {
    /** Plugin base instructions + scene strategy. System slot. Trusted. */
    INSTRUCTIONS,
    /** SkillManager-contributed skill content. System slot. Trusted. */
    SKILL,
    /** Fixed memory policy sentence. System slot. Trusted. */
    MEMORY_POLICY,
    /** Retrieved memory evidence. Independent message with boundary tag. Untrusted. */
    MEMORY_EVIDENCE,
    /** Host perception signals. Independent message with tag. Untrusted. */
    PERCEPTION,
    /** Compression-produced summary. Conversation flow with boundary marker. Untrusted. */
    SUMMARY,
    /** Accumulated transcript. Conversation flow. Trusted. */
    TRANSCRIPT,
    /** Current turn user input. Last in conversation flow. Never evicted. Trusted. */
    USER_INPUT,
    /** Tool call/output round. Structured field. Single-turn lifetime. Trusted. */
    TOOL_ROUND,
    /** Retry instruction. Conversation flow. Single-turn. Trusted. */
    RETRY,
}

/** 会话流里一条消息的说话人。归属是元数据，**永不**由文本推断。 */
enum class SegmentRole { USER, ASSISTANT }

/**
 * One named, attributed block of text in a request frame (spec §3.2).
 *
 * [origin] identifies what produced this segment and where it belongs in the frame.
 * [trusted] marks whether the content is authoritative (true) or untrusted external data (false).
 * [text] is the segment's content; always non-blank.
 * [role] is the speaker of a conversation-flow segment, or `null` for every segment that is not
 * conversation flow. `role` 只对会话流段有意义；系统槽与记忆段为 `null`。
 */
data class Segment(
    val origin: SegmentOrigin,
    val trusted: Boolean,
    val text: String,
    val role: SegmentRole? = null,
) {
    init {
        require(text.isNotBlank()) { "Segment text must not be blank" }
    }
}
