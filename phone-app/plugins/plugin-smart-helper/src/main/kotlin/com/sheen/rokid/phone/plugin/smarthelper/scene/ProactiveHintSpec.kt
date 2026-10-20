package com.sheen.rokid.phone.plugin.smarthelper.scene

import com.sheen.rokid.plugin.ToolSpec

/**
 * The one tool a proactive request offers, and the constants of its display bound.
 *
 * 0.3 Phase 1 Task 12 (spec §4.5): the instruction prose this object used to own moved to the versioned prompt
 * catalog (`prompts/core/` + `route/wake.md` / `route/proactive-hint.md`). Keeping a second copy here would be a
 * second source of truth for the same sentences, and a model would receive both. What remains is genuinely
 * **behaviour**, not prose: the tool schema a proactive request advertises and the numbers its one call is judged by.
 *
 * The tool calling itself means "say this", and it is **never executed and never fed back** (`ProactiveTurn` sends one
 * request and stops), so the call can carry a sentence and nothing else.
 *
 * The schema asks for ~40 characters while [PROACTIVE_HINT_MAX_CHARS] is the display-side bound: the prompt narrows
 * what the model generates, the constant rejects what the glasses would silently clip
 * (`docs/project/evidence/2026-10-09-p3-2-hint-capacity.md`).
 */
internal object ProactiveHintSpec {

    /** The only control channel of a proactive request: calling it means "say this", silence is a legal answer. */
    val PROPOSE_HINT = ToolSpec(
        "propose_hint",
        "在用户未开口时给出至多一句短提示；不值得打断用户时不要调用。",
        """{"type":"object","properties":{"text":{"type":"string","description":"不超过 40 字的一句短提示"}},"required":["text"],"additionalProperties":false}""",
    )

    /**
     * The longest hint that may be shown: measured on the glasses, 60 characters occupy 5 of the 6 visible lines and
     * 120 are **silently clipped** (10 lines), so anything longer than this is refused rather than shown half-way.
     */
    const val PROACTIVE_HINT_MAX_CHARS = 60

    /** The one proactive request's own bound: a hint that is not worth waiting for is not worth showing. */
    const val PROACTIVE_DEADLINE_MILLIS = 8_000L

    /** How long a submitted proactive hint stays valid; the display's own TTL is a separate, shorter window. */
    const val PROACTIVE_HINT_TTL_MILLIS = 8_000L
}
