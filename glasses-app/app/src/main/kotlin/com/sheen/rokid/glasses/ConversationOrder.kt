package com.sheen.rokid.glasses

import com.sheen.rokid.contracts.AssistantRoute

/**
 * Phase C (spec §6.6): which conversation the glasses are showing, and how the wearer changes it.
 *
 * The glasses have no touch screen; the vendor's official sample documents the **temple's side-touch pad** as
 * `KeyEvent`s delivered to the foreground page (`50-system/01-按键与侧边触控.md`): a forward swipe is
 * `KEYCODE_DPAD_RIGHT`, a backward swipe is `KEYCODE_DPAD_LEFT`, a single tap is `KEYCODE_ENTER`. This class holds
 * only the decision — which route a swipe lands on, and whether a tap should stop or resume the slide — so the
 * decision is provable off-device while the Activity keeps the key plumbing.
 *
 * Two rules are deliberately asymmetric:
 *
 * - A swipe **wraps**, so both routes are reachable with one gesture from either side.
 * - [show] — used when a hint arrives for a route that is not the one on screen — is **not** a swipe: it is the
 *   display following the newest thing that needs the wearer's attention, which is what the panel has always done.
 *   Without it a proactive hint would be filed into a conversation nobody is looking at, and the wearer would never
 *   see it at all.
 */
class ConversationOrder {

    private var selected: AssistantRoute = AssistantRoute.WAKE
    private var paused = false

    /** The route currently in front of the wearer. */
    fun selected(): AssistantRoute = selected

    /** True while the wearer has stopped the slide, so arriving content holds still. */
    fun isPaused(): Boolean = paused

    /** Switches to the other route. Returns the route now selected. */
    fun swipe(): AssistantRoute {
        selected = if (selected == AssistantRoute.WAKE) AssistantRoute.PROACTIVE else AssistantRoute.WAKE
        return selected
    }

    /** A tap stops a slide that is running and resumes one that is not. Returns the new paused state. */
    fun togglePaused(): Boolean {
        paused = !paused
        return paused
    }

    /**
     * Brings [route] to the front because something arrived for it.
     *
     * Resuming here is deliberate: content that has just arrived is content the wearer has not read, and leaving a
     * stale pause in place would hold it off screen with nothing on the display to say why.
     */
    fun show(route: AssistantRoute): AssistantRoute {
        selected = route
        paused = false
        return selected
    }

    /** A new session opens on the wake conversation, unpaused. */
    fun reset() {
        selected = AssistantRoute.WAKE
        paused = false
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
