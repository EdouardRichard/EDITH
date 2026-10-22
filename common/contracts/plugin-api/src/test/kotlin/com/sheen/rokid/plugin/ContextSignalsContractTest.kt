package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.ContractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ContextSignalsContractTest {
    /** Host that grants nothing and records which capability each typed accessor asked for. */
    private class UngrantedServices : HostServices {
        val requested = mutableListOf<Capability>()
        override fun require(capability: Capability): Any {
            requested += capability
            throw ContractError.CapabilityMissing("capability not granted: $capability")
        }
        override val tasks: TaskScope = EmptyHostServices.tasks
    }

    @Test fun `snapshot rejects negative capture time`() {
        assertFailsWith<IllegalArgumentException> { ContextSnapshot(-1, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ContextSnapshot(Long.MIN_VALUE, emptyMap()) }
        assertEquals(0, ContextSnapshot(0, emptyMap()).capturedAtNanos)
    }

    @Test fun `snapshot rejects blank signal keys`() {
        assertFailsWith<IllegalArgumentException> { ContextSnapshot(1, mapOf("" to "v")) }
        assertFailsWith<IllegalArgumentException> { ContextSnapshot(1, mapOf("  " to "v")) }
    }

    @Test fun `snapshot preserves capture time and values`() {
        val snapshot = ContextSnapshot(42, mapOf("screen" to "home", "hour" to "9"))
        assertEquals(42, snapshot.capturedAtNanos)
        assertEquals(mapOf("screen" to "home", "hour" to "9"), snapshot.values)
        assertEquals(emptyMap<String, String>(), ContextSnapshot(1, emptyMap()).values)
    }

    @Test fun `typed accessors report a missing capability as CapabilityMissing`() {
        val services = UngrantedServices()
        assertFailsWith<ContractError.CapabilityMissing> { services.context() }
        assertFailsWith<ContractError.CapabilityMissing> { services.actions() }
        assertFailsWith<ContractError.CapabilityMissing> { services.wakeWord() }
        assertEquals(
            listOf(Capability.Context, Capability.Actions, Capability.WakeWord),
            services.requested,
        )
    }

    @Test fun `context accessor returns the granted signals provider`() {
        val signals = object : ContextSignals {
            override fun snapshot() = ContextSnapshot(7, mapOf("k" to "v"))
            override fun listen(listener: (ContextSnapshot) -> Unit) =
                object : CancellationHandle { override fun cancel() = Unit }
        }
        val services = object : HostServices {
            override fun require(capability: Capability): Any {
                assertEquals(Capability.Context, capability)
                return signals
            }
            override val tasks: TaskScope = EmptyHostServices.tasks
        }
        assertSame(signals, services.context())
    }
}
