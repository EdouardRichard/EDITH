package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationEntry

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
