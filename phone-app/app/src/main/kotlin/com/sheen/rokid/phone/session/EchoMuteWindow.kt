package com.sheen.rokid.phone.session

import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.plugin.MonotonicClock

/**
 * The same frame with its samples replaced by silence, keeping epoch/range/timestamps so the consumer's timeline
 * stays contiguous (dropping the frame instead would read as a discontinuity and kill live turns).
 */
internal fun PcmFrame.silenced(): PcmFrame = copy(samples = FloatArray(samples.size))

/**
 * 0.4 detached wake acknowledgement (owner 2026-10-27): the half-duplex window that keeps the phone's own
 * acknowledgement out of the phone microphone's ASR/VAD/KWS.
 *
 * The window is defined **only by playback events**, never by what is played: it opens at the **audible onset** (the
 * platform player's `onStart`, i.e. when the speaker starts emitting) and closes one reverb tail after the player
 * reports the utterance finished, failed or interrupted. Frames whose capture timestamp falls inside are replaced by
 * silence at the ingress dispatch point, so the acknowledgement can never become a transcript and can never corrupt
 * the turn that follows it.
 *
 * 0.5.1 C5 removed the 120 s watchdog. A lost end callback no longer expires by time - the old bound silently unmuted
 * the phone mic halfway through a long answer and the ASR transcribed the App's own voice - so recovery is explicit:
 * exactly four actions call [clear] - starting a manual BALL dictation, turning speech off, stopping the session and
 * the long-press takeover - while [close] (with its reverb tail) marks every playback end/close. A lost callback may
 * outlive its answer and stays muted through any other action (a plain voice wake does not clear it); only one of
 * those four releases it. Do not read the bound as "the next user action".
 *
 * Content-independent by construction: a different phrase, voice, language or volume changes only the callback
 * times, and the window follows them.
 */
class EchoMuteWindow(
    private val clock: MonotonicClock,
    private val tailNanos: Long = DEFAULT_TAIL_NANOS,
) {

    @Volatile private var openAtNanos: Long = -1L
    @Volatile private var endAtNanos: Long = -1L

    /**
     * Starts muting from the audible onset. There is deliberately no time bound (0.5.1 C5 removed the watchdog):
     * an end callback that never arrives is released only by [clear] on one of its four explicit actions, not by the
     * clock and not by a later voice wake.
     */
    fun open() {
        openAtNanos = clock.nowNanos()
        endAtNanos = Long.MAX_VALUE
    }

    /**
     * Schedules the end of the window one reverb tail from now; a no-op when no window is open.
     *
     * The addition saturates at `Long.MAX_VALUE` instead of overflowing: a clock near the end of its range must not
     * turn the tail into a past instant (which would silently unmute during the tail it was meant to cover).
     */
    fun close() {
        if (openAtNanos < 0L) return
        val now = clock.nowNanos()
        endAtNanos = if (tailNanos > 0L && now > Long.MAX_VALUE - tailNanos) Long.MAX_VALUE else now + tailNanos
    }

    /**
     * Drops the window **immediately**, including a reverb tail [close] has already scheduled.
     *
     * This is 0.5.1 C5's explicit recovery for a lost end callback: starting a manual BALL dictation, turning speech
     * off, stopping the session or the long-press takeover calls it. A missing callback therefore outlives its answer
     * until one of those four actions runs - a voice wake does not release it, so the bound is not "the next user
     * action". Both bounds are reset, not just [endAtNanos]: an already-ended window must stay a true no-op, and a late
     * [close] must not re-arm it (that is what `openAtNanos < 0` means to [close]).
     */
    fun clear() {
        openAtNanos = -1L
        endAtNanos = -1L
    }

    /** True when a frame captured at [nanos] falls inside the open window. */
    fun covers(nanos: Long): Boolean {
        val openAt = openAtNanos
        if (openAt < 0L) return false
        return nanos >= openAt && nanos < endAtNanos
    }

    companion object {
        /** Room reverb after the speaker stops; measured conservatively for a phone on a desk or in a hand. */
        const val DEFAULT_TAIL_NANOS: Long = 150_000_000L
    }
}
