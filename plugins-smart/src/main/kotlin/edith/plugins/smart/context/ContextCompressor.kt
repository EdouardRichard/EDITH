package edith.plugins.smart.context

import edith.plugin.ModelHistoryItem
import edith.plugin.TaskScope
import edith.plugins.smart.P3Log
import edith.plugins.smart.memory.MemoryKind
import edith.plugins.smart.memory.MemoryModule
import edith.plugins.smart.memory.MemorySource

/**
 * Turns the messages a compression discarded into one short paragraph, or answers `null` when it cannot.
 *
 * A port rather than a `ModelClient` because the compressor's job is ordering, not transport: it must be provable
 * that the summary is **deferred**, that its failure costs nothing, and that its result lands in one place. The
 * production implementation is the wiring's — it assembles the request from the plugin's own configured profile.
 *
 * It blocks, and it is called on the task scope for exactly that reason.
 */
fun interface Summarizer {
    fun summarize(transcript: String): String?
}

/**
 * What one compression did. Counts and a reason code only — never a message, never a summary (spec §4.4).
 *
 * [summaryStarted] is `false` together with a [summaryReason] whenever the asynchronous half did not run, and that is
 * a **normal** outcome rather than a failure: §4.4's degradation is "permanently plain truncation, which is never
 * worse than plain truncation".
 */
data class CompressionReport(
    val truncated: Int,
    val summaryStarted: Boolean,
    val summaryReason: String?,
)

/**
 * 方案 E (spec §4.4): synchronous rule-based truncation, plus an asynchronous cloud summary.
 *
 * **The synchronous half never fails.** It drops the oldest messages until the transcript fits, stopping at the
 * anchored region, and hands each discarded message to the store *before* it leaves the transcript — so the raw
 * content is preserved even though the conversation no longer carries it (spec §4.9's ordering rule is the caller's,
 * and this class is written so that following it is the only thing that makes sense: the write happens here, inside
 * the truncation, not after it).
 *
 * **The asynchronous half is a pure improvement.** A missing summarizer, an unavailable cloud, a throw, or an empty
 * answer all leave the transcript exactly as the synchronous half left it. Nothing about that path produces an error
 * message or touches the turn's output.
 *
 * **Anchors, and one that does not exist.** §4.4 names three: the most recent user message, an unfinished tool call
 * and its result, and "the confirmation message of the currently active SKILL". The first two are implemented — the
 * keepline is the last user turn, so a tool round that has not returned is inside it. The third has **no
 * representation**: nothing in `ModelHistoryItem` marks a message as SKILL-related, because D60 removed SKILL
 * *selection* and a SKILL now contributes prompt text rather than a transcript entry. It is recorded here rather than
 * invented, and if a future version adds such a message the anchor must be added with it.
 */
open class ContextCompressor(
    private val memory: MemoryModule? = null,
    private val summarizer: Summarizer? = null,
    /**
     * Where the summary runs. `null` runs it inline, which is what a deterministic test wants; production passes the
     * plugin's task scope so the turn is not held by a cloud round trip.
     */
    private val scope: TaskScope? = null,
    private val log: (String) -> Unit = { P3Log.line(it) },
) {

    /** `open` so a test can observe *when* compression ran relative to the memory write (spec §4.9's ordering). */
    open fun compressIfNeeded(ctx: ConversationContext, budgetTokens: Int): CompressionReport {
        val messages = ctx.history()
        if (messages.isEmpty()) return CompressionReport(0, false, null)
        val total = messages.sumOf { estimateTokens(it.estimatedText()) }
        if (total <= budgetTokens) return CompressionReport(0, false, null)

        val keepline = keeplineIndex(messages)
        var dropped = 0
        var remaining = total
        while (dropped < keepline && remaining > budgetTokens) {
            remaining -= estimateTokens(messages[dropped].estimatedText())
            dropped++
        }
        if (dropped == 0) {
            // The whole transcript is anchored. Overrunning the budget is the better failure: emptying the
            // conversation would leave the prompt with no user turn at all.
            log("P3_CONTEXT compress truncated=0 reason=ANCHORED")
            return CompressionReport(0, false, ANCHORED)
        }

        val discarded = messages.take(dropped)
        ctx.dropOldest(dropped)
        // Spec §4.4 step 2: the raw messages reach the store before they leave the transcript. A store that refuses
        // is a degradation, not a failure - the truncation already happened and the turn must go on.
        discarded.forEach { message ->
            runCatching { memory?.remember(message.estimatedText(), MemoryKind.FACT, MemorySource.AUTO) }
        }
        log("P3_CONTEXT compress truncated=$dropped budget=$budgetTokens")

        val summarizer = this.summarizer
            ?: return CompressionReport(dropped, false, NO_SUMMARIZER)
        val text = discarded.joinToString("\n") { it.estimatedText() }
        val task = scope
        if (task == null) {
            applySummary(ctx, summarizer, text)
        } else {
            // Deferred, and the caller is not held: a cloud round trip must not sit between the wearer and the
            // answer (spec §4.4 step 3).
            task.launch { applySummary(ctx, summarizer, text) }
        }
        return CompressionReport(dropped, true, null)
    }

    /** Runs on the task scope, or inline; never throws into whoever called it. */
    private fun applySummary(ctx: ConversationContext, summarizer: Summarizer, text: String) {
        val summary = runCatching { summarizer.summarize(text) }.getOrNull()
        if (summary.isNullOrBlank()) {
            log("P3_CONTEXT summary=none")
            return
        }
        // Spec §4.4 step 4a: one placeholder occupying a little of the budget, and (4b) the summary joins the raw
        // messages in the store rather than replacing them (C4: raw messages are what is stored).
        runCatching { ctx.appendSummaryPlaceholder(summary) }
        runCatching { memory?.remember(summary, MemoryKind.FACT, MemorySource.AUTO) }
        log("P3_CONTEXT summary=ok chars=${summary.length}")
    }

    /**
     * The index from which the transcript must be kept: the start of the most recent user turn.
     *
     * Anchoring a whole turn rather than a single message is what keeps §4.4's second anchor true for free — a tool
     * round that has not returned yet is inside the turn that opened it.
     */
    private fun keeplineIndex(messages: List<ModelHistoryItem>): Int {
        val lastUser = messages.indexOfLast { it is ModelHistoryItem.Text && it.role == edith.plugin.MessageRole.USER }
        return if (lastUser >= 0) lastUser else 0
    }

    private companion object {
        const val NO_SUMMARIZER = "NO_SUMMARIZER"
        const val ANCHORED = "ANCHORED"
    }
}
