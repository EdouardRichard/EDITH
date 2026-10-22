package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import kotlin.test.*

class HostRuntimeLifecycleTest {
    private val descriptor = PluginDescriptor("p1", "1.0", 1, "P1", listOf(Capability.TextOutput), ConfigSchema(1))
    private val plugin = RecordingPlugin(descriptor)

    @Test fun `activation before scene ready does not activate`() {
        val host = HostRuntime(apiVersion = 1)
        host.register(plugin)
        host.startSession(SessionId("s"))
        assertEquals(ActivationResult.SessionNotReady, host.activate("p1"))
        assertEquals(0, plugin.activations)
    }

    @Test fun `only one owner is active`() {
        val host = readyHost()
        assertEquals(ActivationResult.Activated, host.activate("p1"))
        val other = RecordingPlugin(PluginDescriptor("p2", "1", 1, "P2", listOf(Capability.TextOutput), ConfigSchema(1)))
        host.register(other)
        assertEquals(ActivationResult.OwnerBusy, host.activate("p2"))
    }

    private fun readyHost(): HostRuntime {
        val host = HostRuntime(1, HostTestRig(setOf(Capability.TextOutput)).environment)
        host.register(plugin)
        host.startSession(SessionId("s"))
        host.markSceneReady()
        return host
    }

    private class RecordingPlugin(override val descriptor: PluginDescriptor) : PluginLifecycle {
        var activations = 0
        var deactivations = 0
        override fun activate(context: PluginContext) { activations++ }
        override fun handle(event: HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) { deactivations++ }
    }
}
