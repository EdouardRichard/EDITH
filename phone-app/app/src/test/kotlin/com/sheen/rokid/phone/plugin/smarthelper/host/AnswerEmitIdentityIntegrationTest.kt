package com.sheen.rokid.phone.plugin.smarthelper.host

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.host.ActivationResult
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.bridge.ConversationWire
import com.sheen.rokid.phone.bridge.ConversationWireDisplay
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryExtraction
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
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import com.sheen.rokid.plugin.StorageAccess
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import com.sheen.rokid.storage.ProfileRepository
import com.sheen.rokid.testsupport.FakeCancellation
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24 S3 Task 14 fix round (C1): the answer's identity is **per emit**, not per turn.
 *
 * The plugin unit tests hand-craft `OutputStateEvent`s, so they never exercised the event the real arbiter produces
 * when a second emit replaces the first in the same turn: `OutputArbiter.submit` removes the displayed item with
 * `Rejected` **before** the new item is shown, and the host's FIFO dispatcher delivers that replacement rejection
 * ahead of the new hint's `Displayed`. With one hint id per turn, the plugin read that "superseded" event as "the final
 * answer was rejected", closed the panel and cancelled the answer.
 *
 * These tests therefore build the real chain - `HostRuntime` + its `OutputArbiter` + the real `SmartAssistantPlugin`
 * the production `SmartWiring` builds - and drive the two normal production shapes that emit twice in one turn:
 *
 * - an explicit-memory acknowledgement (`recordAsAnswer = false`) followed by the closing answer from the assembler;
 * - two `notify_user` calls in one run (spec §6.3 explicitly supports start/done notices).
 *
 * The hint device defers its `Displayed` acknowledgement to the test, which makes the ordering deterministic: the
 * replacement `Rejected` is delivered while the final answer's `Displayed` has not happened yet, exactly the
 * "rejected settles no_display before the new hint is acknowledged" sequence the review described.
 */
class AnswerEmitIdentityIntegrationTest {

    // ---------------------------------------------------------------- fakes

    /**
     * A display that records what reached the glasses and **defers** the acknowledgement.
     *
     * The real panel acknowledges from another process; deferring it here is what makes the replacement ordering
     * deterministic instead of a race between the arbiter's executor and the model round trip.
     */
    private class DeferredHintDevice : HintDevice {
        val shown = CopyOnWriteArrayList<Assistance>()
        private val listeners = CopyOnWriteArrayList<(OutputStatus) -> Unit>()
        override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
            shown += assistance
            listeners += listener
            return FakeCancellation()
        }

