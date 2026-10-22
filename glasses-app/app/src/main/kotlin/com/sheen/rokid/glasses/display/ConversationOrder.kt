package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute

/**
 * 0.23 P4 (spec §5.2.1, U8): which conversation the glasses are showing, and the wearer's pin.
 *
 * The official panel has **no route label and no route gesture**: the assistant's answer is whatever arrived last.
 * EDITH keeps two conversations internally (wake and proactive), but the glasses no longer offer a switch - the view
 * follows `SHOW`, `SAID` does not move it, and the other log is only retained in memory for the current window.
 *
 * The pin is the only view-level state left. It maps the official "single tap = hold" gesture to EDITH's reading
 * control: one tap pins, and there is no unpin gesture (official `holdAIAssistant()` has no cancel path). Pinning no
 * longer stops the ticker; it only means "do not auto-exit". It is deliberately **not** a route state, so a new answer
 * can still be brought into view while the pin is held.
 */
class ConversationOrder {

    private var selected: AssistantRoute = AssistantRoute.WAKE
    private var pinned: Boolean = false

    /** The route currently in front of the wearer. */
    fun selected(): AssistantRoute = selected

    /** True while the wearer has pinned the reading position. */
    fun isPinned(): Boolean = pinned

    /**
     * Brings [route] to the front because something arrived for it.
     *
     * This is the **only** way the selected route changes: a `SHOW` (the assistant telling the wearer something), not a
     * `SAID` (the wearer's own words, which do not need looking at). The pin is left alone - it is a reading position,
     * not a property of the conversation.
     */
    fun show(route: AssistantRoute): AssistantRoute {
        selected = route
        return selected
    }

    /**
     * Pins the reading position. **One-way**: the official `holdAIAssistant()` has no cancel path, so a second tap
     * changes nothing. Only [reset] (a new session/window) clears it.
     */
    fun pin() {
        pinned = true
    }

    /** A new session opens on the wake conversation, unpinned. */
    fun reset() {
        selected = AssistantRoute.WAKE
        pinned = false
    }
}

/**
 * The `SAID` ordering gate (Phase C).
 *
 * The custom-command channel promises nothing about ordering — `HintWireCodec`'s own contract says so — so an
 * utterance that arrives late would be appended behind a later one. The phone numbers its utterances per session,
 * so the rule is the one the rest of this wire already uses: accept a strictly increasing sequence, counted **per
 * route**, because the two conversations are independent.
 */
class SaidSequenceGate {

    private val last: MutableMap<AssistantRoute, Long> =
        AssistantRoute.entries.associateWithTo(linkedMapOf()) { Long.MIN_VALUE }

    fun accept(route: AssistantRoute, sequence: Long): Boolean {
        if (sequence <= last.getValue(route)) return false
        last[route] = sequence
        return true
    }

    fun reset() {
        AssistantRoute.entries.forEach { last[it] = Long.MIN_VALUE }
    }
}
