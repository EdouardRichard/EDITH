package edith.agent

import edith.plugin.MessageRole
import edith.plugin.ModelHistoryItem

/**
 * Per-route session stream holding ordered segments (spec §3.3).
 *
 * This replaces ConversationContext's role as the mutable accumulator of conversation state.
 * Each route gets its own independent store; stores do not share state.
 *
 * Thread safety: all mutating methods and [snapshot] are synchronized to allow
 * concurrent access from agent loop threads.
 *
 * ## Storage shape
 *
 * The stored shape is [ModelHistoryItem] — the wire shape — with attribution kept beside it as
 * metadata ([SessionItem.origin] / [SessionItem.role]), never inferred from text. A summary's text is
 * stored **with** its [SummaryBoundary]: the boundary is rendered once, at store-insert time, so
 * [history] and [snapshot] return the **same** bounded text and no later stage can add a byte the byte
 * accounting never saw. (A clean body would be measured clean and sent rendered: +64 B for the
 * boundary, a latent over-budget rejection.)
 *
 * ## The summary invariant (the tests pin this)
 *
 * A `SUMMARY` item may appear **only at index 0**. After [replaceWithSummary] the store holds a single
 * summary; the next append is a new user turn, so in "summary + turns" the summary covers every turn
 * before it and must be kept. A summary that sits *after* a turn means earlier turns still precede it,
 * so it has been superseded by a newer summary: it takes no part in whole-turn eviction, and eviction
 * removes it — by [dropOldestTurns] or [removeOldestTurns], or by any append other than the one that created it
 * ([enforceEnvelope]). This is also why a summary takes no part in whole-turn eviction: it is not a turn.
 *
 * @param maxItems the maximum number of stored messages the envelope keeps. Defaults to [MAX_ITEMS],
 *   which equals `ConversationContext.MAX_MESSAGES`, so the default behaviour is unchanged.
 */
class ConversationStore(val maxItems: Int = MAX_ITEMS) {

    init {
        require(maxItems >= 1) { "maxItems must be at least 1, was $maxItems" }
    }

    private data class SessionItem(val item: ModelHistoryItem, val origin: SegmentOrigin, val role: SegmentRole?)

    private val items = mutableListOf<SessionItem>()

    /**
     * Appends a user message as a TRANSCRIPT item trusted=true, with [SegmentRole.USER].
     */
    @Synchronized
    fun appendUser(text: String) =
        add(ModelHistoryItem.Text(MessageRole.USER, text), SegmentOrigin.TRANSCRIPT, SegmentRole.USER)

    /**
     * Appends an assistant message as a TRANSCRIPT item trusted=true, with [SegmentRole.ASSISTANT].
     */
    @Synchronized
    fun appendAssistant(text: String) =
        add(ModelHistoryItem.Text(MessageRole.ASSISTANT, text), SegmentOrigin.TRANSCRIPT, SegmentRole.ASSISTANT)

    /**
     * Appends a tool call as a TOOL_ROUND item trusted=true. A tool call is not conversation-flow text,
     * so it carries no role.
     */
    @Synchronized
    fun appendToolCall(callId: String, name: String, argsJson: String, itemId: String = callId) =
        add(
            ModelHistoryItem.ToolCall(callId = callId, name = name, argsJson = argsJson, itemId = itemId),
            SegmentOrigin.TOOL_ROUND,
            null,
        )

    /**
     * Appends a tool output as a TOOL_ROUND item trusted=true. A tool output is not conversation-flow
     * text, so it carries no role.
     */
    @Synchronized
    fun appendToolOutput(callId: String, output: String) =
        add(ModelHistoryItem.ToolOutput(callId, output), SegmentOrigin.TOOL_ROUND, null)

    /**
     * Appends a tool round summary as a TOOL_ROUND item trusted=true, with [SegmentRole.ASSISTANT].
     */
    @Synchronized
    fun appendToolRound(summary: String) =
        add(ModelHistoryItem.Text(MessageRole.ASSISTANT, summary), SegmentOrigin.TOOL_ROUND, SegmentRole.ASSISTANT)

