package com.sheen.rokid.phone.app

import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.audio.PcmRecordDevice
import com.sheen.rokid.phone.bridge.CxrConnection
import com.sheen.rokid.phone.bridge.CxrHintLink
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartWiring
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.withP3Sink
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.AsrSession
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
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
import com.sheen.rokid.plugin.StorageAccess
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.storage.ProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.4 Task 7 (`D199`/`D201`, spec §4.3): a detached session activates, listens and answers **without CXR**.
 *
 * The production `create` cannot run on the JVM (it needs an Android `Context`), so this rig is the same shape the
 * composition-level tests already use: the real `HostCompositionRoot`/`HostRuntime`/`SmartWiring`/`CxrHintLink`/
 * `CxrHostEnvironment`, with recording transports and a fake phone microphone. What is under test is the composition
 * code, not a copy of it:
 *
 * - the start path must not touch the vendor transport at all in detached mode - no `ensureCxrLink`, no
 *   `attachCxrActivity` connect, no `P3_CXR` line - and must still start the session and activate the plugin;
 * - the scene is locally ready for a detached session, so a late `onSceneReady` is a no-op instead of introducing a
 *   session to glasses that are not there;
 * - `TextOutput` is the phone display device, and the conversation cycle sees its panel as visible without a
 *   `PANEL_SHOWN` that will never come;
 * - glasses mode keeps its own rules: no activation until the link reports ready and a clock mapping makes the display
 *   real, and the live output is still the link's device.
 */
class DetachedStartTest {

