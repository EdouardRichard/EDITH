package com.sheen.rokid.phone.audio

/**
 * 0.5.1 Task 3: the composition-shaped rig shared by [DictationWiringTest] and [DictationOwnerTest].
 *
 * Extracted from `DictationWiringTest` unchanged (only visibility widened to `internal`) so the owner-isolation tests
 * drive the same **real** `CxrAudioChain`/`SpeechPipeline`/`HostCompositionRoot` as the 0.4 dictation tests, instead of
 * a second, drifting copy of that setup. The class KDoc there explains what the rig proves; the fakes are the minimal
 * set: scripted VAD/ASR/wake engines and a fake recorder/CXR transport around the production loop and pipeline.
 */

import android.content.Intent
import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.audio.WakeWordEngine
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.bridge.CxrConnection
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartWiring
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.AsrSession
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelInfo
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
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.plugin.StorageAccess
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.storage.ProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ---- fakes (the composition-shaped rigs' minimal set) -------------------------------------------------------

internal fun profile(id: String) = ModelProfile(id, 3, "Synthetic $id", "Synthetic",
    "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
    object : SecretHandle { override val id = "draft-placeholder" },
    4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT, InputModality.IMAGE),
    setOf(OutputModality.TEXT), emptyList())

/** The pipeline's VAD: tests script whether a frame starts a turn and whether it is the endpoint. */
internal class PipelineVad : VadEngine {
    @Volatile var decision = VadDecision(false, false)

    /**
     * When positive, this many of the next accepts report a speech endpoint (then [decision] applies again).
     *
     * Lets one multi-frame batch contain the endpoint that ends the dictation, with more frames behind it in the
     * same batch - the tail the quiesce has to stop (fix 2, finding 4b).
     */
    @Volatile var endpointAccepts = 0

    /** A scripted provider failure: one accept throws, which must pause the shared pipeline (R-CD-3). */
    @Volatile var failure: RuntimeException? = null

    override fun accept(frame: com.sheen.rokid.audio.PcmFrame): VadDecision {
        failure?.let { throw it }
        if (endpointAccepts > 0) {
            endpointAccepts--
            return VadDecision(true, true)
        }
        return decision
    }

    override fun reset() = Unit
    override fun close() = Unit
}

/** The wake detector's own VAD: always speech, so "the detector saw a frame" is observable via its engine. */
internal class AlwaysSpeechVad : VadEngine {
    override fun accept(frame: com.sheen.rokid.audio.PcmFrame) = VadDecision(true, false)
    override fun reset() = Unit
    override fun close() = Unit
}

internal class RecordingWakeEngine : WakeWordEngine {
    val frames = CopyOnWriteArrayList<com.sheen.rokid.audio.PcmFrame>()
    override fun accept(frame: com.sheen.rokid.audio.PcmFrame): List<String> {
        frames += frame
        return emptyList()
    }

    override fun reset() = Unit
    override fun close() = Unit
}

internal class FinishingAsr : StreamingAsrEngine {
    val frames = CopyOnWriteArrayList<com.sheen.rokid.audio.PcmFrame>()
    val finishes = AtomicInteger()
    @Volatile var finalText = "听写结果"

    /** Runs inside [finish], i.e. while the controller is forcing the final (fix 2, finding 4b ordering probe). */
    @Volatile var onFinish: (() -> Unit)? = null

    override fun accept(frame: com.sheen.rokid.audio.PcmFrame): List<AsrHypothesis> {
        frames += frame
        return emptyList()
    }

    override fun finish(): AsrHypothesis {
        finishes.incrementAndGet()
        onFinish?.invoke()
        return AsrHypothesis(finalText, true)
    }

    override fun reset() = Unit
    override fun close() = Unit
}

internal class SilentSpeaker : SpeakerEngine {
    override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
        object : CancellationHandle { override fun cancel() = Unit }

    override fun reset() = Unit
    override fun close() = Unit
}

internal class NoActions : ActionExecutor {
    override fun descriptors(): List<ActionDescriptor> = emptyList()
    override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Refused, "UNKNOWN_ACTION")
    override fun cancel() = Unit
}

internal class NoConfirmations : ConfirmationChannel {
    override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
        object : CancellationHandle { override fun cancel() = Unit }
}

internal class LiveDisplay : HintDevice {
    override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle =
        object : CancellationHandle { override fun cancel() = Unit }

    override fun clear(sessionId: SessionId, generation: Generation) = Unit
}

