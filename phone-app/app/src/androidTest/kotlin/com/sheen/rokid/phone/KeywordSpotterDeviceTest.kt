package com.sheen.rokid.phone

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.audio.KeywordHit
import com.sheen.rokid.audio.KeywordSpotterPort
import com.sheen.rokid.audio.android.KeywordAssetBundle
import com.sheen.rokid.audio.android.SherpaKeywordSpotterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P3.1 Task 10d: the first device proof that the **real** native `KeywordSpotter`, our **packaged** asset
 * bundle and our **config builder** actually detect a keyword.
 *
 * What is proven here: `KeywordAssetBundle.load` finds the five model files inside the **app under test's**
 * APK assets (so the `kws/` assets really merged from `:audio-android` into the `:phone-app` APK),
 * `SherpaKeywordSpotterFactory.create` builds a working binary, and feeding the model's **own** ground-truth
 * audio produces the keywords the model documents — with our own bundled keyword argument (`伊迪丝`/`EDITH`)
 * producing **no** hit on the same audio, which is the control that proves the keywords constrain the search.
 *
 * What is **not** proven here (the honest boundary, repeated in the report):
 * - no microphone capture, no CXR transport, no AudioRecord path is exercised;
 * - no recall figure for `伊迪丝`/`EDITH` — the model ships no audio for them;
 * - no false-accept rate in noise; the negative control is one clean studio clip, not a noise sweep;
 * - the ASR-text wake path (`AsrTextWakeWordMatcher`) that P3.1 actually runs is untouched by this test.
 *
 * Audio is read from the `en_*.wav` clips staged under `/data/local/tmp/edith-kws/` through
 * `UiAutomation.executeShellCommand` (which runs as the `shell` user), so the wavs never enter version
 * control and no storage permission is needed on Android 13. The wavs must be pushed before the run; that is
 * an `Assume` skip, not a failure, so a machine without them does not go red.
 *
 * The AAR's `WaveReader` was probed first: `javap` on the pinned AAR's `classes.jar` shows the class itself
 * exposes **no** public method and the only entry points are `WaveReader$Companion.readWave(...)` /
 * `readWaveFromAsset(...)` (the latter native). That shape is not reachable from Java/Kotlin without
 * reflection, so this test parses the RIFF/fmt/data header itself and **asserts** the format
 * (PCM / 16 kHz / mono / 16-bit) before feeding `acceptWaveform(FloatArray, Int)`.
 */
@RunWith(AndroidJUnit4::class)
class KeywordSpotterDeviceTest {

    private lateinit var bundle: KeywordAssetBundle

