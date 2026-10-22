package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import kotlin.test.*

class HostRuntimeSwitchingTest {
    @Test fun `stale output is rejected after switch`() {
        val first = NoopPlugin("one")
        val second = NoopPlugin("two")
        val host = HostRuntime(1, HostTestRig(setOf(Capability.TextOutput)).environment)
        host.register(first); host.register(second)
        host.startSession(SessionId("s")); host.markSceneReady()
        assertEquals(ActivationResult.Activated, host.activate("one"))
        val oldGeneration = host.currentGeneration
        assertEquals(ActivationResult.Activated, host.switchTo("two"))
        assertFalse(host.accept(Assistance("h", SessionId("s"), oldGeneration, null, 1, "old", null, Long.MAX_VALUE)))
    }
    private class NoopPlugin(id: String) : PluginLifecycle {
        override val descriptor = PluginDescriptor(id, "1", 1, id, listOf(Capability.TextOutput), ConfigSchema(1))
        override fun activate(context: PluginContext) = Unit
        override fun handle(event: HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
    }
}
