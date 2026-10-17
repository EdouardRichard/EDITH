package edith.audio

import edith.plugin.MonotonicClock

/**
 * Platform-independent wake-word engine port.
 *
 * The wake layer is deliberately split into two implementations:
 * [AsrTextWakeWordMatcher] (the path that actually runs in P3.1, because it only needs the ASR artifacts the
 * bundle really carries) and [KeywordSpotterWakeWordEngine] (the KWS path over the pinned sherpa-onnx
 * `KeywordSpotter` API, whose model ships as assets when the approved local bundle is present — see that class
 * for what is measured and what is not).
 */
interface WakeWordEngine {
    /** Matched wake words for this frame; empty when the frame carries no wake word. */
    fun accept(frame: PcmFrame): List<String>
    fun reset()
    fun close()
}

/**
 * Comparable form of wake text: trim, full-width → half-width, lower-case, and drop everything that is not a
 * letter or a digit (so spaces and punctuation do not break a match).
 *
 * `"伊迪丝"`, `"伊 迪 丝"`, `"ＥＤＩＴＨ"`, `"Edith!"` and `"edith"` therefore all normalise to a form that
 * matches the corresponding configured wake word, while `"edison"` never matches `"edith"`.
 */
fun defaultWakeNormalization(text: String): String = buildString(text.length) {
    text.forEach { character ->
        val code = character.code
        val folded = when {
            code in 0xFF01..0xFF5E -> code - 0xFEE0
            code == 0x3000 -> ' '.code
            else -> code
        }.toChar()
        if (folded.isLetterOrDigit()) append(folded.lowercaseChar())
    }
}

/**
 * The form of a configured wake word that goes into **offline tokenisation** (the official
 * `sherpa-onnx-cli text2token --tokens-type phone+ppinyin --lexicon en.phone` recipe).
 *
 * `en.phone`'s headwords are **uppercase-only**, so a lower-case English wake word is skipped *silently*:
 * no error, no tokens, no way to spot the word on device. Uppercasing the ASCII letters here is exactly what
 * prevents that — `"edith"` and `"Edith"` both tokenise to `IY1 D IH0 TH @EDITH`, while CJK is untouched
 * (`"伊迪丝"` → `y ī d í s ī @伊迪丝`).
 *
 * Matching keeps using [defaultWakeNormalization] (which lower-cases); this function only feeds the
 * tokenisation/configuration side.
 */
fun wakeWordForTokenisation(raw: String): String = buildString(raw.length) {
    raw.trim().forEach { character ->
        append(if (character in 'a'..'z') character.uppercaseChar() else character)
    }
}

/**
 * Wakes by matching ASR text — **the wake path P3.1 actually runs**, because it needs only the ASR artifacts
 * that `tools/p2/engine-manifest.json` really declares.
 *
 * Each frame is handed to the wrapped [StreamingAsrEngine] and **both partial and final** hypotheses are
 * matched (partials matter for wake latency). `StreamingAsrEngine.finish` is intentionally never called: the
 * matcher must not close the recogniser's streaming turn.
 *
 * **Ownership:** this is a faithful wrapper. [reset] and [close] delegate to the wrapped engine, so the caller
 * must not share one [StreamingAsrEngine] between the wake path and another owner (in particular the wake
 * detector releases its engine's stream after a sustained silence). Give the wake path its own engine instance.
 */
class AsrTextWakeWordMatcher(
    private val asr: StreamingAsrEngine,
    private val words: List<String>,
    private val normalize: (String) -> String = ::defaultWakeNormalization,
) : WakeWordEngine {
    private val configured = words.map { it to normalize(it) }
    private var closed = false

    override fun accept(frame: PcmFrame): List<String> {
        check(!closed)
        // A separator keeps a match from spanning two hypotheses; normalisation already removed real spaces.
        val text = asr.accept(frame).joinToString(" ") { normalize(it.text) }
        if (text.isEmpty()) return emptyList()
        return configured.filter { (_, normalized) -> normalized.isNotEmpty() && normalized in text }
            .map { (word, _) -> word }
    }

    override fun reset() {
        check(!closed)
        asr.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        asr.close()
    }
}

