package edith.phone

import edith.audio.AsrHypothesis
import edith.audio.PcmFramer
import edith.audio.PcmFrame
import edith.audio.PcmOffer
import edith.audio.SpeakerEngine
import edith.audio.StreamingAsrEngine
import edith.audio.VadDecision
import edith.audio.VadEngine
import edith.audio.WakeWordDetector
import edith.contracts.Assistance
import edith.contracts.AudioFormat
import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.HostEvent
import edith.contracts.InputEpoch
import edith.contracts.OutputStatus
import edith.contracts.PcmEncoding
import edith.contracts.SampleRange
import edith.contracts.SessionId
import edith.contracts.SessionState
import edith.contracts.SpeakerAssessment
import edith.contracts.UserAction
import edith.contracts.VerifiedPcm
import edith.contracts.WakeDetected
import edith.host.HostRuntime
import edith.host.ResourceScope
import edith.phone.model.TestBlobs
import edith.phone.storage.AndroidProfileRepository
import edith.phone.storage.KeystoreVault
import edith.plugin.ActionDescriptor
import edith.plugin.ActionExecutor
import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.AsrSession
import edith.plugin.CancellationHandle
import edith.plugin.ConfirmationChannel
import edith.plugin.Capability
import edith.plugin.CapabilityVerification
import edith.plugin.ConfigSchema
import edith.plugin.ConfiguredPlugin
import edith.plugin.ContextSignals
import edith.plugin.ContextSnapshot
import edith.plugin.DeactivationReason
import edith.plugin.HintDevice
import edith.plugin.InputModality
import edith.plugin.InputPort
import edith.plugin.ModelClient
import edith.plugin.ModelProfile
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.OutputModality
import edith.plugin.PluginConfiguration
import edith.plugin.PluginContext
import edith.plugin.PluginDescriptor
import edith.plugin.PluginLifecycle
import edith.plugin.SecretHandle
import edith.plugin.SpeakerWindow
import edith.plugin.StorageHandle
import edith.plugins.diagnostic.DiagnosticHintPlugin
import edith.plugins.smart.SmartAssistantPlugin
import edith.rokid.CxrAuthorizationOutcome
import edith.storage.MemoryProfileRepository
import edith.storage.ProfileRepository
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import edith.testsupport.RecordingInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Task 14a: the passive loop is only real once the App arms it.
 *
 * Everything this file drives already existed before this task - the wake detector, the audio chain, the host's
 * capability gate, the smart plugin - but nothing connected them: the plugin was registered with defaults, no
 * `WakeWord` provider existed, the detector's `emit` was a no-op, and the audio epoch was never started. These tests
 * are the connection, where the pieces actually meet.
 */
class PassiveLoopWiringTest {

    private val session = SessionId("s-1")

    private fun profile(id: String) = ModelProfile(id, 3, "Synthetic $id", "Synthetic",
        "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
        object : SecretHandle { override val id = "draft-placeholder" },
        4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())

    // ---- fakes ---------------------------------------------------------------------------------------------------

    /** Speech on every frame; the wake layer only ever sees frames the VAD admits. */
    private class SpeechVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(true, false)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentAsr : StreamingAsrEngine {
        override fun accept(frame: PcmFrame): List<AsrHypothesis> = emptyList()
        override fun finish() = AsrHypothesis("", true)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentSpeaker : SpeakerEngine {
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class NoActions : ActionExecutor {
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Refused, "UNKNOWN_ACTION")
        override fun cancel() = Unit
    }

    /**
     * P3.3: the smart plugin declares `Capability.Confirmation`, so these rigs must advertise it or the host refuses to
     * activate the plugin (`CAPABILITY_MISSING`) - which is exactly what the host's capability gate is for. This fake
     * presents nothing and answers nothing: these tests are about the wake path, not about confirming.
     */
    private class NoConfirmations : ConfirmationChannel {
        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class LiveDisplay : HintDevice {
        override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }

