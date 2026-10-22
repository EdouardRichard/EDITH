package com.sheen.rokid.agent

import com.sheen.rokid.plugin.RiskLevel

/**
 * One免确认 grant: [action] may run without asking, but **only** against [scope].
 *
 * The pair is the whole point (spec §7.2). A grant of `("message.send", "contact:A")` is the user saying "you may
 * message this person", never "you may send any message" — an action-only key would silently widen every grant to
 * every target the moment a new scope appeared.
 */
data class WhitelistEntry(val action: String, val scope: String)

/**
 * The user's ceiling plus their免确认 grants, consulted before any tool runs (spec §7.2, §7.3).
 *
 * Three hard properties decide the shape of [decide] **in normal mode**:
 * 1. the ceiling is the **auto-run** boundary (4B D190) — a risk above [maxLevel] is escalated to
 *    [PolicyDecision.RequireConfirmation] and can never run unasked, so the check runs ahead of any grant;
 * 2. **L3/L4 are never免确认**, even when a matching grant exists and even when [maxLevel] is `L4`. This is why the
 *    whitelist is consulted only for `L2`: a grant is an opt-in convenience for the reversible middle, never a way to
 *    launder a high-risk action past the user;
 * 3. a plugin may only tighten. `requiresConfirmation = true` on the descriptor forces a question at any level, and a
 *    grant cannot override it — otherwise a plugin could ship an action that the user pre-approved without knowing
 *    what it does.
 *
 * The whitelist is copied on construction, and [snapshot] hands out an independent rule set: the wiring takes one
 * snapshot at turn start so that editing the user's settings mid-turn (the phone UI is live during a run) cannot
 * change the rules an already-running turn is judged by.
 *
 * 0.3 Phase 4B (`D189`, spec §5.1): [noInterception] is the host's explicit global 「不拦截」 mode. It is deliberately a
 * **live provider** rather than a constructor boolean, and it is checked **before** every other rule: while it answers
 * `true`, [decide] allows L0-L4 alike, so neither the ceiling, the whitelist nor a plugin's `requiresConfirmation` can
 * raise a question. It travels with [snapshot] because the mode must stay live inside a running turn — only the
 * normal-mode rules (ceiling, grants) are snapshotted. The default `{ false }` keeps every pre-4B caller normal.
 */
class WhitelistActionPolicy(
    private val maxLevel: RiskLevel,
    whitelist: Collection<WhitelistEntry>,
    private val noInterception: () -> Boolean = { false },
) : ActionPolicy {

    private val whitelist: Set<WhitelistEntry> = whitelist.toSet()

    /** An independent copy of this rule set, taken by the caller at turn start. The live gate travels with it. */
    fun snapshot(): WhitelistActionPolicy = WhitelistActionPolicy(maxLevel, whitelist, noInterception)

    /**
     * 0.3 Phase 4B (B7): the mode the next [decide] will apply, read from the same live provider [decide] reads.
     *
     * It is a read on each call, never a captured snapshot, for the same reason [decide] is: the owner's saved switch
     * must apply from the next tool call.
     */
    override fun mode(): PolicyMode = if (noInterception()) PolicyMode.NO_INTERCEPTION else PolicyMode.NORMAL

    override fun decide(descriptor: ToolDescriptor): PolicyDecision {
        // 0. The host's explicit mode outranks every rule below (spec §5.1). It is read on each decision because the
        // user's save must apply from the next tool call, and it must never be frozen into a turn snapshot.
        if (noInterception()) return PolicyDecision.Allow
        // 1. Above the ceiling the action is escalated to a question, not refused: `maxLevel` bounds what may run
        // automatically (4B D190). The check stays ahead of grants and plugin declarations so neither can lift it.
        if (descriptor.risk.ordinal > maxLevel.ordinal) return PolicyDecision.RequireConfirmation(descriptor.title)
        // 2. An app declaration can only tighten: it forces a question the user's grants may not answer for them.
        // edith-0.3 Phase 4A Task 5 (A5): the question shows the short user title; `description` is the model's
        // instruction prose and must never be painted as a confirmation summary.
        if (descriptor.requiresConfirmation) return PolicyDecision.RequireConfirmation(descriptor.title)
        // 3. L0/L1 are the levels the user has already accepted outright, so they run without asking.
        if (descriptor.risk <= RiskLevel.L1) return PolicyDecision.Allow
        // 4. L3/L4 may never be added to the免确认 list, so they always ask, listed or not.
        if (descriptor.risk >= RiskLevel.L3) return PolicyDecision.RequireConfirmation(descriptor.title)
        // 5. L2 is opt-in, and only for this exact (action, scope) pair.
        return if (WhitelistEntry(descriptor.name, descriptor.scope) in whitelist) {
            PolicyDecision.Allow
        } else {
            PolicyDecision.RequireConfirmation(descriptor.title)
        }
    }
}