/**
 * Native keyword-spotting port, mirroring the pinned AAR's `KeywordSpotter` surface so the engine above is
 * testable without the native library.
 *
 * `reset` is the AAR's **in-place** `KeywordSpotter.reset(OnlineStream)`: it clears the stream's detection
 * state without releasing it. [KeywordSpotterWakeWordEngine] calls it after every reported hit (so the same
 * keyword is not re-reported on every ready frame) and releases/recreates the stream on its own `reset`.
 */
interface KeywordSpotterPort {
    fun createStream(keywords: String): KeywordStreamPort
    fun decode(stream: KeywordStreamPort)
    fun isReady(stream: KeywordStreamPort): Boolean
    fun result(stream: KeywordStreamPort): KeywordHit?
    fun reset(stream: KeywordStreamPort)
    fun close()
}

interface KeywordStreamPort {
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)
    fun release()
}

data class KeywordHit(val keyword: String, val tokens: List<String>, val timestamps: FloatArray)

/**
 * KWS wake-word engine over a [KeywordSpotterPort].
 *
 * **Honest status — wired to the API, model recall unmeasured.** `javap -p` on the pinned
 * `audio-android/libs/sherpa-onnx-1.13.8.aar` (SHA-256 `633C2432…3BD96`) proves the native API exists:
 * `KeywordSpotter(AssetManager, KeywordSpotterConfig)`, `createStream(String): OnlineStream`,
 * `decode(OnlineStream)`, `reset(OnlineStream)`, `isReady(OnlineStream): Boolean`,
 * `getResult(OnlineStream): KeywordSpotterResult(keyword, tokens, timestamps)`, `release()`;
 * `KeywordSpotterConfig(FeatureConfig, OnlineModelConfig, int maxActivePaths, String keywordsFile,
 * float keywordsScore, float keywordsThreshold, int numTrailingBlanks)`; and
 * `OnlineStream.acceptWaveform(FloatArray, Int)` / `inputFinished()`. The same javap shows the file-based
 * entry point `newFromFile(KeywordSpotterConfig)` is **private native**, so the public route is assets only.
 *
 * Since 2026-10-09 a real KWS model (user-approved download, zh-en zipformer 3M) is present under the
 * git-ignored `.local/p3-kws-model/`; `audio-android`'s `generateKwsModelAssets` task packages it as `kws/`
 * assets when it is there and packages nothing when it is not (the app then reports `KWS_MODEL_MISSING`).
 * `tools/p2/engine-manifest.json` still declares only the ASR/VAD/speaker roles.
 *
 * **Unmeasured:** the model's own `test_wavs/` contains no audio for `伊迪丝`/`EDITH`, so their recall is not
 * verified here; its `en_*.wav` clips trigger `LIGHT_UP`/`LOVELY_CHILD` and are kept for a device check.
 *
 * [accept] resets the stream **in place** after a hit, so a detection is reported once and the stream keeps
 * decoding; [reset] releases the current stream and the next [accept] recreates it (mirrors
 * `OwnedRecognizerPort`).
 */
class KeywordSpotterWakeWordEngine(
    private val port: KeywordSpotterPort,
    private val keywords: String,
    private val sampleRateHz: Int = 16_000,
) : WakeWordEngine {
    private var stream: KeywordStreamPort? = null
    private var closed = false

    override fun accept(frame: PcmFrame): List<String> {
        check(!closed)
        require(frame.sampleRateHz == sampleRateHz) {
            "keyword spotter is configured for $sampleRateHz Hz but received ${frame.sampleRateHz} Hz"
        }
        val current = stream ?: port.createStream(keywords).also { stream = it }
        current.acceptWaveform(frame.samples, sampleRateHz)
        if (!port.isReady(current)) return emptyList()
        port.decode(current)
        val hit = port.result(current) ?: return emptyList()
        // In place, before reporting: without it the stream still holds the detection and the same keyword
        // would be re-reported on every ready frame.
        port.reset(current)
        return listOf(hit.keyword)
    }

    override fun reset() {
        check(!closed)
        val previous = stream
        stream = null
        previous?.release()
    }

    override fun close() {
        if (closed) return
        closed = true
        val previous = stream
        stream = null
        previous?.release()
        port.close()
    }
}

