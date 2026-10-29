package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.withP3Sink
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.bridge.CxrConnection
import com.sheen.rokid.phone.bridge.CxrHintLink
import com.sheen.rokid.phone.bridge.HintIdentity
import com.sheen.rokid.phone.bridge.RokidConnection
import com.sheen.rokid.phone.app.AudioChainEngines
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.app.TypedSendResult
import com.sheen.rokid.phone.bridge.UnavailableTaskScope

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.SampleRange
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.storage.AndroidProfileRepository
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.AsrSession
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ConfiguredPlugin
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.plugin.StorageAccess
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.phone.plugin.diagnostic.DiagnosticHintPlugin
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.storage.ProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
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
        4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT, InputModality.IMAGE),
        setOf(OutputModality.TEXT), emptyList())

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
        override fun listen(listener: (com.sheen.rokid.contracts.TranscriptRevision) -> Unit): CancellationHandle =
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

    /**
     * 0.3 Phase 1 Task 12: the smart plugin declares the static device profile channel, so every composition-shaped
     * rig that activates it must answer `Capability.DeviceState` exactly as the App's composition does. The facts are
     * deliberately empty (a profile with no model/version), so this rig contributes no device literal to any prompt and
     * these tests stay about wiring rather than prompt content.
     */
    private class SyntheticDeviceState : DeviceStateProvider {
        override fun profile() = DeviceProfile(
            manufacturer = "",
            model = "",
            deviceName = null,
            androidRelease = "",
            apiLevel = 0,
            screenWidthPx = 0,
            screenHeightPx = 0,
            densityDpi = 0,
            glassesModel = null,
            glassesFirmware = null,
            glassesWidthPx = null,
            glassesHeightPx = null,
            appVersion = "",
            locale = "",
            timeZone = "",
            wakeWords = emptyList(),
            capabilities = emptySet(),
        )

        override fun state() = DeviceState(
            capturedAtEpochMillis = 0L,
            timeZone = "UTC",
            localDate = "2026-10-20",
            weekday = "星期一",
            localTime = "00:00",
        )
    }

    /**
     * edith-0.3 Phase 2 Task 9: the smart plugin declares the host's per-turn perception port, so a composition-shaped
     * rig must answer `Capability.Perception` or the host refuses activation with `CAPABILITY_MISSING`. This provider
     * returns an all-unavailable snapshot: these tests are about wiring, so it contributes no screen text, no
     * notification body and no device fact.
     */
    internal class InertPerception : PerceptionProvider {
        private val snapshot = PerceptionSnapshot(
            snapshotId = "snap-test",
            capturedAtEpochMillis = 0L,
            coverage = PerceptionCoverage(
                screen = "unavailable(no_accessibility)",
                notifications = "unavailable(no_access)",
                media = "unavailable(no_session)",
                calendar = "empty",
                acoustic = "unavailable(no_event)",
                clipboard = "unavailable(background_restricted)",
                vision = "disabled",
                glasses = "unavailable(no_state)",
                device = "partial",
            ),
        )

        override fun beginTurn(): PerceptionSnapshot = snapshot
        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot = snapshot
        override fun afterAction(): PerceptionSnapshot = snapshot
        override fun screenshot(): PerceptionScreenshotResult = PerceptionScreenshotResult.Unavailable("test")
        override fun endTurn() = Unit
    }

    /**
     * edith-0.2 Phase B (B4): the smart plugin declares `Capability.StorageAccess`, so a composition-shaped rig must
     * advertise and answer it exactly as the App's composition does - otherwise the host refuses activation with
     * `CAPABILITY_MISSING`, which is the correct behaviour for a declared capability nobody can serve.
     *
     * It answers "no SKILLs installed", the normal state on a device where the user has written none (spec §3.7),
     * so no test in this file needs a real file system.
     */
    /** Phase C: the conversation port the smart plugin declares; this rig only needs activation to succeed. */
private class NoConversationDisplay : ConversationDisplay {
    override fun userMessage(route: AssistantRoute, text: String) = Unit
    override fun assistantMessage(route: AssistantRoute, text: String) = Unit
    override fun working(route: AssistantRoute) = Unit
    override fun idle(route: AssistantRoute) = Unit
}

/** 0.24 S3 fix (C1): records the official levels the composition-level wake drove into the real plugin. */
private class RecordingConversationDisplay : ConversationDisplay {
    val states = CopyOnWriteArrayList<Pair<AssistantRoute, Int>>()
    val closes = CopyOnWriteArrayList<AssistantRoute>()
    override fun userMessage(route: AssistantRoute, text: String) = Unit
    override fun assistantMessage(route: AssistantRoute, text: String) = Unit
    override fun working(route: AssistantRoute) = Unit
    override fun idle(route: AssistantRoute) = Unit
    override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
        states += route to status
    }
    override fun closePanel(route: AssistantRoute) { closes += route }
}

