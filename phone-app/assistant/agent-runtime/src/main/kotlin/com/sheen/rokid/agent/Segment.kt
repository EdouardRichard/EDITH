package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ImageRef

/**
 * The twelve origins a request segment can have (spec §3.2 + Task 12's RUNTIME_STATE + Task 9's SKILL_BODY).
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
    /**
     * One non-persistent loop state line (Task 12, R59). Untrusted, request-scoped metadata: the gateway folds it into
     * the request input, it is never written to a `ConversationStore`, and `TurnTrimmer` protects it from eviction.
     */
    RUNTIME_STATE,
    /**
     * A trusted skill body pulled into this turn's conversation flow. It is dynamic, request-scoped content: it may
     * only appear before `USER_INPUT` (the request tail), never in the system slot, so the cacheable prefix stays
     * stable when a skill is loaded or its text changes.
     */
    SKILL_BODY,
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
 * [images] 是本段附带的图片 artifact 引用（0.25 spec §5.3），默认空以保持旧段形状不变。
 */
data class Segment(
    val origin: SegmentOrigin,
    val trusted: Boolean,
    val text: String,
    val role: SegmentRole? = null,
    val images: List<ImageRef> = emptyList(),
) {
    init {
        require(text.isNotBlank()) { "Segment text must not be blank" }
    }
}
