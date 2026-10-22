package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.agent.AgentLoop

/**
 * One proactive **hint turn** in flight (0.3 Phase 3 spec §4.2): the identity it was opened under and the handles
 * `Stop`/`deactivate` need to cancel it.
 *
 * It is deliberately not a `SmartTurn`: a hint turn has no listening window, no transcript to adopt and no display
 * acknowledgement of its own. It owns only what the blocking half (`runHintTurn`) and the cancellation paths share:
 *
 * - [hintId]/[turnId] are the **same** identities the displayed `Assistance` carries, so a cancellation names the row
 *   the wearer is looking at (Task 6's follow-up window keys on them too);
 * - [startedNanos] anchors the turn's deadline once, at admission, so every silence line can report the true elapsed
 *   time and a queued task is measured from the trigger rather than from when a worker happened to pick it up;
 * - [cancelled] is the flag the plugin's own gateway polls as its `stop` predicate. It is `@Volatile` because `Stop`
 *   runs on the host's dispatcher while the hint run is blocked on the transport;
 * - [loop] is the single-use `AgentLoop` once it exists, so `Stop` can cancel the run itself (and its executors) as
 *   well as unblocking the transport. Written and read under the plugin's `gate`.
 */
internal class ProactiveHintRequest(
    val hintId: String,
    val turnId: String,
    val startedNanos: Long,
) {
    @Volatile
    var cancelled: Boolean = false

    /** The loop currently serving this request, or `null` before it exists and after it returns. Guarded by `gate`. */
    var loop: AgentLoop? = null
}
