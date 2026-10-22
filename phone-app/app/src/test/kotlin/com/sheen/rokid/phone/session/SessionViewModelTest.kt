package com.sheen.rokid.phone.session
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.*
import com.sheen.rokid.host.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.*
import org.junit.Assert.*
import org.junit.Test

class SessionViewModelTest {
    @Test fun recreatedServiceCannotResumeWithoutOwningForeground() = PhoneSessionRig().use { rig ->
        rig.start()
        rig.root.pause()
        assertFalse(rig.root.dispatch("edith.RESUME", foregroundActive = false))
        assertEquals(SessionState.Paused, rig.root.state.value.session)
        assertEquals(1, rig.input.started.size)
        assertEquals("FOREGROUND_UI_REQUIRED", rig.root.state.value.errorCode)
    }

    @Test fun closeContinuesAfterModelFailureAndReleasesRemainingResourcesExactlyOnce() {
        var modelClosed = 0
        var providerClosed = 0
        val model = object : ModelClient, AutoCloseable {
            override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle = error("not used")
            override fun close() { modelClosed++; throw IllegalStateException("synthetic close failure") }
        }
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null,
            listOf(AutoCloseable { providerClosed++ }))
        runCatching { root.close() }
        root.close()
        assertEquals(1, modelClosed)
        assertEquals(1, providerClosed)
        assertEquals(SessionState.Idle, root.state.value.session)
    }

    @Test fun foregroundAdmissionPrecedesHostSessionStart() = PhoneSessionRig().use { rig ->
        var called = false
        val token = rig.root.authorizeStart("p2-diagnostic")
        assertTrue(rig.root.dispatch("edith.START", token, beforeStart = {
            called = true
            assertEquals(SessionState.Idle, rig.root.host.currentState)
            assertNull(rig.root.host.currentInputEpoch)
        }))
        assertTrue(called)
        assertEquals(SessionState.BuildingScene, rig.root.state.value.session)
    }

    @Test fun foregroundFailureNeverStartsSessionOrInput() = PhoneSessionRig().use { rig ->
        val token = rig.root.authorizeStart("p2-diagnostic")
        assertThrows(SecurityException::class.java) {
            rig.root.dispatch("edith.START", token, beforeStart = { throw SecurityException("synthetic denial") })
        }
        rig.root.onSceneReady()
        assertEquals(SessionState.Idle, rig.root.state.value.session)
        assertTrue(rig.input.started.isEmpty())
        assertFalse(rig.root.dispatch("edith.START", token))
    }

    @Test fun expiredStartGrantCannotAdmitSession() = PhoneSessionRig().use { rig ->
        val token = rig.root.authorizeStart("p2-diagnostic")
        rig.clock.advance(5_000_000_000L)
        assertFalse(rig.root.dispatch("edith.START", token))
        assertEquals(SessionState.Idle, rig.root.state.value.session)
    }

    @Test fun deniedStartStopsAnExistingOwnerBeforeReportingPermissionFailure() = PhoneSessionRig().use { rig ->
        rig.start()
        val resource = requireNotNull(rig.scope)
        val token = rig.root.authorizeStart("p2-diagnostic")
        assertFalse(rig.root.dispatch("edith.START", token, permissionGranted = false))
        assertTrue(resource.isRevoked)
        assertNull(rig.root.host.currentInputEpoch)
        assertEquals(SessionState.Idle, rig.root.state.value.session)
    }

    @Test fun stopAndCloseInvalidateAnUnconsumedStartGrant() = PhoneSessionRig().use { rig ->
        val stopped = rig.root.authorizeStart("p2-diagnostic")
        rig.root.stop()
        assertFalse(rig.root.dispatch("edith.START", stopped))
        val closed = rig.root.authorizeStart("p2-diagnostic")
        rig.root.close()
        assertFalse(rig.root.dispatch("edith.START", closed))
        rig.root.onSceneReady()
        assertTrue(rig.input.started.isEmpty())
    }

    @Test fun startDoesNotClaimListeningBeforeAudio() = PhoneSessionRig().use { rig ->
        rig.viewModel.start("p2-diagnostic")
        rig.root.onSceneReady()
        assertEquals(SessionState.Ready, rig.viewModel.state.value.session)
        rig.viewModel.pause()
        assertEquals(SessionState.Paused, rig.viewModel.state.value.session)
    }

    @Test fun acceptedInputUpdatesUiButOldEpochCannotResumePausedSession() = PhoneSessionRig().use { rig ->
        rig.start()
        assertEquals(SessionState.Ready, rig.viewModel.state.value.session)
        val epoch = requireNotNull(rig.root.host.currentInputEpoch)
        rig.root.host.onAudioReceived(epoch)
        assertEquals(SessionState.Listening, rig.root.host.currentState)
        assertEquals(SessionState.Listening, rig.viewModel.state.value.session)
        rig.viewModel.pause()
        rig.root.host.onAudioReceived(epoch)
        assertEquals(SessionState.Paused, rig.root.host.currentState)
        assertEquals(SessionState.Paused, rig.viewModel.state.value.session)
    }

    @Test fun recreatedViewModelObservesOwnerWithoutRestartingInput() = PhoneSessionRig().use { rig ->
        rig.start()
        val recreated = SessionViewModel(rig.root)
        assertEquals(SessionState.Ready, recreated.state.value.session)
        assertEquals(1, rig.input.started.size)
        recreated.pause()
        assertEquals(SessionState.Paused, rig.viewModel.state.value.session)
    }

    @Test fun pauseRevokesScopeAndResumeUsesNewEpoch() = PhoneSessionRig().use { rig ->
        rig.start()
        assertEquals(SessionState.Ready, rig.viewModel.state.value.session)
        val oldScope = requireNotNull(rig.scope)
        val oldEpoch = requireNotNull(rig.root.host.currentInputEpoch)
        rig.viewModel.pause()
        assertTrue(oldScope.isRevoked)
        assertEquals(listOf(oldEpoch), rig.input.stopped)
        rig.viewModel.resume()
        assertEquals(SessionState.Ready, rig.viewModel.state.value.session)
        assertNotEquals(oldEpoch, rig.root.host.currentInputEpoch)
    }

    @Test fun stopRevokesResourcesAndCloseIsIdempotent() = PhoneSessionRig().use { rig ->
        rig.start()
        assertEquals(SessionState.Ready, rig.viewModel.state.value.session)
        val resource = requireNotNull(rig.scope)
        rig.viewModel.stop()
        assertEquals(SessionState.Idle, rig.viewModel.state.value.session)
        assertTrue(resource.isRevoked)
        assertNull(rig.root.host.currentInputEpoch)
        rig.root.close()
        rig.root.close()
        assertEquals(SessionState.Idle, rig.root.state.value.session)
    }

    @Test fun serviceNeedsFreshExplicitStartAndRejectsRedelivery() = PhoneSessionRig().use { rig ->
        assertFalse(rig.root.dispatch(null))
        assertFalse(rig.root.dispatch("edith.START"))
        val stale = rig.root.authorizeStart("p2-diagnostic")
        assertFalse(rig.root.dispatch("edith.START", stale, redelivered = true))
        assertFalse(rig.root.dispatch("edith.START", stale))
        val token = rig.root.authorizeStart("p2-diagnostic")
        assertTrue(rig.root.dispatch("edith.START", token))
        rig.root.onSceneReady()
        assertEquals(SessionState.Ready, rig.root.state.value.session)
        assertFalse(rig.root.dispatch("edith.START", token))
        assertEquals(1, rig.input.started.size)
    }

    @Test fun permissionDenialCannotStartInput() = PhoneSessionRig().use { rig ->
        val token = rig.root.authorizeStart("p2-diagnostic")
        assertFalse(rig.root.dispatch("edith.START", token, permissionGranted = false))
        rig.root.onSceneReady()
        assertEquals("BLUETOOTH_PERMISSION_DENIED", rig.root.state.value.errorCode)
        assertTrue(rig.input.started.isEmpty())
    }

    @Test fun selectionDuringPauseDoesNotResumeInput() = PhoneSessionRig().use { rig ->
        rig.start()
        rig.viewModel.pause()
        rig.viewModel.selectPlugin("other")
        assertEquals(SessionState.Paused, rig.viewModel.state.value.session)
        assertEquals("other", rig.viewModel.state.value.selectedPluginId)
        assertEquals(1, rig.input.started.size)
        rig.viewModel.resume()
        assertEquals(2, rig.input.started.size)
    }

    @Test fun productionMissingProvidersAreExplicitAndNeverListening() {
        val root = HostCompositionRoot.create(nullContext())
        try {
            SessionViewModel(root).start("smart-assistant")
            // T18 gave the production assembly a real host environment, so the refusal now happens where it belongs -
            // at activation, once the transport reports the scene ready - rather than at session start. The property
            // this test exists for is unchanged: nothing is activated and nothing is listening.
            root.onSceneReady()
            assertEquals(SessionState.Paused, root.state.value.session)
            assertEquals("CAPABILITY_MISSING", root.state.value.errorCode)
            // Providers that are genuinely wired are no longer reported missing; the ones that are not still are.
            assertTrue("Audio is not wired and must be reported missing",
                root.state.value.capabilityReasons.containsKey(Capability.Audio))
            assertTrue("Speaker is not wired and must be reported missing",
                root.state.value.capabilityReasons.containsKey(Capability.Speaker))
            assertFalse("the model client is wired, so it must not be reported missing",
                root.state.value.capabilityReasons.containsKey(Capability.Model))
            // Task 12: the L0/L1 action provider is built by the production composition, so the App must stop
            // reporting the capability as missing - the same advertised/answerable rule the environment tests pin.
            assertFalse("the action provider is wired, so it must not be reported missing",
                root.state.value.capabilityReasons.containsKey(Capability.Actions))
        } finally { root.close() }
    }

    private fun nullContext(): android.content.Context = android.content.ContextWrapper(null)
}

