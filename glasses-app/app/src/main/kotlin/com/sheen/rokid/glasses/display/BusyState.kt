package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute

/**
 * Phase C (spec §6.6 加载动画): whether the assistant is working, per route, decided without Android.
 *
 * [com.sheen.rokid.output.HintCommand.Busy] is a **level, not an event** — the phone states it when a turn starts and states the
 * opposite when the turn ends, and the later statement wins. That makes one failure mode obvious: a single missing
 * "finished" (a model timeout, a `Stop`, a process that died) would leave the ring turning and the panel held awake on
 * a device the wearer cannot switch off from the app. So the level **expires by itself** at [maxMillis], which is the
 * difference between an animation that stops and an animation that has to be stopped.
 *
 * The clock is injected as a plain `Long`, in the same monotonic nanoseconds the rest of the glasses app uses.
 */
class BusyState(private val maxMillis: Long = MAX_MILLIS) {

    init {
        require(maxMillis > 0) { "MAX_MILLIS_MUST_BE_POSITIVE" }
    }

    /** When each route's current busy level was **first** reported; null means that route is idle. */
    private val startedAt: MutableMap<AssistantRoute, Long?> =
        AssistantRoute.entries.associateWithTo(linkedMapOf()) { null }

    /**
     * Records the level the phone stated for [route].
     *
     * Re-stating a level that is already in force does **not** restart its clock: the phone repeats the level on every
     * turn, and if each repeat reset the start instant the bound above could never fire.
     */
    fun set(route: AssistantRoute, busy: Boolean, nowNanos: Long) {
        val current = startedAt[route]
        if (busy) {
            if (current == null) startedAt[route] = nowNanos
        } else {
            startedAt[route] = null
        }
    }

    /** True while any route is busy and has not yet outlived the bound. */
    fun any(nowNanos: Long): Boolean = route(nowNanos) != null

    /**
     * Whether **this** route is busy and still inside the bound.
     *
     * 0.23 P3/P4: the panel shows the state of the conversation in front of the wearer, so a proactive turn must not
     * make the wake route look busy (or the reverse). `any` remains the answer for the two decisions that are about
     * the whole assistant - the bottom-left "interrupt" copy and the BACK semantics.
     */
    fun isBusy(route: AssistantRoute, nowNanos: Long): Boolean {
        val started = startedAt[route] ?: return false
        return nowNanos - started <= maxMillis * 1_000_000L
    }

    /**
     * The route to name on the display: the one that started most recently and has not outlived the bound.
     *
     * The newest statement is the true one — the header says which conversation is being worked on, and when two are
     * (a wake turn while a proactive turn finishes), the one that just started is what the wearer is waiting for.
     */
    fun route(nowNanos: Long): AssistantRoute? =
        AssistantRoute.entries
            .mapNotNull { route -> startedAt[route]?.let { started -> route to started } }
            .filter { (_, started) -> nowNanos - started <= maxMillis * 1_000_000L }
            .maxByOrNull { (_, started) -> started }
            ?.first

    /** A new session starts idle on both routes. */
    fun reset() {
        AssistantRoute.entries.forEach { startedAt[it] = null }
    }

    companion object {
        /**
         * How long a busy level is believed without being re-stated.
         *
         * Longer than any turn this assistant can run — the request deadline is well under a minute — and short enough
         * that a leaked level cannot hold the panel awake for a noticeable time.
         */
        const val MAX_MILLIS = 60_000L
    }
}

/**
 * The rotation of the loading ring, as a pure function of elapsed time (Phase C, spec §6.6 点线圆圈旋转).
 *
 * Keeping the angle here leaves exactly one untestable half — the `View` that draws dots at those angles — instead of
 * an animation whose speed can only be judged by looking at it.
 */
object LoadingIndicator {

    /** How many dots form the ring. Enough to read as a circle, few enough to draw on a glasses panel. */
    const val DOTS = 8

    /** One revolution takes this long: slow enough to read as "working", fast enough not to look frozen. */
    const val TURN_MILLIS = 1_200L

    /**
     * The ring's rotation at [elapsedMillis], in degrees in `[0, 360)`.
     *
     * A negative elapsed time is the start of the animation rather than a backwards rotation: the caller may hand
     * over a clock difference that has not been clamped, and wrapping backwards would show as a visible jolt.
     */
    fun angleFor(elapsedMillis: Long): Float {
        if (elapsedMillis <= 0L) return 0f
        val withinTurn = elapsedMillis % TURN_MILLIS
        return withinTurn / TURN_MILLIS.toFloat() * 360f
    }
}