internal class CountingModel : ModelClient {
    val requests = AtomicInteger()
    override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        requests.incrementAndGet()
        return object : CancellationHandle { override fun cancel() = Unit }
    }
}

internal class StubAsrSession : AsrSession {
    override fun listen(listener: (com.sheen.rokid.contracts.TranscriptRevision) -> Unit): CancellationHandle =
        object : CancellationHandle { override fun cancel() = Unit }
}

internal class EmptyContextSignals : ContextSignals {
    override fun snapshot() = ContextSnapshot(0L, emptyMap())
    override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle =
        object : CancellationHandle { override fun cancel() = Unit }
}

internal class SyntheticDeviceState : DeviceStateProvider {
    override fun profile() = DeviceProfile("", "", null, "", 0, 0, 0, 0, null, null, null, null, "", "", "", emptyList(), emptySet())
    override fun state() = DeviceState(0L, "UTC", "2026-10-20", "星期一", "00:00")
}

internal class InertPerception : PerceptionProvider {
    private val snapshot = PerceptionSnapshot("snap-test", 0L, coverage = PerceptionCoverage(
        screen = "unavailable(no_accessibility)", notifications = "unavailable(no_access)",
        media = "unavailable(no_session)", calendar = "empty", acoustic = "unavailable(no_event)",
        clipboard = "unavailable(background_restricted)", vision = "disabled",
        glasses = "unavailable(no_state)", device = "partial"))

    override fun beginTurn() = snapshot
    override fun snapshot(include: Set<PerceptionSource>, filter: PerceptionFilter, limit: Int?) = snapshot
    override fun afterAction() = snapshot
    override fun screenshot(): PerceptionScreenshotResult = PerceptionScreenshotResult.Unavailable("test")
    override fun endTurn() = Unit
}

internal class NoStorageAccess : StorageAccess {
    override fun listFiles(dirPath: String, suffix: String): List<String> = emptyList()
    override fun readFile(path: String): String? = null
    override fun deleteFile(path: String): Boolean = false
}

internal class ReadySystemOne : SystemOneService {
    override fun ask(state: String, question: SystemOneQuestion): SystemOneResult =
        SystemOneResult.Success(SystemOneAnswer.Noul(probability = 0.5, confidence = 0.5), latencyMs = 1.0)

    override val isAvailable = true
    override val status = SystemOneStatus.READY
    override val modelInfo: ModelInfo? = null
}

internal class ReadyEmbedding : EmbeddingService {
    override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(256))
    override val isAvailable = true
    override val modelInfo: ModelInfo? = null
    override val dimensions = 256
}

internal class StubStorage : StorageHandle {
    private val values = mutableMapOf<String, ByteArray>()
    override fun read(key: String): ByteArray? = values[key]
    override fun write(key: String, value: ByteArray) { values[key] = value }
    override fun delete(key: String) { values.remove(key) }
    override fun clear() { values.clear() }
}

internal class NoConversationDisplay : ConversationDisplay {
    override fun userMessage(route: AssistantRoute, text: String) = Unit
    override fun assistantMessage(route: AssistantRoute, text: String) = Unit
    override fun working(route: AssistantRoute) = Unit
    override fun idle(route: AssistantRoute) = Unit
}

internal class FixedProfiles(private val saved: List<ModelProfile>) : ProfileRepository {
    constructor(profile: ModelProfile) : this(listOf(profile))
    override fun list(): List<ModelProfile> = saved
    override fun get(id: String): ModelProfile? = saved.firstOrNull { it.id == id }
    override fun save(profile: ModelProfile) = profile
    override fun delete(id: String) = Unit
    override fun verify(id: String, result: com.sheen.rokid.plugin.CapabilityVerification) = Unit
    override fun listenChanges(listener: (String) -> Unit): CancellationHandle =
        object : CancellationHandle { override fun cancel() = Unit }
}

/** The CXR connection the composition builds lazily; records every command/port call so "no CXR restart" is real. */
internal class FakeConnection(
    private val port: InputPort?,
    private val commands: AtomicInteger,
) : CxrConnection {
    override var ready = true
    override fun connect(activity: Any) = Unit
    override fun onAuthorizationResult(resultCode: Int, data: Intent?): CxrAuthorizationOutcome? = null
    override fun sendCommand(json: String): Boolean {
        commands.incrementAndGet()
        return true
    }

    override fun onInboundCommand(listener: (String) -> Unit) = Unit
    override fun close() = Unit
    override fun inputPortOrNull(): InputPort? = port
}

