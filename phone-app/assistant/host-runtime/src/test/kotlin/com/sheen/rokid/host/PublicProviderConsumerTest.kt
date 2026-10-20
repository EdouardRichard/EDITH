package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.output.*
import com.sheen.rokid.phone.plugin.diagnostic.DiagnosticTextPlugin
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import com.sheen.rokid.phone.plugin.interview.InterviewAssistantPlugin
import com.sheen.rokid.testsupport.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PublicProviderConsumerTest {
    @Test fun `host turn cancellation clears admitted hint and rejects stale identity`() {
        val rig = HostTestRig(); val host = rig.host
        assertEquals(ActivationResult.Activated, host.activate("probe"))
        val context = rig.plugin.context!!
        context.services.textOutput().submit(Assistance("h", context.sessionId, context.generation,
            "t", 0, "建议。", null, 5_000_000_000))
        val before = rig.output.clears.size
        host.cancelOutputTurn(SessionId("wrong"), context.generation, "t")
        assertEquals(before, rig.output.clears.size)
        host.cancelOutputTurn(context.sessionId, Generation(0), "t")
        assertEquals(before, rig.output.clears.size)
        host.cancelOutputTurn(context.sessionId, context.generation, "t")
        assertEquals(before + 1, rig.output.clears.size)
        host.stop()
    }

    @Test fun `host hint cancellation preserves a newer same turn hint`() {
        val rig = HostTestRig(); val host = rig.host
        assertEquals(ActivationResult.Activated, host.activate("probe"))
        val context = rig.plugin.context!!
        val expiry = 5_000_000_000L
        context.services.textOutput().submit(Assistance("a", context.sessionId, context.generation,
            "t", 0, "旧建议。", null, expiry))
        context.services.textOutput().submit(Assistance("b", context.sessionId, context.generation,
            "t", 0, "新建议。", null, expiry))
        host.cancelOutputHint(SessionId("wrong"), context.generation, "t", "a")
        host.cancelOutputHint(context.sessionId, Generation(0), "t", "a")
        assertEquals(0, rig.output.clears.size)
        host.cancelOutputHint(context.sessionId, context.generation, "t", "a")
        assertEquals(listOf("a", "b"), rig.output.shown.map { it.first.hintId })
        assertEquals(1, rig.output.clears.size)
        rig.output.callbacks[1](OutputStatus.Displayed)
        assertEquals(1, rig.output.clears.size)
        host.stop()
    }

    @Test fun `actual smart and interview shells retain provider compatibility across lifecycle`() {
        for (smartFirst in listOf(true, false)) {
            val rig = Providers(); val host = rig.host
            val smart = SmartAssistantPlugin(); val interview = InterviewAssistantPlugin()
            host.register(smart); host.register(interview)
            // Task 13 made `plugins-smart` a real configured plugin, so the host's `ConfigurationPolicy.validate` now
            // runs before activation: an unconfigured smart plugin is *refused*, not silently activated with an empty
            // profile. The lifecycle assertions below are unchanged; only the configuration step is new.
            assertTrue(
                host.configure(
                    "smart-assistant",
                    PluginConfiguration(1, mapOf("profileId" to "dummy", "profileRevision" to "3")),
                ),
            )
            val first = if (smartFirst) smart else interview
            val second = if (smartFirst) interview else smart
            host.startSession(SessionId("s")); host.markSceneReady()
            assertEquals(ActivationResult.Activated, host.activate(first.descriptor.pluginId))
            assertEquals(first.descriptor.capabilities, rig.grants.last())
            val old = rig.services.last(); val generation = host.currentGeneration
            host.onEvent(UserAction(rig.envelope(generation), UserAction.Action.Help)); host.currentState
            assertTrue(rig.requests.isEmpty(), "no wake and no final transcript: Help has nothing to run")
            var delivered = 0
            old.model().request(rig.request(generation)) { delivered++ }
            assertEquals(ActivationResult.Activated, host.switchTo(second.descriptor.pluginId))
            assertEquals(!smartFirst, smart.active); assertEquals(smartFirst, interview.active)
            assertEquals(second.descriptor.capabilities, rig.grants.last())
            assertEquals(1, rig.handles[0].cancelCount)
            rig.callbacks[0](ModelStreamEvent.TextDelta("r", "late")); assertEquals(0, delivered)
            assertFailsWith<ContractError.Cancelled> { old.model() }
            val current = rig.services.last(); val currentGeneration = host.currentGeneration
            current.model().request(rig.request(currentGeneration)) { delivered++ }
            if (second is InterviewAssistantPlugin) {
                current.storage().write("dummy", byteArrayOf(1)); assertContentEquals(byteArrayOf(1), current.storage().read("dummy"))
                current.speaker().enroll(emptyList()) { delivered++ }
            }
            host.pause(); assertFalse(smart.active); assertFalse(interview.active)
            assertTrue(rig.handles.all { it.cancelCount == 1 })
            rig.callbacks.forEach { it(ModelStreamEvent.TextDelta("r", "late")) }; assertEquals(0, delivered)
            assertFailsWith<ContractError.Cancelled> { current.model() }
            assertEquals(ActivationResult.Activated, host.resume())
            assertTrue(host.currentGeneration.value > currentGeneration.value)
            val resumed = rig.services.last(); resumed.model().request(rig.request(host.currentGeneration)) { delivered++ }
            host.stop(); assertFalse(smart.active); assertFalse(interview.active)
            assertEquals(1, rig.handles.last().cancelCount)
            assertFailsWith<ContractError.Cancelled> { resumed.textOutput() }
        }
    }

    @Test fun `diagnostic uses real host arbitration ACK and turn clearing with no late pause output`() {
        val rig = Providers(); val host = rig.host; val trace = TraceRecorder()
        val displayed = CountDownLatch(1)
        var eventHandled = CountDownLatch(1)
        val plugin = DiagnosticTextPlugin(rig.clock, rig.scheduler, trace, { rig.profile }, { true },
            host::cancelOutputTurn, 30_000, host::cancelOutputHint)
        val observing = object : PluginLifecycle, ConfiguredPlugin {
            override val descriptor = plugin.descriptor
            override fun validate(configuration: PluginConfiguration) = plugin.validate(configuration)
            override fun configure(configuration: PluginConfiguration) = plugin.configure(configuration)
            override fun activate(context: PluginContext) = plugin.activate(context)
            override fun deactivate(reason: DeactivationReason) = plugin.deactivate(reason)
            override fun handle(event: HostEvent) {
                plugin.handle(event)
                if (event is OutputStateEvent && event.status == OutputStatus.Displayed) displayed.countDown()
                if (event is TranscriptEvent || event is UserAction) eventHandled.countDown()
            }
        }
        host.register(observing)
        assertTrue(host.configure("p2-diagnostic", PluginConfiguration(1, mapOf("profileId" to "dummy", "profileRevision" to "3", "requestConsent" to "true"))))
        host.startSession(SessionId("s")); host.markSceneReady()
        assertEquals(ActivationResult.Activated, host.activate("p2-diagnostic"))
        val g = host.currentGeneration
        fun turn(id: String, start: Long) {
            eventHandled = CountDownLatch(1)
            host.onEvent(TranscriptEvent(TranscriptRevision(rig.envelope(g), id, 1, "dummy question", true, start, rig.clock.nowNanos)))
            assertTrue(eventHandled.await(5, TimeUnit.SECONDS))
        }
        fun help() {
            eventHandled = CountDownLatch(1)
            host.onEvent(UserAction(rig.envelope(g), UserAction.Action.Help))
            assertTrue(eventHandled.await(5, TimeUnit.SECONDS))
        }
        turn("a", 1); assertTrue(rig.requests.isEmpty()); help()
        val r = rig.requests.single(); rig.callbacks.single()(ModelStreamEvent.TextDelta(r.requestId, "建议。"))
        rig.clock.advance(150_000_000); rig.scheduler.runDue(); host.currentState
        assertEquals(listOf("建议。"), rig.device.shown.map { it.first.text })
        assertEquals(1, trace.snapshot().count { it.mark == TraceMark.FIRST_READABLE_HINT })
        assertFalse(trace.snapshot().any { it.mark == TraceMark.DISPLAY_ACK })
        rig.device.callbacks.single()(OutputStatus.Displayed)
        assertTrue(displayed.await(5, TimeUnit.SECONDS))
        assertEquals(1, trace.snapshot().count { it.mark == TraceMark.DISPLAY_ACK })
        rig.device.callbacks.single()(OutputStatus.Displayed)
        val clears = rig.device.clears.size; turn("b", 2)
        assertEquals(clears + 1, rig.device.clears.size); assertEquals(1, rig.handles.single().cancelCount)
        help(); val next = rig.requests.last(); rig.callbacks.last()(ModelStreamEvent.TextDelta(next.requestId, "新建议。"))
        host.pause(); rig.clock.advance(150_000_000); rig.scheduler.runDue()
        rig.device.callbacks.first()(OutputStatus.Displayed); rig.callbacks.last()(ModelStreamEvent.TextDelta(next.requestId, "迟到。"))
        assertEquals(1, rig.device.shown.size); assertEquals(1, trace.snapshot().count { it.mark == TraceMark.DISPLAY_ACK })
        assertEquals(1, rig.handles.last().cancelCount); assertEquals(1, rig.input.stopped.size)
        host.stop()
    }

    private class Providers {
        val clock = FakeClock(1_000_000_000); val scheduler = ManualScheduler(clock)
        val device = RecordingHintDevice(); val input = RecordingInputPort()
        val requests = mutableListOf<ModelRequest>(); val callbacks = mutableListOf<(ModelStreamEvent) -> Unit>()
        val handles = mutableListOf<FakeCancellation>(); val services = mutableListOf<HostServices>()
        val grants = mutableListOf<Set<Capability>>()
        private var eventSequence = 0L
        val profile = ModelProfile("dummy", 3, "dummy", "dummy", "https://invalid.test", "dummy", "dummy",
            object : SecretHandle { override val id = "dummy" }, 1000, 500, 100, 100, 10000,
            setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())
        private val storage = mutableMapOf<String, ByteArray>()
        private val providers: Map<Capability, Any> = mapOf(
            Capability.Model to object : ModelClient {
                override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                    requests += request; callbacks += listener; return FakeCancellation().also(handles::add)
                }
            },
            Capability.TextOutput to object : OutputSink { override fun submit(assistance: Assistance) = OutputSubmission.Accepted },
            Capability.Audio to object : AudioSubscription { override fun listen(listener: (AudioSegment) -> Unit) = FakeCancellation().also(handles::add) },
            Capability.Asr to object : AsrSession { override fun listen(listener: (TranscriptRevision) -> Unit) = FakeCancellation().also(handles::add) },
            Capability.Speaker to object : SpeakerService {
                override fun enroll(windows: List<SpeakerWindow>, listener: (SpeakerProfileRef?) -> Unit) = FakeCancellation().also(handles::add)
                override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit) = FakeCancellation().also(handles::add)
                override fun delete(profile: SpeakerProfileRef) = Unit
            },
            Capability.Storage to object : StorageHandle {
                override fun read(key: String) = storage[key]
                override fun write(key: String, value: ByteArray) { storage[key] = value }
                override fun delete(key: String) { storage.remove(key) }
                override fun clear() = storage.clear()
            },
            // Task 13 turned `plugins-smart` from a 12-line shell into the real passive loop and widened its
            // descriptor to `WakeWord, Asr, Model, Actions, TextOutput`; P3.2's Task 8 added `Context` (the plugin's
            // proactive path runs on the host's perception events). The host refuses to activate a plugin whose
            // declared capabilities are not all advertised, so this map now has to name every one of them — that is
            // the same "no fake success" rule the Speaker entry above exists for, not a new behaviour.
            Capability.WakeWord to object : WakeWordRegistry {
                override fun declare(words: List<String>) = true
                override fun current() = emptyList<String>()
            },
            Capability.Context to object : ContextSignals {
                override fun snapshot() = ContextSnapshot(0L, emptyMap())
                override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle = FakeCancellation()
            },
            // edith-0.3 Phase 2 (Task 9, f42b7b79): the smart plugin now declares `Capability.Perception`, and
            // `HostRuntime.activate` refuses a plugin whose declared capabilities are not all advertised. This rig
            // answers every other declared capability the way the App's composition does, so it must answer this
            // one too. The provider is deliberately inert - every source reports `unavailable(no_provider)` - so
            // this test stays about capability hand-over, not about perception content.
            Capability.Perception to InertPerception(),
            // 0.3 Phase 1 Task 12: the smart plugin now declares the static device profile channel, so a rig that
            // activates it must answer it the way the App's composition does (its Android provider is wired at
            // startup). The facts are synthetic and empty: this test is about capability hand-over, not prompt content.
            Capability.DeviceState to object : DeviceStateProvider {
                override fun profile() = DeviceProfile(
                    manufacturer = "Synthetic",
                    model = "Synthetic",
                    deviceName = null,
                    androidRelease = "0",
                    apiLevel = 0,
                    screenWidthPx = 0,
                    screenHeightPx = 0,
                    densityDpi = 0,
                    glassesModel = null,
                    glassesFirmware = null,
                    glassesWidthPx = null,
                    glassesHeightPx = null,
                    appVersion = "0",
                    locale = "zh-CN",
                    timeZone = "Asia/Shanghai",
                    wakeWords = emptyList(),
                    capabilities = emptySet(),
                )

                override fun state() = DeviceState(
                    capturedAtEpochMillis = 0L,
                    timeZone = "Asia/Shanghai",
                    localDate = "2026-10-20",
                    weekday = "星期一",
                    localTime = "00:00",
                )
            },
            Capability.Actions to object : ActionExecutor {
                override fun descriptors() = emptyList<ActionDescriptor>()
                override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, "done")
                override fun cancel() = Unit
            },
            // P3.3 (Task 13): the smart plugin declares `Confirmation` too (spec §17.6), and `activateSelected`
            // refuses a plugin whose declared capabilities are not all advertised. This rig therefore has to answer
            // it exactly as the App's composition does. Nothing in this test asks a question, so the channel never
            // presents one; it exists so that "declared" and "answerable" stay the same statement.
            Capability.Confirmation to object : ConfirmationChannel {
                override fun present(requestId: String, summary: String, timeoutMillis: Long) = FakeCancellation()
            },
            // edith-0.2 Phase B (B4): the smart plugin now declares the SKILL directory grant, and this rig's rule is
            // that "declared" and "answerable" are the same statement - the App's composition provides this one too.
            // Nothing in this test reads a SKILL; the provider exists so activation is judged on the App's terms.
            Capability.StorageAccess to object : StorageAccess {
                override fun listFiles(dirPath: String, suffix: String): List<String> = emptyList()
                override fun readFile(path: String): String? = null
                override fun deleteFile(path: String): Boolean = false
            },
            // edith-0.2 Phase B (B9, spec §1.3 C2): the smart plugin now declares both local models as hard
            // dependencies. This rig's rule is that "declared" and "answerable" are the same statement, and the
            // App's composition provides both - so both are answered here, in the ready state.
            Capability.SystemOne to object : SystemOneService {
                override fun ask(state: String, question: SystemOneQuestion): SystemOneResult =
                    SystemOneResult.Success(SystemOneAnswer.Noul(probability = 0.5, confidence = 0.5), latencyMs = 1.0)
                override val isAvailable = true
                override val status = SystemOneStatus.READY
                override val modelInfo: ModelInfo? = null
            },
            Capability.Embedding to object : EmbeddingService {
                override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(256))
                override val isAvailable = true
                override val modelInfo: ModelInfo? = null
                override val dimensions = 256
            },
            // edith-0.2 Phase C: the smart assistant's conversation port. It is **declared** by the plugin (that is
            // what makes it reachable - see the 2026-10-15 device report), and a declared capability the host cannot
            // provide refuses activation, so this composition-shaped rig has to answer it exactly as the phone app
            // does. A no-op is enough here: this test is about activation and provider compatibility, not about what
            // the port carries.
            Capability.ConversationDisplay to object : ConversationDisplay {
                override fun userMessage(route: AssistantRoute, text: String) = Unit
                override fun assistantMessage(route: AssistantRoute, text: String) = Unit
                override fun working(route: AssistantRoute) = Unit
                override fun idle(route: AssistantRoute) = Unit
            },
        )
        val host = HostRuntime(1, object : ScheduledHostEnvironment {
            override val clock = this@Providers.clock
            override val scheduler = this@Providers.scheduler
            override val input = this@Providers.input
            override val output = device
            override val availableCapabilities = providers.keys
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices {
                grants += granted
                return ScopedHostServices(scope, granted, providers, FakeTaskScope()).also(services::add)
            }
        })
        fun envelope(g: Generation) = EventEnvelope(SessionId("s"), g, "e-${eventSequence++}", clock.nowNanos, clock.nowNanos)
        fun request(g: Generation) = ModelRequest(envelope(g), "r", "t", "dummy", 3, "dummy instructions",
            listOf(InputPart.Text("dummy")), emptyList(), OutputModality.TEXT, 11_000_000_000)

        /** The empty, all-unavailable perception read: no screen, notification, media, acoustic or device content. */
        private class InertPerception : PerceptionProvider {
            private val snapshot = PerceptionSnapshot(
                snapshotId = "",
                capturedAtEpochMillis = 0L,
                coverage = PerceptionCoverage(
                    screen = "unavailable(no_provider)",
                    notifications = "unavailable(no_provider)",
                    media = "unavailable(no_provider)",
                    calendar = "unavailable(no_provider)",
                    acoustic = "unavailable(no_provider)",
                    clipboard = "unavailable(no_provider)",
                    vision = "unavailable(no_provider)",
                    glasses = "unavailable(no_provider)",
                    device = "unavailable(no_provider)",
                ),
            )

            override fun beginTurn() = snapshot
            override fun snapshot(
                include: Set<PerceptionSource>,
                filter: PerceptionFilter,
                limit: Int?,
            ) = snapshot
            override fun afterAction() = snapshot
            override fun screenshot() = PerceptionScreenshotResult.Disabled
            override fun endTurn() = Unit
        }
    }
}
