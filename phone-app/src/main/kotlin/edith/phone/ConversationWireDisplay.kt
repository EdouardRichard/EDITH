package edith.phone

import edith.contracts.AssistantRoute
import edith.output.ConversationEntry
import edith.output.ConversationLog
import edith.output.ConversationRole
import edith.plugin.ConversationDisplay
import java.util.UUID

/**
 * The production [ConversationDisplay]: one statement becomes the phone's record **and** traffic for the glasses.
 *
 * Why this is its own class rather than a few lines inside `HostCompositionRoot`: the two rules it enforces are the
 * ones that would be silently wrong if they were inlined. It is testable here without a composition, an Android
 * context or a device, and the failure it prevents is a real one - a wearer whose glasses show a conversation the
 * phone's own tab does not, or the reverse.
 *
 * **Record first, send second.** The wearer said the thing whether or not the link was up, so a refused send costs
 * them the *display* of an utterance and never the utterance itself. That is also what keeps the two screens honest
 * about each other: the phone's tab is a record of the conversation, not a log of successful transmissions.
 *
 * **An assistant message is not an utterance.** The assistant's side reaches the glasses as the hint itself, which
 * already carries its route, and re-sending it as a `SAID` would show the wearer their assistant's words as if they had
 * spoken them. So [assistantMessage] records and forwards nothing; [userMessage] does both.
 *
 * Entry ids are minted per statement (`said-…` / `say-…`), not derived from the text: the wearer may say the same
 * sentence twice, and the log must keep both. The glasses mint their own ids - theirs come from the wire's `hintId`
 * for the assistant's side - so the two records agree on content, order and route without sharing identities.
 */
class ConversationWireDisplay(
    private val log: ConversationLog,
    private val sendSaid: (AssistantRoute, String) -> Boolean,
    private val sendBusy: (AssistantRoute, Boolean) -> Boolean,
) : ConversationDisplay {

    override fun userMessage(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        log.append(ConversationEntry("said-${UUID.randomUUID()}", route, ConversationRole.USER, text))
        sendSaid(route, text)
    }

    override fun assistantMessage(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        log.append(ConversationEntry("say-${UUID.randomUUID()}", route, ConversationRole.ASSISTANT, text))
    }

    override fun working(route: AssistantRoute) {
        sendBusy(route, true)
    }

    override fun idle(route: AssistantRoute) {
        sendBusy(route, false)
    }
}
