package edith.phone

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import edith.contracts.Assistance
import edith.output.TtsAvailability
import edith.output.availability
import edith.plugin.CancellationHandle
import edith.plugin.SpeechPlayback
import edith.plugin.SpeechPlayer
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
    override val isLanguageAvailable: Int get() = tts?.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED

    init {
        tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) { initStatus = status }
        })
    }

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
            // Wait briefly for init callback (it fires on the main thread; in practice it is near-instant).
            Thread.sleep(200)
            val avail = availability(
                enginePresent = engine.initStatus == TextToSpeech.SUCCESS || engine.initStatus == 0,
                languageResult = engine.isLanguageAvailable,
                initSucceeded = engine.initStatus == TextToSpeech.SUCCESS,
            )
            if (avail != TtsAvailability.READY) {
                android.util.Log.i("EdithSpeech", "TTS unavailable: $avail")
                engine.shutdown()
                return null
            }
            return AndroidSpeechPlayer(engine, gate)
        }
    }

    private val active = AtomicBoolean(false)

    override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
        val utteranceId = assistance.hintId
        val charCount = assistance.text.length
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
        engine.speak(assistance.text, utteranceId)
        return object : CancellationHandle {
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
    }

    override fun close() {
        engine.stop()
        engine.shutdown()
    }
}
