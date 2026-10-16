package edith.plugins.smart.context

import edith.agent.ConversationStore
import edith.plugin.MessageRole
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
 * [truncated] counts **messages** dropped; [droppedTurns] counts whole turns. They are two counts of the same
 * removal because the two paths evict different units: the store drops a whole turn (user message plus everything it
 * opened), the test-only `ConversationContext` path drops the oldest messages one at a time, exactly as it always
 * has. [droppedTurns] carries a default so every existing positional construction still compiles.
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
 * that expects `compressStore` to make the current request fit is expecting it to do something it cannot do; the
 * request that does not fit is cut ①/②'s problem (`TurnTrimmer`), never this one's. That is also why
 * [compressStore] is called at the **end** of a turn ([`runTurn`]'s tail) rather than before assembly.
 *
 * **The ③ gate (D-4).** Cut ③ runs **only after cut ① actually happened**, so both entry points start a summary only
 * when `droppedTurns > 0`. `TurnTrimmer`'s `compressionOwed` can be `true` with `droppedTurns == 0` — an over-budget
 * frame whose flow holds only this turn's input and memory, or a turn whose memory was the only thing trimmed — and
 * in that shape there is no dropped turn to summarise. A summary started there would be **invented content**. The
 * caller applies the same `report.droppedTurns > 0 && report.compressionOwed` gate; this class applies
 * `droppedTurns > 0` itself, so the mistake cannot be made by calling it.
 *
 * **Two paths, one arithmetic, one deliberate split.** The eviction arithmetic and the write-before-compress
 * ordering live once, in [compress], behind the small [SessionPort] both paths implement. What still differs is the
 * unit of eviction: [compressStore] removes whole turns (a user message plus everything it opened, so a half turn can
 * never be left behind), while [compressIfNeeded] keeps `ConversationContext`'s historical message-at-a-time
 * behaviour **byte for byte**, including its summary staying a plain assistant message with **no**
 * [edith.agent.SummaryBoundary]. Only the store path renders a boundary, and it does so by delegating to
 * [ConversationStore.appendSummary] rather than by rendering text here. That duplication is deliberate and
 * time-boxed: `ConversationContext` is a test-only path after Phase 5's wiring lands, and Task 9 records this split
 * (and the plan to delete the context path) in `CONTRACTS.md`.
 *
 * **Anchors, and one that does not exist.** §4.4 names three: the most recent user message, an unfinished tool call
 * and its result, and "the confirmation message of the currently active SKILL". The first two are implemented — the
 * anchored region starts at the last user turn, so a tool round that has not returned is inside it. The third has
 * **no representation**: nothing in `ModelHistoryItem` marks a message as SKILL-related, because D60 removed SKILL
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

    /**
     * The one body both the context path and the store path share: measure, drop whole turns from the head until the
     * budget is met or the anchored region is reached, write every discarded message to memory **before** it leaves,
     * then start the summary. `applySummary` is the only difference between the two callers, and it is a parameter.
     *
     * [keepTurnItems] is the deliberate split: `false` charges a turn only its first message (the
     * `ConversationContext` path's historical message-at-a-time eviction, unchanged), `true` charges it the whole
     * span and removes the whole span (the store path).
     *
     * [compressionOwed] is the caller's half of the ③ gate. When it is `false` the transcript is still compacted —
     * that is a maintenance action — but no summary is started, because a transcript that fits is not owed one.
     * When it is `true` but no turn could be given up, `droppedTurns == 0` refuses the summary on its own: the
     * anchored region is never summarised.
     */
    private fun compress(
        port: SessionPort,
        budgetTokens: Int,
        keepTurnItems: Boolean,
        compressionOwed: Boolean,
    ): CompressionReport {
        val messages = port.history()
        if (messages.isEmpty()) return CompressionReport(0, false, null)
        val total = messages.sumOf { estimateTokens(estimatedText(it)) }
        if (total <= budgetTokens) return CompressionReport(0, false, null)

        val starts = turnStartIndices(messages)
        // The anchored region is the last turn: the last user message and everything it opened (§4.4's first two
        // anchors). It is never offered for eviction — even when it is the only turn there is — which is why a tool
        // round that has not returned survives and why a store cannot be emptied by compression.
        val droppable = maxOf(0, starts.size - 1)
        val spans = starts.mapIndexed { index, start ->
            start until (starts.getOrNull(index + 1) ?: messages.size)
        }
        /** The messages one turn gives up: the whole span on the store path, its opening message on the context path. */
        fun givenUp(index: Int): List<ModelHistoryItem> =
            if (keepTurnItems) messages.slice(spans[index]) else listOf(messages[spans[index].first])

        var droppedTurns = 0
        var remaining = total
        while (droppedTurns < droppable && remaining > budgetTokens) {
            remaining -= givenUp(droppedTurns).sumOf { estimateTokens(estimatedText(it)) }
            droppedTurns++
        }
        if (droppedTurns == 0) {
            // The anchored region is the whole transcript. Overrunning the budget is the better failure: emptying the
            // conversation would leave the prompt with no user turn at all.
            if (total > budgetTokens) log("P3_CONTEXT compress truncated=0 reason=ANCHORED")
            return CompressionReport(0, false, ANCHORED)
        }

        val discarded = (0 until droppedTurns).flatMap { givenUp(it) }
        val removed = port.dropLeadingTurns(droppedTurns)
        // Spec §4.4 step 2: the raw messages reach the store before they leave the transcript. A store that refuses
        // is a degradation, not a failure - the truncation already happened and the turn must go on.
        discarded.forEach { message ->
            runCatching { memory?.remember(estimatedText(message), MemoryKind.FACT, MemorySource.AUTO) }
        }
        if (removed != discarded.size) {
            // A store may also sweep a superseded summary: a message that left, so it belongs in the true count.
            log("P3_CONTEXT compress swept=${removed - discarded.size}")
        }
        log("P3_CONTEXT compress truncated=${maxOf(removed, discarded.size)} budget=$budgetTokens")

        if (discarded.isEmpty() || !compressionOwed) {
            return CompressionReport(maxOf(removed, discarded.size), false, null, droppedTurns)
        }

        val summarizer = this.summarizer
            ?: return CompressionReport(maxOf(removed, discarded.size), false, NO_SUMMARIZER, droppedTurns)
        val text = discarded.joinToString("\n") { estimatedText(it) }
        val task = scope
        if (task == null) {
            applySummary(port, summarizer, text)
        } else {
            // Deferred, and the caller is not held: a cloud round trip must not sit between the wearer and the
            // answer (spec §4.4 step 3).
            task.launch { applySummary(port, summarizer, text) }
        }
        return CompressionReport(maxOf(removed, discarded.size), true, null, droppedTurns)
    }

    /**
     * The `ConversationContext` entry point: **signature and behaviour unchanged**, so the ordering test that
     * overrides it still sees the same call, and `CompressionTest` still passes line for line. Its eviction stays
     * message-at-a-time and its summary stays a plain assistant message with no boundary; only [compressStore] has
     * the whole-turn, bounded-summary semantics.
     *
     * `open` so a test can observe *when* compression ran relative to the memory write (spec §4.9's ordering).
     */
    open fun compressIfNeeded(ctx: ConversationContext, budgetTokens: Int): CompressionReport =
        compress(ContextSessionPort(ctx), budgetTokens, keepTurnItems = false, compressionOwed = true)

    /**
     * Cut ③ over the production session owner. Drops the oldest **whole turns** until the store fits [shareTokens]
     * or only the anchored region is left, writes what it dropped to memory, and — only when a turn actually went —
     * starts the summary. Returns the standard report.
     *
     * There is no window share to consult here: [shareTokens] is the store's own allowance, and the caller passes the
     * share it measured. In the wired turn the caller has already written this turn's memory and already applied the
     * ③ gate; this method re-checks the gate on its own so a caller that forgets it cannot invent a summary.
     *
     * **It does not free space in the current request** (P5-R4; see the class KDoc). Call it at the turn's tail.
     */
    fun compressStore(store: ConversationStore, shareTokens: Int): CompressionReport =
        compressStore(store, shareTokens, compressionOwed = true)

    /**
     * The same call with the caller's ③ gate made explicit, so the gate itself is testable without a request frame:
     * `compressionOwed = false` must compact without starting a summary.
     *
     * `internal` and not private on purpose: the gate is one of the three things this task must get right, and a
     * private overload could only be exercised through a real `TurnTrimmer` report.
     */
    internal fun compressStore(
        store: ConversationStore,
        shareTokens: Int,
        compressionOwed: Boolean,
    ): CompressionReport =
        compress(StoreSessionPort(store), shareTokens, keepTurnItems = true, compressionOwed = compressionOwed)

    /** Runs on the task scope, or inline; never throws into whoever called it. */
    private fun applySummary(port: SessionPort, summarizer: Summarizer, text: String) {
        val summary = runCatching { summarizer.summarize(text) }.getOrNull()
        if (summary.isNullOrBlank()) {
            log("P3_CONTEXT summary=none")
            return
        }
        // Spec §4.4 step 4a: one placeholder occupying a little of the budget, and (4b) the summary joins the raw
        // messages in the store rather than replacing them (C4: raw messages are what is stored).
        //
        // The summary body is passed on **as the summarizer produced it**: the store renders `SummaryBoundary` at
        // insert time, and rendering it here too would either double-wrap or desynchronise the text the byte
        // accounting measured from the text that is sent. `ConversationContext` puts no boundary on it at all.
        runCatching { port.appendSummary(summary) }
        runCatching { memory?.remember(summary, MemoryKind.FACT, MemorySource.AUTO) }
        log("P3_CONTEXT summary=ok chars=${summary.length}")
    }

    /** The index of every message a turn can start at: a `USER` text, in order. */
    private fun turnStartIndices(messages: List<ModelHistoryItem>): List<Int> =
        messages.indices.filter { (messages[it] as? ModelHistoryItem.Text)?.role == MessageRole.USER }

    /**
     * The text a history item contributes to an estimate, mirroring `TokenBudget.estimatedText()` (which is
     * `internal` to this module but is the same arithmetic). A store item and a context item with the same wire shape
     * therefore cost the same.
     */
    private fun estimatedText(item: ModelHistoryItem): String = when (item) {
        is ModelHistoryItem.Text -> item.text
        is ModelHistoryItem.ToolCall -> item.argsJson
        is ModelHistoryItem.ToolOutput -> item.output
    }

    /**
     * The small port the shared body needs, so the eviction arithmetic exists once. Two adapters implement it; the
     * only semantic difference between them is the unit [dropLeadingTurns] removes and whether the summary it appends
     * carries a boundary.
     */
    private interface SessionPort {
        fun history(): List<ModelHistoryItem>

        /** Removes that many oldest whole turns. Returns the true number of **messages** that left. */
        fun dropLeadingTurns(count: Int): Int

        /** Lands one summary. The adapter decides whether that means a boundary. */
        fun appendSummary(text: String)
    }

    /** `ConversationContext`: message-at-a-time eviction, summary as a plain assistant message. Behaviour unchanged. */
    private class ContextSessionPort(private val ctx: ConversationContext) : SessionPort {
        override fun history(): List<ModelHistoryItem> = ctx.history()
        override fun dropLeadingTurns(count: Int): Int = ctx.dropOldest(count)
        override fun appendSummary(text: String) = ctx.appendSummaryPlaceholder(text)
    }

    /** `ConversationStore`: whole-turn eviction, and the summary is bounded by the store at insert time. */
    private class StoreSessionPort(private val store: ConversationStore) : SessionPort {
        override fun history(): List<ModelHistoryItem> = store.history()
        override fun dropLeadingTurns(count: Int): Int = store.dropOldestTurns(count)
        override fun appendSummary(text: String) = store.appendSummary(text)
    }

    private companion object {
        const val NO_SUMMARIZER = "NO_SUMMARIZER"
        const val ANCHORED = "ANCHORED"
    }
}
