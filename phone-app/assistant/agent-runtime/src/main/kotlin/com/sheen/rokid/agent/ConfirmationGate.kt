package com.sheen.rokid.agent

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock

/**
 * The confirmation state machine (spec §7.3/§7.4): one pending question per [request] id, settled exactly once.
 *
 * The load-bearing rule is the **timeout default of deny**. A confirmation that is not answered within [timeoutNanos]
 * (10 s by default) delivers `false`; the gate never treats silence as consent, because the user walking away, the
 * screen being off, or the phone being out of range must all mean "no". Withdrawing a request — [cancelAll] on 急停, or
 * cancelling the handle [request] returned — is a denial too, for the same reason.
 *
 * Every request settles *at most once*. [settle] removes the entry **before** it invokes the listener, so a listener
 * that re-enters the gate (resolving its own id, or a stop cancelling everything) observes an already-settled request
 * instead of producing a second, possibly contradicting, decision.
 *
 * P3.1 declares the port only and has no confirmation channel wired (see [PolicyDecision.RequireConfirmation]); the
 * summary carried by [request] is stored for the channel that later renders the question.
 *
 * **Thread safety.** P3.1 assumed delivery was single-threaded; P3.3 is not. The plugin asks from the turn's worker, the
 * host answers from its event dispatcher, 急停 cancels from the UI thread, and the timeout fires on the deadline thread
 * - so [request], [resolve] and [cancelAll] may all arrive at once. Every entry point is therefore synchronized, and
 * [settle] removes the entry **while holding that lock**, which is what keeps "settled at most once" true under
 * concurrency and not just under a single thread. A listener that re-enters the gate does so on the same thread (the
 * intrinsic lock is re-entrant) and finds its request already gone.
 */
class ConfirmationGate(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val timeoutNanos: Long = 10_000_000_000L,
) {
    /** One pending question; [timer] is armed after the entry is registered, so it is null only inside [request]. */
    private class Pending(val summary: String, val listener: (Boolean) -> Unit) {
        var timer: CancellationHandle? = null
    }

    private val pending = mutableMapOf<String, Pending>()

    init {
        require(timeoutNanos > 0) { "ConfirmationGate timeout must be positive, was $timeoutNanos" }
    }

    /**
     * Asks the user, arming this request's deny-by-timeout, and returns a handle that withdraws it (settles it as
     * denied, like every other withdrawal).
     *
     * A duplicate id is rejected rather than silently replacing the pending question: overwriting would strand a
     * listener that the caller still believes is live.
     */
    @Synchronized
    fun request(requestId: String, summary: String, listener: (Boolean) -> Unit): CancellationHandle {
        require(requestId.isNotBlank()) { "ConfirmationGate request id must not be blank" }
        require(requestId !in pending) { "ConfirmationGate already has a pending request with id: $requestId" }
        val entry = Pending(summary, listener)
        // Register before arming the timer: a scheduler that fires immediately must still find the entry to settle.
        pending[requestId] = entry
        entry.timer = scheduler.at(clock.nowNanos() + timeoutNanos) { settle(requestId, approved = false) }
        return object : CancellationHandle {
            override fun cancel() {
                settle(requestId, approved = false)
            }
        }
    }

    /** Delivers [approved] to a still-pending request. `false` when the id is unknown or already settled. */
    @Synchronized
    fun resolve(requestId: String, approved: Boolean): Boolean = settle(requestId, approved)

    /** 急停: settles every pending request as denied and clears its timer, so nothing is left to fire later. */
    @Synchronized
    fun cancelAll() {
        pending.keys.toList().forEach { settle(it, approved = false) }
    }

    @Synchronized
    private fun settle(requestId: String, approved: Boolean): Boolean {
        // Removal is the settlement marker, and it happens first so a re-entrant listener cannot settle twice.
        val entry = pending.remove(requestId) ?: return false
        entry.timer?.cancel()
        entry.listener(approved)
        return true
    }
}