    /**
     * Appends a compression-produced summary as a SUMMARY item trusted=false, with
     * [SegmentRole.ASSISTANT]. The boundary is rendered **here**, at the boundary of the store: the stored
     * text is the bounded rendering, so [history] and [snapshot] hand out the same bytes and the byte
     * accounting of a request measures exactly what is sent. A body that already carries the boundary is
     * passed through unchanged rather than wrapped twice.
     *
     * **The append that creates a summary never drops it.** Appending a summary that ends up *after* a turn
     * makes it a superseded summary, but this method only appends and skips the sweep: the removal is a
     * consequence of the summary invariant and happens at the next eviction — [dropOldestTurns] or
     * [removeOldestTurns], or any later append (see [enforceEnvelope]). A non-head summary is therefore transiently
     * visible through [snapshot] and [history] until that next eviction, and never after the append that created it.
     */
    @Synchronized
    fun appendSummary(text: String) =
        add(
            ModelHistoryItem.Text(MessageRole.ASSISTANT, boundedBody(text)),
            SegmentOrigin.SUMMARY,
            SegmentRole.ASSISTANT,
            sweepSuperseded = false,
        )

    /**
     * Clears all existing items and replaces them with a single SUMMARY item (trusted=false).
     *
     * Used when context compression produces a summary that supersedes the prior transcript.
     */
    @Synchronized
    fun replaceWithSummary(text: String) {
        items.clear()
        appendSummary(text)
    }

    /**
     * Returns the conversation in wire shape, in order. A summary carries the boundary rendering that
     * [appendSummary] applied at insert time; [snapshot] returns the same text, not a stripped body.
     */
    @Synchronized
    fun history(): List<ModelHistoryItem> = items.map { it.item }

    /**
     * Returns an immutable defensive copy of the current segment list.
     *
     * Later mutations to this store do not affect previously returned snapshots. A summary segment holds the
     * **bounded** text — [SummaryBoundary] is rendered at insert time ([appendSummary]), so this view and
     * [history] are byte-identical and a request measured from this snapshot is a request that fits.
     */
    @Synchronized
    fun snapshot(): List<Segment> = items.map {
        Segment(
            origin = it.origin,
            trusted = it.origin == SegmentOrigin.TRANSCRIPT || it.origin == SegmentOrigin.TOOL_ROUND,
            text = textOf(it.item),
            role = it.role,
        )
    }

    /** The number of stored messages. */
    @Synchronized
    fun size(): Int = items.size

    /** Removes every stored message. */
    @Synchronized
    fun clear() = items.clear()

    /** The role of the last stored message, or `null` when the store is empty or ends on a tool item. */
    @Synchronized
    fun lastRole(): SegmentRole? = items.lastOrNull()?.role

    /** Whether the conversation currently ends on a user message with no assistant reply yet. */
    @Synchronized
    fun hasPendingUserTurn(): Boolean = lastRole() == SegmentRole.USER

    /**
     * Drops the oldest whole turns until `size <= maxItems`, and returns the number of messages dropped —
     * the superseded summaries removed by the sweep plus the turn items evicted.
     *
     * The parameter is a **target size**, not a count: `dropOldestTurns(1)` on a five-message store leaves one
     * message, not one turn fewer. Use [removeOldestTurns] when the caller knows how many **turns** to give up.
     *
     * A whole turn is one user message plus the assistant/tool round it produced; a tool round is never
     * orphaned from the turn that caused it. The **last** user turn is never dropped — and because a turn
     * is dropped whole, a drop that would remove the last user turn never begins.
     */
    @Synchronized
    fun dropOldestTurns(maxItems: Int): Int = enforceEnvelope(maxItems)

    /**
     * Removes exactly [count] oldest whole turns and returns the number of messages that left — the superseded
     * summaries removed by the sweep plus the turn items. Unlike [dropOldestTurns], whose parameter is a **target
     * size**, [count] is a **number of turns**: one turn dropped from `[u1, a1, u2, a2, u3]` leaves
     * `[u2, a2, u3]`, three messages, not one.
     *
     * The turn boundary is the same one [dropOldestTurns] uses (both go through [dropLeadingTurn]), so a tool round
     * is never orphaned from its turn. The **last** user turn is never removed: a [count] larger than the number of
     * droppable turns removes what it can and reports the true figure rather than failing.
     *
     * A non-positive [count] changes nothing and returns 0.
     */
    @Synchronized
    fun removeOldestTurns(count: Int): Int {
        if (count <= 0) return 0
        val superseded = dropSupersededSummaries()
        var dropped = 0
        var remaining = count
        while (remaining > 0) {
            val gone = dropLeadingTurn()
            if (gone == 0) break
            dropped += gone
            remaining--
        }
        return superseded + dropped
    }

