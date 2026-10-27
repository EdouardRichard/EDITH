package com.sheen.rokid.host.embedding

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.FailureReason
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddingServiceImplTest {
    @Test
    fun `status is UNAVAILABLE when model not loaded`() {
        val service = EmbeddingServiceImpl(modelPath = "/nonexistent")
        Thread.sleep(200)
        assertFalse(service.isAvailable)
    }

    @Test
    fun `dimensions returns 256 regardless of availability`() {
        val service = EmbeddingServiceImpl(modelPath = "/nonexistent")
        assertEquals(256, service.dimensions)
    }

    @Test
    fun `embed returns error when unavailable`() {
        val service = EmbeddingServiceImpl(modelPath = "/nonexistent")
        Thread.sleep(200)
        val result = service.embed("test text")
        assertTrue(result is EmbedResult.Error)
        assertEquals(FailureReason.MODEL_NOT_LOADED, (result as EmbedResult.Error).reason)
    }

    @Test
    fun `modelInfo is null when unavailable`() {
        val service = EmbeddingServiceImpl(modelPath = "/nonexistent")
        Thread.sleep(200)
        assertEquals(null, service.modelInfo)
    }

    /**
     * 0.4 Task 3: the defense-in-depth materializer must run **before** `File.exists()`.
     *
     * The fixture starts without the directory; only a provision callback that runs first can create it, so an
     * available service proves the ordering rather than merely that a callback happened.
     */
    @Test
    fun `provision runs before the file existence check`() {
        val root = Files.createTempDirectory("edith-embedding").toFile()
        try {
            val directory = File(root, "models/embeddinggemma")
            val graph = File(directory, GRAPH)
            var provisioned = false
            val service = EmbeddingServiceImpl(
                modelPath = directory.absolutePath,
                provision = {
                    provisioned = true
                    graph.parentFile.mkdirs()
                    graph.writeBytes(byteArrayOf(1, 2, 3))
                },
            )

            assertTrue(await { service.isAvailable }, "the provisioned directory must load")
            assertTrue(provisioned, "provision must have run")
        } finally {
            root.deleteRecursively()
        }
    }

    /** The new parameter is optional; an existing artifact keeps loading exactly as before. */
    @Test
    fun `null provision still loads an existing model directory`() {
        val root = Files.createTempDirectory("edith-embedding").toFile()
        try {
            val graph = File(root, GRAPH)
            graph.writeBytes(byteArrayOf(1, 2, 3))
            val service = EmbeddingServiceImpl(modelPath = root.absolutePath, provision = null)

            assertTrue(await { service.isAvailable }, "an existing path must still load")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private companion object {
        const val GRAPH = "embeddinggemma-300M_seq256_mixed-precision.tflite"
    }
}
