package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.withP3Sink
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ConfiguredPlugin
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.WakeWordRegistry
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10: an activation refused for the missing display must be retried when the clock mapping becomes real even
 * when the link re-establishes it **in place**, without a not-live -> live transition.
 *
 * Measured on the K40 on 2026-10-09: the link was already live and merely re-synced with a fresh `syncId` every
 * ~20 s (`mapping live` at 09:51:31, :34, :59, 09:52:19, 09:52:59 - no transition). The retry was reachable only
 * from the transition, so `awaitingDisplayForActivation` stayed true for the whole session, the plugin was never
 * activated, `armAudioEpoch()` never ran and the ears were never armed while PCM was flowing the whole time.
 *
 * The rig is the real [CxrHintLink] over the real [CxrHostEnvironment] - the same composition the App builds - with
 * a connection double and a manual deadline scheduler, so "re-established without a transition" is the link's own
 * steady state rather than something this file simulates.
 */
class DisplayRetryTest {

    // ---- doubles -------------------------------------------------------------------------------------------------

    /** The `Capability.WakeWord` provider the environment advertises for a wake-word plugin. */
    private class StubWakeWords : WakeWordRegistry {
        private var words: List<String> = emptyList()
        override fun declare(words: List<String>): Boolean { this.words = words; return true }
        override fun current(): List<String> = words
    }

    /**
     * A connection double that can carry a real transport port, so the composition's arming is observable
     * (the epoch is started on both the poll loop and the transport's own port).
     */
    private class HintConnection(private val port: InputPort?) : CxrConnection {
        override var ready = false
        val sent = mutableListOf<String>()
        private var inbound: ((String) -> Unit)? = null
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String): Boolean {
            if (!ready) return false
            sent += json
            return true
        }
        override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
        override fun close() = Unit
        override fun inputPortOrNull(): InputPort? = port