internal class FakePhoneDevice : com.sheen.rokid.phone.audio.PcmRecordDevice {
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val wakes = AtomicInteger()
    @Volatile private var recording = false
    @Volatile var failReadAt = -1
    private val reads = AtomicInteger()

    override fun start(): Boolean {
        starts.incrementAndGet()
        recording = true
        return true
    }

    override fun read(buffer: ShortArray): Int {
        if (reads.incrementAndGet() == failReadAt) return -1
        while (recording) Thread.sleep(5)
        return 0
    }

    override fun wake() {
        wakes.incrementAndGet()
        recording = false
    }

    override fun stop() {
        stops.incrementAndGet()
        recording = false
    }
}

internal fun detachedGate(detached: Boolean): DetachGate {
    val store = SmartSettingsStore(TestBlobs())
    store.save(SmartSettings(glassesDetached = detached))
    return DetachGate(store).also { it.load() }
}

internal class DictationRig(
    detached: Boolean,
    withConnection: Boolean = true,
    private val microphoneAllowed: Boolean = true,
    private val queueDispatches: Boolean = false,
    private val configurePhoneDevice: FakePhoneDevice.() -> Unit = {},
    private val withScheduler: Boolean = true,
) {
    val clock = FakeClock(0)
    val scheduler = ManualScheduler(clock)
    val pipelineVad = PipelineVad()
    val asr = FinishingAsr()
    val wakeEngine = RecordingWakeEngine()
    val model = CountingModel()
    val phoneIngress = PcmIngress(clock)
    val cxrIngress = PcmIngress(clock)
    val transportPort = RecordingInputPort()
    val connectionCommands = AtomicInteger()
    val devices = CopyOnWriteArrayList<FakePhoneDevice>()
    /** Every outcome the chain forwards, as the composition's `EdithAudio` sink sees it on a device. */
    val faults = CopyOnWriteArrayList<String>()
    /** Every outcome the chain forwards to diagnostics, including the silent ones. */
    val offers = CopyOnWriteArrayList<PcmOffer>()
    /** Pipeline turns revoked by a framing gap/fault: the damage a stolen framer reset leaves behind. */
    val revokedTurns = CopyOnWriteArrayList<String>()
    /** Every event the production chain emit hands to the host: the only observable of the transcript route. */
    val delivered = CopyOnWriteArrayList<com.sheen.rokid.contracts.HostEvent>()
    private val dispatchQueue = CopyOnWriteArrayList<() -> Unit>()
    val root: HostCompositionRoot
    val chain: CxrAudioChain

    /** Runs on the thread opening a dictation capture, after the fake device exists (fix 2 ordering assertions). */
    var onPhoneDeviceCreated: (() -> Unit)? = null

    init {
        val chosen = profile("p-1")
        val gate = detachedGate(detached)
        val wiring = SmartWiring(
            SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
                profileId = chosen.id, cooldownMillis = 3000L),
            FixedProfiles(chosen), clock, ManualScheduler(clock))
        val hostHolder = arrayOfNulls<HostRuntime>(1)
        val wakeVad = AlwaysSpeechVad()
        val wakeDetector = WakeWordDetector(wakeVad, wakeEngine, emit = { _, _ -> }, clock = clock)
        chain = CxrAudioChain.assemble(
            ingress = { if (gate.enabled.value) phoneIngress else cxrIngress },
            framer = PcmFramer(PhoneMicFormat.VERIFIED, 20),
            vad = pipelineVad, asr = asr, speaker = SilentSpeaker(),
            wake = com.sheen.rokid.phone.app.WiredWakeDetector(wakeDetector, wakeVad),
            scheduler = scheduler, clock = clock,
            emit = { event -> delivered += event; hostHolder[0]?.onEvent(event) },
            cancelTurn = { turnId -> revokedTurns += turnId },
            onFault = { code -> faults += code }, onOffer = { offer -> offers += offer })
        val environment = CxrHostEnvironment(clock = clock, input = chain.input, liveOutput = { LiveDisplay() },
            scheduler = scheduler,
            providers = mapOf(
                Capability.Model to model,
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
                Capability.ConversationDisplay to NoConversationDisplay(),
            ),
            storageFor = { StubStorage() })
        hostHolder[0] = HostRuntime(1, environment)
        root = HostCompositionRoot(hostHolder[0]!!, MemoryProfileRepository(), model, environment,
            smartWiring = wiring,
            rokidConnectionFactory = if (withConnection) {
                { FakeConnection(transportPort, connectionCommands) }
            } else null,
            detachGate = gate,
            dispatchHostEvent = { task -> if (queueDispatches) dispatchQueue += task else task() },
            phoneIngress = phoneIngress,
            phoneMicDeviceFactory = {
                FakePhoneDevice().also { device ->
                    device.configurePhoneDevice()
                    devices += device
                    onPhoneDeviceCreated?.invoke()
                }
            },
            deadlineScheduler = if (withScheduler) scheduler else null,
            microphoneGranted = { microphoneAllowed },
            // The composition owns the chain in production; this rig wires the same real chain itself.
        )
        root.audioChain = chain
        root.registerPlugin(wiring.plugin)
    }

    /** The production start path: attach, start, scene-ready. */
    fun activate(): Boolean {
        root.attachCxrActivity(Any())
        root.start("smart-assistant")
        root.onSceneReady()
        val epoch = root.currentAudioEpoch()
        if (epoch != null && !root.detachGate.enabled.value) {
            // The fake transport does not open the real ingress; the test opens the CXR source it will feed.
            cxrIngress.open(epoch, PhoneMicFormat.VERIFIED)
        }
        return root.state.value.errorCode == null && epoch != null
    }

    fun epoch(): InputEpoch = requireNotNull(root.currentAudioEpoch())

    /** Offers one 20 ms chunk to the phone source and runs the next loop tick. */
    fun feedPhone() {
        assertEquals("the phone ingress must accept the chunk", com.sheen.rokid.audio.PcmOffer.ACCEPTED,
            phoneIngress.offer(epoch(), ByteArray(PhoneMicFormat.CHUNK_BYTES)))
        clock.advance(20_000_000)
        scheduler.runDue()
    }

    /** Offers one 20 ms chunk to the CXR source and runs the next loop tick. */
    fun feedCxr() {
        assertEquals("the CXR ingress must accept the chunk", PcmOffer.ACCEPTED,
            cxrIngress.offer(epoch(), ByteArray(PhoneMicFormat.CHUNK_BYTES)))
        clock.advance(20_000_000)
        scheduler.runDue()
    }

    /**
     * Offers one 20 ms CXR chunk and reports its outcome through the **real** transport seam.
     *
     * `RokidSessionTransport.received` forwards every `PcmIngress.offer` result to
     * `HostCompositionRoot.onPcmOffer`, not just the failures, so a test that only calls the chain directly
     * would miss the ACCEPTED outcomes that build a loop.
     */
    fun offerCxr(): PcmOffer {
        val offer = cxrIngress.offer(epoch(), ByteArray(PhoneMicFormat.CHUNK_BYTES))
        root.onPcmOffer(offer)
        return offer
    }

    /** Fills the paused CXR ingress past its 2 s capacity; the last outcome is normally [PcmOffer.OVERFLOW]. */
    fun offerCxrUntilOverflow(limit: Int = 400): PcmOffer {
        var last = PcmOffer.ACCEPTED
        for (attempt in 1..limit) {
            last = offerCxr()
            if (last == PcmOffer.OVERFLOW) return last
        }
        return last
    }

    fun runDispatches() {
        while (dispatchQueue.isNotEmpty()) dispatchQueue.removeAt(0).invoke()
    }

    fun pendingDispatches(): Int = dispatchQueue.size

    fun close() = root.close()
}

