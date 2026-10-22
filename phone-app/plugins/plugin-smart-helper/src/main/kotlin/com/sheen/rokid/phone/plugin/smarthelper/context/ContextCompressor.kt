package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModule
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource

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
    /**
     * @param maxOutputTokens the policy's summary allowance (0.3 design §6.4): the production implementation forwards
     *   it as the request's own output cap, so a summary can never spend the window the next turn needs.
     */
    fun summarize(transcript: String, maxOutputTokens: Int): String?
}

/**
 * What one compression did. Counts and a reason code only — never a message, never a summary (spec §4.4).
 *
 * [truncated] counts **messages** dropped. [droppedTurns] counts the turns that removal reached into, and the two
 * paths answer it differently because they evict different units: [ContextCompressor.compressStore] removes **whole
 * turns**, so this is exactly how many went; [ContextCompressor.compressIfNeeded] removes the oldest messages one at
 * a time, exactly as it always has, so this is how many turns those leading messages came from (a half turn can be
 * the answer there). [droppedTurns] carries a default so every existing positional construction still compiles.
 *
 * [summaryStarted] is `false` together with a [summaryReason] whenever the asynchronous half did not run, and that is
 * a **normal** outcome rather than a failure: §4.4's degradation is "permanently plain truncation, which is never
 * worse than plain truncation".
 */
data class CompressionReport(
    val truncated: Int,
    val summaryStarted: Boolean,
    val summaryReason: String?,
    val droppedTurns: Int = 0,
)

/**
 * 方案 E (spec §4.4): synchronous rule-based truncation, plus an asynchronous cloud summary.
 *
 * **The synchronous half never fails.** It drops the oldest content until the transcript fits, stopping at the
 * anchored region, and hands each discarded message to the store *before* it leaves the transcript — so the raw
 * content is preserved even though the conversation no longer carries it (spec §4.9's ordering rule is the caller's,
 * and this class is written so that following it is the only thing that makes sense: the write happens here, inside
 * the truncation, not after it).
 *
 * **The asynchronous half is a pure improvement.** A missing summarizer, an unavailable cloud, a throw, or an empty
 * answer all leave the transcript exactly as the synchronous half left it. Nothing about that path produces an error
 * message or touches the turn's output.
 *
 * **Compression does not free space in the request it is decided for** (P5-R4). The content it summarises has
 * *already* left the request — it is what cut ① removed — so replacing it with a summary cannot rescue the turn that
 * is in flight. Its whole effect is on the **next** turn, which then starts from the compacted transcript. A caller
 * that expects [compressStore] to make the current request fit is expecting it to do something it cannot do; the
 * request that does not fit is cut ①/②'s problem (`TurnTrimmer`), never this one's. That is also why
 * [compressStore] is called at the **end** of a turn ([`runTurn`]'s tail) rather than before assembly.
 *
 * **The ③ gate (D-4).** Cut ③ runs **only after cut ① actually happened** — only when something really left the
 * transcript — and each entry point states that condition in its own eviction unit rather than in a shared
 * expression. **These gates are frozen with the paths; documenting them must not change them.**
 *
 * [compressStore] evicts whole turns and applies the full `droppedTurns > 0 && compressionOwed` conjunction itself,
 * taking the caller's half (`compressionOwed`) as a parameter: there is one public entry point and it is the gated
 * one, so a caller cannot reach a summary by forgetting the gate. `TurnTrimmer`'s `compressionOwed` can be `true`
 * with its own `droppedTurns == 0` — an over-budget frame whose flow holds only this turn's input and memory, or a
 * turn whose memory was the only thing trimmed — and in that shape there is no dropped turn to summarise. A summary
 * started there would be **invented content**.
 *
 * [compressIfNeeded] is the frozen `ConversationContext` path and its gate is **not** the store's: it drops the
 * oldest messages one at a time, so its condition is `dropped != 0` — a **message count** — and it never consults
 * `compressionOwed` at all (its caller has no `TurnTrimmer` report to offer). The `droppedTurns` it publishes in
 * the report is derived *afterwards* (`turnStartIndices(messages).count { it < dropped }`) for the caller's
 * accounting; it is deliberately **not** what that path gates on, because a half turn is a legitimate unit there.
 *
 * **Two paths, two eviction units, deliberately not one body.** [compressStore] removes the oldest **whole turns** (a
 * user message plus everything it opened, so a half turn can never be left behind), through
 * [ConversationStore.removeOldestTurns] — a **count** of turns. [compressIfNeeded] keeps `ConversationContext`'s
 * historical message-at-a-time behaviour **byte for byte**, including its summary staying a plain assistant message
 * with **no** [com.sheen.rokid.agent.SummaryBoundary]. Sharing one eviction loop between them is exactly what changed the
 * frozen path's semantics once already (a turn charged one message, then removed by count), so the two loops are
 * written separately and only the write-to-memory ordering, [estimatedText] and the summary landing are shared. The
 * duplication is deliberate and time-boxed: `ConversationContext` is a test-only path after Phase 5's wiring lands,
 * and Task 9 records this split (and the plan to delete the context path) in `CONTRACTS.md`.
 *
 * **Anchors, and one that does not exist.** §4.4 names three: the most recent user message, an unfinished tool call
 * and its result, and "the confirmation message of the currently active SKILL". The first two are implemented — the
 * anchored region starts at the last user turn, so a tool round that has not returned is inside it. The third has
 * **no representation**: nothing in `ModelHistoryItem` marks a message as SKILL-related, because D60 removed SKILL
 * *selection* and a SKILL now contributes prompt text rather than a transcript entry. It is recorded here rather than
 * invented, and if a future version adds such a message the anchor must be added with it.
 */
