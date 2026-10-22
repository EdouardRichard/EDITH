package com.sheen.rokid.agent

/**
 * 0.3 Phase 4B (D189, spec §5.1/§6.2): which rule set a policy decision was taken under.
 *
 * [NORMAL] is the ceiling/whitelist/plugin-tightening rule set. [NO_INTERCEPTION] is the owner's explicit global
 * 「不拦截」 mode, which allows L0-L4 alike and is outranked by nothing. The type lives here (not in `plugin-api`)
 * because both the host's composition and the plugin's policy wiring read it; it is a value, never a wire field.
 *
 * The enum names are the count-only `P4_POLICY mode=` tokens once lower-cased (`normal` / `no_interception`), so the
 * telemetry line cannot drift from the mode the policy actually applied.
 */
enum class PolicyMode { NORMAL, NO_INTERCEPTION }

/**
 * The policy port the loop consults before executing any tool.
 *
 * P3.1 declares the port only. **Task 8 owns every implementation** — `WhitelistActionPolicy`, `ConfirmationGate` and
 * the audit sink. There is deliberately no allow-all or no-op implementation in production sources: a fail-open
 * default is exactly the failure mode the whitelist exists to prevent, so this module ships none.
 */
interface ActionPolicy {
    fun decide(descriptor: ToolDescriptor): PolicyDecision

    /**
     * 0.3 Phase 4B (B7, spec §5.3/§6.3): the mode [decide] applies **right now**, for the count-only `P4_POLICY` line.
     *
     * It is a separate question because `PolicyDecision` deliberately carries no mode: a caller must not be able to
     * infer the owner's global switch from an `Allow`. The default is [PolicyMode.NORMAL], which keeps every pre-4B
     * policy (including a test fake) honest without an override. A live implementation must read its provider on every
     * call — a saved switch applies from the next decision, never from a stale turn snapshot.
     */
    fun mode(): PolicyMode = PolicyMode.NORMAL
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
