package com.sheen.rokid.phone.session

/**
 * 0.5.4 hotfix (K70 2026-11-06): drops a KWS wake that is actually the detached acknowledgement's own tail.
 *
 * The acknowledgement is played on the same phone speaker the microphone hears. [EchoMuteWindow] silences frames
 * while the player is audible, but the keyword spotter can still report a hit ~150 ms after the player's `onDone`;
 * the scene cooldown then admits a second wake and `emitWake` speaks "我在" again. The gate is driven by the
 * **player's real callbacks**, not by the request time: [started] when audio begins, [finished] when the player reports
 * the end/failure, so a cold TTS start that takes seconds does not shorten the window, and an utterance that never
 * became audible cannot suppress a real wake.
 *
 * The tail is longer than [EchoMuteWindow]'s 150 ms because it also covers the spotter's own reporting latency.
 * A wake while audible, or within [tailNanos] after the real end, is dropped whole: no acknowledgement and no
 * `WakeDetected` event (a second admit would open a ten-second turn with no user speech).
 *
 * Not thread-safe by lock; all three fields are [Volatile] because the player's callbacks run on its playback thread while
 * [shouldSuppress] is read on the audio loop. Only monotonic timestamps are expected; a backwards clock fails open
 * (no suppression) rather than muting forever.
 */
internal class WakeAckGate(
    private val tailNanos: Long = DEFAULT_TAIL_NANOS,
) {
    @Volatile private var audible = false
    @Volatile private var audibleSinceNanos = Long.MIN_VALUE
    @Volatile private var endedAtNanos = Long.MIN_VALUE

    /** The player's audible onset. */
    fun started(nowNanos: Long) {
        audible = true
        audibleSinceNanos = nowNanos
        endedAtNanos = Long.MIN_VALUE
    }

    /**
     * The player's real end/failure. A failure that never became audible is deliberately a no-op: nothing played,
     * so nothing can have been heard as a wake.
     */
    fun finished(nowNanos: Long) {
        if (!audible) return
        audible = false
        endedAtNanos = nowNanos
    }

    /** True while the ack is audible, or during the echo tail after its real end. */
    fun shouldSuppress(nowNanos: Long): Boolean {
        if (audible) {
            val since = audibleSinceNanos
            // A lost terminal must not silence every later wake forever: after a generous ceiling the gate fails
            // open. A backwards clock also fails open instead of extending the quiet.
            return since != Long.MIN_VALUE && nowNanos >= since &&
                nowNanos - since < MAX_AUDIBLE_NANOS
        }
        val ended = endedAtNanos
        return ended != Long.MIN_VALUE && nowNanos >= ended && nowNanos - ended < tailNanos
    }

    /** A new session/mode starts with no remembered ack. */
    fun reset() {
        audible = false
        audibleSinceNanos = Long.MIN_VALUE
        endedAtNanos = Long.MIN_VALUE
    }

    companion object {
        /**
         * Covers the observed spotter reporting latency (~150 ms on K70) with margin. A genuine second wake inside
         * half a second of the ack's end is indistinguishable from the echo and is safer dropped than admitted.
         */
        internal const val DEFAULT_TAIL_NANOS: Long = 500_000_000L

        /**
         * If the player never reports its terminal (closed while playing, listener takeover, engine dropped the
         * callback), the ack cannot be audible forever. Ten seconds is well beyond the ~3 s engine init + a short
         * phrase; after it the gate fails open and a later wake is admitted rather than silenced for the session.
         */
        internal const val MAX_AUDIBLE_NANOS: Long = 10_000_000_000L
    }
}
