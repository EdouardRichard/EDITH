package com.sheen.rokid.phone.bridge

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.audio.PcmRecordDevice
import com.sheen.rokid.phone.audio.PhoneMicFormat
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartWiring
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.withP3Sink
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
import com.sheen.rokid.storage.ProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.4 Task 6 (`D199`/`D200`): the detached session's ears come from the phone, the glasses session's from the CXR
 * uplink, and the loop that frames both is the same one.
 *
 * The audio chain caches its `AudioIngressLoop` on the first ingress it sees. A session that starts detached and the
 * next one that starts attached therefore must **not** share that cache: a stale loop would poll the wrong source for
 * a whole session, which looks exactly like "the microphone works but nobody wakes". These tests drive the composition
 * (`armAudioEpoch`/`disarmAudioEpoch`) with recording ports instead of a real recorder, so the source selection, the
 * start/stop ordering and the frame counters are pinned without a phone:
 *
 * - detached arm opens the phone ingress, starts capture then the loop, and never touches the transport port;
 * - glasses arm still starts the transport and opens no microphone;
 * - disarm stops the loop **before** the capture (the loop must not poll audio a released recorder owned);
 * - pause releases the capture, and resume captures through a fresh device;
 * - a mode change between sessions builds the loop against the ingress the new mode selects, and `P3_FRAME` still
 *   counts exactly once per delivered frame.
 */
class DetachedAudioWiringTest {

