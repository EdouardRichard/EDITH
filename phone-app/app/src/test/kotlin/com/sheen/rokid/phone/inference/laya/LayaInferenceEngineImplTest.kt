package com.sheen.rokid.phone.inference.laya

import android.content.ContextWrapper
import com.laya.LayaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The production engine's backend (edith-0.2 Phase B, B11).
 *
 * The plan's Global Constraints and the A5 evidence both require the **GPU** on the delivery target: the same graph
 * measured 742 ms CPU against 231 ms GPU, which is the difference between fitting D60's event-driven cadence and not.
 * The class shipped with a CPU default anyway (review M4 recorded it and nothing changed), so what is pinned here is
 * the default itself, plus the one degradation that is allowed to change it.
 */
class LayaInferenceEngineImplTest {

    private fun engine(requested: LayaEngine.Backend? = null): LayaInferenceEngineImpl =
        if (requested == null) {
            LayaInferenceEngineImpl(ContextWrapper(null))
        } else {
            LayaInferenceEngineImpl(ContextWrapper(null), requested)
        }

    @Test
    fun `the production engine asks for the GPU, which is what the delivery target needs`() {
        assertEquals(LayaEngine.Backend.GPU, LayaInferenceEngineImpl.DEFAULT_BACKEND)
    }

    @Test
    fun `no backend is in use before a graph has compiled`() {
        // `effectiveBackend` must not read the *requested* backend: before `initialize()` (and after a total failure)
        // no graph exists, and a field that says GPU while nothing was compiled is the kind of value a later reader
        // believes. `ask` is unreachable in that state anyway (`host ?: error`).
        assertNull(engine().effectiveBackend)
    }

    @Test
    fun `an explicitly requested CPU is honoured rather than being a GPU in disguise`() {
        // CPU remains the configurable degradation (a device without a working GPU runtime), so asking for it must be
        // honoured exactly - a silent promotion back to GPU would compile a graph this caller did not ask for.
        assertEquals(LayaEngine.Backend.CPU, engine(LayaEngine.Backend.CPU).requestedBackend)
    }

    @Test
    fun `a failed GPU compile may step down to the CPU, and a CPU request has nowhere to step`() {
        // The retry itself needs the SDK and a provisioned model set, so it is the device's answer; what is pinned
        // here is the policy - CPU is the only degradation the delivery target allows, and it is never retried.
        assertEquals(LayaEngine.Backend.CPU, cpuFallbackFor(LayaEngine.Backend.GPU))
        assertEquals(null, cpuFallbackFor(LayaEngine.Backend.CPU))
    }
}
