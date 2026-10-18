package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationRole

/**
 * How the conversation is laid out, decided without Android so it can be tested off-device.
 *
 * The rule 0.23 fixes (U9) is that **every line is left-aligned**, including the wearer's own words; the official
 * panel separates the two roles by colour and size instead of by side. `GlassesActivity` builds the `TextView`s;
 * **which** rows exist, which side each one sits on, and which one is the live hint are decisions, and they live here
 * so a rendering regression fails a unit test instead of needing someone to put the glasses on.
 *
 * Nothing here reads the clock, the log or the wire: it is a pure function of the entries it is handed, which is what
 * keeps the phone's assistant tab (same [com.sheen.rokid.output.ConversationLog], same order) and this view in agreement.
 */
object ConversationView {

    /**
     * One line of the conversation as the wearer will see it.
     *
     * [live] marks the entry a hint is currently standing behind: the rest of the conversation stays readable, but
     * only this one is the thing the phone is waiting to have confirmed. Alignment is not a per-row field: 0.23 U9
     * fixes every row to the left, so the Activity's `gravity` is the same for both roles.
     */
    data class Row(val entry: ConversationEntry, val live: Boolean)

    /**
     * The rows to render, in the order the log gives them.
     *
     * The order is deliberately **not** recomputed here. The log is the single authority both screens read, and a
     * second sort would be a second opinion about what was said first.
     */
    fun rows(entries: List<ConversationEntry>, liveEntryId: String?): List<Row> =
        entries.map { entry ->
            Row(
                entry = entry,
                live = liveEntryId != null && entry.entryId == liveEntryId,
            )
        }

    // ---------------------------------------------------------------- per-turn items (0.24 S4 Task 17)

    /**
     * One **turn** as the panel draws it (spec §5.6): the wearer's line, the answer that belongs to it, and the two
     * transient rows the turn may own (thinking and tool). A turn with neither line is not produced except for a
     * transient-only turn, where the thinking/tool row is the whole item.
     *
     * The fields are the display decisions, not the Android views: the host maps [asrText]/[answerText] to
     * `TextView`s, [thinkingVisible]/[toolLabel] to their rows. [asrTextColor] is the official rule "recognising is full
     * green, after the answer it drops to 70%", so a style drift fails a JVM test.
     */
    data class TurnRow(
        val turnId: String?,
        val asrText: String?,
        val answerText: String?,
        val answerLive: Boolean,
        val thinkingVisible: Boolean,
        val toolLabel: String?,
        val toolPhase: ToolPhase?,
        /**
         * 0.24 S4 Task 19: the assistant entry is the final `SHOW`, so the answer row is Markwon-rendered. A streamed
         * partial leaves this false and the host assigns plain text (see [MarkdownPolicy]). A flag rather than a text
         * heuristic: a partial may already contain `**` while it is still being streamed.
         */
        val answerFinal: Boolean = false,
        /**
         * 0.24 S4 Task 19: this item is the transient ASR waiting item (status 0/1), rendered as the official 26x13
         * `gif_loading`. Status 2 removes it when the wearer's text replaces the "…".
         */
        val asrLoadingVisible: Boolean = false,
    ) {
        val asrTextSizePx: Float get() = GlassesPanelSpec.USER_TEXT_SIZE_PX
        val asrTextColor: Int
            get() = if (answerText != null) GlassesPanelSpec.GREEN_NORMAL_70 else GlassesPanelSpec.GREEN_NORMAL
        val answerTextSizePx: Float get() = GlassesPanelSpec.ASSISTANT_TEXT_SIZE_PX
        val answerTextColor: Int get() = GlassesPanelSpec.GREEN_NORMAL
        val toolTextColor: Int get() = GlassesPanelSpec.toolTextColor(toolPhase ?: ToolPhase.START)
    }

