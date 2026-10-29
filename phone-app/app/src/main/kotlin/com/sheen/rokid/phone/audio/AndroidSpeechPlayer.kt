package com.sheen.rokid.phone.audio
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.session.EchoMuteWindow
import com.sheen.rokid.phone.session.PlaybackGate

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.output.TtsAvailability
import com.sheen.rokid.output.availability
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seamed TTS engine so [AndroidSpeechPlayer] can be tested without the real Android framework.
 *
 * Production wraps [TextToSpeech]; tests inject a fake that records calls and fires callbacks on demand.
 */
internal interface TtsEngine {
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
    fun setOnProgressListener(listener: UtteranceProgressListener?)
    val isLanguageAvailable: Int
    val initStatus: Int
}

/**
 * Production [TtsEngine] backed by the platform [TextToSpeech].
 *
 * No vendor engine package name is hardcoded: the system default engine is used, whatever it is on this device.
 */
internal class PlatformTtsEngine(context: Context) : TtsEngine {
    private var tts: TextToSpeech? = null
    override var initStatus: Int = TextToSpeech.ERROR
        private set
    private val initLatch = java.util.concurrent.CountDownLatch(1)
    override val isLanguageAvailable: Int get() = tts?.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED

    init {
        tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                initStatus = status
                initLatch.countDown()
            }
        })
    }

    /** Block until the TTS engine's onInit callback fires, or timeout after [timeoutMs]. */
    fun awaitInit(timeoutMs: Long = 5000): Boolean = initLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun speak(text: String, utteranceId: String): Boolean =
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS

    override fun stop() { tts?.stop() }
    override fun shutdown() { tts?.shutdown(); tts = null }
    override fun setOnProgressListener(listener: UtteranceProgressListener?) {
        tts?.setOnUtteranceProgressListener(listener)
    }
}

/**
 * System-TTS implementation of [SpeechPlayer] (spec §S1).
 *
 * Uses only the generic Android `android.speech.tts.TextToSpeech` API; no vendor engine package name is referenced.
 * The playback gate (`begin`/`end`) wraps the **actual audible interval** (`onStart` → `onDone`/`onError`, and also
 * `onStop`, a handle cancel, or `close()`), not the synthesis preparation time.
 */
