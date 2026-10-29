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
 * The window is defined **only by playback events**, never by what is played: it opens at the moment the App
 * requests the speech (which precedes the audible onset, covering output latency) and closes one reverb tail after
 * the player reports the utterance finished or failed. Frames whose capture timestamp falls inside are replaced by
 * silence at the ingress dispatch point, so the acknowledgement can never become a transcript and can never corrupt
 * the turn that follows it. A lost end callback is bounded by [DEFAULT_WATCHDOG_NANOS] rather than deafening the
 * session forever.
 *
 * Content-independent by construction: a different phrase, voice, language or volume changes only the callback
 * times, and the window follows them.
 */
class EchoMuteWindow(
    private val clock: MonotonicClock,
    private val tailNanos: Long = DEFAULT_TAIL_NANOS,
    private val watchdogNanos: Long = DEFAULT_WATCHDOG_NANOS,
) {

    @Volatile private var openAtNanos: Long = -1L
    @Volatile private var endAtNanos: Long = -1L

    /** Starts muting from now; also arms the watchdog in case no end callback ever arrives. */
    fun open() {
        val now = clock.nowNanos()
        openAtNanos = now
        endAtNanos = now + watchdogNanos
    }

    /** Schedules the end of the window one reverb tail from now; a no-op when no window is open. */
    fun close() {
        if (openAtNanos < 0L) return
        endAtNanos = clock.nowNanos() + tailNanos
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

        /**
         * Hard bound when the TTS engine never reports an end.
         *
         * 0.4 sized this for the short detached acknowledgement (5 s). 0.5's final answers go through the same window,
         * and an answer may legitimately speak for far longer than 5 s; a 5 s bound would unmute the phone mic halfway
         * through the answer and the ASR would transcribe our own voice. The bound is therefore the same 120 s the
         * conversation cycle allows a missing `SpeechFinished`, which is the failure this watchdog covers.
         */
        const val DEFAULT_WATCHDOG_NANOS: Long = 120_000_000_000L
    }
}
