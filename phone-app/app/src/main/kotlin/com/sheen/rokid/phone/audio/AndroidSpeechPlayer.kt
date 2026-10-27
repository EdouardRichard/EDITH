package com.sheen.rokid.phone.audio
import com.sheen.rokid.phone.platform.P3Log
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
 * The playback gate (`begin`/`end`) wraps the **actual audible interval** (`onStart` → `onDone`/`onError`), not the
 * synthesis preparation time.
 */
internal class AndroidSpeechPlayer(
    private val engine: TtsEngine,
    private val gate: PlaybackGate = PlaybackGate(),
) : SpeechPlayer, AutoCloseable {

    companion object {
        /**
         * Factory that builds an [AndroidSpeechPlayer] from the platform TTS, or returns null when the engine is not
         * available. The caller decides what to do with the null (typically: leave [HostEnvironment.speech] as null so
         * [Capability.SpeechOutput] is not advertised).
         */
        fun create(context: Context, gate: PlaybackGate = PlaybackGate()): AndroidSpeechPlayer? {
            val engine = PlatformTtsEngine(context)
            // On MIUI/K40 the TTS onInit callback may be delayed by heavy Compose rendering during app startup.
            // Rather than blocking (which deadlocks on the main thread or times out on a background thread), we
            // create the player optimistically and let the first play() call verify readiness. If the engine never
            // initializes, play() will report FAILED and the gate stays open.
            android.util.Log.i("EdithSpeech", "TTS engine created, deferring availability check to first play()")
            return AndroidSpeechPlayer(engine, gate)
        }
    }

    private val active = AtomicBoolean(false)

    private var availabilityChecked = false

    override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
        if (!ensureAvailable(assistance.hintId, onState)) return noOpHandle()
        startUtterance(assistance.text, assistance.hintId, onState)
        return cancellationHandle(assistance.hintId, assistance.text.length, onState)
    }

    /**
     * Speaks one fixed, host-owned phrase outside the output arbiter (the detached wake acknowledgement).
     * Uses the same lazy availability check, engine listener and [PlaybackGate] as normal answers; [onState] lets
     * the caller close its echo-mute window exactly when the audible interval ends.
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
                if (uid == utteranceId && active.compareAndSet(false, true)) {
                    gate.begin()
                    P3Log.line("P5_SPEECH state=start hintId=$uid chars=$charCount")
                    onState(SpeechPlayback.STARTED)
                }
            }
            override fun onDone(uid: String) {
                if (uid == utteranceId && active.compareAndSet(true, false)) {
                    gate.end()
                    P3Log.line("P5_SPEECH state=end hintId=$uid chars=$charCount")
                    onState(SpeechPlayback.FINISHED)
                }
            }
            @Suppress("DEPRECATION")
            override fun onError(uid: String) {
                if (uid == utteranceId && active.compareAndSet(true, false)) {
                    gate.end()
                    P3Log.line("P5_SPEECH state=failed hintId=$uid chars=$charCount")
                    onState(SpeechPlayback.FAILED)
                }
            }
        })
        return engine.speak(text, utteranceId)
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
                if (active.compareAndSet(true, false)) {
                    gate.end()
                    P3Log.line("P5_SPEECH state=cancelled hintId=$utteranceId chars=$charCount")
                    onState(SpeechPlayback.FINISHED)
                }
            }
        }
    }

    private fun noOpHandle(): CancellationHandle = object : CancellationHandle {
        override fun cancel() = Unit
    }

    override fun close() {
        engine.stop()
        engine.shutdown()
    }
}