internal class AndroidSpeechPlayer(
    private val engine: TtsEngine,
    private val gate: PlaybackGate = PlaybackGate(),
    /**
     * 0.5 (`D213` fix): the half-duplex window over the phone microphone.
     *
     * 0.5.1 C5: this player is the window's **single owner**. It opens the window at the audible onset and closes it one
     * reverb tail after the end/failure/cancel, or on [close]. The detached wake ack must not open or close it: the root
     * doing so made a second owner, opened the window twice, and could leave it open forever when the engine never
     * started. The window exists because, with the phone-only model's speaker output, the phone mic heard the answer and
     * the ASR transcribed it as a user turn, which the continuous-dialogue path then answered - an echo loop.
     */
    private val echoMute: EchoMuteWindow? = null,
) : SpeechPlayer, AutoCloseable {

    companion object {
        /** The audible-interval states of [active]: idle, a playback owns the gate/window, or the player is closed. */
        private const val IDLE = 0
        private const val PLAYING = 1
        private const val CLOSED = 2

        /**
         * Factory that builds an [AndroidSpeechPlayer] from the platform TTS, or returns null when the engine is not
         * available. The caller decides what to do with the null (typically: leave [HostEnvironment.speech] as null so
         * [Capability.SpeechOutput] is not advertised).
         */
        fun create(
            context: Context,
            gate: PlaybackGate = PlaybackGate(),
            echoMute: EchoMuteWindow? = null,
        ): AndroidSpeechPlayer? {
            val engine = PlatformTtsEngine(context)
            // On MIUI/K40 the TTS onInit callback may be delayed by heavy Compose rendering during app startup.
            // Rather than blocking (which deadlocks on the main thread or times out on a background thread), we
            // create the player optimistically and let the first play() call verify readiness. If the engine never
            // initializes, play() will report FAILED and the gate stays open.
            android.util.Log.i("EdithSpeech", "TTS engine created, deferring availability check to first play()")
            return AndroidSpeechPlayer(engine, gate, echoMute)
        }
    }

    /**
     * 0.5.1 C5: the audible-interval state, one atomic machine rather than two flags.
     *
     * [PLAYING] means a playback owns the gate and the echo window; [CLOSED] is terminal, so a late `onStart` delivered
     * by the engine after `close()` can never re-arm them (it would leave the window open with no callback left to
     * close it).
     */
    private val active = AtomicInteger(IDLE)

    /**
     * 0.5.1 (review Important #1): one playback request's own lifecycle.
     *
     * The player-wide [active] state cannot say **which** request was cancelled. A cancel before the audible onset left
     * the player [IDLE], so "the CAS found nothing to settle" was also indistinguishable from "nothing was cancelled",
     * and a late `onStart` for that request could still win IDLE -> PLAYING - re-arming the gate and the echo window
     * for a playback whose caller had already been told it was over. Each request now carries `cancelled` (latched by
     * the handle's `cancel()`, and by a start the engine refuses), `started` (it owns the PLAYING state) and
     * `terminated` (the one terminal it owes its caller). All three are created per request and captured by that
     * request's listener, so a late callback can only ever be inert.
     */
    private class Utterance(val utteranceId: String, val charCount: Int) {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        private val terminated = AtomicBoolean(false)

        /** True exactly once: whichever settle path (cancel or engine callback) gets here first owns the terminal. */
        fun claimTerminal(): Boolean = terminated.compareAndSet(false, true)
    }

    /**
     * Serializes the gate/echo transitions of `onStart`, `finish` and `close`.
     *
     * The callback side runs on the TTS engine's thread and `close()` on the composition's, so "CAS the state, then
     * begin/open" and "set CLOSED, then close the window" can otherwise interleave: an `onStart` that won the CAS just
     * before `close()` could open the window **after** close had already closed it, leaving the mic muted with no
     * callback left to release it. Holding this lock across the state change *and* the gate/window effects makes each
     * transition atomic; the listener callbacks are invoked outside it.
     */
    private val transition = Any()

    private var availabilityChecked = false

    override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
        if (!ensureAvailable(assistance.hintId, onState)) return noOpHandle()
        // 0.5.1 C5: one terminal per request. An engine that rejects the utterance returns false and will never deliver
        // a callback, so the port's "exactly one FINISHED/FAILED even when cancelled" would otherwise be silent.
        val report = exactlyOnceTerminal(onState)
        val utterance = Utterance(assistance.hintId, assistance.text.length)
        if (!startUtterance(assistance.text, utterance, report)) {
            P3Log.line(
                "P5_SPEECH state=failed hintId=${assistance.hintId} chars=${assistance.text.length} reason=ENGINE_REFUSED",
            )
            report(SpeechPlayback.FAILED)
            return noOpHandle()
        }
        return cancellationHandle(utterance, report)
    }

    /**
     * Wraps [onState] so a playback reports **at most one** terminal (`FINISHED`/`FAILED`), even when the engine or one
     * of this player's own settle paths delivers more than one. `STARTED` is forwarded only before the terminal.
     *
     * The [SpeechPlayer] port promises exactly one terminal per playback, cancelled or not; the wrapper turns that
     * promise into an invariant of this implementation instead of trusting every engine callback sequence.
     */
    private fun exactlyOnceTerminal(onState: (SpeechPlayback) -> Unit): (SpeechPlayback) -> Unit {
        val settled = AtomicBoolean(false)
        return { playback ->
            when {
                settled.get() -> Unit
                playback == SpeechPlayback.STARTED -> onState(playback)
                settled.compareAndSet(false, true) -> onState(playback)
                else -> Unit
            }
        }
    }

    /**
     * Speaks one fixed, host-owned phrase outside the output arbiter (the detached wake acknowledgement).
     * Uses the same lazy availability check, engine listener and [PlaybackGate] as normal answers. 0.5.1 C5: this
     * player - not the caller - opens the echo window at the audible onset and closes it one reverb tail after the
     * end/failure, so the caller must not manage the window around this call.
     *
     * @return false when the engine is unavailable or rejected the utterance; the caller may then fall back.
     */
    internal fun speakText(
        text: String,
        utteranceId: String,
        onState: (SpeechPlayback) -> Unit = {},
    ): Boolean {
        if (!ensureAvailable(utteranceId, onState)) return false
        return startUtterance(text, Utterance(utteranceId, text.length), onState)
    }

    private fun ensureAvailable(utteranceId: String, onState: (SpeechPlayback) -> Unit): Boolean {
        // Lazy availability check: the TTS engine may not have finished initializing at create() time.
        if (!availabilityChecked) {
            availabilityChecked = true
            val eng = engine as? PlatformTtsEngine
            if (eng != null && !eng.awaitInit(3000)) {
                P3Log.line("P5_SPEECH state=failed hintId=$utteranceId reason=INIT_TIMEOUT")
                onState(SpeechPlayback.FAILED)
                return false
            }
            val avail = availability(
                enginePresent = engine.initStatus == TextToSpeech.SUCCESS || engine.initStatus == 0,
                languageResult = engine.isLanguageAvailable,
                initSucceeded = engine.initStatus == TextToSpeech.SUCCESS,
            )
            if (avail != TtsAvailability.READY) {
                P3Log.line("P5_SPEECH state=failed hintId=$utteranceId reason=$avail")
                onState(SpeechPlayback.FAILED)
                return false
            }
        }
        return true
    }

    private fun startUtterance(
        text: String,
        utterance: Utterance,
        onState: (SpeechPlayback) -> Unit,
    ): Boolean {
        val utteranceId = utterance.utteranceId
        val charCount = utterance.charCount
        engine.setOnProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String) {
                if (uid != utteranceId) return
                val started = synchronized(transition) {
                    // 0.5.1 (review Important #1): the cancelled check is inside the lock so it cannot race cancel()'s
                    // settle; cancel latches the flag before asking the engine to stop. A request that was cancelled
                    // (or refused by the engine) before its onset therefore never reopens the gate or the echo window.
                    if (utterance.cancelled.get()) {
                        false
                    } else {
                        active.compareAndSet(IDLE, PLAYING).also { won ->
                            if (won) {
                                utterance.started.set(true)
                                gate.begin()
                                // 0.5: the phone mic must not hear the start of our own utterance; open before the
                                // first sample.
                                echoMute?.open()
                            }
                        }
                    }
                }
                if (!started) return
                P3Log.line("P5_SPEECH state=start hintId=$uid chars=$charCount")
                onState(SpeechPlayback.STARTED)
            }
            override fun onDone(uid: String) {
                if (uid == utteranceId) finish(SpeechPlayback.FINISHED, utterance, onState, "end")
            }
            @Suppress("DEPRECATION")
            override fun onError(uid: String) {
                if (uid == utteranceId) finish(SpeechPlayback.FAILED, utterance, onState, "failed")
            }
            /**
             * 0.5.1 C5: `TextToSpeech.stop()`/an interruption delivers `onStop`, not `onDone`/`onError`. Without this
             * override the playback gate stayed closed and the echo window stayed open - the review's "TTS `onStop`
             * is not overridden".
             */
            override fun onStop(uid: String, interrupted: Boolean) {
                if (uid == utteranceId) finish(SpeechPlayback.FINISHED, utterance, onState, "stopped")
            }
        })
        val accepted = engine.speak(text, utteranceId)
        // The engine rejects the request with false and normally delivers no callback. A misbehaving engine might still
        // deliver a late onset; latching the request cancelled keeps that onset from arming a gate/echo that no
        // callback will release (the same inertness a pre-onset cancel needs).
        if (!accepted) utterance.cancelled.set(true)
        return accepted
    }

    /**
     * The one terminal transition of the audible interval: close the gate, close the echo window with its reverb tail,
     * log and report - exactly once, whichever callback arrives first.
     *
     * A callback for an utterance that was cancelled is inert: it must not report after the cancel, and it must not
     * touch a gate/echo that [cancellationHandle] already released. The PLAYING -> IDLE CAS (rather than an
     * utterance-scoped "did it start" test) is deliberately kept as the recovery path for an utterance whose onset was
     * never reported: the engine has already told us this playback is over, so leaving the gate/echo armed would be the
     * permanent mute 0.5.1 removed the watchdog for.
     */
    private fun finish(
        playback: SpeechPlayback,
        utterance: Utterance,
        onState: (SpeechPlayback) -> Unit,
        logState: String,
    ) {
        val finished = synchronized(transition) {
            if (utterance.cancelled.get()) {
                false
            } else {
                active.compareAndSet(PLAYING, IDLE).also { won ->
                    if (won) {
                        // Clear the per-request marker under the same lock that closes the audible interval.
                        utterance.started.set(false)
                        gate.end()
                        echoMute?.close()
                    }
                }
            }
        }
        if (!finished) return
        reportTerminal(utterance, playback, onState, logState)
    }

    /** Reports one terminal, whichever settle path gets here first; a later path is a no-op. */
    private fun reportTerminal(
        utterance: Utterance,
        playback: SpeechPlayback,
        onState: (SpeechPlayback) -> Unit,
        logState: String,
    ) {
        if (!utterance.claimTerminal()) return
        P3Log.line("P5_SPEECH state=$logState hintId=${utterance.utteranceId} chars=${utterance.charCount}")
        onState(playback)
    }

    private fun cancellationHandle(
        utterance: Utterance,
        onState: (SpeechPlayback) -> Unit,
    ): CancellationHandle = object : CancellationHandle {
        override fun cancel() {
            // Latch first: from this point on a late `onStart`/`onDone`/`onStop` is inert, and a second cancel is a
            // no-op.
            if (!utterance.cancelled.compareAndSet(false, true)) return
            engine.stop()
            synchronized(transition) {
                // Only a request that actually owns the audible interval may release the gate/echo; a pre-onset cancel
                // has nothing to release, and `close()` already released a closed player.
                if (utterance.started.compareAndSet(true, false) && active.compareAndSet(PLAYING, IDLE)) {
                    gate.end()
                    echoMute?.close()
                }
            }
            // Report a terminal even when the request never reached PLAYING (or close() won the race): the cancel is the
            // caller's only settle, and without it the arbiter holds the hint/turn for SPEECH_EXPIRY_GRACE (120 s).
            reportTerminal(utterance, SpeechPlayback.FINISHED, onState, "cancelled")
        }
    }

    private fun noOpHandle(): CancellationHandle = object : CancellationHandle {
        override fun cancel() = Unit
    }

    /**
     * Closes the player. 0.5.1 C5: this player is the owner of the echo window, so closing it must release the phone
     * microphone even when the platform never delivered the end callback that would have done it.
     *
     * `close()` - not an immediate clear - keeps the existing 150 ms reverb tail: audio that already left the speaker
     * may still be in the room. The audible-interval state is reset **before** the engine is shut down, so a late
     * `onStop` produced by `engine.stop()` cannot report a second completion for a player that no longer exists.
     */
    override fun close() {
        synchronized(transition) {
            // The state change and the window close are one critical section: an `onStart` that won the CAS already
            // opened the window and closed the gate, so both are undone here; one that lost can never open it after.
            val wasPlaying = active.getAndSet(CLOSED) == PLAYING
            // The window close is inside the critical section (see the KDoc): it keeps the existing tail.
            echoMute?.close()
            // The gate is a counter, so it is only decremented for a playback this player actually started; an
            // unmatched `end` would leave a credit that swallows one later wake suppression.
            if (wasPlaying) gate.end()
        }
        engine.stop()
        engine.shutdown()
    }
}
