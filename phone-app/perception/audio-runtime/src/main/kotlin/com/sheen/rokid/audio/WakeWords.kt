package com.sheen.rokid.audio

import com.sheen.rokid.plugin.MonotonicClock

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
 * **Honest status — wired to the API, model recall measured 2026-10-20.** `javap -p` on the pinned
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
 * **Phase diversity (F-01, measured 2026-10-20).** A single stream is not merely "one decoder": whether the
 * model reports a keyword depends on **where the word falls relative to its chunk boundary**, because the
 * pinned model decodes one 160 ms chunk at a time from a stream that has already been fed an arbitrary amount
 * of audio. A device probe fed the same 8.9 s/9.5 s wake recordings through the real packaged assets with a
 * quiet prefix in front: with one stream, the clips hit at a 0 ms prefix but were **deterministically missed**
 * at 5 s (5/5 misses), and 8 of the 9 English alignment/prefix cases it was run on produced no hit. Four
 * streams spaced 40 ms apart hit all 11 probe cases (9 English + 2 Chinese), but a later production run still
 * missed one of twelve injections; **eight** streams, one per 20 ms phase, hit every one of the twelve probe
 * cases (with 3-4 independent detections per clip) and all seven production injections of the same clips (six
 * back-to-back plus one after a 60 s idle). The production count is eight. The streams share one native spotter
 * (the model is loaded once); the cost is eight decoder states, not eight models.
 *
 * [accept] feeds every due stream and resets a stream **in place** after its hit, so a detection is reported
 * once per stream and the stream keeps decoding. [reset] releases all streams and restarts the stagger;
 * without that, a release would create every stream on the same frame and collapse the diversity it exists for.
 */
class KeywordSpotterWakeWordEngine(
    private val port: KeywordSpotterPort,
    private val keywords: String,
    private val sampleRateHz: Int = 16_000,
    /**
     * Streams fed in parallel. The default of one preserves the single-stream shape for unit harnesses; the
     * production factory passes [PHASE_DIVERSITY_STREAMS].
     */
    private val streams: Int = 1,
    /**
     * Frames between consecutive stream starts. One 20 ms frame gives [PHASE_DIVERSITY_STREAMS] streams every one
     * of the eight 20 ms phases the measured model is sensitive to; the production factory passes
     * [PHASE_DIVERSITY_STRIDE_FRAMES].
     */
    private val streamStrideFrames: Int = 1,
) : WakeWordEngine {
    init {
        require(streams >= 1) { "at least one keyword stream is required" }
        require(streamStrideFrames >= 1) { "stream stride must be at least one frame" }
    }

    private val live = arrayOfNulls<KeywordStreamPort>(streams)
    private var frameIndex = 0L
    private var closed = false

    override fun accept(frame: PcmFrame): List<String> {
        check(!closed)
        require(frame.sampleRateHz == sampleRateHz) {
            "keyword spotter is configured for $sampleRateHz Hz but received ${frame.sampleRateHz} Hz"
        }
        val hits = LinkedHashSet<String>()
        val currentFrame = frameIndex++
        for (index in 0 until streams) {
            // Staggered start: stream `index` opens on frame `index * stride`, so its chunk boundaries are
            // shifted against the first stream's by exactly that many frames.
            if (currentFrame < index.toLong() * streamStrideFrames) continue
            val current = live[index] ?: port.createStream(keywords).also { live[index] = it }
            current.acceptWaveform(frame.samples, sampleRateHz)
            if (!port.isReady(current)) continue
            port.decode(current)
            val hit = port.result(current) ?: continue
            // In place, before reporting: without it the stream still holds the detection and the same keyword
            // would be re-reported on every ready frame.
            port.reset(current)
            hits += hit.keyword
        }
        return hits.toList()
    }

    override fun reset() {
        check(!closed)
        releaseStreams()
        // The next accept opens stream 0; streams 1.. are opened on the following `stride`-spaced frames.
        frameIndex = 0L
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseStreams()
        port.close()
    }

    private fun releaseStreams() {
        for (index in live.indices) {
            live[index]?.release()
            live[index] = null
        }
    }

    companion object {
        /**
         * Eight staggered streams cover every 20 ms phase of the measured model. Four hit all 11 probe cases but
         * still missed one of twelve production injections; eight hit 12/12 in the probe. See the class note.
         */
        const val PHASE_DIVERSITY_STREAMS = 8

        /** One 20 ms frame: eight streams at 0/20/…/140 ms cover all eight 20 ms phases. */
        const val PHASE_DIVERSITY_STRIDE_FRAMES = 1
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
 *   frames recreate the streams (the engine re-staggers them, so a release cannot collapse their phases into one).
 *   A short pause inside one utterance therefore keeps the streams and their decoding context alive. Silence
 *   before any speech never releases, so an idle detector does not churn streams.
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
