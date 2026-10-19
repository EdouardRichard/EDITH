package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.RiskLevel

/**
 * One tool invocation requested by the model for a single agent step.
 *
 * [argsJson] is carried as the model produced it: the registry validates it but never rewrites it, so the executor
 * receives exactly the string the wire delivered (see `ToolRegistry.execute`). [itemId] is the provider's own
 * `function_call` item identity, distinct from [id] (the `call_id` the tool output is addressed to); a gateway that
 * does not know it leaves the default, and `ModelHistoryItem.ToolCall` echoes it back on the wire.
 */
data class ToolCall(val id: String, val name: String, val argsJson: String, val itemId: String = id) {
    init {
        // A blank claim is refused at the port: `ModelHistoryItem.ToolCall` also requires a non-blank item id, and this
        // failure has to name the tool call rather than surface while the transcript is assembled.
        require(itemId.isNotBlank())
    }
}

/**
 * The outcome of one tool invocation, fed back to the model as the next step's tool result.
 *
 * A `ok = false` result is *information*, never a run-ending verdict: an unknown tool, unparsable arguments, a refusal
 * and an executor failure all come back here so the model can correct itself.
 *
 * [fingerprint] (Task 12, R57) is the executor's optional **post-call observation fingerprint**, projected verbatim from
 * `plugin-api`'s `ActionResult.fingerprint`. It is a hash of the screen/window content, never the payload and never a
 * timing value; the loop feeds it to the poll/global detectors and (for a write) to `ProgressGuard`. `null` means this
 * call has no observation state, and no detector may invent one from the payload.
 */
data class ToolResult(
    val id: String,
    val ok: Boolean,
    val payload: String,
    val fingerprint: String? = null,
    /**
     * 0.25 Task 12 fix (R98): the executor's per-call declaration that this call **changed state**, projected verbatim
     * from `plugin-api`'s `ActionResult.stateChanging`.
     *
     * It is what lets the loop tell `device.act` (state-changing, `observation = true` because it returns a fingerprint)
     * from `device.observe` (read-only) without a name table. `ProgressGuard` is fed only a state-changing result that
     * also carries a [fingerprint]; `false` — the default, and every plugin tool — keeps the R62 read-only exclusion.
     */
    val stateChanging: Boolean = false,
) {
    companion object {
        /** A failed step whose payload is a stable machine-readable code rather than an exception message. */
        fun error(id: String, safeCode: String): ToolResult = ToolResult(id = id, ok = false, payload = safeCode)
    }
}

/**
 * Platform-independent description of one tool offered to the model, projected from `plugin-api`'s
 * [com.sheen.rokid.plugin.ActionDescriptor] (its `title` becomes [description]).
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parametersJson: String,
    val risk: RiskLevel,
    val requiresConfirmation: Boolean,
    val idempotent: Boolean,
    val reversible: Boolean,
    val scope: String,
    /** P3.3: projected from the plugin's descriptor; see `ActionDescriptor.auditTargetArgument`. */
    val auditTargetArgument: String? = null,
    /**
     * Phase 6 (design D-9): projected from the plugin's descriptor; see
     * [com.sheen.rokid.plugin.ActionDescriptor.countsTowardStepLimit]. `true` — the default — spends one step of the loop's
     * `maxSteps` hang guard. `false` means the call does **not** increment that counter; it does **not** bypass an
     * already-exhausted guard (the next call is still stopped at `maxSteps`), and the call still appears in
     * `AgentRun.steps`.
     */
    val countsTowardStepLimit: Boolean = true,
    /**
     * Task 12 (R57): projected from `ActionDescriptor.observation`. `true` means each call returns an observation
     * fingerprint: it counts toward `AgentRun.spentObservations` (R61) and the poll/global detectors may use it. The
     * runtime never infers this from a tool's name.
     */
    val observation: Boolean = false,
    /**
     * Task 12 (R60): projected from `ActionDescriptor.timeoutMillis` — the per-call bound the loop enforces (further
     * capped by the turn deadline). Defaults to the ordinary-tool value so every pre-0.25 descriptor is unchanged.
     */
    val timeoutMillis: Long = 30_000L,
    /**
     * Task 12 fix (R99): projected from `ActionDescriptor.mayAskHuman`. `true` means the call may spend up to D47's
     * human decision window inside `execute`; the loop adds its fixed allowance to [timeoutMillis] and shifts the wait
     * out of the turn deadline instead of letting either cut a legal answer (D49).
     */
    val mayAskHuman: Boolean = false,
)

/** A descriptor bound to the executor that declared it. */
class RegisteredTool(val executor: ActionExecutor, val descriptor: ToolDescriptor)

/**
 * A tool whose descriptor carries this scope may end the agent loop (Task 6 reads this exact string).
 */
const val SCOPE_TERMINAL = "terminal"
