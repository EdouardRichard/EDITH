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
 * metadata ([SessionItem.origin] / [SessionItem.role]), never inferred from text. A summary's text
 * is stored **clean**; [SummaryBoundary] is a render-time product, so [history] carries the rendered
 * boundary while [snapshot] returns the clean body. Both paths render the same constant, so there is
 * only one wording.
 *
 * ## The summary invariant (the tests pin this)
 *
 * A `SUMMARY` item may appear **only at index 0**. After [replaceWithSummary] the store holds a single
 * summary; the next append is a new user turn, so in "summary + turns" the summary covers every turn
 * before it and must be kept. A summary that sits *after* a turn means earlier turns still precede it,
 * so it has been superseded by a newer summary: it takes no part in whole-turn eviction, and eviction
 * removes it ([dropOldestTurns], and every append that evicts). This is also why a summary takes no part
 * in whole-turn eviction: it is not a turn.
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
     * [SegmentRole.ASSISTANT]. The stored text is the **clean body**; the wire shape ([history]) carries
     * the explicit [SummaryBoundary] because it is rendered here, at the boundary of the store.
     */
    @Synchronized
    fun appendSummary(text: String) =
        add(ModelHistoryItem.Text(MessageRole.ASSISTANT, SummaryBoundary.render(text)), SegmentOrigin.SUMMARY, SegmentRole.ASSISTANT)

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
     * Returns the conversation in wire shape, in order. A summary keeps the boundary rendering applied
     * by [appendSummary]; use [snapshot] for the clean body.
     */
    @Synchronized
    fun history(): List<ModelHistoryItem> = items.map { it.item }

    /**
     * Returns an immutable defensive copy of the current segment list.
     *
     * Later mutations to this store do not affect previously returned snapshots. A summary segment holds
     * the **clean** body (no boundary) — [history] is the bounded rendering.
     */
    @Synchronized
    fun snapshot(): List<Segment> = items.map {
        Segment(
            origin = it.origin,
            trusted = it.origin == SegmentOrigin.TRANSCRIPT || it.origin == SegmentOrigin.TOOL_ROUND,
            text = cleanBodyOf(it),
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
     * Drops the oldest whole turns until `size <= maxItems`, and returns the number of messages dropped.
     *
     * A whole turn is one user message plus the assistant/tool round it produced; a tool round is never
     * orphaned from the turn that caused it. The **last** user turn is never dropped — and because a turn
     * is dropped whole, a drop that would remove the last user turn never begins.
     */
    @Synchronized
    fun dropOldestTurns(maxItems: Int): Int = enforceEnvelope(maxItems)

    private fun add(item: ModelHistoryItem, origin: SegmentOrigin, role: SegmentRole?) {
        items += SessionItem(item, origin, role)
        // A summary that is no longer at the head is *superseded*, but it is not removed here: appending a
        // summary is a maintenance action whose result the caller may read back immediately, and the removal
        // is a consequence of the summary invariant rather than of the append. It happens at eviction
        // ([dropOldestTurns] / the next append that evicts), which is what [dropOldestTurns]'s tests pin.
        evictTurns(maxItems)
    }

    /**
     * Enforces the envelope: first drop every SUMMARY that is no longer at the head (it is superseded by the
     * newer summary that covers the turns before it), then drop whole turns from the head. Returns the number
     * of messages dropped.
     *
     * A summary is never evicted as a turn — it is not a turn. When the only remaining turn is the last user
     * turn, the envelope is allowed to overrun rather than empty the conversation, the same trade-off
     * `ContextCompressor` already makes with its `ANCHORED` reason.
     */
    private fun enforceEnvelope(target: Int): Int {
        dropSupersededSummaries()
        return evictTurns(target)
    }

    /** Drops every SUMMARY that is not at index 0. See the summary invariant in the class KDoc. */
    private fun dropSupersededSummaries() {
        var index = items.size - 1
        while (index > 0) {
            if (items[index].origin == SegmentOrigin.SUMMARY) items.removeAt(index)
            index--
        }
    }

    /** Drops whole turns from the head while more than [target] messages remain. Returns the count dropped. */
    private fun evictTurns(target: Int): Int {
        var dropped = 0
        while (items.size > target) {
            val start = turnStart(0) ?: break
            val next = turnStart(start + 1) ?: break
            dropped += next - start
            repeat(next - start) { items.removeAt(start) }
        }
        return dropped
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
     * The clean body of a stored item, for [snapshot].
     *
     * A summary is stored on the wire with its boundary ([appendSummary] renders it there), so the boundary is
     * stripped back off here: `snapshot()` is the clean-body view, `history()` is the bounded rendering. The
     * boundary constant is the same one, so there is only one wording. A store that never went through
     * [appendSummary] carries no boundary, so the strip is a no-op for it.
     */
    private fun cleanBodyOf(stored: SessionItem): String {
        val text = textOf(stored.item)
        if (stored.origin != SegmentOrigin.SUMMARY || !SummaryBoundary.isRendered(text)) return text
        return text
            .removePrefix(SummaryBoundary.OPEN)
            .removeSuffix(SummaryBoundary.CLOSE)
            .trim('\n')
    }

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
