package com.sheen.rokid.phone.bridge

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.phone.overlay.PhoneBubbleEvent
import com.sheen.rokid.phone.overlay.PhoneBubbleFeed
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
 * **One serial executor per route.** [userPartial]/[assistantPartial] run on the plugin pool, the final-user statement
 * runs there too, and Task 16's answer deltas run on the transport thread. The record, the pending-entry memory and
 * the send must be one atomic step per route, or a final can land between a partial's append and its pending-set and
 * mint a second row (`say-<UUID>` / `said-<UUID>`). [routeLocks] is that serial executor; the two routes are
 * independent, so a stalled wake send cannot freeze proactive traffic. A partial that arrives **after** its final is
 * dropped as well: the final is the authoritative text, and re-recording a late revision would overwrite it.
 * [lastAnswerRevision] adds the other half of the unordered-channel rule: a revision older than the one already
 * applied is dropped before it touches either screen.
 *
 * **The turn id is re-sent on every revision.** [ConversationLog] replaces the whole entry when an entry id repeats,
 * so a partial revision that carried only its new text would erase the `turnId` the previous revision stored and
 * break the per-turn grouping on the glasses. Every append here passes the turn id again.
 *
 * Entry ids follow the 0.24 convention when the producer knows the ids: `asr-${turnId}` for the wearer's utterance
 * and `say-${hintId}` for the assistant's answer. [userFinal] takes the exact id from its producer, so the final
 * replaces the line the partial opened and travels as `SAID` under that same id. The pre-0.24 entry points keep
 * minting their old unique ids (`said-…` / `say-…`) because an old caller has neither a turn nor a hint id to name
 * the entry with.
 *
 * **A final keeps the line its partial opened.** The legacy final calls carry no turn/hint id, so the entry opened by
 * [userPartial]/[assistantPartial] is remembered per route and the final statement replaces that same entry, carrying
 * the remembered turn id. A final with no partial before it keeps the old behaviour (a fresh entry id). A status for a
 * different turn, or a panel close, forgets the remembered entry so a cancelled turn cannot capture the next final.
 */
