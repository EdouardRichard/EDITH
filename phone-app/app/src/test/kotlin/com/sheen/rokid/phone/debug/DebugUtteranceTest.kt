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
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.EmptyHostServices
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.25 Task 15 (R82/D116), fix round 1: the debug-only text-turn injection seam, pinned at the entry the corpus uses.
 *
 * The seam exists because the acceptance corpus must not be rewritten by ASR: a fixed utterance delivered as a final
 * `TranscriptRevision` is the only way "the model did the wrong thing" stays distinguishable from "the recogniser heard
 * something else". What is specific to this instrument - and what these tests hold - is that it enters through
 * `HostRuntime.onEvent`, the same gate every production event passes, with the session/generation the host currently
 * accepts, a turn id the harness can identify afterwards, a monotonically increasing revision and a monotonic timestamp.
 * No test-only host entry is introduced.
 *
 * The receiver's own sequence is `inject(...)`: validate the route and the text, ask the production composition for the
 * wake (panel request + `WakeDetected`), then submit the final transcript. These tests drive that same function - the
 * `wake` argument is the composition's `emitWake`, exactly what the receiver passes - so the production branch is what
 * is under test rather than a test-only WAKE branch of a helper.
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

    /** The composition only needs a model reference here; the injection never asks it for a request. */
    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
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

    /**
     * The receiver path with the **production** `emitWake`, not a stand-in: a real composition (minimal wiring, but the
     * real method) submits the `WakeDetected` the plugin sees, then the seam submits the fixed final transcript. This is
     * the branch the receiver calls; the old test-only WAKE branch of the delivery helper is gone.
     */
    @Test
    fun theReceiverPathCallsTheProductionEmitWakeThenDeliversTheFinalTranscript() {
        val (host, plugin) = rig()
        val composition = HostCompositionRoot(host, MemoryProfileRepository(), InertModel(), null)
        try {
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "打开设置",
                rawRoute = null,
                turnId = "receiver-path-1",
                revision = 4L,
                nowNanos = 5_000L,
                wake = {
                    wakeCalls++
                    composition.emitWake(host, DebugUtteranceInjection.WAKE_WORD, 1.0)
                },
            )
            // A command result is the barrier that proves the host's dispatcher drained.
            host.currentState

            assertEquals(DebugUtteranceInjection.Outcome.ACCEPTED, injection.outcome)
            assertEquals(DebugUtteranceInjection.Route.WAKE, injection.route)
            assertEquals("the receiver path must ask for the wake exactly once", 1, wakeCalls)
            assertEquals("the wake must precede the transcript", 2, plugin.events.size)
            val wake = plugin.events[0] as WakeDetected
            assertEquals("伊迪丝", wake.word)
            val transcript = (plugin.events[1] as TranscriptEvent).revision
            assertTrue("the injected revision must be final", transcript.final)
            assertEquals("receiver-path-1", transcript.turnId)
            assertEquals(4L, transcript.revision)
            assertEquals("打开设置", transcript.text)
            assertEquals("the transcript must reach the same session as the wake",
                wake.envelope.sessionId, transcript.envelope.sessionId)
            assertEquals(wake.envelope.generation, transcript.envelope.generation)
        } finally {
            composition.close()
        }
    }

    @Test
    fun unknownRouteIsRefusedBeforeAnyWakeSideEffect() {
        val (host, plugin) = rig()
        try {
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "打开设置",
                rawRoute = "bogus",
                turnId = "turn-fixed",
                revision = 1L,
                nowNanos = 1_000L,
                wake = { wakeCalls++ },
            )
            assertEquals(DebugUtteranceInjection.Outcome.UNKNOWN_ROUTE, injection.outcome)
            assertNull("an unknown route has no route to report", injection.route)
            assertEquals("an unknown route must not open a panel", 0, wakeCalls)
            host.currentState
            assertTrue("an unknown route must not submit an event", plugin.events.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun blankRouteIsRefusedInsteadOfOpeningTheWakePanel() {
        val (host, plugin) = rig()
        try {
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "打开设置",
                rawRoute = "   ",
                turnId = "turn-fixed",
                revision = 1L,
                nowNanos = 1_000L,
                wake = { wakeCalls++ },
            )
            assertEquals("an explicitly blank route is a caller error, not the wake default",
                DebugUtteranceInjection.Outcome.UNKNOWN_ROUTE, injection.outcome)
            assertEquals("a blank route must not open a panel", 0, wakeCalls)
            host.currentState
            assertTrue(plugin.events.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun blankTextIsRefusedBeforeAnyWakeSideEffect() {
        val (host, plugin) = rig()
        try {
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "   ",
                rawRoute = "wake",
                turnId = "turn-fixed",
                revision = 1L,
                nowNanos = 1_000L,
                wake = { wakeCalls++ },
            )
            assertEquals(DebugUtteranceInjection.Outcome.BLANK_TEXT, injection.outcome)
            assertEquals("a blank utterance must not open a panel", 0, wakeCalls)
            host.currentState
            assertTrue("a blank utterance must not open a turn", plugin.events.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun noSessionIdentityIsRefusedBeforeAnyWakeSideEffect() {
        val host = HostRuntime(1)
        val plugin = RecordingPlugin()
        host.register(plugin)
        try {
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "打开设置",
                rawRoute = null,
                turnId = "turn-fixed",
                revision = 1L,
                nowNanos = 1_000L,
                wake = { wakeCalls++ },
            )
            assertEquals(DebugUtteranceInjection.Outcome.NO_SESSION, injection.outcome)
            assertEquals("nothing active means no panel request either", 0, wakeCalls)
            assertTrue("no event may be submitted without a session identity", plugin.events.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun helpRouteEntersAsFinalTranscriptThenTheProductionManualAction() {
        val (host, plugin) = rig()
        try {
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "打开设置",
                rawRoute = "help",
                turnId = "debug-help-fixed",
                revision = 8L,
                nowNanos = 1_000L,
                wake = { wakeCalls++ },
            )
            assertEquals(DebugUtteranceInjection.Outcome.ACCEPTED, injection.outcome)
            assertEquals("the help route does not fake a wake", 0, wakeCalls)
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
    fun transcriptRouteSubmitsOnlyTheFinalRevisionWithTheProductionIdentityAndTimestamps() {
        val (host, plugin) = rig()
        try {
            val identity = requireNotNull(host.currentIdentity())
            val before = System.nanoTime()
            var wakeCalls = 0
            val injection = DebugUtteranceInjection.inject(
                host = host,
                text = "把屏幕亮度调到最低",
                rawRoute = "transcript",
                turnId = "debug-turn-fixed",
                revision = 7L,
                nowNanos = before,
                wake = { wakeCalls++ },
            )
            assertEquals(DebugUtteranceInjection.Outcome.ACCEPTED, injection.outcome)
            assertEquals("the transcript route is not a wake", 0, wakeCalls)
            host.currentState

            val transcript = (plugin.events.single() as TranscriptEvent).revision
            assertTrue(transcript.final)
            assertEquals(identity.first, transcript.envelope.sessionId)
            assertEquals(identity.second, transcript.envelope.generation)
            assertEquals("debug-turn-fixed", transcript.turnId)
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
    fun theRevisionCounterIsStrictlyMonotonic() {
        val revisions = DebugUtteranceRevisions()
        assertEquals(1L, revisions.next())
        assertEquals(2L, revisions.next())
        assertEquals(3L, revisions.next())
        assertFalse("a revision is never reused", revisions.next() <= 3L)
    }

    /**
     * 0.25 Task 28 (R146): the acceptance harness verifies its input by hashing the frozen question itself and comparing
     * it with the `textSha256` field. The field must therefore be the plain UTF-8 SHA-256 in lower-case hex - the same
     * digest `sha256sum` prints for the bytes the harness broadcast - not a salted or locale-dependent summary.
     */
    @Test
    fun theUtteranceHashIsTheUtf8Sha256InLowerCaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DebugUtteranceLog.sha256("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DebugUtteranceLog.sha256(""))
        assertEquals("fbf5a9b60f376fdebef992190ba3952fbd2dae55b68f50b4e6c1859e428c0367",
            DebugUtteranceLog.sha256("把屏幕亮度调到最低"))
        // Stable across calls, and always the full 64-character hex width.
        assertEquals(DebugUtteranceLog.sha256("把屏幕亮度调到最低"), DebugUtteranceLog.sha256("把屏幕亮度调到最低"))
        assertEquals(64, DebugUtteranceLog.sha256("把屏幕亮度调到最低").length)
    }

    /**
     * The line may carry the digest and the character count but never the utterance: a fixed corpus question is still
     * text, and the evidence rule is that logcat never becomes a channel for it. Any CJK fragment counts as a leak.
     */
    @Test
    fun theLogFieldsCarryTheHashAndTheCountButNeverTheText() {
        val text = "把屏幕亮度调到最低"
        val fields = DebugUtteranceLog.fields(
            state = "accepted",
            route = "wake",
            turnId = "corpus-t2-1",
            revision = 7L,
            text = text,
            session = "Active",
            error = null,
        )
        assertTrue("the hash field is the whole point: $fields", fields.contains("textSha256=fbf5a9b60f376fdebef992190ba3952fbd2dae55b68f50b4e6c1859e428c0367"))
        assertTrue("the character count stays alongside it: $fields", fields.contains("chars=9"))
        assertTrue(fields.contains("turn=corpus-t2-1"))
        assertFalse("the utterance text must never reach the line: $fields", fields.contains(text))
        assertFalse("no fragment of it either: $fields", fields.contains("亮度"))
        assertFalse(fields.lowercase().contains("text="))
    }

    /**
     * The turn id is caller-chosen and ends up in logcat. `Char.isLetterOrDigit()` accepts CJK, so a turn id could have
     * carried utterance text into the log; the sanitiser must keep only the documented ASCII alphabet.
     */
    @Test
    fun theTurnIdSanitiserKeepsOnlyTheAsciiLogAlphabet() {
        assertEquals("turn-A_1", DebugUtteranceInjection.ident("turn-A_1"))
        assertEquals("xy9_z-", DebugUtteranceInjection.ident("\u5468\u6770\u4f26 x y\t9_z-"))
        assertEquals("none", DebugUtteranceInjection.ident("\u5468\u6770\u4f26"))
    }

    @Test
    fun theTurnIdSanitiserCapsLengthAndFallsBackWhenNothingSurvives() {
        assertEquals(64, DebugUtteranceInjection.ident("a".repeat(80)).length)
        assertEquals("none", DebugUtteranceInjection.ident(" !!!\n"))
    }
}