private class NoStorageAccess : StorageAccess {
        override fun listFiles(dirPath: String, suffix: String): List<String> = emptyList()
        override fun readFile(path: String): String? = null
        override fun deleteFile(path: String): Boolean = false
    }

    /**
     * edith-0.2 Phase B (B9, spec §1.3 C2): the plugin declares both local models as hard dependencies, so the rig
     * answers them **ready**. An unavailable model is refused service, which is exactly what `HardDependencyTest`
     * covers in `plugins-smart`; here the point is that a normal composition runs a normal turn.
     */
    private class ReadySystemOne : SystemOneService {
        override fun ask(state: String, question: SystemOneQuestion): SystemOneResult =
            SystemOneResult.Success(SystemOneAnswer.Noul(probability = 0.5, confidence = 0.5), latencyMs = 1.0)
        override val isAvailable = true
        override val status = SystemOneStatus.READY
        override val modelInfo: ModelInfo? = null
    }

    private class ReadyEmbedding : EmbeddingService {
        override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(256))
        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = 256
    }

    private class StubStorage : StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

    /** A wake engine that reports one configured word from one frame, so the real detector can be driven. */
    private class OneWordEngine(private val word: String) : com.sheen.rokid.audio.WakeWordEngine {
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
     * 0.4 Task 10: a task scope that takes the typed turn's loop and never runs it.
     *
     * The composition tests assert **delivery and the result vocabulary**, not the agent loop (that is the plugin
     * module's `TypedMessageTurnTest`). A never-run block keeps the typed turn installed without needing a model
     * stream, so `canAcceptTypedMessage()` is false after the request and the root can be closed cleanly.
     */
    private class NeverRunsTasks : TaskScope {
        val launched = CopyOnWriteArrayList<() -> Unit>()
        override fun launch(block: () -> Unit): CancellationHandle {
            launched += block
            return object : CancellationHandle { override fun cancel() = Unit }
        }
        override fun cancelAll() = Unit
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
                    Capability.StorageAccess to NoStorageAccess(),
                    Capability.SystemOne to ReadySystemOne(),
                    Capability.Embedding to ReadyEmbedding(),
                    Capability.DeviceState to SyntheticDeviceState(),
                    Capability.Perception to InertPerception(),
                    Capability.ConversationDisplay to NoConversationDisplay(),
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

        fun activateSmart(): com.sheen.rokid.host.ActivationResult {
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
            assertEquals(com.sheen.rokid.host.ActivationResult.Activated, rig.activateSmart())
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
            assertEquals(com.sheen.rokid.host.ActivationResult.Activated, rig.activateSmart())
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
            assertEquals(com.sheen.rokid.host.ActivationResult.Activated, rig.activateSmart())
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

    @Test
    fun aSessionOnATextOnlyProfileIsRefusedBeforeItStartsAndNamesTheMultimodalCode() {
        // R74: the assistant is a TEXT+IMAGE product. A profile that does not declare IMAGE is a configuration
        // refusal, not a silent text-only downgrade: no session, no epoch, and the UI-visible code is the ruling's.
        val root = compositionRoot(RecordingInputPort(),
            profile = profile("p-1").copy(inputs = setOf(InputModality.TEXT)))
        try {
            root.start("smart-assistant")

            assertEquals("PROFILE_MODEL_NOT_MULTIMODAL", root.state.value.errorCode)
            assertEquals("a refused start begins no session", SessionState.Idle, root.host.currentState)
            assertNull("and arms nothing", root.currentAudioEpoch())
        } finally {
            root.close()
        }
    }

    // ---- P3_WAKE / P3_AUDIO: the P3.1 acceptance lines ----------------------------------------------------------

    @Test
    fun theWakeEmissionLineCarriesTheWordAndConfidenceAndNothingElse() {
        withRig { rig ->
            assertEquals(com.sheen.rokid.host.ActivationResult.Activated, rig.activateSmart())
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
    fun aDetectionIsEvidencedEvenWhenTheHostHasNoIdentity() {
        // No session is started here, so `host.currentIdentity()` is null — which is exactly the case that used to be
        // invisible: `emitWakeDetected` returned before its log line, so "the detector fired and the event could not be
        // delivered" and "the detector never fired" looked identical in logcat. Its own KDoc says the line exists so a
        // firing is evidenced even when the host drops the event, so the line must come first.
        withRig { rig ->
            val lines = withP3Sink { captured ->
                rig.emitWake("伊迪丝")
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
                    audioLines(lines),
                )
                // 0.4 Task 6 (spec §7.2): the new evidence line names the source; the P3 line above keeps its shape.
                assertEquals(
                    listOf("P4_AUDIO state=disarmed source=cxr transport=false loop=false lockscreen_hold=false reason=NOT_ACTIVE"),
                    lines.filter { it.startsWith("P4_AUDIO") },
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

    // ---- 0.4 Task 10 (D203): the production typed-message entry point --------------------------------------------

    @Test
    fun aTypedMessageIsDeliveredAsATypedUserActionAtTheCurrentIdentity() {
        val rig = androidRoot(RecordingInputPort(), taskScope = NeverRunsTasks()) { wiring ->
            RecordingConfiguredSmart(wiring.plugin)
        }
        try {
            rig.root.start("smart-assistant")
            rig.root.connectCxr(activity = Any())
            rig.root.onSceneReady()
            assertNull("the plugin must be active for a typed message to reach it", rig.root.state.value.errorCode)
            val identity = requireNotNull(rig.root.host.currentIdentity())

            val lines = withP3Sink { captured ->
                assertEquals(
                    "a live, idle session must accept the typed message",
                    TypedSendResult.Accepted,
                    rig.root.requestTypedMessage("  帮我记一条笔记  "),
                )
                // Reading a host command is a barrier that proves the dispatcher drain ran.
                rig.root.host.currentState
                captured.toList()
            }

            val typed = rig.recorded.handled.filterIsInstance<UserAction>()
                .single { it.action == UserAction.Action.Typed }
            assertEquals("the typed body is trimmed once, at the composition", "帮我记一条笔记", typed.text)
            assertEquals("the event names the identity the host accepts", identity.first, typed.envelope.sessionId)
            assertEquals(identity.second, typed.envelope.generation)
            assertTrue(
                "the event id names the typed turn and is unique per request: ${typed.envelope.eventId}",
                typed.envelope.eventId.startsWith("typed-"),
            )
            assertEquals(
                "the accepted line is count-only and names the turn",
                listOf("P4_TYPED state=accepted turn=${typed.envelope.eventId} chars=7"),
                lines.filter { it.startsWith("P4_TYPED") },
            )
            assertTrue(
                "the typed text must never be logged: $lines",
                lines.none { it.contains("帮我记一条笔记") },
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun aTypedRequestWithoutASessionIsAnsweredAndBlankIsCheckedFirst() {
        val root = compositionRoot(RecordingInputPort())
        try {
            val lines = withP3Sink { captured ->
                assertEquals(
                    "a blank body is a local refusal even before an identity is consulted",
                    TypedSendResult.Blank,
                    root.requestTypedMessage("   "),
                )
                assertEquals(
                    "nothing is active, so there is no identity to name",
                    TypedSendResult.NoSession,
                    root.requestTypedMessage("帮我记一条笔记"),
                )
                captured.toList()
            }
            val refused = lines.filter { it.startsWith("P4_TYPED") }
            assertEquals("each request logs exactly one P4_TYPED line: $lines", 2, refused.size)
            assertTrue("blank is the first reason: ${refused[0]}",
                refused[0].startsWith("P4_TYPED state=refused reason=blank turn=typed-"))
            assertTrue("a blank body has zero characters: ${refused[0]}", refused[0].endsWith(" chars=0"))
            assertTrue("no identity is named as no_session: ${refused[1]}",
                refused[1].startsWith("P4_TYPED state=refused reason=no_session turn=typed-"))
            assertTrue("the count is the body's: ${refused[1]}", refused[1].endsWith(" chars=7"))
        } finally {
            root.close()
        }
        assertEquals(
            "a closed composition must answer, not throw",
            TypedSendResult.NoSession,
            root.requestTypedMessage("帮我记一条笔记"),
        )
        assertEquals(
            "blank stays the first refusal after close, so the result vocabulary is order-stable",
            TypedSendResult.Blank,
            root.requestTypedMessage("  "),
        )
    }

    @Test
    fun aTypedRequestDuringAWakeTurnIsReportedBusyAndKeepsTheTurn() {
        val rig = androidRoot(RecordingInputPort(), taskScope = NeverRunsTasks()) { wiring ->
            RecordingConfiguredSmart(wiring.plugin)
        }
        try {
            rig.root.start("smart-assistant")
            rig.root.connectCxr(activity = Any())
            rig.root.onSceneReady()
            // A wake opens a turn that is waiting for its transcript: the one condition the typed path must not touch.
            rig.root.emitWake(rig.root.host, "小艾", 0.9)
            rig.root.host.currentState
            assertFalse(
                "the wake turn must own the slot before the typed request is made",
                requireNotNull(rig.root.smartPluginForProbe).canAcceptTypedMessage(),
            )

            val lines = withP3Sink { captured ->
                assertEquals(TypedSendResult.Busy, rig.root.requestTypedMessage("帮我记一条笔记"))
                captured.toList()
            }
            val refused = lines.single { it.startsWith("P4_TYPED") }
            assertTrue("the busy reason is named: $refused",
                refused.startsWith("P4_TYPED state=refused reason=busy turn=typed-"))
            assertTrue("the count is the body's: $refused", refused.endsWith(" chars=7"))
            assertTrue(
                "a refused typed message must not reach the plugin",
                rig.recorded.handled.filterIsInstance<UserAction>().none { it.action == UserAction.Action.Typed },
            )
            assertFalse(
                "the refusal must not cancel or settle the awaiting wake turn",
                requireNotNull(rig.root.smartPluginForProbe).canAcceptTypedMessage(),
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun aTypedRequestTheHostRefusesIsReportedStale() {
        val rig = androidRoot(RecordingInputPort(), taskScope = NeverRunsTasks()) { wiring ->
            RecordingConfiguredSmart(wiring.plugin)
        }
        try {
            rig.root.start("smart-assistant")
            rig.root.connectCxr(activity = Any())
            rig.root.onSceneReady()
            // The identity moved between the read and the submit: the host's own answer is what the result reports.
            rig.root.typedSubmitForProbe = { false }

            val lines = withP3Sink { captured ->
                assertEquals(TypedSendResult.Stale, rig.root.requestTypedMessage("帮我记一条笔记"))
                captured.toList()
            }
            val refused = lines.single { it.startsWith("P4_TYPED") }
            assertTrue("the stale reason is named: $refused",
                refused.startsWith("P4_TYPED state=refused reason=stale turn=typed-"))
            assertTrue("the count is the body's: $refused", refused.endsWith(" chars=7"))
            assertTrue(
                "a stale submission must not reach the plugin",
                rig.recorded.handled.filterIsInstance<UserAction>().isEmpty(),
            )
        } finally {
            rig.close()
        }
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
        profile: ModelProfile = profile("p-1"),
        /**
         * 0.4 Task 10: the plugin's background work. The shipped default is the environment's own refusal; a typed
         * message test passes [NeverRunsTasks] so the delivered turn needs no model stream.
         */
        taskScope: TaskScope = UnavailableTaskScope,
        register: (HostCompositionRoot) -> Unit = {},
    ): HostCompositionRoot {
        val wiring = SmartWiring(SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1",
            whitelist = emptyList(), profileId = profile.id, cooldownMillis = 3000L),
            FixedProfiles(profile),
            FakeClock(0), ManualScheduler(FakeClock(0)))
        val environment = CxrHostEnvironment(clock = FakeClock(0), input = input, liveOutput = { LiveDisplay() },
            scheduler = ManualScheduler(FakeClock(0)),
            taskScope = taskScope,
            providers = mapOf(
                Capability.Model to InertModel(),
                Capability.Asr to StubAsrSession(),
                Capability.Actions to NoActions(),
                // P3.3: the smart plugin declares `Capability.Confirmation`, so a composition-shaped rig must answer
                // it too - the same rule the `Context` line below records for P3.2.
                Capability.Confirmation to NoConfirmations(),
                Capability.WakeWord to wiring.wakeWords,
                Capability.Context to EmptyContextSignals(),
                Capability.StorageAccess to NoStorageAccess(),
                Capability.SystemOne to ReadySystemOne(),
                Capability.Embedding to ReadyEmbedding(),
                Capability.DeviceState to SyntheticDeviceState(),
                Capability.Perception to InertPerception(),
                Capability.ConversationDisplay to NoConversationDisplay(),
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
        val ingress = com.sheen.rokid.audio.PcmIngress(clock)
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
                Capability.Actions, Capability.WakeWord, Capability.Context, Capability.Confirmation,
                Capability.StorageAccess, Capability.SystemOne, Capability.Embedding,
                // edith-0.2 Phase C: the smart plugin declares the conversation port, so the composition advertises
                // it and this rig answers it - the two halves of the contract this test exists to keep equal.
                Capability.ConversationDisplay,
                // 0.3 Phase 1 Task 12: the static device profile the session pins into its prompt plan.
                Capability.DeviceState,
                // 0.3 Phase 2 Task 9: the per-turn perception port the request tail renders from.
                Capability.Perception), advertised)
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
            "proactiveCooldownMillis", "proactiveSensitivity", "noInterception"), values.keys)
    }

    /**
     * 0.3 Phase 4B (`D189`, spec §5.3): the persisted switch also travels in the plugin's configuration shape.
     *
     * The live behavior is the host gate (`NoInterceptionGate.provider()`); this key is the initial/persisted value the
     * plugin stores, so a restarted App sends the same setting the store loaded. A missing key would make the plugin
     * refuse the whole closed configuration, which is why the exact key-set test above pins it.
     */
    @Test
    fun theNoInterceptionSettingIsSentAsThePluginsInitialValue() {
        val off = SmartSettings(profileId = "p-1").pluginConfiguration(profile("p-1"))
        val on = SmartSettings(profileId = "p-1", noInterception = true).pluginConfiguration(profile("p-1"))

        assertEquals("false", off.values["noInterception"])
        assertEquals("true", on.values["noInterception"])
    }

    /**
     * 0.3 Phase 4B (`D189`, spec §5.1): the composition must pass the **live** gate, never a snapshot.
     *
     * The policy and shell-precheck consumers call this provider on every decision, so a wiring that passed a boolean
     * (or read the settings object once) would leave the user's save ineffective until the next App assembly. Flipping
     * the backing flag after construction is the proof that the plugin holds the provider itself.
     */
    @Test
    fun theLiveNoInterceptionGateReachesThePluginTheWiringBuilds() {
        val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
        val wiring = SmartWiring(
            SmartSettings(profileId = "p-1"), MemoryProfileRepository(), FakeClock(0), ManualScheduler(FakeClock(0)),
            noInterception = { enabled.get() },
        )
        val field = wiring.plugin.javaClass.getDeclaredField("noInterception")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val provider = field.get(wiring.plugin) as () -> Boolean

        assertFalse("the mode is off by default", provider())
        enabled.set(true)
        assertTrue("a save must be visible from the provider's next call", provider())
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

    /**
     * 0.24 S3 (spec §5.5): the persisted「持续对话」switch must reach the plugin the composition builds.
     *
     * The plugin's `continueDialogue` seam is a private lambda (the plugin is in another Gradle module, so this module
     * cannot read an `internal` accessor). Reading the field is the honest composition-level proof: the constructor
     * argument is what the plugin actually consults, and a wiring that forgot to pass it would fail here, not only on
     * the device.
     */
    @Test
    fun thePersistedContinueDialogueSwitchReachesThePluginTheWiringBuilds() {
        fun wiredSwitch(settings: SmartSettings): Boolean {
            val wiring = SmartWiring(settings, MemoryProfileRepository(), FakeClock(0), ManualScheduler(FakeClock(0)))
            val field = wiring.plugin.javaClass.getDeclaredField("continueDialogue")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return (field.get(wiring.plugin) as () -> Boolean).invoke()
        }

        assertTrue("the official default is on", wiredSwitch(SmartSettings()))
        assertFalse("the stored switch must reach the plugin", wiredSwitch(SmartSettings(continueDialogue = false)))
    }

    /**
     * 0.5.1 (`C2`, spec §3.2): phone-only mode disables **only** the post-answer continuation the composition wires.
     *
     * The stored 「持续对话」 value still travels to the plugin (glasses mode and the queued-utterance seam read it);
     * the new seam multiplies it by the live `!phoneOnly()`. A wiring that forgot the mode, or snapshotted it, would
     * either keep the 10 s continuation on the phone or leave glasses mode unchanged only by accident.
     */
    @Test
    fun thePhoneOnlyModeGatesThePostAnswerContinuationTheWiringBuilds() {
        val detached = java.util.concurrent.atomic.AtomicBoolean(false)

        fun wiredSwitch(settings: SmartSettings): () -> Boolean {
            val wiring = SmartWiring(
                settings, MemoryProfileRepository(), FakeClock(0), ManualScheduler(FakeClock(0)),
                phoneOnly = { detached.get() },
            )
            val field = wiring.plugin.javaClass.getDeclaredField("continueAfterAnswer")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(wiring.plugin) as () -> Boolean
        }

        val storedOn = wiredSwitch(SmartSettings(continueDialogue = true))
        val storedOff = wiredSwitch(SmartSettings(continueDialogue = false))

        assertTrue("glasses mode with the stored switch on still continues", storedOn())
        assertFalse("the stored switch still gates glasses mode", storedOff())

        // The provider is read live: a save/mode change after assembly must apply without rebuilding the plugin.
        detached.set(true)
        assertFalse(
            "phone-only mode must disable the post-answer continuation even with the stored switch on",
            storedOn(),
        )
    }

    /**
     * 0.24 S3 Task 14 fix round (C2): whether a player exists must reach the plugin.
     *
     * With「语音朗读」off (the shipped default) production builds no player, so a `both`/`voice` answer has no
     * `SpeechFinished` source and must be settled by its `Displayed` acknowledgement. The wiring is the only place the
     * production composition can pass that fact to the plugin; a wiring that forgot it would keep the old
     * wait-then-close behavior on every default configuration.
     */
    @Test
    fun theSpeechPlayerPresenceReachesThePluginTheWiringBuilds() {
        fun wiredSpeech(available: Boolean): Boolean {
            val wiring = SmartWiring(
                SmartSettings(), MemoryProfileRepository(), FakeClock(0), ManualScheduler(FakeClock(0)),
                speechAvailable = { available },
            )
            val field = wiring.plugin.javaClass.getDeclaredField("speechAvailable")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return (field.get(wiring.plugin) as () -> Boolean).invoke()
        }

        assertFalse("no player means no speech", wiredSpeech(false))
        assertTrue("a player means speech can happen", wiredSpeech(true))
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
        /** 0.4 Task 10: see [compositionRoot]'s same parameter. */
        taskScope: TaskScope = UnavailableTaskScope,
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
            taskScope = taskScope,
            providers = mapOf(
                Capability.Model to InertModel(),
                Capability.Asr to StubAsrSession(),
                Capability.Actions to NoActions(),
                // P3.3: the smart plugin declares `Capability.Confirmation`, so a composition-shaped rig must answer
                // it too - the same rule the `Context` line below records for P3.2.
                Capability.Confirmation to NoConfirmations(),
                Capability.WakeWord to wiring.wakeWords,
                Capability.Context to EmptyContextSignals(),
                Capability.StorageAccess to NoStorageAccess(),
                Capability.SystemOne to ReadySystemOne(),
                Capability.Embedding to ReadyEmbedding(),
                Capability.DeviceState to SyntheticDeviceState(),
                Capability.Perception to InertPerception(),
                Capability.ConversationDisplay to NoConversationDisplay(),
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

    /**
     * 0.24 S3 fix (C1): the **production composition**, not the tests, feeds the conversation timers.
     *
     * A real hidden wake used to take the hidden-panel path forever: `SmartWiring` never passed `isPanelVisible` and
     * `HostCompositionRoot`'s `onPanelShown`/`onUiClosed` seams were assigned only by tests. This builds the same
     * `HostCompositionRoot`/`SmartWiring` pair `create` builds (a real link included) and drives the panel lifecycle
     * through the root's own entry points.
     */
    @Test
    fun theCompositionFeedsPanelShownAndVisibilityToThePluginConversationTimers() {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val display = RecordingConversationDisplay()
        val rootHolder = arrayOfNulls<HostCompositionRoot>(1)
        val wiring = SmartWiring(
            SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1",
                whitelist = emptyList(), profileId = "p-1", cooldownMillis = 3000L),
            FixedProfiles(profile("p-1")), clock, scheduler,
            // Exactly the expression `create` passes: the composition's live link visibility, via the holder that does
            // not exist yet when the wiring is built.
            isPanelVisible = { rootHolder[0]?.isPanelVisible() ?: false },
        )
        val link = CxrHintLink(clock, scheduler, sendJson = { true })
        val environment = CxrHostEnvironment(
            clock = clock, input = RecordingInputPort(), liveOutput = { LiveDisplay() }, scheduler = scheduler,
            providers = mapOf(
                Capability.Model to InertModel(),
                Capability.Asr to StubAsrSession(),
                Capability.Actions to NoActions(),
                Capability.Confirmation to NoConfirmations(),
                Capability.WakeWord to wiring.wakeWords,
                Capability.Context to EmptyContextSignals(),
                Capability.StorageAccess to NoStorageAccess(),
                Capability.SystemOne to ReadySystemOne(),
                Capability.Embedding to ReadyEmbedding(),
                Capability.DeviceState to SyntheticDeviceState(),
                Capability.Perception to InertPerception(),
                Capability.ConversationDisplay to display,
            ),
            storageFor = { StubStorage() })
        val root = HostCompositionRoot(HostRuntime(1, environment), MemoryProfileRepository(), InertModel(),
            environment, hintLink = link, smartWiring = wiring,
            rokidConnectionFactory = { FakeTransportConnection(null) })
        rootHolder[0] = root
        try {
            root.registerPlugin(wiring.plugin)
            root.attachCxrActivity(Any())
            root.start("smart-assistant")
            root.onSceneReady()
            val identity = requireNotNull(root.host.currentIdentity())
            val hintIdentity = HintIdentity(identity.first.value, identity.second.value)
            // The vendor-callback hop is `CxrLinkWiringTest`'s; this is the composition hop, fed the way `create`
            // feeds it (the link callback calls the root's marshal method).
            link.onPanelShown = { validated, route -> root.panelShownArrived(validated, route) }
            link.onUiState = { validated, state -> root.onUiStateFromLink(validated, state) }

            assertTrue(link.onInbound(HintWireCodec.encode(
                HintCommand.PanelShown(hintIdentity.sessionId, hintIdentity.generation, AssistantRoute.WAKE),
            )))
            root.host.currentState
            assertTrue("the link's live visibility is what the production wiring reads", root.isPanelVisible())

            // A wake on that already-visible panel must display STATE 1 and arm the 10 s at once - the behavior a
            // missing `isPanelVisible` used to suppress (no STATE 1 at all, no timer).
            assertTrue(root.host.onEvent(WakeDetected(
                EventEnvelope(identity.first, identity.second, "wake-visible", 0L, 1L), "伊迪丝", 1.0,
            )))
            root.host.currentState
            assertEquals(listOf(AssistantRoute.WAKE to OfficialStatus.LISTENING.wire), display.states)

            clock.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS * 1_000_000L)
            scheduler.runDue()
            assertEquals(
                "the visible wake's 10 s window closes the round through the composition",
                listOf(AssistantRoute.WAKE),
                display.closes,
            )

            // A second wake, then UI_CLOSED: the plugin's own window is cancelled by the composition's seam.
            assertTrue(root.host.onEvent(WakeDetected(
                EventEnvelope(identity.first, identity.second, "wake-2", 0L, 1L), "伊迪丝", 1.0,
            )))
            root.host.currentState
            assertEquals("the second wake displayed its own STATE 1", 2, display.states.size)
            assertTrue(link.onInbound(HintWireCodec.encode(
                HintCommand.UiClosed(hintIdentity.sessionId, hintIdentity.generation, AssistantRoute.WAKE),
            )))
            root.host.currentState
            val closesBefore = display.closes.size
            clock.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS * 1_000_000L)
            scheduler.runDue()
            assertEquals("UI_CLOSED must cancel the plugin's listening window", closesBefore, display.closes.size)
        } finally {
            root.close()
        }
    }

    // ---------------------------------------------------------------- 0.24 S5 final review Important #1: orphan wake panel

    /**
     * The production wake decision (`HostCompositionRoot.emitWake`) with a real smart plugin and a real `CxrHintLink`,
     * so a `PANEL_OPEN` that leaves the phone is observed on the composition's own outbound path.
     *
     * A live clock mapping is published before every test, so an unguarded `requestPanelOpen` really would send:
     * "no `PANEL_OPEN`" therefore means the decision refused it, not that the link was not ready.
     */
    private inner class WakePanelRig : AutoCloseable {
        val clock = FakeClock(1_000_000_000L)
        val scheduler = ManualScheduler(clock)
        val sent = CopyOnWriteArrayList<String>()
        var chimes = 0
        var appStarts = 0
        val display = RecordingConversationDisplay()
        private val rootHolder = arrayOfNulls<HostCompositionRoot>(1)
        private val wiring = SmartWiring(
            SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
                profileId = "p-1", cooldownMillis = 5_000L),
            FixedProfiles(profile("p-1")), clock, scheduler,
            isPanelVisible = { rootHolder[0]?.isPanelVisible() ?: false })
        private val link = CxrHintLink(
            clock, scheduler,
            sendJson = { json -> sent += json; true },
            startGlassesApp = { onResult -> appStarts++; onResult(true); true },
        )
        val root: HostCompositionRoot

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { LiveDisplay() }, scheduler = scheduler,
                providers = mapOf(
                    Capability.Model to InertModel(),
                    Capability.Asr to StubAsrSession(),
                    Capability.Actions to NoActions(),
                    Capability.Confirmation to NoConfirmations(),
                    Capability.WakeWord to wiring.wakeWords,
                    Capability.Context to EmptyContextSignals(),
                    Capability.StorageAccess to NoStorageAccess(),
                    Capability.SystemOne to ReadySystemOne(),
                    Capability.Embedding to ReadyEmbedding(),
                    Capability.DeviceState to SyntheticDeviceState(),
                Capability.Perception to InertPerception(),
                Capability.ConversationDisplay to display,
                ),
                storageFor = { StubStorage() })
            root = HostCompositionRoot(HostRuntime(1, environment), MemoryProfileRepository(), InertModel(),
                environment, hintLink = link, smartWiring = wiring,
                rokidConnectionFactory = { FakeTransportConnection(null) })
            rootHolder[0] = root
            root.registerPlugin(wiring.plugin)
            root.attachCxrActivity(Any())
        }

        /** 开始 + the transport's live-session signal, then the clock exchange that makes a display real. */
        fun startEstablished() {
            root.start("smart-assistant")
            root.onSceneReady()
            val ping = sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
            clock.nowNanos = ping.p0 + 10_000_000
            assertTrue("the clock exchange must be answered", link.onInbound(HintWireCodec.encode(
                HintCommand.Pong(ping.syncId, ping.p0, ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000))))
            settle()
        }

        /** One detector emission through the production composition decision. */
        fun emitWake(word: String = "伊迪丝") {
            root.emitWake(root.host, word, 1.0) { chimes++ }
        }

        fun advance(millis: Long) {
            clock.advance(millis * 1_000_000L)
            scheduler.runDue()
        }

        /** Delivery runs on the host's executor; a command result is the barrier that proves the drain. */
        fun settle() { root.host.currentState }

        fun panelOpens(): List<HintCommand.PanelOpen> =
            sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.PanelOpen }

        fun closePanelThroughLink() { assertTrue(link.close(AssistantRoute.WAKE)) }

        override fun close() = root.close()
    }

    @Test
    fun aWakeWithoutALiveIdentityDoesNotRequestThePanel() {
        WakePanelRig().use { rig ->
            rig.startEstablished()
            // Activation race: the link is established and live, but no plugin owns the host identity (paused).
            rig.root.pause()
            rig.sent.clear()
            val appStartsBefore = rig.appStarts

            rig.emitWake()
            rig.settle()

            assertEquals("no chime for a wake no live session can hear", 0, rig.chimes)
            assertTrue("an inactive plugin must not open the overlay: ${rig.sent}", rig.panelOpens().isEmpty())
            assertEquals("and the scene must not be started for it", appStartsBefore, rig.appStarts)
        }
    }

    @Test
    fun anAcceptedWakeStillRequestsThePanel() {
        WakePanelRig().use { rig ->
            rig.startEstablished()
            rig.sent.clear()

            rig.emitWake()
            rig.settle()

            assertEquals(1, rig.chimes)
            val opens = rig.panelOpens()
            assertEquals("the accepted wake must still open the resident panel", 1, opens.size)
            assertEquals(AssistantRoute.WAKE, opens.single().route)
            assertEquals("a live mapping sends PANEL_OPEN without starting the scene", 0, rig.appStarts)
        }
    }

    @Test
    fun aWakeTheSceneCooldownRefusesDoesNotRequestThePanel() {
        WakePanelRig().use { rig ->
            rig.startEstablished()

            // An admitted wake opens the panel and starts the 5 s scene cooldown.
            rig.emitWake()
            rig.settle()
            assertEquals(1, rig.panelOpens().size)

            // The panel never comes up, so the cycle's bounded acquisition wait ends the turn at 3 s while the
            // cooldown still runs. Clear the link's open attempt so an unguarded request would be a visible new
            // PANEL_OPEN - exactly the orphan overlay the final review found.
            rig.advance(3_000)
            rig.closePanelThroughLink()
            rig.sent.clear()
            val appStartsBefore = rig.appStarts

            rig.emitWake()
            rig.settle()

            assertTrue("a refused wake must not open the overlay: ${rig.sent}", rig.panelOpens().isEmpty())
            assertEquals("and must not start the scene", appStartsBefore, rig.appStarts)

            // The gate is the cooldown, not a permanent block: once it expires the next wake opens again.
            rig.advance(2_000)
            rig.emitWake()
            rig.settle()
            assertEquals("a wake after the cooldown must open again", 1, rig.panelOpens().size)
        }
    }

    @Test
    fun noWakeMeansNoModelRequestIsMade() {
        withRig { rig ->
            assertEquals(com.sheen.rokid.host.ActivationResult.Activated, rig.activateSmart())
            rig.settle()

            assertTrue("nothing was said, so nothing may be handled", rig.smart.handled.isEmpty())
            assertTrue("no wake and no transcript must mean no model request", rig.model.requests.isEmpty())
        }
    }
}