    /**
     * Groups the flat conversation log into the official per-turn items, **without reordering it**.
     *
     * Entries with the same non-null `turnId` belong to one item; entries with no turn id are one item **each** (spec
     * §5.6, the legacy producer). Group order is first appearance and entry order inside a group is the log's, so this
     * is a grouping, never a sort. The transient rows are attached to the turn that owns them: thinking to
     * [thinkingTurnId] (or the last item when no turn id exists, the legacy shape), the tool row to [tool.turnId]. A
     * transient row whose entry has not arrived yet is appended as its own item rather than being dropped, because the
     * status that names it is still live.
     */
    fun turns(
        entries: List<ConversationEntry>,
        liveEntryId: String? = null,
        thinkingVisible: Boolean = false,
        thinkingTurnId: String? = null,
        tool: ToolRowState? = null,
        /** 0.24 S4 Task 19: entry ids whose `SHOW` was accepted; only these answers are Markwon-rendered. */
        finalAnswerEntryIds: Set<String> = emptySet(),
        /** 0.24 S4 Task 19: status 0/1's ASR waiting GIF, appended as the current transient item. */
        asrLoadingVisible: Boolean = false,
    ): List<TurnRow> {
        val groups = LinkedHashMap<Any, MutableList<ConversationEntry>>()
        entries.forEachIndexed { index, entry ->
            // A null id is unique per entry; using the index as the key is what keeps two legacy lines in two items.
            groups.getOrPut(entry.turnId ?: NullTurnKey(index)) { mutableListOf() } += entry
        }
        val turns = groups.map { (key, group) ->
            val turnId = key as? String
            val user = group.lastOrNull { it.role == ConversationRole.USER }
            val assistant = group.lastOrNull { it.role == ConversationRole.ASSISTANT }
            TurnRow(
                turnId = turnId,
                asrText = user?.text,
                answerText = assistant?.text,
                answerLive = assistant != null && assistant.entryId == liveEntryId,
                thinkingVisible = false,
                toolLabel = null,
                toolPhase = null,
                answerFinal = assistant != null && assistant.entryId in finalAnswerEntryIds,
            )
        }.toMutableList()

        if (thinkingVisible) {
            val index = if (thinkingTurnId == null) turns.lastIndex
            else turns.indexOfFirst { it.turnId == thinkingTurnId }
            if (index >= 0) turns[index] = turns[index].copy(thinkingVisible = true)
            else turns += TurnRow(thinkingTurnId, null, null, false, true, null, null)
        }

        if (tool != null) {
            val index = turns.indexOfFirst { it.turnId == tool.turnId }
            if (index >= 0) turns[index] = turns[index].copy(toolLabel = tool.label, toolPhase = tool.phase)
            else turns += TurnRow(tool.turnId, null, null, false, false, tool.label, tool.phase)
        }

        if (asrLoadingVisible) {
            // The official `imv_asr_loading` sits at the top of the current item. Status 0/1 have no current text yet,
            // so the current item is a transient one after the last stored turn; appending it never reorders the log.
            turns += TurnRow(
                turnId = null,
                asrText = null,
                answerText = null,
                answerLive = false,
                thinkingVisible = false,
                toolLabel = null,
                toolPhase = null,
                asrLoadingVisible = true,
            )
        }
        return turns
    }

    /** A render plan for one published model: rebuild the list only when its structure changed. */
    sealed interface RenderPlan {
        /** The row list must be rebuilt (a new/removed row, a new turn, thinking/tool appearing or disappearing). */
        data class Rebuild(val turns: List<TurnRow>) : RenderPlan

        /** The rows are the same objects; patch text/live/style in place. */
        data class Update(
            val turns: List<TurnRow>,
            val textChanged: Boolean,
            val liveChanged: Boolean,
            /** 0.24 S4 Task 19: the partial became the final Markdown row; the host parses it in place. */
            val answerFinalChanged: Boolean = false,
        ) : RenderPlan

        /** Nothing the view shows changed (an equal replacement, a duplicate revision). */
        data class Unchanged(val turns: List<TurnRow>) : RenderPlan
    }

    /**
     * Compares the turns already on screen with the newly published ones.
     *
     * The **structure** is the identity of each item and which rows it has; a text-only difference is an [Update],
     * which is what makes ASR/ANSWER revisions patch one `TextView` instead of rebuilding the list. A tool phase change
     * is an update too (it only restyles the label); a new answer row or a turn is a [Rebuild]. `live` is style only:
     * a final `SHOW` replacing the same `say-${hintId}` row is an update, never a second item.
     */
    fun plan(previous: List<TurnRow>?, next: List<TurnRow>): RenderPlan {
        if (previous == null || previous.size != next.size) return RenderPlan.Rebuild(next)
        val sameStructure = previous.indices.all { shape(previous[it]) == shape(next[it]) }
        if (!sameStructure) return RenderPlan.Rebuild(next)
        val textChanged = previous.indices.any {
            previous[it].asrText != next[it].asrText ||
                previous[it].answerText != next[it].answerText ||
                previous[it].toolLabel != next[it].toolLabel ||
                previous[it].toolPhase != next[it].toolPhase
        }
        val liveChanged = previous.indices.any { previous[it].answerLive != next[it].answerLive }
        val answerFinalChanged = previous.indices.any { previous[it].answerFinal != next[it].answerFinal }
        return if (textChanged || liveChanged || answerFinalChanged) {
            RenderPlan.Update(next, textChanged, liveChanged, answerFinalChanged)
        } else {
            RenderPlan.Unchanged(next)
        }
    }

    /** What makes two turns the same item for the view: identity plus which rows exist, never their text. */
    private data class Shape(
        val turnId: String?,
        val hasAsr: Boolean,
        val hasAnswer: Boolean,
        val thinking: Boolean,
        val hasTool: Boolean,
        val asrLoading: Boolean,
    )

