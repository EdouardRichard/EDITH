package edith.host

import edith.contracts.*
import edith.plugin.*
import kotlin.test.*

class HostRuntimeRecoveryTest {
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
}