/**
 * Wake detection over a wake [engine], with a per-word cooldown and a sustained-silence release.
 *
 * - **Every frame reaches [engine], whatever the VAD decides** (D11). A wake word is normally the first ~400 ms
 *   of an utterance while the VAD's first `speech` decision lands 440–960 ms in, so gating KWS feeding on the
 *   VAD starves the spotter of exactly the frames that carry the word — and the stream used to be created
 *   lazily at that first speech frame, so the onset had nowhere to go even in principle. The VAD's only role
 *   here is the *release* decision below; turn boundaries are [SpeechPipeline]'s business and its VAD is a
 *   separate engine (see `WiredWakeDetector`).
 * - The stream is released **only after a sustained silence**: once a speech frame has been seen, a run of
 *   non-speech frames lasting [silenceReleaseNanos] calls [WakeWordEngine.reset] exactly once, and the next
 *   frame recreates the stream. A short pause inside one utterance therefore keeps the stream and its decoding
 *   context alive. Silence before any speech never releases, so an idle detector does not churn streams.
 * - The same word is emitted at most once per [cooldownNanos] (anchored on the emission, not on the report);
 *   different words never block each other. An engine hit is emitted even on a frame the VAD calls silence:
 *   the emission rides the engine's return value, not the VAD's decision.
 * - While [suppressWhile] is true the detector touches neither the VAD nor the engine and emits nothing — this
 *   is the guard that stops our own playback/hint text from re-triggering the wake word. Suppressed frames do
 *   not move the release state either: the engine was not fed and the VAD was not consulted.
 *
 * The wake-word port carries no score, so a reported match is emitted with confidence `1.0`; calibration is the
 * engine's business (a real KWS hit would have to surface its own score through the port first).
 */
class WakeWordDetector(
    private val vad: VadEngine,
    private val engine: WakeWordEngine,
    private val emit: (word: String, confidence: Double) -> Unit,
    private val clock: MonotonicClock,
    private val cooldownNanos: Long = 2_000_000_000L,
    private val suppressWhile: () -> Boolean = { false },
    private val silenceReleaseNanos: Long = 1_500_000_000L,
) {
    private val lastEmittedNanos = mutableMapOf<String, Long>()
    private var speechActive = false
    private var lastSpeechNanos = 0L
    private var closed = false

    fun accept(frame: PcmFrame) {
        check(!closed)
        if (suppressWhile()) return
        val decision = vad.accept(frame)
        val hits = engine.accept(frame)
        val now = clock.nowNanos()
        hits.forEach { word ->
            val previous = lastEmittedNanos[word]
            if (previous != null && now - previous < cooldownNanos) return@forEach
            lastEmittedNanos[word] = now
            emit(word, matchedConfidence)
        }
        if (decision.speech) {
            speechActive = true
            lastSpeechNanos = now
        } else if (speechActive && now - lastSpeechNanos >= silenceReleaseNanos) {
            speechActive = false
            engine.reset()
        }
    }

    fun reset() {
        check(!closed)
        lastEmittedNanos.clear()
        speechActive = false
        lastSpeechNanos = 0L
        engine.reset()
    }

    fun close() {
        if (closed) return
        closed = true
        lastEmittedNanos.clear()
        engine.close()
    }

    private companion object {
        const val matchedConfidence = 1.0
    }
}