    private fun shape(row: TurnRow): Shape =
        Shape(row.turnId, row.asrText != null, row.answerText != null, row.thinkingVisible, row.toolLabel != null,
            row.asrLoadingVisible)

    /** A distinct key for one entry that has no turn id, so two legacy lines never merge into one item. */
    private data class NullTurnKey(val index: Int)
}

/** One tool row's wire state (0.24 S4 Task 17): the turn it belongs to, its mapped copy and whether it still runs. */
data class ToolRowState(val turnId: String, val label: String, val phase: ToolPhase)

/**
 * The expensive-pass cadence for streamed answer updates (0.24 S4 Task 17).
 *
 * The official `AIModeManager.handleTtsData` accumulates TTS text and dispatches it on a 50 ms boundary; EDITH receives
 * one full-text `ANSWER` revision per delta and patches the row immediately (so no revision is ever delayed or lost),
 * while the reveal/layout pass behind it is coalesced to the same 50 ms cadence. The class is pure so the cadence is a
 * JVM test rather than a stopwatch on the glasses.
 */
class RevealCoalescer(private val intervalNanos: Long = INTERVAL_NANOS) {
    private var lastPassNanos: Long? = null

    /** True when a full reveal/log pass may run at [nowNanos]; records the pass when it does. */
    fun allow(nowNanos: Long): Boolean {
        val last = lastPassNanos
        if (last != null && nowNanos - last < intervalNanos) return false
        lastPassNanos = nowNanos
        return true
    }

    /** A new list/session: the next pass is never treated as "too soon". */
    fun reset() {
        lastPassNanos = null
    }

    companion object {
        /** The official coalescing boundary; the task's bound is ≤100 ms. */
        const val INTERVAL_NANOS = 50_000_000L
    }
}

/**
 * The ASR revision/finality gate (0.24 spec §6).
 *
 * The custom-command channel promises nothing about ordering, and ASR revisions are display increments: a late
 * revision must not overwrite a newer one, and a revision that arrives **after** the final `SAID` replaced the line
 * must not overwrite the authoritative final text. The gate is deliberately pure state: the controller owns the log
 * and the wire, and this class owns only the ordering rules, so the matrix is testable without Android.
 *
 * Revisions are counted per **(route, turn)**: a new turn starts its own revision domain, and the two conversations
 * are independent. The **recently finalized** entry ids are remembered per route (bounded, oldest evicted first): a new
 * turn's entry has a different id and is not blocked by them, while a revision delayed across a turn boundary still
 * cannot reopen an older final (0.24 Task 15 review I1).
 */
class AsrRevisionGate {

    /** The last applied revision per route, then per turn. */
    private val lastRevision: MutableMap<AssistantRoute, MutableMap<String, Long>> = mutableMapOf()

    /**
     * The recently finalized user entry ids per route, oldest first.
     *
     * A **set** rather than only the latest id matters because the channel is unordered across a turn boundary: after
     * turn 2 finalizes, a delayed revision of turn 1 must still not reopen turn 1's final. A new turn has a new entry id
     * and is therefore not blocked. Bounded so a long session cannot grow the gate without end; dropping the oldest
     * only means a revision older than that many finalized turns could reopen its own row (the same documented
     * trade-off as the phone's display, which keeps the same number of ids).
     */
    private val finalizedEntries: MutableMap<AssistantRoute, MutableSet<String>> = mutableMapOf()

    /** True when [revision] is newer than everything applied for this route/turn and no final has claimed the line. */
    fun accept(route: AssistantRoute, turnId: String, revision: Long): Boolean {
        if (turnId.isBlank() || revision < 0) return false
        if (isFinalized(route, turnId)) return false
        val perTurn = lastRevision.getOrPut(route) { mutableMapOf() }
        val previous = perTurn[turnId] ?: Long.MIN_VALUE
        if (revision <= previous) return false
        perTurn[turnId] = revision
        return true
    }

    /** True when a final already replaced the line for [turnId]; a later `ASR` revision is stale. */
    fun isFinalized(route: AssistantRoute, turnId: String): Boolean =
        finalizedEntries[route]?.contains(userEntryId(turnId)) == true

    /** A final `SAID` replaced [entryId]; no later revision may reopen that line. */
    fun finalize(route: AssistantRoute, entryId: String) {
        if (entryId.isBlank()) return
        // Only the documented `asr-${turnId}` shape is queryable through `isFinalized`, so a legacy/minted id is not
        // worth a slot: remembering it could evict a real final without ever being asked about.
        val turnId = turnIdOf(entryId) ?: return
        val finalized = finalizedEntries.getOrPut(route) { linkedSetOf() }
        finalized += entryId
        while (finalized.size > MAX_FINALIZED_ENTRIES_PER_ROUTE) {
            val oldest = finalized.firstOrNull() ?: break
            finalized.remove(oldest)
        }
        // The finalized turn's revision domain is dead. Dropping it bounds the per-turn map to turns that have not
        // finalized yet; a late revision still loses to `isFinalized` above for as long as the id is remembered.
        lastRevision[route]?.remove(turnId)
    }

