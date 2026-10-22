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
