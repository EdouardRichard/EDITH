package com.sheen.rokid.audio.android

import android.content.res.AssetManager
import java.io.IOException

/**
 * Read-only view of the generated `kws/` assets.
 *
 * A seam only because the JVM unit tests cannot build a real [AssetManager] (and must never load the native
 * library); production always goes through [KeywordAssetBundle.load].
 */
internal interface KeywordAssetReader {
    /** `true` when the asset exists; must not throw for a missing asset. */
    fun exists(path: String): Boolean

    /** The whole asset; only called for paths [exists] accepted. */
    fun readBytes(path: String): ByteArray
}

/**
 * The KWS model as it is packaged into `assets/kws/`.
 *
 * The loading shape is fixed by the pinned AAR: `javap -p` on `libs/sherpa-onnx-1.13.8.aar` shows the only
 * public constructor is `KeywordSpotter(AssetManager, KeywordSpotterConfig)` and that the file-based
 * `newFromFile(KeywordSpotterConfig)` is **private native**, so the model must be read through the asset
 * manager with asset-relative paths. The five paths below are the ones the `KeywordSpotterConfig` receives.
 *
 * They are **strings, not `java.nio.file.Path`**: an asset path is always `/`-separated, and `Path.toString()`
 * rewrites the separator of the host (`kws\encoder…` on a Windows checkout), which is exactly the kind of
 * platform-shaped guess the native asset lookup must not receive.
 *
 * This type does **no native work**: it only answers "are the assets there, and what are their paths" plus the
 * parsed keyword lines. Verification of the *bytes* happens in the build (`generateKwsModelAssets` fails on a
 * digest mismatch) and in `KeywordAssetBundleTest`.
 *
 * `en.phone` is packaged next to these five files (it is on the approved file list) but it is **offline
 * tokenisation only**: nothing at runtime reads it, so it is not part of [REQUIRED_FILES] and no path here
 * refers to it.
 *
 * The bundle is optional by design: the model lives outside version control under the git-ignored
 * `.local/p3-kws-model/`, so [load] returns `null` on a fresh clone and the caller reports `KWS_MODEL_MISSING`
 * instead of starting a wake path that cannot work.
 */
data class KeywordAssetBundle(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val keywordsFile: String,
    val keywords: List<String>,
) {
    /**
     * The argument `KeywordSpotter.createStream` takes: the keywords-file lines joined with `/` (the model's
     * own test recipe uses the same form).
     */
    val keywordsArgument: String get() = keywords.joinToString("/")

    companion object {
        /** Asset directory the generated source dir supplies. */
        const val ASSET_DIR = "kws"

        const val ENCODER_FILE = "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
        const val DECODER_FILE = "decoder-epoch-13-avg-2-chunk-8-left-64.onnx"
        const val JOINER_FILE = "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val KEYWORDS_FILE = "keywords.txt"

        /** Asset-relative paths, in the order the config consumes them. */
        const val ENCODER = "$ASSET_DIR/$ENCODER_FILE"
        const val DECODER = "$ASSET_DIR/$DECODER_FILE"
        const val JOINER = "$ASSET_DIR/$JOINER_FILE"
        const val TOKENS = "$ASSET_DIR/$TOKENS_FILE"
        const val KEYWORDS = "$ASSET_DIR/$KEYWORDS_FILE"

        /** Every asset the model needs; any missing one means "not packaged". */
        val REQUIRED_FILES = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, KEYWORDS_FILE)

        fun assetPath(fileName: String): String = "$ASSET_DIR/$fileName"

        /**
         * @return the bundle, or `null` when the local model was not packaged (fresh clone, or the model files
         *   absent). Never throws for missing assets and never touches the native library.
         */
        fun load(assets: AssetManager): KeywordAssetBundle? = load(AssetManagerKeywordAssetReader(assets))

        internal fun load(reader: KeywordAssetReader): KeywordAssetBundle? {
            if (REQUIRED_FILES.any { !reader.exists(assetPath(it)) }) return null
            return KeywordAssetBundle(
                encoder = ENCODER,
                decoder = DECODER,
                joiner = JOINER,
                tokens = TOKENS,
                keywordsFile = KEYWORDS,
                keywords = parseKeywords(reader.readBytes(KEYWORDS)),
            )
        }

        /**
         * `keywords.txt` is UTF-8 with `\n` line endings and no BOM; drop only the CR of a CRLF file and blank
         * lines so a stray blank does not become an unparseable keyword. Anything else is a build-time problem
         * (`keywords.txt` is asserted byte-for-byte against the pinned tokenisation).
         */
        private fun parseKeywords(bytes: ByteArray): List<String> =
            String(bytes, Charsets.UTF_8).split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
    }
}

/** Opens only the asset listing once; a missing `kws/` directory simply means "not packaged". */
private class AssetManagerKeywordAssetReader(private val assets: AssetManager) : KeywordAssetReader {
    private val listing: Set<String> by lazy {
        try {
            assets.list(KeywordAssetBundle.ASSET_DIR)?.toSet() ?: emptySet()
        } catch (missing: IOException) {
            emptySet()
        }
    }

    override fun exists(path: String): Boolean {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (directory != KeywordAssetBundle.ASSET_DIR) return false
        return path.substringAfterLast('/') in listing
    }

    override fun readBytes(path: String): ByteArray = assets.open(path).use { it.readBytes() }
}
