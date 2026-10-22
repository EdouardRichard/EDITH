package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.Assistance

/**
 * Playback state reported by a [SpeechPlayer] for a single utterance.
 *
 * - [STARTED]: the synthesizer has begun producing audible output (the playback gate should now suppress capture).
 * - [FINISHED]: the utterance completed normally; the gate may reopen.
 * - [FAILED]: synthesis or output failed after [STARTED]; the gate must still reopen.
 */
enum class SpeechPlayback { STARTED, FINISHED, FAILED }

/**
 * Host-owned port that turns an [Assistance] into audible speech.
 *
 * Plugins never call this directly; the output arbiter invokes it when a hint goes on screen and cancels it when the
 * hint leaves. Implementations live in the phone app and use platform TTS APIs only — no vendor engine package names
 * may be hardcoded here or in any implementation.
 */
interface SpeechPlayer {
    /**
     * Begin speaking [assistance]. The returned handle cancels this specific utterance; implementations must make
     * [CancellationHandle.cancel] idempotent and must report [SpeechPlayback.FINISHED] or [SpeechPlayback.FAILED]
     * exactly once even when cancelled.
     */
    fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle
}
