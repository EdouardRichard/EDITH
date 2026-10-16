package edith.agent

/**
 * Per-route session stream holding ordered segments (spec §3.3).
 *
 * This replaces ConversationContext's role as the mutable accumulator of conversation state.
 * Each route gets its own independent store; stores do not share state.
 *
 * Thread safety: all mutating methods and [snapshot] are synchronized to allow
 * concurrent access from agent loop threads.
 */
class ConversationStore {

    private val segments = mutableListOf<Segment>()

    /**
     * Appends a user message as a TRANSCRIPT segment (trusted=true).
     */
    @Synchronized
    fun appendUser(text: String) {
        segments.add(Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = text))
    }

    /**
     * Appends an assistant message as a TRANSCRIPT segment (trusted=true).
     */
    @Synchronized
    fun appendAssistant(text: String) {
        segments.add(Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = text))
    }

    /**
     * Appends a tool round summary as a TOOL_ROUND segment (trusted=true).
     */
    @Synchronized
    fun appendToolRound(summary: String) {
        segments.add(Segment(SegmentOrigin.TOOL_ROUND, trusted = true, text = summary))
    }

    /**
     * Clears all existing segments and replaces them with a single SUMMARY segment (trusted=false).
     *
     * Used when context compression produces a summary that supersedes the prior transcript.
     */
    @Synchronized
    fun replaceWithSummary(summaryText: String) {
        segments.clear()
        segments.add(Segment(SegmentOrigin.SUMMARY, trusted = false, text = summaryText))
    }

    /**
     * Returns an immutable defensive copy of the current segment list.
     *
     * Later mutations to this store do not affect previously returned snapshots.
     */
    @Synchronized
    fun snapshot(): List<Segment> {
        return segments.toList()
    }
}
