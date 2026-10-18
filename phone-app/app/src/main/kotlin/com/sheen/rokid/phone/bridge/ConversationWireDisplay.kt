package com.sheen.rokid.phone.bridge

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.plugin.ConversationDisplay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.24 spec §5.4/§5.5: the official conversation commands, as the display needs them.
 *
 * One method per wire command, with the same fields the codec encodes. The `Boolean` is what the link can honestly
 * say about its own send - `false` when there is no established session or the transport refused it. The display
 * ignores it: the phone's own record was written before the call, so a refused command costs the glasses the display
 * of that revision and never the record itself.
 *
 * The real implementation is the CXR link; tests use a fake. A composition with no official display gets the silent
 * wire, which refuses everything - the same meaning as "there is nowhere to show this".
 */
interface ConversationWire {

    /** The official status level for [route], in the official wire int (0–7). */
    fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long): Boolean

    /** The official `showIdleStatus` for [route] - not the legacy busy level; see [ConversationDisplay.showIdle]. */
    fun idle(route: AssistantRoute, sequence: Long): Boolean

    /** One streaming ASR revision for [turnId]; the final utterance still travels as `SAID`. */
    fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String): Boolean

    /** One streaming answer revision for [hintId] within [turnId]; the final answer still travels as the hint. */
    fun answer(route: AssistantRoute, turnId: String, hintId: String, revision: Long, text: String): Boolean

    /** A tool row for [route], before or after the labelled action runs. */
    fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String): Boolean

    /** Show the conversation panel for [route] without changing its status. */
    fun panelOpen(route: AssistantRoute): Boolean

    /** Close the conversation panel for [route] and drop the display's cached rows. */
    fun close(route: AssistantRoute): Boolean
}

/** A wire with nothing behind it: every command is refused, exactly as with no session. */
private object SilentConversationWire : ConversationWire {
    override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long): Boolean = false
    override fun idle(route: AssistantRoute, sequence: Long): Boolean = false
    override fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String): Boolean = false
    override fun answer(
        route: AssistantRoute,
        turnId: String,
        hintId: String,
        revision: Long,
        text: String,
    ): Boolean = false
    override fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String): Boolean = false
    override fun panelOpen(route: AssistantRoute): Boolean = false
    override fun close(route: AssistantRoute): Boolean = false
}

/**
 * The production [ConversationDisplay]: one statement becomes the phone's record **and** traffic for the glasses.
 *
 * Why this is its own class rather than a few lines inside `HostCompositionRoot`: the two rules it enforces are the
 * ones that would be silently wrong if they were inlined. It is testable here without a composition, an Android
 * context or a device, and the failure it prevents is a real one - a wearer whose glasses show a conversation the
 * phone's own tab does not, or the reverse.
 *
 * **Record first, send second.** The wearer said the thing (or the assistant streamed it) whether or not the link
 * was up, so a refused send costs them the *display* of a statement and never the statement itself. That is also
 * what keeps the two screens honest about each other: the phone's tab is a record of the conversation, not a log of
 * successful transmissions.
 *
 * **Two wires, because the old and official paths are not the same command.** The legacy paths stay as they were:
 * [userMessage] sends `SAID` through [sendSaid] and [working]/[idle] send the busy level through [sendBusy]. The
 * 0.24 methods forward `STATE/IDLE/ASR/ANSWER/TOOL/PANEL_OPEN/CLOSE` through [wire]. The final answer is not sent
 * from this class at all: it reaches the glasses as the hint (`SHOW`), which already carries its route, ack and TTL.
 *
 * **The turn id is re-sent on every revision.** [ConversationLog] replaces the whole entry when an entry id repeats,
 * so a partial revision that carried only its new text would erase the `turnId` the previous revision stored and
 * break the per-turn grouping on the glasses. Every append here passes the turn id again.
 *
 * Entry ids follow the 0.24 convention when the producer knows the ids: `asr-${turnId}` for the wearer's utterance
 * and `say-${hintId}` for the assistant's answer. The pre-0.24 entry points keep minting their old unique ids
 * (`said-…` / `say-…`) because an old caller has neither a turn nor a hint id to name the entry with.
 *
 * **A final call keeps the line its partial opened.** The interface's final calls carry no turn/hint id, so the
 * entry opened by [userPartial]/[assistantPartial] is remembered per route and the final statement replaces that
 * same entry, carrying the remembered turn id. Without it, every streamed answer would become two rows and, because
 * the log replaces the whole entry, a final without the remembered turn would clear it. A final with no partial
 * before it keeps the old behaviour (a fresh entry id). A status for a different turn, or a panel close, forgets the
 * remembered entry so a cancelled turn cannot capture the next final.
 */
