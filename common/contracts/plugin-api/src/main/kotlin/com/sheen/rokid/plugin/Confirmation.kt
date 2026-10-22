package com.sheen.rokid.plugin

/**
 * How a plugin asks the host to put a question to the user (spec §17.4).
 *
 * [present] only *presents*: the answer arrives separately as a `ConfirmationResult` host event, which the plugin's own
 * `ConfirmationGate` turns into a decision. That split is deliberate - the host owns the phone UI, the notification and
 * the glasses summary, while the plugin owns the policy, the timeout and the audit line.
 *
 * [requestId] is the tool call id: it is what makes the answer addressable, and a second question with the same id must
 * never replace a live one. [timeoutMillis] is the window the host shows; the plugin's gate is the authority on what an
 * expired window means. Must not block the caller.
 */
interface ConfirmationChannel {
    fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle

    /**
     * 0.3 Phase 4B (B7, spec §6.3): the same [present], naming the action the question is about.
     *
     * [tool] is the action's wire name (`app.uninstall`, `shell`, …) — never the summary, an argument or a command. It
     * exists so the host's count-only `P4_CONFIRM` line can count confirmations per tool; it is not shown to the wearer
     * and never replaces [summary]. Additive with a delegating default: a channel written before the name traveled keeps
     * presenting exactly as it did.
     */
    fun present(requestId: String, summary: String, timeoutMillis: Long, tool: String): CancellationHandle =
        present(requestId, summary, timeoutMillis)

    /**
     * 0.3 Phase 4B (B7): the plugin's settlement of a question, reported to the host for its count-only telemetry.
     *
     * The **plugin** owns what an answer means (its gate decides timeout as denial, voice as an exact phrase, STOP as a
     * cancellation), so it can name the decision truthfully: [decision] is exactly one of `allow`, `deny`, `timeout`,
     * `unavailable`. The **host** owns where the question was shown, so the line's channel is the one it captured when
     * the question went up; [answerChannel] overrides it only when the answer really arrived somewhere else
     * (`voice` — a spoken phrase never presents a question). `null` means "use the presentation channel".
     *
     * Additive with a no-op default, and deliberately not a verdict: this call only reports a settlement that already
     * happened. It must not deliver, resolve or re-open anything, and a host that predates it keeps working unchanged.
     * A host that does implement it may take its question down as part of reporting; the returned
     * [CancellationHandle] still withdraws whatever is left, exactly as before. The plugin's own outcome must never
     * depend on whether the report was accepted.
     *
     * @return `true` when the host settled a question it still had; `false` for an unknown id, an unknown decision word
     *   or a host that predates this report. The plugin's own outcome never depends on it.
     */
    fun settle(requestId: String, decision: String, answerChannel: String? = null): Boolean = false

    /**
     * Whether this channel can put a question to the wearer **right now** (0.3 Phase 4B, spec §5.4).
     *
     * Additive with a `true` default: a channel written before the availability check, or one whose surface is simply
     * unknown, keeps asking exactly as it did. A host that knows its screen/permission/link surface is gone overrides
     * this with `false`; the loop then refuses the action at once with `CONFIRMATION_UNAVAILABLE` instead of waiting
     * out D47's 120 s window for an answer nobody can give. The check is **live** - called before each question, never
     * cached at attach time - because a link, a page or a permission can come back.
     */
    fun available(): Boolean = true
}
