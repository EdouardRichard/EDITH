package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome

/**
 * R43/§4.12: `dial` — a direct call, and only a direct call.
 *
 * The number validation, the `CALL_PHONE` check and the `ACTION_CALL` hand-off stay in
 * [IntentActionExecutor]; this tool reuses that one implementation (the brief permits it) instead of
 * growing a parallel rule set. A missing permission is refused before the executor is reached, so
 * the tool's own gate and the executor's answer cannot disagree for long.
 *
 * **R129 risk ruling: `dial` is L1, not L2.** The user explicitly ruled that dialing never asks:
 * placing a call is an immediate, explicit action with no payment, and the wearer can end it from the
 * same phone. The default ceiling (`maxLevel = L1`) therefore treats it as the accepted class the
 * policy allows outright (see `WhitelistActionPolicy` step 3) instead of demanding an L2 grant or
 * asking a 120 s question. A wearer who lowers the ceiling to L0 still gets a `Refuse` — a refusal,
 * never a question. The number's privacy is why `auditTargetArgument` stays null, not the risk level.
 *
 * The result is `accepted_only` — an intent hand-off has no verified screen ownership, so `applied` is null and there
 * is no confirmation (R-03). Since edith-0.3 Phase 2.2 Task 6 the call **does** leave the phone on its call UI, so
 * `ToolCatalog` attaches the shared post-call observation + one best-effort image around this result; the outcome and
 * the tool-specific `data` stay exactly as they were. **There is deliberately no `ACTION_DIAL` fallback**: a dialer
 * screen would be a different action and would make a refused call look accepted.
 */
class DialTool(
    private val executor: ActionExecutor?,
    private val callAvailable: () -> Boolean,
) : ActionExecutor {

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun cancel() = Unit

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) {
            return ActionResult(
                ActionOutcome.Refused,
                "{}",
                // Fix review: an unknown action is not a `dial` call at all, so it cannot declare a state change.
                stateChanging = false,
                execution = refusedExecution(UNKNOWN_ACTION),
            )
        }
        val args = parseObject(argsJson) ?: return refused(ARGUMENTS_INVALID)
        args.stringArg("number")?.trim()?.takeIf { it.isNotEmpty() } ?: return refused(ARGUMENTS_INVALID)
        if (!callAvailable()) return refused(CALL_PERMISSION_MISSING)
        val target = executor ?: return refused(CALL_PERMISSION_MISSING)
        val result = try {
            target.execute(NAME, argsJson)
        } catch (t: Throwable) {
            // edith-0.3 Phase 3 M1: the dial already reached the executor, so a throw is not a pre-dispatch refusal.
            // The legacy projection cannot express uncertainty; the structured fact is `Uncertain` with accepted=true
            // and every tri-state field unknown - the call may have been placed.
            return ActionResult(
                outcome = ActionOutcome.Failed,
                detail = "{}",
                stateChanging = true,
                execution = ToolExecution(
                    outcome = ToolOutcome.Uncertain,
                    code = DIAL_FAILED,
                    message = DIAL_DISPATCH_UNCERTAIN,
                    recoverable = true,
                    accepted = true,
                    applied = null,
                    verified = null,
                    changed = null,
                    uncertain = true,
                ),
            )
        }
        return when (result.outcome) {
            ActionOutcome.Done -> ActionResult(
                ActionOutcome.Done,
                "{}",
                stateChanging = true,
                execution = ToolExecution(
                    outcome = ToolOutcome.Done,
                    accepted = true,
                    applied = null,
                ),
            )
            ActionOutcome.Refused -> refused(result.detail.ifBlank { ARGUMENTS_INVALID })
            ActionOutcome.Failed -> failed()
        }
    }

    /**
     * Task 8: the `DIAL_FAILED` shape, stated once.
     *
     * `data` carries no facts at all (a phone number never appears in a payload): the execution facts are authoritative
     * and the observation, when the catalog enriches this result, is the call UI. `accepted=true`/`applied=false` is the
     * same reading `ToolCatalog` used to derive: the valid call reached the executor, but the intent did not launch.
     */
    private fun failed(): ActionResult = ActionResult(
        ActionOutcome.Failed,
        "{}",
        stateChanging = true,
        execution = ToolExecution(
            outcome = ToolOutcome.Failed,
            code = DIAL_FAILED,
            message = "the dial intent could not be launched",
            recoverable = true,
            accepted = true,
            applied = false,
        ),
    )

    // R98: every `dial` call is a write, whatever its outcome — the declaration describes the call class, not whether
    // CALL_PHONE let the intent run. `execution` is the one carrier of the stable code; `data` stays empty because no
    // part of a dial result is tool-specific content that may face the model.
    private fun refused(code: String): ActionResult = ActionResult(
        ActionOutcome.Refused,
        "{}",
        stateChanging = true,
        execution = refusedExecution(code),
    )

    /** A dial refusal never dispatched the intent: `accepted=false`/`applied=false` are facts; only bad arguments are fatal. */
    private fun refusedExecution(code: String): ToolExecution = ToolExecution(
        outcome = ToolOutcome.Refused,
        code = code,
        message = messageFor(code),
        recoverable = code != ARGUMENTS_INVALID,
        accepted = false,
        applied = false,
    )

    private fun messageFor(code: String): String = when (code) {
        CALL_PERMISSION_MISSING -> "the CALL_PHONE permission is not granted"
        ARGUMENTS_INVALID -> "the number is not dialable"
        else -> "the dial was refused"
    }

    companion object {
        const val NAME = "dial"

        private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
        private const val CALL_PERMISSION_MISSING = "CALL_PERMISSION_MISSING"
        private const val DIAL_FAILED = "DIAL_FAILED"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        /** M1: the stable, content-free reason for a throw that happened after the dial was dispatched. */
        private const val DIAL_DISPATCH_UNCERTAIN = "the dial call may have been dispatched"

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "拨打电话",
            description = "直接拨打电话：number 只接受由数字、+、*、# 组成的号码（其它形式返回 ARGUMENTS_INVALID）。只在用户明确要求“打电话给…”时使用；只是把号码填进拨号盘、发消息或查询号码不要用它。没有通话权限返回 CALL_PERMISSION_MISSING；启动被系统拒绝返回 LAUNCH_REFUSED（没有拨出）；只有把调用交给拨号通路之后无法确认结果时才返回 DIAL_FAILED（可能已拨出，不要自动重拨）。号码属于隐私，工具不会把它写入审计。",
            parametersJson = """{"type":"object","properties":{"number":{"type":"string"}},""" +
                """"required":["number"],"additionalProperties":false}""",
            // R129: L1 - the user's ruling is that dial never asks; see the class KDoc.
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "phone.number",
            // A phone number is personal data: no part of it ever reaches the audit trail (spec §17.5).
            auditTargetArgument = null,
            // edith-0.3 Phase 2.2 Task 6: the call UI is the post-call screen, so `ToolCatalog` observes it. This is the
            // screen-observation declaration, not the fingerprint seam (`observation` stays false).
            screenObservation = true,
        )
    }
}
