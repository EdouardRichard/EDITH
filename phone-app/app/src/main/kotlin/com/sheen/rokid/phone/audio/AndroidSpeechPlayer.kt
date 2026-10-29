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
        if (!startUtterance(assistance.text, assistance.hintId, report)) {
            P3Log.line(
                "P5_SPEECH state=failed hintId=${assistance.hintId} chars=${assistance.text.length} reason=ENGINE_REFUSED",
            )
            report(SpeechPlayback.FAILED)
            return noOpHandle()
        }
        return cancellationHandle(assistance.hintId, assistance.text.length, report)
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
        return startUtterance(text, utteranceId, onState)
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
        utteranceId: String,
        onState: (SpeechPlayback) -> Unit,
    ): Boolean {
        val charCount = text.length
        engine.setOnProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String) {
                if (uid != utteranceId) return
                val started = synchronized(transition) {
                    active.compareAndSet(IDLE, PLAYING).also { won ->
                        if (won) {
                            gate.begin()
                            // 0.5: the phone mic must not hear the start of our own utterance; open before the first
                            // sample.
                            echoMute?.open()
                        }
                    }
                }
                if (!started) return
                P3Log.line("P5_SPEECH state=start hintId=$uid chars=$charCount")
                onState(SpeechPlayback.STARTED)
            }
            override fun onDone(uid: String) {
                if (uid == utteranceId) finish(SpeechPlayback.FINISHED, uid, charCount, onState, "end")
            }
            @Suppress("DEPRECATION")
            override fun onError(uid: String) {
                if (uid == utteranceId) finish(SpeechPlayback.FAILED, uid, charCount, onState, "failed")
            }
            /**
             * 0.5.1 C5: `TextToSpeech.stop()`/an interruption delivers `onStop`, not `onDone`/`onError`. Without this
             * override the playback gate stayed closed and the echo window stayed open - the review's "TTS `onStop`
             * is not overridden".
             */
            override fun onStop(uid: String, interrupted: Boolean) {
                if (uid == utteranceId) finish(SpeechPlayback.FINISHED, uid, charCount, onState, "stopped")
            }
        })
        return engine.speak(text, utteranceId)
    }

    /**
     * The one terminal transition of the audible interval: close the gate, close the echo window with its reverb tail,
     * log and report - exactly once, whichever callback arrives first.
     */
    private fun finish(
        playback: SpeechPlayback,
        utteranceId: String,
        charCount: Int,
        onState: (SpeechPlayback) -> Unit,
        logState: String,
    ) {
        val finished = synchronized(transition) {
            active.compareAndSet(PLAYING, IDLE).also { won ->
                if (won) {
                    gate.end()
                    echoMute?.close()
                }
            }
        }
        if (!finished) return
        P3Log.line("P5_SPEECH state=$logState hintId=$utteranceId chars=$charCount")
        onState(playback)
    }

    private fun cancellationHandle(
        utteranceId: String,
        charCount: Int,
        onState: (SpeechPlayback) -> Unit,
    ): CancellationHandle = object : CancellationHandle {
        private val cancelled = AtomicBoolean(false)
        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                engine.stop()
                finish(SpeechPlayback.FINISHED, utteranceId, charCount, onState, "cancelled")
            }
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