        /** Acknowledges the hint currently on screen, the way the glasses' display path does. */
        fun acknowledgeLatest() {
            check(listeners.isNotEmpty()) { "nothing was shown" }
            listeners.last()(OutputStatus.Displayed)
        }

        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }

    /**
     * The production [ConversationWireDisplay] plus the level/close observations the tests make.
     *
     * The real display is used so the phone's conversation record is part of the evidence: Task 16 I1 is about which
     * entry a statement lands in, which a no-op fake cannot show.
     */
    private class RecordingDisplay : ConversationDisplay {
        val log = ConversationLog()

        /** No official traffic is asserted here; the wire only has to exist for the production display. */
        private val wire = object : ConversationWire {
            override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) = true
            override fun idle(route: AssistantRoute, sequence: Long) = true
            override fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String) = true
            override fun answer(
                route: AssistantRoute,
                turnId: String,
                hintId: String,
                revision: Long,
                text: String,
            ) = true
            override fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) = true
            override fun panelOpen(route: AssistantRoute) = true
            override fun close(route: AssistantRoute) = true
        }
        private val records = ConversationWireDisplay(
            log = log,
            wire = wire,
            sendSaid = { _, _, _ -> true },
            sendBusy = { _, _ -> true },
        )

        val statuses = CopyOnWriteArrayList<Int>()
        val closes = CopyOnWriteArrayList<AssistantRoute>()

        override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
            statuses += status
            records.state(route, turnId, status, sequence)
        }

        override fun closePanel(route: AssistantRoute) {
            closes += route
            records.closePanel(route)
        }

        override fun showIdle(route: AssistantRoute, sequence: Long) = records.showIdle(route, sequence)
        override fun userMessage(route: AssistantRoute, text: String) = records.userMessage(route, text)
        override fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) =
            records.userPartial(route, turnId, revision, text)
        override fun userFinal(route: AssistantRoute, turnId: String, entryId: String, text: String) =
            records.userFinal(route, turnId, entryId, text)
        override fun assistantMessage(route: AssistantRoute, text: String) = records.assistantMessage(route, text)
        override fun assistantPartial(
            route: AssistantRoute,
            turnId: String,
            hintId: String,
            revision: Long,
            text: String,
        ) = records.assistantPartial(route, turnId, hintId, revision, text)
        override fun assistantFinal(route: AssistantRoute, turnId: String, hintId: String, text: String) =
            records.assistantFinal(route, turnId, hintId, text)
        override fun assistantNotice(route: AssistantRoute, text: String) = records.assistantNotice(route, text)
        override fun toolStatus(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) =
            records.toolStatus(route, turnId, phase, label)
        override fun panelOpen(route: AssistantRoute) = records.panelOpen(route)
        override fun working(route: AssistantRoute) = records.working(route)
        override fun idle(route: AssistantRoute) = records.idle(route)
    }

    /**
     * A model client with a per-request script.
     *
     * The request listener is called **inline**, which models a synchronous transport and keeps the whole turn on the
     * host's thread: the emits, the arbiter replacement and the run-end settlement then happen in the order a real
     * local model's stream does (the arbiter's own event callbacks are still queued on its executor).
     */
    private class ScriptedModel : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        var script: (index: Int, request: ModelRequest, listener: (ModelStreamEvent) -> Unit) -> Unit =
            { _, _, _ -> }

        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            val index = requests.size
            requests += request
            script(index, request, listener)
            return FakeCancellation()
        }
    }

    private class FixedProfiles(private val profile: ModelProfile) : ProfileRepository {
        override fun list(): List<ModelProfile> = listOf(profile)
        override fun get(id: String): ModelProfile? = profile.takeIf { it.id == id }
        override fun save(profile: ModelProfile) = profile
        override fun delete(id: String) = Unit
        override fun verify(id: String, result: CapabilityVerification) = Unit
        override fun listenChanges(listener: (String) -> Unit): CancellationHandle = FakeCancellation()
    }

    private class NoActions : ActionExecutor {
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Refused, "UNKNOWN_ACTION")
        override fun cancel() = Unit
    }

    private class NoConfirmations : ConfirmationChannel {
        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
            FakeCancellation()
    }

    private class StubAsrSession : AsrSession {
        override fun listen(listener: (TranscriptRevision) -> Unit): CancellationHandle = FakeCancellation()
    }

    private class EmptyContextSignals : ContextSignals {
        override fun snapshot() = ContextSnapshot(0L, emptyMap())
        override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle = FakeCancellation()
    }

    private class NoStorageAccess : StorageAccess {
        override fun listFiles(dirPath: String, suffix: String): List<String> = emptyList()
        override fun readFile(path: String): String? = null
        override fun deleteFile(path: String): Boolean = false
    }

    private class StubStorage : StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() = values.clear()
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

    /** A speech player whose terminal state the test fires by hand, keyed by the emit id it was asked to play. */
    private class ControlledSpeechPlayer : SpeechPlayer {
        private val callbacks = mutableMapOf<String, (SpeechPlayback) -> Unit>()
        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            val key = assistance.emitId ?: assistance.hintId
            callbacks[key] = onState
            onState(SpeechPlayback.STARTED)
            return FakeCancellation()
        }

        fun finish(emitId: String) { callbacks.getValue(emitId)(SpeechPlayback.FINISHED) }
    }

    private object InlineTasks : TaskScope {
        override fun launch(block: () -> Unit): CancellationHandle { block(); return FakeCancellation() }
        override fun cancelAll() = Unit
    }

    // ---------------------------------------------------------------- the production chain

    private class Rig(
        private val speechAvailable: Boolean = false,
        private val speechPlayer: SpeechPlayer? = null,
    ) {
        val session = SessionId("s-1")
        val clock = FakeClock(1_000_000_000L)
        val scheduler = ManualScheduler(clock)
        val display = RecordingDisplay()
        val device = DeferredHintDevice()
        val model = ScriptedModel()
        val profile = ModelProfile(
            "p-1", 3, "Synthetic", "Synthetic", "https://synthetic.example/v1", "DEEPSEEK_RESPONSES",
            "synthetic-model", object : SecretHandle { override val id = "draft-placeholder" },
            4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList(),
        )
        val settings = SmartSettings(
            wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
            profileId = "p-1", cooldownMillis = 0L, continueDialogue = true,
        )
        val host: HostRuntime
        val plugin: SmartAssistantPlugin

        init {
            val hostHolder = arrayOfNulls<HostRuntime>(1)
            // The same wiring the production composition builds: the persisted switches, one clock/scheduler for the
            // environment and the plugin, and the host's own output-turn cancellation. The panel is already visible so
            // a wake starts its listening window immediately instead of waiting for a PANEL_SHOWN this rig does not
            // produce.
            val wiring = SmartWiring(
                settings,
                FixedProfiles(profile),
                clock,
                scheduler,
                cancelOutputTurn = { s, g, t -> hostHolder[0]?.cancelOutputTurn(s, g, t) },
                isPanelVisible = { true },
                speechAvailable = { speechAvailable },
            )
            plugin = wiring.plugin
            val environment = CxrHostEnvironment(
                clock = clock,
                input = RecordingInputPort(),
                liveOutput = { device },
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
                    // 0.3 Phase 1 Task 12: the smart plugin declares the static device profile channel; the App's
                    // composition answers it with its Android provider, so a composition-shaped rig must answer it too
                    // or the host refuses activation.
                    Capability.DeviceState to SyntheticDeviceState(),
                    // 0.3 Phase 2 Task 9: the per-turn perception port the request tail renders from. The tests here
                    // are about emission identity, so it answers an all-unavailable snapshot.
                    Capability.Perception to PassiveLoopWiringTest.InertPerception(),
                    Capability.ConversationDisplay to display,
                ),
                storageFor = { StubStorage() },
                taskScope = InlineTasks,
                speech = speechPlayer,
            )
            host = HostRuntime(1, environment)
            hostHolder[0] = host
            host.register(plugin)
            host.configure("smart-assistant", wiring.configuration(wiring.selectedProfile()))
            host.startSession(session)
            host.markSceneReady()
            assertEquals(ActivationResult.Activated, host.activate("smart-assistant"))
        }

        private var serial = 0L

        fun envelope() = EventEnvelope(
            session, host.currentGeneration, "e-${serial++}", clock.nowNanos, clock.nowNanos,
        )

        /** A wake the host accepts and delivers; the command barrier proves the plugin saw it. */
        fun wake() {
            assertTrue(host.onEvent(WakeDetected(envelope(), "伊迪丝", 1.0)))
            settle()
        }

        /**
         * One final transcript. The whole turn runs inline on the host's dispatcher: the emits, the arbiter
         * replacement and the run-end settlement all happen before this returns, exactly as they do when the host
         * delivers the ASR final and the model answers synchronously.
         */
        fun say(text: String) {
            assertTrue(host.onEvent(TranscriptEvent(TranscriptRevision(
                envelope(), "asr-1", 1, text, final = true, clock.nowNanos, clock.nowNanos,
            ))))
            settle()
        }

        /** Releases deadlines and drains the host's event queue: reading a state is the barrier. */
        fun tick(millis: Long) {
            clock.advance(millis * 1_000_000L)
            scheduler.runDue()
            settle()
        }

        /**
         * A barrier that proves every event produced so far was delivered.
         *
         * The display/speech callbacks are posted to the arbiter's serial executor, which then calls `onEvent`; a
         * command that goes through that same executor (`cancelHint`) runs after them, and reading a host state then
         * waits for the dispatcher drain that the arbiter's `onEvent` scheduled.
         */
        fun settle() {
            host.cancelOutputHint(session, host.currentGeneration, "barrier-turn", "barrier-hint")
            host.currentState
        }

        fun acknowledgeLatest() {
            device.acknowledgeLatest()
            settle()
        }

        fun close() { runCatching { host.close() } }
    }

    private fun withRig(block: (Rig) -> Unit) = Rig().let { rig ->
        try { block(rig) } finally { rig.close() }
    }

    private fun toolCall(
        request: ModelRequest,
        listener: (ModelStreamEvent) -> Unit,
        text: String,
        channel: String,
    ) {
        listener(ModelStreamEvent.ToolCallTerminal(
            requestId = request.requestId,
            itemId = "item-$text",
            callId = "call-$text",
            name = "notify_user",
            argsJson = """{"text":"$text","channel":"$channel"}""",
        ))
    }

    private fun complete(request: ModelRequest, listener: (ModelStreamEvent) -> Unit) {
        listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
    }

    // ---------------------------------------------------------------- C1: one turn, two emits

    @Test
    fun anExplicitMemoryAcknowledgementThenTheFinalAnswerContinuesOnTheFinalAnswersDisplay() {
        withRig { rig ->
            rig.model.script = { _, request, listener ->
                listener(ModelStreamEvent.TextDelta(request.requestId, "好的，记住了。"))
                complete(request, listener)
            }

            rig.wake()
            rig.say("记住我的钥匙在抽屉里")
            // The acknowledgement is emitted during the run; the closing fallback rides the assembler's 150 ms merge.
            assertEquals("the acknowledgement is displayed first", 1, rig.device.shown.size)
            rig.tick(150)
            assertEquals("the final answer is the second emit", 2, rig.device.shown.size)
            val acknowledgement = rig.device.shown[0]
            val answer = rig.device.shown[1]
            assertEquals(
                "both emits are one entry, so they share the turn hint id",
                acknowledgement.hintId, answer.hintId,
            )

            // By now the real arbiter has replaced the acknowledgement and the host delivered its `Rejected`. The
            // final answer has not been acknowledged yet - this is exactly the ordering that used to close the panel.
            assertTrue(
                "a superseded emit's rejection must not close the conversation",
                rig.display.closes.isEmpty(),
            )
            assertEquals("the run took one model request", 1, rig.model.requests.size)
            assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses)

            rig.acknowledgeLatest()

            assertTrue(
                "the displayed final answer continues instead of closing",
                rig.display.closes.isEmpty(),
            )
            assertEquals(OfficialStatus.AUDIO_FINISHED.wire, rig.display.statuses[rig.display.statuses.size - 2])
            assertEquals(
                "the next listening round opened",
                OfficialStatus.LISTENING.wire, rig.display.statuses.last(),
            )
        }
    }

    @Test
    fun aNotifyAnswerFollowedByTheMemoryAcknowledgementKeepsTheAnswerRecord() {
        withRig { rig ->
            rig.model.script = { index, request, listener ->
                if (index == 0) {
                    toolCall(request, listener, "答案", "screen")
                } else {
                    // The closing text is suppressed because the notification already reached the wearer.
                    listener(ModelStreamEvent.TextDelta(request.requestId, "完成"))
                }
                complete(request, listener)
            }

            rig.wake()
            rig.say("记住我不吃香菜")

            assertEquals("the answer and the acknowledgement both reached the glasses", 2, rig.device.shown.size)
            val answer = rig.device.shown.first { it.text == "答案" }
            val acknowledgement = rig.device.shown.first { it.text == MemoryExtraction.ACKNOWLEDGEMENT }
            assertEquals("both emits are one conversation entry", answer.hintId, acknowledgement.hintId)
            assertTrue(
                "the answer has not been settled: its Displayed ACK is still in flight",
                rig.display.closes.isEmpty(),
            )
            assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses)

            val entries = rig.display.log.entries(AssistantRoute.WAKE)
            val answerEntry = entries.single { it.entryId == "say-${answer.hintId}" }
            assertEquals(ConversationRole.ASSISTANT, answerEntry.role)
            assertEquals("the acknowledgement must not overwrite the answer row", "答案", answerEntry.text)
            assertEquals(
                "exactly one entry has the answer identity",
                1,
                entries.count { it.entryId == "say-${answer.hintId}" },
            )
            val notice = entries.single {
                it.role == ConversationRole.ASSISTANT && it.entryId != "say-${answer.hintId}"
            }
            assertEquals("the acknowledgement is its own entry", MemoryExtraction.ACKNOWLEDGEMENT, notice.text)
            assertTrue("an assistant record keeps the say- identity", notice.entryId.startsWith("say-"))
        }
    }

    @Test
    fun twoNotifyUserEmitsInOneTurnLeaveTheFinalAnswerDisplayedAndContinuing() {
        withRig { rig ->
            rig.model.script = { index, request, listener ->
                if (index == 0) {
                    // Spec §6.3: a task-start and a task-done notification in one run.
                    toolCall(request, listener, "开始处理", "screen")
                    toolCall(request, listener, "已经完成", "screen")
                    complete(request, listener)
                } else {
                    // The closing text is suppressed because the notifications already reached the wearer.
                    listener(ModelStreamEvent.TextDelta(request.requestId, "完成"))
                    complete(request, listener)
                }
            }

            rig.wake()
            rig.say("帮我处理一下")
            assertEquals("both notifications were emitted", 2, rig.device.shown.size)
            val first = rig.device.shown[0]
            val second = rig.device.shown[1]
            assertEquals(first.hintId, second.hintId)
            assertEquals("已经完成", second.text)

            assertTrue(
                "the first emit's rejection is not the final answer's terminal",
                rig.display.closes.isEmpty(),
            )
            assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses)

            rig.acknowledgeLatest()

            assertTrue(
                "the second notification is the answer that continues",
                rig.display.closes.isEmpty(),
            )
            assertEquals(OfficialStatus.AUDIO_FINISHED.wire, rig.display.statuses[rig.display.statuses.size - 2])
            assertEquals(OfficialStatus.LISTENING.wire, rig.display.statuses.last())
        }
    }

    // ---------------------------------------------------------------- C2: speech off

    @Test
    fun aSpeechOffBothAnswerContinuesOnItsDisplayWithoutWaitingForSpeech() {
        withRig { rig ->
            rig.model.script = { index, request, listener ->
                if (index == 0) toolCall(request, listener, "答案", "both")
                else listener(ModelStreamEvent.TextDelta(request.requestId, "完成"))
                complete(request, listener)
            }

            rig.wake()
            rig.say("问题")
            val answer = rig.device.shown.single()
            assertNull("no player: the answer is display-only", answer.optionalSpeech)

            rig.acknowledgeLatest()

            assertTrue("the display acknowledgement is the next-round trigger", rig.display.closes.isEmpty())
            assertEquals(OfficialStatus.AUDIO_FINISHED.wire, rig.display.statuses[rig.display.statuses.size - 2])
            assertEquals(OfficialStatus.LISTENING.wire, rig.display.statuses.last())
        }
    }

    @Test
    fun aStaleSpeechEndFromASupersededEmitCannotSettleTheCurrentAnswer() {
        val speech = ControlledSpeechPlayer()
        val rig = Rig(speechAvailable = true, speechPlayer = speech)
        try {
            rig.model.script = { index, request, listener ->
                if (index == 0) toolCall(request, listener, "答案", "both")
                else listener(ModelStreamEvent.TextDelta(request.requestId, "完成"))
                complete(request, listener)
            }

            rig.wake()
            rig.say("问题")
            val answer = rig.device.shown.single()
            assertTrue("the answer was asked to speak", answer.optionalSpeech != null)
            val emitId = requireNotNull(answer.emitId)

            // A playback end for an earlier emit of the same conversation entry (same hint id, different emit id)
            // that was already queued in the host dispatcher when the replacement happened.
            assertTrue(rig.host.onEvent(SpeechFinished(
                rig.envelope(), answer.hintId, SpeechResult.FINISHED, "emit-superseded",
            )))
            rig.settle()
            rig.acknowledgeLatest()

            assertTrue(
                "the current answer's speech is still playing; the stale result must not settle it",
                OfficialStatus.AUDIO_FINISHED.wire !in rig.display.statuses,
            )

            speech.finish(emitId)
            rig.settle()

            assertTrue(rig.display.closes.isEmpty())
            assertEquals(OfficialStatus.AUDIO_FINISHED.wire, rig.display.statuses[rig.display.statuses.size - 2])
            assertEquals(OfficialStatus.LISTENING.wire, rig.display.statuses.last())
        } finally {
            rig.close()
        }
    }
}

/**
 * 0.3 Phase 1 Task 12: the static device profile channel a composition-shaped rig must answer.
 *
 * The facts are synthetic and empty: this file is about the answer's emit identity, not about prompt content, and an
 * absent profile is a legal state the prompt omits rather than invents.
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
