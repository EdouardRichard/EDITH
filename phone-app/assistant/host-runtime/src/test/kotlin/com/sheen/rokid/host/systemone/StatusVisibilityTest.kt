package com.sheen.rokid.host.systemone

import com.sheen.rokid.host.embedding.EmbeddingServiceImpl
import com.sheen.rokid.plugin.SystemOneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that LOADING/READY/UNAVAILABLE status is queryable for both services.
 * Performance benchmarks (P95 latency, memory) are deferred to Android instrumented tests
 * on K40/K70 Ultra hardware.
 */
class StatusVisibilityTest {
    @Test
    fun `SystemOneService status is queryable and transitions correctly`() {
        val service = SystemOneServiceImpl(modelPath = "/nonexistent")
        // Initially LOADING (async thread just started)
        val initialStatus = service.status
        assertEquals(SystemOneStatus.LOADING, initialStatus, "Initial status should be LOADING")

        // After async failure resolves
        Thread.sleep(300)
        assertEquals(SystemOneStatus.UNAVAILABLE, service.status)
        assertNotNull(service.status.name, "Status name must be non-null for UI display")
    }

    @Test
    fun `EmbeddingService availability is queryable`() {
        val service = EmbeddingServiceImpl(modelPath = "/nonexistent")
        Thread.sleep(300)
        assertEquals(false, service.isAvailable)
        assertEquals(256, service.dimensions, "Dimensions must be queryable regardless of availability")
    }

    @Test
    fun `ModelInfo fields are populated when model loads successfully`() {
        // Create a temp file to simulate a valid model path
        val tempFile = java.io.File.createTempFile("test-model", ".bin")
        tempFile.writeBytes(ByteArray(100))
        try {
            val service = SystemOneServiceImpl(modelPath = tempFile.absolutePath)
            Thread.sleep(500)
            // With stub engine + valid file, should reach READY
            assertEquals(SystemOneStatus.READY, service.status)
            val info = service.modelInfo
            assertNotNull(info, "ModelInfo should be populated when READY")
            assertNotNull(info.name)
            assertNotNull(info.version)
        } finally {
            tempFile.delete()
        }
    }
}
