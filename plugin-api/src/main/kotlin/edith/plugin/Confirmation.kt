package edith.plugin

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
}
