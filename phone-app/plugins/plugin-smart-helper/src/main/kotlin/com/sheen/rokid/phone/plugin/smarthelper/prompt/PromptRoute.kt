package com.sheen.rokid.phone.plugin.smarthelper.prompt

/**
 * Which prompt posture one request is assembled under (0.3 Phase 3 spec §5.1).
 *
 * This is deliberately **not** `ContextRoute`: the proactive chain has two distinct jobs — deciding whether to
 * speak unprompted ([PROACTIVE_HINT]) and answering the wearer after that hint ([PROACTIVE_FOLLOWUP]) — which need
 * different route postures while sharing the proactive transcript/store. A `ContextRoute` says which conversation a
 * turn belongs to; a `PromptRoute` says which job the model is being asked to do.
 *
 * The enum names are also the `P3_PROMPT route=` labels, so a log line and a pinned plan use one vocabulary.
 */
internal enum class PromptRoute {
    /** The wearer spoke to the assistant: answer the request, or act on it. */
    WAKE,

    /** The proactive chain decides whether this environment is worth interrupting the wearer for. */
    PROACTIVE_HINT,

    /** The proactive chain already spoke and the wearer replied: a normal task turn in the follow-up posture. */
    PROACTIVE_FOLLOWUP,
}
