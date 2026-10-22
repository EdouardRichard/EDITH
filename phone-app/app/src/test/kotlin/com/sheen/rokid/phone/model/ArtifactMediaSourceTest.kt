package com.sheen.rokid.phone.model

import com.sheen.rokid.device.AccessibilityPort
import com.sheen.rokid.device.DeviceKernel
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.device.Screenshot
import com.sheen.rokid.model.MediaSource
import com.sheen.rokid.model.ResolvedAudio
import com.sheen.rokid.phone.artifacts.FileArtifactStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.25 Task 13 / R67 + R71: the production [MediaSource.resolveImage] backed by the same artifact store the kernel
 * writes screenshots into. A hit returns the stored bytes and metadata; a miss returns null so the adapter can render
 * the visible `[image unavailable: …]` placeholder instead of refusing the request.
 */
class ArtifactMediaSourceTest {

    private lateinit var root: File
    private lateinit var store: FileArtifactStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("edith-media-source-test-").toFile()
        store = FileArtifactStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `an artifact written through the shared store resolves to image bytes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 4, 5, 6)
        val ref = store.put(bytes, "image/jpeg", 768, 1664)
        val source = ArtifactMediaSource(store)

        val resolved = requireNotNull(source.resolveImage(ref.artifactId))

        assertArrayEquals(bytes, resolved.bytes)
        assertEquals("image/jpeg", resolved.mime)
        assertEquals(768, resolved.width)
        assertEquals(1664, resolved.height)
    }

    @Test
    fun `a missing or expired artifact resolves to null rather than throwing`() {
        assertNull(ArtifactMediaSource(store).resolveImage("img-expired"))
    }

    @Test
    fun `audio resolution delegates to the wrapped source`() {
        val audioBytes = byteArrayOf(1, 2, 3, 4)
        val audio = object : MediaSource {
            override fun resolveAudio(ref: String): ResolvedAudio? =
                if (ref == "seg-1") ResolvedAudio(audioBytes, "pcm16-16k-mono", 500) else null
        }
        val source = ArtifactMediaSource(store, audio = audio)

        val resolved = requireNotNull(source.resolveAudio("seg-1"))
        assertArrayEquals(audioBytes, resolved.bytes)
        assertEquals("pcm16-16k-mono", resolved.format)
        assertEquals(500, resolved.durationMillis)
        assertNull(source.resolveAudio("seg-missing"))
    }

    @Test
    fun `without an audio source image-only resolution still works and audio is null`() {
        val ref = store.put(byteArrayOf(7, 8), "image/png", 10, 20)
        val source = ArtifactMediaSource(store)

        assertNotNull(source.resolveImage(ref.artifactId))
        assertNull(source.resolveAudio("seg-1"))
    }

    @Test
    fun `the kernel's real screenshot reference resolves through the same store-backed source`() {
        // The production wiring (R67): the kernel and the model's MediaSource are handed the same store. This drives
        // the real kernel path and reads the reference back through the model-facing source, without a device.
        val bytes = byteArrayOf(9, 8, 7, 6)
        val accessibility = object : AccessibilityPort {
            override fun isBound() = true
            override fun isInteractive() = true
            override fun isKeyguardLocked() = false
            override fun observe(filter: ScreenFilter) = Observation(0, emptyList(), emptyList(), false)
            override fun performNodeAction(ref: Ref, action: String) = false
            override fun dispatchGesture(gesture: Gesture) = false
            override fun screenshot() = Screenshot(bytes, "image/jpeg", 768, 1664)
            override fun foreground() = Foreground("com.example.app", null)
        }
        val kernel = DeviceKernel(accessibility = accessibility, shell = null, artifactStore = store)
        try {
            val envelope = kernel.screenshot()
            assertTrue(envelope.ok)
            val ref = envelope.images.single()
            assertTrue("a real reference, not the placeholder: $ref", ref.artifactId != "pending")
            assertTrue(ref.sha256.isNotBlank())

            val resolved = requireNotNull(ArtifactMediaSource(store).resolveImage(ref.artifactId))
            assertArrayEquals(bytes, resolved.bytes)
            assertEquals("image/jpeg", resolved.mime)
            assertEquals(768, resolved.width)
            assertEquals(1664, resolved.height)
        } finally {
            kernel.close()
        }
    }
}
