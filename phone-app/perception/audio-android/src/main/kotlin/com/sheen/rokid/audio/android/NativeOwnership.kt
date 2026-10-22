package com.sheen.rokid.audio.android

import com.sheen.rokid.audio.VadDecision

internal interface VadOwner {
    fun accept(samples: FloatArray)
    fun speechDetected(): Boolean
    fun empty(): Boolean
    fun pop()
    fun reset()
    fun release()
}
internal class OwnedVadPort(private val owner: VadOwner) : VadPort {
    private var closed = false
    override fun accept(samples: FloatArray, sampleRateHz: Int): VadDecision {
        check(!closed)
        require(sampleRateHz == 16000)
        owner.accept(samples)
        val speech = owner.speechDetected()
        var endpoint = false
        while (!owner.empty()) { endpoint = true; owner.pop() }
        return VadDecision(speech, endpoint)
    }
    override fun reset() { check(!closed); owner.reset() }
    override fun release() {
        if (closed) return
        closed = true
        owner.release()
    }
}

internal interface RecognizerStream {
    fun accept(samples: FloatArray, sampleRateHz: Int)
    fun finish()
    fun read(): String
    fun release()
}
internal interface RecognizerOwner {
    fun createStream(): RecognizerStream
    fun release()
}

internal class OwnedRecognizerPort(private val owner: RecognizerOwner) : RecognizerPort {
    private var closed = false
    private var stream: RecognizerStream? = try { owner.createStream() } catch (failure: Throwable) {
        try { owner.release() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        throw failure
    }
    override fun accept(samples: FloatArray, sampleRateHz: Int): String {
        val current = checkNotNull(stream)
        current.accept(samples, sampleRateHz)
        return current.read()
    }
    override fun finish(): String {
        val current = checkNotNull(stream)
        current.finish()
        return current.read()
    }
    override fun reset() {
        check(!closed)
        val previous = stream
        stream = null
        previous?.release()
        stream = owner.createStream()
    }
    override fun release() {
        if (closed) return
        closed = true
        val previous = stream
        stream = null
        try { previous?.release() } finally { owner.release() }
    }
}

internal interface EmbeddingStream {
    fun accept(samples: FloatArray, sampleRateHz: Int)
    fun finish()
    fun ready(): Boolean
    fun compute(): FloatArray
    fun release()
}
internal interface EmbeddingOwner {
    fun createStream(): EmbeddingStream
    fun release()
}
internal class OwnedEmbeddingPort(private val owner: EmbeddingOwner) : EmbeddingPort {
    private var closed = false
    override fun extract(samples: FloatArray, sampleRateHz: Int): FloatArray? {
        check(!closed)
        val stream = owner.createStream()
        try {
            stream.accept(samples, sampleRateHz)
            stream.finish()
            return if (stream.ready()) stream.compute() else null
        } finally { stream.release() }
    }
    override fun release() {
        if (closed) return
        closed = true
        owner.release()
    }
}
