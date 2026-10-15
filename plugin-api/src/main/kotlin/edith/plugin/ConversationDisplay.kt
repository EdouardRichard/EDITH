package edith.plugin

import edith.contracts.AssistantRoute

/**
 * Phase C (spec §6.6 / §6.7): the plugin's side of the conversation the wearer reads back from.
 *
 * This is what turns "the assistant is doing something" into two visible things: the phone's own record of the
 * conversation, and the traffic that puts it on the glasses. The plugin knows *when* — a turn opened, an utterance was
 * accepted, a hint went out, a turn ended in silence — and this port is how that knowledge leaves the plugin without
 * the plugin ever touching the wire.
 *
 * **It is optional.** A host with no display and no conversation record simply never provides
 * [Capability.ConversationDisplay], and the plugin treats every call as best-effort: a missing port must never stop a
 * turn, exactly as a missing speech player must not stop a hint from being shown.
 *
 * Two facts about the shapes:
 *
 * - [userMessage] and [assistantMessage] are **statements about the conversation**; the glasses render the wearer's
 *   side from the former and the assistant's side from the hint itself, so an assistant message is not sent as an
 *   utterance.
 * - [working] and [idle] are a **level**, not a pair of events with meaning in their own right: the later statement
 *   wins, and a caller that reports the same level twice has said nothing new. The receiving side bounds the level in
 *   time as well, because a single missing `idle` would otherwise leave a spinner turning and a panel lit forever.
 */
interface ConversationDisplay {

    /** One utterance the wearer made, for the conversation named by [route]. */
    fun userMessage(route: AssistantRoute, text: String)

    /** One message the assistant is telling the wearer, for the conversation named by [route]. */
    fun assistantMessage(route: AssistantRoute, text: String)

    /** The assistant has started working on [route]. */
    fun working(route: AssistantRoute)

    /** The assistant has stopped working on [route]: a hint went out, the turn was cancelled, or it timed out. */
    fun idle(route: AssistantRoute)
}
