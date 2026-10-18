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
 * are independent. A final entry id is remembered per route; the next turn's entry has a different id and is not
 * blocked by it.
 */
class AsrRevisionGate {

    /** The last applied revision per route, then per turn. */
    private val lastRevision: MutableMap<AssistantRoute, MutableMap<String, Long>> = mutableMapOf()

    /** The final entry id per route; a late revision for that id is stale. */
    private val finalizedEntry: MutableMap<AssistantRoute, String> = mutableMapOf()

    /** True when [revision] is newer than everything applied for this route/turn and no final has claimed the line. */
    fun accept(route: AssistantRoute, turnId: String, revision: Long): Boolean {
        if (turnId.isBlank() || revision < 0) return false
        if (finalizedEntry[route] == userEntryId(turnId)) return false
        val perTurn = lastRevision.getOrPut(route) { mutableMapOf() }
        val previous = perTurn[turnId] ?: Long.MIN_VALUE
        if (revision <= previous) return false
        perTurn[turnId] = revision
        return true
    }

    /** True when a final already replaced the line for [turnId]; a later `ASR` revision is stale. */
    fun isFinalized(route: AssistantRoute, turnId: String): Boolean =
        finalizedEntry[route] == userEntryId(turnId)

    /** A final `SAID`/`SHOW` replaced [entryId]; no later revision may reopen that line. */
    fun finalize(route: AssistantRoute, entryId: String) {
        if (entryId.isNotBlank()) finalizedEntry[route] = entryId
    }

    /** A new session or a closed/reopened window starts a clean revision domain. */
    fun reset() {
        lastRevision.clear()
        finalizedEntry.clear()
    }

    companion object {
        private const val USER_PREFIX = "asr-"

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
