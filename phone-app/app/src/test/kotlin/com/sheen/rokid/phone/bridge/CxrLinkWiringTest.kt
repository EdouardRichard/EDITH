package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.app.HostCompositionRoot

import android.app.Activity
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.PinChanged
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authorization fact belongs to the SDK's own callback, not to the connect button: `MainActivity.onActivityResult`
 * forwards the result, and the page may only report what arrived there. This test keeps the two apart, which is what the
 * owner-reported defect (2026-10-10) was about.
 */
class CxrLinkWiringTest {

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    private class Rig {
        val clock = FakeClock(0)
        val connection = CodeVerdictConnection()
        val root: HostCompositionRoot

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
            )
            root = HostCompositionRoot(
                HostRuntime(1, environment), MemoryProfileRepository(), UnusedModel(), environment,
                rokidConnectionFactory = { connection },
            )
        }
    }

    /**
     * A live transport whose own parse of the result is the verdict, exactly as the real transport's is.
     *
     * The double answers the verdict **given to it** rather than deriving it, because the parse lives in the transport
     * (the vendor's `AuthResult` never leaves `transport-rokid`); what these tests pin is that the page repeats it.
     */
    private class VerdictConnection(private val verdict: CxrAuthorizationOutcome?) : CxrConnection {
        var lastResultCode: Int? = null
        override var ready: Boolean = false
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? {
            lastResultCode = resultCode
            return verdict
        }
        override fun sendCommand(json: String): Boolean = false
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
    }

    /**
     * The transport's verdict for a result the SDK **did** parse successfully, i.e. where the raw code and the parse
     * agree. Standing in for the success path is what keeps these tests about the page: the parsed-failure path is the
     * one [VerdictConnection] is for, and the parse rule itself is pinned in `transport-rokid`'s `CxrBackendTest`.
     */
    private class CodeVerdictConnection : CxrConnection {
        override var ready: Boolean = false
        /** Every `connect` the composition asked for; W1's evidence that a session start really establishes the link. */
        var connects: Int = 0
        override fun connect(activity: Any) { connects++ }
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? =
            when (resultCode) {                Activity.RESULT_OK -> CxrAuthorizationOutcome.CONFIRMED
                Activity.RESULT_CANCELED -> CxrAuthorizationOutcome.CANCELLED
                else -> CxrAuthorizationOutcome.UNEXPECTED
            }
        override fun sendCommand(json: String): Boolean = false
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
    }

    /**
     * W1 (F17/F18): **开始** establishes the link, with no other tap anywhere.
     *
     * The device symptom was "point 开始, then `准备中` forever, and the new process never even *tried* to connect" -
     * the only first-use entry point was a Compose button in one UI combination. The session start path now calls the
     * link itself, so the count below must grow on `start()` and not only on `attachCxrActivity`.
     */
    @Test
    fun startingASessionEstablishesTheLinkWithoutAnyOtherTap() {
        val rig = Rig()
        try {
            rig.root.attachCxrActivity(Any())
            assertEquals("the composition is wired and connected once the Activity exists", 1, rig.connection.connects)
            rig.root.stop()
            assertEquals("停止 disarms but keeps the built connection", 1, rig.connection.connects)

            rig.root.start("p2-diagnostic")

            assertEquals("开始 itself must (re)establish the link", 2, rig.connection.connects)
        } finally { rig.root.close() }
    }

    /** A start while already armed is a no-op for the link; only 停止 disarms it (D53-③). */
    @Test
    fun aSecondStartWhileAlreadyConnectedDoesNotAskTheSdkAgain() {
        val rig = Rig()
        try {
            rig.root.attachCxrActivity(Any())
            // Captured **before** `start()`. Capturing after it and comparing to itself was tautological (review M2):
            // it could never fail, so the `linkBuilt` guard it was meant to pin was not pinned at all.
            val armed = rig.connection.connects
            rig.root.start("p2-diagnostic")
            assertEquals("开始 on an armed link must not reconnect", armed, rig.connection.connects)
            rig.root.stop()
            rig.root.start("p2-diagnostic")

            assertEquals("but a stop+start cycle must", armed + 1, rig.connection.connects)
        } finally { rig.root.close() }
    }

    @Test
    fun theSdkAuthorizationResultBecomesAPageFact() {
        val rig = Rig()
        assertNull("nothing is on the page before the SDK answers", rig.root.state.value.cxrAuthorization)
        assertTrue("the transport is the party that asks", rig.root.connectCxr(Any()))

        rig.root.onCxrAuthorizationResult(Activity.RESULT_OK, null)
        assertEquals(CxrAuthorizationOutcome.CONFIRMED, rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(Activity.RESULT_CANCELED, null)
        assertEquals(CxrAuthorizationOutcome.CANCELLED, rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(42, null)
        assertEquals(CxrAuthorizationOutcome.UNEXPECTED, rig.root.state.value.cxrAuthorization)
    }

    @Test
    fun aResultTheTransportParsedAsAFailureIsNotAPageConfirmation() {
        // Review Minor 6: the verdict was derived from the **bare** `resultCode`, so a result the SDK's own parser
        // refused (`RESULT_OK` with an `AuthResult` that is not a success - the vendor's parser reports a fail for an
        // `auth_result` extra it does not recognise, and for a missing/blank token) still printed
        // "眼镜授权确认已完成". The page may only say what the transport parsed, and the transport is the party that
        // holds the vendor's `AuthResult`.
        val connection = VerdictConnection(CxrAuthorizationOutcome.UNEXPECTED)
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            rokidConnectionFactory = { connection },
        )
        try {
            assertTrue(root.connectCxr(Any()))

            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertEquals("the result must still reach the transport", Activity.RESULT_OK, connection.lastResultCode)
            assertNotEquals(
                "a result the transport parsed as a failure may not be reported as a confirmed authorization",
                CxrAuthorizationOutcome.CONFIRMED, root.state.value.cxrAuthorization,
            )
        } finally { root.close() }
    }

    @Test
    fun theTransportReadinessBecomesAPageFact() {
        // The page's "connected" wording used to hang off `Capability.Audio`, which `CxrHostEnvironment` deliberately
        // never advertises - so the ready branch was unreachable on a phone. The transport's own signal is the fact.
        val rig = Rig()
        assertFalse("nothing may be reported before the transport says so", rig.root.state.value.cxrLinkReady)

        rig.root.onSceneReady()

        assertTrue("the SDK's live-session signal must reach the page", rig.root.state.value.cxrLinkReady)
    }

    @Test
    fun aNewConnectAttemptClearsThePreviousAuthorizationAnswer() {
        // Review I-2: a cancelled prompt used to stay on the page forever, next to a link that came up afterwards. The
        // answer describes one attempt, so a new attempt starts it empty again.
        val rig = Rig()
        assertTrue(rig.root.connectCxr(Any()))
        rig.root.onCxrAuthorizationResult(Activity.RESULT_CANCELED, null)
        assertEquals(CxrAuthorizationOutcome.CANCELLED, rig.root.state.value.cxrAuthorization)

        rig.root.connectCxr(rig.root)

        assertNull("a new attempt must not carry the previous verdict", rig.root.state.value.cxrAuthorization)
    }

    @Test
    fun anActivityResultNoPromptAskedForIsNotAPageFact() {
        // Review I-3: `onActivityResult` also carries the results of everything else this Activity starts for -
        // including the App's own `permissions.launch(...)` dialogs - and the App recorded all of them as the SDK's
        // authorization answer. The transport is the only party that knows whether a prompt is pending (it armed the
        // callback inside `connect`), so a result it does not answer must leave the page alone instead of inventing
        // "眼镜授权被取消".
        val connection = VerdictConnection(null)
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            rokidConnectionFactory = { connection },
        )
        try {
            assertTrue(root.connectCxr(Any()))
            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertEquals("the result must still reach the transport", Activity.RESULT_OK, connection.lastResultCode)
            assertNull("a result no prompt asked for must not become a page fact", root.state.value.cxrAuthorization)
        } finally { root.close() }
    }

    @Test
    fun theCompositionOwnedAuthorizationRouteIsAHonestClaimToo() {
        // Review I-3, Minor: the second route an activity result can take into the transport (the composition's own
        // connection holder) carries the same verdict contract, and no test had ever passed a non-default one - a
        // lambda that recorded the result while quietly answering "not mine" would have gone unnoticed.
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            authorizeResult = { _, _ -> CxrAuthorizationOutcome.CONFIRMED },
        )
        try {
            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertEquals(CxrAuthorizationOutcome.CONFIRMED, root.state.value.cxrAuthorization)
        } finally { root.close() }
    }

    @Test
    fun aFailureVerdictOnTheCompositionOwnedRouteIsNotAConfirmationEither() {
        // Review Minor 6 also has to hold on the second injection point: this route hands the page a verdict of its
        // own, so a `RESULT_OK` whose parsed answer failed must be reported as an anomaly there too - the point of
        // deriving the page fact from the verdict is that it cannot depend on which route delivered it.
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            authorizeResult = { _, _ -> CxrAuthorizationOutcome.UNEXPECTED },
        )
        try {
            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertNotEquals(
                "the second route's verdict is the page's fact too",
                CxrAuthorizationOutcome.CONFIRMED, root.state.value.cxrAuthorization,
            )
            assertEquals(CxrAuthorizationOutcome.UNEXPECTED, root.state.value.cxrAuthorization)
        } finally { root.close() }
    }

    // ---------------------------------------------------------------- Task 11: real conversation wire

    /** One conversation wire that records every official command the display forwards. */
    private class RecordingWire : ConversationWire {
        val calls = mutableListOf<String>()

        override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long): Boolean {
            calls += "state:$route:$turnId:$status:$sequence"
            return true
        }

        override fun idle(route: AssistantRoute, sequence: Long): Boolean {
            calls += "idle:$route:$sequence"
            return true
        }

        override fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String): Boolean {
            calls += "asr:$route:$turnId:$revision"
            return true
        }

        override fun answer(route: AssistantRoute, turnId: String, hintId: String, revision: Long,
            text: String): Boolean {
            calls += "answer:$route:$turnId:$hintId:$revision"
            return true
        }

        override fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String): Boolean {
            calls += "tool:$route:$turnId:$phase:$label"
            return true
        }

        override fun panelOpen(route: AssistantRoute): Boolean {
            calls += "panelOpen:$route"
            return true
        }

        override fun close(route: AssistantRoute): Boolean {
            calls += "close:$route"
            return true
        }
    }

    @Test
    fun theConversationDisplayForwardsAStateToTheWireItWasBuiltWith() {
        // Task 6 review gate: a production display built with the 3-arg constructor would look identical to the real
        // one and silently refuse every official command. This asserts the composition-level object is built with a
        // wire (the production one is the CxrHintLink, below) and that `state` really reaches it.
        val log = ConversationLog()
        val wire = RecordingWire()
        val display = ConversationWireDisplay(log, wire, { _, _, _ -> true }, { _, _ -> true })
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            conversationLog = log, conversationDisplay = display,
        )
        try {
            assertSame("the composition must own the display it was given", display, root.conversationDisplay)

            root.conversationDisplay!!.state(AssistantRoute.WAKE, "t-1", 1, 5)

            assertEquals(listOf("state:WAKE:t-1:1:5"), wire.calls)
        } finally { root.close() }
    }

    @Test
    fun theProductionAssemblyUsesTheRealHintLinkAsTheConversationWire() {
        // The 3-arg silent constructor is for old links/tests only. The production `create` must pass the real
        // `hintLink`, or the whole official wire stays silently dead while the display tests pass.
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val display = requireNotNull(root.conversationDisplay) {
                "the production composition must build a ConversationWireDisplay"
            }
            assertTrue("the silent wire is not the production wire", display.wire is CxrHintLink)
            assertSame("`create` must pass its own hint link", root.hintLinkForProbe, display.wire)
        } finally { root.close() }
    }

    @Test
    fun theDisplayAndLinkTogetherSendTheFinalUtteranceUnderTheAsrEntryId() {
        // The real composition shape: `ConversationWireDisplay` records, its `sendSaid` is `CxrHintLink.userSaid`, and
        // the id the display chose - not one minted inside the link - is what the wire carries.
        val clock = FakeClock(0)
        val sent = mutableListOf<String>()
        val link = CxrHintLink(clock, ManualScheduler(clock), sendJson = { json -> sent += json; true })
        link.sessionEstablished("s-1", 1)
        sent.clear()
        val display = ConversationWireDisplay(
            log = ConversationLog(),
            wire = link,
            sendSaid = { route, entryId, text -> link.userSaid(route, entryId, text) },
            sendBusy = { route, busy -> link.busy(route, busy) },
        )

        display.userFinal(AssistantRoute.WAKE, "turn-7", "asr-turn-7", "最终的话")

        val said = sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Said }.single()
        assertEquals("asr-turn-7", said.entryId)
        assertEquals("最终的话", said.text)
    }

    @Test
    fun theProductionAssemblySubscribesTheConversationTimersToThePanelLifecycle() {
        // 0.24 S3 fix (C1): `onPanelShown`/`onUiClosed` used to be assigned only by tests, so a real wake waited for a
        // PANEL_SHOWN that never reached the plugin (no STATE 1, no 10 s). `create` owns a smart wiring, so the
        // composition itself must be the subscriber.
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            assertNotNull("`create` must register the smart plugin", root.smartPluginForProbe)
            assertNotNull("PANEL_SHOWN must reach the conversation cycle", root.onPanelShown)
            assertNotNull("UI_CLOSED must reach the conversation cycle", root.onUiClosed)
        } finally { root.close() }
    }

    // ---------------------------------------------------------------- Task 11: vendor-thread marshalling

    private class RecordingPlugin : PluginLifecycle {
        val events = mutableListOf<HostEvent>()
        override val descriptor = PluginDescriptor(
            "recording", "1.0.0", 1, "Recording", emptyList(), ConfigSchema(1))
        override fun activate(context: PluginContext) = Unit
        override fun handle(event: HostEvent) { events += event }
        override fun deactivate(reason: DeactivationReason) = Unit
    }

    @Test
    fun aPinIsDeliveredAsPinChangedOffTheVendorCallbackThreadWithTheValidatedIdentity() {
        // `CxrHintLink.onPin` runs on the vendor's callback thread. The composition must wrap it in the host event
        // with the identity the link validated - not the identity current at delivery time - and marshal it away from
        // that thread, or the vendor callback runs host policy.
        val clock = FakeClock(1_000_000_000L)
        val environment = CxrHostEnvironment(
            clock = clock,
            input = RecordingInputPort(),
            liveOutput = { null },
            scheduler = ManualScheduler(clock),
        )
        val dispatched = mutableListOf<() -> Unit>()
        val plugin = RecordingPlugin()
        val host = HostRuntime(1, environment)
        val root = HostCompositionRoot(
            host, MemoryProfileRepository(), UnusedModel(), environment,
            dispatchHostEvent = { task -> dispatched += task },
        )
        try {
            root.registerPlugin(plugin)
            root.onSceneReady()
            root.start("recording")
            val identity = requireNotNull(host.currentIdentity()) { "the started session has an identity" }

            root.onPinFromLink(HintIdentity(identity.first.value, identity.second.value), AssistantRoute.PROACTIVE)

            assertTrue("nothing may run inline on the vendor thread: ${plugin.events}", plugin.events.isEmpty())
            assertEquals("the pin must be queued for another thread", 1, dispatched.size)
            dispatched.removeAt(0).invoke()
            // HostRuntime delivers events on its own executor, so the wrapped event is observable shortly after the
            // hand-off rather than synchronously in the dispatching task.
            val deadline = System.nanoTime() + 2_000_000_000L
            while (plugin.events.none { it is PinChanged } && System.nanoTime() < deadline) Thread.sleep(10)
            val pin = plugin.events.filterIsInstance<PinChanged>().single()
            assertEquals(AssistantRoute.PROACTIVE, pin.route)
            assertEquals("the envelope must carry the identity the link validated", identity.first,
                pin.envelope.sessionId)
            assertEquals("the envelope must carry the generation the link validated", identity.second,
                pin.envelope.generation)
        } finally { root.close() }
    }

    @Test
    fun aQueuedPinFromThePreviousSessionIsRefusedAfterTheHostMovesOn() {
        // Review I2: the link validates PIN against the established session/generation, but the composition used to
        // stamp the queued event with `currentIdentity()` at delivery time. A session switch before the queued task ran
        // therefore turned an old pin into the new conversation's pin. The envelope now carries the validated pair.
        val clock = FakeClock(1_000_000_000L)
        val environment = CxrHostEnvironment(
            clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
        )
        val dispatched = mutableListOf<() -> Unit>()
        val plugin = RecordingPlugin()
        val host = HostRuntime(1, environment)
        val root = HostCompositionRoot(
            host, MemoryProfileRepository(), UnusedModel(), environment,
            dispatchHostEvent = { task -> dispatched += task },
        )
        try {
            root.registerPlugin(plugin)
            root.onSceneReady()
            root.start("recording")
            val old = requireNotNull(host.currentIdentity())

            root.onPinFromLink(HintIdentity(old.first.value, old.second.value), AssistantRoute.PROACTIVE)
            assertEquals(1, dispatched.size)

            root.stop()
            root.start("recording")
            val current = requireNotNull(host.currentIdentity())
            assertNotEquals("the session must have moved on", old.first, current.first)

            dispatched.removeAt(0).invoke()
            Thread.sleep(50)
            assertTrue("a pin validated for the old session must not reach the new one: ${plugin.events}",
                plugin.events.filterIsInstance<PinChanged>().isEmpty())
        } finally { root.close() }
    }

    @Test
    fun aPanelShownIsDeliveredOffTheVendorCallbackThreadForTheTurnTimingOwner() {
        // Task 12 owns the 10 s timer and may only start it after the panel's first layout. Task 11 exposes that event
        // as a host-thread callback, not as a direct vendor-thread invocation, and carries the validated identity so a
        // queued event from a previous session cannot start the new session's timer.
        val clock = FakeClock(1_000_000_000L)
        val environment = CxrHostEnvironment(
            clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
        )
        val pending = mutableListOf<() -> Unit>()
        val host = HostRuntime(1, environment)
        val root = HostCompositionRoot(
            host, MemoryProfileRepository(), UnusedModel(), environment,
            dispatchHostEvent = { task -> pending += task },
        )
        try {
            root.registerPlugin(RecordingPlugin())
            root.onSceneReady()
            root.start("recording")
            val identity = requireNotNull(host.currentIdentity())
            val shown = mutableListOf<Pair<HintIdentity, AssistantRoute>>()
            root.onPanelShown = { validated, route -> shown += validated to route }

            root.panelShownArrived(HintIdentity(identity.first.value, identity.second.value), AssistantRoute.WAKE)

            assertTrue("the timing owner must not run on the vendor thread", shown.isEmpty())
            assertEquals(1, pending.size)
            pending.removeAt(0).invoke()
            assertEquals(listOf(HintIdentity(identity.first.value, identity.second.value) to AssistantRoute.WAKE), shown)
        } finally { root.close() }
    }

    @Test
    fun aQueuedPanelShownFromThePreviousSessionIsDropped() {
        val clock = FakeClock(1_000_000_000L)
        val environment = CxrHostEnvironment(
            clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
        )
        val pending = mutableListOf<() -> Unit>()
        val host = HostRuntime(1, environment)
        val root = HostCompositionRoot(
            host, MemoryProfileRepository(), UnusedModel(), environment,
            dispatchHostEvent = { task -> pending += task },
        )
        try {
            root.registerPlugin(RecordingPlugin())
            root.onSceneReady()
            root.start("recording")
            val old = requireNotNull(host.currentIdentity())
            root.panelShownArrived(HintIdentity(old.first.value, old.second.value), AssistantRoute.WAKE)
            assertEquals(1, pending.size)

            root.stop()
            root.start("recording")
            val shown = mutableListOf<Pair<HintIdentity, AssistantRoute>>()
            root.onPanelShown = { validated, route -> shown += validated to route }

            pending.removeAt(0).invoke()
            assertTrue("a queued PANEL_SHOWN from the previous session must be dropped: $shown", shown.isEmpty())
        } finally { root.close() }
    }

    @Test
    fun thePanelVisibilityIsReadableAndUiClosedReachesTheTurnOwner() {
        // Review I5: Task 12 can start its 10 s window on a turn open when the panel is already visible only if the
        // composition exposes that state, and it needs the close event to cancel a window whose panel went away.
        val clock = FakeClock(1_000_000_000L)
        val environment = CxrHostEnvironment(
            clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
        )
        val sent = mutableListOf<String>()
        val link = CxrHintLink(clock, ManualScheduler(clock), sendJson = { json -> sent += json; true })
        val pending = mutableListOf<() -> Unit>()
        val host = HostRuntime(1, environment)
        val root = HostCompositionRoot(
            host, MemoryProfileRepository(), UnusedModel(), environment,
            hintLink = link, dispatchHostEvent = { task -> pending += task },
        )
        try {
            root.registerPlugin(RecordingPlugin())
            root.onSceneReady()
            root.start("recording")
            val identity = requireNotNull(host.currentIdentity())
            val hintIdentity = HintIdentity(identity.first.value, identity.second.value)
            val closed = mutableListOf<AssistantRoute>()
            root.onUiClosed = { route -> closed += route }
            link.onUiState = { validated, state -> root.onUiStateFromLink(validated, state) }

            assertFalse("nothing is visible before PANEL_SHOWN", root.isPanelVisible())
            assertTrue(link.onInbound(HintWireCodec.encode(
                HintCommand.PanelShown(hintIdentity.sessionId, hintIdentity.generation, AssistantRoute.WAKE))))
            assertTrue("an already-visible panel must be readable on the turn-open path", root.isPanelVisible())

            assertTrue(link.onInbound(HintWireCodec.encode(
                HintCommand.UiClosed(hintIdentity.sessionId, hintIdentity.generation, AssistantRoute.WAKE))))
            pending.forEach { it() }
            assertEquals(listOf(AssistantRoute.WAKE), closed)
            assertFalse(root.isPanelVisible())
        } finally { root.close() }
    }
}