class ConversationWireDisplay(
    private val log: ConversationLog,
    /** The wire every official command is forwarded to; the production assembly passes the CXR link. */
    internal val wire: ConversationWire,
    private val sendSaid: (AssistantRoute, String) -> Boolean,
    private val sendBusy: (AssistantRoute, Boolean) -> Boolean,
) : ConversationDisplay {

    /**
     * A display whose composition has no official wire yet (an old host, or a link that has not implemented
     * [ConversationWire]). The phone's record still works; only the official commands have nowhere to go.
     */
    constructor(
        log: ConversationLog,
        sendSaid: (AssistantRoute, String) -> Boolean,
        sendBusy: (AssistantRoute, Boolean) -> Boolean,
    ) : this(log, SilentConversationWire, sendSaid, sendBusy)

    /** The entry a streaming revision opened, waiting for its final statement. */
    private data class OpenEntry(val entryId: String, val turnId: String)

    private val openUserEntry = ConcurrentHashMap<AssistantRoute, OpenEntry>()
    private val openAnswerEntry = ConcurrentHashMap<AssistantRoute, OpenEntry>()

    override fun userMessage(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        val open = openUserEntry.remove(route)
        log.append(
            if (open == null) {
                ConversationEntry("said-${UUID.randomUUID()}", route, ConversationRole.USER, text)
            } else {
                ConversationEntry(open.entryId, route, ConversationRole.USER, text, open.turnId)
            },
        )
        sendSaid(route, text)
    }

    /**
     * The final utterance when the producer knows its turn (0.24 spec §5.5).
     *
     * Records under `asr-${turnId}` - the id [userPartial] used - carrying the turn, then sends the final through
     * the legacy `SAID` path. A caller that streamed partials gets one entry, not a second row.
     */
    fun userMessage(route: AssistantRoute, turnId: String, text: String) {
        if (text.isBlank() || turnId.isBlank()) return
        openUserEntry.remove(route)
        log.append(ConversationEntry("asr-$turnId", route, ConversationRole.USER, text, turnId))
        sendSaid(route, text)
    }

    override fun assistantMessage(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        val open = openAnswerEntry.remove(route)
        log.append(
            if (open == null) {
                ConversationEntry("say-${UUID.randomUUID()}", route, ConversationRole.ASSISTANT, text)
            } else {
                ConversationEntry(open.entryId, route, ConversationRole.ASSISTANT, text, open.turnId)
            },
        )
    }

    /**
     * The final answer when the producer knows its turn and hint id (0.24 spec §5.5).
     *
     * Records under `say-${hintId}` - the id [assistantPartial] used - carrying the turn. Deliberately sends nothing:
     * the final answer reaches the glasses as the hint (`SHOW`), and re-sending it here would duplicate it.
     */
    fun assistantMessage(route: AssistantRoute, turnId: String, hintId: String, text: String) {
        if (text.isBlank() || turnId.isBlank() || hintId.isBlank()) return
        openAnswerEntry.remove(route)
        log.append(ConversationEntry("say-$hintId", route, ConversationRole.ASSISTANT, text, turnId))
    }

    override fun working(route: AssistantRoute) {
        sendBusy(route, true)
    }

    override fun idle(route: AssistantRoute) {
        sendBusy(route, false)
    }

    override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
        forgetEntriesFromOtherTurns(route, turnId)
        wire.state(route, turnId, status, sequence)
    }

    override fun showIdle(route: AssistantRoute, sequence: Long) {
        wire.idle(route, sequence)
    }

    override fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) {
        if (text.isBlank() || turnId.isBlank()) return
        val entryId = "asr-$turnId"
        log.append(ConversationEntry(entryId, route, ConversationRole.USER, text, turnId))
        openUserEntry[route] = OpenEntry(entryId, turnId)
        wire.asr(route, turnId, revision, text)
    }

    override fun assistantPartial(
        route: AssistantRoute,
        turnId: String,
        hintId: String,
        revision: Long,
        text: String,
    ) {
        if (text.isBlank() || turnId.isBlank() || hintId.isBlank()) return
        val entryId = "say-$hintId"
        log.append(ConversationEntry(entryId, route, ConversationRole.ASSISTANT, text, turnId))
        openAnswerEntry[route] = OpenEntry(entryId, turnId)
        wire.answer(route, turnId, hintId, revision, text)
    }

    override fun toolStatus(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) {
        wire.tool(route, turnId, phase, label)
    }

    override fun panelOpen(route: AssistantRoute) {
        wire.panelOpen(route)
    }

    override fun closePanel(route: AssistantRoute) {
        // The panel is being dropped, so a streaming revision's final must not update a line the wearer can no
        // longer see. The phone's record keeps the entries; only the pending correlation is forgotten.
        openUserEntry.remove(route)
        openAnswerEntry.remove(route)
        wire.close(route)
    }

    /** A status for a different turn means the pending revision belongs to a turn that ended without a final. */
    private fun forgetEntriesFromOtherTurns(route: AssistantRoute, turnId: String) {
        openUserEntry[route]?.takeIf { it.turnId != turnId }?.let { openUserEntry.remove(route, it) }
        openAnswerEntry[route]?.takeIf { it.turnId != turnId }?.let { openAnswerEntry.remove(route, it) }
    }
}