    private fun profile(id: String) = ModelProfile(id, 3, "Synthetic $id", "Synthetic",
        "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
        object : SecretHandle { override val id = "draft-placeholder" },
        4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT, InputModality.IMAGE),
        setOf(OutputModality.TEXT), emptyList())

    private val profile = profile("p-1")

    // ---- fakes (the same shapes the composition-shaped rigs use) -------------------------------------------------

    private class NoActions : ActionExecutor {
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Refused, "UNKNOWN_ACTION")
        override fun cancel() = Unit
    }

    private class NoConfirmations : ConfirmationChannel {
        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
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

    private class SyntheticDeviceState : DeviceStateProvider {
        override fun profile() = DeviceProfile("", "", null, "", 0, 0, 0, 0, null, null, null, null, "", "", "",
            emptyList(), emptySet())
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

    private class StubStorage : StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

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

    /** Records the official levels the plugin's conversation cycle writes, and nothing else. */
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

    /** 0.4 Task 10: accepts a typed turn's loop without running it - this rig asserts delivery, not the agent loop. */
    private object InertTasks : TaskScope {
        override fun launch(block: () -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun cancelAll() = Unit
    }

    /**
     * The phone microphone as the capture sees it: produces no audio, blocks in [read] like a real `AudioRecord` does,
     * and records that it was released. The detached arm starts one of these per epoch.
     */
    private class NullPhoneDevice : PcmRecordDevice {
        @Volatile private var recording = false
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        override fun start(): Boolean { starts.incrementAndGet(); recording = true; return true }
        override fun read(buffer: ShortArray): Int {
            while (recording) Thread.sleep(5)
            return 0
        }
        override fun wake() { recording = false }
        override fun stop() { stops.incrementAndGet(); recording = false }
    }

    /** The CXR transport double; only a ready connection records outbound commands, like the real transport. */
    private class HintConnection(private val port: InputPort) : CxrConnection {
        @Volatile override var ready = false
        val sent = CopyOnWriteArrayList<String>()

        /**
         * Every app-level command the link **offered** to the transport, even the ones a closed/not-ready connection
         * refused. A detached link must not even attempt one, and a closed transport would otherwise hide the attempt.
         */
        val attempts = CopyOnWriteArrayList<String>()
        val closes = AtomicInteger()
        @Volatile var closed = false
        private var inbound: ((String) -> Unit)? = null
        override fun connect(activity: Any) { closed = false }
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String): Boolean {
            attempts += json
            if (closed || !ready) return false
            sent += json
            return true
        }
        override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
        override fun close() {
            closed = true
            closes.incrementAndGet()
        }
        override fun inputPortOrNull(): InputPort = port
        fun answer(json: String) { requireNotNull(inbound).invoke(json) }
    }

    /**
     * The composition `create` builds, with the device-only pieces (Context, real engines) replaced by recording
     * doubles. The two 0.4 selectors come from the production helpers, so the detached choices under test are the
     * shipped decisions rather than a rig-local `if`.
     */
    private inner class Rig(detached: Boolean) : AutoCloseable {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val loopPort = RecordingInputPort()
        val transportPort = RecordingInputPort()
        val connection = HintConnection(transportPort)
        val factoryCalls = AtomicInteger()
        val appStarts = AtomicInteger()
        val devices = CopyOnWriteArrayList<NullPhoneDevice>()
        val display = RecordingConversationDisplay()
        val phoneIngress = PcmIngress(clock)
        val rootHolder = arrayOfNulls<HostCompositionRoot>(1)

        /** Everything the link reports, so "no handshake restarted after detach" is observable, not just implied. */
        val diagnostics = CopyOnWriteArrayList<String>()

        /**
         * The production gate shape: its stop-before-save rule drives this composition's own `stop()`, and its mode
         * side effect is the seam that neutralizes (or re-arms) the CXR link. Wiring it here - rather than a bare
         * `DetachGate(store)` - is what makes the composition-level detach regression drive the shipped path.
         */
        val gate: DetachGate = SmartSettingsStore(TestBlobs()).let { store ->
            store.save(SmartSettings(glassesDetached = detached))
            DetachGate(
                settings = store,
                stopSession = { rootHolder[0]?.stop() },
                sessionState = { rootHolder[0]?.host?.currentState ?: SessionState.Idle },
                onModeChanged = { enabled -> rootHolder[0]?.onDetachModeChanged(enabled) },
            ).also { it.load() }
        }

        val link: CxrHintLink
        val panelVisible: () -> Boolean
        val wiring: SmartWiring
        val environment: CxrHostEnvironment
        val host: HostRuntime
        val root: HostCompositionRoot

        init {
            val holder = arrayOfNulls<CxrConnection>(1)
            link = CxrHintLink(
                clock, scheduler,
                sendJson = { json -> holder[0]?.sendCommand(json) ?: false },
                diagnostics = { message -> diagnostics += message },
                startGlassesApp = { onResult -> appStarts.incrementAndGet(); onResult(true); true },
            )
            panelVisible = SmartWiring.cyclePanelVisible(
                detached = { gate.enabled.value },
                glassesVisible = { rootHolder[0]?.isPanelVisible() == true },
            )
            wiring = SmartWiring(
                SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
                    profileId = profile.id, cooldownMillis = 3000L),
                FixedProfiles(profile), clock, scheduler,
                isPanelVisible = panelVisible,
                requestPanelOpen = { route -> rootHolder[0]?.requestPanelOpen(route) ?: false },
            )
            environment = CxrHostEnvironment(
                clock = clock,
                input = loopPort,
                liveOutput = HostCompositionRoot.hostLiveOutput(
                    detached = { gate.enabled.value },
                    phone = PhoneHintDevice(ConversationLog()),
                    glasses = link::liveOutput,
                ),
                scheduler = scheduler,
                taskScope = InertTasks,
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
                storageFor = { StubStorage() },
            )
            host = HostRuntime(1, environment)
            root = HostCompositionRoot(
                host, MemoryProfileRepository(), InertModel(), environment,
                hintLink = link,
                smartWiring = wiring,
                rokidConnectionFactory = { factoryCalls.incrementAndGet(); connection.also { holder[0] = it } },
                detachGate = gate,
                phoneIngress = phoneIngress,
                phoneMicDeviceFactory = { NullPhoneDevice().also { devices += it } },
            )
            rootHolder[0] = root
            root.registerPlugin(wiring.plugin)
        }

        /** The production start path: the Activity is attached first, exactly as `MainActivity.onCreate` does. */
        fun attach() = root.attachCxrActivity(Any())

        fun start() = root.start("smart-assistant")

        /** The transport's one-shot readiness signal, as the SDK delivers it. */
        fun sceneReady() = root.onSceneReady()

        /** Answers the outstanding `PING`, which is what makes a clock mapping (and thus the display) live. */
        fun establishMapping() {
            val ping = connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
            clock.nowNanos = ping.p0 + 10_000_000L
            connection.answer(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000L, ping.p0 + 1_005_000_000L)))
        }

        override fun close() = root.close()
    }

    // ---- detached: activation without CXR -------------------------------------------------------------------------

    @Test
    fun detachedSessionActivatesPluginWithoutLinkReady() {
        Rig(detached = true).use { rig ->
            val lines = withP3Sink { captured ->
                rig.attach()
                rig.start()
                captured.toList()
            }

            assertEquals("a detached start must not build a CXR connection", 0, rig.factoryCalls.get())
            assertNull("a successful detached start leaves no error", rig.root.state.value.errorCode)
            assertEquals("the detached scene is ready locally", SessionState.Ready, rig.root.host.currentState)
            assertNotNull("the host must own the detached session", rig.root.host.currentIdentity())
            assertNotNull("the phone source must be armed", rig.root.currentAudioEpoch())
            assertFalse("the glasses link must not be introduced to a detached session", rig.link.isEstablished())
            assertTrue("nothing may be sent towards the glasses", rig.connection.sent.isEmpty())
            assertTrue("no CXR line may be produced: $lines", lines.none { it.startsWith("P3_CXR") })

            // The display is the phone device: TextOutput is advertised without a clock mapping, and a submit is
            // acknowledged rather than thrown away.
            assertTrue("the phone display must advertise TextOutput",
                Capability.TextOutput in rig.environment.availableCapabilities)
            val statuses = mutableListOf<OutputStatus>()
            rig.environment.output.show(
                Assistance("h-1", SessionId("s-1"), Generation(1), null, 0, "答案", null, 0L),
                revision = 1,
            ) { statuses += it }
            assertEquals("the phone display acknowledges the emit", listOf(OutputStatus.Displayed), statuses)
            assertTrue("and sends no CXR command", rig.connection.sent.isEmpty())
        }
    }

    @Test
    fun detachedModeMakesEveryCxrEntryPointANoOp() {
        Rig(detached = true).use { rig ->
            val lines = withP3Sink { captured ->
                rig.attach()
                rig.start()
                assertFalse("ensureCxrLink must not connect while detached", rig.root.ensureCxrLink())
                assertFalse("connectCxr must not build a connection while detached", rig.root.connectCxr(Any()))
                captured.toList()
            }

            assertEquals("no vendor connection may be built", 0, rig.factoryCalls.get())
            assertTrue("no command may leave the phone", rig.connection.sent.isEmpty())
            assertTrue("no CXR connect line may be produced: $lines", lines.none { it.startsWith("P3_CXR") })

            // A link left established by a prior glasses session (the mode change stops the session, but a stale link
            // is exactly what a defensive read must survive) must not be asked for a panel either.
            rig.link.sessionEstablished("stale-session", 1L)
            val appStartsBefore = rig.appStarts.get()
            assertFalse("a detached composition refuses the panel request",
                rig.root.requestPanelOpen(AssistantRoute.WAKE))
            assertEquals("and must not start the glasses scene", appStartsBefore, rig.appStarts.get())
        }
    }

    @Test
    fun aStaleSceneReadyInDetachedModeDoesNotTouchTheLocalSession() {
        Rig(detached = true).use { rig ->
            rig.attach()
            rig.start()
            val identity = rig.root.host.currentIdentity()
            assertNotNull("a detached start must own a session", identity)
            val state = rig.root.host.currentState
            val epoch = requireNotNull(rig.root.currentAudioEpoch())
            assertFalse("a detached session is not waiting for a CXR link", rig.root.state.value.cxrLinkReady)

            val lines = withP3Sink { captured ->
                rig.sceneReady()
                captured.toList()
            }

            assertEquals("a late CXR readiness must not change the local scene", state, rig.root.host.currentState)
            assertEquals("nor its identity", identity, rig.root.host.currentIdentity())
            assertSame("nor re-arm the ears", epoch, rig.root.currentAudioEpoch())
            assertFalse("the detached link must never be introduced", rig.link.isEstablished())
            assertFalse("a detached session never becomes 'link ready'", rig.root.state.value.cxrLinkReady)
            assertEquals("and nothing may be built", 0, rig.factoryCalls.get())
            assertTrue(rig.connection.sent.isEmpty())
            assertTrue("no CXR line may be produced: $lines", lines.none { it.startsWith("P3_CXR") })
        }
    }

    @Test
    fun aDetachedWakeStartsItsWindowOnThePhonePanelAndNeverAsksForTheGlassesPanel() {
        Rig(detached = true).use { rig ->
            rig.attach()
            rig.start()
            assertEquals(SessionState.Ready, rig.root.host.currentState)
            assertFalse("nobody showed a glasses panel", rig.root.isPanelVisible())
            assertTrue("the phone's conversation is the visible surface", rig.panelVisible())

            var chimes = 0
            val acks = mutableListOf<String>()
            val lines = withP3Sink { captured ->
                rig.root.emitWake(rig.host, "伊迪丝", 1.0, ack = { text -> acks += text; true }) { chimes++ }
                // Reading a host command is a barrier that proves the dispatcher drain ran.
                rig.host.currentState
                captured.toList()
            }

            assertEquals("the wake must still be delivered on the phone", 1,
                lines.count { it.startsWith("P3_WAKE") })
            assertEquals("detached mode speaks its fixed acknowledgement instead of the beep",
                listOf("我在"), acks)
            assertEquals("the detached acknowledgement replaces the chime", 0, chimes)
            assertEquals("the visible phone panel starts the listening round at once",
                listOf(AssistantRoute.WAKE to OfficialStatus.LISTENING.wire), rig.display.states)
            assertTrue("the round stays open", rig.display.closes.isEmpty())
            assertTrue("no panel/show command may leave the phone", rig.connection.sent.isEmpty())
            assertEquals("and no glasses scene may be started", 0, rig.appStarts.get())
        }
    }

    @Test
    fun aGlassesWakeKeepsItsChimeAndNeverAsksForTheDetachedAcknowledgement() {
        Rig(detached = false).use { rig ->
            rig.attach()
            rig.connection.ready = true
            rig.start()
            rig.sceneReady()
            rig.establishMapping()
            assertEquals(SessionState.Ready, rig.root.host.currentState)

            var chimes = 0
            val acks = mutableListOf<String>()
            rig.root.emitWake(rig.host, "伊迪丝", 1.0, ack = { text -> acks += text; true }) { chimes++ }

            assertEquals("glasses mode must not speak the detached acknowledgement", emptyList<String>(), acks)
            assertEquals("glasses mode keeps its existing beep", 1, chimes)
        }
    }

    /**
     * 0.4 Task 10 (`D203`, spec §5.2): the phone's typed input is available in **both** display modes.
     *
     * Detached mode is the harder half: there is no glasses panel and no CXR link, and the typed path must not consult
     * either. The accepted request is delivered at the detached session's identity, the count-only line names its turn,
     * the text never reaches the log, and the plugin takes it into a WAKE turn (the slot is busy afterwards). The
     * glasses half is pinned by `PassiveLoopWiringTest.aTypedMessageIsDeliveredAsATypedUserActionAtTheCurrentIdentity`.
     */
    @Test
    fun aDetachedSessionAcceptsATypedMessage() {
        Rig(detached = true).use { rig ->
            rig.attach()
            rig.start()
            assertEquals(SessionState.Ready, rig.root.host.currentState)

            val lines = withP3Sink { captured ->
                assertEquals(
                    "detached mode must not gate the typed path",
                    TypedSendResult.Accepted,
                    rig.root.requestTypedMessage("帮我记一条笔记"),
                )
                rig.root.host.currentState
                captured.toList()
            }

            val typed = lines.filter { it.startsWith("P4_TYPED") }.single()
            assertTrue("the accepted line is count-only and names its turn: $typed",
                typed.startsWith("P4_TYPED state=accepted turn=typed-"))
            assertTrue("the count is the body's: $typed", typed.endsWith(" chars=7"))
            assertTrue("the typed text must never reach the log: $lines",
                lines.none { it.contains("帮我记一条笔记") })
            assertFalse("the detached plugin must take the message into a WAKE turn",
                rig.wiring.plugin.canAcceptTypedMessage())
        }
    }

    // ---- glasses: the existing rules still hold -------------------------------------------------------------------

    @Test
    fun glassesModeStillRequiresTheLinkAndDisplaysThroughTheHintLink() {
        Rig(detached = false).use { rig ->
            rig.attach()
            rig.connection.ready = true
            rig.start()

            assertEquals("without the link readiness the scene is not ready",
                SessionState.BuildingScene, rig.root.host.currentState)
            assertNull("and nothing may be active yet", rig.root.host.currentIdentity())
            assertFalse("the phone display must not advertise TextOutput in glasses mode",
                Capability.TextOutput in rig.environment.availableCapabilities)

            rig.sceneReady()
            assertTrue("scene readiness introduces the session to the glasses", rig.link.isEstablished())
            assertTrue("the opening handshake leaves the phone",
                rig.connection.sent.any { HintWireCodec.decode(it) is HintCommand.Ping })

            // The display only becomes real with a live mapping; the refused activation is retried on it.
            rig.establishMapping()
            assertEquals(SessionState.Ready, rig.root.host.currentState)
            assertNotNull(rig.root.host.currentIdentity())
            val epoch = requireNotNull(rig.root.currentAudioEpoch())
            assertEquals("the glasses epoch starts the transport port", listOf(epoch), rig.transportPort.started)
            assertTrue("the phone microphone is never opened in glasses mode", rig.devices.isEmpty())
            assertTrue(Capability.TextOutput in rig.environment.availableCapabilities)

            val statuses = mutableListOf<OutputStatus>()
            val identity = requireNotNull(rig.root.host.currentIdentity())
            rig.environment.output.show(
                Assistance("h-1", identity.first, identity.second, null, 0, "答案", null,
                    rig.clock.nowNanos() + 5_000_000_000L),
                revision = 1,
            ) { statuses += it }

            val shows = rig.connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Show }
            assertEquals("the live output is still the glasses link's device", 1, shows.size)
            assertFalse("nothing may be claimed displayed before the glasses acknowledge it",
                statuses.contains(OutputStatus.Displayed))
        }
    }

    // ---- strict-local detach: a same-process live glasses link must go quiet ------------------------------

    /**
     * 0.4 strict-local fix (`D199`/`D201`): detaching after a **live** glasses session in the same process.
     *
     * The Task 13b device log showed `EdithHint: outbound: Ping` and `Sync` 8 s after `P4_DETACH`, because the gate
     * only stopped the host session: `sessionEnded()` cleared the link's session identity but left the clock mapping,
     * its renewal and the connection alive. This drives the real `DetachGate` -> `HostCompositionRoot.stop()` ->
     * `CxrHintLink` path over the composition's own seams and asserts that the transition leaves the link inert:
     * timers cancelled, mapping/session/panel cleared, the vendor connection dropped, and no app-level command (not
     * even a refused/attempted one) after the mode is persisted.
     */
    @Test
    fun detachingFromALiveGlassesSessionNeutralizesTheLinkAndSendsNothingAfterwards() {
        Rig(detached = false).use { rig ->
            rig.attach()
            rig.connection.ready = true
            rig.start()
            rig.sceneReady()
            rig.establishMapping()
            assertTrue("precondition: the glasses session is live", rig.link.isEstablished())
            assertNotNull("precondition: the clock mapping makes the display real", rig.link.liveOutput())

            val result = rig.gate.set(true)

            assertTrue("the detach mode must persist", result.saved)
            assertTrue("the running session is stopped before the mode is saved", result.stoppedSession)
            assertTrue("the vendor connection must be dropped", rig.connection.closed)
            assertEquals("exactly once", 1, rig.connection.closes.get())
            assertNull("no clock mapping may survive detach", rig.link.liveSyncId())
            assertNull("no display may be advertised while detached", rig.link.liveOutput())
            assertFalse("the glasses session is no longer established", rig.link.isEstablished())
            assertFalse("nor is the resident panel visible", rig.link.isPanelVisible())
            assertFalse("the host must stop claiming a CXR link", rig.root.state.value.cxrLinkReady)

            val sentAtDetach = rig.connection.sent.size
            val attemptsAtDetach = rig.connection.attempts.size
            rig.diagnostics.clear()

            // Every renewal/PING timeout/grace deadline armed before the change is now in the past.
            rig.clock.nowNanos += 120_000_000_000L
            rig.scheduler.runDue()

            assertTrue("no handshake may restart after detach: ${rig.diagnostics.toList()}",
                rig.diagnostics.none { it.startsWith("handshake: ping") || it.startsWith("outbound:") })
            assertEquals("no CXR command may leave after detach: " +
                rig.connection.sent.drop(sentAtDetach).mapNotNull { HintWireCodec.decode(it) },
                sentAtDetach, rig.connection.sent.size)
            assertEquals("not even attempt one", attemptsAtDetach, rig.connection.attempts.size)

            // Stale-link entry points (the confirmation SHOW path and the wear-off CLOSE path) are inert.
            assertFalse("a detached confirmation state is refused", rig.link.confirmationState("c-1", true))
            assertFalse("a detached close-all is refused", rig.link.closeAll())
            assertFalse("a detached utterance is refused",
                rig.link.userSaid(AssistantRoute.WAKE, "asr-1", "text"))
            assertEquals("and none of them reaches the transport", attemptsAtDetach, rig.connection.attempts.size)
        }
    }

    /**
     * 0.4 strict-local fix (`D199`): a stale vendor wear-off must not touch a detached session.
     *
     * The old `onWearingChanged` cancelled the current turn and sent `closeAll()` on the old link. After a detach in
     * the same process, that callback can still be delivered from the dying connection; it must be recognized as a
     * stale glasses fact and neither command the (dead) link nor cancel the strictly-local session.
     */
    @Test
    fun aStaleWearOffCannotCancelOrCommandADetachedSession() {
        Rig(detached = false).use { rig ->
            rig.attach()
            rig.connection.ready = true
            rig.start()
            rig.sceneReady()
            rig.establishMapping()
            assertTrue(rig.gate.set(true).saved)

            // A strictly-local session is running when the stale callback arrives.
            rig.start()
            assertEquals("the detached session must be ready", SessionState.Ready, rig.root.host.currentState)
            val identity = rig.root.host.currentIdentity()
            assertNotNull("with its own local identity", identity)
            val sentBefore = rig.connection.sent.size
            val attemptsBefore = rig.connection.attempts.size
            rig.diagnostics.clear()

            val lines = withP3Sink { captured ->
                rig.root.onWearingChanged(false)
                captured.toList()
            }

            assertEquals("the detached session keeps its identity", identity, rig.root.host.currentIdentity())
            assertEquals("and keeps running", SessionState.Ready, rig.root.host.currentState)
            assertEquals("no CLOSE/STOP command may be attempted", attemptsBefore, rig.connection.attempts.size)
            assertEquals("and nothing may be sent", sentBefore, rig.connection.sent.size)
            assertNull("the stale glasses wear fact is not this session's state", rig.root.glassesWearing())
            assertTrue("the callback is recorded as ignored: $lines",
                lines.any { it.contains("ignored=detached") })
            assertTrue("the old wear path must not run: $lines", lines.none { it.contains("wear=off") })
            assertTrue("and the link must stay neutralized: ${rig.diagnostics.toList()}",
                rig.diagnostics.none { it.startsWith("outbound:") || it.startsWith("handshake: ping") })
        }
    }

    /**
     * 0.4 strict-local fix (`D199`, spec §4.1): switching detach off again must re-arm a clean glasses link.
     *
     * The detach transition closes the vendor connection (its transport refuses to reconnect once closed), so the
     * composition must forget that connection: the next `开始` builds a fresh one and the link is allowed to introduce
     * the session again. A link left neutralized would silently refuse every glasses session after reattaching.
     */
    @Test
    fun switchingDetachOffRebuildsTheCxrLinkForTheNextGlassesSession() {
        Rig(detached = false).use { rig ->
            rig.attach()
            rig.connection.ready = true
            rig.start()
            rig.sceneReady()
            rig.establishMapping()
            assertTrue(rig.gate.set(true).saved)
            assertTrue("the detach closed the vendor connection", rig.connection.closed)

            assertTrue("switching detach off must persist", rig.gate.set(false).saved)

            rig.start()
            rig.sceneReady()

            assertEquals("a fresh connection must be built, not the closed one reused", 2, rig.factoryCalls.get())
            assertTrue("the re-armed link introduces the new session",
                rig.link.isEstablished())
            assertTrue("with a fresh HELLO: " +
                rig.connection.sent.mapNotNull { HintWireCodec.decode(it) },
                rig.connection.sent.any { HintWireCodec.decode(it) is HintCommand.Hello })
            assertTrue("and the host reports a live link again", rig.root.state.value.cxrLinkReady)
        }
    }
}
