package com.sheen.rokid.audio.android

import org.junit.Assert.*
import org.junit.Test

class NativeOwnershipTest {
    @Test fun repeatedReleaseDoesNotReleaseRecognizerOwnerTwice() {
        var releases = 0
        val port = OwnedRecognizerPort(object : RecognizerOwner {
            override fun createStream() = object : RecognizerStream {
                override fun accept(samples: FloatArray, sampleRateHz: Int) {}
                override fun finish() {}
                override fun read() = ""
                override fun release() {}
            }
            override fun release() { releases++ }
        })
        port.release(); port.release()
        assertEquals(1, releases)
        assertThrows(IllegalStateException::class.java) { port.reset() }
    }
    @Test fun resetReleasesOldStreamAndFinalUsesTheNewStream() {
        val actions = mutableListOf<String>(); var number = 0
        val port = OwnedRecognizerPort(object : RecognizerOwner {
            override fun createStream(): RecognizerStream {
                val id = ++number; actions += "create$id"
                return object : RecognizerStream {
                    override fun accept(samples: FloatArray, sampleRateHz: Int) { actions += "accept$id:$sampleRateHz" }
                    override fun finish() { actions += "finish$id" }
                    override fun read() = "result$id"
                    override fun release() { actions += "release$id" }
                }
            }
            override fun release() { actions += "ownerRelease" }
        })
        assertEquals("result1", port.accept(floatArrayOf(0f), 16000))
        port.reset()
        assertEquals("result2", port.finish())
        port.release()
        assertEquals(listOf("create1", "accept1:16000", "release1", "create2", "finish2", "release2", "ownerRelease"), actions)
    }

    @Test fun failedInitialStreamReleasesItsRecognizerOwner() {
        var releases = 0
        assertThrows(IllegalStateException::class.java) {
            OwnedRecognizerPort(object : RecognizerOwner {
                override fun createStream(): RecognizerStream = error("synthetic stream failure")
                override fun release() { releases++ }
            })
        }
        assertEquals(1, releases)
    }

    @Test fun computeFailureStillReleasesEmbeddingStream() {
        val actions = mutableListOf<String>()
        val port = OwnedEmbeddingPort(object : EmbeddingOwner {
            override fun createStream() = object : EmbeddingStream {
                override fun accept(samples: FloatArray, sampleRateHz: Int) { actions += "accept:$sampleRateHz" }
                override fun finish() { actions += "finish" }
                override fun ready() = true
                override fun compute(): FloatArray = error("synthetic compute failure")
                override fun release() { actions += "streamRelease" }
            }
            override fun release() { actions += "ownerRelease" }
        })
        assertThrows(IllegalStateException::class.java) { port.extract(floatArrayOf(0f), 16000) }
        port.release()
        assertEquals(listOf("accept:16000", "finish", "streamRelease", "ownerRelease"), actions)
    }

    @Test fun unreadyEmbeddingStreamAbstainsAndReleases() {
        var releases = 0; var computes = 0
        val port = OwnedEmbeddingPort(object : EmbeddingOwner {
            override fun createStream() = object : EmbeddingStream {
                override fun accept(samples: FloatArray, sampleRateHz: Int) {}
                override fun finish() {}
                override fun ready() = false
                override fun compute() = floatArrayOf(1f).also { computes++ }
                override fun release() { releases++ }
            }
            override fun release() {}
        })
        assertNull(port.extract(floatArrayOf(0f), 16000))
        assertEquals(1, releases); assertEquals(0, computes)
        port.release()
    }
}
