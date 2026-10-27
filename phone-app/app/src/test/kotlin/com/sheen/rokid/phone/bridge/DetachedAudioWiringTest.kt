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
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.audio.PcmRecordDevice
import com.sheen.rokid.phone.audio.PhoneMicCapture
import com.sheen.rokid.phone.audio.PhoneMicFormat
import com.sheen.rokid.phone.audio.WakeLockBridge
import com.sheen.rokid.phone.audio.WakeLockHold
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
import java.util.concurrent.TimeUnit
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

    /**
     * A recorder that produces no audio but records begin/release, which is what the ordering assertions need.
     *
     * [startResult] and [failReadAt]/[throwAtRead] make the two §4.5 failures driveable: a microphone that cannot
     * begin at arm time, and one that dies after the session already says it is listening. [failureTriggered] is the
     * signal the test waits on before it runs the composition's queued dispatcher.
     */
    private class FakePhoneDevice(private val order: MutableList<String>) : PcmRecordDevice {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val wakes = AtomicInteger()
        val reads = AtomicInteger()
        val failureTriggered = AtomicInteger()
        @Volatile private var recording = false
        @Volatile var startResult = true
        @Volatile var failReadAt = -1
        @Volatile var throwAtRead = -1

        override fun start(): Boolean {
            order += "start:capture"
            starts.incrementAndGet()
            recording = startResult
            return startResult
        }

        override fun read(buffer: ShortArray): Int {
            val read = reads.incrementAndGet()
            if (read == throwAtRead) {
                failureTriggered.incrementAndGet()
                throw IllegalStateException("fake phone recorder died")
            }
            if (read == failReadAt) {
                failureTriggered.incrementAndGet()
                return -1
            }
            while (recording) Thread.sleep(5)
            return 0
        }

        override fun wake() {
            wakes.incrementAndGet()
            recording = false
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
     * 0.4 Task 8: the platform seam of the CPU wake lock. The [root] receives the same [WakeLockHold] this bridge
     * backs, so a test can assert both the platform call counts and what the `P4_AUDIO` evidence line reported.
     */
    private class RecordingWakeLockBridge(private val order: MutableList<String>? = null) : WakeLockBridge {
        val acquires = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile private var held = false
        @Volatile var rejectAcquire = false
        @Volatile var failure: String? = null

        override fun acquire(): Boolean {
            acquires.incrementAndGet()
            order?.add("acquire:wake")
            if (rejectAcquire) {
                failure = "ACQUIRE_REFUSED"
                return false
            }
            held = true
            return true
        }

        override fun release() {
            releases.incrementAndGet()
            order?.add("release:wake")
            held = false
        }

        override fun isHeld(): Boolean = held

        override fun lastFailure(): String? = failure
    }

    /**
     * The real `HostCompositionRoot` with a recording loop port, the real smart wiring, and - when
     * [withConnection] - a CXR connection carrying a recording transport port. The phone microphone is a fake device
     * behind the composition's own factory seam, so `armAudioEpoch` runs the production branch end to end.
     *
     * [queueDispatches] keeps [HostCompositionRoot]'s dispatcher seam out of the capture thread: a failure task is
     * queued and the test runs it with [runDispatches], exactly as production's deadline scheduler would. The
     * default inline seam stays for the success paths, where nothing is ever dispatched.
     */
    private inner class Rig(
        detached: Boolean,
        private val withConnection: Boolean = true,
        private val queueDispatches: Boolean = false,
        private val configurePhoneDevice: FakePhoneDevice.() -> Unit = {},
        configureWakeBridge: RecordingWakeLockBridge.() -> Unit = {},
    ) {
        val clock = FakeClock(0)
        val order = CopyOnWriteArrayList<String>()
        val loop = OrderedPort("loop", order)
        val transport = OrderedPort("transport", order)
        val phoneIngress = PcmIngress(clock)
        val devices = CopyOnWriteArrayList<FakePhoneDevice>()
        val wakeBridge = RecordingWakeLockBridge(order).also(configureWakeBridge)
        val wakeLock = WakeLockHold(wakeBridge)
        private val dispatchQueue = CopyOnWriteArrayList<() -> Unit>()
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
                dispatchHostEvent = { task -> if (queueDispatches) dispatchQueue += task else task() },
                phoneIngress = phoneIngress,
                phoneMicDeviceFactory = {
                    FakePhoneDevice(order).also { devices += it; it.configurePhoneDevice() }
                },
                // 0.4 Task 8: the same hold-backed bridge is reachable from the test, so the P4 line and the platform
                // call counts describe one lock.
                wakeLock = wakeLock)
            root.registerPlugin(wiring.plugin)
        }

        /** The production start path (`MainActivity` attaches, the start establishes the link, then readiness arrives). */
        fun activate(): Boolean {
            root.attachCxrActivity(Any())
            root.start("smart-assistant")
            root.onSceneReady()
            return root.state.value.errorCode == null && root.currentAudioEpoch() != null
        }

        /** Runs the queued failure tasks in the order the production scheduler would. */
        fun runDispatches() {
            while (dispatchQueue.isNotEmpty()) dispatchQueue.removeAt(0).invoke()
        }

        fun pendingDispatches(): Int = dispatchQueue.size

        fun close() = root.close()
    }

    private fun withRig(
        detached: Boolean,
        withConnection: Boolean = true,
        queueDispatches: Boolean = false,
        configurePhoneDevice: FakePhoneDevice.() -> Unit = {},
        configureWakeBridge: RecordingWakeLockBridge.() -> Unit = {},
        block: (Rig) -> Unit,
    ) {
        val rig = Rig(detached, withConnection, queueDispatches, configurePhoneDevice, configureWakeBridge)
        try { block(rig) } finally { rig.close() }
    }

    private fun awaitCondition(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue("timed out waiting for the capture failure to reach the dispatcher", condition())
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
            assertEquals("capture must begin before the loop polls it, and the CPU hold is taken only after both are up",
                listOf("start:capture", "start:loop", "acquire:wake"), rig.order.toList())
            assertEquals("the phone ingress is open with the session's epoch, so its offers are accepted",
                PcmOffer.ACCEPTED, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            assertEquals("exactly one recorder for one epoch", 1, rig.devices.size)
            assertTrue(rig.devices.single().starts.get() == 1)
            // 0.4 Task 8: one successful detached arm takes the CPU lock exactly once, after capture and loop are up.
            assertEquals("one detached arm takes exactly one CPU wake lock", 1, rig.wakeBridge.acquires.get())
            assertTrue("the lock-screen hold must be real while the epoch runs", rig.wakeLock.isHeld())
            // 0.4 Task 6 (spec §7.2): the new evidence line attributes the audio to the phone source. Task 8 adds the
            // lock-screen hold; this rig holds a fake platform lock, so the arm line must say the hold is real.
            assertEquals(
                listOf("P4_AUDIO state=armed source=phone_mic transport=false loop=true lockscreen_hold=true reason=ARMED"),
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
            assertEquals("the glasses path never takes the detached CPU wake lock", 0, rig.wakeBridge.acquires.get())
            assertFalse("no CPU hold exists in glasses mode", rig.wakeLock.isHeld())
            assertEquals("the phone ingress was never opened for this epoch",
                PcmOffer.STALE_EPOCH, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            // 0.4 evidence still attributes a glasses session to the CXR source (spec §7.2), with no CPU hold: the
            // glasses path never takes the detached wake lock (Task 8).
            assertEquals(
                listOf("P4_AUDIO state=armed source=cxr transport=true loop=true lockscreen_hold=false reason=ARMED"),
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

            assertEquals("the loop must stop before the device it was polling is released, and the CPU hold goes last",
                listOf("stop:loop", "stop:capture", "release:wake"), rig.order.toList())
            assertEquals("the device is released exactly once", 1, rig.devices.single().stops.get())
            assertNull(rig.root.currentAudioEpoch())
            assertEquals("stop releases the CPU hold exactly once", 1, rig.wakeBridge.releases.get())
            assertFalse("no CPU hold survives a stopped session", rig.wakeLock.isHeld())
            assertEquals("a released phone ingress accepts nothing",
                PcmOffer.STALE_EPOCH, rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            assertEquals(
                listOf("P4_AUDIO state=disarmed source=phone_mic transport=false loop=false lockscreen_hold=false reason=DISARMED"),
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
            assertEquals("pause must release the CPU hold", 1, rig.wakeBridge.releases.get())
            assertFalse("a paused session must not keep the CPU awake", rig.wakeLock.isHeld())

            rig.root.resume()

            assertNotNull("resume re-arms the epoch", rig.root.currentAudioEpoch())
            assertEquals("resume must not reuse a released recorder", 2, rig.devices.size)
            assertEquals(1, rig.devices.last().starts.get())
            assertEquals("the loop starts again for the new epoch", 2, rig.loop.recorder.started.size)
            assertEquals("resume takes a fresh CPU hold", 2, rig.wakeBridge.acquires.get())
            assertTrue("a resumed detached session is held awake again", rig.wakeLock.isHeld())
        }
    }

    /**
     * 0.4 Task 8 fix 1: Settings -> profile selection pauses the active plugin through
     * `HostCompositionRoot.applyProfileSelection`, which used to call `host.pause()` directly. That bypassed
     * `disarmAudioEpoch`, so a detached session's recorder, ingress, poll loop and CPU hold all outlived the pause
     * (spec §4.5 "会话暂停/停止: 停采集、清 epoch、释放 AudioRecord" and §4.6 "release on pause"). The selection is the
     * production seam `ProfileViewModel.select` calls, so driving it here is the user-reachable path without a UI.
     */
    @Test
    fun aProfileSelectionWhileDetachedReleasesThePhoneCaptureAndTheWakeLock() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val epoch = requireNotNull(rig.root.currentAudioEpoch())
            val device = rig.devices.single()
            assertTrue("an armed detached session holds the CPU awake", rig.wakeLock.isHeld())

            val lines = withP3Sink { captured ->
                rig.root.applyProfileSelection("smart-assistant", profile("p-2"))
                captured.toList()
            }

            assertNull("a profile change must not leave the detached epoch running",
                rig.root.currentAudioEpoch())
            assertEquals("the phone recorder is released exactly once", 1, device.stops.get())
            assertEquals("the poll loop for that epoch is stopped", listOf(epoch), rig.loop.recorder.stopped)
            assertEquals("the phone ingress is closed with the epoch", PcmOffer.STALE_EPOCH,
                rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            assertEquals("the CPU hold is released exactly once", 1, rig.wakeBridge.releases.get())
            assertFalse("a session paused for a profile change must not keep the CPU awake", rig.wakeLock.isHeld())
            assertEquals("the host really paused rather than just losing its epoch",
                SessionState.Paused, rig.root.state.value.session)
            assertNull("the rebuilt configuration was accepted", rig.root.state.value.errorCode)
            assertEquals(
                "the release is the ordinary DISARMED transition of the phone source",
                listOf(
                    "P4_AUDIO state=disarmed source=phone_mic transport=false loop=false lockscreen_hold=false reason=DISARMED",
                ),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
        }
    }

    /**
     * 0.4 Task 8 fix 1 (audit): the permission-refused START rollback calls `host.stop()` directly. No normal UI path
     * reaches it while an epoch is armed, but if one does, that stop must not leave the detached capture and CPU hold
     * running with no session (the same "release on stop" rule).
     */
    @Test
    fun aPermissionRefusedStartReleasesAnArmedDetachedEpoch() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val epoch = requireNotNull(rig.root.currentAudioEpoch())
            val device = rig.devices.single()
            val token = rig.root.authorizeStart("smart-assistant")

            val lines = withP3Sink { captured ->
                val admitted = rig.root.dispatch("edith.START", token, permissionGranted = false)
                assertFalse("a start without the microphone permission must be refused", admitted)
                captured.toList()
            }

            assertEquals("the page must name the missing permission",
                "MIC_PERMISSION_MISSING", rig.root.state.value.errorCode)
            assertEquals("the refused start leaves no session behind", SessionState.Idle, rig.root.state.value.session)
            assertNull("the rollback must release the armed epoch", rig.root.currentAudioEpoch())
            assertEquals("the phone recorder is released exactly once", 1, device.stops.get())
            assertEquals("the poll loop for that epoch is stopped", listOf(epoch), rig.loop.recorder.stopped)
            assertEquals("the CPU hold is released exactly once", 1, rig.wakeBridge.releases.get())
            assertFalse("no CPU hold may outlive the refused start", rig.wakeLock.isHeld())
            assertEquals(
                "the rollback is recorded as the ordinary DISARMED transition of the phone source",
                listOf(
                    "P4_AUDIO state=disarmed source=phone_mic transport=false loop=false lockscreen_hold=false reason=DISARMED",
                ),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
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

    // ---- §4.5: a microphone failure must never leave a session claiming normal radio -----------------------------

    @Test
    fun aFailedPhoneCaptureStartPausesTheSessionAndReportsStartFailed() {
        withRig(detached = true, configurePhoneDevice = { startResult = false }) { rig ->
            val lines = withP3Sink { captured ->
                assertFalse("a session whose detached microphone cannot start must not report itself started",
                    rig.activate())
                captured.toList()
            }

            assertEquals("the page must carry the recorder's stable code",
                PhoneMicCapture.MIC_UNAVAILABLE, rig.root.state.value.errorCode)
            assertEquals("the session may not stay Ready/Listening with no producer",
                SessionState.Paused, rig.root.state.value.session)
            assertNull("no epoch may survive a failed arm", rig.root.currentAudioEpoch())
            assertTrue("the phone source never touches the CXR transport",
                rig.transport.recorder.started.isEmpty())
            assertTrue("the poll loop must not run with nothing producing into it",
                rig.loop.recorder.started.isEmpty())
            assertEquals("the arm did try the phone recorder exactly once", 1, rig.devices.single().starts.get())
            assertEquals("a failed arm never takes the CPU wake lock", 0, rig.wakeBridge.acquires.get())
            assertEquals("a failed arm never has a hold to release", 0, rig.wakeBridge.releases.get())
            assertFalse(rig.wakeLock.isHeld())
            assertEquals(
                "the failure is an explicit START_FAILED transition for the phone source, and no CPU hold survived it",
                listOf("P4_AUDIO state=disarmed source=phone_mic transport=false loop=false lockscreen_hold=false reason=START_FAILED"),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
        }
    }

    @Test
    fun aMidCaptureFailureDisarmsPausesAndReportsCaptureFailed() {
        // The first read after a successful arm dies, which is the device-preempted shape: the session was already
        // advertised as listening when the producer went away.
        withRig(detached = true, queueDispatches = true, configurePhoneDevice = { failReadAt = 1 }) { rig ->
            withP3Sink { captured ->
                assertTrue(rig.activate())
                val epoch = requireNotNull(rig.root.currentAudioEpoch())
                val device = rig.devices.single()
                assertTrue("an armed detached session holds the CPU awake", rig.wakeLock.isHeld())

                awaitCondition { device.failureTriggered.get() == 1 }
                awaitCondition { rig.pendingDispatches() == 1 }

                // The failure is unwound on the host dispatcher, never on the capture thread (no self-join).
                rig.runDispatches()

                assertEquals("the reader's stable code reaches the page",
                    PhoneMicCapture.MIC_UNAVAILABLE, rig.root.state.value.errorCode)
                assertEquals("the degraded state is explicit, not a session that still looks listening",
                    SessionState.Paused, rig.root.state.value.session)
                assertNull("the dead epoch is released", rig.root.currentAudioEpoch())
                assertEquals("the released phone ingress accepts no later offer", PcmOffer.STALE_EPOCH,
                    rig.phoneIngress.offer(epoch, ByteArray(PhoneMicFormat.CHUNK_BYTES)))
                assertEquals("the poll loop for that epoch is stopped", listOf(epoch), rig.loop.recorder.stopped)
                assertEquals("the recorder is released exactly once", 1, device.stops.get())
                assertEquals("the CPU hold is released exactly once", 1, rig.wakeBridge.releases.get())
                assertFalse("a degraded session must not keep the CPU awake", rig.wakeLock.isHeld())
                assertTrue("a detached failure does not start the CXR transport",
                    rig.transport.recorder.started.isEmpty())
                assertEquals(
                    "the arm succeeded first, then the dead reader degrades the epoch with CAPTURE_FAILED and drops the CPU hold",
                    listOf(
                        "P4_AUDIO state=armed source=phone_mic transport=false loop=true lockscreen_hold=true reason=ARMED",
                        "P4_AUDIO state=disarmed source=phone_mic transport=false loop=false lockscreen_hold=false reason=CAPTURE_FAILED",
                    ),
                    captured.filter { it.startsWith("P4_AUDIO") },
                )
                assertEquals("the session is not silently re-armed by the failure", 0, rig.pendingDispatches())
                captured.toList()
            }
        }
    }

    // ---- Task 8: the detached CPU wake lock ----------------------------------------------------------------------

    @Test
    fun aSecondArmDoesNotAcquireASecondWakeLock() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertTrue("a second arm of a running epoch is a no-op", rig.root.armAudioEpoch())
            assertEquals("one logical hold means one platform acquire", 1, rig.wakeBridge.acquires.get())
            assertTrue(rig.wakeLock.isHeld())
        }
    }

    @Test
    fun closeReleasesTheDetachedWakeLock() {
        val rig = Rig(detached = true)
        try {
            assertTrue(rig.activate())
            assertTrue(rig.wakeLock.isHeld())

            rig.root.close()

            assertEquals("close releases the CPU hold exactly once", 1, rig.wakeBridge.releases.get())
            assertFalse("a closed composition must not keep the CPU awake", rig.wakeLock.isHeld())
        } finally {
            rig.close()
        }
    }

    @Test
    fun anExplicitDisarmReleasesTheWakeLockAndTheLineSaysSo() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertTrue(rig.wakeLock.isHeld())

            val lines = withP3Sink { captured ->
                rig.root.disarmAudioEpoch()
                captured.toList()
            }

            assertFalse(rig.wakeLock.isHeld())
            assertEquals(1, rig.wakeBridge.releases.get())
            assertEquals(
                listOf("P4_AUDIO state=disarmed source=phone_mic transport=false loop=false lockscreen_hold=false reason=DISARMED"),
                lines.filter { it.startsWith("P4_AUDIO") },
            )
        }
    }

    @Test
    fun aRefusedWakeLockAcquireStillArmsTheSessionAndReportsNoHold() {
        withRig(detached = true, configureWakeBridge = { rejectAcquire = true }) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue("a CPU lock the platform refused must not fail the audio session", rig.activate())
                captured.toList()
            }

            assertEquals("the arm still tries the lock exactly once", 1, rig.wakeBridge.acquires.get())
            assertFalse("a refused acquire must not pretend to hold", rig.wakeLock.isHeld())
            assertEquals("ACQUIRE_REFUSED", rig.wakeLock.lastFailure())
            assertEquals(
                "the evidence line must report the missing hold, not the attempted one",
                listOf("P4_AUDIO state=armed source=phone_mic transport=false loop=true lockscreen_hold=false reason=ARMED"),
                lines.filter { it.startsWith("P4_AUDIO") },
            )

            rig.root.stop()
            assertEquals("there was no hold to release", 0, rig.wakeBridge.releases.get())
        }
    }
}
