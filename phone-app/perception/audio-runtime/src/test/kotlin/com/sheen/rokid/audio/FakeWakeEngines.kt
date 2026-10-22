package com.sheen.rokid.audio

import com.sheen.rokid.contracts.SampleRange

/**
 * Test doubles for the wake layer. Kept out of the `*Test.kt` files so that the detector tests and the
 * engine tests share one definition, mirroring the `FakeSpeechEngines.kt` convention.
 */
internal class RecordingWakeWordEngine : WakeWordEngine {
    var words: List<String> = emptyList()
    var failure: RuntimeException? = null
    var resetFailure: RuntimeException? = null
    val accepted = mutableListOf<SampleRange>()
    var resets = 0
    var closes = 0
    override fun accept(frame: PcmFrame): List<String> {
        failure?.let { throw it }
        accepted += frame.range
        return words
    }
    override fun reset() { resets++; resetFailure?.let { throw it } }
    override fun close() { closes++ }
}

/** Fake [KeywordSpotterPort]; it makes no claim about the native sherpa-onnx library. */internal class FakeKeywordSpotterPort : KeywordSpotterPort {
    val streams = mutableListOf<FakeKeywordStream>()
    var ready = true
    var hit: KeywordHit? = null
    var creates = 0
    var decodes = 0
    var resets = 0
    var closes = 0

    /**
     * When set, [result] answers a hit only for streams whose creation index is in the set. This models the
     * measured phase dependence: the same audio produces a hit on one stream alignment and nothing on another.
     */
    var hittingCreations: Set<Int>? = null
    private var nextCreation = 0
    override fun createStream(keywords: String): KeywordStreamPort {
        creates++
        return FakeKeywordStream(keywords, nextCreation++).also { streams += it }
    }
    override fun decode(stream: KeywordStreamPort) { decodes++ }
    override fun isReady(stream: KeywordStreamPort) = ready
    override fun result(stream: KeywordStreamPort): KeywordHit? {
        val allowed = hittingCreations ?: return hit
        return hit?.takeIf { (stream as FakeKeywordStream).creation in allowed }
    }
    override fun reset(stream: KeywordStreamPort) { resets++ }
    override fun close() { closes++ }
}

internal class FakeKeywordStream(val keywords: String, val creation: Int = 0) : KeywordStreamPort {
    var accepts = 0
    var sampleRateHz = 0
    var lastSamples: FloatArray? = null
    var releases = 0
    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        accepts++
        this.sampleRateHz = sampleRateHz
        lastSamples = samples
    }
    override fun release() { releases++ }
}

/**
 * A scripted KWS stand-in whose recall depends on the utterance's **opening** frames: it can report [word] only
 * once it has been fed every range in [onsetRanges] — the frames that physically carry the word. Frames outside
 * the onset are accepted and ignored, the way a real spotter decodes a frame without producing a hit.
 *
 * This is the D11 shape. A real keyword spotter only sees the word if the frames that contain it reach it, so an
 * engine that models that dependence is the honest witness for "the detector discarded the onset": if the
 * detector withholds the pre-onset frames, this engine can never fire, however good the model is.
 */
internal class OnsetGatedWakeWordEngine(
    private val word: String,
    private val onsetRanges: Set<SampleRange>,
) : WakeWordEngine {
    val accepted = mutableListOf<SampleRange>()
    private val onsetSeen = mutableSetOf<SampleRange>()
    private var reported = false
    var resets = 0
    var closes = 0
    override fun accept(frame: PcmFrame): List<String> {
        accepted += frame.range
        if (frame.range in onsetRanges) onsetSeen += frame.range
        if (reported || onsetSeen.size < onsetRanges.size) return emptyList()
        reported = true
        return listOf(word)
    }
    override fun reset() { resets++; onsetSeen.clear(); reported = false }
    override fun close() { closes++ }
}
