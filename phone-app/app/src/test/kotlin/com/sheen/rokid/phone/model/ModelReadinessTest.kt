package com.sheen.rokid.phone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 3: the one readiness state the UI, the composition and the session start path share.
 *
 * Two properties are load-bearing:
 * - the initial state is **Preparing** (the app is about to materialize the bundle, so it must never present
 *   itself as ready before the provisioner says so);
 * - **Ready is terminal for the process.** A background re-read that fails after the models already worked must
 *   not take the working models away from the running app (spec §6.3: "已有可用模型时不得因后台校验失败而阻断").
 *   An APK change restarts the process, which is the only event that legitimately re-enters Preparing.
 */
class ModelReadinessTest {

    @Test
    fun `the initial state is Preparing`() {
        assertEquals(ProvisioningState.Preparing, ModelReadiness().state.value)
    }

    @Test
    fun `markReady carries the bundle id`() {
        val readiness = ModelReadiness()

        readiness.markReady("bundle-1")

        assertEquals(ProvisioningState.Ready("bundle-1"), readiness.state.value)
    }

    @Test
    fun `a failed attempt can be retried and then become Ready`() {
        val readiness = ModelReadiness()
        readiness.markFailed("MODELS_LAYA_FAILED")
        assertEquals(ProvisioningState.Failed("MODELS_LAYA_FAILED"), readiness.state.value)

        readiness.markPreparing()
        assertEquals(ProvisioningState.Preparing, readiness.state.value)

        readiness.markReady("bundle-2")
        assertEquals(ProvisioningState.Ready("bundle-2"), readiness.state.value)
    }

    @Test
    fun `Ready does not regress when a background re-read fails`() {
        val readiness = ModelReadiness()
        readiness.markReady("bundle-1")

        readiness.markPreparing()
        assertEquals("a Ready process must not go back to Preparing", ProvisioningState.Ready("bundle-1"),
            readiness.state.value)

        readiness.markFailed("MODELS_EMBEDDING_FAILED")
        assertEquals("a failed re-read must not block already-working models", ProvisioningState.Ready("bundle-1"),
            readiness.state.value)
    }

    @Test
    fun `the failure reason is exposed verbatim`() {
        val readiness = ModelReadiness()

        readiness.markFailed("MODELS_NO_SPACE")

        val state = readiness.state.value
        assertTrue(state is ProvisioningState.Failed)
        assertEquals("MODELS_NO_SPACE", (state as ProvisioningState.Failed).reason)
    }
}
