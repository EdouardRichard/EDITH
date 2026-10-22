package com.sheen.rokid.audio.android

import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.VerifiedPcm

/** Candidate lifecycle only; creation does not select a production engine. */
class SherpaEngineCandidate internal constructor(
    val asr: SherpaStreamingAsrEngine,
    val vad: SherpaVadEngine,
    val embedding: SherpaEmbeddingExtractor
) : AutoCloseable {
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        closeAll(listOf({ embedding.close() }, { vad.close() }, { asr.close() }))
    }
}

object SherpaEngineFactory {
    fun createCandidate(bundle: ValidatedModelBundle, verified: VerifiedPcm): SherpaEngineCandidate {
        require(verified.format == AudioFormat.Pcm(16000, 1, 16))
        val cleanup = mutableListOf<() -> Unit>()
        try {
            val asr = SherpaStreamingAsrEngine(OwnedRecognizerPort(SherpaRecognizerOwner(
                OnlineRecognizer(config = SherpaNativeConfiguration.asr(bundle)))))
            cleanup += { asr.close() }
            val vad = SherpaVadEngine(OwnedVadPort(SherpaVadOwner(
                Vad(config = SherpaNativeConfiguration.vad(bundle)))))
            cleanup += { vad.close() }
            val paths = bundle.validatedPaths()
            val manifest = bundle.manifest
            val embedding = SherpaEmbeddingExtractor(OwnedEmbeddingPort(SherpaEmbeddingOwner(
                SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
                    model = paths.getValue("speaker").toString(), numThreads = manifest.threadCount,
                    provider = manifest.provider, debug = false)))), verified)
            cleanup += { embedding.close() }
            return SherpaEngineCandidate(asr, vad, embedding)
        } catch (failure: Throwable) {
            try { closeAll(cleanup.asReversed()) } catch (release: Throwable) {
                if (release !== failure) failure.addSuppressed(release)
            }
            throw failure
        }
    }
}

private class SherpaRecognizerOwner(private val recognizer: OnlineRecognizer) : RecognizerOwner {
    private var closed = false
    override fun createStream(): RecognizerStream {
        check(!closed)
        return SherpaRecognizerStream(recognizer, recognizer.createStream(""))
    }
    override fun release() {
        if (closed) return
        closed = true
        recognizer.release()
    }
}

private class SherpaRecognizerStream(private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream) : RecognizerStream {
    private var closed = false
    override fun accept(samples: FloatArray, sampleRateHz: Int) {
        check(!closed)
        stream.acceptWaveform(samples, sampleRateHz)
    }
    override fun finish() { check(!closed); stream.inputFinished() }
    override fun read(): String {
        check(!closed)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text
    }
    override fun release() {
        if (closed) return
        closed = true
        stream.release()
    }
}

/**
 * Internal rather than private since Task 11b: `SherpaVadFactory` builds a VAD-only engine from an already
 * validated bundle through this same owner, so the wake path's VAD is created, reset and released exactly like
 * the pipeline's. Nothing else about the class changes.
 */
internal class SherpaVadOwner(private val vad: Vad) : VadOwner {
    private var closed = false
    override fun accept(samples: FloatArray) { check(!closed); vad.acceptWaveform(samples) }
    override fun speechDetected(): Boolean { check(!closed); return vad.isSpeechDetected() }
    override fun empty(): Boolean { check(!closed); return vad.empty() }
    override fun pop() {
        check(!closed)
        try { vad.front().samples.fill(0f) } finally { vad.pop() }
    }
    override fun reset() {
        check(!closed)
        // reset resets detection; clear separately releases the completed-segment queue.
        vad.flush()
        while (!vad.empty()) pop()
        vad.clear()
        vad.reset()
    }
    override fun release() {
        if (closed) return
        closed = true
        vad.release()
    }
}

private class SherpaEmbeddingOwner(private val extractor: SpeakerEmbeddingExtractor) : EmbeddingOwner {
    private var closed = false
    override fun createStream(): EmbeddingStream {
        check(!closed)
        val stream = extractor.createStream()
        return object : EmbeddingStream {
            private var released = false
            override fun accept(samples: FloatArray, sampleRateHz: Int) {
                check(!released); stream.acceptWaveform(samples, sampleRateHz)
            }
            override fun finish() { check(!released); stream.inputFinished() }
            override fun ready(): Boolean { check(!released); return extractor.isReady(stream) }
            override fun compute(): FloatArray { check(!released); return extractor.compute(stream) }
            override fun release() {
                if (released) return
                released = true
                stream.release()
            }
        }
    }
    override fun release() {
        if (closed) return
        closed = true
        extractor.release()
    }
}

private fun closeAll(actions: List<() -> Unit>) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() } catch (release: Throwable) {
            if (failure == null) failure = release else if (release !== failure) failure!!.addSuppressed(release)
        }
    }
    failure?.let { throw it }
}