internal fun withRig(
    detached: Boolean,
    withConnection: Boolean = true,
    microphoneAllowed: Boolean = true,
    queueDispatches: Boolean = false,
    configurePhoneDevice: FakePhoneDevice.() -> Unit = {},
    withScheduler: Boolean = true,
    block: (DictationRig) -> Unit,
) {
    val rig = DictationRig(detached, withConnection, microphoneAllowed, queueDispatches, configurePhoneDevice, withScheduler)
    try {
        block(rig)
    } finally {
        rig.close()
    }
}

internal fun awaitCondition(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
    assertTrue("timed out waiting for the capture failure to reach the dispatcher", condition())
}

internal fun stateOf(rig: DictationRig) = rig.root.dictationUi.value.state

/** Runs one whole dictation to its final (start, one speech frame, stop) and returns the delivered final. */
internal fun dictatedFinal(rig: DictationRig, text: String): DictationFinal {
    rig.asr.finalText = text
    assertEquals("the dictation starts on the armed session", DictationStartResult.Accepted, rig.root.startDictation())
    rig.pipelineVad.decision = VadDecision(true, false)
    rig.feedPhone()
    rig.root.stopDictation(DictationTarget.COMPOSER)
    rig.pipelineVad.decision = VadDecision(false, false)
    return requireNotNull(rig.root.dictationUi.value.final) { "the stop must deliver a final" }
}
