package com.sheen.rokid.phone.plugin.smarthelper.host

import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3 Phase 4B (`D189`): the live global 「不拦截」 gate.
 *
 * The persisted field is only one half. The plugin/policy reads a **provider** on every `decide()` / shell precheck,
 * so the gate must answer with the value in force *now* - not with a snapshot taken when the provider was handed out.
 * A save on the settings screen updates the same instance, and a failed save must never arm unconfirmed execution.
 */
class NoInterceptionGateTest {

    @Test
    fun theGateIsOffByDefaultAndAProviderCapturedBeforeTheSetStillSeesTheFlip() {
        // A composition can be hand-built; its default must be the safe one.
        assertFalse(NoInterceptionGate().enabled)

        val gate = NoInterceptionGate()
        val provider = gate.provider()
        assertFalse(provider())

        gate.set(true)
        assertTrue(gate.enabled)
        assertTrue("the provider must read the live value, not a snapshot", provider())

        gate.set(false)
        assertFalse(gate.enabled)
        assertFalse(provider())
    }

    @Test
    fun theGateCanBeSeededFromAPersistedOnValue() {
        assertTrue(NoInterceptionGate(initial = true).enabled)
    }

    @Test
    fun aSuccessfulSavePersistsTheModeAndUpdatesTheLiveGateImmediately() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        val gate = NoInterceptionGate(initial = false)
        val root = root(store, gate)
        try {
            root.saveSmartSettings(SmartSettings(noInterception = true))

            assertTrue("the live gate must follow a successful save", gate.enabled)
            assertTrue(gate.provider()())
            assertTrue("the blob must agree with the gate", SmartSettingsStore(blobs).load().noInterception)

            root.saveSmartSettings(SmartSettings(noInterception = false))

            assertFalse("turning the mode off must apply immediately", gate.enabled)
            assertFalse(SmartSettingsStore(blobs).load().noInterception)
        } finally {
            root.close()
        }
    }

    @Test
    fun aFailedSaveCannotArmUnconfirmedExecution() {
        // Turning the mode on is a privilege grant. It applies only after the blob accepted it: a write that failed
        // must not leave the process running in a mode the next restart will not remember.
        val blobs = TestBlobs().apply { failId = SmartSettingsStore.BLOB_ID }
        val store = SmartSettingsStore(blobs)
        val gate = NoInterceptionGate(initial = false)
        val root = root(store, gate)
        try {
            val result = runCatching { root.saveSmartSettings(SmartSettings(noInterception = true)) }

            assertTrue("the failing store must actually fail this save", result.isFailure)
            assertFalse("a failed write must not leave the gate enabled", gate.enabled)
        } finally {
            root.close()
        }
    }

    @Test
    fun aFailedOffSaveStillDisablesTheMode() {
        // The safe direction applies before the write and stays applied: a failed blob write must never leave
        // unconfirmed execution alive after the user disabled it.
        val blobs = TestBlobs().apply { failId = SmartSettingsStore.BLOB_ID }
        val store = SmartSettingsStore(blobs)
        val gate = NoInterceptionGate(initial = true)
        val root = root(store, gate)
        try {
            val result = runCatching { root.saveSmartSettings(SmartSettings(noInterception = false)) }

            assertTrue("the failing store must actually fail this save", result.isFailure)
            assertFalse("a failed save must not keep the gate enabled", gate.enabled)
        } finally {
            root.close()
        }
    }

    /** A composition with only the settings seam wired; the model and host are never used by these tests. */
    private fun root(store: SmartSettingsStore, gate: NoInterceptionGate): HostCompositionRoot =
        HostCompositionRoot(
            HostRuntime(1),
            MemoryProfileRepository(),
            UnusedModel(),
            null,
            smartSettingsStore = store,
            noInterceptionGate = gate,
        )

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("the settings seam must never reach the model")
    }
}
