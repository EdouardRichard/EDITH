package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem

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
 * A `SUMMARY` item may appear **only at index 0**, and there is at most one. Every public mutator maintains that
 * atomically: [appendSummary] lands its summary at the head and removes every other `SUMMARY` **in the same call**, so
 * the store can never be observed holding two summaries — the shape `[newer, older, turn, …]` does not exist, not even
 * between two calls, and a frame built from [history] therefore can never relabel a stranded older summary as an
 * assistant turn. A summary *after* a turn is consequently unreachable through this API; the sweep of non-head
 * summaries in [enforceEnvelope] and [removeOldestTurns] remains as the fallback for any future path that introduces
 * one. This is also why a summary takes no part in whole-turn eviction: it is not a turn.
 *
 * The landing append must not drop **its own** summary, and it must drop the previously superseded one. Both hold in
 * the same call because the rule is positional: [dropSupersededSummaries] removes only a `SUMMARY` **past index 0**,
 * and a freshly landed summary is at index 0.
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
     * Appends a turn's transcript in order — the user text, the tool calls and their outputs, and the closing
     * assistant text (0.25 spec §5.1/§5.3). This is the production record path: the plugin hands over the run's own
     * transcript instead of rebuilding it from two text halves, so a multi-step turn keeps its tool rounds and their
     * image references across turns.
     *
     * ## Only complete tool rounds are stored
     *
     * A run that ends early (step limit, cancellation, provider failure) can leave a `ToolCall` whose `ToolOutput`
     * never arrived. Storing it would make the next request echo a call the endpoint never gets an answer for, and the
     * wire shape refuses an unanswered call (R10). The rule is local to this append: a call is kept only when the same
     * batch carries an output with the same call id, and an output with no call in the batch is dropped for the same
     * reason. Text items are never dropped by this filter, so a truncated turn still records what was said.
     *
     * The plugin must apply [withoutIncompleteToolRounds] to a turn **before** it appends the closing reply: a turn
     * that ended on a dangling call then ends on the assistant opener text, which the closing step replaces. If the
     * reply were appended first, the opener and the reply would be two adjacent assistant texts — the one shape
     * `ContextBudgeter` refuses, which would make every later turn of that session fail until the app restarted.
     *
     * The items are stored as they are, including [ModelHistoryItem.ToolOutput.images]: the store keeps image
     * references (never bytes) and the frame renderer writes them back into the tool-round JSON.
     */
    @Synchronized
    fun appendTranscript(items: List<ModelHistoryItem>) {
        if (items.isEmpty()) return
        for (item in withoutIncompleteToolRounds(items)) {
            when (item) {
                is ModelHistoryItem.Text -> add(item, SegmentOrigin.TRANSCRIPT, roleOf(item.role))
                is ModelHistoryItem.ToolCall, is ModelHistoryItem.ToolOutput ->
                    add(item, SegmentOrigin.TOOL_ROUND, null)
            }
        }
    }

    /** The segment role a transcript text carries: only `USER` is user speech; every other text is the assistant's. */
    private fun roleOf(role: MessageRole): SegmentRole =
        if (role == MessageRole.USER) SegmentRole.USER else SegmentRole.ASSISTANT

    /**
     * Appends a tool round summary as a TOOL_ROUND item trusted=true, with [SegmentRole.ASSISTANT].
     */
    @Synchronized
    fun appendToolRound(summary: String) =
        add(ModelHistoryItem.Text(MessageRole.ASSISTANT, summary), SegmentOrigin.TOOL_ROUND, SegmentRole.ASSISTANT)

    /**
     * Inserts a compression-produced summary at the **head** (index 0) as a SUMMARY item trusted=false, with
     * [SegmentRole.ASSISTANT], and removes every summary it supersedes **in the same call** (see the class KDoc). The
     * head is the only position the invariant allows, so a summary inserted at the tail would be swept by the very
     * next append while a head insert makes the "the summary is what lets the next turn start compacted" property
     * hold. Supersession is atomic: a second compression can no longer be observed as `[newer, older, …]`, so a caller
     * reading the store right after it sees exactly one summary, at the head.
     *
     * The removal cannot reach the summary just landed — that one is at index 0 and [dropSupersededSummaries] removes
     * only items past index 0 — so the two requirements hold without a flag: the append that **creates** a summary
     * never drops **its own** summary, while it does drop the previously superseded one.
     *
     * The boundary is rendered **here**, at the boundary of the store: the stored text is the bounded rendering, so
     * [history] and [snapshot] hand out the same bytes and the byte accounting of a request measures exactly what is
     * sent. A body that already carries the boundary is passed through unchanged rather than wrapped twice.
     */
    @Synchronized
    fun appendSummary(text: String) =
        insertSummaryAtHead(ModelHistoryItem.Text(MessageRole.ASSISTANT, boundedBody(text)))

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

    /**
     * Whether the head item is this store's summary — answered from the stored [SegmentOrigin], never from the text.
     *
     * The store guarantees, after **every** public mutation, that a summary occupies only index 0 and that there is at
     * most one (see the class KDoc: [appendSummary] replaces the previous summary in the same call rather than
     * displacing it). "Is the head a summary" is therefore exactly the question a caller rebuilding a request from
     * [history] has to ask: [history] returns the wire shape, which cannot carry attribution, and the head summary is
     * the one item whose `SUMMARY` origin a flat list loses. A caller that answers it from the rendered
     * [SummaryBoundary] instead would be classifying by text, which the boundary's own KDoc forbids.
     */
    @Synchronized
    fun headIsSummary(): Boolean = items.firstOrNull()?.origin == SegmentOrigin.SUMMARY

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
     * Lands a summary at the head, removes every summary it supersedes, then enforces the envelope.
     *
     * Replacement happens **inside this one call**: the item goes in, [dropSupersededSummaries] removes any other
     * `SUMMARY` (the one this summary supersedes), and only then does [evictTurns] run. Appending without the removal
     * would leave the store holding `[newer, older, …]` for a whole turn — long enough for the next turn's frame to
     * render the older one as an assistant turn — so this is what makes the invariant hold after every append rather
     * than lazily.
     *
     * The sweep runs **before** the eviction for the same reason [enforceEnvelope] does: a summary a later call would
     * remove must not count toward the envelope and cost a turn its place. And it can never remove the item just
     * inserted, which is at index 0, while the sweep only removes items past index 0.
     */
    private fun insertSummaryAtHead(item: ModelHistoryItem) {
        items.add(0, SessionItem(item, SegmentOrigin.SUMMARY, SegmentRole.ASSISTANT))
        dropSupersededSummaries()
        evictTurns(maxItems)
    }

    /**
     * Appends [item] and enforces the envelope.
     *
     * Every append enforces the summary invariant together with the envelope. [add] does it through
     * [enforceEnvelope]; [appendSummary] does it through [insertSummaryAtHead], which removes the summaries it
     * supersedes in the same call and then evicts. Either way no append can leave two summaries behind.
     */
    private fun add(
        item: ModelHistoryItem,
        origin: SegmentOrigin,
        role: SegmentRole?,
    ) {
        items += SessionItem(item, origin, role)
        enforceEnvelope(maxItems)
    }

    /**
     * Enforces the envelope: first drop every SUMMARY that is no longer at the head (it is superseded by the
     * newer summary that covers the turns before it), then drop whole turns from the head. Returns the true
     * number of messages dropped — the superseded summaries plus the turn items.
     *
     * **This, [removeOldestTurns] and [insertSummaryAtHead] are the only places a superseded summary is removed.** In
     * the reachable state [appendSummary] has already removed it atomically (through [insertSummaryAtHead]); this sweep
     * is the fallback that keeps the invariant true if a future path ever introduces a non-head summary. It is also why
     * the invariant needs no flag: the sweep never touches index 0, so the summary that *is* current is never a
     * candidate here.
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
     *
     * Called from [enforceEnvelope] and [removeOldestTurns] as the fallback sweep, and from [insertSummaryAtHead] as
     * the atomic supersession itself. The lower bound is index 1, so a summary that was just landed at index 0 — the
     * current one — is never a candidate.
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
     * The text of a stored item. `estimatedText()` is `internal` to `plugins-smart`'s `ContextTextMeasure.kt`
     * and is not visible to this module, so the text is taken here directly.
     */
    private fun textOf(item: ModelHistoryItem): String = when (item) {
        is ModelHistoryItem.Text -> item.text
        is ModelHistoryItem.ToolCall -> item.name + item.argsJson
        is ModelHistoryItem.ToolOutput -> item.output
    }

    companion object {
        const val MAX_ITEMS = 200

        /**
         * [items] without the tool items that do not form a complete round **within [items]**.
         *
         * A call is kept only when the same batch also carries an output with its call id; an output with no opening
         * call in the batch is dropped for the same reason (it cannot round-trip). Text items are never dropped.
         * [appendTranscript] uses this on its own batch, and the production plugin also applies it to a turn *before*
         * closing the turn with the reply — see [appendTranscript]'s KDoc for why the order is load-bearing.
         */
        fun withoutIncompleteToolRounds(items: List<ModelHistoryItem>): List<ModelHistoryItem> {
            val callIds = items.filterIsInstance<ModelHistoryItem.ToolCall>().mapTo(mutableSetOf()) { it.callId }
            val outputIds = items.filterIsInstance<ModelHistoryItem.ToolOutput>().mapTo(mutableSetOf()) { it.callId }
            val completeRounds = callIds intersect outputIds
            return items.filter { item ->
                when (item) {
                    is ModelHistoryItem.Text -> true
                    is ModelHistoryItem.ToolCall -> item.callId in completeRounds
                    is ModelHistoryItem.ToolOutput -> item.callId in completeRounds
                }
            }
        }
    }
}