    /**
     * Appends [item] and enforces the envelope.
     *
     * Every append except the one that creates a summary sweeps and evicts together ([enforceEnvelope]), so
     * the summary invariant holds after every append. The exception is [appendSummary], which passes
     * `sweepSuperseded = false`: the append that creates a summary must not immediately drop its own summary
     * (supersession is lazy), so that append only evicts ([evictTurns]).
     */
    private fun add(
        item: ModelHistoryItem,
        origin: SegmentOrigin,
        role: SegmentRole?,
        sweepSuperseded: Boolean = true,
    ) {
        items += SessionItem(item, origin, role)
        if (sweepSuperseded) enforceEnvelope(maxItems) else evictTurns(maxItems)
    }

    /**
     * Enforces the envelope: first drop every SUMMARY that is no longer at the head (it is superseded by the
     * newer summary that covers the turns before it), then drop whole turns from the head. Returns the true
     * number of messages dropped — the superseded summaries plus the turn items.
     *
     * **This and [removeOldestTurns] are the only places a superseded summary is removed.** The invariant is lazy:
     * a non-head summary is *superseded* and is removed at an eviction — by [dropOldestTurns], by
     * [removeOldestTurns], or by any other append — never by the append that created it ([appendSummary] passes
     * `sweepSuperseded = false`; see its KDoc and [add]).
     *
     * A summary is never evicted as a turn — it is not a turn. When the only remaining turn is the last user
     * turn, the envelope is allowed to overrun rather than empty the conversation, the same trade-off
     * `ContextCompressor` already makes with its `ANCHORED` reason.
     */
    private fun enforceEnvelope(target: Int): Int {
        val superseded = dropSupersededSummaries()
        return superseded + evictTurns(target)
    }

    /**
     * Drops every SUMMARY that is not at index 0 and returns how many were removed. See the summary invariant
     * in the class KDoc.
     */
    private fun dropSupersededSummaries(): Int {
        var dropped = 0
        var index = items.size - 1
        while (index > 0) {
            if (items[index].origin == SegmentOrigin.SUMMARY) {
                items.removeAt(index)
                dropped++
            }
            index--
        }
        return dropped
    }

    /** Drops whole turns from the head while more than [target] messages remain. Returns the count dropped. */
    private fun evictTurns(target: Int): Int {
        var dropped = 0
        while (items.size > target) {
            val gone = dropLeadingTurn()
            if (gone == 0) break
            dropped += gone
        }
        return dropped
    }

    /**
     * Removes the oldest whole turn — its user message plus every message up to the next one — and returns how many
     * messages it gave up. Returns 0 and removes nothing when there is no turn left to give: either no user message
     * remains, or only the **last** user turn does. This is the one turn-boundary routine [evictTurns] (for
     * [dropOldestTurns]) and [removeOldestTurns] share, so the two can never disagree about where a turn ends.
     */
    private fun dropLeadingTurn(): Int {
        val start = turnStart(0) ?: return 0
        val next = turnStart(start + 1) ?: return 0
        repeat(next - start) { items.removeAt(start) }
        return next - start
    }

    /** The index of the first user message at or after [index], or `null` when there is none. */
    private fun turnStart(index: Int): Int? {
        for (i in index until items.size) {
            val item = items[i].item
            if (item is ModelHistoryItem.Text && item.role == MessageRole.USER) return i
        }
        return null
    }

    /**
     * The stored text of a summary: the body wrapped in the explicit [SummaryBoundary], rendered **once**.
     *
     * A body that already carries the boundary is passed through unchanged, so a caller that hands over an
     * already-bounded rendering does not get a second layer. Attribution never depends on this: a `SUMMARY` is
     * a `SUMMARY` because of [SegmentOrigin.SUMMARY], not because its text looks bounded.
     */
    private fun boundedBody(text: String): String =
        if (SummaryBoundary.isRendered(text)) text else SummaryBoundary.render(text)

    /**
     * The text of a stored item. `estimatedText()` is `internal` to `plugins-smart`'s `TokenBudget.kt`
     * and is not visible to this module, so the text is taken here directly.
     */
    private fun textOf(item: ModelHistoryItem): String = when (item) {
        is ModelHistoryItem.Text -> item.text
        is ModelHistoryItem.ToolCall -> item.name + item.argsJson
        is ModelHistoryItem.ToolOutput -> item.output
    }

    companion object {
        const val MAX_ITEMS = 200
    }
}
