package com.sheen.rokid.phone.debug

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.host.ActivationResult
import com.sheen.rokid.host.HostEnvironment
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.EmptyHostServices
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.25 Task 15 (R82/D116): the debug-only text-turn injection seam, pinned at the entry the corpus actually uses.
 *
 * The seam exists because the acceptance corpus must not be rewritten by ASR: a fixed utterance delivered as a final
 * `TranscriptRevision` is the only way "the model did the wrong thing" stays distinguishable from "the recogniser heard
 * something else". What is specific to this instrument - and what these tests hold - is that it enters through
 * `HostRuntime.onEvent`, the same gate every production event passes, with the session/generation the host currently
 * accepts, a turn id the harness can identify afterwards, a monotonically increasing revision and a monotonic timestamp.
 * No test-only host entry is introduced.
 */
class DebugUtteranceTest {

    private class RecordingPlugin : PluginLifecycle {
        override val descriptor = PluginDescriptor(
            "debug-utterance-probe", "1", 1, "Debug utterance probe",
            listOf(Capability.WakeWord, Capability.Asr), ConfigSchema(1),
        )
        val events = mutableListOf<HostEvent>()
        override fun activate(context: PluginContext) = Unit
        override fun handle(event: HostEvent) { events += event }
        override fun deactivate(reason: DeactivationReason) = Unit
    }

    /** The two capabilities this seam's events are gated on; everything else is irrelevant to the injection contract. */
    private class InjectionEnvironment : HostEnvironment {
        override val clock = FakeClock()
        override val input = object : InputPort {
            override fun start(epoch: InputEpoch) = Unit
            override fun stop(epoch: InputEpoch) = Unit
        }
        override val output = object : HintDevice {
            override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
                listener(OutputStatus.Accepted)
                return object : CancellationHandle { override fun cancel() = Unit }
            }
            override fun clear(sessionId: SessionId, generation: Generation) = Unit
        }
        override val availableCapabilities = setOf(Capability.WakeWord, Capability.Asr)
        override fun services(scope: ResourceScope, granted: Set<Capability>) = EmptyHostServices
    }

    private fun rig(): Pair<HostRuntime, RecordingPlugin> {
        val host = HostRuntime(1, InjectionEnvironment())
        val plugin = RecordingPlugin()
        host.register(plugin)
        host.startSession(SessionId("s-debug-utterance"))
        host.markSceneReady()
        assertEquals(ActivationResult.Activated, host.activate(plugin.descriptor.pluginId))
        return host to plugin
    }

    @Test
    fun wakeRouteEntersAsWakeThenFinalTranscriptThroughTheProductionHostEntry() {
        val (host, plugin) = rig()
        try {
            val identity = requireNotNull(host.currentIdentity())
            val before = System.nanoTime()
            val outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = "把屏幕亮度调到最低",
                route = DebugUtteranceInjection.Route.WAKE,
                turnId = "debug-turn-fixed",
                revision = 7L,
                nowNanos = before,
            )
            assertEquals(DebugUtteranceInjection.Outcome.ACCEPTED, outcome)
            // Reading a command result is the barrier that proves the host's dispatcher drained.
            host.currentState

            assertEquals(2, plugin.events.size)
            val wake = plugin.events[0] as WakeDetected
            assertEquals("伊迪丝", wake.word)
            assertEquals(identity.first, wake.envelope.sessionId)
            assertEquals(identity.second, wake.envelope.generation)

            val transcript = (plugin.events[1] as TranscriptEvent).revision
            assertTrue("the injected revision must be final", transcript.final)
            assertEquals("the injected revision must reach the same session", identity.first, transcript.envelope.sessionId)
            assertEquals(identity.second, transcript.envelope.generation)
            assertEquals("the turn id must be the fixed judgeable one", "debug-turn-fixed", transcript.turnId)
            assertEquals(7L, transcript.revision)
            assertEquals("把屏幕亮度调到最低", transcript.text)
            assertTrue("the timestamp must be the monotonic-clock reading", transcript.startNanos >= before)
            assertTrue(transcript.endNanos >= transcript.startNanos)
            assertTrue("the submitted event's envelope timestamps must satisfy the envelope invariant",
                transcript.envelope.endNanos >= transcript.envelope.startNanos)
        } finally {
            host.close()
        }
    }

    @Test
    fun theCompositionWakeHookReplacesTheSyntheticWakeAndRunsBeforeTheTranscript() {
        val (host, plugin) = rig()
        try {
            val calls = mutableListOf<String>()
            val outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = "打开设置",
                route = DebugUtteranceInjection.Route.WAKE,
                turnId = "debug-hook-fixed",
                revision = 9L,
                nowNanos = 2_000L,
                wake = { calls += "wake" },
            )
            assertEquals(DebugUtteranceInjection.Outcome.ACCEPTED, outcome)
            host.currentState
            // The composition's own emission sink owns the panel request; the seam must not also submit a bare
            // WakeDetected, or the plugin would open two turns for one injection.
            assertEquals(listOf("wake"), calls)
            assertEquals(1, plugin.events.size)
            val transcript = (plugin.events.single() as TranscriptEvent).revision
            assertEquals("debug-hook-fixed", transcript.turnId)
            assertEquals(9L, transcript.revision)
        } finally {
            host.close()
        }
    }

    @Test
    fun helpRouteEntersAsFinalTranscriptThenTheProductionManualAction() {
        val (host, plugin) = rig()
        try {
            val outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = "打开设置",
                route = DebugUtteranceInjection.Route.HELP,
                turnId = "debug-help-fixed",
                revision = 8L,
                nowNanos = 1_000L,
            )
            assertEquals(DebugUtteranceInjection.Outcome.ACCEPTED, outcome)
            host.currentState

            assertEquals(2, plugin.events.size)
            val transcript = (plugin.events[0] as TranscriptEvent).revision
            assertTrue(transcript.final)
            assertEquals("debug-help-fixed", transcript.turnId)
            val action = plugin.events[1] as UserAction
            assertEquals(UserAction.Action.Help, action.action)
            assertEquals(transcript.envelope.sessionId, action.envelope.sessionId)
            assertEquals(transcript.envelope.generation, action.envelope.generation)
        } finally {
            host.close()
        }
    }

    @Test
    fun noSessionIdentityRefusesBeforeAnyEventAndReportsTheReason() {
        val host = HostRuntime(1)
        val plugin = RecordingPlugin()
        host.register(plugin)
        try {
            val outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = "打开设置",
                route = DebugUtteranceInjection.Route.WAKE,
                turnId = "debug-turn-fixed",
                revision = 1L,
                nowNanos = 1_000L,
            )
            assertEquals(DebugUtteranceInjection.Outcome.NO_SESSION, outcome)
            assertTrue("no event may be submitted without a session identity", plugin.events.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun blankTextIsRefusedBeforeAnyEventReachesTheHost() {
        val (host, plugin) = rig()
        try {
            val outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = "   ",
                route = DebugUtteranceInjection.Route.WAKE,
                turnId = "debug-turn-fixed",
                revision = 1L,
                nowNanos = 1_000L,
            )
            assertEquals(DebugUtteranceInjection.Outcome.BLANK_TEXT, outcome)
            host.currentState
            assertTrue("a blank utterance must not open a turn", plugin.events.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun theRevisionCounterIsStrictlyMonotonic() {
        val revisions = DebugUtteranceRevisions()
        assertEquals(1L, revisions.next())
        assertEquals(2L, revisions.next())
        assertEquals(3L, revisions.next())
        assertFalse("a revision is never reused", revisions.next() <= 3L)
    }
}
