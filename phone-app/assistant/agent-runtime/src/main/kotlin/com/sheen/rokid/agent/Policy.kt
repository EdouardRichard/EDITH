package com.sheen.rokid.agent

/**
 * The policy port the loop consults before executing any tool.
 *
 * P3.1 declares the port only. **Task 8 owns every implementation** — `WhitelistActionPolicy`, `ConfirmationGate` and
 * the audit sink. There is deliberately no allow-all or no-op implementation in production sources: a fail-open
 * default is exactly the failure mode the whitelist exists to prevent, so this module ships none.
 */
interface ActionPolicy {
    fun decide(descriptor: ToolDescriptor): PolicyDecision
}

/**
 * What the policy says about one tool.
 *
 * [RequireConfirmation] carries the human-readable question to ask; P3.1 has no confirmation channel wired, so the
 * loop refuses such a call with `CONFIRMATION_UNAVAILABLE` and never executes it (see `AgentLoop`).
 */
sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data object Refuse : PolicyDecision
    data class RequireConfirmation(val summary: String) : PolicyDecision
}

/**
 * The local audit trail port (spec §7.4): one line per tool decision and per executed step.
 *
 * Task 8 owns the implementation; the loop only writes.
 *
 * P3.3 (spec §17.5) widened the line with the four fields spec §7.4 asks for beyond the identity: the action's
 * [scope] and [risk], the [decision] the policy took, and which channel settled a [confirmationSource]. Every one of
 * them has a default, so a P3.1-era call site and a test fake that only knows the original four keep compiling and
 * keep meaning the same thing. [summary] is the *only* free-text field and it carries nothing but the target argument
 * the executor itself declared safe (`ActionDescriptor.auditTargetArgument`).
 */
interface AuditSink {
    fun record(
        turnId: String,
        action: String,
        summary: String,
        outcome: String,
        scope: String? = null,
        risk: String? = null,
        decision: String? = null,
        confirmationSource: ConfirmationSource? = null,
    )
}
