package com.sheen.rokid.agent

/**
 * The no-progress guard: ends a run whose actions keep producing the same after-action fingerprint.
 *
 * [observeAfterAction] may only ever be called with a fingerprint that exists **after** an action ran. P3.0 measured
 * the failure mode the hard way: a first version also counted the observation made before a click, so three identical
 * pre-click screens tripped the guard before the click could take effect.
 *
 * [tripped] reflects the current run of consecutive identical fingerprints; a different fingerprint starts a new run,
 * which is what makes *consecutive* the load-bearing word, and [reset] starts a fresh streak before any observation.
 * The caller reads [tripped] immediately after each observation.
 */
class ProgressGuard(private val limit: Int = 3) {
    init {
        require(limit >= 1) { "ProgressGuard limit must be at least 1, was $limit" }
    }

    private var previous: String? = null
    private var consecutive = 0

    fun observeAfterAction(fingerprint: String) {
        consecutive = if (fingerprint == previous) consecutive + 1 else 1
        previous = fingerprint
    }

    /**
     * Drops the whole streak, so the next [observeAfterAction] is a first observation again. `AgentLoop.run` calls this
     * before its first observation: a guard is otherwise a per-loop object that could carry one run's streak (and its
     * armed `limit - 1` count) into the next run, tripping that run before it did anything.
     */
    fun reset() {
        previous = null
        consecutive = 0
    }

    val tripped: Boolean get() = consecutive >= limit
}
