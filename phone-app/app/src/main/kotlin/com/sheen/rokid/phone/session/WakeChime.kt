package com.sheen.rokid.phone.session

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 0.23 P5 (spec §5.3.4, U12/D135): the wake answer is a **short chime**, not the fixed TTS phrase 「在的」.
 *
 * Two rules only, and both are about the person wearing the glasses:
 * - the 「语音朗读」 switch is the only thing that silences it; off means off;
 * - a second wake inside 800 ms does not chime again. The official `aiWakeSound` is debounced, and two chimes in
 *   half a second read as a stutter rather than as "I heard you".
 *
 * Monotonic nanoseconds, never the wall clock: the same reason the rest of the audio path uses `nowNanos`.
 */
class WakeChime(private val debounceMillis: Long = DEBOUNCE_MILLIS) {

    init {
        require(debounceMillis > 0) { "DEBOUNCE_MUST_BE_POSITIVE" }
    }

    private var lastChimeNanos: Long? = null

    /**
     * Whether a wake at [nowNanos] should chime, given the user's switch. A `false` answer does **not** mean the wake
     * was dropped: the caller still emits `WakeDetected`.
     */
    fun onWake(nowNanos: Long, enabled: Boolean): Boolean {
        if (!enabled) return false
        val last = lastChimeNanos
        if (last != null && nowNanos - last < debounceMillis * 1_000_000L) return false
        lastChimeNanos = nowNanos
        return true
    }

    /**
     * Asks [sink] to play exactly when [onWake] accepts. A sink that throws costs the wearer the sound, never the
     * wake: this runs on the path that emits `WakeDetected`, and an exception must not take that down.
     */
    internal fun play(nowNanos: Long, enabled: Boolean, sink: WakeChimeSink): Boolean {
        if (!onWake(nowNanos, enabled)) return false
        runCatching { sink.play() }
        return true
    }

    /** As [play], but keeps the sink's own result so `P5_CHIME` cannot say `played` after a failed tone. */
    internal fun playOutcome(nowNanos: Long, enabled: Boolean, sink: WakeChimeSink): WakeChimeOutcome {
        if (!enabled) return WakeChimeOutcome.DISABLED
        if (!onWake(nowNanos, true)) return WakeChimeOutcome.DEBOUNCED
        val played = runCatching { sink.play() }.getOrDefault(false)
        return if (played) WakeChimeOutcome.PLAYED else WakeChimeOutcome.FAILED
    }

    /** Forgets the debounce window; used when a composition is rebuilt rather than on a normal wake. */
    fun reset() {
        lastChimeNanos = null
    }

    companion object {
        /** The official 800 ms debounce. */
        const val DEBOUNCE_MILLIS = 800L
    }
}

/** The one thing the chime needs from Android, so the decision above stays JVM-testable. */
internal fun interface WakeChimeSink {
    /** True when the platform accepted the tone; false when it could not even start one. */
    fun play(): Boolean
}

/** The result of one wake's chime attempt, for the one `P5_CHIME` line the composition writes. */
internal enum class WakeChimeOutcome { DISABLED, DEBOUNCED, PLAYED, FAILED }

/**
 * The platform equivalent of the official short `aiWakeSound`: a 120 ms `TONE_PROP_BEEP` on the music stream.
 *
 * The vendor's FX 34 is not reachable through the public SDK (D135), so this is the honest substitute: a platform
 * tone, not a claim of matching the official audio. Construction failure is not fatal - a composition without an
 * audio device simply never chimes.
 */
internal class ToneWakeChimeSink(context: Context) : WakeChimeSink, AutoCloseable {

    private val tone: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull()

    override fun play(): Boolean {
        val generator = tone ?: return false
        return runCatching {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS)
        }.getOrDefault(false)
    }

    override fun close() {
        runCatching { tone?.release() }
    }

    private companion object {
        const val TAG = "EdithChime"
        const val TONE_VOLUME = 80
        const val TONE_MILLIS = 120
    }
}