    /**
     * Case 1 also asserts the bundle the *app under test* sees, because the whole point of the asset step is
     * that the generated `kws/` directory merged from `:audio-android` into the `:phone-app` APK.
     *
     * The run is pinned to the **phone**: this is P3.1's carrier and, since `D46` (2026-10-10), the delivery target
     * (Redmi K40 / Android 13), and the glasses is a different SoC and a different app. Gradle's
     * `connectedDebugAndroidTest` refuses to run when no device is selected, so rather than let the suite
     * install the phone APK on the glasses and silently `Assume`-skip there, the off-carrier run is skipped by
     * name with the reason stated.
     */
    @Before fun loadTheAppsOwnKwsAssets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val model = android.os.Build.MODEL
        val isPhone = target.packageName == PHONE_PACKAGE
        assumeTrue(
            "this KWS device check is pinned to the phone carrier ($PHONE_PACKAGE); " +
                "current device is model=$model package=${target.packageName}",
            isPhone,
        )
        val loaded = KeywordAssetBundle.load(target.assets)
        assumeTrue(
            "no kws/ assets in the app under test: the local model was not packaged by " +
                ":audio-android:generateKwsModelAssets",
            loaded != null,
        )
        bundle = requireNotNull(loaded)
    }

    @Test fun bundledAssetsContainTheModelFilesAndTheConfiguredKeywords() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        // `AssetManager.list` returns the **children names** of a directory, so `kws` lists bare file names.
        val listing = assets.list(KeywordAssetBundle.ASSET_DIR)?.toSet().orEmpty()
        KeywordAssetBundle.REQUIRED_FILES.forEach { required ->
            assertTrue("the app APK must carry kws/$required: $listing", required in listing)
        }
        assertEquals(
            "the packaged keywords.txt must be our own two keyword lines",
            listOf("y ī d í s ī @伊迪丝", "IY1 D AH0 TH @EDITH"),
            bundle.keywords,
        )
        println("P3_KWS_DEVICE case=assets files=${listing.sorted()} " +
            "keywords=${bundle.keywords.size} argument=${bundle.keywordsArgument}")
    }

    /** `en_0.wav` against the model's own demo lines: must fire `LIGHT_UP`. */
    @Test fun demoKeywordLinesDetectLightUpInEn0() {
        runCase(
            caseId = "positive_en0",
            keywords = DEMO_KEYWORDS,
            audio = readWav("en_0.wav"),
            expected = "LIGHT_UP",
        )
    }

    /** `en_1.wav` against the model's own demo lines: must fire `LOVELY_CHILD`. */
    @Test fun demoKeywordLinesDetectLovelyChildInEn1() {
        runCase(
            caseId = "positive_en1",
            keywords = DEMO_KEYWORDS,
            audio = readWav("en_1.wav"),
            expected = "LOVELY_CHILD",
        )
    }

    /**
     * The control that makes the positives mean something: the **same** audio that fires `LIGHT_UP` above must
     * fire nothing when the stream is built with our bundled keywords (`伊迪丝`/`EDITH`). If the model fired
     * regardless of the keyword argument, this case would report `LIGHT_UP` too.
     */
    @Test fun ourOwnBundledKeywordsDoNotDetectLightUpInEn0() {
        runCase(
            caseId = "negative_en0",
            keywords = bundle.keywordsArgument,
            audio = readWav("en_0.wav"),
            expected = null,
        )
    }

    /**
     * @param expected the single keyword this case must produce, or `null` for the negative control (no hit at
     *   all). The stream is reset in place after every hit and keeps decoding, so a pathological repeated
     *   detection is still visible in `hits=`.
     */
    private fun runCase(caseId: String, keywords: String, audio: WavAudio, expected: String?) {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val port = SherpaKeywordSpotterFactory.create(bundle, assets)
        assertNotNull("a packaged bundle plus an AssetManager must build a real KeywordSpotter", port)
        if (port == null) return
        try {
            val stream = port.createStream(keywords)
            try {
                val hits = mutableListOf<String>()
                var readyDecodes = 0
                var chunks = 0
                var offset = 0
                while (offset < audio.samples.size) {
                    val end = minOf(offset + CHUNK_SAMPLES, audio.samples.size)
                    stream.acceptWaveform(audio.samples.copyOfRange(offset, end), audio.sampleRateHz)
                    offset = end
                    chunks++
                    // The documented loop: decode only while the stream reports enough audio, and collect every
                    // result. Resetting in place after a hit keeps the stream usable for the rest of the clip.
                    while (port.isReady(stream)) {
                        port.decode(stream)
                        readyDecodes++
                        val hit: KeywordHit = port.result(stream) ?: continue
                        hits += hit.keyword
                        port.reset(stream)
                    }
                }
                if (expected == null) {
                    assertEquals("$caseId: the packaged keywords must not detect anything in this audio",
                        emptyList<String>(), hits.toList())
                } else {
                    assertTrue("$caseId: expected $expected, observed $hits " +
                        "(chunks=$chunks readyDecodes=$readyDecodes streamMs=${audio.streamMillis})",
                        expected in hits)
                }
                println(
                    "P3_KWS_DEVICE case=$caseId keywords=${describe(keywords)} " +
                        "audioSamples=${audio.samples.size} sampleRateHz=${audio.sampleRateHz} " +
                        "streamMs=${audio.streamMillis} chunks=$chunks readyDecodes=$readyDecodes " +
                        "hits=${if (hits.isEmpty()) 0 else hits.size} observed=${hits.toList()}",
                )
            } finally {
                stream.release()
            }
        } finally {
            port.close()
        }
    }

    /** Multi-line keyword arguments are one line per keyword; collapse them so the evidence line stays one line. */
    private fun describe(keywords: String) = keywords.replace("/", "|")

    // --- reading the ground-truth audio off the device, as the shell user -----------------------------------

    /**
     * @return the parsed clip, or skips the test when the wav is not on the device (the wavs are deliberately
     *   not in version control, so their absence is an environment gap and not a product failure).
     */
    private fun readWav(fileName: String): WavAudio {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = "$DEVICE_DIR/$fileName"
        val bytes = instrumentation.uiAutomation
            .executeShellCommand("cat $target")
            .use { descriptor -> readPipe(descriptor) }
        val exists = instrumentation.uiAutomation
            .executeShellCommand("ls -l $target")
            .use { descriptor -> String(readPipe(descriptor), Charsets.UTF_8).trim() }
        assumeTrue(
            "ground-truth audio $target is not on the device (push it with `adb -s <phone> push " +
                "<model>/test_wavs/$fileName $DEVICE_DIR/`): [$exists]",
            bytes.isNotEmpty(),
        )
        return WavAudio.parse(fileName, bytes)
    }

    private fun readPipe(descriptor: ParcelFileDescriptor): ByteArray {
        val sink = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
            }
        }
        return sink.toByteArray()
    }

    /** Raw 16-bit little-endian mono PCM plus the rate its header declares. */
    internal class WavAudio(val samples: FloatArray, val sampleRateHz: Int) {
        val streamMillis: Int get() = (samples.size * 1000L / sampleRateHz).toInt()

        companion object {
            /**
             * Walks the RIFF chunks (skipping an odd-sized chunk's pad byte) instead of trusting fixed offsets,
             * then **asserts** the shape the spotter is configured for before any sample is fed.
             */
            fun parse(fileName: String, bytes: ByteArray): WavAudio {
                check(bytes.size >= 12) { "$fileName is too short to be a RIFF/WAVE file: ${bytes.size} B" }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                check(ascii(buffer, 0) == "RIFF") { "$fileName is not RIFF: ${ascii(buffer, 0)}" }
                check(ascii(buffer, 8) == "WAVE") { "$fileName is not WAVE: ${ascii(buffer, 8)}" }

                var audioFormat = -1
                var channels = -1
                var sampleRateHz = -1
                var bitsPerSample = -1
                var dataStart = -1
                var dataLength = -1
                var position = 12
                while (position + 8 <= bytes.size) {
                    val id = ascii(buffer, position)
                    val size = buffer.getInt(position + 4)
                    check(size >= 0) { "$fileName has a negative chunk size at $position" }
                    val body = position + 8
                    when (id) {
                        "fmt " -> {
                            check(size >= 16 && body + 16 <= bytes.size) { "$fileName has a truncated fmt chunk" }
                            audioFormat = buffer.getShort(body).toInt() and 0xFFFF
                            channels = buffer.getShort(body + 2).toInt() and 0xFFFF
                            sampleRateHz = buffer.getInt(body + 4)
                            bitsPerSample = buffer.getShort(body + 14).toInt() and 0xFFFF
                        }
                        "data" -> {
                            check(body + size <= bytes.size) { "$fileName has a truncated data chunk" }
                            dataStart = body
                            dataLength = size
                        }
                    }
                    position = body + size + (size and 1)
                }

                check(audioFormat == 1) { "$fileName must be uncompressed PCM, audioFormat=$audioFormat" }
                check(channels == 1) { "$fileName must be mono, channels=$channels" }
                check(sampleRateHz == EXPECTED_SAMPLE_RATE_HZ) {
                    "$fileName must be $EXPECTED_SAMPLE_RATE_HZ Hz, header says $sampleRateHz Hz"
                }
                check(bitsPerSample == 16) { "$fileName must be 16-bit, header says $bitsPerSample" }
                check(dataStart >= 0) { "$fileName has no data chunk" }

                // The AAR's `OnlineStream.acceptWaveform` takes samples in [-1, 1]; 32768 keeps +32767 < 1.0.
                val samples = FloatArray(dataLength / 2) { index ->
                    val raw = bytes[dataStart + index * 2].toInt() and 0xFF
                    val high = bytes[dataStart + index * 2 + 1].toInt()
                    ((high shl 8) or raw).toShort().toFloat() / 32768f
                }
                return WavAudio(samples, sampleRateHz)
            }

            private fun ascii(buffer: ByteBuffer, offset: Int): String =
                String(ByteArray(4) { buffer.get(offset + it) }, Charsets.US_ASCII)
        }
    }

    private companion object {
        /**
         * Verbatim from the model's own `test_wavs/keywords.txt` (ASCII only, so no encoding risk). The file
         * carries ten keyword lines; only these two have ground-truth audio, and a longer argument changes the
         * search problem, so the two demo lines are used exactly as the model's test recipe does.
         */
        const val DEMO_KEYWORDS =
            "L AY1 T AH1 P @LIGHT_UP/L AH1 V L IY0 CH AY1 L D @LOVELY_CHILD"

        /** Where the runner pushes the model's ground-truth clips; never in version control. */
        const val DEVICE_DIR = "/data/local/tmp/edith-kws"

        /** The debugging carrier this check is pinned to; the glasses is a different app and SoC. */
        const val PHONE_PACKAGE = "com.sheen.rokid.phone"

        const val EXPECTED_SAMPLE_RATE_HZ = 16_000

        /** 100 ms at 16 kHz: the feed chunk, not the model's internal chunk-8 frame shift. */
        const val CHUNK_SAMPLES = 1_600
    }
}
