package com.sheen.rokid.phone.app

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.HintDevice

/**
 * 0.4 Task 7 (`D201`, spec §4.3): the phone's display device, used while the session is detached from the glasses.
 *
 * While detached there is no CXR mapping and therefore no `hintLink.liveOutput()`: this device is what the host's
 * `liveOutput` answers, which is also what keeps `Capability.TextOutput` advertised without a glasses connection.
 *
 * **Ack-only, by contract.** The assistant's answer row (`say-<hintId>`) is written by the plugin's
 * `publishAssistantFinal` into the same [ConversationLog] the phone's conversation tab renders. This device must not
 * write it: a second append under the same entry id would replace the plugin's text, and a memory acknowledgement that
 * shares the turn's hint id would then overwrite the real answer (`AnswerEmitIdentityIntegrationTest` pins that the
 * two must stay separate rows). So the record is read-only here, and the only thing this device produces is the
 * acknowledgement the plugin actually settles on.
 *
 * **Exactly one `Displayed`, synchronously.** `Accepted` only means the arbiter took the item; the plugin settles the
 * answer and opens the next listening round on `Displayed` (spec §4.3/D201). The real production order is
 * `sink.submit` first and `publishAssistantFinal` second (`SmartAssistantPlugin.emitAnswer`), so this device cannot
 * gate the acknowledgement on the row already being present - doing so would refuse every first answer of a turn.
 * The row lands immediately after the submit, and the tab renders it; the acknowledgement is unconditional and
 * delivered once inside [show], never deferred to a thread this class does not own.
 *
 * [clear] is deliberately a no-op: it withdraws the *current* hint (of which this device keeps no state - the phone
 * tab is the surface), and must never delete what was said.
 */
class PhoneHintDevice(private val log: ConversationLog) : HintDevice {

    override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
        // `revision` is the glasses' row-versioning rule; the phone tab renders the record itself, so it carries no
        // meaning here. `assistance.text` is displayed by the tab from the plugin's row - it is never logged here.
        listener(OutputStatus.Displayed)
        return NoopCancellation
    }

    /** Nothing to withdraw and nothing to delete: the phone's conversation record outlives every hint. */
    override fun clear(sessionId: SessionId, generation: Generation) = Unit

    /**
     * Whether the phone record already carries the answer row for [hintId].
     *
     * Read-only, and the reason [log] is a constructor value rather than a callback: this is the surface the
     * acknowledgement is about, and a JVM test can assert that [show] neither creates nor replaces its rows.
     */
    internal fun answerRowVisible(hintId: String, route: AssistantRoute = AssistantRoute.WAKE): Boolean =
        log.entries(route).any { it.entryId == "say-$hintId" }

    private object NoopCancellation : CancellationHandle {
        override fun cancel() = Unit
    }
}