        /** Offers one message on the reply channel, exactly as the transport's inbound routing does. */
        fun answer(json: String) { requireNotNull(inbound).invoke(json) }
    }

    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    /** A plugin that counts its activations, so "activated exactly once" is countable rather than inferred. */
    private open class CountingPlugin(id: String, capabilities: List<Capability>) : PluginLifecycle {
        val activations = AtomicInteger()
        override val descriptor =
            PluginDescriptor(id, "1.0.0", 1, "D10 Probe", capabilities, ConfigSchema(1))
        override fun activate(context: PluginContext) { activations.incrementAndGet() }
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) = Unit
    }

    /**
     * A configured plugin whose `configure` calls are the composition's activation attempts.
     *
     * `HostCompositionRoot.activateSelected` configures the selected plugin once per attempt before it asks the
     * host to activate, and it builds a configuration only for an id it knows - so registering this under the
     * diagnostic id makes each attempt countable even when every attempt is refused.
     */
    private class AttemptCountingPlugin(id: String, capabilities: List<Capability>) :
        PluginLifecycle, ConfiguredPlugin {
        val attempts = AtomicInteger()
        val activations = AtomicInteger()
        override val descriptor =
            PluginDescriptor(id, "1.0.0", 1, "D10 Attempts", capabilities, ConfigSchema(1))
        override fun validate(configuration: PluginConfiguration) = Unit
        override fun configure(configuration: PluginConfiguration) { attempts.incrementAndGet() }
        override fun activate(context: PluginContext) { activations.incrementAndGet() }
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) = Unit
    }

    // ---- the composition the App builds --------------------------------------------------------------------------

    private class Rig(plugin: PluginLifecycle, wakeWordProvider: Boolean) : AutoCloseable {
        private val pluginId = plugin.descriptor.pluginId
        val clock = FakeClock(0)
        val deadlines = ManualScheduler(clock)
        val loopPort = RecordingInputPort()
        val transportPort = RecordingInputPort()
        val connection = HintConnection(transportPort)
        private val link: CxrHintLink
        private val environment: CxrHostEnvironment
        val host: HostRuntime
        val root: HostCompositionRoot

        init {
            // The link reads the live connection through a holder, exactly as the production assembly does: the
            // connection is built lazily on the first `connectCxr`.
            val holder = arrayOfNulls<CxrConnection>(1)
            link = CxrHintLink(clock, deadlines, sendJson = { json -> holder[0]?.sendCommand(json) ?: false })
            environment = CxrHostEnvironment(clock = clock, input = loopPort,
                liveOutput = link::liveOutput, scheduler = deadlines,
                providers = if (wakeWordProvider) mapOf(Capability.WakeWord to StubWakeWords()) else emptyMap())
            host = HostRuntime(1, environment)
            root = HostCompositionRoot(host, MemoryProfileRepository(), InertModel(), environment,
                rokidConnectionFactory = { connection.also { holder[0] = it } }, hintLink = link)
            root.registerPlugin(plugin)
        }

        /** 开始 then 连接眼镜, the device order: the session exists before the transport reports ready. */
        fun startThenConnect() {
            root.start(pluginId)
            root.connectCxr(activity = Any())
            connection.ready = true
            root.onSceneReady()
        }

        /** True while a hint could be displayed, i.e. the clock mapping is live. */
        fun displayIsLive(): Boolean = link.liveOutput() != null

        /**
         * Answers the outstanding `PING`, which is what makes a clock mapping live.
         *
         * The exchange is the one `HintClockSync` accepts: `p3` is read from the same clock the composition uses,
         * and the offset interval is narrow enough for `ClockMapping.fromExchange`.
         */
        fun answerExchange() {
            val ping = connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
            clock.nowNanos = ping.p0 + 10_000_000
            connection.answer(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))
        }

        /**
         * The link's own renewal deadline, then its answer: a re-sync that replaces a live mapping with another
         * live mapping **in place**, which is the ~20 s steady state the device showed.
         */
        fun resyncInPlace() {
            clock.advance(60_000_000_000L)
            deadlines.runDue()
            answerExchange()
        }

        override fun close() = root.close()
    }

    // ---- (a) the refusal is retried when the mapping is re-established without a transition ----------------------

    @Test
    fun aRefusedActivationIsRetriedWhenTheMappingIsReEstablishedWithoutATransition() {
        val plugin = CountingPlugin("d10-wake", listOf(Capability.TextOutput, Capability.WakeWord))
        Rig(plugin, wakeWordProvider = true).use { rig ->
            // The first session activates once the display becomes real: the pre-existing transition path.
            rig.startThenConnect()
            assertEquals("the activation must be refused before any mapping exists",
                SessionState.Paused, rig.host.currentState)
            rig.answerExchange()
            assertEquals(SessionState.Ready, rig.host.currentState)
            assertEquals(1, plugin.activations.get())

            // The link stays live across the stop, so the second session's exchange re-establishes the mapping
            // **in place**: the display never goes not-live, so the transition callback cannot fire. Without the
            // every-sync signal nothing would retry and the plugin would stay refused for the whole session.
            withP3Sink { lines ->
                rig.root.stop()
                rig.loopPort.started.clear()
                rig.transportPort.started.clear()
                rig.startThenConnect()
                assertEquals("the second session must also be refused before its mapping exists",
                    SessionState.Paused, rig.host.currentState)
                assertTrue("and nothing may be displayable yet", !rig.displayIsLive())

                rig.answerExchange()

                assertEquals("a mapping re-established without a transition must still retry the activation",
                    SessionState.Ready, rig.host.currentState)
                assertNull("and a successful retry must clear the refusal it was retried from",
                    rig.root.state.value.errorCode)
                assertEquals("activated once per session, not once per signal", 2, plugin.activations.get())

                val armed = requireNotNull(rig.root.currentAudioEpoch()) { "the retry must arm the ears" }
                assertEquals("the epoch must name the generation that is current after the activation",
                    rig.host.currentGeneration, armed.generation)
                assertEquals("exactly one arm line for the whole retry", 1,
                    lines.count { it.startsWith("P3_AUDIO state=armed") })
                assertEquals(listOf(armed), rig.loopPort.started)
                assertEquals(listOf(armed), rig.transportPort.started)
            }
        }
    }

    // ---- (b) already activated, or after stop(), a re-sync does nothing ------------------------------------------

    @Test
    fun anAlreadyActivatedPluginIsNotRetriedByAMappingResync() {
        val plugin = CountingPlugin("d10-wake", listOf(Capability.TextOutput, Capability.WakeWord))
        Rig(plugin, wakeWordProvider = true).use { rig ->
            rig.startThenConnect()
            rig.answerExchange()
            val armed = requireNotNull(rig.root.currentAudioEpoch())
            assertEquals(1, plugin.activations.get())

            withP3Sink { lines ->
                rig.resyncInPlace()

                assertTrue("the re-sync must really leave a live mapping", rig.displayIsLive())
                assertEquals("an activated plugin must not be activated again", 1, plugin.activations.get())
                assertSame("the same epoch must keep polling; a re-arm would be a second poll loop",
                    armed, rig.root.currentAudioEpoch())
                assertTrue("nothing may be armed: ${lines.toList()}",
                    lines.none { it.startsWith("P3_AUDIO state=armed") })
                assertTrue("and nothing may be disarmed either: ${lines.toList()}",
                    lines.none { it.startsWith("P3_AUDIO") })
            }
        }
    }

    @Test
    fun aStoppedSessionIsNotResurrectedByAMappingResync() {
        val plugin = CountingPlugin("d10-wake", listOf(Capability.TextOutput, Capability.WakeWord))
        Rig(plugin, wakeWordProvider = true).use { rig ->
            rig.startThenConnect()
            rig.answerExchange()
            assertEquals(1, plugin.activations.get())

            rig.root.stop()
            assertEquals(SessionState.Idle, rig.host.currentState)

            withP3Sink { lines ->
                rig.resyncInPlace()

                assertTrue("the re-sync must really leave a live mapping", rig.displayIsLive())
                assertEquals("a stopped session must not be resurrected", SessionState.Idle, rig.host.currentState)
                assertNull("and nothing may be armed", rig.root.currentAudioEpoch())
                assertEquals("and nothing may be activated again", 1, plugin.activations.get())
                assertTrue("no arm line may exist: ${lines.toList()}",
                    lines.none { it.startsWith("P3_AUDIO state=armed") })
            }
        }
    }

    // ---- (c) a plugin without WakeWord is activated but never arms ------------------------------------------------

    @Test
    fun aRetriedPluginThatDoesNotDeclareWakeWordIsActivatedButNeverArms() {
        val plugin = CountingPlugin("d10-text", listOf(Capability.TextOutput))
        Rig(plugin, wakeWordProvider = false).use { rig ->
            rig.startThenConnect()
            rig.answerExchange()
            assertEquals(SessionState.Ready, rig.host.currentState)

            withP3Sink { lines ->
                rig.root.stop()
                rig.loopPort.started.clear()
                rig.transportPort.started.clear()
                rig.startThenConnect()
                rig.answerExchange()

                assertEquals("the retry must still activate a display-only plugin",
                    SessionState.Ready, rig.host.currentState)
                assertNull(rig.root.state.value.errorCode)
                assertEquals(2, plugin.activations.get())
                assertNull("a plugin that does not declare WakeWord must not arm the ears",
                    rig.root.currentAudioEpoch())
                assertTrue("neither port may be started", rig.loopPort.started.isEmpty())
                assertTrue("neither port may be started", rig.transportPort.started.isEmpty())
                assertTrue("no P3_AUDIO armed line may exist: ${lines.toList()}",
                    lines.none { it.startsWith("P3_AUDIO state=armed") })
            }
        }
    }

    // ---- (d) a retry that keeps failing does not loop -------------------------------------------------------------

    @Test
    fun aRetryWhoseCapabilityStaysMissingAttemptsOncePerMappingAndNeverLoops() {
        // Audio is never advertised by this environment, so the display becoming real cannot satisfy this plugin
        // and every attempt is refused. Registered under the diagnostic id, which is the id the composition builds
        // a configuration for, so one `configure` per attempt makes the attempts countable.
        val plugin = AttemptCountingPlugin("p2-diagnostic", listOf(Capability.Audio, Capability.TextOutput))
        Rig(plugin, wakeWordProvider = false).use { rig ->
            rig.startThenConnect()
            assertEquals("the scene-ready path makes the first attempt", 1, plugin.attempts.get())
            assertEquals(SessionState.Paused, rig.host.currentState)

            // One exchange: the transition callback and the every-sync callback both arrive for the same mapping,
            // and the attempt must happen once - not once per callback.
            rig.answerExchange()
            assertEquals("one mapping, one attempt", 2, plugin.attempts.get())

            // Two more re-syncs in place: one more attempt each, because a fresh mapping is a fresh chance for the
            // capability to have appeared - and no more than that.
            rig.resyncInPlace()
            assertEquals(3, plugin.attempts.get())
            rig.resyncInPlace()
            assertEquals(4, plugin.attempts.get())

            assertEquals("a capability that stays missing must not leave the ears armed",
                null, rig.root.currentAudioEpoch())
            assertEquals(SessionState.Paused, rig.host.currentState)
            assertEquals("CAPABILITY_MISSING", rig.root.state.value.errorCode)
            assertEquals("and it must never have activated", 0, plugin.activations.get())
        }
    }
}
