package com.sheen.rokid.glasses.display

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24 S4 Task 19 (spec §3.4/§5.6): the four official GIFs, byte-for-byte.
 *
 * The decoded official APK is the only source. Copying a GIF and silently losing a frame, or substituting a different
 * animation, must fail in CI rather than only be visible on a worn panel; the hashes below are the decoded files'
 * SHA-256. The intrinsic heights/widths are 40x20 and 72x72 - the official layout scales them into its 26x13 and 22x22
 * `ImageView` slots, which is why the view sizes are pinned in [GlassesPanelSpec] rather than derived from the GIF.
 */
class OfficialGifAssetsTest {

    private companion object {
        /** name, bytes, sha256, intrinsic width x height (from the GIF header). */
        val EXPECTED = mapOf(
            "gif_loading.gif" to Asset(5_584, "F62BFFB56002008A8C78A46E5C6757821A8D4867CBF5D3D3391D26C6D3FFCAE0", 40, 20),
            "ai_tts_thinking.gif" to Asset(30_223, "DBA1B82A89AF6DAA4E11BE7AD102C1C4C47CC66CEB7D34BD1CE3A381B36EE9E5", 72, 72),
            "ai_agent_tool_start.gif" to Asset(102_445, "08821C5E5F9DCBF0C38F301BC5414A84F8082F323C0911EB75ABA91F68B1528A", 72, 72),
            "ai_agent_tool_end.gif" to Asset(27_194, "6E4EBA8DF59C83EBD2B10E373D2F51DCB3D90233419D337DFAA7E35327F414F3", 72, 72),
        )
    }

    private data class Asset(val bytes: Long, val sha256: String, val width: Int, val height: Int)

    @Test
    fun theFourOfficialGifsArePresentByteForByte() {
        val dir = drawableDir()
        val actual = dir.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
        EXPECTED.forEach { (name, expected) ->
            val file = actual[name] ?: throw AssertionError("missing official GIF $name in ${dir.absolutePath}")
            assertEquals("$name bytes", expected.bytes, file.length())
            assertEquals("$name SHA-256", expected.sha256, sha256(file))
            val (width, height) = gifIntrinsicSize(file)
            assertEquals("$name intrinsic width", expected.width, width)
            assertEquals("$name intrinsic height", expected.height, height)
        }
    }

    @Test
    fun theViewSlotsAreTheOfficialOnesNotTheGifIntrinsicSizes() {
        assertEquals("the ASR '…' slot is 26x13 px", 26, GlassesPanelSpec.ASR_GIF_WIDTH_PX)
        assertEquals(13, GlassesPanelSpec.ASR_GIF_HEIGHT_PX)
        assertEquals("the thinking GIF slot is 22x22 px", 22, GlassesPanelSpec.THINKING_ICON_PX)
        assertEquals("the tool GIF slot is 22x22 px", 22, GlassesPanelSpec.TOOL_ICON_PX)
        assertTrue("the ASR GIF is scaled down from its 40x20 intrinsic size",
            GlassesPanelSpec.ASR_GIF_WIDTH_PX < 40)
        assertTrue("the thinking/tool GIFs are scaled down from 72x72", GlassesPanelSpec.THINKING_ICON_PX < 72)
    }

    private fun drawableDir(): File {
        val candidates = listOf(
            File("src/main/res/drawable"),
            File("glasses-app/app/src/main/res/drawable"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "official GIFs are missing; looked in ${candidates.joinToString { it.absolutePath }}")
    }

    private fun gifIntrinsicSize(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        assertTrue("$file is too short to be a GIF", bytes.size >= 10)
        val signature = String(bytes, 0, 6)
        assertTrue("$file has no GIF signature (was $signature)", signature == "GIF87a" || signature == "GIF89a")
        // The header is the signature + logical screen width/height as little-endian uint16.
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return width to height
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it.toInt() and 255) }
    }
}
