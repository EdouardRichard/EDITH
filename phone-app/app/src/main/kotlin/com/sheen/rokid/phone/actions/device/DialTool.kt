package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel

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
 * The result is `accepted_only` — an intent hand-off has no screen to observe, so `applied` is null
 * and there is no confirmation (R-03). **There is deliberately no `ACTION_DIAL` fallback**: a dialer
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
        if (name != NAME) return ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
        val args = parseObject(argsJson) ?: return refused(ARGUMENTS_INVALID)
        args.stringArg("number")?.trim()?.takeIf { it.isNotEmpty() } ?: return refused(ARGUMENTS_INVALID)
        if (!callAvailable()) return refused(CALL_PERMISSION_MISSING)
        val target = executor ?: return refused(CALL_PERMISSION_MISSING)
        val result = try {
            target.execute(NAME, argsJson)
        } catch (t: Throwable) {
            return refused(DIAL_FAILED)
        }
        return when (result.outcome) {
            ActionOutcome.Done -> ActionResult(ActionOutcome.Done, acceptedOnly(), stateChanging = true)
            ActionOutcome.Refused -> refused(result.detail.ifBlank { ARGUMENTS_INVALID })
            ActionOutcome.Failed -> ActionResult(
                ActionOutcome.Failed,
                toolEnvelope(
                    tool = NAME,
                    ok = false,
                    channel = CHANNEL,
                    accepted = false,
                    error = ToolFailure(DIAL_FAILED, "the dial intent could not be launched", recoverable = true),
                ),
                stateChanging = true,
            )
        }
    }

    /** The `accepted_only` success shape: no observation, no number in the payload. */
    private fun acceptedOnly(): String = toolEnvelope(
        tool = NAME,
        ok = true,
        channel = CHANNEL,
        accepted = true,
        applied = null,
    )

    // R98: every `dial` call is a write, whatever its outcome — the declaration describes the call class, not whether
    // CALL_PHONE let the intent run.
    private fun refused(code: String): ActionResult = ActionResult(
        ActionOutcome.Refused,
        toolEnvelope(
            tool = NAME,
            ok = false,
            channel = CHANNEL,
            accepted = false,
            error = ToolFailure(code, messageFor(code), recoverable = code != ARGUMENTS_INVALID),
        ),
        stateChanging = true,
    )

    private fun messageFor(code: String): String = when (code) {
        CALL_PERMISSION_MISSING -> "the CALL_PHONE permission is not granted"
        ARGUMENTS_INVALID -> "the number is not dialable"
        else -> "the dial was refused"
    }

    companion object {
        const val NAME = "dial"

        private const val CHANNEL = "intent"
        private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
        private const val CALL_PERMISSION_MISSING = "CALL_PERMISSION_MISSING"
        private const val DIAL_FAILED = "DIAL_FAILED"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "拨打电话",
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
        )
    }
}