    /** A new session or a closed/reopened window starts a clean revision domain. */
    fun reset() {
        lastRevision.clear()
        finalizedEntries.clear()
    }

    companion object {
        private const val USER_PREFIX = "asr-"

        /** How many finalized user entry ids one route keeps; the same bound the phone's display uses. */
        private const val MAX_FINALIZED_ENTRIES_PER_ROUTE = 8

        /** 0.24 spec §6: the wearer's entry id is derived from the turn, not minted per statement. */
        fun userEntryId(turnId: String): String = "$USER_PREFIX$turnId"

        /**
         * The turn named by a user entry id, or null when the id is not the documented `asr-${turnId}` shape.
         *
         * `SAID` carries no `turnId` field, so this is how a final that arrives without a preceding partial still joins
         * the per-turn grouping (spec §6). Only the documented prefix is parsed; any other id is left alone.
         */
        fun turnIdOf(entryId: String): String? =
            entryId.takeIf { it.startsWith(USER_PREFIX) && it.length > USER_PREFIX.length }
                ?.removePrefix(USER_PREFIX)
    }
}

/**
 * The answer revision/finality gate (0.24 spec §6, Task 16).
 *
 * The answer side of [AsrRevisionGate]'s rule: `ANSWER` revisions are display increments of one answer entry, the
 * custom-command channel promises no ordering, and the final `SHOW` replaces the line under `say-${hintId}`. A late
 * revision must therefore neither overwrite a newer one nor reopen a line the final already replaced. Revisions are
 * counted per **(route, hint)**: a new hint starts its own domain, and the two conversations are independent.
 *
 * Finalized entry ids are remembered per route in a bounded set (oldest evicted first), exactly like the ASR gate: a
 * revision delayed across a later answer's final still cannot reopen its own older row.
 */
class AnswerRevisionGate {

    /** The last applied revision per route, then per hint id. */
    private val lastRevision: MutableMap<AssistantRoute, MutableMap<String, Long>> = mutableMapOf()

    /** The recently finalized answer entry ids per route, oldest first. */
    private val finalizedEntries: MutableMap<AssistantRoute, MutableSet<String>> = mutableMapOf()

    /** True when [revision] is newer than everything applied for this route/hint and no final claimed the line. */
    fun accept(route: AssistantRoute, hintId: String, revision: Long): Boolean {
        if (hintId.isBlank() || revision < 0) return false
        if (isFinalized(route, hintId)) return false
        val perHint = lastRevision.getOrPut(route) { mutableMapOf() }
        val previous = perHint[hintId] ?: Long.MIN_VALUE
        if (revision <= previous) return false
        perHint[hintId] = revision
        return true
    }

    /** True when the final `SHOW` already replaced the line for [hintId]. */
    fun isFinalized(route: AssistantRoute, hintId: String): Boolean =
        finalizedEntries[route]?.contains(answerEntryId(hintId)) == true

    /** A final `SHOW` replaced [entryId]; no later revision may reopen that line. */
    fun finalize(route: AssistantRoute, entryId: String) {
        if (entryId.isBlank()) return
        val hintId = hintIdOf(entryId) ?: return
        val finalized = finalizedEntries.getOrPut(route) { linkedSetOf() }
        finalized += entryId
        while (finalized.size > MAX_FINALIZED_ENTRIES_PER_ROUTE) {
            val oldest = finalized.firstOrNull() ?: break
            finalized.remove(oldest)
        }
        lastRevision[route]?.remove(hintId)
    }

    /** A new session or a closed/reopened window starts a clean revision domain. */
    fun reset() {
        lastRevision.clear()
        finalizedEntries.clear()
    }

    companion object {
        private const val ANSWER_PREFIX = "say-"

        /** How many finalized answer entry ids one route keeps; the same bound the ASR gate uses. */
        private const val MAX_FINALIZED_ENTRIES_PER_ROUTE = 8

        /** 0.24 spec §6: the assistant's entry id is derived from the hint, not minted per statement. */
        fun answerEntryId(hintId: String): String = "$ANSWER_PREFIX$hintId"

        /** The hint named by an answer entry id, or null when the id is not the documented `say-${hintId}` shape. */
        fun hintIdOf(entryId: String): String? =
            entryId.takeIf { it.startsWith(ANSWER_PREFIX) && it.length > ANSWER_PREFIX.length }
                ?.removePrefix(ANSWER_PREFIX)
    }
}
