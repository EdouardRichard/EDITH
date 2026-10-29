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
 * **0.5.1 C5: the router owns the active handle, not the platform player.** The target, the tracked handle and a
 * generation are one immutable [State] replaced by compare-and-set, so no transition can observe "target detached but
 * handle still tracked" - the hole the review found between `target.get()` and `active.set(handle)` when the switch was
 * turned off during `play()`: that handle was never tracked and its utterance (and echo window) survived the off.
 * [stopCurrent] and [install]`(null)` therefore cancel what they remove, and a `play()` whose platform call was already
 * running publishes nothing once its generation is stale - it cancels its own handle and settles the caller with
 * `FAILED` unless the player's own cancel already reported a terminal (the port owes exactly one even when cancelled).
 *
 * **Closing a player is the composition owner's job, not this router's.** The router only forgets and cancels; the
 * root that created the player through its factory closes it (which is what releases the echo window).
 *
 * Threading: `play` can be called from the arbiter's serial executor and `install`/`stopCurrent` from the UI thread. All
 * state transitions are compare-and-set on one atomic; the returned handle is idempotent (the wrapped player is
 * required to be), so an install/stop racing a play cannot throw. `play` calls are serialized by the output arbiter.
 */
class SpeechPlayerRouter : SpeechPlayer {

    /** One atomic snapshot: the installed target, the tracked handle and the generation that invalidates stale plays. */
    private class State(
        val target: SpeechPlayer? = null,
        val active: CancellationHandle? = null,
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
                current.active?.let { cancelQuietly(it) }
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
        // platform player normally reports it, but a cancel that lands before the onset reports nothing, and the stale
        // branch below cancels the handle itself. The tracker forwards at most one terminal to the caller and lets the
        // stale branch tell whether the platform player's cancel already settled the playback.
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
        while (true) {
            val current = state.get()
            if (current.target !== player || current.generation != start.generation) {
                // Off/stop (or a replacement player) went through while the platform player was starting. Publish
                // nothing and cancel what it returned: an untracked handle would keep speaking with nobody able to
                // stop it, and its echo window would stay open.
                cancelQuietly(handle)
                // And settle this play: the platform's cancel may have reported nothing (it never reached PLAYING, or a
                // close won the race). Without a terminal the arbiter keeps the hint and the turn waiting for
                // SPEECH_EXPIRY_GRACE (120 s) after the display budget.
                if (terminal.compareAndSet(false, true)) runCatching { onState(SpeechPlayback.FAILED) }
                return NoopHandle
            }
            if (state.compareAndSet(current, State(player, handle, current.generation))) {
                return object : CancellationHandle {
                    override fun cancel() = release(handle)
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
                current.active?.let { cancelQuietly(it) }
                return
            }
        }
    }

    private fun detach() {
        while (true) {
            val current = state.get()
            val next = State(null, null, current.generation + 1)
            if (state.compareAndSet(current, next)) {
                current.active?.let { cancelQuietly(it) }
                return
            }
        }
    }

    /** Clears this handle if it is still the tracked one, then cancels it once; a late cancel is a no-op. */
    private fun release(handle: CancellationHandle) {
        while (true) {
            val current = state.get()
            if (current.active !== handle) return
            val next = State(current.target, null, current.generation + 1)
            if (state.compareAndSet(current, next)) {
                cancelQuietly(handle)
                return
            }
        }
    }

    private fun cancelQuietly(handle: CancellationHandle) {
        runCatching { handle.cancel() }
    }

    private object NoopHandle : CancellationHandle {
        override fun cancel() = Unit
    }
}
