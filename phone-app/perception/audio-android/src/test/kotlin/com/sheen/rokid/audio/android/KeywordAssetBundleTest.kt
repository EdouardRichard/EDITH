package com.sheen.rokid.audio.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The generated `kws/` assets never reach the JVM unit tests — a real `KeywordSpotter` needs an
 * `AssetManager` and the native library — so [KeywordAssetBundle] is loaded through the
 * [KeywordAssetReader] seam. The pinned `keywords.txt` bytes are asserted against the tracked template that
 * the `generateKwsModelAssets` task copies into the assets, byte for byte.
 */
class KeywordAssetBundleTest {
    private class FakeAssets(private val contents: Map<String, ByteArray>) : KeywordAssetReader {
        override fun exists(path: String): Boolean = contents.containsKey(path)
        override fun readBytes(path: String): ByteArray = contents.getValue(path)
    }

    private val keywordsBytes: ByteArray by lazy { trackedKeywordsTemplate().readBytes() }

    private fun packagedAssets(): MutableMap<String, ByteArray> = mutableMapOf(
        KeywordAssetBundle.ENCODER to ByteArray(4) { 1 },
        KeywordAssetBundle.DECODER to ByteArray(4) { 2 },
        KeywordAssetBundle.JOINER to ByteArray(4) { 3 },
        KeywordAssetBundle.TOKENS to ByteArray(4) { 4 },
        KeywordAssetBundle.KEYWORDS to keywordsBytes,
    )

    private fun load(assets: Map<String, ByteArray>): KeywordAssetBundle? =
        KeywordAssetBundle.load(FakeAssets(assets))

    @Test fun `load returns null when the local model bundle was not packaged`() {
        assertNull(load(emptyMap()))
    }

    @Test fun `load returns null when any single required asset is missing`() {
        KeywordAssetBundle.REQUIRED_FILES.forEach { missing ->
            val assets = packagedAssets().apply { remove(KeywordAssetBundle.assetPath(missing)) }
            assertNull("expected no bundle without $missing", load(assets))
        }
    }

    @Test fun `load exposes the five asset relative paths and the parsed keyword lines`() {
        val bundle = load(packagedAssets())!!
        assertEquals("kws/encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", bundle.encoder)
        assertEquals("kws/decoder-epoch-13-avg-2-chunk-8-left-64.onnx", bundle.decoder)
        assertEquals("kws/joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx", bundle.joiner)
        assertEquals("kws/tokens.txt", bundle.tokens)
        assertEquals("kws/keywords.txt", bundle.keywordsFile)
        assertEquals(listOf("y ī d í s ī @伊迪丝", "IY1 D AH0 TH @EDITH"), bundle.keywords)
    }

    @Test fun `keywords asset is exactly the pinned tokenisation, lf terminated, no bom and no cr`() {
        assertEquals(46, keywordsBytes.size)
        assertFalse(
            "keywords.txt must not start with a UTF-8 BOM",
            keywordsBytes[0] == 0xEF.toByte() &&
                keywordsBytes[1] == 0xBB.toByte() &&
                keywordsBytes[2] == 0xBF.toByte())
        assertFalse("keywords.txt must not contain CR", keywordsBytes.any { it == '\r'.code.toByte() })
        assertEquals("y ī d í s ī @伊迪丝\nIY1 D AH0 TH @EDITH\n", String(keywordsBytes, Charsets.UTF_8))
        assertEquals(2, keywordsBytes.count { it == '\n'.code.toByte() })
    }

    @Test fun `createStream argument joins the keyword lines with a slash`() {
        val bundle = load(packagedAssets())!!
        assertEquals("y ī d í s ī @伊迪丝/IY1 D AH0 TH @EDITH", bundle.keywordsArgument)
    }

    @Test fun `parsed lines drop a trailing blank line and the carriage return of a crlf file`() {
        val assets = packagedAssets().apply {
            put(KeywordAssetBundle.KEYWORDS, "y ī d í s ī @伊迪丝\r\n\r\n".toByteArray())
        }
        assertEquals(listOf("y ī d í s ī @伊迪丝"), load(assets)!!.keywords)
    }

    /** The tracked template `generateKwsModelAssets` copies into `kws/keywords.txt`; not model data. */
    private fun trackedKeywordsTemplate(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            File(directory, "kws/keywords.txt").takeIf { it.isFile }?.let { return it }
            File(directory, "audio-android/kws/keywords.txt").takeIf { it.isFile }?.let { return it }
            directory = directory.parentFile
        }
        throw AssertionError("kws/keywords.txt not found from ${File("").absolutePath}")
    }
}
