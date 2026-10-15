package edith.output

import edith.contracts.AssistantRoute

/** Who said one entry in the conversation (spec §6.6: AI replies on the left, the wearer's messages on the right). */
enum class ConversationRole { USER, ASSISTANT }

/**
 * One thing that was said, in the conversation named by [route].
 *
 * [entryId] is the identity the log dedupes and replaces on: the phone sends the hint's own `hintId` for an assistant
 * message (so a revised hint updates one entry rather than adding another) and a per-utterance id for the wearer's.
 */
data class ConversationEntry(
    val entryId: String,
    val route: AssistantRoute,
    val role: ConversationRole,
    val text: String,
)

/**
 * The conversation the wearer reads back from (Phase C, spec §6.6 / §6.7).
 *
 * One implementation serves both screens — the glasses render it and the phone's assistant tab renders it — so they
 * cannot disagree about what was said, in which order, or under which route. It is deliberately transport-free and
 * Android-free, which is what makes the ordering and eviction rules plain JVM behaviour.
 *
 * **Bounded, per route.** A session that runs for an hour appends two entries a turn; the glasses rebuild their view
 * from this list every time it changes and have no input path to scroll a long one, so the list has to stay small
 * enough to be useful. Both bounds evict from the oldest end — but the newest entry is never evicted, because a log
 * that a single long message can empty is a log that goes blank exactly when it has something to say.
 *
 * **The two routes are independent** (spec §1.3 C3). Bounds are counted per route so that a busy proactive spell
 * cannot evict the conversation the wearer opened by speaking.
 */
class ConversationLog(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxChars: Int = MAX_CHARS,
) {
    init {
        require(maxEntries > 0) { "MAX_ENTRIES_MUST_BE_POSITIVE" }
        require(maxChars > 0) { "MAX_CHARS_MUST_BE_POSITIVE" }
    }

    /** Insertion-ordered per route, so replacing an entry's text leaves it where it was. */
    private val byRoute: MutableMap<AssistantRoute, LinkedHashMap<String, ConversationEntry>> =
        AssistantRoute.entries.associateWithTo(linkedMapOf()) { LinkedHashMap<String, ConversationEntry>() }

    /**
     * Adds [entry], or updates the text of one already present under the same id.
     *
     * Returns false only for an entry the log refuses outright — a blank id or blank text. A revised entry is a
     * success: the caller's statement was recorded either way.
     */
    fun append(entry: ConversationEntry): Boolean {
        if (entry.entryId.isBlank() || entry.text.isBlank()) return false
        val route = byRoute.getValue(entry.route)
        // `put` on a LinkedHashMap keeps the original position when the key already exists, which is what makes a
        // revised hint stay where the wearer last saw it instead of jumping to the bottom.
        route[entry.entryId] = entry
        evict(route)
        return true
    }

    /** A read-only snapshot of one route's conversation, oldest first. */
    fun entries(route: AssistantRoute): List<ConversationEntry> =
        byRoute.getValue(route).values.toList()

    /** Every route's entries, for a caller that only needs the count or a full reset. */
    fun size(): Int = byRoute.values.sumOf { it.size }

    /** Forgets both conversations — a new session must not open on the last one's words. */
    fun clear() {
        byRoute.values.forEach { it.clear() }
    }

    /** Evicts from the oldest end until both bounds hold, never touching the newest entry. */
    private fun evict(route: LinkedHashMap<String, ConversationEntry>) {
        while (route.size > maxEntries) {
            route.remove(route.keys.first())
        }
        while (route.size > 1 && route.values.sumOf { it.text.length } > maxChars) {
            route.remove(route.keys.first())
        }
    }

    companion object {
        /**
         * How many entries one route keeps.
         *
         * Twenty exchanges is more than a wearer can scroll through on glasses with no touch input, and the assistant
         * dims everything but the live hint, so the useful part is the tail.
         */
        const val MAX_ENTRIES = 40

        /**
         * How many characters one route keeps in total.
         *
         * A hint is bounded at 400 code points (`HintWireCodec.MAX_TEXT_CODEPOINTS`), so ten of them is a generous
         * floor and thirty is the ceiling this allows. It exists so a turn that produced several long notifications
         * cannot make rendering the list expensive.
         */
        const val MAX_CHARS = 6_000
    }
}
