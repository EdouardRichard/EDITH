package com.sheen.rokid.phone.actions.a11y

import com.sheen.rokid.phone.actions.ExecutionRouter
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult

/**
 * T1.4 — Applied semantics post-processor (AC-10).
 *
 * Wraps an [ExecutionRouter] and enriches every [ActionResult] with explicit `applied` information in the detail
 * string. The model never has to infer whether the screen actually changed from outcome codes alone.
 *
 * ## Design constraints
 *
 * - **ActionDescriptor shape unchanged** (plugin-api contract preserved): the `applied` concept lives entirely in
 *   the detail/payload string, encoded as `[applied=true]` or `[applied=false]` prefix.
 * - **Backend verdicts trusted**: each backend owns its own success criterion. The accessibility backend verifies
 *   via screen fingerprint; Shizuku verifies via state re-read; intent actions are fire-and-forget. The
 *   post-processor makes the applied status explicit without duplicating backend logic.
 * - **Refusal codes preserved**: `ACTION_CHANNEL_UNAVAILABLE`, `NODE_NOT_CLICKABLE`, `ARGUMENTS_INVALID`, etc.
 *   are never overwritten — the applied prefix is additive.
 *
 * ## Applied rules
 *
 * | Outcome | Applied | Rationale |
 * |---------|---------|-----------|
 * | Done    | true    | Backend verified success (screen changed / state changed / command accepted) |
 * | Refused | false   | Action was not applied (channel down, node not clickable, no screen change, etc.) |
 * | Failed  | false   | Action attempted but did not achieve its goal (state unchanged, exception, etc.) |
 */
class AppliedSemanticsPostProcessor(
    private val router: ExecutionRouter,
) : ActionExecutor {

    override fun descriptors(): List<ActionDescriptor> = router.descriptors()

    override fun execute(name: String, argsJson: String): ActionResult {
        val result = router.execute(name, argsJson)

        // Enrich based on outcome. Each backend owns its success criterion:
        // - Accessibility: fingerprint comparison → Done only when screen changed
        // - Shizuku: state re-read → Done only when target state achieved
        // - Intent: fire-and-forget → Done when accepted by the system
        // The post-processor trusts these verdicts and makes applied explicit.
        return when (result.outcome) {
            ActionOutcome.Done -> result.copy(detail = "[applied=true] ${result.detail}")
            ActionOutcome.Refused -> result.copy(detail = "[applied=false] ${result.detail}")
            ActionOutcome.Failed -> result.copy(detail = "[applied=false] ${result.detail}")
        }
    }

    override fun cancel() = router.cancel()
}
