package edith.audio.android

import edith.audio.*
import edith.contracts.*
import edith.plugin.SpeakerWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface RecognizerPort {
    fun accept(samples: FloatArray, sampleRateHz: Int): String
    fun finish(): String
    fun reset()
    fun release()
}

internal interface VadPort {
    fun accept(samples: FloatArray, sampleRateHz: Int): VadDecision
    fun reset()
    fun release()
}

internal interface EmbeddingPort {
    fun extract(samples: FloatArray, sampleRateHz: Int): FloatArray?
    fun release()
}

class SherpaEmbeddingExtractor internal constructor(private val native: EmbeddingPort,
    private val verified: VerifiedPcm) : EmbeddingExtractor, AutoCloseable {
    private var closed = false
    init { require(verified.format == AudioFormat.Pcm(16000, 1, 16)) }

    @Synchronized override fun extract(window: SpeakerWindow): FloatArray? {
        check(!closed)
        val segment = window.segment
        val count = window.range.endExclusive - window.range.first
        if (segment.format != verified.format || count !in 1..320_000 || segment.pcm.size.toLong() != count * 2 ||
            segment.envelope.endNanos - segment.envelope.startNanos != count * 62_500L) return null
        val bytes = ByteBuffer.wrap(segment.pcm).order(
            if (verified.encoding == PcmEncoding.SIGNED_LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val samples = FloatArray(count.toInt()) { bytes.short.toFloat() / 32768f }
        try {
            val vector = native.extract(samples, 16000) ?: return null
            if (vector.isEmpty() || vector.any { !it.isFinite() } || vector.all { it == 0f }) {
                vector.fill(0f)
                return null
            }
            return vector
        } finally { samples.fill(0f) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        native.release()
    }
}

class SherpaStreamingAsrEngine internal constructor(private val native: RecognizerPort) : StreamingAsrEngine {
    private var closed = false
    private var partial: String? = null
    private var final: AsrHypothesis? = null

    @Synchronized override fun accept(frame: PcmFrame): List<AsrHypothesis> {
        check(!closed && final == null)
        validateFrame(frame)
        val text = native.accept(frame.samples, frame.sampleRateHz)
        if (text.isBlank() || text == partial) return emptyList()
        partial = text
        return listOf(AsrHypothesis(text, false))
    }

    @Synchronized override fun finish(): AsrHypothesis {
        check(!closed)
        return final ?: AsrHypothesis(native.finish(), true).also { final = it }
    }

    @Synchronized override fun reset() {
        check(!closed)
        native.reset()
        partial = null; final = null
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        partial = null; final = null
        native.release()
    }
}

class SherpaVadEngine internal constructor(private val native: VadPort) : VadEngine {
    private var closed = false
    @Synchronized override fun accept(frame: PcmFrame): VadDecision {
        check(!closed)
        validateFrame(frame)
        return native.accept(frame.samples, frame.sampleRateHz)
    }
    @Synchronized override fun reset() { check(!closed); native.reset() }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        native.release()
    }
}

private fun validateFrame(frame: PcmFrame) {
    require(frame.sampleRateHz == 16000 && frame.samples.isNotEmpty() && frame.samples.size <= 640)
    require(frame.range.endExclusive - frame.range.first == frame.samples.size.toLong())
    require(frame.samples.all { it.isFinite() && it in -1f..1f })
}
