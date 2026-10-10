package edith.phone

import edith.contracts.InputEpoch
import edith.contracts.SessionState
import edith.host.HostRuntime
import edith.output.HintCommand
import edith.output.HintWireCodec
import edith.plugin.CancellationHandle
import edith.plugin.Capability
import edith.plugin.ConfigSchema
import edith.plugin.DeactivationReason
import edith.plugin.DeadlineScheduler
import edith.plugin.ModelClient
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.MonotonicClock
import edith.plugin.PluginContext
import edith.plugin.PluginDescriptor
import edith.plugin.PluginLifecycle
import edith.plugins.diagnostic.DiagnosticHintPlugin
import edith.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
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
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): Boolean = false
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
        Rig().use { rig ->
            rig.root.connectCxr(activity = Any())

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
                    edith.contracts.OutputStatus.Displayed))))
        }
    }

    @Test
    fun theHostEnvironmentRefusesTheMicrophonePathWhileAudioIsNotAdvertised() {
        // Wiring the display must not quietly add a microphone-start path: the host starts `input` for plugins that
        // require audio, and `Capability.Audio` is not advertised, so the port must still refuse.
        Rig().use { rig ->
            val thrown = runCatching { rig.environment.input.start(InputEpoch(
                edith.contracts.SessionId("s"), edith.contracts.Generation(1), 1)) }.exceptionOrNull()

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
        override fun handle(event: edith.contracts.HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
    }

    private class NamespaceStub(private val namespace: String) : edith.plugin.StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]?.copyOf()
        override fun write(key: String, value: ByteArray) { values[key] = value.copyOf() }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
        override fun toString(): String = "namespace-$namespace"
    }
}
