package com.sheen.rokid.phone.actions.intent

/**
 * Whether this app currently holds `CALL_PHONE` (P3.3 Task 9).
 *
 * A port rather than a direct `checkSelfPermission` call because `dial` is an L2 action whose *refusal path* is the
 * interesting half: an unpermitted dial must refuse with a stable code and place no call, and that has to be provable
 * without a device. Reading the grant through a seam is also what keeps the executor free of Android APIs, like the
 * launcher and the catalog next to it.
 */
fun interface CallPermission {
    fun granted(): Boolean
}
