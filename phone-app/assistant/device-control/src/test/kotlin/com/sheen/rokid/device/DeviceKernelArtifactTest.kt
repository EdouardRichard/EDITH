package com.sheen.rokid.device

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.25 Task 13 / R67: `DeviceKernel.screenshot` stores the captured bytes through the [ArtifactStore] port and reports
 * the real [com.sheen.rokid.plugin.ImageRef] — sha256, mime and dimensions included. Without a store, or when the
 * store throws, the turn still succeeds with the `pending` placeholder and a diagnostic, never an exception.
 */
class DeviceKernelArtifactTest {

    private val accessibility = FakeAccessibilityPort()

    private class RecordingStore(private val failure: IOException? = null) : ArtifactStore {
        var puts = 0
        var lastBytes: ByteArray? = null
        var lastMime: String? = null
        var lastWidth = 0
        var lastHeight = 0

        override fun put(bytes: ByteArray, mime: String, width: Int, height: Int): com.sheen.rokid.plugin.ImageRef {
            puts++
            lastBytes = bytes.copyOf()
            lastMime = mime
            lastWidth = width
            lastHeight = height
            failure?.let { throw it }
            return com.sheen.rokid.plugin.ImageRef("img-real", mime, width, height, "sha256-real")
        }

        override fun get(artifactId: String): StoredArtifact? = null

        override fun evictBefore(keep: Int) = Unit
    }

    private fun kernel(store: ArtifactStore?) =
        DeviceKernel(accessibility = accessibility, shell = null, artifactStore = store)

    @Test
    fun `the captured bytes are stored and the envelope carries the real artifact reference`() {
        val store = RecordingStore()
        kernel(store).use { kernel ->
            val envelope = kernel.screenshot()

            assertTrue(envelope.ok)
            assertContentEquals(byteArrayOf(1, 2, 3), store.lastBytes, "the kernel stored exactly the captured bytes")
            assertEquals("image/jpeg", store.lastMime)
            assertEquals(768, store.lastWidth)
            assertEquals(1664, store.lastHeight)

            val image = envelope.images.single()
            assertEquals("img-real", image.artifactId)
            assertEquals("sha256-real", image.sha256)
            assertEquals("image/jpeg", image.mime)
            assertEquals(768, image.width)
            assertEquals(1664, image.height)
            assertNull(envelope.data, "a resolved artifact needs no pending diagnostic")
        }
    }

    @Test
    fun `a store that throws leaves the pending placeholder and a diagnostic, and never fails the turn`() {
        val store = RecordingStore(failure = IOException("disk full"))
        kernel(store).use { kernel ->
            val envelope = kernel.screenshot()

            assertTrue(envelope.ok, "the screenshot itself succeeded; only the artifact write failed")
            assertEquals(1, store.puts)
            val image = envelope.images.single()
            assertEquals("pending", image.artifactId)
            assertEquals("", image.sha256)
            val diagnostic = requireNotNull(envelope.data)
            assertTrue(diagnostic.contains("screenshot_artifact_pending"), diagnostic)
            assertTrue(diagnostic.contains("STORE_FAILED"), diagnostic)
        }
    }
}
