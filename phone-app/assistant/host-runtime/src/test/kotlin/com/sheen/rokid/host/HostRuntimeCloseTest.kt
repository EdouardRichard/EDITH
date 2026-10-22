package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import kotlin.test.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HostRuntimeCloseTest {
    @Test fun concurrentCloseAllowsIdleObserverToReenterClose() {
        val host = HostRuntime(1)
        host.startSession(SessionId("session"))
        val reentered = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        var cancelledUpdates = 0
        val cancelled = host.observeState { cancelledUpdates++ }
        cancelled.cancel()
        val observation = host.observeState {
            if (it == SessionState.Idle) {
                host.close()
                reentered.countDown()
            }
        }
        repeat(2) {
            Thread({
                try { start.await(); host.close() } catch (error: Throwable) { failures += error }
                finally { completed.countDown() }
            }, "close-regression-$it").apply { isDaemon = true; start() }
        }
        start.countDown()
        assertTrue(reentered.await(2, TimeUnit.SECONDS), "Idle observer must finish reentrant close")
        assertTrue(completed.await(2, TimeUnit.SECONDS), "both external closes must finish")
        assertTrue(failures.isEmpty(), "concurrent close failures: $failures")
        observation.cancel()
        host.close()
        assertEquals(1, cancelledUpdates)
        assertEquals(SessionState.Idle, host.currentState)
        assertFailsWith<IllegalStateException> { host.startSession(SessionId("late")) }
        assertFailsWith<IllegalStateException> { host.observeState {} }
    }

    @Test fun providerStopObservesRevokedScope() {
        var resource: ResourceScope? = null
        var revokedWhenStopped = false
        val environment = object : HostEnvironment {
            override val clock = FakeClock()
            override val input = object : InputPort {
                override fun start(epoch: InputEpoch) = Unit
                override fun stop(epoch: InputEpoch) { revokedWhenStopped = resource?.isRevoked == true }
            }
            override val output = RecordingHintDevice()
            override val availableCapabilities = setOf(Capability.Audio)
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices { resource = scope; return FakeServices() }
        }
        val host = HostRuntime(1, environment)
        try {
            host.register(object : PluginLifecycle {
                override val descriptor = PluginDescriptor("audio", "1.0.0", 1, "audio", listOf(Capability.Audio), ConfigSchema(1))
                override fun activate(context: PluginContext) = Unit
                override fun handle(event: HostEvent) = Unit
                override fun deactivate(reason: DeactivationReason) = Unit
            })
            host.startSession(SessionId("session")); host.markSceneReady(); host.activate("audio")
            host.pause()
            assertTrue(revokedWhenStopped)
        } finally { host.close() }
    }

    @Test fun observationPublishesAdmittedInputAndCancellationFencesFurtherUpdates() {
        val input = RecordingInputPort()
        val environment = object : HostEnvironment {
            override val clock = FakeClock()
            override val input = input
            override val output = RecordingHintDevice()
            override val availableCapabilities = setOf(Capability.Audio)
            override fun services(scope: ResourceScope, granted: Set<Capability>) = FakeServices()
        }
        val host = HostRuntime(1, environment)
        val states = mutableListOf<SessionState>()
        try {
            host.register(object : PluginLifecycle {
                override val descriptor = PluginDescriptor("audio", "1.0.0", 1, "audio", listOf(Capability.Audio), ConfigSchema(1))
                override fun activate(context: PluginContext) = Unit
                override fun handle(event: HostEvent) = Unit
                override fun deactivate(reason: DeactivationReason) = Unit
            })
            val observation = host.observeState(states::add)
            host.startSession(SessionId("session"))
            host.markSceneReady()
            host.activate("audio")
            host.onAudioReceived(requireNotNull(host.currentInputEpoch))
            assertEquals(SessionState.Listening, host.currentState)
            assertEquals(listOf(SessionState.Idle, SessionState.BuildingScene, SessionState.Ready, SessionState.Listening), states)
            observation.cancel()
            host.pause()
            assertEquals(4, states.size)
        } finally { host.stop(); host.close() }
    }

    @Test fun closeStopsAndRevokesBeforeReleasingExecutorAndIsIdempotent() {
        val input = RecordingInputPort()
        var resource: ResourceScope? = null
        val handle = FakeCancellation()
        val environment = object : HostEnvironment {
            override val clock = FakeClock()
            override val input = input
            override val output = RecordingHintDevice()
            override val availableCapabilities = setOf(Capability.Audio)
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices {
                resource = scope
                scope.bind(handle)
                return FakeServices()
            }
        }
        val host = HostRuntime(1, environment)
        host.register(object : PluginLifecycle {
            override val descriptor = PluginDescriptor("audio", "1.0.0", 1, "audio", listOf(Capability.Audio), ConfigSchema(1))
            override fun activate(context: PluginContext) = Unit
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) = Unit
        })
        try {
            host.startSession(SessionId("session")); host.markSceneReady(); host.activate("audio")
            val epoch = requireNotNull(host.currentInputEpoch)
            host.close(); host.close()
            assertTrue(requireNotNull(resource).isRevoked)
            assertEquals(1, handle.cancelCount)
            assertEquals(listOf(epoch), input.stopped)
            assertEquals(SessionState.Idle, host.currentState)
            host.onAudioReceived(epoch)
            assertEquals(SessionState.Idle, host.currentState)
            assertFailsWith<IllegalStateException> { host.startSession(SessionId("late")) }
        } finally { runCatching { host.stop() }; host.close() }
    }
}
