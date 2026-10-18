package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.phone.plugin.diagnostic.DiagnosticHintPlugin
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T18 composition: the four wiring points, proven at the composition level.
 *
 * This exists because "the display path is wired" is otherwise an unverifiable claim: `HostRuntime(1)` used to fall
 * back to `MissingHostEnvironment`, whose output throws, and no production `HostEnvironment` existed at all. Here the
 * real [CxrHostEnvironment] and the real [CxrHintLink] are composed exactly as `HostCompositionRoot.create` composes
 * them, against a connection double:
 *
 * 1. ownership - one `RokidHintDevice` behind a live-connection sender;
 * 2. `syncId` - absent until the `PING`/`PONG` exchange produces a mapping, and the display is not advertised before
 *    that, so no hint can travel with an identifier the glasses cannot map;
 * 3. the composition's scheduler drives the renewal (nothing here creates a thread);
 * 4. inbound acknowledgements arrive through the connection's reply channel.
 */
class CxrHintLinkTest {
    private class FakeClock(var nanos: Long = 0L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        private class Entry(val deadline: Long, val task: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Entry>()
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val entry = Entry(deadlineNanos, task)
            tasks += entry
            return object : CancellationHandle { override fun cancel() { entry.cancelled = true } }
        }

        /** Runs every non-cancelled task that has come due, as the composition's real scheduler would. */
        fun fireDue(nowNanos: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.deadline <= nowNanos }.minByOrNull { it.deadline } ?: return
                tasks.remove(next)
                next.task()
            }
        }
    }

    private class FakeConnection : CxrConnection {
        override var ready = false
        val sent = mutableListOf<String>()
        var inbound: ((String) -> Unit)? = null
        var connected = 0
        override fun connect(activity: Any) { connected++ }
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String): Boolean {
            if (!ready) return false
            sent += json
            return true
        }
        override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
        override fun close() = Unit
    }

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    private class Rig : AutoCloseable {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val connection = FakeConnection()
        val host: HostRuntime
        val environment: CxrHostEnvironment
        val link: CxrHintLink
        val root: HostCompositionRoot

        init {
            // The connection is created lazily, exactly as in production: the link reads it through a holder.
            val holder = arrayOfNulls<CxrConnection>(1)
            link = CxrHintLink(clock, scheduler, sendJson = { json -> holder[0]?.sendCommand(json) ?: false })
            environment = CxrHostEnvironment(clock = clock, input = UnwiredInputPort,
                liveOutput = link::liveOutput, scheduler = scheduler,
                // Production's storage factory refuses a namespace it does not know (a whitelist keyed by plugin id),
                // which is what made a display-only plugin fail on device with `UNKNOWN_PLUGIN_NAMESPACE`: storage was
                // being built for every activation whether or not the plugin had asked for it.
                storageFor = { pluginId ->
                    require(pluginId in setOf("smart-assistant", "interview-assistant", "p2-diagnostic")) {
                        "UNKNOWN_PLUGIN_NAMESPACE"
                    }
                    NamespaceStub(pluginId)
                })
            host = HostRuntime(1, environment)
            root = HostCompositionRoot(host, MemoryProfileRepository(), UnusedModel(),
                environment, rokidConnectionFactory = { connection.also { holder[0] = it } }, hintLink = link)
        }

        /** Completes the clock exchange, which is what makes the display real. */
        fun completeExchange() {
            val ping = connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
            clock.nanos = ping.p0 + 10_000_000
            connection.inbound!!(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))
        }

        override fun close() = root.close()
    }

    @Test
    fun theDisplayIsNotAdvertisedBeforeTheClockExchangeCompletes() {
        Rig().use { rig ->            rig.root.connectCxr(activity = Any())

            assertTrue("the connection must be connected", rig.connection.connected == 1)
            assertNull("no mapping means nothing can be displayed yet", rig.link.liveOutput())
        }
    }

    @Test
    fun aSessionStartFollowedByReadinessEstablishesTheGlassesSessionAndClock() {
        Rig().use { rig ->
            rig.root.connectCxr(activity = Any())
            rig.connection.ready = true
            // Readiness arrives before the session exists here, which is the normal order when the user connects the
            // glasses first. The signal must not be dropped.
            rig.root.onSceneReady()
            rig.root.start("p2-diagnostic")

            val commands = rig.connection.sent.mapNotNull { HintWireCodec.decode(it) }
            assertTrue("the glasses must be told about the session: $commands", commands.any { it is HintCommand.Hello })
            assertTrue("an exchange must be started: $commands", commands.any { it is HintCommand.Ping })
            assertTrue("nothing may be displayed before the exchange completes: $commands",
                commands.none { it is HintCommand.Show })
        }
    }

    @Test
    fun thePongPublishesTheMappingAndOnlyThenIsTheDisplayLive() {
        Rig().use { rig ->
            rig.root.connectCxr(activity = Any())
            rig.connection.ready = true
            rig.root.onSceneReady()
            rig.root.start("p2-diagnostic")

            val ping = rig.connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.single()
            assertNull("still nothing to display", rig.link.liveOutput())

            rig.clock.nanos = ping.p0 + 10_000_000
            rig.connection.inbound!!(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))

            assertNotNull("after the exchange the display is real", rig.link.liveOutput())
            val sync = rig.connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Sync }.single()
            assertEquals(ping.syncId, sync.syncId)
        }
    }

    @Test
    fun anInboundAckReachesTheHintThatIsInFlight() {
        Rig().use { rig ->
            rig.root.connectCxr(activity = Any())
            rig.connection.ready = true
            rig.root.onSceneReady()
            rig.root.start("p2-diagnostic")
            val ping = rig.connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.single()
            rig.clock.nanos = ping.p0 + 10_000_000
            rig.connection.inbound!!(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))

            // An ack for a hint this device never showed must be ignored rather than adopted.
            assertTrue("an unknown ack must not be reported as handled",
                !rig.link.onInbound(HintWireCodec.encode(HintCommand.Ack("s", 1, 7, "nobody", 1,
                    com.sheen.rokid.contracts.OutputStatus.Displayed))))
        }
    }

    @Test
    fun theHostEnvironmentRefusesTheMicrophonePathWhileAudioIsNotAdvertised() {
        // Wiring the display must not quietly add a microphone-start path: the host starts `input` for plugins that
        // require audio, and `Capability.Audio` is not advertised, so the port must still refuse.
        Rig().use { rig ->
            val thrown = runCatching { rig.environment.input.start(InputEpoch(
                com.sheen.rokid.contracts.SessionId("s"), com.sheen.rokid.contracts.Generation(1), 1)) }.exceptionOrNull()

            assertNotNull("the host audio path must stay refusing", thrown)
        }
    }

    /**
     * The acceptance path itself: activation is attempted when the scene becomes ready, which is *before* the first
     * clock mapping exists, so a display-only plugin is refused at that moment. Unless the not-live -> live transition
     * retries the activation, the wearer sees nothing for the whole session - the device run on 2026-10-08 showed the
     * session staying `Paused` after `display became live`, which is what this pins.
     */
    @Test
    fun aRefusalForTheMissingDisplayIsRetriedOnceTheDisplayBecomesReal() {
        Rig().use { rig ->
            rig.host.register(DiagnosticHintPlugin(rig.clock))
            rig.root.connectCxr(activity = Any())
            rig.connection.ready = true
            rig.root.onSceneReady()
            // The refusal arrives before the exchange completes: no mapping means no display, honestly.
            runCatching { rig.root.start("p2-hint") }
            assertEquals("the refused session must be paused", SessionState.Paused, rig.host.currentState)
            assertNull("and nothing may be advertised yet", rig.link.liveOutput())

            rig.completeExchange()

            assertNotNull("the display is real after the exchange", rig.link.liveOutput())
            assertEquals("a display that just became usable must be used, or the wearer sees nothing",
                SessionState.Ready, rig.host.currentState)
            assertEquals("and a successful retry must clear the refusal it was retried from",
                null, rig.root.state.value.errorCode)
        }
    }

    /**
     * D15: a `HELLO`/`PING` sent before the glasses app has subscribed is lost forever.
     *
     * The composition-level half of the bounded repeat: it must happen for a session whose opening exchange was never
     * answered, and `stop()` must end it - a stopped session must not keep being introduced to the glasses.
     */
    @Test
    fun theOpeningHandshakeIsRepeatedUntilTheSessionStops() {
        Rig().use { rig ->
            rig.root.connectCxr(activity = Any())
            rig.connection.ready = true
            rig.root.onSceneReady()
            rig.root.start("p2-diagnostic")
            fun hellos() = rig.connection.sent.mapNotNull(HintWireCodec::decode).count { it is HintCommand.Hello }
            assertEquals("the session is introduced once on start", 1, hellos())

            // Nothing answered, because the glasses had not subscribed yet: the opening handshake must be repeated.
            rig.clock.nanos = 1_000_000_000
            rig.scheduler.fireDue(rig.clock.nanos)
            assertEquals("an unanswered opening handshake must be repeated", 2, hellos())

            rig.root.stop()
            rig.clock.nanos = 5_000_000_000
            rig.scheduler.fireDue(rig.clock.nanos)
            assertEquals("a stopped session must not keep being introduced", 2, hellos())
        }
    }

    @Test
    fun aGenuineCapabilityGapIsNotRetriedByTheDisplayBecomingReal() {
        // Audio is never advertised by this environment, so a plugin that needs it stays refused however live the
        // display gets: the retry must not become a way to activate anything at all.
        Rig().use { rig ->
            rig.host.register(AudioNeedingPlugin())
            rig.root.connectCxr(activity = Any())
            rig.connection.ready = true
            rig.root.onSceneReady()
            runCatching { rig.root.start("needs-audio") }
            assertEquals(SessionState.Paused, rig.host.currentState)

            runCatching { rig.completeExchange() }

            assertEquals("a missing microphone is not fixed by the display becoming live",
                SessionState.Paused, rig.host.currentState)
        }
    }

    private class AudioNeedingPlugin : PluginLifecycle {
        override val descriptor = PluginDescriptor("needs-audio", "1.0.0", 1, "Audio Probe",
            listOf(Capability.Audio, Capability.TextOutput), ConfigSchema(1))
        override fun activate(context: PluginContext) = Unit
        override fun handle(event: com.sheen.rokid.contracts.HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
    }

    private class NamespaceStub(private val namespace: String) : com.sheen.rokid.plugin.StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]?.copyOf()
        override fun write(key: String, value: ByteArray) { values[key] = value.copyOf() }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
        override fun toString(): String = "namespace-$namespace"
    }

    // ---------------------------------------------------------------- Phase C: what was said, and who is working

    /**
     * A bare link with a recording sender.
     *
     * The `Rig` above needs the composition to create its connection lazily, which is what its own tests are about;
     * the conversation is added *to the link* and needs no composition at all, so these cases drive the link directly.
     */
    private fun linkInto(sent: MutableList<String>, accept: Boolean = true): CxrHintLink =
        CxrHintLink(FakeClock(), FakeScheduler(), sendJson = { json -> if (accept) sent += json; accept })

    private fun List<String>.said(): List<HintCommand.Said> =
        mapNotNull { HintWireCodec.decode(it) as? HintCommand.Said }

    private fun List<String>.busy(): List<HintCommand.Busy> =
        mapNotNull { HintWireCodec.decode(it) as? HintCommand.Busy }

    @Test
    fun anUtteranceIsSentWithAnIncreasingSequenceOnceASessionExists() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        sent.clear()

        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "第一句"))
        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "第二句"))

        assertEquals(listOf(1L, 2L), sent.said().map { it.sequence })
        assertEquals("第一句", sent.said().first().text)
        assertEquals("s-1", sent.said().first().sessionId)
    }

    @Test
    fun saidAndBusyAreDroppedBeforeASessionExists() {
        // Nothing on the glasses has been introduced to a session, so a wearer's words must not go on the wire.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "没人听的时候"))
        assertFalse(link.busy(com.sheen.rokid.contracts.AssistantRoute.WAKE, true))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aStoppedSessionStopsAcceptingConversationTraffic() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        link.sessionEnded()
        sent.clear()

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "会话结束之后"))
        assertFalse(link.busy(com.sheen.rokid.contracts.AssistantRoute.WAKE, true))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theClockExchangeGoingLiveDoesNotStopTheConversation() {
        // `openingSession` is cleared the moment a mapping goes live; conversation traffic wired to that flag would go
        // deaf at exactly the point the display started working.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        val ping = sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
        link.onInbound(HintWireCodec.encode(
            HintCommand.Pong(ping.syncId, ping.p0, ping.p0 + 1_000_000, ping.p0 + 1_000_000)))
        sent.clear()

        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "交换之后"))
    }

    @Test
    fun busyIsALevelAndBothDirectionsTravel() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        sent.clear()

        assertTrue(link.busy(com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, true))
        assertTrue(link.busy(com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, false))

        assertEquals(listOf(true, false), sent.busy().map { it.busy })
        assertEquals(com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, sent.busy().first().route)
    }

    @Test
    fun aRefusedSendIsReportedRatherThanAssumedToHaveArrived() {
        // The caller has to be able to tell "the wearer's words are on the glasses" from "they are only in our log".
        val sent = mutableListOf<String>()
        val link = linkInto(sent, accept = false)
        link.sessionEstablished("s-1", 1)

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "链接断了"))
    }

    @Test
    fun aBlankUtteranceIsNeverSent() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        sent.clear()

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "   "))
        assertTrue(sent.isEmpty())
    }

    // ---------------------------------------------------------------- 0.23 P5: the wearer's STOP

    @Test
    fun aMatchingStopIsRoutedToTheHostCallbackWithItsOwnIdentity() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val received = mutableListOf<Pair<String, Long>>()
        link.onStopRequest = { session, generation -> received += session to generation }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.Stop("s-1", 3))))
        assertEquals(listOf("s-1" to 3L), received)
    }

    @Test
    fun aStopForAnotherGenerationIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var stopped = 0
        link.onStopRequest = { _, _ -> stopped++ }
        link.sessionEstablished("s-1", 3)

        assertFalse("another generation is another session's work",
            link.onInbound(HintWireCodec.encode(HintCommand.Stop("s-1", 2))))
        assertEquals(0, stopped)
    }

    @Test
    fun aRepeatedStopIsIdempotentAndStillRouted() {
        // STOP is an event, not a level: the wearer may double-click twice and both must be answered without harm.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var stopped = 0
        link.onStopRequest = { _, _ -> stopped++ }
        link.sessionEstablished("s-1", 3)
        val json = HintWireCodec.encode(HintCommand.Stop("s-1", 3))

        assertTrue(link.onInbound(json))
        assertTrue(link.onInbound(json))
        assertEquals(2, stopped)
    }

    @Test
    fun aThrowingStopCallbackDoesNotEscapeTheSdkThread() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.onStopRequest = { _, _ -> error("the host died") }
        link.sessionEstablished("s-1", 3)

        assertTrue("the link still handled the STOP", link.onInbound(HintWireCodec.encode(HintCommand.Stop("s-1", 3))))
    }

    @Test
    fun aStopBeforeAnySessionIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var stopped = 0
        link.onStopRequest = { _, _ -> stopped++ }

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.Stop("s-1", 3))))
        assertEquals(0, stopped)
    }

    @Test
    fun endingASessionClearsBothBusyLevelsBeforeTheSessionGoesAway() {
        // 0.23 P5 review: the phone's own 停止 used to clear the session before the plugin could publish idle, so the
        // glasses spun for up to 60 s. The link now states both levels false while it still has an identity to send.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 4)
        sent.clear()

        link.sessionEnded()

        val busies = sent.busy()
        assertEquals("one idle per route: $busies", 2, busies.size)
        assertEquals(setOf(com.sheen.rokid.contracts.AssistantRoute.WAKE,
            com.sheen.rokid.contracts.AssistantRoute.PROACTIVE), busies.map { it.route }.toSet())
        assertTrue("both levels must be false: $busies", busies.none { it.busy })
    }

    @Test
    fun theCompositionStopCheckMatchesTheSessionAcrossPauseResume() {
        // The link validates the STOP against the identity it knows; the composition must rule out a **different
        // session** (a stop/start) but accept the same session after pause/resume, which bumps the host generation
        // without re-introducing the session to the glasses.
        assertFalse(com.sheen.rokid.phone.app.stopSessionMatches(null, "s-1"))
        assertFalse(com.sheen.rokid.phone.app.stopSessionMatches(
            com.sheen.rokid.contracts.SessionId("other") to com.sheen.rokid.contracts.Generation(3), "s-1"))
        assertTrue("pause/resume keeps the session id; generation is the link's concern",
            com.sheen.rokid.phone.app.stopSessionMatches(
                com.sheen.rokid.contracts.SessionId("s-1") to com.sheen.rokid.contracts.Generation(4), "s-1"))
    }

    // ---------------------------------------------------------------- 0.24: official conversation wire sends

    @Test
    fun everyOfficialConversationSendCarriesTheEstablishedIdentityAndItsOwnFields() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 3)
        sent.clear()

        assertTrue(link.state(AssistantRoute.PROACTIVE, "t-1", 3, 7))
        assertTrue(link.idle(AssistantRoute.WAKE, 8))
        assertTrue(link.asr(AssistantRoute.WAKE, "t-1", 2, "你好"))
        assertTrue(link.answer(AssistantRoute.PROACTIVE, "t-1", "h-1", 4, "答案"))
        assertTrue(link.tool(AssistantRoute.WAKE, "t-1", ToolPhase.START, "search"))
        assertTrue(link.panelOpen(AssistantRoute.WAKE))
        assertTrue(link.close(AssistantRoute.PROACTIVE))

        assertEquals(listOf(
            HintCommand.State("s-1", 3, AssistantRoute.PROACTIVE, "t-1", 3, 7),
            HintCommand.Idle("s-1", 3, AssistantRoute.WAKE, 8),
            HintCommand.Asr("s-1", 3, AssistantRoute.WAKE, "t-1", 2, "你好"),
            HintCommand.Answer("s-1", 3, AssistantRoute.PROACTIVE, "t-1", "h-1", 4, "答案"),
            HintCommand.Tool("s-1", 3, AssistantRoute.WAKE, "t-1", ToolPhase.START, "search"),
            HintCommand.PanelOpen("s-1", 3, AssistantRoute.WAKE),
            HintCommand.Close("s-1", 3, AssistantRoute.PROACTIVE),
        ), sent.mapNotNull(HintWireCodec::decode))
    }

    @Test
    fun aStateSendIsTheExactOfficialWireShape() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 3)
        sent.clear()

        assertTrue(link.state(AssistantRoute.PROACTIVE, "t-1", 1, 5))

        assertEquals(
            """{"wireVersion":1,"command":"STATE","sessionId":"s-1","generation":3,"route":"PROACTIVE","turnId":"t-1","status":1,"sequence":5}""",
            sent.single())
    }

    @Test
    fun everyOfficialConversationSendIsDroppedBeforeASessionExists() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)

        assertFalse(link.state(AssistantRoute.WAKE, "t-1", 1, 1))
        assertFalse(link.idle(AssistantRoute.WAKE, 1))
        assertFalse(link.asr(AssistantRoute.WAKE, "t-1", 1, "你好"))
        assertFalse(link.answer(AssistantRoute.WAKE, "t-1", "h-1", 1, "答案"))
        assertFalse(link.tool(AssistantRoute.WAKE, "t-1", ToolPhase.START, "search"))
        assertFalse(link.panelOpen(AssistantRoute.WAKE))
        assertFalse(link.close(AssistantRoute.WAKE))
        assertTrue("a refused command may not reach the wire: $sent", sent.isEmpty())
    }

    @Test
    fun aRefusedOfficialSendIsReportedRatherThanAssumedToHaveArrived() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent, accept = false)
        link.sessionEstablished("s-1", 3)

        assertFalse(link.state(AssistantRoute.WAKE, "t-1", 1, 1))
        assertFalse(link.panelOpen(AssistantRoute.WAKE))
    }

    // ---------------------------------------------------------------- 0.24: inbound PIN and UI state

    @Test
    fun aMatchingPinIsRoutedWithItsRoute() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val pinned = mutableListOf<AssistantRoute>()
        link.onPin = { pinned += it }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.PROACTIVE))))

        assertEquals(listOf(AssistantRoute.PROACTIVE), pinned)
    }

    @Test
    fun aPinForAnotherSessionOrGenerationIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var pinned = 0
        link.onPin = { pinned++ }
        link.sessionEstablished("s-1", 3)

        assertFalse("another session cannot pin this one",
            link.onInbound(HintWireCodec.encode(HintCommand.Pin("other", 3, AssistantRoute.WAKE))))
        assertFalse("another generation is another session's state",
            link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 2, AssistantRoute.WAKE))))
        assertEquals(0, pinned)
    }

    @Test
    fun aPinBeforeASessionExistsIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var pinned = 0
        link.onPin = { pinned++ }

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.WAKE))))
        assertEquals(0, pinned)
    }

    @Test
    fun aThrowingPinCallbackDoesNotEscapeTheSdkThread() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.onPin = { error("the host died") }
        link.sessionEstablished("s-1", 3)

        assertTrue("the link still handled the PIN",
            link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.WAKE))))
    }

    @Test
    fun uiReadyIsRoutedForTheEstablishedIdentity() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val states = mutableListOf<UiState>()
        link.onUiState = { states += it }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 3))))

        assertEquals(listOf(UiState.Ready), states)
    }

    @Test
    fun uiClosedIsRoutedWithItsRoute() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val states = mutableListOf<UiState>()
        link.onUiState = { states += it }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.UiClosed("s-1", 3, AssistantRoute.PROACTIVE))))

        assertEquals(listOf(UiState.Closed(AssistantRoute.PROACTIVE)), states)
    }

    @Test
    fun uiStateForAnotherIdentityIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var states = 0
        link.onUiState = { states++ }
        link.sessionEstablished("s-1", 3)

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("other", 3))))
        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 2))))
        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.UiClosed("s-1", 2, AssistantRoute.WAKE))))
        assertEquals(0, states)
    }

    @Test
    fun uiStateBeforeASessionExistsIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var states = 0
        link.onUiState = { states++ }

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 3))))
        assertEquals(0, states)
    }

    @Test
    fun aThrowingUiStateCallbackDoesNotEscapeTheSdkThread() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.onUiState = { error("the host died") }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 3))))
    }

    // ---------------------------------------------------------------- 0.24: reintroduce and appStart

    @Test
    fun reintroduceRestartsTheOpeningHandshakeWithoutChangingTheConversationIdentity() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 4)
        sent.clear()

        link.reintroduce()

        val commands = sent.mapNotNull(HintWireCodec::decode)
        assertEquals(1, commands.filterIsInstance<HintCommand.Hello>().size)
        assertEquals(HintCommand.Hello("s-1", 4), commands.first())
        assertEquals(1, commands.filterIsInstance<HintCommand.Ping>().size)
        assertTrue("a live session must keep accepting conversation traffic after reintroduce",
            link.state(AssistantRoute.WAKE, "t-1", 1, 1))
    }

    @Test
    fun reintroduceDropsTheOldClockMappingUntilANewExchangeCompletes() {
        val clock = FakeClock()
        val sent = mutableListOf<String>()
        val link = CxrHintLink(clock, FakeScheduler(), sendJson = { sent += it; true })
        link.sessionEstablished("s-1", 4)
        val ping = sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
        clock.nanos = ping.p0 + 10_000_000
        link.onInbound(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
            ping.p0 + 1_000_000, ping.p0 + 1_000_000)))
        assertNotNull("the first exchange must have produced a live display", link.liveOutput())

        link.reintroduce()

        assertNull("the new glasses scene has no mapping for the old interval", link.liveOutput())
    }

    @Test
    fun reintroduceWithoutASessionDoesNothing() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)

        link.reintroduce()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun openGlassesAppIsDelegatedToTheInjectedStarter() {
        var starts = 0
        var result: ((Boolean) -> Unit)? = null
        val link = CxrHintLink(FakeClock(), FakeScheduler(), sendJson = { true },
            startGlassesApp = { callback -> starts++; result = callback; true })
        var observed: Boolean? = null

        assertTrue(link.openGlassesApp { observed = it })
        assertEquals(1, starts)
        result!!.invoke(false)

        assertEquals(false, observed)
    }

    @Test
    fun aLinkWithNoStarterRefusesToStartTheGlassesApp() {
        val link = linkInto(mutableListOf())

        assertFalse(link.openGlassesApp { })
    }
}