    private class InertModel : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            requests += request
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    private class StubAsrSession : AsrSession {
        override fun listen(listener: (edith.contracts.TranscriptRevision) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    /**
     * The provider that answers the smart plugin's declared `Context` capability in these rigs (P3.2 Task 8): the
     * plugin's proactive path runs on perception events, so a rig that activates it must provide the capability the
     * way `HostCompositionRoot.create` now does. It reports nothing - these tests are about the passive loop.
     */
    private class EmptyContextSignals : ContextSignals {
        override fun snapshot() = ContextSnapshot(0L, emptyMap())
        override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class StubStorage : StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

    /** A wake engine that reports one configured word from one frame, so the real detector can be driven. */
    private class OneWordEngine(private val word: String) : edith.audio.WakeWordEngine {
        override fun accept(frame: PcmFrame): List<String> = listOf(word)
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** Records every delivery the wrapped plugin received, so a dropped event is distinguishable from a delivered one. */
    private class RecordingSmart(private val profile: ModelProfile) : PluginLifecycle {
        val plugin = SmartAssistantPlugin(
            clock = FakeClock(0),
            scheduler = ManualScheduler(FakeClock(0)),
            profileLookup = { id -> if (id == profile.id) profile else null },
        )
        val handled = CopyOnWriteArrayList<HostEvent>()
        override val descriptor = plugin.descriptor
        override fun activate(context: PluginContext) = plugin.activate(context)
        override fun deactivate(reason: DeactivationReason) = plugin.deactivate(reason)
        override fun handle(event: HostEvent) { handled += event; plugin.handle(event) }
    }

    /** A repository that hands back the profile exactly as given; `MemoryProfileRepository` renumbers the revision. */
    private class FixedProfiles(private val saved: List<ModelProfile>) : ProfileRepository {
        constructor(profile: ModelProfile) : this(listOf(profile))
        override fun list(): List<ModelProfile> = saved
        override fun get(id: String): ModelProfile? = saved.firstOrNull { it.id == id }
        override fun save(profile: ModelProfile) = profile
        override fun delete(id: String) = Unit
        override fun verify(id: String, result: CapabilityVerification) = Unit
        override fun listenChanges(listener: (String) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    /**
     * The real `SmartAssistantPlugin` behind a recorder, so a test can read the configuration the composition
     * actually gave it while every parse, activation and event still happens in the shipped plugin. It also keeps
     * every delivery, which is what a help request (D2) is asserted through.
     */
    private class RecordingConfiguredSmart(private val real: SmartAssistantPlugin) : PluginLifecycle, ConfiguredPlugin {
        val configurations = CopyOnWriteArrayList<PluginConfiguration>()
        val handled = CopyOnWriteArrayList<HostEvent>()
        override val descriptor = real.descriptor
        override fun validate(configuration: PluginConfiguration) = real.validate(configuration)
        override fun configure(configuration: PluginConfiguration) {
            configurations += configuration
            real.configure(configuration)
        }
        override fun activate(context: PluginContext) = real.activate(context)
        override fun deactivate(reason: DeactivationReason) = real.deactivate(reason)
        override fun handle(event: HostEvent) { handled += event; real.handle(event) }
    }

    /**
     * A CXR connection double that carries a real transport port, so the composition's arming can be observed
     * (Task 15c D1). `port = null` is the connection that exists but is not a transport - the same "no port" answer
     * the shipping default gives.
     */
    private class FakeTransportConnection(private val port: InputPort?) : CxrConnection {
        override var ready = true
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String) = true
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
        override fun inputPortOrNull(): InputPort? = port
    }

    /** One port that records the epochs it was handed **and** the order it was started/stopped in. */
    private class OrderedPort(private val name: String, private val order: MutableList<String>) : InputPort {
        val recorder = RecordingInputPort()
        override fun start(epoch: InputEpoch) { order += "start:$name"; recorder.start(epoch) }
        override fun stop(epoch: InputEpoch) { order += "stop:$name"; recorder.stop(epoch) }
    }

    /**
     * The debug diagnostic text plugin's **key set**, without its `Audio`/`Speaker` capabilities: the shipped
     * environment deliberately refuses those two, so the shipped plugin cannot activate in a JVM composition.
     *
     * Because it is a `ConfiguredPlugin` with a closed key set, sending the smart keys to it fails exactly as it
     * fails on device - which is what makes it a witness for "the smart configuration goes to the smart plugin only".
     * It declares `TextOutput` alone, so it activates like the debug hint probe.
     */
    private class DiagnosticShapedPlugin : PluginLifecycle, ConfiguredPlugin {
        val configurations = CopyOnWriteArrayList<PluginConfiguration>()
        override val descriptor = PluginDescriptor("p2-diagnostic", "1.0.0", 1, "P2 Diagnostic",
            listOf(Capability.TextOutput), ConfigSchema(1))
        override fun validate(configuration: PluginConfiguration) { parse(configuration) }
        override fun configure(configuration: PluginConfiguration) {
            configurations += configuration
            parse(configuration)
        }
        private fun parse(configuration: PluginConfiguration) {
            require(configuration.schemaVersion == 1)
            require(configuration.values.keys.all { it in setOf("profileId", "profileRevision", "requestConsent", "inputMode") }) {
                "unknown p2-diagnostic configuration keys: ${configuration.values.keys}"
            }
            require(!configuration.values["profileId"].isNullOrBlank()) { "profileId is required" }
            require((configuration.values["profileRevision"]?.toLongOrNull() ?: -1) >= 0) {
                "profileRevision must be a non-negative integer"
            }
        }
        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) = Unit
    }

    /**
     * The composition-shaped half of the wiring, built exactly as `HostCompositionRoot.create` builds it: the
     * settings-derived configuration, the plugin with the composition's own clock/scheduler/profile lookup/cooldown,
     * the wake-word provider, and a chain whose detector emission is turned into a `WakeDetected` carrying the host's
     * **current** identity.
     *
     * The detector is the real `WakeWordDetector` over a VAD that admits the frame and an engine that reports one
     * word, so the emission this file drives is the same object the production chain installs - the sink is a
     * constructor value of that detector, exactly as `AudioChainEngines.wakeDetector` passes it.
     */
    private class PassiveRig(private val session: SessionId) {
        val profile = ModelProfile("p-1", 3, "Synthetic", "Synthetic", "https://synthetic.example/v1",
            "DEEPSEEK_RESPONSES", "synthetic-model", object : SecretHandle { override val id = "draft-placeholder" },
            4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())
        val settings = SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
            profileId = "p-1", cooldownMillis = 3000L)
        val input = RecordingInputPort()
        val model = InertModel()
        val wakes = SmartWakeWordRegistry(settings.wakeWords)
        val smart = RecordingSmart(profile)

        /** One clock for the environment and for the emission, as the composition uses one clock for both. */
        val clock = FakeClock(0)
        val configuration: PluginConfiguration
        val chain: CxrAudioChain
        val environment: CxrHostEnvironment
        val host: HostRuntime

        init {
            val holder = arrayOfNulls<HostRuntime>(1)
            configuration = settings.pluginConfiguration(profile)
            // The same emission the production factory installs: the chain's sink hands the event to the host, and
            // the detector is built with a sink that reads the chain's current one (the detector's is a constructor
            // value, so it cannot be patched later). The builder is the production one, not a copy of it
            // (Task 14a-fix item 6), and it timestamps on the same clock the host validates against.
            chain = CxrAudioChain.assemble(ingress = { null },
                framer = PcmFramer(VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test"), 20),
                vad = SpeechVad(), asr = SilentAsr(), speaker = SilentSpeaker(), wake = null,
                scheduler = ManualScheduler(clock), clock = clock,
                emit = { event -> holder[0]?.onEvent(event) },
                wakeEmit = { word, confidence ->
                    HostCompositionRoot.emitWakeDetected(requireNotNull(holder[0]), clock, word, confidence)
                })
            environment = CxrHostEnvironment(clock = clock, input = input, liveOutput = { LiveDisplay() },
                scheduler = ManualScheduler(clock),
                providers = mapOf(
                    Capability.Model to model,
                    Capability.Asr to chain.asrSession,
                    Capability.Actions to NoActions(),
                    Capability.Confirmation to NoConfirmations(),
                    Capability.WakeWord to wakes,
                    // P3.2: the smart plugin declares `Context` (its proactive path runs on perception events), so a
                    // rig that activates it must answer that capability the way the composition does.
                    Capability.Context to EmptyContextSignals(),
                ),
                storageFor = { StubStorage() })
            host = HostRuntime(1, environment)
            holder[0] = host
            host.register(smart)
        }

        /** Reports one word the way the production detector would, through the chain's own emission sink. */
        fun emitWake(word: String) {
            WakeWordDetector(SpeechVad(), OneWordEngine(word), emit = chain.wakeEmit, clock = clock, cooldownNanos = 0)
                .accept(PcmFrame(InputEpoch(session, host.currentGeneration, 1),
                    FloatArray(320), 16000, SampleRange("c-1", 0, 320), 0L, 20_000_000L))
        }

        fun activateSmart(): edith.host.ActivationResult {
            host.configure("smart-assistant", configuration)
            host.startSession(session)
            host.markSceneReady()
            return host.activate("smart-assistant")
        }

        /** Delivery runs on the host's own executor; reading a command result is a barrier that proves the drain. */
        fun settle() { host.currentState }

        fun close() { runCatching { host.close() }; runCatching { chain.close() } }
    }

    private fun withRig(block: (PassiveRig) -> Unit) = PassiveRig(session).let { rig ->
        try { block(rig) } finally { rig.close() }
    }

    // ---- item 3: arm the wake path --------------------------------------------------------------------------------

    @Test
    fun aDetectorEmissionBecomesAWakeDetectedTheHostAcceptsAndDeliversToTheActivePlugin() {
        withRig { rig ->
            assertEquals(edith.host.ActivationResult.Activated, rig.activateSmart())
            val current = requireNotNull(rig.host.currentIdentity())
            assertEquals(session, current.first)

            // A real `WakeWordDetector` over a VAD that admits the frame and an engine that reports the word. Its
            // sink is the chain's own emission path, so what this drives is exactly what the KWS detector would.
            rig.emitWake("伊迪丝")
            rig.settle()
            val delivered = rig.smart.handled.filterIsInstance<WakeDetected>()
            assertEquals("the detector's emission must arrive as a WakeDetected event", 1, delivered.size)
            assertEquals("伊迪丝", delivered.single().word)
            assertEquals(current.first, delivered.single().envelope.sessionId)
            assertEquals(current.second, delivered.single().envelope.generation)
            assertEquals(1.0, delivered.single().confidence, 0.0)
        }
    }

    @Test
    fun aDetectorEmissionCarryingAStaleIdentityIsDroppedAndNeverReleasedByTheHost() {
        withRig { rig ->
            assertEquals(edith.host.ActivationResult.Activated, rig.activateSmart())
            val current = requireNotNull(rig.host.currentIdentity())
            val stale = current.first to Generation(current.second.value - 1)
            assertNotEquals(current, stale)

            // The event a chain built before a pause would have produced: the identity was resolved then, and the
            // generation it names no longer exists. The host must drop it rather than open a turn under it.
            rig.host.onEvent(WakeDetected(
                EventEnvelope(stale.first, stale.second, "stale-wake", 0L, 1L), "伊迪丝", 1.0))
            rig.settle()

            assertTrue("a stale identity must never reach an active plugin",
                rig.smart.handled.none { it is WakeDetected })
        }
    }

    @Test
    fun theWakePathDeliversOnlyTheConfiguredWordList() {
        withRig { rig ->
            assertEquals(edith.host.ActivationResult.Activated, rig.activateSmart())
            // The plugin declares its configured words on activation; the provider must hold exactly those, in order.
            assertEquals(listOf("伊迪丝", "edith"), rig.wakes.current())
            assertTrue("the plugin's gate is the WakeWord capability this provider answers",
                rig.smart.descriptor.capabilities.contains(Capability.WakeWord))
        }
    }

    // ---- item 4: start the audio epoch ----------------------------------------------------------------------------

    @Test
    fun theCompositionStartsTheAudioEpochWhenTheSmartPluginActivatesAndStopsItOnTeardown() {
        val input = RecordingInputPort()
        val root = compositionRoot(input)
        try {
            assertTrue(activateThroughRoot(root))
            val armed = requireNotNull(root.currentAudioEpoch()) { "an activated wake-word plugin must be listening" }
            assertEquals(listOf(armed), input.started)
            assertEquals("the epoch must be the one the activated plugin is running under",
                requireNotNull(root.host.currentIdentity()).first, armed.sessionId)
            assertEquals(root.host.currentGeneration, armed.generation)

            root.stop()

            assertEquals("teardown must stop exactly the epoch it started", listOf(armed), input.stopped)
            assertNull("no epoch may survive its session", root.currentAudioEpoch())
        } finally {
            root.close()
        }
    }

    @Test
    fun aSecondArmOfTheSameSessionIsANoOpRatherThanASecondPollLoop() {
        val input = RecordingInputPort()
        val root = compositionRoot(input)
        try {
            assertTrue(activateThroughRoot(root))
            val armed = requireNotNull(root.currentAudioEpoch())

            root.armAudioEpoch()
            root.armAudioEpoch()

            assertEquals("a second start must not add a loop", 1, input.started.size)
            assertEquals(armed, root.currentAudioEpoch())
        } finally {
            root.close()
        }
    }

    @Test
    fun noEpochRunsWhileTheCompositionHasNoActivePlugin() {
        val input = RecordingInputPort()
        val root = compositionRoot(input)
        try {
            root.host.startSession(session)

            assertTrue("the ears must not be armed by a composition that has done nothing", input.started.isEmpty())
            assertNull(root.currentAudioEpoch())
        } finally {
            root.close()
        }
    }

    // ---- Task 15c D1: the audio epoch is armed on the transport port as well as on the poll loop -----------------

    @Test
    fun armingStartsTheTransportPortAndThePollLoopUnderOneEpochAndDisarmingStopsBoth() {
        val order = mutableListOf<String>()
        val loop = OrderedPort("loop", order)
        val transport = OrderedPort("transport", order)
        val root = compositionRoot(loop, transport)
        try {
            assertTrue(activateThroughRoot(root))

            val armed = requireNotNull(root.currentAudioEpoch()) { "an activated wake-word plugin must be listening" }
            // The transport's port is the only path that opens the CXR ingress and calls `startAudioStream`; arming
            // just the loop left `PcmIngress.format` null and the glasses were never asked for a single frame.
            assertEquals("the transport port must be started with the epoch the loop polls under",
                listOf(armed), transport.recorder.started)
            assertEquals("the poll loop must be started under the same epoch", listOf(armed), loop.recorder.started)
            assertEquals("and started after the port that opens the ingress it polls",
                listOf("start:transport", "start:loop"), order.toList())
            assertEquals("the epoch must be the one the activated plugin is running under",
                requireNotNull(root.host.currentIdentity()).first, armed.sessionId)
            assertEquals(root.host.currentGeneration, armed.generation)

            root.stop()

            assertEquals("teardown must stop the loop it started", listOf(armed), loop.recorder.stopped)
            assertEquals("and the transport port with it", listOf(armed), transport.recorder.stopped)
            assertEquals("the loop must stop before the ingress the transport closes",
                listOf("start:transport", "start:loop", "stop:loop", "stop:transport"), order.toList())
            assertNull("no epoch may survive its session", root.currentAudioEpoch())
        } finally {
            root.close()
        }
    }

    @Test
    fun withNoCxrConnectionAnActivationArmsNeitherTheTransportNorThePollLoop() {
        val loop = RecordingInputPort()
        val root = compositionRoot(loop, withConnection = false)
        try {
            root.start("smart-assistant")
            root.onSceneReady()

            assertEquals("the activation itself must still succeed", SessionState.Ready, root.host.currentState)
            assertNull("no connection means no epoch may be armed", root.currentAudioEpoch())
            assertTrue("a loop here would poll an ingress nothing has opened", loop.started.isEmpty())
        } finally {
            root.close()
        }
    }

    // ---- P3_WAKE / P3_AUDIO: the P3.1 acceptance lines ----------------------------------------------------------

    @Test
    fun theWakeEmissionLineCarriesTheWordAndConfidenceAndNothingElse() {
        withRig { rig ->
            assertEquals(edith.host.ActivationResult.Activated, rig.activateSmart())
            // The emission builder is the production one (Task 14a-fix item 6), so this drives the same call the KWS
            // detector's sink reaches; only the wake is captured, not the activation that preceded it.
            val lines = withP3Sink { captured ->
                rig.emitWake("伊迪丝")
                rig.settle()
                captured.toList()
            }
            assertEquals(listOf("P3_WAKE word=伊迪丝 confidence=1.0"), lines)
        }
    }

    @Test
    fun theArmAndDisarmLinesReportTheEpochAndCarryNoFramePayload() {
        val input = RecordingInputPort()
        val root = compositionRoot(input)
        try {
            withP3Sink { lines ->
                assertTrue(activateThroughRoot(root))
                val armed = requireNotNull(root.currentAudioEpoch())
                // W1: the session start path now establishes the CXR link itself, and it says so **before** the ears
                // report arming - which is the causal order a device run reads. The exact lists below stay about the
                // `P3_AUDIO` vocabulary (that is what "carries no frame payload" is a claim about).
                assertEquals(
                    "the link attempt must be visible, and only as a result code",
                    listOf("P3_CXR connect attempt=start result=ok"),
                    lines.filter { it.startsWith("P3_CXR") },
                )
                assertEquals(
                    listOf("P3_AUDIO state=armed generation=${armed.generation.value} transport=true loop=true"),
                    audioLines(lines),
                )

                root.stop()
                assertEquals(
                    "an armed epoch must report its disarm once, with both halves down",
                    listOf(
                        "P3_AUDIO state=armed generation=${armed.generation.value} transport=true loop=true",
                        "P3_AUDIO state=disarmed generation=${armed.generation.value} " +
                            "transport=false loop=false reason=DISARMED",
                    ),
                    audioLines(lines),
                )
            }
        } finally {
            root.close()
        }
    }

    @Test
    fun anArmWithNoCxrTransportReportsADisarmedReasonAndCarriesNoPayload() {
        val loop = RecordingInputPort()
        val root = compositionRoot(loop, withConnection = false)
        try {
            withP3Sink { lines ->
                root.start("smart-assistant")
                root.onSceneReady()

                // W1: a composition with no CXR assembly now says so at the start - which is exactly the line whose
                // absence made F17/F18 read as "no CXR activity at all".
                assertEquals(
                    listOf("P3_CXR connect attempt=start result=transport_missing"),
                    lines.filter { it.startsWith("P3_CXR") },
                )
                val audio = audioLines(lines)
                assertEquals(audio.toString(), 1, audio.size)
                val line = audio.single()
                assertTrue(line, line.startsWith("P3_AUDIO state=disarmed"))
                assertTrue(line, line.endsWith("transport=false loop=false reason=TRANSPORT_MISSING"))
                assertTrue("the reason line still names the generation", line.contains("generation="))
            }
        } finally {
            root.close()
        }
    }

    @Test
    fun anArmWithNoActivePluginReportsTheDisarmedStateAndReason() {
        val root = compositionRoot(RecordingInputPort(), withConnection = false)
        try {
            withP3Sink { lines ->
                root.armAudioEpoch()
                assertEquals(
                    listOf("P3_AUDIO state=disarmed generation=0 transport=false loop=false reason=NOT_ACTIVE"),
                    lines.toList(),
                )
            }
        } finally {
            root.close()
        }
    }

    // ---- Task 15c D2: the production help entry point -------------------------------------------------------------

    @Test
    fun aHelpRequestIsDeliveredAsAUserActionAtTheCurrentIdentityToTheActivePlugin() {
        val rig = androidRoot(RecordingInputPort()) { wiring -> RecordingConfiguredSmart(wiring.plugin) }
        try {
            rig.root.start("smart-assistant")
            rig.root.connectCxr(activity = Any())
            rig.root.onSceneReady()
            assertNull("the plugin must be active for a help request to reach it", rig.root.state.value.errorCode)
            assertTrue("the receiving plugin must declare the capabilities its help path runs on",
                listOf(Capability.Model, Capability.Asr, Capability.TextOutput)
                    .all { it in rig.recorded.descriptor.capabilities })
            val identity = requireNotNull(rig.root.host.currentIdentity())

            assertTrue("a live session must accept the help request", rig.root.requestHelp())
            // Reading a host command is a barrier that proves the dispatcher drain ran.
            rig.root.host.currentState

            val help = rig.recorded.handled.filterIsInstance<UserAction>().single()
            assertEquals(UserAction.Action.Help, help.action)
            assertEquals("the event must name the identity the host accepts", identity.first, help.envelope.sessionId)
            assertEquals(identity.second, help.envelope.generation)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aHelpRequestWithNoActiveSessionIsDroppedRatherThanThrown() {
        val root = compositionRoot(RecordingInputPort())
        try {
            assertFalse("nothing is active, so there is no identity to name", root.requestHelp())

            root.start("smart-assistant")
            root.connectCxr(activity = Any())
            root.onSceneReady()
            assertTrue("the live session accepts it", root.requestHelp())

            root.pause()
            assertFalse("a paused session has no live identity", root.requestHelp())
        } finally {
            root.close()
        }
        assertFalse("a closed composition must answer, not throw", root.requestHelp())
    }

    /**
     * The real `HostCompositionRoot` with an injected recording input port and the real smart wiring, so the epoch
     * arming runs on the production path (`HostCompositionRoot.start` -> `activateSelected`) rather than in a harness.
     *
     * The environment is supplied because `HostCompositionRoot`'s own constructor never builds one; the shipped
     * assembly builds it in `create`, which needs a real Android `Context` and is therefore a device path.
     *
     * [transport] is the CXR connection's own port (Task 15c D1): the composition arms the epoch on **both** ports,
     * so this rig carries one. `withConnection = false` models a composition whose CXR connection has not been
     * built yet, which is the case the arming must leave alone.
     */
    private fun compositionRoot(
        input: InputPort,
        transport: InputPort = RecordingInputPort(),
        withConnection: Boolean = true,
        register: (HostCompositionRoot) -> Unit = {},
    ): HostCompositionRoot {
        val wiring = SmartWiring(SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1",
            whitelist = emptyList(), profileId = "p-1", cooldownMillis = 3000L),
            FixedProfiles(profile("p-1")),
            FakeClock(0), ManualScheduler(FakeClock(0)))
        val environment = CxrHostEnvironment(clock = FakeClock(0), input = input, liveOutput = { LiveDisplay() },
            scheduler = ManualScheduler(FakeClock(0)),
            providers = mapOf(
                Capability.Model to InertModel(),
                Capability.Asr to StubAsrSession(),
                Capability.Actions to NoActions(),
                // P3.3: the smart plugin declares `Capability.Confirmation`, so a composition-shaped rig must answer
                // it too - the same rule the `Context` line below records for P3.2.
                Capability.Confirmation to NoConfirmations(),
                Capability.WakeWord to wiring.wakeWords,
                Capability.Context to EmptyContextSignals(),
            ),
            storageFor = { StubStorage() })
        val root = HostCompositionRoot(HostRuntime(1, environment), MemoryProfileRepository(), InertModel(),
            environment, smartWiring = wiring,
            rokidConnectionFactory = if (withConnection) { { FakeTransportConnection(transport) } } else null)
        // Through the composition, so its activation side effects can see what the plugin declares.
        root.registerPlugin(wiring.plugin)
        register(root)
        return root
    }

    /** Starts a session through the root and reports whether the smart plugin actually ended up listening. */
    private fun activateThroughRoot(root: HostCompositionRoot): Boolean {
        // W1: the Activity comes first (as `MainActivity.onCreate` does), and the **start path** establishes the link -
        // there is no connect button any more, so the rig must not wire the connection in behind the composition.
        root.attachCxrActivity(activity = Any())
        root.start("smart-assistant")
        root.onSceneReady()
        return root.state.value.errorCode == null && root.currentAudioEpoch() != null
    }

    /** The `P3_AUDIO` lines of a capture, in order: what the ear-vocabulary assertions below are about. */
    private fun audioLines(lines: List<String>): List<String> = lines.filter { it.startsWith("P3_AUDIO") }

    // ---- item 5: the fault seam is reachable ----------------------------------------------------------------------

    @Test
    fun anOverflowReportedByTheReceiverIsTurnedIntoAFaultWhileAStaleEpochStaysSilent() {
        val faults = CopyOnWriteArrayList<String>()
        val offers = CopyOnWriteArrayList<PcmOffer>()
        val clock = FakeClock(0)
        val ingress = edith.audio.PcmIngress(clock)
        val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test")
        val chain = CxrAudioChain.assemble(ingress = { ingress },
            framer = PcmFramer(verified, 20),
            vad = SpeechVad(), asr = SilentAsr(), speaker = SilentSpeaker(), wake = null,
            scheduler = ManualScheduler(clock), clock = clock,
            emit = {}, onFault = faults::add, onOffer = offers::add)
        try {
            // The chain has a running loop, which is the state the transport's offer seam actually arrives in.
            chain.input.start(InputEpoch(session, Generation(1), 1))

            // What `RokidConnection` forwards from `PcmIngress.offer`'s own result, verbatim.
            chain.onOffer(PcmOffer.OVERFLOW)
            chain.onOffer(PcmOffer.STALE_EPOCH)
            chain.onOffer(PcmOffer.UNKNOWN_FORMAT)

            assertEquals("OVERFLOW/UNKNOWN_FORMAT are reportable; STALE_EPOCH is silent by contract",
                listOf("INGRESS_OVERFLOW", "UNKNOWN_FORMAT"), faults)
            assertEquals(listOf(PcmOffer.OVERFLOW, PcmOffer.STALE_EPOCH, PcmOffer.UNKNOWN_FORMAT), offers)
        } finally {
            chain.close()
        }
    }

    // ---- item 2: the advertised set stays honest ------------------------------------------------------------------

    @Test
    fun theWakeWordProviderIsAnswerableSoTheAdvertisedSetEqualsTheAnswerableOne() {
        withRig { rig ->
            val advertised = rig.environment.availableCapabilities

            assertEquals(setOf(Capability.Model, Capability.Storage, Capability.TextOutput, Capability.Asr,
                Capability.Actions, Capability.WakeWord, Capability.Context, Capability.Confirmation), advertised)
            val services = rig.environment.services(ResourceScope("smart-assistant", session, Generation(1)), advertised)
            advertised.forEach { capability ->
                assertNull("$capability is advertised, so it must be answerable",
                    runCatching { services.require(capability) }.exceptionOrNull())
            }
            assertFalse(Capability.Audio in advertised)
            assertNotNull(runCatching { services.require(Capability.Audio) }.exceptionOrNull())
        }
    }

    // ---- item 1: the real plugin dependencies and configuration ---------------------------------------------------

    @Test
    fun theSettingsBuiltConfigurationIsAcceptedByTheRealPluginAndNamesTheChosenProfile() {
        val settings = SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1",
            whitelist = emptyList(), profileId = "p-1", cooldownMillis = 3000L)
        val configuration = settings.pluginConfiguration(profile("p-1"))

        val plugin = SmartAssistantPlugin(clock = FakeClock(0), scheduler = ManualScheduler(FakeClock(0)),
            profileLookup = { id -> if (id == "p-1") profile("p-1") else null })
        // Both calls throw on a refused shape; `validate` is exactly what the host's configuration policy runs before
        // activation, so a refusal here is the "an unconfigured plugin is refused" failure reproduced at the gateway.
        plugin.validate(configuration)
        plugin.configure(configuration)

        assertEquals(1, configuration.schemaVersion)
        assertEquals("p-1", configuration.values["profileId"])
        assertEquals("3", configuration.values["profileRevision"])
        assertEquals("伊迪丝,edith", configuration.values["wakeWords"])
        assertEquals("L1", configuration.values["maxLevel"])
    }

    @Test
    fun aSettingsProfileThatNoLongerExistsStillProducesAConfigurationThePluginAccepts() {
        val settings = SmartSettings(profileId = "deleted")
        val configuration = settings.pluginConfiguration(profile = null)

        assertTrue("the plugin requires a profileId in its shape even when the profile is gone",
            !configuration.values.getValue("profileId").isNullOrBlank())
        assertEquals("0", configuration.values["profileRevision"])
        SmartAssistantPlugin(clock = FakeClock(0), scheduler = ManualScheduler(FakeClock(0)),
            profileLookup = { null }).validate(configuration)
    }

    @Test
    fun theConfigurationNamesEachKeyThePluginKnows() {
        val values = SmartSettings(profileId = "p-1").pluginConfiguration(profile("p-1")).values

        assertEquals(setOf("profileId", "profileRevision", "wakeWords", "maxLevel", "whitelist",
            "proactiveCooldownMillis", "proactiveSensitivity"), values.keys)
    }

    @Test
    fun theWiringBuildsThePluginWithTheCompositionsOwnCollaboratorsRatherThanDefaults() {
        val wiring = SmartWiring(SmartSettings(profileId = "p-1"), FixedProfiles(profile("p-1")),
            FakeClock(0), ManualScheduler(FakeClock(0)))

        assertEquals("smart-assistant", wiring.plugin.descriptor.pluginId)
        assertEquals(listOf("伊迪丝", "edith"), wiring.wakeWords.current())
        val resolved = wiring.configuration(requireNotNull(wiring.selectedProfile()))
        assertEquals("p-1", resolved.values["profileId"])
        assertEquals("3", resolved.values["profileRevision"])
    }

    @Test
    fun withNoProfileAtAllTheWiringStillBuildsAConfigurationThePluginAccepts() {
        // An installed app with no profile yet: the shape must stay valid and the plugin must be able to activate
        // (its turns simply cannot run), rather than the whole passive loop being un-armable.
        val wiring = SmartWiring(SmartSettings(), MemoryProfileRepository(),
            FakeClock(0), ManualScheduler(FakeClock(0)))

        val configuration = wiring.configuration(wiring.selectedProfile())

        assertEquals("__unconfigured__", configuration.values["profileId"])
        wiring.plugin.validate(configuration)
    }

    // ---- fix items 1-3: one configuration, for the plugin it belongs to, and an honest epoch ---------------------

    /**
     * A root over a real `AndroidProfileRepository`.
     *
     * This is the repository the shipped App uses, and the only one whose per-plugin `selections()` makes the
     * activation path take the branch these fix items are about: `MemoryProfileRepository` has no selection store, so
     * a rig built on it can never reproduce "the profile selection replaced the settings-derived keys".
     */
    private class AndroidRoot(
        val root: HostCompositionRoot,
        val wiring: SmartWiring,
        val registered: PluginLifecycle,
    ) : AutoCloseable {
        val recorded: RecordingConfiguredSmart get() = registered as RecordingConfiguredSmart
        override fun close() = root.close()
    }

    private fun androidRoot(input: InputPort, transport: InputPort = RecordingInputPort(),
        registered: (SmartWiring) -> PluginLifecycle = { it.plugin }): AndroidRoot {
        val blobs = TestBlobs()
        val repository = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val saved = repository.saveWithSecret(profile("p-1"), "synthetic-dummy-secret".toCharArray())
        repository.select("smart-assistant", saved.id)
        val wiring = SmartWiring(SmartSettings(wakeWords = listOf("小艾"), maxLevel = "L0",
            whitelist = listOf("launch|app"), profileId = saved.id, cooldownMillis = 3000L),
            repository, FakeClock(0), ManualScheduler(FakeClock(0)))
        val environment = CxrHostEnvironment(clock = FakeClock(0), input = input, liveOutput = { LiveDisplay() },
            scheduler = ManualScheduler(FakeClock(0)),
            providers = mapOf(
                Capability.Model to InertModel(),
                Capability.Asr to StubAsrSession(),
                Capability.Actions to NoActions(),
                // P3.3: the smart plugin declares `Capability.Confirmation`, so a composition-shaped rig must answer
                // it too - the same rule the `Context` line below records for P3.2.
                Capability.Confirmation to NoConfirmations(),
                Capability.WakeWord to wiring.wakeWords,
                Capability.Context to EmptyContextSignals(),
            ),
            storageFor = { StubStorage() })
        val root = HostCompositionRoot(HostRuntime(1, environment), repository, InertModel(),
            environment, smartWiring = wiring,
            rokidConnectionFactory = { FakeTransportConnection(transport) })
        val plugin = registered(wiring)
        root.registerPlugin(plugin)
        return AndroidRoot(root, wiring, plugin)
    }

    @Test
    fun theSettingsKeysAndTheProfileSelectionReachThePluginAsOneConfiguration() {
        val input = RecordingInputPort()
        val rig = androidRoot(input) { wiring -> RecordingConfiguredSmart(wiring.plugin) }
        try {
            rig.root.start("smart-assistant")
            rig.root.connectCxr(activity = Any())
            rig.root.onSceneReady()

            assertNull("the merged configuration must be accepted", rig.root.state.value.errorCode)
            assertNotNull(rig.root.currentAudioEpoch())

            // The configuration the plugin was last given is the one it parses and runs with: `HostRuntime.configure`
            // replaces the stored configuration, so a second call with only the profile selection would leave the
            // plugin on its own defaults for every other key.
            val effective = rig.recorded.configurations.last()
            assertEquals("p-1", effective.values["profileId"])
            assertEquals("1", effective.values["profileRevision"])
            assertEquals("the user's risk ceiling must not fall back to the plugin's own L1 default",
                "L0", effective.values["maxLevel"])
            assertEquals("the configured wake words must not fall back to the plugin's own default",
                "小艾", effective.values["wakeWords"])
            assertEquals("launch|app", effective.values["whitelist"])
            assertEquals("one configuration per activation, not two that replace each other",
                1, rig.recorded.configurations.distinct().size)
            assertEquals("the provider holds the words the plugin was actually configured with",
                listOf("小艾"), rig.wiring.wakeWords.current())
        } finally {
            rig.close()
        }
    }

    @Test
    fun selectingTheDiagnosticPluginGetsItsOwnKeysAndLeavesNoErrorOrArmedEpoch() {
        val input = RecordingInputPort()
        val diagnostic = DiagnosticShapedPlugin()
        val root = compositionRoot(input) { it.registerPlugin(diagnostic) }
        try {
            assertTrue(activateThroughRoot(root))
            val smartEpoch = requireNotNull(root.currentAudioEpoch())

            root.selectPlugin("p2-diagnostic")

            assertNull("the diagnostic plugin's own configuration must be accepted, not refused",
                root.state.value.errorCode)
            assertEquals("no smart key may be sent to a plugin whose configuration is not the smart one",
                setOf("profileId", "profileRevision", "requestConsent", "inputMode"),
                diagnostic.configurations.last().values.keys)
            assertNull("the previous plugin's epoch must not stay armed while another plugin is active",
                root.currentAudioEpoch())
            assertEquals(listOf(smartEpoch), input.stopped)
        } finally {
            root.close()
        }
    }

    @Test
    fun switchingToAPluginWithoutTheWakeWordDisarmsAndSwitchingBackRearms() {
        val input = RecordingInputPort()
        // The debug hint probe's shape: TextOutput only, no WakeWord.
        val root = compositionRoot(input) { it.registerPlugin(DiagnosticHintPlugin(FakeClock(0))) }
        try {
            assertTrue(activateThroughRoot(root))
            val smartEpoch = requireNotNull(root.currentAudioEpoch())

            root.selectPlugin("p2-hint")

            assertNull("a plugin that does not declare WakeWord must not leave the ears armed",
                root.currentAudioEpoch())
            assertEquals(listOf(smartEpoch), input.stopped)

            root.selectPlugin("smart-assistant")

            val rearmed = requireNotNull(root.currentAudioEpoch()) { "switching back must re-arm the ears" }
            assertNotEquals(smartEpoch, rearmed)
            assertEquals(2, input.started.size)
        } finally {
            root.close()
        }
    }

    /**
     * A second plugin that declares `Capability.WakeWord`, registered through the composition's own registry.
     *
     * It shares the smart plugin's wake-word gate but nothing else: switching to it must keep the ears armed, and
     * because `HostRuntime.switchTo` increments the generation the epoch that was armed for the first plugin can no
     * longer be the one the pipeline polls under.
     */
    private class RecordingWakeWordPlugin : PluginLifecycle {
        val handled = CopyOnWriteArrayList<HostEvent>()
        override val descriptor = PluginDescriptor("p2-alt-wake", "1.0.0", 1, "P2 Alt Wake",
            listOf(Capability.TextOutput, Capability.WakeWord), ConfigSchema(1))
        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) { handled += event }
    }

    /**
     * A switch between two wake-word plugins is the case the generation is easy to get wrong for: `switchTo`
     * increments the generation inside the host *after* the composition has resolved the one it thinks it activated
     * under, so an epoch armed with the pre-switch generation tags every frame with an identity the host drops.
     */
    @Test
    fun switchingBetweenTwoWakeWordPluginsArmsTheEpochUnderTheGenerationThatIsCurrentAfterTheSwitch() {
        val input = RecordingInputPort()
        val alt = RecordingWakeWordPlugin()
        // Through the composition, so `declares` can see the second plugin's capabilities - registering it straight
        // on the host would leave it unknown and the ears would be disarmed instead of re-armed.
        val root = compositionRoot(input) { it.registerPlugin(alt) }
        try {
            assertTrue(activateThroughRoot(root))
            val first = requireNotNull(root.currentAudioEpoch())
            assertEquals(root.host.currentGeneration, first.generation)

            root.selectPlugin("p2-alt-wake")

            assertNull("the switch must not leave an error", root.state.value.errorCode)
            val armed = requireNotNull(root.currentAudioEpoch()) {
                "a switched-to plugin that declares WakeWord must keep the ears armed"
            }
            assertEquals("the armed epoch must name the generation the host is on after the switch",
                root.host.currentGeneration, armed.generation)
            assertNotEquals("the previous activation's epoch cannot be the one polling now", first, armed)
            assertEquals(listOf(first), input.stopped)

            // The pipeline builds its envelopes from the epoch it is polling under (`SpeechPipeline.envelope`), so the
            // strong proof is that an event built from the armed epoch survives the host's identity gate instead of
            // being silently dropped. This is the behaviour a wake-word-only switch must not regress.
            assertTrue("an event under the armed epoch must be accepted by the host",
                root.host.onEvent(WakeDetected(
                    EventEnvelope(armed.sessionId, armed.generation, "wake-under-armed-epoch", 0L, 1L), "伊迪丝", 1.0)))
            // Delivery runs on the host's own executor; reading a command result is a barrier that proves the drain.
            root.host.currentState
            assertEquals("the switched-to plugin must receive the event its epoch produced",
                1, alt.handled.filterIsInstance<WakeDetected>().size)
        } finally {
            root.close()
        }
    }

    @Test
    fun noWakeMeansNoModelRequestIsMade() {
        withRig { rig ->
            assertEquals(edith.host.ActivationResult.Activated, rig.activateSmart())
            rig.settle()

            assertTrue("nothing was said, so nothing may be handled", rig.smart.handled.isEmpty())
            assertTrue("no wake and no transcript must mean no model request", rig.model.requests.isEmpty())
        }
    }
}
