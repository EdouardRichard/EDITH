package com.sheen.rokid.phone.audio

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.5 (`D213`): a stable [SpeechPlayer] whose target can be replaced while the composition runs.
 *
 * `CxrHostEnvironment.speech` and the host's `OutputArbiter` capture their player when the composition is assembled;
 * the 0.4 switch therefore only took effect after an App restart. This router is the stable object they hold. Enabling
 * 「语音朗读」 installs a freshly created platform player; disabling stops the utterance in flight, detaches, and the
 * caller closes the player. The plugin's own `speechAvailable` gate (the holder) and the environment's capability
 * predicate both read the same live presence, so no request is made while the target is absent.
 *
 * **0.5.1 C5: the router owns the active playback, not the platform player.** The target, the tracked [Playback] and a
 * generation are one immutable [State] replaced by compare-and-set, so no transition can observe "target detached but
 * handle still tracked" - the hole the review found between `target.get()` and `active.set(handle)` when the switch was
 * turned off during `play()`: that handle was never tracked and its utterance (and echo window) survived the off.
 * **Every** router-initiated cancel (the returned handle, [stopCurrent], detach, a replacement) goes through
 * [Playback.cancel], which cancels the platform handle and supplies the terminal the player did not report: the port owes
 * exactly one FINISHED/FAILED even when cancelled, and the arbiter otherwise waits its 120 s grace. A `play()` whose
 * platform call was already running publishes nothing once its generation is stale and settles its caller the same way.
 *
 * **Closing a player is the composition owner's job, not this router's.** The router only forgets and cancels; the
 * root that created the player through its factory closes it (which is what releases the echo window).
 *
 * Threading: `play` can be called from the arbiter's serial executor and `install`/`stopCurrent` from the UI thread. All
 * state transitions are compare-and-set on one atomic; the returned handle is idempotent (the wrapped player is
 * required to be), so an install/stop racing a play cannot throw. `play` calls are serialized by the output arbiter.
 */
class SpeechPlayerRouter : SpeechPlayer {

    /**
     * One playback the router owns: the platform handle plus the exactly-once terminal it owes the caller.
     *
     * 0.5.1 (review Important #1): a tracked handle's cancel only cancelled. When the platform player reports no terminal
     * - a cancel that lands before its audible onset reaches no PLAYING state, or `close()` won the race - the caller
     * (the `OutputArbiter`) waited for `SPEECH_EXPIRY_GRACE` (120 s). [cancel] therefore settles the request itself when
     * the player did not, mirroring the stale-play branch.
     */
    private class Playback(
        val handle: CancellationHandle,
        private val terminal: AtomicBoolean,
        private val onState: (SpeechPlayback) -> Unit,
    ) {
        /** Cancels the platform utterance and reports FAILED only if no terminal was forwarded already. */
        fun cancel() {
            runCatching { handle.cancel() }
            if (terminal.compareAndSet(false, true)) runCatching { onState(SpeechPlayback.FAILED) }
        }
    }

    /** One atomic snapshot: the installed target, the tracked playback and the generation that invalidates stale plays. */
    private class State(
        val target: SpeechPlayer? = null,
        val active: Playback? = null,
        val generation: Long = 0,
    )

    private val state = AtomicReference(State())

    /**
     * Installs [player] as the current target; null detaches.
     *
     * A detach also cancels and forgets the tracked utterance: leaving it tracked against a null target is exactly the
     * untracked-handle state the ownership fix removes. Installing a **different** player does the same with the old
     * target's handle - the new target cannot own the previous target's utterance, so carrying it across would make it
     * unstoppable through the router (the orphan-handle invariant). Re-installing the same player is an idempotent
     * "on": nothing was replaced, so its in-flight utterance keeps playing.
     */
    fun install(player: SpeechPlayer?) {
        if (player == null) {
            detach()
            return
        }
        while (true) {
            val current = state.get()
            if (current.target === player) return
            val next = State(player, null, current.generation + 1)
            if (state.compareAndSet(current, next)) {
                current.active?.cancel()
                return
            }
        }
    }

    /** The current platform player, or null while 「语音朗读」 is off / unavailable. */
    fun current(): SpeechPlayer? = state.get().target

    /** True while a player is installed; the host advertises `SpeechOutput` on this fact. */
    fun isAvailable(): Boolean = state.get().target != null

    override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
        val start = state.get()
        val player = start.target
        if (player == null) {
            // A request that races the off switch is reported as a failed playback, not silently accepted: the arbiter
            // then releases the speech wait and the conversation cycle continues instead of holding the display open.
            runCatching { onState(SpeechPlayback.FAILED) }
            return NoopHandle
        }
        // The port owes its caller exactly one terminal (FINISHED or FAILED) even when the utterance is cancelled. The
        // platform player normally reports it, but a cancel that lands before the onset reports nothing. The tracker
        // forwards at most one terminal to the caller; [Playback.cancel] uses the same flag to tell whether the platform
        // player's cancel already settled the playback, and only supplies the missing terminal when it did not.
        val terminal = AtomicBoolean(false)
        val report: (SpeechPlayback) -> Unit = { playback ->
            when {
                terminal.get() -> Unit
                playback == SpeechPlayback.STARTED -> onState(playback)
                terminal.compareAndSet(false, true) -> onState(playback)
                else -> Unit
            }
        }
        val handle = player.play(assistance, report)
        val playback = Playback(handle, terminal, onState)
        while (true) {
            val current = state.get()
            if (current.target !== player || current.generation != start.generation) {
                // Off/stop (or a replacement player) went through while the platform player was starting. Publish
                // nothing and cancel what it returned: an untracked handle would keep speaking with nobody able to
                // stop it, and its echo window would stay open. `Playback.cancel` also settles this play when the
                // platform's cancel reports nothing (it never reached PLAYING, or a close won the race); without a
                // terminal the arbiter keeps the hint and the turn waiting for SPEECH_EXPIRY_GRACE (120 s).
                playback.cancel()
                return NoopHandle
            }
            if (state.compareAndSet(current, State(player, playback, current.generation))) {
                return object : CancellationHandle {
                    override fun cancel() = release(playback)
                }
            }
        }
    }

    /** Stops the utterance in flight (if any) and forgets it; the platform player itself is closed by the caller. */
    fun stopCurrent() {
        while (true) {
            val current = state.get()
            // Bump the generation even when nothing is tracked: a play() that is currently inside the platform player
            // must not publish its handle after this stop.
            val next = State(current.target, null, current.generation + 1)
            if (state.compareAndSet(current, next)) {
                current.active?.cancel()
                return
            }
        }
    }

    private fun detach() {
        while (true) {
            val current = state.get()
            val next = State(null, null, current.generation + 1)
            if (state.compareAndSet(current, next)) {
                current.active?.cancel()
                return
            }
        }
    }

    /** Clears this playback if it is still the tracked one, then cancels it once; a late cancel is a no-op. */
    private fun release(playback: Playback) {
        while (true) {
            val current = state.get()
            if (current.active !== playback) return
            val next = State(current.target, null, current.generation + 1)
            if (state.compareAndSet(current, next)) {
                playback.cancel()
                return
            }
        }
    }

    private object NoopHandle : CancellationHandle {
        override fun cancel() = Unit
    }
}
