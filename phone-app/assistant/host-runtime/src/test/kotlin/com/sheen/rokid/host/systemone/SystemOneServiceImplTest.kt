package com.sheen.rokid.host.systemone

import com.sheen.rokid.plugin.SystemOneStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemOneServiceImplTest {
    @Test
    fun `status is UNAVAILABLE when model path does not exist`() {
        val service = SystemOneServiceImpl(modelPath = "/nonexistent/path")
        // Async loader needs time to resolve the missing file
        Thread.sleep(200)
        assertEquals(SystemOneStatus.UNAVAILABLE, service.status)
        assertFalse(service.isAvailable)
    }

    @Test
    fun `modelInfo is null when unavailable`() {
        val service = SystemOneServiceImpl(modelPath = "/nonexistent/path")
        assertEquals(null, service.modelInfo)
    }

    @Test
    fun `status transitions from LOADING to UNAVAILABLE on load failure`() {
        // The constructor starts async loading; by the time we check after a brief wait,
        // a nonexistent path should have resolved to UNAVAILABLE.
        val service = SystemOneServiceImpl(modelPath = "/nonexistent/path")
        // Give async loader time to fail
        Thread.sleep(200)
        assertEquals(SystemOneStatus.UNAVAILABLE, service.status)
    }

    /**
     * 0.4 Task 3: the defense-in-depth materializer must run **before** `ModelLoader.load` checks the path.
     *
     * The fixture starts without the file; only a provision callback that runs first can create it, so a READY
     * service proves the ordering rather than merely that a callback happened.
     */
    @Test
    fun `provision runs before the model file check`() {
        val directory = Files.createTempDirectory("edith-systemone").toFile()
        try {
            val model = File(directory, LAYA_GRAPH)
            var provisioned = false
            val service = SystemOneServiceImpl(
                modelPath = model.absolutePath,
                provision = {
                    provisioned = true
                    model.writeBytes(byteArrayOf(1, 2, 3))
                },
            )

            assertTrue(await { service.status == SystemOneStatus.READY }, "the provisioned graph must load")
            assertTrue(provisioned, "provision must have run")
            assertTrue(service.isAvailable)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The new parameter is optional; an existing test-shaped construction keeps the old behavior. */
    @Test
    fun `null provision keeps the existing missing-file behavior`() {
        val service = SystemOneServiceImpl(modelPath = "/nonexistent/path", provision = null)
        Thread.sleep(200)
        assertEquals(SystemOneStatus.UNAVAILABLE, service.status)
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
        const val LAYA_GRAPH = "laya_ml_s256_embeds_wfp16.tflite"
    }
}
