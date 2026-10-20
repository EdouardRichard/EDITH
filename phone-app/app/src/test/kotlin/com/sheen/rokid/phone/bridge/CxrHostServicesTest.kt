package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.audio.SherpaAsrSession
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.AsrSession
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock as TestClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T18 increment 2: the plugin-facing services the App can genuinely answer, and the activation timing that makes them
 * usable.
 *
 * The rule under test keeps [CxrHostEnvironment.availableCapabilities] honest: **advertised and answerable must be the
 * same set**. The host refuses any plugin whose required capabilities are not advertised, so a capability claimed but
 * not answerable would let a plugin start and then fail confusingly later, while one answerable but not advertised
 * would keep a working plugin from ever starting.
 *
 * Two capabilities must stay absent until approved in their own right: `Audio` (advertising it would add a
 * microphone-start path, because the host starts `input` for audio-requiring plugins, and the delivery path has no
 * `AudioSubscription` provider - transcripts reach a plugin through `AsrSession`) and `Speaker` (a real extractor
 * would have to decode audio against the decision-labelled PCM format, and enrolment is not authorized).
 */
class CxrHostServicesTest {
    private class FakeClock(var nanos: Long = 1_000_000_000L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class RecordingInput : InputPort {
        val started = mutableListOf<InputEpoch>()
        override fun start(epoch: InputEpoch) {
            started += epoch
            throw ContractError.CapabilityMissing("audio unavailable")
        }
        override fun stop(epoch: InputEpoch) = Unit
    }

    /** A port that really accepts the epoch, so the arming path can be asserted without the deliberate refusal above. */
    private class AcceptingInput : InputPort {
        val started = mutableListOf<InputEpoch>()
        val stopped = mutableListOf<InputEpoch>()
        override fun start(epoch: InputEpoch) { started += epoch }
        override fun stop(epoch: InputEpoch) { stopped += epoch }
    }

    private class StubDevice : HintDevice {
        override fun show(assistance: Assistance, revision: Long,
            listener: (OutputStatus) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }

    private class StubModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class StubAsr : AsrSession {
        override fun listen(listener: (TranscriptRevision) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    /**
     * A stand-in for the wake-word provider. Task 14a made `Capability.WakeWord` real (the composition now supplies
     * its own configured registry), so the advertised set includes it and it must therefore be answerable here too.
     */
    private class StubWakeWords : com.sheen.rokid.plugin.WakeWordRegistry {
        private val words = mutableListOf<String>()
        override fun declare(words: List<String>): Boolean { this.words.clear(); this.words += words; return true }
        override fun current(): List<String> = words.toList()
    }

    /**
     * A stand-in for the Task 12 action provider. The environment only type-checks the provider here; the executor's
     * own behaviour is pinned in `com.sheen.rokid.phone.actions`.
     */
    private class StubActions : ActionExecutor {
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String): ActionResult =
            ActionResult(ActionOutcome.Refused, "UNKNOWN_ACTION")
        override fun cancel() = Unit
    }

    /** edith-0.3 Phase 2 Task 7: the Android perception provider's stand-in for the environment's answerability check. */
    private class StubPerception : PerceptionProvider {
        private fun snapshot(): PerceptionSnapshot = PerceptionSnapshot(
            snapshotId = "snap",
            capturedAtEpochMillis = 0L,
            coverage = PerceptionCoverage(
                screen = "ok",
                notifications = "ok",
                media = "ok",
                calendar = "ok",
                acoustic = "ok",
                clipboard = "ok",
                vision = "ok",
                glasses = "ok",
                device = "ok",
            ),
        )

        override fun beginTurn(): PerceptionSnapshot = snapshot()

        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot = snapshot()

        override fun afterAction(): PerceptionSnapshot = snapshot()

        override fun screenshot(): PerceptionScreenshotResult = PerceptionScreenshotResult.Disabled

        override fun endTurn() = Unit
    }

    private class StubStorage(val pluginId: String) : StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

    private fun scope(pluginId: String) = ResourceScope(pluginId, SessionId("s-1"), Generation(1))

    private fun environment(displayLive: Boolean, storage: Boolean = true, input: InputPort = RecordingInput(),
        asr: AsrSession? = null, actions: ActionExecutor? = null, wakeWords: com.sheen.rokid.plugin.WakeWordRegistry? = StubWakeWords(),
        perception: PerceptionProvider? = null) =
        CxrHostEnvironment(
            clock = FakeClock(),
            input = input,
            liveOutput = { if (displayLive) StubDevice() else null },
            scheduler = FakeScheduler(),
            providers = buildMap {
                put(Capability.Model, StubModel())
                asr?.let { put(Capability.Asr, it) }
                actions?.let { put(Capability.Actions, it) }
                wakeWords?.let { put(Capability.WakeWord, it) }
                perception?.let { put(Capability.Perception, it) }
            },
            storageFor = if (storage) { { id -> StubStorage(id) } } else null,
        )

    @Test
    fun everyAdvertisedCapabilityIsAnswerableAndNothingElseIs() {
        val env = environment(displayLive = true, asr = StubAsr(), actions = StubActions(), perception = StubPerception())
        val advertised = env.availableCapabilities

        // Task 11 ruling: with the engine chain wired the Asr provider is advertised; Audio stays out because there is
        // no AudioSubscription provider (transcripts arrive through AsrSession). Task 12 adds the action provider, so
        // Actions joins the set - and must therefore be answerable below. Task 14a adds the wake-word provider, which
        // is what finally lets `smart-assistant` (which declares WakeWord) pass the host's capability gate. Phase 2
        // Task 7 adds the perception port; a registered provider is advertised and answerable like every other.
        assertEquals(setOf(Capability.Model, Capability.Storage, Capability.TextOutput, Capability.Asr,
            Capability.Actions, Capability.WakeWord, Capability.Perception), advertised)
        val services = env.services(scope("smart-assistant"), advertised)
        advertised.forEach { capability ->
            assertNull("$capability is advertised, so it must be answerable",
                runCatching { services.require(capability) }.exceptionOrNull())
        }
        listOf(Capability.Audio, Capability.Speaker, Capability.SpeechOutput, Capability.Context)
            .forEach { capability ->
                assertFalse("$capability must not be advertised", capability in advertised)
                assertNotNull("$capability must not be answerable",
                    runCatching { services.require(capability) }.exceptionOrNull())
            }
    }

    @Test
    fun wakeWordIsRefusedRatherThanAdvertisedWhenTheCompositionSuppliesNoProvider() {
        val env = environment(displayLive = true, wakeWords = null)

        assertFalse(Capability.WakeWord in env.availableCapabilities)
        assertNotNull(runCatching { env.services(scope("smart-assistant"), setOf(Capability.WakeWord))
            .require(Capability.WakeWord) }.exceptionOrNull())
    }

    @Test
    fun perceptionIsRefusedRatherThanAdvertisedWhenTheCompositionSuppliesNoProvider() {
        val env = environment(displayLive = true)

        assertFalse(Capability.Perception in env.availableCapabilities)
        assertNotNull(runCatching { env.services(scope("smart-assistant"), setOf(Capability.Perception))
            .require(Capability.Perception) }.exceptionOrNull())
    }

    @Test
    fun storageIsRefusedRatherThanAdvertisedWhenNoNamespaceFactoryExists() {
        val env = environment(displayLive = false, storage = false)

        assertFalse(Capability.Storage in env.availableCapabilities)
        assertNotNull(runCatching { env.services(scope("smart-assistant"), setOf(Capability.Storage))
            .require(Capability.Storage) }.exceptionOrNull())
    }

    @Test
    fun theModelProviderIsTheCompositionClientAndStopsAnsweringOnceItsScopeIsRevoked() {
        val env = environment(displayLive = true)
        val resource = scope("smart-assistant")
        val services = env.services(resource, setOf(Capability.Model))

        assertTrue(services.require(Capability.Model) is ModelClient)
        resource.revoke()

        assertNotNull("a revoked plugin must not keep resolving services",
            runCatching { services.require(Capability.Model) }.exceptionOrNull())
    }

    @Test
    fun storageIsResolvedPerPluginSoNamespacesCannotShareState() {
        val env = environment(displayLive = false)
        val first = env.services(scope("smart-assistant"), setOf(Capability.Storage))
            .require(Capability.Storage) as StorageHandle
        val second = env.services(scope("interview-assistant"), setOf(Capability.Storage))
            .require(Capability.Storage) as StorageHandle

        first.write("key", byteArrayOf(1))

        assertNull("each plugin must get its own namespace", second.read("key"))
    }

    @Test
    fun theDisplayCapabilityFollowsTheLiveMappingRatherThanTheEnvironment() {
        var device: HintDevice? = null
        val env = CxrHostEnvironment(clock = FakeClock(), input = RecordingInput(), liveOutput = { device },
            scheduler = FakeScheduler(), providers = mapOf(Capability.Model to StubModel()),
            storageFor = { id -> StubStorage(id) })

        assertFalse(Capability.TextOutput in env.availableCapabilities)
        device = StubDevice()
        assertTrue(Capability.TextOutput in env.availableCapabilities)
    }

    // ---- activation timing -----------------------------------------------------------------------------------

    private class ShellPlugin(override val descriptor: PluginDescriptor) : PluginLifecycle {
        var activated = false
        override fun activate(context: PluginContext) { activated = true }
        override fun handle(event: HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) { activated = false }
    }

    private class FakeConnection(private val transport: InputPort? = null) : CxrConnection {
        override var ready = false
        val sent = mutableListOf<String>()
        var inbound: ((String) -> Unit)? = null
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String): Boolean { if (!ready) return false; sent += json; return true }
        override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
        override fun close() = Unit
        override fun inputPortOrNull(): InputPort? = transport
    }

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    /**
     * [input] is the environment's own poll loop. [transport] is the CXR connection's port, which Task 15c D1 also
     * arms (it is the call that opens the ingress), so a rig that asserts the loop was started must carry one.
     */
    private class ActivationRig(descriptor: PluginDescriptor, val input: InputPort = RecordingInput(),
        transport: InputPort = AcceptingInput()) : AutoCloseable {
        val clock = FakeClock()
        val connection = FakeConnection(transport)
        val plugin = ShellPlugin(descriptor)
        val link: CxrHintLink
        val root: HostCompositionRoot

        init {
            val holder = arrayOfNulls<CxrConnection>(1)
            link = CxrHintLink(clock, FakeScheduler(), sendJson = { json -> holder[0]?.sendCommand(json) ?: false })
            val environment = CxrHostEnvironment(clock = clock, input = input, liveOutput = link::liveOutput,
                scheduler = FakeScheduler(), providers = mapOf(Capability.Model to StubModel(),
                    Capability.WakeWord to StubWakeWords()),
                storageFor = { id -> StubStorage(id) })
            val host = HostRuntime(1, environment)
            root = HostCompositionRoot(host, MemoryProfileRepository(), UnusedModel(), environment,
                rokidConnectionFactory = { connection.also { holder[0] = it } }, hintLink = link)
            // Registered through the composition, which owns the descriptor its activation side effects are gated
            // on (Task 14a-fix item 4): registering straight on the host leaves it unknown and nothing arms.
            root.registerPlugin(plugin)
        }

        override fun close() = root.close()

        fun startAndReportReady(pluginId: String) {
            root.connectCxr(activity = Any())
            connection.ready = true
            root.onSceneReady()
            root.start(pluginId)
        }

        fun completeExchange() {
            val ping = connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.single()
            clock.nanos = ping.p0 + 10_000_000
            connection.inbound!!(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))
        }
    }

    private fun smartDescriptor() = PluginDescriptor("smart-assistant", "1.0.0", 1, "smart",
        listOf(Capability.Model, Capability.TextOutput), ConfigSchema(1))

    @Test
    fun aPluginNeedingTheDisplayIsRefusedBeforeTheMappingExistsAndActivatedOnceItDoes() {
        ActivationRig(smartDescriptor()).use { rig ->
            rig.startAndReportReady("smart-assistant")

            // The display is not real until the glasses answer PING, so the plugin cannot start yet - which is honest
            // rather than a half-started plugin.
            assertFalse("the plugin must not activate while the display is not real", rig.plugin.activated)
            assertEquals("CAPABILITY_MISSING", rig.root.state.value.errorCode)

            rig.completeExchange()

            assertTrue("the display became real, so the refused activation must be retried", rig.plugin.activated)
            assertNull("a successful activation clears the earlier refusal", rig.root.state.value.errorCode)
        }
    }

    @Test
    fun aPluginNeedingAnUnwiredCapabilityStaysRefusedEvenAfterTheDisplayBecomesReal() {
        // interview-assistant needs Speaker, which is deliberately unwired: a live display must not be turned into a
        // fake success, and the App must keep reporting the refusal.
        val descriptor = PluginDescriptor("interview-assistant", "1.0.0", 1, "interview",
            listOf(Capability.Speaker, Capability.Storage, Capability.Model, Capability.TextOutput), ConfigSchema(1))
        ActivationRig(descriptor).use { rig ->
            rig.startAndReportReady("interview-assistant")
            rig.completeExchange()

            assertFalse("a plugin needing an unwired capability must stay refused", rig.plugin.activated)
            assertEquals("CAPABILITY_MISSING", rig.root.state.value.errorCode)
        }
    }

    @Test
    fun theHostStartsNoMicrophonePathForAPluginThatDoesNotRequireAudio() {
        val input = RecordingInput()
        ActivationRig(smartDescriptor(), input).use { rig ->
            rig.startAndReportReady("smart-assistant")
            rig.completeExchange()

            assertTrue(rig.plugin.activated)
            assertTrue("the host must not start the microphone path for a non-audio plugin", input.started.isEmpty())
        }
    }

    @Test
    fun theAudioEpochIsArmedOnlyForThePluginThatActuallyDeclaresTheWakeWord() {
        // Task 14a arms the ears on activation. That arming must follow the *capability*, not "a plugin activated":
        // a display-only plugin has nothing to do with the wake path, and starting its epoch would put a poll loop on
        // the CXR ingress for a plugin that never listens - exactly the microphone-start side effect `Audio` is kept
        // unadvertised for.
        val displayOnly = PluginDescriptor("smart-assistant", "1.0.0", 1, "smart",
            listOf(Capability.Model, Capability.TextOutput), ConfigSchema(1))
        val input = AcceptingInput()
        ActivationRig(displayOnly, input).use { rig ->
            rig.startAndReportReady("smart-assistant")
            rig.completeExchange()

            assertTrue(rig.plugin.activated)
            assertTrue("a plugin without the WakeWord capability must not arm the ears", input.started.isEmpty())
        }
    }

    @Test
    fun theAudioEpochIsArmedForAWakeWordPluginAndStoppedOnTeardown() {
        val wakeDescriptor = PluginDescriptor("smart-assistant", "1.0.0", 1, "smart",
            listOf(Capability.Model, Capability.TextOutput, Capability.WakeWord), ConfigSchema(1))
        val input = AcceptingInput()
        ActivationRig(wakeDescriptor, input).use { rig ->
            rig.startAndReportReady("smart-assistant")
            rig.completeExchange()

            assertTrue(rig.plugin.activated)
            assertEquals("the declared wake-word plugin is what the ears exist for", 1, input.started.size)

            rig.root.stop()

            assertEquals(listOf(input.started.single()), input.stopped)
        }
    }

    /**
     * Task 15c D1's ordering, on the composed display path: the first activation is refused (no clock mapping, so no
     * display), which arms **neither** port; when the mapping goes live the existing retry re-runs the activation, and
     * by then the CXR connection exists - so that retry is what arms both the connection's port and the poll loop,
     * under one epoch. The device run's `armed=true state=Paused` retry line is exactly this step.
     */
    @Test
    fun theDisplayLiveRetryArmsTheConnectionPortAndThePollLoopTogether() {
        val wakeDescriptor = PluginDescriptor("smart-assistant", "1.0.0", 1, "smart",
            listOf(Capability.Model, Capability.TextOutput, Capability.WakeWord), ConfigSchema(1))
        val loop = AcceptingInput()
        val transport = AcceptingInput()
        ActivationRig(wakeDescriptor, loop, transport).use { rig ->
            rig.startAndReportReady("smart-assistant")

            assertFalse("the display is not real before the PONG, so the activation is refused", rig.plugin.activated)
            assertTrue("a refused activation must not arm the loop", loop.started.isEmpty())
            assertTrue("nor open the ingress through the connection's port", transport.started.isEmpty())

            rig.completeExchange()

            assertTrue("the display became real, so the refused activation must be retried", rig.plugin.activated)
            assertEquals("the retry arms the poll loop", 1, loop.started.size)
            assertEquals("and the port that opens the ingress it polls", 1, transport.started.size)
            assertEquals("both halves under the same epoch", loop.started.single(), transport.started.single())
        }
    }

    // ---- Task 11: the ASR provider and the audio input the App actually wires -----------------------------

    /** Speech is present on both scripted frames; the second is the endpoint that finishes the turn. */
    private class ScriptedVad : VadEngine {
        private val endpoints = ArrayDeque(listOf(false, true))
        override fun accept(frame: PcmFrame) =
            VadDecision(true, if (endpoints.isEmpty()) false else endpoints.removeFirst())
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class ScriptedAsr(private val partial: String, private val finalText: String) : StreamingAsrEngine {
        override fun accept(frame: PcmFrame): List<AsrHypothesis> = listOf(AsrHypothesis(partial, false))
        override fun finish() = AsrHypothesis(finalText, true)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentSpeaker : SpeakerEngine {
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun reset() = Unit
        override fun close() = Unit
    }

    /**
     * The environment exactly as `HostCompositionRoot` now builds it: the chain's polled ingress loop as the input
     * port and its `SherpaAsrSession` as the `Asr` provider.
     *
     * Stubbing either half would let the advertised set pass while the delivery path stayed disconnected, which is
     * the failure this test exists to catch.
     */
    private class AudioWiringRig {
        val clock = TestClock(0)
        val scheduler = ManualScheduler(clock)
        val epoch = InputEpoch(SessionId("s-1"), Generation(1), 0)
        val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "test-format")
        val ingress = PcmIngress(clock)
        val emitted = mutableListOf<HostEvent>()
        val chain = CxrAudioChain.assemble(ingress = { ingress }, framer = PcmFramer(verified, 20),
            vad = ScriptedVad(), asr = ScriptedAsr("你好", "你好世界"), speaker = SilentSpeaker(), wake = null,
            scheduler = scheduler, clock = clock, emit = { event -> emitted += event })
        val environment = CxrHostEnvironment(clock = clock, input = chain.input, liveOutput = { StubDevice() },
            scheduler = scheduler,
            providers = mapOf(Capability.Model to StubModel(), Capability.Asr to chain.asrSession,
                Capability.Actions to StubActions(), Capability.WakeWord to StubWakeWords(),
                Capability.Perception to StubPerception()),
            storageFor = { id -> StubStorage(id) })
    }

    @Test
    fun theAppWiresThePolledIngressLoopAndTheProductionAsrSession() {
        val rig = AudioWiringRig()
        try {
            assertEquals(setOf(Capability.Model, Capability.Storage, Capability.TextOutput, Capability.Asr,
                Capability.Actions, Capability.WakeWord, Capability.Perception), rig.environment.availableCapabilities)
            assertTrue("the input port must be the polled audio loop, not the refusing placeholder",
                rig.environment.input === rig.chain.input)

            val resource = scope("smart-assistant")
            val services = rig.environment.services(resource, rig.environment.availableCapabilities)
            rig.environment.availableCapabilities.forEach { capability ->
                assertNull("$capability is advertised, so it must be answerable",
                    runCatching { services.require(capability) }.exceptionOrNull())
            }
            assertTrue("the granted Asr provider must be an AsrSession",
                services.require(Capability.Asr) is AsrSession)

            val handle = (services.require(Capability.Asr) as AsrSession).listen { }
            handle.cancel()
            resource.revoke()
        } finally {
            rig.chain.close()
        }
    }

    @Test
    fun aCxrChunkBecomesAPluginVisibleTranscriptThroughTheAssembledChain() {
        val rig = AudioWiringRig()
        try {
            val received = mutableListOf<TranscriptRevision>()
            rig.chain.asrSession.listen { received += it }
            rig.ingress.open(rig.epoch, rig.verified)
            rig.ingress.offer(rig.epoch, ByteArray(640))
            rig.ingress.offer(rig.epoch, ByteArray(640))

            rig.chain.input.start(rig.epoch)
            rig.clock.advance(20_000_000); rig.scheduler.runDue()
            rig.clock.advance(20_000_000); rig.scheduler.runDue()
            rig.chain.input.stop(rig.epoch)

            assertEquals(listOf("你好", "你好世界"), received.map { it.text })
            assertEquals(listOf(false, true), received.map { it.final })
            assertEquals("the host's own delivery must see the same revisions",
                listOf("你好", "你好世界"),
                rig.emitted.filterIsInstance<TranscriptEvent>().map { it.revision.text })
        } finally {
            rig.chain.close()
        }
    }
}
