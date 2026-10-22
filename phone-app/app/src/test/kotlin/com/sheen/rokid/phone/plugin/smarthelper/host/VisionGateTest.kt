package com.sheen.rokid.phone.plugin.smarthelper.host

import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 10: the live vision switch shared by the perception provider, the screenshot tool and the
 * settings screen.
 *
 * The point of the gate is that it is **live**, not an assembly-time snapshot: the screen saves through
 * [HostCompositionRoot.saveSmartSettings] and the provider/tool must see the new value on their very next call. The
 * store is only the persistence half; a write that fails must still leave capture disabled when the user asked for
 * off.
 */
class VisionGateTest {

    @Test
    fun theGateStartsAtItsInitialValueAndFlipsLive() {
        val gate = VisionGate(true)

        assertTrue(gate.enabled)

        gate.set(false)
        assertFalse("an off save must be visible to the next reader", gate.enabled)

        gate.set(true)
        assertTrue(gate.enabled)
    }

    @Test
    fun onChangeHearsEveryActualChangeAndNoRedundantSet() {
        val seen = mutableListOf<Boolean>()
        val gate = VisionGate(initial = true, onChange = { seen += it })

        gate.set(true) // already on: not a change
        gate.set(false)
        gate.set(false) // already off: not a change
        gate.set(true)

        assertEquals(listOf(false, true), seen)
        assertTrue(gate.enabled)
    }

    @Test
    fun aSuccessfulSavePersistsTheSwitchAndAppliesItImmediately() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        val gate = VisionGate(initial = true)
        val root = root(store, gate)
        try {
            root.saveSmartSettings(SmartSettings(visionEnabled = false))

            assertFalse("the live gate must follow a successful save", gate.enabled)
            assertFalse("the blob must agree with the gate", SmartSettingsStore(blobs).load().visionEnabled)

            root.saveSmartSettings(SmartSettings(visionEnabled = true))
            assertTrue(gate.enabled)
            assertTrue(SmartSettingsStore(blobs).load().visionEnabled)
        } finally {
            root.close()
        }
    }

    @Test
    fun aFailedSaveStillLeavesVisionClosedWhenTheUserTurnedItOff() {
        // A failed write must not silently leave capture enabled: persistence is the durability half, the switch is
        // the privacy control. Turning it off applies even when the blob store refuses the write.
        val blobs = TestBlobs().apply { failId = SmartSettingsStore.BLOB_ID }
        val store = SmartSettingsStore(blobs)
        val gate = VisionGate(initial = true)
        val root = root(store, gate)
        try {
            val result = runCatching { root.saveSmartSettings(SmartSettings(visionEnabled = false)) }

            assertTrue("the failing store must actually fail this save", result.isFailure)
            assertFalse("a failed save must not leave the gate enabled", gate.enabled)
        } finally {
            root.close()
        }
    }

    /** A composition with only the settings seam wired; the model and host are never used by these tests. */
    private fun root(store: SmartSettingsStore, gate: VisionGate): HostCompositionRoot =
        HostCompositionRoot(
            HostRuntime(1),
            MemoryProfileRepository(),
            UnusedModel(),
            null,
            smartSettingsStore = store,
            visionGate = gate,
        )

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("the settings seam must never reach the model")
    }
}
