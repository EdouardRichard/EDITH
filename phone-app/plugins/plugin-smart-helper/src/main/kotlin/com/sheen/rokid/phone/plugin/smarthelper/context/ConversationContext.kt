package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem

/**
 * One route's transcript.
 *
 * It owns a message list and nothing else: no budget, no memory, no compression. Those belong to [ContextManager]
 * (which owns the budget and the two instances) and to the compressor, so that this class stays a transcript and a
 * test of it stays a test of a transcript.
 *
 * **Locked.** A turn runs on the plugin's task pool while the host's single dispatcher keeps delivering events to
 * the plugin, and a streaming reply appends from the turn's own thread — so the list is reached from more than one
 * thread. `MutableList` is not thread-safe, and a lost append here would silently shorten a conversation, so every
 * accessor is synchronized rather than relying on a caller to hold a lock.
 *
 * **Text is not validated here.** [ModelHistoryItem.Text] already refuses blank text and
 * [ModelHistoryItem.ToolCall] already refuses blank identities; re-checking would only add a second place for the
 * rule to drift.
 */
class ConversationContext(
    val route: ContextRoute,
    /**
     * How many messages this route may hold before compression is owed (spec §4.10 sets 200). Exposed because
     * [overCap] is the trigger the compressor reads, not a private detail.
     */
    val maxMessages: Int = MAX_MESSAGES,
) {
    init {
        require(maxMessages >= 1) { "maxMessages must be at least 1, was $maxMessages" }
    }

    private val messages = mutableListOf<ModelHistoryItem>()

    /** The transcript, as an independent copy: a caller cannot reach into the list this context owns. */
    @Synchronized
    fun history(): List<ModelHistoryItem> = messages.toList()

    @Synchronized
    fun appendUser(text: String) {
        messages += ModelHistoryItem.Text(MessageRole.USER, text)
    }

    @Synchronized
    fun appendAssistant(text: String) {
        messages += ModelHistoryItem.Text(MessageRole.ASSISTANT, text)
    }

    /** [itemId] is the provider's own `function_call` identity; the transcript echo needs it on the wire. */
    @Synchronized
    fun appendToolCall(callId: String, name: String, argsJson: String, itemId: String = callId) {
        messages += ModelHistoryItem.ToolCall(callId = callId, name = name, argsJson = argsJson, itemId = itemId)
    }

    @Synchronized
    fun appendToolOutput(callId: String, output: String) {
        messages += ModelHistoryItem.ToolOutput(callId = callId, output = output)
    }

    /**
     * The one message an asynchronous summary leaves behind (spec §4.4 step 4a).
     *
     * It is an ordinary assistant message on purpose: the model reads it as part of the transcript, and the
     * alternative — a fourth [ModelHistoryItem] variant — would change a public contract to carry a distinction
     * nothing downstream consumes.
     */
    @Synchronized
    fun appendSummaryPlaceholder(text: String) {
        appendAssistant(text)
    }

    @Synchronized
    fun size(): Int = messages.size

    /**
     * Removes the [count] oldest messages and answers how many actually went.
     *
     * The one mutation that is not an append, and it belongs here rather than to the compressor: the transcript is
     * this class's to change, and a caller reaching in with its own list would be a second owner of it.
     */
    @Synchronized
    fun dropOldest(count: Int): Int {
        val removed = count.coerceIn(0, messages.size)
        repeat(removed) { messages.removeAt(0) }
        return removed
    }

    /** Whether this route owes a compression (spec §4.10). */
    @Synchronized
    fun overCap(): Boolean = messages.size > maxMessages

    @Synchronized
    fun clear() {
        messages.clear()
    }

    companion object {
        /** Spec §4.10: at most this many messages per context; exceeding it triggers compression. */
        const val MAX_MESSAGES = 200
    }
}
