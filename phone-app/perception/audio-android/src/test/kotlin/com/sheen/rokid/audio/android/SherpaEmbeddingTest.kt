package com.sheen.rokid.audio.android

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.SpeakerWindow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SherpaEmbeddingTest {
    private val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic")
    private fun window() = SpeakerWindow(AudioSegment(
        EventEnvelope(SessionId("test"), Generation(1), "event", 0, 20_000_000),
        verified.format, ByteArray(640).apply { this[1] = 0x80.toByte() }), SampleRange("c", 0, 320), false)

    @Test fun extractionDecodesVerifiedEncodingAndWipesOwnedSamples() {
        var ownedSamples: FloatArray? = null
        val port = object : EmbeddingPort {
            override fun extract(samples: FloatArray, sampleRateHz: Int): FloatArray {
                assertEquals(16000, sampleRateHz)
                assertEquals(-1f, samples[0], 0f)
                ownedSamples = samples
                return floatArrayOf(1f, 0f)
            }
            override fun release() {}
        }
        val engine = SherpaEmbeddingExtractor(port, verified)
        assertArrayEquals(floatArrayOf(1f, 0f), engine.extract(window()), 0f)
        assertTrue(requireNotNull(ownedSamples).all { it == 0f })
        engine.close()
    }

    @Test fun invalidSourceAndInvalidVectorAbstainWithoutRetainingSamples() {
        val invalidVector = floatArrayOf(Float.NaN)
        var calls = 0
        val engine = SherpaEmbeddingExtractor(object : EmbeddingPort {
            override fun extract(samples: FloatArray, sampleRateHz: Int) = invalidVector.also { calls++ }
            override fun release() {}
        }, verified)
        try {
            assertNull(engine.extract(window().copy(segment = window().segment.copy(format = AudioFormat.Unknown))))
            assertEquals(0, calls)
            assertNull(engine.extract(window()))
            assertArrayEquals(floatArrayOf(0f), invalidVector, 0f)
        } finally { engine.close() }
    }

    @Test fun closeWaitsForAdmittedExtractionAndRejectsFurtherWork() {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1); val closeStarted = CountDownLatch(1)
        var releases = 0
        val engine = SherpaEmbeddingExtractor(object : EmbeddingPort {
            override fun extract(samples: FloatArray, sampleRateHz: Int): FloatArray {
                entered.countDown(); check(proceed.await(3, TimeUnit.SECONDS)); assertEquals(0, releases)
                return floatArrayOf(1f)
            }
            override fun release() { releases++ }
        }, verified)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val extraction = pool.submit<FloatArray?> { engine.extract(window()) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val close = pool.submit { closeStarted.countDown(); engine.close() }
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS))
            proceed.countDown()
            assertArrayEquals(floatArrayOf(1f), extraction.get(3, TimeUnit.SECONDS), 0f)
            close.get(3, TimeUnit.SECONDS); engine.close()
            assertEquals(1, releases)
            assertThrows(IllegalStateException::class.java) { engine.extract(window()) }
        } finally { proceed.countDown(); pool.shutdownNow(); engine.close() }
    }
}
