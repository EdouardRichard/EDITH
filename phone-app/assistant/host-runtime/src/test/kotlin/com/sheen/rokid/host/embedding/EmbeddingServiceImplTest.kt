package com.sheen.rokid.host.embedding

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.FailureReason
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
}
