package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome

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
 * edith-0.3 Phase 2.2: [payload] is no longer the tool's own string. It is the one model-facing envelope
 * (`{execution, observation|null, data}`) produced by [ToolResultEnvelope.encode], and [execution]/[observation]/[data]
 * are its authoritative parts. [payload] is derived, never stored separately, so the three can never drift apart.
 *
 * [fingerprint] (Task 12, R57) is the executor's optional **post-call observation fingerprint**, projected verbatim from
 * `plugin-api`'s `ActionResult.fingerprint`. It is a hash of the screen/window content, never the payload and never a
 * timing value; the loop feeds it to the poll/global detectors and (for a write) to `ProgressGuard`. `null` means this
 * call has no observation state, and no detector may invent one from the payload.
 */
data class ToolResult(
    val id: String,
    val ok: Boolean,
    /**
     * The structured execution facts of this call — the envelope's `execution` member.
     *
     * A result synthesized by the framework itself ([error]) always carries one; a migrated tool states the facts it
     * knows and leaves unknown tri-state fields `null`. It is never derived from [data].
     */
    val execution: ToolExecution,
    /**
     * The post-call observation — the envelope's `observation` member — or `null` when this tool does not observe the
     * screen / did not attempt one. Never invented to fill the key: "no observation" is itself the fact.
     */
    val observation: ToolObservation?,
    /**
     * The tool-specific detail, rendered as the envelope's `data` member and kept here so a consumer that needs the raw
     * detail (a terminal tool's final answer) does not have to parse the envelope. It must not repeat the observation
     * fields; observation content belongs in [observation].
     */
    val data: String = "",
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
    /**
     * 0.25 Task 24 (R133): the image artifacts this call produced, projected verbatim from `plugin-api`'s
     * `ActionResult.images` — metadata only, the bytes stay in the artifact store.
     *
     * It is what puts a screenshot on the `ModelHistoryItem.ToolOutput` of the tool round, and from there on the model
     * request. Empty — the default — keeps every pre-0.25 result and every non-image tool source-compatible and its
     * tool round byte-for-byte unchanged; an image is never invented from the payload.
     */
    val images: List<ImageRef> = emptyList(),
    /**
     * edith-0.3 Phase 2 final review (C2): the executor's declaration that the tool-specific part of this result carries
     * perception content which may live only in the current model request (projected from `plugin-api`'s
     * `ActionResult.nonPersistent`).
     *
     * The loop feeds the full payload to the model for this run, but at `finish` the persisted form of a marked tool
     * output becomes a safe marker with no images, so it cannot reach a `ConversationStore` or an `AgentRun.transcript`.
     * `false` — the default — keeps every pre-Phase-2 result and every non-perception tool byte-for-byte unchanged.
     */
    val nonPersistent: Boolean = false,
) {
    /** The one model-facing result shape; see [ToolResultEnvelope]. */
    val payload: String = ToolResultEnvelope.encode(execution, observation, data)

    companion object {
        /**
         * A failed step the framework synthesized **before dispatch**, or before any tool produced a result: an unknown
         * tool, unparsable arguments, or a policy/confirmation refusal.
         *
         * The action was never dispatched, so `accepted=false` and `applied=false` are facts, not guesses; no observation
         * is fabricated here (the loop may add a best-effort screen observation for a `screenObservation` descriptor).
         * The code is the stable machine identity the loop's error detectors already key on — the old
         * `"Failed: "`/`"Refused: "` prefix is deliberately gone. A **dispatched** call that threw is not this shape:
         * see [threw].
         */
        fun error(id: String, safeCode: String): ToolResult = ToolResult(
            id = id,
            ok = false,
            execution = ToolExecution(
                outcome = ToolOutcome.Failed,
                code = safeCode,
                accepted = false,
                applied = false,
            ),
            observation = null,
        )

        /**
         * A **dispatched** call whose executor threw: the tool may have applied part of the call, so no tri-state fact
         * is known (`accepted`/`applied`/`changed` stay `null`) and the result is explicitly [ToolExecution.uncertain].
         *
         * The stable code is `TOOL_THREW`; no observation is fabricated. A screen tool's thrown result is enriched
         * best-effort by `AgentLoop` after projection, which records an unavailable observation rather than a silent
         * `null` when the source is missing or cannot answer in time.
         */
        fun threw(id: String): ToolResult = ToolResult(
            id = id,
            ok = false,
            execution = ToolExecution(
                outcome = ToolOutcome.Uncertain,
                code = TOOL_THREW,
                uncertain = true,
            ),
            observation = null,
        )

        private const val TOOL_THREW = "TOOL_THREW"
    }
}

/**
 * Platform-independent description of one tool offered to the model, projected from `plugin-api`'s
 * [com.sheen.rokid.plugin.ActionDescriptor].
 *
 * Two copy channels are deliberately separate (edith-0.3 Phase 4A Task 5): [description] is the model-facing
 * instruction prose, while [title] is the short label user-facing surfaces (a confirmation summary, a tool row) paint.
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
    /**
     * edith-0.3 Phase 2.2 Task 3: projected from [com.sheen.rokid.plugin.ActionDescriptor.screenObservation] — this
     * tool touches the screen, so a failed call must come back with a best-effort observation (or an explicit
     * `unavailable` one) instead of a silent `null`.
     *
     * It is separate from [observation]: the latter is the fingerprint/progress declaration. `false` — the default —
     * keeps every pure tool out of the failure observer, preserving `observation = null` for it.
     */
    val screenObservation: Boolean = false,
    /**
     * edith-0.3 Phase 4A Task 5 (A5): the short, **user-facing** label projected from
     * `ActionDescriptor.title`.
     *
     * Unlike [description] — the model-only instruction prose (when to use the tool, when not to, how it fails, what the
     * arguments constrain) — this field is the copy the wearer may see as a tool row or a confirmation summary, and the
     * long description must never be painted there (the A1 review finding). It defaults to [description] so every
     * pre-A5 `ToolDescriptor` construction keeps compiling and keeps showing the copy it showed before the split; the
     * registry projects the descriptor's real title.
     */
    val title: String = description,
)

/** A descriptor bound to the executor that declared it. */
class RegisteredTool(val executor: ActionExecutor, val descriptor: ToolDescriptor)

/**
 * A tool whose descriptor carries this scope may end the agent loop (Task 6 reads this exact string).
 */
const val SCOPE_TERMINAL = "terminal"
