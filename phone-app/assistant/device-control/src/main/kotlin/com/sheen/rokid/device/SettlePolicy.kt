package com.sheen.rokid.device

/**
 * How long the kernel waits after a mutating action before it accepts that the action changed
 * nothing (spec §4.4).
 *
 * These are the spec's starting values, not calibrated device values. A tap can start a navigation
 * that commits hundreds of milliseconds later, so the kernel polls the accessibility tree until it
 * leaves the pre-action shape or the per-kind [changeWaitMillis] expires. A scroll is usually
 * settled within half a second; a type reads the first observation that shows the change
 * (`after-change`), while taps, scrolls and keys additionally require two consecutive identical
 * observations ([requiresHeldStill], bounded by [heldStillMillis]) so a list caught mid-update is
 * not returned as settled.
 *
 * The kernel takes this as a constructor parameter so tests can shrink the budgets; production uses
 * the defaults.
 */
data class SettlePolicy(
    /** `tap`/`double_tap`/`long_press`: spec §4.4 changeWait. */
    val tapChangeWaitMillis: Long = 2_000L,
    /** `scroll`/`scroll_to`/`swipe`: spec §4.4 changeWait. */
    val scrollChangeWaitMillis: Long = 500L,
    /** `type`: spec §4.4 changeWait. */
    val typeChangeWaitMillis: Long = 800L,
    /** `press_key`/`home`/`back`/`recents`/`notifications`: a key can replace the whole screen. */
    val keyChangeWaitMillis: Long = 2_000L,
    /** Spec §4.4 "held-still 上限": how long two captures may take to agree after a change. */
    val heldStillMillis: Long = 1_000L,
    /** Bounded polling granularity; the kernel never sleeps for a fixed settle interval. */
    val pollIntervalMillis: Long = 100L,
) {

    /** The change-wait budget for [kind]; zero means "look once" (a wait applies nothing). */
    fun changeWaitMillis(kind: ActKind): Long = when (kind) {
        ActKind.TAP, ActKind.LONG_PRESS, ActKind.DOUBLE_TAP -> tapChangeWaitMillis
        ActKind.SWIPE, ActKind.SCROLL, ActKind.SCROLL_TO_TEXT -> scrollChangeWaitMillis
        ActKind.TYPE -> typeChangeWaitMillis
        ActKind.PRESS_KEY, ActKind.HOME, ActKind.BACK, ActKind.RECENTS, ActKind.NOTIFICATIONS -> keyChangeWaitMillis
        ActKind.WAIT -> 0L
    }.coerceAtLeast(0L)

    /** Whether the kind needs two agreeing captures after the change (all writes except `type`). */
    fun requiresHeldStill(kind: ActKind): Boolean = when (kind) {
        ActKind.TYPE, ActKind.WAIT -> false
        else -> true
    }

    companion object {
        /**
         * Spec §4.4's `open` budget. `open` is a Task 10 tool action (it launches through the shell
         * and observes the foreground app), not an [ActKind]; it uses this budget when it settles.
         */
        const val OPEN_CHANGE_WAIT_MILLIS = 1_500L
    }
}
