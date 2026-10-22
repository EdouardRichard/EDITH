package com.sheen.rokid.audio.android

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.sheen.rokid.audio.KeywordHit
import com.sheen.rokid.audio.KeywordSpotterPort
import com.sheen.rokid.audio.KeywordStreamPort

/**
 * Everything the native keyword spotter needs, as **asset-relative path strings** (the pinned AAR's only
 * public constructor is `KeywordSpotter(AssetManager, KeywordSpotterConfig)`, and sherpa resolves its model
 * paths through the asset manager, where the separator is always `/`).
 *
 * Built from a packaged [KeywordAssetBundle] through [toRequest]; the field values below are the wiring values
 * the plan pins and are asserted one by one in `KeywordSpotterConfigTest`.
 */
data class KeywordSpotterRequest(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val keywordsFile: String,
    val maxActivePaths: Int = 4,
    val keywordsScore: Float = 1.5f,
    val keywordsThreshold: Float = 0.25f,
    val numTrailingBlanks: Int = 1,
)

/**
 * The request form of a packaged bundle: the five asset-relative paths, with the pinned tuning defaults
 * ([KeywordSpotterRequest]'s own defaults).
 */
internal fun KeywordAssetBundle.toRequest(): KeywordSpotterRequest = KeywordSpotterRequest(
    encoder = encoder,
    decoder = decoder,
    joiner = joiner,
    tokens = tokens,
    keywordsFile = keywordsFile,
)

/**
 * Binds the pinned AAR's `KeywordSpotter` to the platform-independent [KeywordSpotterPort].
 *
 * Ownership mirrors `NativeOwnership.kt`: one native spotter per port, closed exactly once, and a stream
 * wrapper that releases its `OnlineStream` idempotently. The JVM unit tests never build a real
 * `KeywordSpotter` (no `AssetManager`), so the native path is wired and its **configuration and asset
 * packaging** are verified; the native detection behaviour itself is not verified here.
 */
internal class OwnedKeywordSpotterPort(private val spotter: KeywordSpotter) : KeywordSpotterPort {
    private var closed = false

    private var frames = 0L
    private var readyFrames = 0L
    private var decodes = 0L
    private var hits = 0L

    override fun createStream(keywords: String): KeywordStreamPort {
        check(!closed)
        return OwnedKeywordStream(spotter.createStream(keywords))
    }

    override fun decode(stream: KeywordStreamPort) {
        check(!closed)
        decodes++
        spotter.decode(native(stream))
    }

    override fun isReady(stream: KeywordStreamPort): Boolean {
        check(!closed)
        val ready = spotter.isReady(native(stream))
        frames++
        if (ready) readyFrames++
        if (frames % 2000L == 0L) {
            android.util.Log.i("EdithKws",
                "P3_KWS frames=$frames ready=$readyFrames decoded=$decodes hits=$hits")
        }
        return ready
    }

    /** `null` when the native result carries no keyword — sherpa reports "nothing detected" as an empty string. */
    override fun result(stream: KeywordStreamPort): KeywordHit? {
        check(!closed)
        val hit = spotter.getResult(native(stream))
        if (hit.keyword.isEmpty()) return null
        hits++
        android.util.Log.i("EdithKws", "P3_KWS hit=${hit.keyword} tokens=${hit.tokens.size}")
        return KeywordHit(hit.keyword, hit.tokens.toList(), hit.timestamps)
    }

    override fun reset(stream: KeywordStreamPort) {
        check(!closed)
        spotter.reset(native(stream))
    }

    override fun close() {
        if (closed) return
        closed = true
        spotter.release()
    }

    private fun native(stream: KeywordStreamPort): OnlineStream =
        (stream as? OwnedKeywordStream)?.stream
            ?: throw IllegalArgumentException("stream does not belong to this keyword spotter port")
}

private class OwnedKeywordStream(val stream: OnlineStream) : KeywordStreamPort {
    private var released = false
    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        check(!released)
        stream.acceptWaveform(samples, sampleRateHz)
    }
    override fun release() {
        if (released) return
        released = true
        stream.release()
    }
}

/**
 * Nullable factory for the native sherpa-onnx `KeywordSpotter`.
 *
 * **Asset-only by measurement.** `javap -p` on the pinned `libs/sherpa-onnx-1.13.8.aar` (SHA-256
 * `633C2432…3BD96`) shows exactly one public constructor, `KeywordSpotter(AssetManager,
 * KeywordSpotterConfig)`; the file-based `private final native long newFromFile(KeywordSpotterConfig)` exists
 * in the class and its JNI symbol `Java_com_k2fsa_sherpa_onnx_KeywordSpotter_newFromFile` exists in
 * `libsherpa-onnx-jni.so`, but it is not reachable from Kotlin without reflection — so the model ships as
 * `assets/kws/` (see [KeywordAssetBundle]).
 *
 * **The model is optional.** A user-approved KWS bundle is downloaded outside version control to
 * `.local/p3-kws-model/`; `generateKwsModelAssets` packages it into `assets/kws/` when present and packages
 * nothing when absent, so a fresh clone still builds and tests.
 *
 * @param bundle the packaged assets, or `null` when the local model was not packaged.
 * @return `null` when [bundle] is `null` — the caller reports `KWS_MODEL_MISSING` instead of starting a wake
 *   path that cannot work. A non-null bundle without an [AssetManager] is a caller bug and is rejected loudly.
 */
object SherpaKeywordSpotterFactory {
    fun create(bundle: KeywordAssetBundle?, assets: AssetManager?): KeywordSpotterPort? {
        if (bundle == null) return null
        val manager = requireNotNull(assets) { "a KeywordSpotter requires an AssetManager" }
        return OwnedKeywordSpotterPort(KeywordSpotter(manager, keywordSpotterConfig(bundle.toRequest())))
    }
}

/** Mirrors `SherpaNativeConfiguration`'s style; every value is asserted field-by-field in the unit test. */
internal fun keywordSpotterConfig(request: KeywordSpotterRequest): KeywordSpotterConfig = KeywordSpotterConfig(
    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(encoder = request.encoder,
            decoder = request.decoder, joiner = request.joiner),
        tokens = request.tokens),
    maxActivePaths = request.maxActivePaths,
    keywordsFile = request.keywordsFile,
    keywordsScore = request.keywordsScore,
    keywordsThreshold = request.keywordsThreshold,
    numTrailingBlanks = request.numTrailingBlanks,
)
