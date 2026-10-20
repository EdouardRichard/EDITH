package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.audio.KeywordHit
import com.sheen.rokid.audio.KeywordSpotterPort
import com.sheen.rokid.audio.android.KeywordAssetBundle
import com.sheen.rokid.audio.android.SherpaKeywordSpotterFactory
import com.sheen.rokid.phone.platform.P3Log
import java.io.File

/**
 * Debug-only keyword-recall probe (F-01, 2026-10-20).
 *
 * The production injector proves whether the *detector* fires, but it cannot tell "the configured keyword lines
 * do not match this recording" from "the native spotter is broken": both show as `P3_WAKE=0`. This probe answers
 * the first question directly - it builds the real `KeywordSpotter` from the packaged assets, feeds a WAV from the
 * host shell, and reports which **labels** fired. It never touches the host, the pipeline or the CXR link, so it can
 * run without a session and its result is independent of the 2x uplink anomaly.
 *
 * Why it exists instead of the androidTest: `:phone-app:app:assembleDebugAndroidTest` cannot resolve the locked
 * `litert-npu-runtime-mediatek` artifact on this machine, so the existing `KeywordSpotterDeviceTest` cannot be built
 * (measured 2026-10-20). This receiver uses the same factory, bundle and feed loop, from the debug source set only.
 *
 * Driven from a host shell:
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_KWS -n com.sheen.rokid.phone/.debug.DebugKwsProbe \
 *     --es path /data/local/tmp/edith-audio/wake-zh.wav \
 *     --es keywords "y ī d í s ī @伊迪丝/IY1 D AH0 TH @EDITH"
 * ```
 *
 * `--es keywords` is optional; absent means the packaged `keywords.txt`. The keyword argument is
 * `/`-separated, exactly the form `KeywordSpotter.createStream` takes. Only labels and counts are logged: the
 * keyword strings are product configuration, not user content, and no audio sample ever reaches logcat.
 */
class DebugKwsProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROBE) return
        val pending = goAsync()
        Thread({
            try {
                probe(context.applicationContext, intent)
            } catch (unexpected: Throwable) {
                P3Log.line("P3_KWS_PROBE state=refused reason=UNEXPECTED:${unexpected.javaClass.simpleName}")
            } finally {
                runCatching { pending.finish() }
            }
        }, THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun probe(context: Context, intent: Intent) {
        val bundle = KeywordAssetBundle.load(context.assets) ?: return refused("NO_BUNDLE")
        val path = intent.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() } ?: return refused("MISSING_PATH")
        val file = File(path)
        if (!file.isFile) return refused("FILE_MISSING")
        val keywords = intent.getStringExtra(EXTRA_KEYWORDS)?.takeIf { it.isNotBlank() }
            ?: bundle.keywordsArgument
        val labelCount = bundle.keywordLabels(keywords)
        val silenceMillis = intent.getLongExtra(EXTRA_SILENCE_MILLIS, 0L).coerceIn(0L, 120_000L)
        val silenceLevel = intent.getIntExtra(EXTRA_SILENCE_LEVEL, 0).coerceIn(0, 30_000)
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return refused("READ_FAILED")
        val loaded = DebugWavPcm.load(bytes)
        if (loaded is DebugWavPcm.Loaded.Refused) return refused(loaded.code)
        loaded as DebugWavPcm.Loaded.Ready

        val port = SherpaKeywordSpotterFactory.create(bundle, context.assets) ?: return refused("NO_SPOTTER")
        val streamCount = intent.getIntExtra(EXTRA_STREAMS, 1).coerceIn(1, 16)
        val streamStride = intent.getIntExtra(EXTRA_STREAM_STRIDE, 1).coerceIn(1, 16)
        val startedNanos = System.nanoTime()
        val streams = arrayOfNulls<com.sheen.rokid.audio.KeywordStreamPort>(streamCount)
        try {
            val hits = mutableListOf<String>()
            var chunks = 0
            var decodes = 0
            var frameIndex = 0

            /**
             * Phase diversity: stream `j` is created at global frame `j` and from then on receives the same frames as
             * stream 0, so every stream sees the same audio with its chunk boundaries shifted by `j` frames. This is
             * the hypothesis under test - a word that misses at one alignment should fire on another stream.
             */
            fun offer(samples: FloatArray) {
                for (index in 0 until streamCount) {
                    if (frameIndex < index * streamStride) continue
                    val stream = streams[index] ?: port.createStream(keywords).also { streams[index] = it }
                    feed(stream, port, samples, onDecode = { decodes++ }, onHit = { hit ->
                        hits += "s$index:${hit.keyword}"
                    })
                }
                frameIndex++
            }

            // Optional quiet prefix: this is how the production stream looks after an idle period, and it isolates
            // "the stream carried unrelated audio before the word" from "the word does not match".
            val silenceChunks = (silenceMillis / CHUNK_MILLIS).toInt()
            repeat(silenceChunks) { offer(quietChunk(silenceLevel)) }
            loaded.chunks.forEach { chunk ->
                chunks++
                offer(chunk.toFloatSamples())
            }
            P3Log.line("P3_KWS_PROBE file=${file.name} chunks=$chunks silenceMs=$silenceMillis " +
                "silenceLevel=$silenceLevel streams=$streamCount stride=$streamStride decodes=$decodes " +
                "labels=$labelCount elapsedMs=${(System.nanoTime() - startedNanos) / 1_000_000} " +
                "hits=${hits.size} observed=${hits.joinToString("|").ifEmpty { "-" }}")
        } finally {
            streams.forEach { runCatching { it?.release() } }
            port.close()
        }
    }

    /** One waveform feed plus the documented decode loop, with the result handed back to the caller. */
    private fun feed(
        stream: com.sheen.rokid.audio.KeywordStreamPort,
        port: KeywordSpotterPort,
        samples: FloatArray,
        onDecode: () -> Unit,
        onHit: (KeywordHit) -> Unit,
    ) {
        stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
        var guard = 0
        while (port.isReady(stream) && guard < MAX_DECODES_PER_CHUNK) {
            port.decode(stream)
            onDecode()
            guard++
            val hit: KeywordHit = port.result(stream) ?: continue
            onHit(hit)
            port.reset(stream)
        }
    }

    private fun refused(code: String) {
        P3Log.line("P3_KWS_PROBE state=refused reason=$code")
    }

    /** The label of each `/`-separated keyword line: the text after `@`. */
    private fun KeywordAssetBundle.keywordLabels(keywords: String): Int =
        keywords.split('/').count { it.isNotBlank() }

    /** A 20 ms prefix chunk: digital zeros, or deterministic noise of +/-[level] LSB so a quiet room can be modelled. */
    private fun quietChunk(level: Int): FloatArray {
        if (level <= 0) return FloatArray(CHUNK_SAMPLES)
        var state = 0x1234567
        return FloatArray(CHUNK_SAMPLES) {
            state = state * 1_103_515_245 + 12_345
            (((state ushr 16) % (2 * level + 1)) - level) / 32768f
        }
    }

    /** PCM16 little-endian bytes to the [-1, 1] floats `acceptWaveform` expects (32768 keeps +32767 < 1). */
    private fun ByteArray.toFloatSamples(): FloatArray = FloatArray(size / 2) { index ->
        val low = this[index * 2].toInt() and 0xFF
        val high = this[index * 2 + 1].toInt()
        ((high shl 8) or low).toShort().toFloat() / 32768f
    }

    companion object {
        const val ACTION_PROBE = "edith.DEBUG_KWS"
        const val EXTRA_PATH = "path"
        const val EXTRA_KEYWORDS = "keywords"
        const val EXTRA_SILENCE_MILLIS = "silenceMillis"
        const val EXTRA_SILENCE_LEVEL = "silenceLevel"
        const val EXTRA_STREAMS = "streams"
        const val EXTRA_STREAM_STRIDE = "streamStride"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHUNK_MILLIS = 20L
        private const val CHUNK_SAMPLES = (SAMPLE_RATE_HZ * CHUNK_MILLIS / 1000).toInt()
        private const val THREAD_NAME = "edith-kws-probe"

        /** A decode loop that cannot end would hang the receiver; sherpa consumes one chunk per decode. */
        private const val MAX_DECODES_PER_CHUNK = 32
    }
}