    private fun profile(id: String) = ModelProfile(id, 3, "Synthetic $id", "Synthetic",
        "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
        object : SecretHandle { override val id = "draft-placeholder" },
        4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT, InputModality.IMAGE),
        setOf(OutputModality.TEXT), emptyList())

    // ---- fakes (the same shapes the composition-shaped rigs use) -----------------------------------------------

    private class SpeechVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(true, false)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class RecordingAsr : StreamingAsrEngine {
        val frames = CopyOnWriteArrayList<PcmFrame>()
        override fun accept(frame: PcmFrame): List<AsrHypothesis> {
            frames += frame
            return emptyList()
        }
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
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class StubAsrSession : AsrSession {
        override fun listen(listener: (com.sheen.rokid.contracts.TranscriptRevision) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class EmptyContextSignals : ContextSignals {
        override fun snapshot() = ContextSnapshot(0L, emptyMap())
        override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class SyntheticDeviceState : DeviceStateProvider {
        override fun profile() = DeviceProfile("", "", null, "", 0, 0, 0, 0, null, null, null, null, "", "", "", emptyList(), emptySet())
        override fun state() = DeviceState(0L, "UTC", "2026-10-20", "星期一", "00:00")
    }

    private class InertPerception : PerceptionProvider {
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

    private class NoStorageAccess : StorageAccess {
        override fun listFiles(dirPath: String, suffix: String): List<String> = emptyList()
        override fun readFile(path: String): String? = null
        override fun deleteFile(path: String): Boolean = false
    }

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

    private class NoConversationDisplay : ConversationDisplay {
        override fun userMessage(route: AssistantRoute, text: String) = Unit
        override fun assistantMessage(route: AssistantRoute, text: String) = Unit
        override fun working(route: AssistantRoute) = Unit
        override fun idle(route: AssistantRoute) = Unit
    }

    private class FixedProfiles(private val saved: List<ModelProfile>) : ProfileRepository {
        constructor(profile: ModelProfile) : this(listOf(profile))
        override fun list(): List<ModelProfile> = saved
        override fun get(id: String): ModelProfile? = saved.firstOrNull { it.id == id }
        override fun save(profile: ModelProfile) = profile
        override fun delete(id: String) = Unit
        override fun verify(id: String, result: com.sheen.rokid.plugin.CapabilityVerification) = Unit
        override fun listenChanges(listener: (String) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class FakeTransportConnection(private val port: InputPort?) : CxrConnection {
        override var ready = true
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String) = true
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
        override fun inputPortOrNull(): InputPort? = port
    }

    /** One port that records the order it was started/stopped in, shared with the fake capture device. */
    private class OrderedPort(private val name: String, private val order: MutableList<String>) : InputPort {
        val recorder = RecordingInputPort()
        override fun start(epoch: InputEpoch) { order += "start:$name"; recorder.start(epoch) }
        override fun stop(epoch: InputEpoch) { order += "stop:$name"; recorder.stop(epoch) }
    }

    /** A recorder that produces no audio but records begin/release, which is what the ordering assertions need. */
    private class FakePhoneDevice(private val order: MutableList<String>) : PcmRecordDevice {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile private var recording = false
        override fun start(): Boolean {
            order += "start:capture"
            starts.incrementAndGet()
            recording = true
            return true
        }
        override fun read(buffer: ShortArray): Int {
            while (recording) Thread.sleep(5)
            return 0
        }
        override fun stop() {
            order += "stop:capture"
            stops.incrementAndGet()
            recording = false
        }
    }

    private fun detachedGate(detached: Boolean): DetachGate {
        val store = SmartSettingsStore(TestBlobs())
        store.save(SmartSettings(glassesDetached = detached))
        return DetachGate(store).also { it.load() }
    }

    /**
     * The real `HostCompositionRoot` with a recording loop port, the real smart wiring, and - when
     * [withConnection] - a CXR connection carrying a recording transport port. The phone microphone is a fake device
     * behind the composition's own factory seam, so `armAudioEpoch` runs the production branch end to end.
     */
    private inner class Rig(detached: Boolean, private val withConnection: Boolean = true) {
        val clock = FakeClock(0)
        val order = CopyOnWriteArrayList<String>()
        val loop = OrderedPort("loop", order)
        val transport = OrderedPort("transport", order)
        val phoneIngress = PcmIngress(clock)
        val devices = CopyOnWriteArrayList<FakePhoneDevice>()
        val root: HostCompositionRoot

        init {
            val chosen = profile("p-1")
            val wiring = SmartWiring(
                SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
                    profileId = chosen.id, cooldownMillis = 3000L),
                FixedProfiles(chosen), clock, ManualScheduler(clock))
            val environment = CxrHostEnvironment(clock = clock, input = loop, liveOutput = { LiveDisplay() },
                scheduler = ManualScheduler(clock),
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
                    Capability.ConversationDisplay to NoConversationDisplay(),
                ),
                storageFor = { StubStorage() })
            root = HostCompositionRoot(HostRuntime(1, environment), MemoryProfileRepository(), InertModel(),
                environment,
                smartWiring = wiring,
                rokidConnectionFactory = if (withConnection) { { FakeTransportConnection(transport) } } else null,
                detachGate = detachedGate(detached),
                phoneIngress = phoneIngress,
                phoneMicDeviceFactory = { FakePhoneDevice(order).also { devices += it } })
            root.registerPlugin(wiring.plugin)
        }

        /** The production start path (`MainActivity` attaches, the start establishes the link, then readiness arrives). */
        fun activate(): Boolean {
            root.attachCxrActivity(Any())
            root.start("smart-assistant")
            root.onSceneReady()
            return root.state.value.errorCode == null && root.currentAudioEpoch() != null
        }

        fun close() = root.close()
    }

    private fun withRig(detached: Boolean, withConnection: Boolean = true, block: (Rig) -> Unit) {
        val rig = Rig(detached, withConnection)
        try { block(rig) } finally { rig.close() }
    }

    // ---- detached arm -------------------------------------------------------------------------------------------

    @Test
    fun detachedArmOpensThePhoneIngressAndStartsOnlyTheLoop() {
        withRig(detached = true) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue("a detached session must arm without a CXR transport start", rig.activate())
                captured.toList()
            }
            val epoch = requireNotNull(rig.root.currentAudioEpoch())

            assertTrue("the transport port must not be started in detached mode",
                rig.transport.recorder.started.isEmpty())
            assertEquals("only the poll loop runs; the phone ingress is opened directly by the arm",
                listOf(epoch), rig.loop.recorder.started)
            assertEquals("capture must begin before the loop polls it",
                listOf("start:capture", "start:loop"), rig.order.toList())
            assertEquals("the phone ingress is open with the session's epoch, so its offers are accepted",
                PcmOffer.ACCEPTED, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            assertEquals("exactly one recorder for one epoch", 1, rig.devices.size)
            assertTrue(rig.devices.single().starts.get() == 1)
            // 0.4 Task 6 (spec §7.2): the new evidence line attributes the audio to the phone source.
            assertEquals(
                listOf("P4_AUDIO state=armed source=phone_mic transport=false loop=true reason=ARMED"),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
        }
    }

    @Test
    fun detachedArmDoesNotRequireACxrConnection() {
        withRig(detached = true, withConnection = false) { rig ->
            assertTrue("the detached source is the phone; no connection may be required", rig.activate())
            val epoch = requireNotNull(rig.root.currentAudioEpoch())
            assertEquals(listOf(epoch), rig.loop.recorder.started)
            assertEquals(PcmOffer.ACCEPTED, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            assertNull("a successful detached arm leaves no error", rig.root.state.value.errorCode)
        }
    }

    @Test
    fun glassesArmStillStartsTheTransportAndOpensNoMicrophone() {
        withRig(detached = false) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                captured.toList()
            }
            val epoch = requireNotNull(rig.root.currentAudioEpoch())

            assertEquals("the glasses path is unchanged: the transport opens its ingress first",
                listOf(epoch), rig.transport.recorder.started)
            assertEquals(listOf(epoch), rig.loop.recorder.started)
            assertEquals(listOf("start:transport", "start:loop"), rig.order.toList())
            assertTrue("the phone microphone must not be opened in glasses mode", rig.devices.isEmpty())
            assertEquals("the phone ingress was never opened for this epoch",
                PcmOffer.STALE_EPOCH, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            // 0.4 evidence still attributes a glasses session to the CXR source (spec §7.2).
            assertEquals(
                listOf("P4_AUDIO state=armed source=cxr transport=true loop=true reason=ARMED"),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
        }
    }

    // ---- teardown -----------------------------------------------------------------------------------------------

    @Test
    fun stopStopsTheLoopBeforeThePhoneCapture() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val epoch = requireNotNull(rig.root.currentAudioEpoch())
            rig.order.clear()

            val lines = withP3Sink { captured ->
                rig.root.stop()
                captured.toList()
            }

            assertEquals("the loop must stop before the device it was polling is released",
                listOf("stop:loop", "stop:capture"), rig.order.toList())
            assertEquals("the device is released exactly once", 1, rig.devices.single().stops.get())
            assertNull(rig.root.currentAudioEpoch())
            assertEquals("a released phone ingress accepts nothing",
                PcmOffer.STALE_EPOCH, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            assertEquals(
                listOf("P4_AUDIO state=disarmed source=phone_mic transport=false loop=false reason=DISARMED"),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
        }
    }

    @Test
    fun pauseReleasesTheCaptureAndResumeStartsAFreshDevice() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val first = rig.devices.single()

            rig.root.pause()

            assertNull("pause disarms the epoch", rig.root.currentAudioEpoch())
            assertEquals("pause must release the recorder", 1, first.stops.get())

            rig.root.resume()

            assertNotNull("resume re-arms the epoch", rig.root.currentAudioEpoch())
            assertEquals("resume must not reuse a released recorder", 2, rig.devices.size)
            assertEquals(1, rig.devices.last().starts.get())
            assertEquals("the loop starts again for the new epoch", 2, rig.loop.recorder.started.size)
        }
    }

    // ---- mode change between sessions ----------------------------------------------------------------------------

    @Test
    fun aModeChangeBetweenSessionsPointsTheLoopAtTheCurrentIngressAndKeepsTheFrameCounters() {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val cxrIngress = PcmIngress(clock)
        val phoneIngress = PcmIngress(clock)
        var detached = false
        val asr = RecordingAsr()
        val chain = CxrAudioChain.assemble(
            ingress = { if (detached) phoneIngress else cxrIngress },
            framer = PcmFramer(PhoneMicFormat.VERIFIED, 20),
            vad = SpeechVad(), asr = asr, speaker = SilentSpeaker(), wake = null,
            scheduler = scheduler, clock = clock, emit = {})
        val epoch1 = InputEpoch(SessionId("s-mode"), Generation(1), 1)
        val epoch2 = InputEpoch(SessionId("s-mode"), Generation(1), 2)
        try {
            val lines = withP3Sink { captured ->
                cxrIngress.open(epoch1, PhoneMicFormat.VERIFIED)
                chain.input.start(epoch1)
                chain.onOffer(PcmOffer.ACCEPTED)
                cxrIngress.offer(epoch1, ByteArray(PhoneMicFormat.CHUNK_BYTES))
                clock.advance(20_000_000)
                scheduler.runDue()
                assertEquals("the first session polls the CXR ingress", 1, asr.frames.size)

                chain.input.stop(epoch1)
                detached = true
                phoneIngress.open(epoch2, PhoneMicFormat.VERIFIED)
                chain.input.start(epoch2)
                chain.onOffer(PcmOffer.ACCEPTED)
                phoneIngress.offer(epoch2, ByteArray(PhoneMicFormat.CHUNK_BYTES))
                clock.advance(20_000_000)
                scheduler.runDue()

                chain.input.stop(epoch2)
                captured.toList()
            }

            assertEquals("the second session must poll the phone ingress, not the cached CXR loop",
                2, asr.frames.size)
            assertEquals(epoch2, asr.frames.last().epoch)
            assertEquals(
                "P3_FRAME must still report one line per stopped epoch with exact counts",
                listOf(
                    "P3_FRAME frames=1 bytes=640 accepted=1 overflow=0 unknownFormat=0 staleEpoch=0 gaps=0",
                    "P3_FRAME frames=1 bytes=640 accepted=1 overflow=0 unknownFormat=0 staleEpoch=0 gaps=0",
                ),
                lines.filter { it.startsWith("P3_FRAME") },
            )
        } finally {
            chain.close()
        }
    }
}
