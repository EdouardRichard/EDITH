package com.sheen.rokid.glasses.display

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