open class ContextCompressor(
    /**
     * The history allowance both entry points measure against (0.3 design §6.3), derived from the session policy's
     * `softLimit`. It used to be a per-call parameter carrying the plugin's own six-way token split; with the policy
     * it is fixed for the session and is therefore constructor state.
     */
    private val historyBudgetTokens: Int,
    /** The policy's summary output allowance (0.3 design §6.4), passed on to every summarizer call. */
    private val summaryMaxTokens: Int,
    private val memory: MemoryModule? = null,
    private val summarizer: Summarizer? = null,
    /**
     * Where the summary runs. `null` runs it inline, which is what a deterministic test wants; production passes the
     * plugin's task scope so the turn is not held by a cloud round trip.
     */
    private val scope: TaskScope? = null,
    private val log: (String) -> Unit = { P3Log.line(it) },
) {

    init {
        require(historyBudgetTokens >= 0) {
            "historyBudgetTokens must not be negative, was $historyBudgetTokens"
        }
        require(summaryMaxTokens > 0) {
            "summaryMaxTokens must be positive, was $summaryMaxTokens"
        }
    }

    /**
     * The `ConversationContext` entry point: **behaviour unchanged**, and it now measures against the session
     * policy's allowance ([historyBudgetTokens]) instead of an arbitrary per-call number, so `CompressionTest` still
     * passes line for line.
     *
     * Its eviction is message-at-a-time and is deliberately **not** the store path's whole-turn eviction: it drops
     * the oldest message, one at a time, until the transcript fits or the keepline is reached, then writes exactly
     * what went to memory. Its summary stays a plain assistant message with no boundary; only [compressStore] has
     * the whole-turn, bounded-summary semantics.
     *
     * `open` so a test can observe *when* compression ran relative to the memory write (spec §4.9's ordering).
     */
    open fun compressIfNeeded(ctx: ConversationContext): CompressionReport {
        val messages = ctx.history()
        if (messages.isEmpty()) return CompressionReport(0, false, null)
        val total = messages.sumOf { estimateTokens(it.estimatedText()) }
        if (total <= historyBudgetTokens) return CompressionReport(0, false, null)

        val keepline = keeplineIndex(messages)
        var dropped = 0
        var remaining = total
        while (dropped < keepline && remaining > historyBudgetTokens) {
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
        log("P3_CONTEXT compress truncated=$dropped budget=$historyBudgetTokens")
        // How many turns those leading messages came from. On this path a half turn can be the answer, which is why
        // the report counts the two units separately.
        val droppedTurns = turnStartIndices(messages).count { it < dropped }

        val summarizer = this.summarizer
            ?: return CompressionReport(dropped, false, NO_SUMMARIZER, droppedTurns)
        val text = discarded.joinToString("\n") { it.estimatedText() }
        val task = scope
        if (task == null) {
            applySummary(summarizer, text) { ctx.appendSummaryPlaceholder(it) }
        } else {
            // Deferred, and the caller is not held: a cloud round trip must not sit between the wearer and the
            // answer (spec §4.4 step 3).
            task.launch { applySummary(summarizer, text) { ctx.appendSummaryPlaceholder(it) } }
        }
        return CompressionReport(dropped, true, null, droppedTurns)
    }

    /**
     * Cut ③ over the production session owner. Drops the oldest **whole turns** until the store fits
     * [historyBudgetTokens] or only the anchored region is left, writes what it dropped to memory, and — only when a
     * whole turn actually went — starts the summary. Returns the standard report.
     *
     * [compressionOwed] is the caller's half of the ③ gate: this method applies `droppedTurns > 0 && compressionOwed`
     * in full, so a caller that has not established that cut ① happened must say so and gets no summary. It is a
     * required parameter rather than a default for exactly that reason — a default would put the half-gated call
     * back on the one public entry point the wiring uses.
     *
     * The allowance is the policy-derived [historyBudgetTokens], the same one [compressIfNeeded] uses.
     *
     * **It does not free space in the current request** (P5-R4; see the class KDoc). Call it at the turn's tail.
     *
     * `open` for the same reason [compressIfNeeded] is: a test observes **when** it ran relative to the memory write
     * (spec §4.9's ordering) and **what the caller's half of the gate was**, which no post-hoc assertion on the store
     * can recover.
     */
    open fun compressStore(
        store: ConversationStore,
        compressionOwed: Boolean,
    ): CompressionReport {
        val messages = store.history()
        if (messages.isEmpty()) return CompressionReport(0, false, null)
        val total = messages.sumOf { estimateTokens(it.estimatedText()) }
        if (total <= historyBudgetTokens) return CompressionReport(0, false, null)

        // A turn starts at a user message and runs to the next one; spans[i] is everything turn i owns. The last
        // turn is the anchored region and is never offered for eviction, so `droppable == 0` when it is the only
        // turn there is — a store cannot be emptied by compression.
        val starts = turnStartIndices(messages)
        val droppable = maxOf(0, starts.size - 1)
        val spans = starts.mapIndexed { index, start ->
            start until (starts.getOrNull(index + 1) ?: messages.size)
        }

        var droppedTurns = 0
        var remaining = total
        while (droppedTurns < droppable && remaining > historyBudgetTokens) {
            remaining -= messages.slice(spans[droppedTurns]).sumOf { estimateTokens(it.estimatedText()) }
            droppedTurns++
        }
        if (droppedTurns == 0) {
            // The anchored region is the whole transcript. Overrunning the budget is the better failure: emptying
            // the conversation would leave the prompt with no user turn at all.
            log("P3_CONTEXT compress truncated=0 reason=ANCHORED")
            return CompressionReport(0, false, ANCHORED)
        }

        val discarded = (0 until droppedTurns).flatMap { messages.slice(spans[it]) }
        // A **count** of whole turns, never a target size: `dropOldestTurns` means "drop until `size <= n`", and
        // passing this count there swept messages that were never written to memory and left the store far below
        // its share.
        val removed = store.removeOldestTurns(droppedTurns)
        // Spec §4.4 step 2: the raw messages reach the store before they leave the transcript. A store that refuses
        // is a degradation, not a failure - the truncation already happened and the turn must go on.
        discarded.forEach { message ->
            runCatching { memory?.remember(message.estimatedText(), MemoryKind.FACT, MemorySource.AUTO) }
        }
        if (removed > discarded.size) {
            // A superseded summary was swept alongside the turns: a message that left, so it belongs in the count.
            log("P3_CONTEXT compress swept=${removed - discarded.size}")
        }
        val truncated = maxOf(removed, discarded.size)
        log("P3_CONTEXT compress truncated=$truncated budget=$historyBudgetTokens")

        if (!compressionOwed) {
            // The caller's half of the gate: nothing was owed, so the compaction stands and no summary is started.
            return CompressionReport(truncated, false, null, droppedTurns)
        }
        val summarizer = this.summarizer
            ?: return CompressionReport(truncated, false, NO_SUMMARIZER, droppedTurns)
        val text = discarded.joinToString("\n") { it.estimatedText() }
        val task = scope
        if (task == null) {
            applySummary(summarizer, text) { store.appendSummary(it) }
        } else {
            // Deferred, and the caller is not held: a cloud round trip must not sit between the wearer and the
            // answer (spec §4.4 step 3).
            task.launch { applySummary(summarizer, text) { store.appendSummary(it) } }
        }
        return CompressionReport(truncated, true, null, droppedTurns)
    }

    /**
     * Runs on the task scope, or inline; never throws into whoever called it.
     *
     * [append] lands the summary in the path's own owner. The summarizer's body is passed on **as produced**: the
     * store renders `SummaryBoundary` at insert time, and rendering it here too would either double-wrap or
     * desynchronise the text the byte accounting measured from the text that is sent. `ConversationContext` puts no
     * boundary on it at all.
     */
    private fun applySummary(summarizer: Summarizer, text: String, append: (String) -> Unit) {
        val summary = runCatching { summarizer.summarize(text, summaryMaxTokens) }.getOrNull()
        if (summary.isNullOrBlank()) {
            log("P3_CONTEXT summary=none")
            return
        }
        // Spec §4.4 step 4a: one placeholder occupying a little of the budget, and (4b) the summary joins the raw
        // messages in the store rather than replacing them (C4: raw messages are what is stored).
        runCatching { append(summary) }
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
        val lastUser = messages.indexOfLast { it is ModelHistoryItem.Text && it.role == MessageRole.USER }
        return if (lastUser >= 0) lastUser else 0
    }

    /** The index of every message a turn can start at: a `USER` text, in order. */
    private fun turnStartIndices(messages: List<ModelHistoryItem>): List<Int> =
        messages.indices.filter { (messages[it] as? ModelHistoryItem.Text)?.role == MessageRole.USER }

    private companion object {
        const val NO_SUMMARIZER = "NO_SUMMARIZER"
        const val ANCHORED = "ANCHORED"
    }
}