class ConversationWireDisplay(
    private val log: ConversationLog,
    /** The wire every official command is forwarded to; the production assembly passes the CXR link. */
    internal val wire: ConversationWire,
    private val sendSaid: (AssistantRoute, String, String) -> Boolean,
    private val sendBusy: (AssistantRoute, Boolean) -> Boolean,
    /**
     * 0.5: the phone-only message stack's hook. App-internal (not part of the plugin contract); the default is the
     * pre-0.5 behavior, so every existing construction and test keeps working without a feed.
     */
    private val bubbleFeed: PhoneBubbleFeed = PhoneBubbleFeed.NONE,
) : ConversationDisplay {

    /**
     * A display whose composition has no official wire yet (an old host, or a link that has not implemented
     * [ConversationWire]). The phone's record still works; only the official commands have nowhere to go.
     */
    constructor(
        log: ConversationLog,
        sendSaid: (AssistantRoute, String, String) -> Boolean,
        sendBusy: (AssistantRoute, Boolean) -> Boolean,
    ) : this(log, SilentConversationWire, sendSaid, sendBusy)

    /** The entry a streaming revision opened, waiting for its final statement. */
    private data class OpenEntry(val entryId: String, val turnId: String)

    /** One monitor per route: the unit of serialization is a route's record/pending/send sequence. */
    private val routeLocks: Map<AssistantRoute, Any> =
        AssistantRoute.entries.associateWith { Any() }

    private val openUserEntry = ConcurrentHashMap<AssistantRoute, OpenEntry>()
    private val openAnswerEntry = ConcurrentHashMap<AssistantRoute, OpenEntry>()

    /**
     * The recently finalized entry ids per route, per direction.
     *
     * A late ASR/ANSWER revision for one of these ids is stale by definition: the final already replaced the line.
     * Keeping a small **set** rather than only the latest id matters because a revision can be delayed across a turn
     * boundary: after turn 2 finalizes, a late revision of turn 1 must still not reopen turn 1's final. A **new** turn
     * has a new entry id and is therefore not blocked. Bounded so a long session cannot grow the display's memory.
     * Guarded by the route's monitor.
     */
    private val finalizedUserEntries = mutableMapOf<AssistantRoute, MutableSet<String>>()
    private val finalizedAnswerEntries = mutableMapOf<AssistantRoute, MutableSet<String>>()

    /**
     * The last accepted answer revision per route, as (entry id, revision).
     *
     * Task 16: `ANSWER` revisions arrive on the transport thread and the channel promises no ordering, so a revision
     * that lost the race must not overwrite a newer one in the phone record or on the glasses. The domain is the
     * answer entry: a new hint id starts fresh. Guarded by the route's monitor.
     */
    private val lastAnswerRevision = mutableMapOf<AssistantRoute, Pair<String, Long>>()

    private fun lockFor(route: AssistantRoute): Any = routeLocks.getValue(route)

    /** Remembers [entryId] as final for [route]; caller holds the route's monitor. */
    private fun markFinalized(
        byRoute: MutableMap<AssistantRoute, MutableSet<String>>,
        route: AssistantRoute,
        entryId: String,
    ) {
        val finalized = byRoute.getOrPut(route) { linkedSetOf() }
        finalized += entryId
        while (finalized.size > FINALIZED_ENTRIES_PER_ROUTE) {
            val oldest = finalized.firstOrNull() ?: break
            finalized.remove(oldest)
        }
    }

    private fun isFinalized(
        byRoute: Map<AssistantRoute, MutableSet<String>>,
        route: AssistantRoute,
        entryId: String,
    ): Boolean = byRoute[route]?.contains(entryId) == true

    override fun userMessage(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        synchronized(lockFor(route)) {
            val open = openUserEntry.remove(route)
            val entry = if (open == null) {
                ConversationEntry("said-${UUID.randomUUID()}", route, ConversationRole.USER, text)
            } else {
                ConversationEntry(open.entryId, route, ConversationRole.USER, text, open.turnId)
            }
            markFinalized(finalizedUserEntries, route, entry.entryId)
            log.append(entry)
            sendSaid(route, entry.entryId, text)
            bubbleFeed.onEvent(PhoneBubbleEvent.User(route, entry.turnId, text))
        }
    }

    /**
     * The final utterance when the producer knows its turn (0.24 spec §5.5).
     *
     * Records under the id [userPartial] used, carrying the turn, then sends the final through the legacy `SAID`
     * path under **the same id**. A caller that streamed partials gets one entry, not a second row.
     */
    override fun userFinal(route: AssistantRoute, turnId: String, entryId: String, text: String) {
        if (text.isBlank() || turnId.isBlank() || entryId.isBlank()) return
        synchronized(lockFor(route)) {
            openUserEntry.remove(route)
            markFinalized(finalizedUserEntries, route, entryId)
            log.append(ConversationEntry(entryId, route, ConversationRole.USER, text, turnId))
            sendSaid(route, entryId, text)
            bubbleFeed.onEvent(PhoneBubbleEvent.User(route, turnId, text))
        }
    }

    /** The 0.24 convenience shape: the final under the documented `asr-${turnId}` id. */
    fun userMessage(route: AssistantRoute, turnId: String, text: String) {
        if (turnId.isBlank()) return
        userFinal(route, turnId, "asr-$turnId", text)
    }

    override fun assistantMessage(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        synchronized(lockFor(route)) {
            val open = openAnswerEntry.remove(route)
            val entry = if (open == null) {
                ConversationEntry("say-${UUID.randomUUID()}", route, ConversationRole.ASSISTANT, text)
            } else {
                ConversationEntry(open.entryId, route, ConversationRole.ASSISTANT, text, open.turnId)
            }
            markFinalized(finalizedAnswerEntries, route, entry.entryId)
            log.append(entry)
            bubbleFeed.onEvent(PhoneBubbleEvent.AnswerFinal(route, null, null, text))
        }
    }

    /**
     * The final answer when the producer knows its turn and hint id (0.24 spec §5.5).
     *
     * Records under `say-${hintId}` - the id [assistantPartial] used - carrying the turn. Deliberately sends nothing:
     * the final answer reaches the glasses as the hint (`SHOW`), and re-sending it here would duplicate it.
     */
    fun assistantMessage(route: AssistantRoute, turnId: String, hintId: String, text: String) {
        if (text.isBlank() || turnId.isBlank() || hintId.isBlank()) return
        synchronized(lockFor(route)) {
            openAnswerEntry.remove(route)
            markFinalized(finalizedAnswerEntries, route, "say-$hintId")
            log.append(ConversationEntry("say-$hintId", route, ConversationRole.ASSISTANT, text, turnId))
            bubbleFeed.onEvent(PhoneBubbleEvent.AnswerFinal(route, turnId, hintId, text))
        }
    }

    /**
     * 0.24 S4 Task 16: the exact-identity final answer from the plugin.
     *
     * Delegates to [assistantMessage]'s turn-carrying shape: the same `say-${hintId}` entry the revisions used, with
     * the final text, and no wire send (the glasses get it as the hint `SHOW`). Switching to this call is what keys
     * `finalizedAnswerEntries` to the documented id even when no revision was ever streamed.
     */
    override fun assistantFinal(route: AssistantRoute, turnId: String, hintId: String, text: String) {
        assistantMessage(route, turnId, hintId, text)
    }

    /**
     * 0.24 S4 Task 16 I1: one assistant statement that is not the turn's answer (the explicit-memory acknowledgement).
     *
     * It gets its own assistant entry - a fresh `say-` id - and deliberately does **not** consume
     * [openAnswerEntry]. The acknowledgement can be emitted while a streamed answer's final is still pending (the
     * explicit-memory + closing-fallback order), and consuming that pending entry would write the acknowledgement into
     * the answer row before the answer final arrives. Nothing is sent: the glasses already received the statement as
     * the hint (`SHOW`). The entry is not marked as a finalized answer either - it is not one.
     */
    override fun assistantNotice(route: AssistantRoute, text: String) {
        if (text.isBlank()) return
        synchronized(lockFor(route)) {
            log.append(ConversationEntry("say-${UUID.randomUUID()}", route, ConversationRole.ASSISTANT, text))
            bubbleFeed.onEvent(PhoneBubbleEvent.Notice(route, text))
        }
    }

    override fun working(route: AssistantRoute) {
        sendBusy(route, true)
    }

    override fun idle(route: AssistantRoute) {
        sendBusy(route, false)
    }

    override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
        synchronized(lockFor(route)) {
            forgetEntriesFromOtherTurns(route, turnId)
            wire.state(route, turnId, status, sequence)
        }
    }

    override fun showIdle(route: AssistantRoute, sequence: Long) {
        wire.idle(route, sequence)
        bubbleFeed.onEvent(PhoneBubbleEvent.TurnIdle(route))
    }

    override fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) {
        if (text.isBlank() || turnId.isBlank()) return
        val entryId = "asr-$turnId"
        synchronized(lockFor(route)) {
            if (isFinalized(finalizedUserEntries, route, entryId)) return
            log.append(ConversationEntry(entryId, route, ConversationRole.USER, text, turnId))
            openUserEntry[route] = OpenEntry(entryId, turnId)
            wire.asr(route, turnId, revision, text)
        }
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
        synchronized(lockFor(route)) {
            if (isFinalized(finalizedAnswerEntries, route, entryId)) return
            val last = lastAnswerRevision[route]
            if (last != null && last.first == entryId && revision <= last.second) return
            lastAnswerRevision[route] = entryId to revision
            log.append(ConversationEntry(entryId, route, ConversationRole.ASSISTANT, text, turnId))
            openAnswerEntry[route] = OpenEntry(entryId, turnId)
            wire.answer(route, turnId, hintId, revision, text)
            bubbleFeed.onEvent(PhoneBubbleEvent.AnswerPartial(route, turnId, hintId, text))
        }
    }

    override fun toolStatus(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) {
        wire.tool(route, turnId, phase, label)
        // A blank label is not a row the wearer can read; the wire still receives it unchanged (existing behavior).
        if (label.isNotBlank()) bubbleFeed.onEvent(PhoneBubbleEvent.Tool(route, turnId, label, phase))
    }

    override fun panelOpen(route: AssistantRoute) {
        wire.panelOpen(route)
    }

    override fun closePanel(route: AssistantRoute) {
        // The panel is being dropped, so a streaming revision's final must not update a line the wearer can no
        // longer see. The phone's record keeps the entries; only the pending correlation is forgotten.
        synchronized(lockFor(route)) {
            openUserEntry.remove(route)
            openAnswerEntry.remove(route)
            wire.close(route)
            bubbleFeed.onEvent(PhoneBubbleEvent.TurnIdle(route))
        }
    }

    /** A status for a different turn means the pending revision belongs to a turn that ended without a final. */
    private fun forgetEntriesFromOtherTurns(route: AssistantRoute, turnId: String) {
        openUserEntry[route]?.takeIf { it.turnId != turnId }?.let { openUserEntry.remove(route, it) }
        openAnswerEntry[route]?.takeIf { it.turnId != turnId }?.let { openAnswerEntry.remove(route, it) }
    }

    private companion object {
        /**
         * How many finalized entry ids one route keeps.
         *
         * Eight covers a realistic reorder window (a delayed ASR/ANSWER revision arriving after a later turn's
         * final) without letting a long session's history grow without bound. Dropping the oldest only means a
         * revision older than that many finalized turns could reopen its row; it still cannot touch a newer final.
         */
        const val FINALIZED_ENTRIES_PER_ROUTE = 8
    }
}
