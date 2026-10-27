package com.sheen.rokid.phone.model

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 3 / spec §6.3: `dispatch("edith.START")` must refuse **before** `beforeStart`, so the foreground
 * service is not left running for a session that could never use the models.
 *
 * The root here is hand-built (no Android context) and has no readiness by default, which also pins the compatibility
 * rule: a composition built without a [ModelReadiness] keeps pre-0.4 behavior (null readiness = admitted).
 */
class ModelStartGateTest {

    private fun root(readiness: ModelReadiness?): HostCompositionRoot {
        val model = object : ModelClient {
            override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
                error("not used")
        }
        return HostCompositionRoot(
            host = HostRuntime(1),
            profiles = MemoryProfileRepository(),
            model = model,
            environment = null,
            modelReadiness = readiness,
        )
    }

    @Test
    fun `Preparing refuses before beforeStart and does not start a session`() {
        val readiness = ModelReadiness()
        val root = root(readiness)
        val token = root.authorizeStart("p2-diagnostic")
        var beforeStartRan = false

        val admitted = root.dispatch("edith.START", token, beforeStart = { beforeStartRan = true })

        assertFalse(admitted)
        assertFalse("beforeStart must not run while models are preparing", beforeStartRan)
        assertEquals("MODELS_PREPARING", root.state.value.errorCode)
        assertEquals(SessionState.Idle, root.host.currentState)
        assertNull(root.host.currentInputEpoch)
    }

    @Test
    fun `Failed refuses before beforeStart and reports MODELS_UNAVAILABLE`() {
        val readiness = ModelReadiness().apply { markFailed("MODELS_LAYA_FAILED") }
        val root = root(readiness)
        val token = root.authorizeStart("p2-diagnostic")
        var beforeStartRan = false

        val admitted = root.dispatch("edith.START", token, beforeStart = { beforeStartRan = true })

        assertFalse(admitted)
        assertFalse("beforeStart must not run when model provisioning failed", beforeStartRan)
        assertEquals("MODELS_UNAVAILABLE", root.state.value.errorCode)
        assertEquals(SessionState.Idle, root.host.currentState)
    }

    @Test
    fun `Ready proceeds through beforeStart`() {
        val readiness = ModelReadiness().apply { markReady("bundle-1") }
        val root = root(readiness)
        val token = root.authorizeStart("p2-diagnostic")
        var beforeStartRan = false

        val admitted = root.dispatch("edith.START", token, beforeStart = { beforeStartRan = true })

        assertTrue(admitted)
        assertTrue("Ready must reach the foreground start callback", beforeStartRan)
        assertEquals("a Ready gate itself sets no error; the harness has no HostEnvironment",
            "CAPABILITY_MISSING", root.state.value.errorCode)
    }

    @Test
    fun `start itself refuses when models are not ready`() {
        val readiness = ModelReadiness().apply { markFailed("MODELS_ASR_FAILED") }
        val root = root(readiness)

        root.start("p2-diagnostic")

        assertEquals("MODELS_UNAVAILABLE", root.state.value.errorCode)
        assertEquals(SessionState.Idle, root.host.currentState)
    }

    @Test
    fun `a composition without a readiness state keeps the old admission behavior`() {
        val root = root(null)
        val token = root.authorizeStart("p2-diagnostic")
        var beforeStartRan = false

        val admitted = root.dispatch("edith.START", token, beforeStart = { beforeStartRan = true })

        assertTrue(admitted)
        assertTrue(beforeStartRan)
    }

    @Test
    fun `create refuses before assembling anything when models are not ready`() {
        val context = android.content.ContextWrapper(null)
        val preparing = runCatching {
            HostCompositionRoot.create(context, readiness = ModelReadiness())
        }.exceptionOrNull()
        assertTrue("Preparing must refuse create: $preparing",
            preparing is IllegalStateException && preparing.message.orEmpty().contains("MODELS_PREPARING"))

        val failed = runCatching {
            HostCompositionRoot.create(context, readiness = ModelReadiness().apply { markFailed("MODELS_LAYA_FAILED") })
        }.exceptionOrNull()
        assertTrue("Failed must refuse create: $failed",
            failed is IllegalStateException && failed.message.orEmpty().contains("MODELS_UNAVAILABLE"))
    }
}
