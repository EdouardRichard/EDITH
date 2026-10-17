package com.sheen.rokid.host.systemone

import com.sheen.rokid.plugin.SystemOneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
