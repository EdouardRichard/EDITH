package com.sheen.rokid.phone.plugin.smarthelper.scene

import com.sheen.rokid.agent.SCOPE_TERMINAL
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolSpec

/**
 * The one tool a proactive hint turn offers, and the constants that bound that turn and its display.
 *
 * 0.3 Phase 1 Task 12 (spec §4.5): the instruction prose this object used to own moved to the versioned prompt
 * catalog (`prompts/core/` + `route/wake.md` / `route/proactive-hint.md`). Keeping a second copy here would be a
 * second source of truth for the same sentences, and a model would receive both. What remains is genuinely
 * **behaviour**, not prose: the tool schema a proactive request advertises and the numbers its one call is judged by.
 *
 * 0.3 Phase 3 Task 5: the proactive hint is no longer a one-shot reply whose tool call was parsed and discarded. It is
 * an `AgentLoop` turn, and `propose_hint` is the loop's **only** tool and a `SCOPE_TERMINAL` one: a successful call
 * ends the run with its `data` (the trimmed sentence) as the run's `finalText`. The declaration therefore carries the
 * loop's descriptor fields too, while [PROPOSE_HINT] stays the wire schema the frame and registry both project.
 *
 * The schema asks for ~40 characters while [PROACTIVE_HINT_MAX_CHARS] is the display-side bound: the prompt narrows
 * what the model generates, the constant rejects what the glasses would silently clip
 * (`docs/project/evidence/2026-10-09-p3-2-hint-capacity.md`).
 */
internal object ProactiveHintSpec {

    /** The one tool name, shared by the wire spec, the descriptor and the executor. */
    const val NAME = "propose_hint"

    /** The only control channel of a proactive request: calling it means "say this", silence is a legal answer. */
    val PROPOSE_HINT = ToolSpec(
        NAME,
        "主动提示的唯一输出通道：调用即向用户显示 text；只回文字不会显示（等于沉默）。不值得打断用户时不要调用。",
        """{"type":"object","properties":{"text":{"type":"string","description":"不超过 40 字的一句短提示"}},"required":["text"],"additionalProperties":false}""",
    )

    /**
     * The loop's declaration of [PROPOSE_HINT] (0.3 Phase 3 spec §4.2).
     *
     * It is deliberately **L0 with no confirmation**: the call changes nothing on the device, it only proposes one
     * sentence, so asking the wearer to approve it would be a question about a sentence that has already been decided.
     * `SCOPE_TERMINAL` is what makes a successful call end the run; `countsTowardStepLimit = true` plus the hint loop's
     * `maxSteps = 1` bounds the turn to its single proposal. `screenObservation = false` keeps the loop's failure
     * observer out of a turn that never looks at the screen.
     */
    val DESCRIPTOR = ActionDescriptor(
        name = NAME,
        title = PROPOSE_HINT.description,
        parametersJson = PROPOSE_HINT.parametersJson,
        risk = RiskLevel.L0,
        requiresConfirmation = false,
        idempotent = true,
        reversible = true,
        scope = SCOPE_TERMINAL,
        countsTowardStepLimit = true,
        timeoutMillis = 5_000L,
        screenObservation = false,
    )

    /**
     * The longest hint that may be shown: measured on the glasses, 60 characters occupy 5 of the 6 visible lines and
     * 120 are **silently clipped** (10 lines), so anything longer than this is refused rather than shown half-way.
     */
    const val PROACTIVE_HINT_MAX_CHARS = 60

    /**
     * The **total** machine budget of one HINT_TURN, in milliseconds.
     *
     * It is anchored at the turn's start, not at the model call: the intervention gate, the perception snapshot,
     * memory retrieval, prompt assembly, the model request itself and the `propose_hint` round all spend this one
     * budget. A hint turn is not a bare model call, so a bound on only the request would not bound the turn.
     *
     * Owner decision A2 (2026-10-21), after Task 9's device readings: on the K40 the pre-model pipeline alone
     * measured 5.306/6.616/6.936 s (gate -> `P3_PROMPT`), leaving too little of the previous 8 s for the model's
     * 0.751-1.826 s reply - 3 of 4 measured hint turns ended `DEADLINE`. The old rule this constant carried ("a hint
     * that is not worth waiting for is not worth showing") was therefore judging the wrong interval. The owner chose
     * 15-20 s; this is the lower bound, **15 s**.
     *
     * Distinct from [PROACTIVE_HINT_TTL_MILLIS]: this bounds how long the turn may take to *produce* a hint, that
     * one how long an accepted hint may stay on the glasses.
     */
    const val PROACTIVE_DEADLINE_MILLIS = 15_000L

    /** How long a submitted proactive hint stays valid; the display's own TTL is a separate, shorter window. */
    const val PROACTIVE_HINT_TTL_MILLIS = 8_000L
}
