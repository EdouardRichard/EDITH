package com.sheen.rokid.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmbeddingServicesContractTest {
    @Test
    fun `EmbedResult Success contains vector`() {
        val result = EmbedResult.Success(vector = floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(3, result.vector.size)
    }

    @Test
    fun `EmbedResult Error contains reason and message`() {
        val result = EmbedResult.Error(reason = FailureReason.MODEL_NOT_LOADED, message = "test")
        assertEquals(FailureReason.MODEL_NOT_LOADED, result.reason)
    }

    @Test
    fun `Capability enum includes SystemOne and Embedding`() {
        assertNotNull(Capability.valueOf("SystemOne"))
        assertNotNull(Capability.valueOf("Embedding"))
    }
}
