package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import kotlin.test.*

class HostRuntimeRecoveryTest {
    @Test fun `explicit configure prevents nested lifecycle changes`() {
        val rig = HostTestRig()
        val states = mutableListOf<SessionState>()
        val plugin = object : PluginLifecycle, ConfiguredPlugin {
            override val descriptor = PluginDescriptor("config", "1", 1, "config", emptyList(), ConfigSchema(1))
            override fun validate(configuration: PluginConfiguration) { rig.host.stop(); states += rig.host.currentState }
            override fun configure(configuration: PluginConfiguration) { rig.host.stop(); states += rig.host.currentState }
            override fun activate(context: PluginContext) = Unit
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        rig.host.register(plugin)
        assertTrue(rig.host.configure("config", PluginConfiguration(1, emptyMap())))
        assertEquals(listOf(SessionState.Ready, SessionState.Ready), states)
        assertEquals(SessionState.Ready, rig.host.currentState)
        assertEquals(ActivationResult.Activated, rig.host.activate("config"))
    }

    @Test fun `validation and configuration cannot stop activation midway`() {
        val rig = HostTestRig()
        val plugin = object : PluginLifecycle, ConfiguredPlugin {
            override val descriptor = PluginDescriptor("reentrant", "1", 1, "probe", listOf(Capability.Audio), ConfigSchema(1))
            var context: PluginContext? = null
            val stopStates = mutableListOf<SessionState>()
            override fun validate(configuration: PluginConfiguration) {
                rig.host.stop()
                stopStates += rig.host.currentState
            }
            override fun configure(configuration: PluginConfiguration) {
                rig.host.stop()
                stopStates += rig.host.currentState
            }
            override fun activate(context: PluginContext) { this.context = context }
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        rig.host.register(plugin)
        assertEquals(ActivationResult.Activated, rig.host.activate("reentrant"))
        assertEquals(listOf(SessionState.Ready, SessionState.Ready), plugin.stopStates)
        assertNotNull(plugin.context)
        assertNotNull(rig.host.currentInputEpoch)
    }

    @Test fun `activation waits for audio and pause fences old epoch`() {
        val rig = HostTestRig.readyWithAudioPlugin()
        assertEquals(ActivationResult.Activated, rig.host.activate("probe"))
        assertEquals(SessionState.Ready, rig.host.currentState)
        val epoch = requireNotNull(rig.host.currentInputEpoch)
        rig.host.onAudioReceived(epoch)
        assertEquals(SessionState.Listening, rig.host.currentState)
        rig.host.pause()
        rig.host.onAudioReceived(epoch)
        assertEquals(SessionState.Paused, rig.host.currentState)
        assertEquals(listOf(epoch), rig.input.stopped)
        assertFailsWith<ContractError.Cancelled> { rig.plugin.context!!.services.audio().listen {} }
        assertEquals(ActivationResult.Activated, rig.host.resume())
        val next = requireNotNull(rig.host.currentInputEpoch)
        assertNotEquals(epoch, next)
        assertEquals(SessionState.Ready, rig.host.currentState)
        rig.host.onAudioReceived(epoch)
        assertEquals(SessionState.Ready, rig.host.currentState)
        rig.host.onAudioReceived(next)
        assertEquals(SessionState.Listening, rig.host.currentState)
    }

    @Test fun `configuration and provider gates reject invalid activation`() {
        val rig = HostTestRig()
        rig.plugin.requireValue = true
        assertFalse(rig.host.configure("probe", PluginConfiguration(2, emptyMap())))
        assertEquals(ActivationResult.Failed, rig.host.activate("probe"))
        assertEquals(SessionState.Paused, rig.host.currentState)
        assertTrue(rig.host.configure("probe", PluginConfiguration(1, mapOf("required" to "yes"))))
        assertEquals(ActivationResult.Activated, rig.host.activate("probe"))
        assertFalse(rig.host.configure("probe", PluginConfiguration(1, emptyMap())))
        val missing = HostRuntime(1)
        missing.register(ProbePlugin("p", listOf(Capability.Audio)))
        missing.startSession(SessionId("m")); missing.markSceneReady()
        assertEquals(ActivationResult.Failed, missing.activate("p"))
        assertEquals(SessionState.Paused, missing.currentState)
    }

    @Test fun `teardown exception still revokes clears and failed next plugin stays paused`() {
        val rig = HostTestRig()
        rig.host.activate("probe")
        val services = rig.plugin.context!!.services
        val old = rig.host.currentGeneration
        rig.plugin.failDeactivate = true
        val next = ProbePlugin("next", listOf(Capability.TextOutput)).also { it.failActivate = true }
        rig.host.register(next)
        assertEquals(ActivationResult.Failed, rig.host.switchTo("next"))
        assertEquals(SessionState.Paused, rig.host.currentState)
        assertFailsWith<ContractError.Cancelled> { services.audio().listen {} }
        assertTrue((SessionId("s") to old) in rig.output.clears)
        assertFalse(rig.host.accept(Assistance("h", SessionId("s"), old, null, 1, "test", null, 100)))
        assertEquals(1, rig.plugin.activations)
    }

    @Test fun `stop is idempotent and session replacement cleans previous owner`() {
        val rig = HostTestRig()
        rig.host.activate("probe")
        val old = requireNotNull(rig.host.currentInputEpoch)
        rig.host.startSession(SessionId("new"))
        assertEquals(listOf(old), rig.input.stopped)
        assertEquals(1, rig.plugin.deactivations)
        assertEquals(SessionState.BuildingScene, rig.host.currentState)
        rig.host.onAudioReceived(old)
        rig.host.stop(); rig.host.stop()
        assertEquals(SessionState.Idle, rig.host.currentState)
        assertNull(rig.host.currentInputEpoch)
    }

    @Test fun `non audio plugin never enters Listening and interrupt requires fresh epoch`() {
        val rig = HostTestRig()
        val text = ProbePlugin("text", listOf(Capability.TextOutput))
        rig.host.register(text)
        assertEquals(ActivationResult.Activated, rig.host.activate("text"))
        assertNull(rig.host.currentInputEpoch)
        assertEquals(SessionState.Ready, rig.host.currentState)
        rig.host.switchTo("probe")
        val old = requireNotNull(rig.host.currentInputEpoch)
        rig.host.interrupt(); rig.host.onAudioReceived(old)
        assertEquals(SessionState.Interrupted, rig.host.currentState)
        assertEquals(ActivationResult.SessionNotReady, rig.host.resume())
        rig.host.markSceneReady()
        assertEquals(SessionState.Ready, rig.host.currentState)
        assertNull(rig.host.currentInputEpoch)
        assertEquals(ActivationResult.Activated, rig.host.resume())
        assertNotEquals(old, rig.host.currentInputEpoch)
    }

    @Test fun `deactivation cannot resurrect resources during pause`() {
        val rig = HostTestRig()
        var attempted: ActivationResult? = null
        val plugin = object : PluginLifecycle {
            override val descriptor = PluginDescriptor("reentrant", "1", 1, "probe", listOf(Capability.Audio), ConfigSchema(1))
            override fun activate(context: PluginContext) = Unit
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) { attempted = rig.host.resume() }
        }
        rig.host.register(plugin); rig.host.activate("reentrant")
        rig.host.pause()
        assertEquals(ActivationResult.OwnerBusy, attempted)
        assertNull(rig.host.currentInputEpoch)
        assertEquals(1, rig.input.started.size)
        assertEquals(1, rig.input.stopped.size)
    }

    @Test fun `restarting same session identity rejects old output`() {
        val rig = HostTestRig()
        rig.host.activate("probe")
        val old = rig.host.currentGeneration
        rig.host.stop(); rig.host.startSession(SessionId("s")); rig.host.markSceneReady(); rig.host.activate("probe")
        assertFalse(rig.host.accept(Assistance("old", SessionId("s"), old, null, 1, "test", null, 100)))
    }

    @Test fun `executor preserves original registration validation exceptions`() {
        val host = HostRuntime(1)
        host.register(ProbePlugin("p", emptyList()))
        assertFailsWith<IllegalArgumentException> { host.register(ProbePlugin("p", emptyList())) }
        val incompatible = object : PluginLifecycle {
            override val descriptor = PluginDescriptor("bad", "1", 2, "bad", emptyList(), ConfigSchema(1))
            override fun activate(context: PluginContext) = Unit
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        assertFailsWith<IllegalArgumentException> { host.register(incompatible) }
    }

    @Test fun `host services submit through TTL arbiter and pause cancels display`() {
        val rig = HostTestRig()
        rig.host.activate("probe")
        val generation = rig.host.currentGeneration
        val sink = rig.plugin.context!!.services.textOutput()
        assertEquals(OutputSubmission.Accepted, sink.submit(Assistance("h", SessionId("s"), generation,
            "t", 1, "test", null, 100)))
        assertEquals(listOf("h"), rig.output.shown.map { it.first.hintId })
        rig.host.pause()
        rig.output.callbacks.single()(OutputStatus.Displayed)
        assertEquals(1, rig.output.handles.single().cancelCount)
        assertFailsWith<ContractError.Cancelled> { sink.submit(Assistance("late", SessionId("s"), generation,
            "t", 1, "test", null, 100)) }
    }
}