private class PhoneSessionRig : AutoCloseable {
    val input = RecordingInputPort()
    val clock = FakeClock()
    var scope: ResourceScope? = null
    private val environment = object : HostEnvironment {
        override val clock = this@PhoneSessionRig.clock
        override val input = this@PhoneSessionRig.input
        override val output = RecordingHintDevice()
        override val availableCapabilities = setOf(Capability.Audio)
        override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices {
            this@PhoneSessionRig.scope = scope
            return ScopedHostServices(scope, granted, mapOf(Capability.Audio to object : AudioSubscription {
                override fun listen(listener: (AudioSegment) -> Unit): CancellationHandle = FakeCancellation()
            }), FakeTaskScope())
        }
    }
    private fun plugin(id: String) = object : PluginLifecycle {
        override val descriptor = PluginDescriptor(id, "1.0.0", 1, id, listOf(Capability.Audio), ConfigSchema(1))
        override fun activate(context: PluginContext) = Unit
        override fun handle(event: HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
    }
    private val host = HostRuntime(1, environment).apply { register(plugin("p2-diagnostic")); register(plugin("other")) }
    val root = HostCompositionRoot(host, MemoryProfileRepository(), object : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle = error("not used")
    }, environment)
    val viewModel = SessionViewModel(root)
    fun start() { viewModel.start("p2-diagnostic"); root.onSceneReady() }
    override fun close() { root.close(); runCatching { host.stop() } }
}
