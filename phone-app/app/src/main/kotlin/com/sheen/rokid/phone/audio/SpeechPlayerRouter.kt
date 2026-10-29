package com.sheen.rokid.phone.audio

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
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
 * Threading: `play` can be called from the arbiter's serial executor and `install`/`stopCurrent` from the UI thread. The
 * references are atomic; the returned handle is idempotent (the wrapped player is required to be), so an install/stop
 * racing a play cannot throw.
 */
class SpeechPlayerRouter : SpeechPlayer {

    private val target = AtomicReference<SpeechPlayer?>(null)
    private val active = AtomicReference<CancellationHandle?>(null)

    /** Installs [player] as the current target (null detaches). Does not stop an utterance; callers pair it with [stopCurrent]. */
    fun install(player: SpeechPlayer?) {
        target.set(player)
    }

    /** The current platform player, or null while 「语音朗读」 is off / unavailable. */
    fun current(): SpeechPlayer? = target.get()

    /** True while a player is installed; the host advertises `SpeechOutput` on this fact. */
    fun isAvailable(): Boolean = target.get() != null

    override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
        val player = target.get()
        if (player == null) {
            // A request that races the off switch is reported as a failed playback, not silently accepted: the arbiter
            // then releases the speech wait and the conversation cycle continues instead of holding the display open.
            runCatching { onState(SpeechPlayback.FAILED) }
            return NoopHandle
        }
        val handle = player.play(assistance, onState)
        active.set(handle)
        return object : CancellationHandle {
            override fun cancel() {
                active.compareAndSet(handle, null)
                runCatching { handle.cancel() }
            }
        }
    }

    /** Stops the utterance in flight (if any) and forgets it; the platform player itself is closed by the caller. */
    fun stopCurrent() {
        active.getAndSet(null)?.let { runCatching { it.cancel() } }
    }

    private object NoopHandle : CancellationHandle {
        override fun cancel() = Unit
    }
}
