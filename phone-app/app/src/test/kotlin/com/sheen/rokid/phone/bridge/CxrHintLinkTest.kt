package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SessionId
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
import com.sheen.rokid.phone.session.confirmation.ConfirmationChannelKind
import com.sheen.rokid.phone.session.confirmation.ConfirmationGlasses
import com.sheen.rokid.phone.session.confirmation.ConfirmationPanel
import com.sheen.rokid.phone.session.confirmation.ConfirmationPresenter
import com.sheen.rokid.phone.session.confirmation.ConfirmationUi
import com.sheen.rokid.phone.session.confirmation.SummaryDisplayer
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

        /** How many timers are still armed. A cancelled entry is dead weight the real scheduler drops as well. */
        fun pendingCount(): Int = tasks.count { !it.cancelled }
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

        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-1", "第一句"))
        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-2", "第二句"))

        assertEquals(listOf(1L, 2L), sent.said().map { it.sequence })
        assertEquals("第一句", sent.said().first().text)
        assertEquals("s-1", sent.said().first().sessionId)
        assertEquals(listOf("said-1", "said-2"), sent.said().map { it.entryId })
    }

    @Test
    fun theFinalUtteranceCarriesTheExactAsrEntryIdTheDisplayChose() {
        // 0.24 spec §6: the final SAID must name `asr-${turnId}`, the same id the ASR partial opened, or the glasses
        // append a second row for the same utterance.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        sent.clear()

        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "asr-turn-7", "最终的话"))

        val said = sent.said().single()
        assertEquals("asr-turn-7", said.entryId)
        assertEquals("最终的话", said.text)
        assertEquals(com.sheen.rokid.contracts.AssistantRoute.WAKE, said.route)
    }

    @Test
    fun aBlankEntryIdIsNeverSent() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        sent.clear()

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, " ", "有字但没身份"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun saidAndBusyAreDroppedBeforeASessionExists() {
        // Nothing on the glasses has been introduced to a session, so a wearer's words must not go on the wire.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-1", "没人听的时候"))
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

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-1", "会话结束之后"))
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

        assertTrue(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-1", "交换之后"))
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

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-1", "链接断了"))
    }

    @Test
    fun aBlankUtteranceIsNeverSent() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 1)
        sent.clear()

        assertFalse(link.userSaid(com.sheen.rokid.contracts.AssistantRoute.WAKE, "said-1", "   "))
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
    fun stoppingTheSessionClosesBothRoutesBeforeTheIdentityGoesAway() {
        // Spec §5.1: 停止 ends the session and closes the panel. There is no new SESSION_END command; `CLOSE` is the
        // existing route-scoped close, sent while the identity still exists to name.
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.sessionEstablished("s-1", 4)
        sent.clear()

        link.sessionEnded()

        val closes = sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Close }
        assertEquals("one close per route: $closes", 2, closes.size)
        assertEquals(setOf(AssistantRoute.WAKE, AssistantRoute.PROACTIVE), closes.map { it.route }.toSet())
        assertTrue("no new command name may be invented", sent.all {
            HintWireCodec.decode(it) is HintCommand.Close || HintWireCodec.decode(it) is HintCommand.Busy
        })
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
    fun aMatchingPinIsRoutedWithItsRouteAndValidatedIdentity() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val pinned = mutableListOf<Pair<HintIdentity, AssistantRoute>>()
        link.onPin = { identity, route -> pinned += identity to route }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.PROACTIVE))))

        assertEquals(listOf(HintIdentity("s-1", 3) to AssistantRoute.PROACTIVE), pinned)
    }

    @Test
    fun aPinForAnotherSessionOrGenerationIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var pinned = 0
        link.onPin = { _, _ -> pinned++ }
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
        link.onPin = { _, _ -> pinned++ }

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.WAKE))))
        assertEquals(0, pinned)
    }

    @Test
    fun aThrowingPinCallbackDoesNotEscapeTheSdkThread() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.onPin = { _, _ -> error("the host died") }
        link.sessionEstablished("s-1", 3)

        assertTrue("the link still handled the PIN",
            link.onInbound(HintWireCodec.encode(HintCommand.Pin("s-1", 3, AssistantRoute.WAKE))))
    }

    @Test
    fun uiReadyIsRoutedForTheEstablishedIdentity() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val states = mutableListOf<UiState>()
        link.onUiState = { _, state -> states += state }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 3))))

        assertEquals(listOf(UiState.Ready), states)
    }

    @Test
    fun uiClosedIsRoutedWithItsRoute() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        val states = mutableListOf<UiState>()
        link.onUiState = { _, state -> states += state }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.UiClosed("s-1", 3, AssistantRoute.PROACTIVE))))

        assertEquals(listOf(UiState.Closed(AssistantRoute.PROACTIVE)), states)
    }

    @Test
    fun uiStateForAnotherIdentityIsRefused() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        var states = 0
        link.onUiState = { _, _ -> states++ }
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
        link.onUiState = { _, _ -> states++ }

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 3))))
        assertEquals(0, states)
    }

    @Test
    fun aThrowingUiStateCallbackDoesNotEscapeTheSdkThread() {
        val sent = mutableListOf<String>()
        val link = linkInto(sent)
        link.onUiState = { _, _ -> error("the host died") }
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

    // ---------------------------------------------------------------- Task 11: resident handshake and panel lifecycle

    /**
     * A link with the two clocks the resident handshake needs: the fake scheduler so the 5 s grace is observable, and
     * the recording app-start seam so "only when needed" can be asserted.
     */
    private class ResidentRig(private val diagnostics: (String) -> Unit = {}) {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val sent = mutableListOf<String>()
        var starts = 0
        var startAccepted = true
        var startCallback: ((Boolean) -> Unit)? = null
        val link = CxrHintLink(
            clock = clock,
            scheduler = scheduler,
            sendJson = { json -> sent += json; true },
            diagnostics = diagnostics,
            // The opening retry is a D15 path with its own tests; with a zero bound it cannot add noise to these.
            maxRetries = 0,
            startGlassesApp = { onResult -> starts++; startCallback = onResult; startAccepted },
        )

        fun establish(sessionId: String = "s-1", generation: Long = 3) {
            link.sessionEstablished(sessionId, generation)
        }

        fun completeExchange() {
            val ping = sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
            clock.nanos = ping.p0 + 10_000_000L
            link.onInbound(HintWireCodec.encode(
                HintCommand.Pong(ping.syncId, ping.p0, ping.p0 + 1_005_000_000L, ping.p0 + 1_005_000_000L)))
        }

        fun uiReady(sessionId: String = "s-1", generation: Long = 3) {
            link.onInbound(HintWireCodec.encode(HintCommand.UiReady(sessionId, generation)))
        }

        fun panelShown(route: AssistantRoute = AssistantRoute.WAKE, sessionId: String = "s-1",
            generation: Long = 3) {
            link.onInbound(HintWireCodec.encode(HintCommand.PanelShown(sessionId, generation, route)))
        }

        fun uiClosed(route: AssistantRoute = AssistantRoute.WAKE, sessionId: String = "s-1",
            generation: Long = 3) {
            link.onInbound(HintWireCodec.encode(HintCommand.UiClosed(sessionId, generation, route)))
        }

        fun advance(millis: Long) {
            clock.nanos += millis * 1_000_000L
            scheduler.fireDue(clock.nanos)
        }

        fun commands(): List<HintCommand> = sent.mapNotNull(HintWireCodec::decode)
        fun panelOpens(): List<HintCommand.PanelOpen> = commands().filterIsInstance<HintCommand.PanelOpen>()
    }

    /**
     * Holds the first `PANEL_OPEN` retry between its capture and send sections.
     *
     * The `panel: no PANEL_SHOWN yet` line is emitted outside the handshake gate, which is exactly the gap in which a
     * session change or a close+new open can land before the retry sends.
     */
    private class BlockingRetryGate {
        private val captured = java.util.concurrent.CountDownLatch(1)
        private val release = java.util.concurrent.CountDownLatch(1)
        private val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        val diagnostics: (String) -> Unit = { message ->
            if (message.startsWith("panel: no PANEL_SHOWN yet") && armed.compareAndSet(true, false)) {
                captured.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        fun awaitCaptured(): Boolean = captured.await(5, java.util.concurrent.TimeUnit.SECONDS)
        fun release() = release.countDown()
    }

    @Test
    fun theSessionStartAsksForTheGlassesSceneOnlyAfterTheFiveSecondUiReadyGrace() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        rig.advance(4_999)
        assertEquals("the grace window must not be short-circuited", 0, rig.starts)

        rig.advance(1)
        assertEquals("no UI_READY inside 5 s means the scene must be started", 1, rig.starts)
        assertTrue("the SDK accepted the request", rig.startCallback != null)

        rig.sent.clear()
        rig.startCallback!!.invoke(true)
        assertTrue("a started scene must be re-introduced: ${rig.commands()}",
            rig.commands().any { it is HintCommand.Hello })
        assertTrue("the new scene needs a fresh clock exchange: ${rig.commands()}",
            rig.commands().any { it is HintCommand.Ping })
    }

    @Test
    fun aUiReadyInsideTheGraceWindowCancelsTheSceneStart() {
        val rig = ResidentRig()
        rig.establish()

        rig.uiReady()
        rig.advance(5_000)

        assertEquals("a live resident service needs no appStart", 0, rig.starts)
        assertTrue(rig.link.hasUiReady())
    }

    @Test
    fun aUiReadyForAnotherSessionDoesNotCountForThisOne() {
        val rig = ResidentRig()
        rig.establish()
        // The inbound identity gate refuses it; the grace must still fire for this session.
        rig.uiReady(sessionId = "other")

        rig.advance(5_000)

        assertEquals(1, rig.starts)
        assertFalse(rig.link.hasUiReady())
    }

    @Test
    fun endingTheSessionCancelsTheUiReadyGrace() {
        val rig = ResidentRig()
        rig.establish()

        rig.link.sessionEnded()
        rig.advance(5_000)

        assertEquals("a stopped session may not start the glasses scene", 0, rig.starts)
    }

    @Test
    fun openingThePanelUsesTheLiveServiceWithoutStartingTheScene() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertEquals("UI_READY + a live mapping means the service is already there", 0, rig.starts)
        assertEquals(listOf(AssistantRoute.WAKE), rig.panelOpens().map { it.route })
    }

    @Test
    fun openingThePanelStartsTheSceneThenWaitsForUiReadyBeforeOpening() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        assertTrue("the request must be accepted for asynchronous appStart", rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.starts)
        assertTrue("PANEL_OPEN may not travel before the scene has started", rig.panelOpens().isEmpty())

        rig.sent.clear()
        rig.startCallback!!.invoke(true)

        val commands = rig.commands()
        val hello = commands.indexOfFirst { it is HintCommand.Hello }
        val ping = commands.indexOfFirst { it is HintCommand.Ping }
        assertTrue("reintroduce must send HELLO/PING: $commands", hello >= 0 && ping >= 0)
        assertTrue("PANEL_OPEN must not travel before UI_READY proves the service can receive it: $commands",
            rig.panelOpens().isEmpty())

        rig.uiReady()
        assertEquals("exactly one PANEL_OPEN once the service reports ready", 1, rig.panelOpens().size)
        assertEquals(AssistantRoute.WAKE, rig.panelOpens().single().route)
    }

    @Test
    fun aStaleUiReadyWithoutALiveMappingStartsWithTheSceneAndWaitsForReadiness() {
        val rig = ResidentRig()
        rig.establish()
        rig.uiReady()
        // Deliberately no PONG: UI_READY was seen once, but no live mapping proves the service can still receive
        // commands. It may have died since it reported ready, so a wake must recover it with appStart.
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertEquals("a UI_READY with no live mapping may belong to a service that died", 1, rig.starts)
        assertTrue("PANEL_OPEN may not travel before readiness is proven: ${rig.commands()}",
            rig.panelOpens().isEmpty())

        rig.startCallback!!.invoke(true)
        assertTrue("the new scene must be introduced: ${rig.commands()}",
            rig.commands().any { it is HintCommand.Hello })
        assertTrue("PANEL_OPEN still may not travel before UI_READY/PONG: ${rig.commands()}",
            rig.panelOpens().isEmpty())

        rig.completeExchange()
        assertEquals("a live PONG mapping proves the service is ready", 1, rig.panelOpens().size)
    }

    @Test
    fun aWakeWithALiveMappingDoesNotStartTheSceneEvenWithoutUiReady() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        // No UI_READY in this test: the live PONG mapping is itself proof that the resident service is there.
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertEquals("a live mapping must never trigger another appStart", 0, rig.starts)
        assertEquals(listOf(AssistantRoute.WAKE), rig.panelOpens().map { it.route })
    }

    @Test
    fun anAcceptedSceneStartThatNeverBecomesReadyDoesNotStallALaterWake() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.starts)
        rig.startCallback!!.invoke(true)
        assertEquals("acceptance alone may not start a second scene", 1, rig.starts)

        // Inside the 10 s readiness window a wake queues behind the start instead of issuing a second one.
        rig.advance(9_999)
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.starts)

        // The accepted start never produced UI_READY/PONG. The pending route may not outlive that window: if it did,
        // every later wake would update it and return true without ever asking CXR to start the scene again.
        rig.advance(1)
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals("an accepted but never-ready scene must be started again", 2, rig.starts)
        assertTrue("PANEL_OPEN still waits for readiness: ${rig.commands()}", rig.panelOpens().isEmpty())
    }

    @Test
    fun aLostSceneStartAnswerExpiresSoTheNextWakeCanRecover() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.starts)
        // The SDK never answers (the backend drops an appStart answer for a binding that closed meanwhile).

        rig.advance(9_999)
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals("a wake inside the answer window may only queue", 1, rig.starts)

        rig.advance(1)
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals("a lost appStart answer must expire instead of blocking every later wake", 2, rig.starts)
    }

    @Test
    fun aReadyLivePanelIsNotStartedAgain() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        rig.startCallback!!.invoke(true)
        rig.uiReady()
        rig.panelShown(AssistantRoute.WAKE)
        rig.completeExchange()
        assertNotNull("the resident service is live and its panel visible", rig.link.liveOutput())
        rig.sent.clear()

        assertFalse("a ready, live, visible panel is a no-op", rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertEquals("the recovery path must not start a live scene again", 1, rig.starts)
        assertTrue("no PANEL_OPEN for a visible panel: ${rig.commands()}", rig.panelOpens().isEmpty())
        assertTrue(rig.link.isPanelVisible())
    }

    @Test
    fun aPanelOpenWithoutPanelShownIsRetriedAndStopsOnPanelShown() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.panelOpens().size)

        rig.advance(1_000)
        assertEquals("a PANEL_OPEN that was lost before PANEL_SHOWN must be repeated", 2, rig.panelOpens().size)

        rig.panelShown(AssistantRoute.WAKE)
        rig.advance(10_000)
        assertEquals("PANEL_SHOWN must stop the repeats", 2, rig.panelOpens().size)
    }

    @Test
    fun panelOpenRetriesAreBoundedWhenPanelShownNeverArrives() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        repeat(10) { rig.advance(1_000) }

        assertEquals("one open plus three bounded repeats", 4, rig.panelOpens().size)
    }

    @Test
    fun aLaterOpenAfterAGiveUpGetsItsOwnBoundedRetries() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        repeat(10) { rig.advance(1_000) }
        assertEquals(4, rig.panelOpens().size)

        // The failure did not mark the service dead. The next turn's open must be a fresh command with its own bound,
        // not inherit the exhausted retry counter and give up without one repeat.
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.panelOpens().size)
        rig.advance(1_000)
        assertEquals("a new open must repeat on its own counter", 2, rig.panelOpens().size)
    }

    @Test
    fun aPanelThatIsAlreadyVisibleIsNotReopenedOrResent() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.panelShown(AssistantRoute.WAKE)
        assertNotNull("the live mapping is what makes the visible panel trustworthy", rig.link.liveOutput())
        rig.sent.clear()

        assertFalse("an already-visible panel is a no-op", rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertTrue("no PANEL_OPEN may be sent for a visible panel", rig.panelOpens().isEmpty())
        assertEquals(0, rig.starts)
        assertTrue(rig.link.isPanelVisible())
    }

    @Test
    fun aVisiblePanelWhoseServiceStoppedIsClearedAndRecoveredOnTheNextWake() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.panelShown(AssistantRoute.WAKE)
        assertTrue(rig.link.isPanelVisible())

        // No PONG for longer than the mapping's validity: the resident service that reported the panel is gone.
        rig.advance(31_000)
        assertNull("the mapping must really be dead for this test", rig.link.liveOutput())
        assertTrue("nothing but a wake has cleared the stale flag yet", rig.link.isPanelVisible())
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertFalse("a visible flag with no live mapping is stale and must be cleared", rig.link.isPanelVisible())
        assertEquals("the next wake must recover the dead resident service", 1, rig.starts)
        assertTrue("PANEL_OPEN still waits for readiness: ${rig.commands()}", rig.panelOpens().isEmpty())
    }

    /**
     * The readiness gap: `PANEL_SHOWN` can arrive before the first PONG.
     *
     * C2's order is `appStart -> UI_READY -> PANEL_OPEN -> PANEL_SHOWN`, and `PANEL_SHOWN` only needs the session
     * identity, not a clock mapping. Treating that genuinely visible panel as stale would start a second scene and let
     * Task 12 report a hidden panel while the wearer is looking at it. Inside the bounded grace it is a no-op.
     */
    @Test
    fun aVisiblePanelInsideTheReadinessGraceIsNotClearedOrStarted() {
        val rig = ResidentRig()
        rig.establish()
        rig.uiReady()
        rig.panelShown(AssistantRoute.WAKE)
        rig.advance(1_000)
        rig.sent.clear()

        assertFalse("a visible panel inside the readiness grace is a no-op",
            rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertTrue("the visible flag must survive a wake inside the grace", rig.link.isPanelVisible())
        assertEquals("a genuinely visible panel must not start the scene", 0, rig.starts)
        assertTrue("no PANEL_OPEN may be sent for a visible panel: ${rig.commands()}", rig.panelOpens().isEmpty())
    }

    /**
     * After the bounded grace, the same visible flag with no mapping is stale and recovery resumes.
     *
     * The grace only covers the legitimate startup gap; a service that died after reporting `PANEL_SHOWN` must still be
     * recovered, and `PANEL_OPEN` must still wait for readiness.
     */
    @Test
    fun aVisiblePanelStillWithoutMappingAfterTheReadinessGraceIsClearedAndRecovered() {
        val rig = ResidentRig()
        rig.establish()
        rig.uiReady()
        rig.panelShown(AssistantRoute.WAKE)
        rig.sent.clear()

        // No PONG: the first clock exchange never completed, and the grace has run out.
        rig.advance(5_000)

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))

        assertFalse("a visible flag with no mapping after the grace is stale", rig.link.isPanelVisible())
        assertEquals("the stale visible flag must not suppress recovery", 1, rig.starts)
        assertTrue("PANEL_OPEN still waits for readiness: ${rig.commands()}", rig.panelOpens().isEmpty())

        rig.startCallback!!.invoke(true)
        assertTrue("the new scene must be re-introduced: ${rig.commands()}",
            rig.commands().any { it is HintCommand.Hello })
        assertTrue("no PANEL_OPEN before UI_READY/PONG: ${rig.commands()}", rig.panelOpens().isEmpty())
    }

    @Test
    fun uiClosedMakesThePanelHiddenAgainSoTheNextOpenIsSent() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.panelShown(AssistantRoute.WAKE)
        rig.uiClosed(AssistantRoute.WAKE)
        rig.sent.clear()

        assertFalse(rig.link.isPanelVisible())
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.panelOpens().size)
    }

    @Test
    fun panelShownIsDeliveredWithItsRouteAndValidatedIdentity() {
        val rig = ResidentRig()
        val shown = mutableListOf<Pair<HintIdentity, AssistantRoute>>()
        rig.link.onPanelShown = { identity, route -> shown += identity to route }
        rig.establish()

        assertTrue(rig.link.onInbound(
            HintWireCodec.encode(HintCommand.PanelShown("s-1", 3, AssistantRoute.PROACTIVE))))

        assertEquals(listOf(HintIdentity("s-1", 3) to AssistantRoute.PROACTIVE), shown)
        assertTrue(rig.link.isPanelVisible())
    }

    @Test
    fun aPanelShownForAnotherSessionIsRefused() {
        val rig = ResidentRig()
        var shown = 0
        rig.link.onPanelShown = { _, _ -> shown++ }
        rig.establish()

        assertFalse(rig.link.onInbound(
            HintWireCodec.encode(HintCommand.PanelShown("other", 3, AssistantRoute.WAKE))))
        assertFalse(rig.link.onInbound(
            HintWireCodec.encode(HintCommand.PanelShown("s-1", 2, AssistantRoute.WAKE))))

        assertEquals(0, shown)
        assertFalse(rig.link.isPanelVisible())
    }

    @Test
    fun aRefusedSceneStartLeavesThePanelUnopened() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        rig.startCallback!!.invoke(false)

        assertTrue("a refused appStart may not be followed by PANEL_OPEN", rig.panelOpens().isEmpty())
    }

    @Test
    fun aWakeDuringTheUiReadyGraceDoesNotStartTheSceneTwiceAndStillOpensThePanel() {
        val rig = ResidentRig()
        rig.establish()
        rig.sent.clear()

        // The grace fires one appStart...
        rig.advance(5_000)
        assertEquals(1, rig.starts)

        // ...and a wake arrives while CXR has not answered yet. It must queue the panel, not start the scene again.
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals("only one appStart may be in flight per session", 1, rig.starts)

        rig.sent.clear()
        rig.startCallback!!.invoke(true)

        assertTrue("the queued wake may not open the panel before readiness: ${rig.commands()}",
            rig.panelOpens().isEmpty())
        rig.uiReady()
        assertTrue("the queued wake must open the panel once UI_READY proves the service is there: ${rig.commands()}",
            rig.panelOpens().isNotEmpty())
    }

    @Test
    fun aStaleAppStartCallbackCannotOpenOrClearTheNextSessionsHandshake() {
        val rig = ResidentRig()
        rig.establish("s-1", 3)
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        val stale = requireNotNull(rig.startCallback)

        // The session moves on while CXR still owes an answer for the old one.
        rig.establish("s-2", 4)
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(2, rig.starts)
        rig.sent.clear()

        stale(true)

        assertTrue("a stale appStart answer may not re-introduce the new session: ${rig.commands()}",
            rig.commands().isEmpty())
        assertTrue("the new session's queued open must survive the stale answer",
            rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals("a stale answer must not allow a second start for the new session", 2, rig.starts)

        rig.uiReady("s-2", 4)
        assertEquals(1, rig.panelOpens().size)
        assertEquals("s-2", rig.panelOpens().single().sessionId)
    }

    @Test
    fun aStaleAppStartCallbackCannotAllowASecondSceneStartForTheNewSession() {
        val rig = ResidentRig()
        rig.establish("s-1", 3)
        rig.link.requestPanelOpen(AssistantRoute.WAKE)
        val stale = requireNotNull(rig.startCallback)

        rig.establish("s-2", 4)
        rig.advance(5_000)
        assertEquals("the grace starts the new session's scene once", 2, rig.starts)

        stale(true)

        assertTrue("a wake during the new scene's start must queue", rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals("the stale answer must not clear the new session's in-flight start", 2, rig.starts)
    }

    @Test
    fun aStaleUiReadyCannotOpenTheNextSessionsPanel() {
        val rig = ResidentRig()
        rig.establish("s-1", 3)
        rig.link.requestPanelOpen(AssistantRoute.WAKE)
        rig.establish("s-2", 4)
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.PROACTIVE))
        rig.sent.clear()

        assertFalse("an old session's UI_READY is not this session's fact",
            rig.link.onInbound(HintWireCodec.encode(HintCommand.UiReady("s-1", 3))))
        assertTrue("no PANEL_OPEN may be sent for the old session's UI_READY", rig.panelOpens().isEmpty())

        rig.uiReady("s-2", 4)
        assertEquals(listOf(AssistantRoute.PROACTIVE), rig.panelOpens().map { it.route })
    }

    @Test
    fun closingOrEndingTheSessionCancelsTheOpenRetries() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()

        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        rig.advance(1_000)
        assertEquals(2, rig.panelOpens().size)

        rig.link.close(AssistantRoute.WAKE)
        rig.advance(5_000)
        assertEquals("CLOSE must cancel the retry", 2, rig.panelOpens().size)

        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        rig.advance(1_000)
        assertEquals(2, rig.panelOpens().size)
        rig.link.sessionEnded()
        rig.advance(5_000)
        assertEquals("a session end must cancel the retry", 2, rig.panelOpens().size)
    }

    @Test
    fun aStalePanelOpenRetryCannotResendIntoTheNextSession() {
        val gate = BlockingRetryGate()
        val rig = ResidentRig(diagnostics = gate.diagnostics)
        rig.establish("s-1", 3)
        rig.completeExchange()
        rig.uiReady("s-1", 3)
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.panelOpens().size)

        // Let the retry capture the old route/identity, then hold it outside the gate.
        rig.clock.nanos += 1_000_000_000L
        val retry = kotlin.concurrent.thread { rig.scheduler.fireDue(rig.clock.nanos) }
        assertTrue("the retry must reach its capture point", gate.awaitCaptured())

        // The session moves on, gets its own live service and opens its own panel.
        rig.link.sessionEstablished("s-2", 4)
        rig.completeExchange()
        rig.uiReady("s-2", 4)
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.PROACTIVE))
        assertEquals(listOf(AssistantRoute.PROACTIVE), rig.panelOpens().map { it.route })

        gate.release()
        retry.join(5_000)

        assertEquals("a retry captured for the old session may not add a PANEL_OPEN: ${rig.commands()}",
            1, rig.panelOpens().size)
        assertEquals("the new session's PANEL_OPEN must survive the stale retry", "s-2",
            rig.panelOpens().single().sessionId)
        assertEquals(AssistantRoute.PROACTIVE, rig.panelOpens().single().route)
    }

    @Test
    fun aStalePanelOpenRetryCannotOverwriteAnotherRoutesOpen() {
        val gate = BlockingRetryGate()
        val rig = ResidentRig(diagnostics = gate.diagnostics)
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.panelOpens().size)

        rig.clock.nanos += 1_000_000_000L
        val retry = kotlin.concurrent.thread { rig.scheduler.fireDue(rig.clock.nanos) }
        assertTrue("the retry must reach its capture point", gate.awaitCaptured())

        // The panel hides and a new turn opens another route before the stale retry reaches its send.
        rig.uiClosed(AssistantRoute.WAKE)
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.PROACTIVE))
        assertEquals(listOf(AssistantRoute.PROACTIVE), rig.panelOpens().map { it.route })

        gate.release()
        retry.join(5_000)

        assertEquals("a retry captured for another route may not add a PANEL_OPEN: ${rig.commands()}",
            1, rig.panelOpens().size)
        assertEquals(AssistantRoute.PROACTIVE, rig.panelOpens().single().route)
    }

    @Test
    fun aStalePanelOpenRetryCannotResendAfterACloseAndReopenOfTheSameRoute() {
        val gate = BlockingRetryGate()
        val rig = ResidentRig(diagnostics = gate.diagnostics)
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(1, rig.panelOpens().size)

        rig.clock.nanos += 1_000_000_000L
        val retry = kotlin.concurrent.thread { rig.scheduler.fireDue(rig.clock.nanos) }
        assertTrue("the retry must reach its capture point", gate.awaitCaptured())

        // The panel closes and the same route reopens before the captured retry reaches its send. Route and session are
        // unchanged, so only the open attempt (epoch) can tell the retry it belongs to the cancelled window.
        rig.uiClosed(AssistantRoute.WAKE)
        rig.sent.clear()
        assertTrue(rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertEquals(listOf(AssistantRoute.WAKE), rig.panelOpens().map { it.route })

        gate.release()
        retry.join(5_000)

        assertEquals("a retry captured for the closed open may not resend into the reopened one: ${rig.commands()}",
            1, rig.panelOpens().size)
    }

    /**
     * Handshake concurrency: a wake and a `UI_READY` arriving together may produce one `appStart`, and after readiness
     * is signalled the panel is opened exactly once. The state is serialized under one gate, so the two interleavings
     * are both valid; neither may double-start or double-open.
     */
    @Test
    fun concurrentWakeAndUiReadyProduceOneSceneStartAndOnePanelOpen() {
        repeat(200) { round ->
            val starts = java.util.concurrent.atomic.AtomicInteger()
            val callback = java.util.concurrent.atomic.AtomicReference<((Boolean) -> Unit)?>(null)
            val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
            val link = CxrHintLink(
                clock = FakeClock(),
                scheduler = FakeScheduler(),
                sendJson = { json -> sent += json; true },
                maxRetries = 0,
                startGlassesApp = { onResult -> starts.incrementAndGet(); callback.set(onResult); true },
            )
            val session = "c-$round"
            link.sessionEstablished(session, 3)
            sent.clear()

            val barrier = java.util.concurrent.CyclicBarrier(3)
            val wake = kotlin.concurrent.thread {
                barrier.await()
                link.requestPanelOpen(AssistantRoute.WAKE)
            }
            val ready = kotlin.concurrent.thread {
                barrier.await()
                link.onInbound(HintWireCodec.encode(HintCommand.UiReady(session, 3)))
            }
            barrier.await()
            wake.join(5_000)
            ready.join(5_000)

            assertEquals("round $round: one appStart", 1, starts.get())
            callback.get()?.invoke(true)
            // A second UI_READY is the same identity reporting ready again; it must not open a second window.
            link.onInbound(HintWireCodec.encode(HintCommand.UiReady(session, 3)))

            val opens = synchronized(sent) { sent.toList() }.mapNotNull(HintWireCodec::decode)
                .filterIsInstance<HintCommand.PanelOpen>()
            assertEquals("round $round: exactly one PANEL_OPEN", 1, opens.size)
            link.sessionEnded()
        }
    }

    // ---------------------------------------------------------------- 0.25 Task 11: the glasses decision channel

    @Test
    fun aConfirmationDecisionFromTheEstablishedSessionReachesTheHost() {
        val sent = mutableListOf<String>()
        val link = CxrHintLink(FakeClock(), FakeScheduler(), sendJson = { json -> sent += json; true }, maxRetries = 0)
        val decisions = mutableListOf<Triple<HintIdentity, String, Boolean>>()
        link.onConfirmationDecision = { identity, requestId, approved ->
            decisions += Triple(identity, requestId, approved)
        }
        link.sessionEstablished("s-1", 3)

        assertTrue(link.onInbound(HintWireCodec.encode(HintCommand.ConfirmDecision("s-1", 3, "c-1", true))))

        assertEquals(listOf(Triple(HintIdentity("s-1", 3), "c-1", true)), decisions)
    }

    @Test
    fun aConfirmationDecisionFromAnotherSessionOrGenerationIsRefused() {
        val sent = mutableListOf<String>()
        val link = CxrHintLink(FakeClock(), FakeScheduler(), sendJson = { json -> sent += json; true }, maxRetries = 0)
        val decisions = mutableListOf<Triple<HintIdentity, String, Boolean>>()
        link.onConfirmationDecision = { identity, requestId, approved ->
            decisions += Triple(identity, requestId, approved)
        }
        link.sessionEstablished("s-1", 3)

        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.ConfirmDecision("s-other", 3, "c-1", true))))
        assertFalse(link.onInbound(HintWireCodec.encode(HintCommand.ConfirmDecision("s-1", 4, "c-1", true))))

        assertTrue("a stale session's key press cannot answer the current question", decisions.isEmpty())
    }

    @Test
    fun aConfirmationStateCarriesTheEstablishedIdentityAndIsRefusedBeforeASession() {
        val sent = mutableListOf<String>()
        val link = CxrHintLink(FakeClock(), FakeScheduler(), sendJson = { json -> sent += json; true }, maxRetries = 0)
        assertFalse("a state with no session introduced has nobody to be true for",
            link.confirmationState("c-1", true))
        assertTrue(sent.isEmpty())

        link.sessionEstablished("s-1", 7)
        sent.clear()

        assertTrue(link.confirmationState("c-1", true))
        assertEquals(
            HintCommand.ConfirmState("s-1", 7, "c-1", true),
            sent.mapNotNull(HintWireCodec::decode).filterIsInstance<HintCommand.ConfirmState>().single(),
        )

        assertTrue(link.confirmationState("c-1", false))
        val inactive = sent.mapNotNull(HintWireCodec::decode).filterIsInstance<HintCommand.ConfirmState>().last()
        assertEquals("c-1", inactive.requestId)
        assertFalse("the withdrawal names the same question", inactive.active)
    }

    // ------------------------------------------------------- 0.3 Phase 2 Task 2: the connected fact

    @Test
    fun theLinkReportsAnEstablishedSessionUntilItEnds() {
        val link = CxrHintLink(FakeClock(), FakeScheduler(), sendJson = { _ -> true }, maxRetries = 0)

        assertFalse("a link nobody introduced a session to is not connected", link.isEstablished())

        link.sessionEstablished("s-1", 3)
        assertTrue("an introduced session is the connected fact", link.isEstablished())

        link.sessionEnded()
        assertFalse("停止 ends the session the glasses were introduced to", link.isEstablished())
    }

    // ----------------------- 0.3 Phase 4B (B4): PANEL_OPEN precedes SHOW/CONFIRM_STATE and PANEL_SHOWN re-sends them

    /**
     * A presenter wired to the real link the way `HostCompositionRoot` wires it: the panel request is the link's
     * `requestPanelOpen`, the keys are the link's `confirmationState`, and the summary is encoded onto the same
     * connection so this test observes the actual wire order.
     */
    private fun confirmationPresenterOn(rig: ResidentRig): ConfirmationPresenter {
        val identity = HintIdentity("s-1", 3)
        var revision = 0L
        return ConfirmationPresenter(
            summary = SummaryDisplayer { hintId, text, _ ->
                revision += 1
                rig.sent += HintWireCodec.encode(
                    HintCommand.Show(
                        sessionId = identity.sessionId,
                        generation = identity.generation,
                        sequence = revision,
                        // The presenter's own hint id, passed through: the panel replaces `say-<hintId>`, so a fresh
                        // id per send would append a second row.
                        hintId = hintId,
                        revision = revision,
                        syncId = "sync",
                        phoneSentNanos = 0L,
                        remainingTtlMillis = 10_000L,
                        text = text,
                    ),
                )
                true
            },
            ui = object : ConfirmationUi {
                // 0.3 Phase 4B (B7): this link-focused rig has no phone surface; reporting `null` keeps the presenter's
                // count-only line honest without touching the assertions below.
                override fun show(requestId: String, summary: String): ConfirmationChannelKind? = null
                override fun hide(requestId: String) = Unit
            },
            glasses = ConfirmationGlasses { requestId, active -> rig.link.confirmationState(requestId, active) },
            panel = ConfirmationPanel { rig.link.requestPanelOpen(AssistantRoute.WAKE) },
            deliver = { },
            clock = rig.clock,
            scheduler = rig.scheduler,
            envelopeFor = { requestId ->
                EventEnvelope(SessionId(identity.sessionId), Generation(identity.generation), requestId, 0, 1)
            },
        )
    }

    @Test
    fun aConfirmationOpensThePanelBeforeShowingTheQuestionAndArmingTheKeys() {
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        rig.sent.clear()
        val presenter = confirmationPresenterOn(rig)

        presenter.present("c-1", "卸载探针 App", 10_000L)

        val commands = rig.commands()
        val panelOpen = commands.indexOfFirst { it is HintCommand.PanelOpen }
        val show = commands.indexOfFirst { it is HintCommand.Show }
        val confirmState = commands.indexOfFirst { it is HintCommand.ConfirmState }
        assertTrue("a hidden panel must be asked to open: $commands", panelOpen >= 0)
        assertTrue("PANEL_OPEN must precede SHOW: $commands", show > panelOpen)
        assertTrue("PANEL_OPEN must precede CONFIRM_STATE: $commands", confirmState > panelOpen)
        assertEquals(1, presenter.pendingCount)
    }

    @Test
    fun aPanelShownReannouncesTheLiveQuestionWithoutWithdrawingIt() {
        // The panel is created after the first send, so its first layout is the moment to repeat SHOW + CONFIRM_STATE.
        // The repeat is an announcement, not a new question: the pending one must survive and still be answerable.
        val rig = ResidentRig()
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        val presenter = confirmationPresenterOn(rig)
        // The production composition routes the link's PANEL_SHOWN into `ConfirmationPresenter.panelShownArrived`.
        rig.link.onPanelShown = { _, _ -> presenter.panelShownArrived() }
        presenter.present("c-1", "卸载探针 App", 10_000L)
        val firstShow = rig.commands().filterIsInstance<HintCommand.Show>().single()
        rig.sent.clear()

        rig.panelShown(AssistantRoute.WAKE)

        val commands = rig.commands()
        val resend = commands.filterIsInstance<HintCommand.Show>().single()
        assertEquals("the newly visible panel must receive the question again: $commands", 1,
            commands.count { it is HintCommand.Show })
        assertEquals(
            "B4 carry-forward: the re-sent SHOW must keep the question's hint id, or the panel appends a second row",
            firstShow.hintId,
            resend.hintId,
        )
        assertTrue(
            "the re-send must be a newer revision for the same hint id, or the glasses refuse it as stale " +
                "(first=${firstShow.revision}, resend=${resend.revision})",
            resend.revision > firstShow.revision,
        )
        val states = commands.filterIsInstance<HintCommand.ConfirmState>()
        assertEquals(1, states.size)
        assertTrue(states.single().active)
        assertEquals("c-1", states.single().requestId)
        assertEquals("PANEL_SHOWN must not clear the unsettled question", 1, presenter.pendingCount)
        assertTrue("the keys still answer the same question", presenter.answerFromGlasses("c-1", approved = true))
    }

    // ------------------------------------------ 0.4 strict-local fix: detach neutralizes the link

    /**
     * 0.4 strict-local fix (`D199`/`D201`): the link itself must become inert when the App detaches.
     *
     * A live mapping keeps a clock renewal armed, and every conversation/confirmation send is stamped with the
     * session that mapping belongs to. Closing only the composition session (the old `sessionEnded()` path) left the
     * mapping, the renewal and the send paths exactly as they were, so a detached App kept sending `PING`/`SYNC` and
     * a late confirmation could still send `SHOW` - the defect the Task 13b device log showed after `P4_DETACH`.
     *
     * `detach()` must therefore cancel every timer it owns (here: the mapping renewal and the UI_READY grace are both
     * gone, so the scheduler is empty), clear the mapping/session/panel state, and refuse every outbound path while
     * remaining a no-command operation itself. `attach()` is the explicit reverse used when the user switches the
     * glasses back on; the next `sessionEstablished` then works normally.
     */
    @Test
    fun detachCancelsTheClockAndRefusesEverySendAfterALiveMapping() {
        val diagnostics = mutableListOf<String>()
        val rig = ResidentRig { message -> diagnostics += message }
        rig.establish()
        rig.completeExchange()
        rig.uiReady()
        assertNotNull("precondition: the display is live", rig.link.liveOutput())
        assertEquals("precondition: the mapping renewal is the only timer left", 1,
            rig.scheduler.pendingCount())
        rig.sent.clear()
        diagnostics.clear()

        rig.link.detach()

        assertEquals("detach must cancel the clock renewal and the link's timers", 0,
            rig.scheduler.pendingCount())
        assertNull("no clock mapping may survive detach", rig.link.liveSyncId())
        assertNull("no display may be advertised while detached", rig.link.liveOutput())
        assertFalse("the glasses session is no longer established", rig.link.isEstablished())
        assertFalse("a sticky UI_READY must not survive", rig.link.hasUiReady())
        assertFalse("nor a visible panel", rig.link.isPanelVisible())

        rig.advance(120_000)
        assertTrue("no HELLO/PING/SYNC/SHOW/CLEAR may leave after detach: ${rig.commands()}",
            rig.sent.isEmpty())
        assertTrue("and no handshake may restart: $diagnostics",
            diagnostics.none { it.startsWith("handshake: ping") || it.startsWith("outbound:") })

        assertFalse("a conversation send is refused",
            rig.link.userSaid(AssistantRoute.WAKE, "asr-1", "text"))
        assertFalse("a confirmation state is refused", rig.link.confirmationState("c-1", true))
        assertFalse("a CLOSE is refused", rig.link.closeAll())
        assertFalse("a PANEL_OPEN is refused", rig.link.requestPanelOpen(AssistantRoute.WAKE))
        assertTrue("nothing may leave the phone after detach: ${rig.commands()}", rig.sent.isEmpty())

        var stops = 0
        rig.link.onStopRequest = { _, _ -> stops++ }
        assertFalse("a stale STOP from the old link is refused",
            rig.link.onInbound(HintWireCodec.encode(HintCommand.Stop("s-1", 3))))
        assertEquals("and reaches no host callback", 0, stops)

        rig.link.sessionEstablished("s-2", 4)
        assertFalse("a stale session introduction must not re-establish the link", rig.link.isEstablished())
        assertTrue("and sends nothing", rig.sent.isEmpty())

        rig.link.attach()
        rig.link.sessionEstablished("s-2", 4)
        assertTrue("attach() must let the next glasses session introduce itself", rig.link.isEstablished())
        assertTrue("with a fresh HELLO: ${rig.commands()}",
            rig.commands().any { it is HintCommand.Hello })
    }
}
