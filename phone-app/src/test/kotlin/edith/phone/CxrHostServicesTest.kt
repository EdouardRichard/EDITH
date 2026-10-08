package edith.phone

import edith.contracts.Assistance
import edith.contracts.ContractError
import edith.contracts.Generation
import edith.contracts.HostEvent
import edith.contracts.InputEpoch
import edith.contracts.OutputStatus
import edith.contracts.SessionId
import edith.host.HostRuntime
import edith.host.ResourceScope
import edith.output.HintCommand
import edith.output.HintWireCodec
import edith.plugin.CancellationHandle
import edith.plugin.Capability
import edith.plugin.ConfigSchema
import edith.plugin.DeactivationReason
import edith.plugin.DeadlineScheduler
import edith.plugin.HintDevice
import edith.plugin.InputPort
import edith.plugin.ModelClient
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.MonotonicClock
import edith.plugin.PluginContext
import edith.plugin.PluginDescriptor
import edith.plugin.PluginLifecycle
import edith.plugin.StorageHandle
import edith.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T18 increment 2: the plugin-facing services the App can genuinely answer, and the activation timing that makes them
 * usable.
 *
 * The rule under test keeps [CxrHostEnvironment.availableCapabilities] honest: **advertised and answerable must be the
 * same set**. The host refuses any plugin whose required capabilities are not advertised, so a capability claimed but
 * not answerable would let a plugin start and then fail confusingly later, while one answerable but not advertised
 * would keep a working plugin from ever starting.
 *
 * Two capabilities must stay absent until approved in their own right: `Audio` (advertising it would add a
 * microphone-start path, because the host starts `input` for audio-requiring plugins) and `Speaker` (a real extractor
 * would have to decode microphone audio against the decision-labelled PCM format, and enrolment is not authorized).
 */
class CxrHostServicesTest {
    private class FakeClock(var nanos: Long = 1_000_000_000L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class RecordingInput : InputPort {
        val started = mutableListOf<InputEpoch>()
        override fun start(epoch: InputEpoch) {
            started += epoch
            throw ContractError.CapabilityMissing("audio unavailable")
        }
        override fun stop(epoch: InputEpoch) = Unit
    }

    private class StubDevice : HintDevice {
        override fun show(assistance: Assistance, revision: Long,
            listener: (OutputStatus) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }

    private class StubModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class StubStorage(val pluginId: String) : StorageHandle {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

    private fun scope(pluginId: String) = ResourceScope(pluginId, SessionId("s-1"), Generation(1))

    private fun environment(displayLive: Boolean, storage: Boolean = true, input: InputPort = RecordingInput()) =
        CxrHostEnvironment(
            clock = FakeClock(),
            input = input,
            liveOutput = { if (displayLive) StubDevice() else null },
            scheduler = FakeScheduler(),
            providers = mapOf(Capability.Model to StubModel()),
            storageFor = if (storage) { { id -> StubStorage(id) } } else null,
        )

    @Test
    fun everyAdvertisedCapabilityIsAnswerableAndNothingElseIs() {
        val env = environment(displayLive = true)
        val advertised = env.availableCapabilities

        assertEquals(setOf(Capability.Model, Capability.Storage, Capability.TextOutput), advertised)
        val services = env.services(scope("smart-assistant"), advertised)
        advertised.forEach { capability ->
            assertNull("$capability is advertised, so it must be answerable",
                runCatching { services.require(capability) }.exceptionOrNull())
        }
        listOf(Capability.Audio, Capability.Asr, Capability.Speaker, Capability.SpeechOutput).forEach { capability ->
            assertFalse("$capability must not be advertised", capability in advertised)
            assertNotNull("$capability must not be answerable",
                runCatching { services.require(capability) }.exceptionOrNull())
        }
    }

    @Test
    fun storageIsRefusedRatherThanAdvertisedWhenNoNamespaceFactoryExists() {
        val env = environment(displayLive = false, storage = false)

        assertFalse(Capability.Storage in env.availableCapabilities)
        assertNotNull(runCatching { env.services(scope("smart-assistant"), setOf(Capability.Storage))
            .require(Capability.Storage) }.exceptionOrNull())
    }

    @Test
    fun theModelProviderIsTheCompositionClientAndStopsAnsweringOnceItsScopeIsRevoked() {
        val env = environment(displayLive = true)
        val resource = scope("smart-assistant")
        val services = env.services(resource, setOf(Capability.Model))

        assertTrue(services.require(Capability.Model) is ModelClient)
        resource.revoke()

        assertNotNull("a revoked plugin must not keep resolving services",
            runCatching { services.require(Capability.Model) }.exceptionOrNull())
    }

    @Test
    fun storageIsResolvedPerPluginSoNamespacesCannotShareState() {
        val env = environment(displayLive = false)
        val first = env.services(scope("smart-assistant"), setOf(Capability.Storage))
            .require(Capability.Storage) as StorageHandle
        val second = env.services(scope("interview-assistant"), setOf(Capability.Storage))
            .require(Capability.Storage) as StorageHandle

        first.write("key", byteArrayOf(1))

        assertNull("each plugin must get its own namespace", second.read("key"))
    }

    @Test
    fun theDisplayCapabilityFollowsTheLiveMappingRatherThanTheEnvironment() {
        var device: HintDevice? = null
        val env = CxrHostEnvironment(clock = FakeClock(), input = RecordingInput(), liveOutput = { device },
            scheduler = FakeScheduler(), providers = mapOf(Capability.Model to StubModel()),
            storageFor = { id -> StubStorage(id) })

        assertFalse(Capability.TextOutput in env.availableCapabilities)
        device = StubDevice()
        assertTrue(Capability.TextOutput in env.availableCapabilities)
    }

    // ---- activation timing -----------------------------------------------------------------------------------

    private class ShellPlugin(override val descriptor: PluginDescriptor) : PluginLifecycle {
        var activated = false
        override fun activate(context: PluginContext) { activated = true }
        override fun handle(event: HostEvent) = Unit
        override fun deactivate(reason: DeactivationReason) { activated = false }
    }

    private class FakeConnection : CxrConnection {
        override var ready = false
        val sent = mutableListOf<String>()
        var inbound: ((String) -> Unit)? = null
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?) = Unit
        override fun sendCommand(json: String): Boolean { if (!ready) return false; sent += json; return true }
        override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
        override fun close() = Unit
    }

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    private class ActivationRig(descriptor: PluginDescriptor) : AutoCloseable {
        val clock = FakeClock()
        val connection = FakeConnection()
        val input = RecordingInput()
        val plugin = ShellPlugin(descriptor)
        val link: CxrHintLink
        val root: HostCompositionRoot

        init {
            val holder = arrayOfNulls<CxrConnection>(1)
            link = CxrHintLink(clock, FakeScheduler(), sendJson = { json -> holder[0]?.sendCommand(json) ?: false })
            val environment = CxrHostEnvironment(clock = clock, input = input, liveOutput = link::liveOutput,
                scheduler = FakeScheduler(), providers = mapOf(Capability.Model to StubModel()),
                storageFor = { id -> StubStorage(id) })
            val host = HostRuntime(1, environment)
            host.register(plugin)
            root = HostCompositionRoot(host, MemoryProfileRepository(), UnusedModel(), environment,
                rokidConnectionFactory = { connection.also { holder[0] = it } }, hintLink = link)
        }

        override fun close() = root.close()

        fun startAndReportReady(pluginId: String) {
            root.connectCxr(activity = Any())
            connection.ready = true
            root.onSceneReady()
            root.start(pluginId)
        }

        fun completeExchange() {
            val ping = connection.sent.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.single()
            clock.nanos = ping.p0 + 10_000_000
            connection.inbound!!(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))
        }
    }

    private fun smartDescriptor() = PluginDescriptor("smart-assistant", "1.0.0", 1, "smart",
        listOf(Capability.Model, Capability.TextOutput), ConfigSchema(1))

    @Test
    fun aPluginNeedingTheDisplayIsRefusedBeforeTheMappingExistsAndActivatedOnceItDoes() {
        ActivationRig(smartDescriptor()).use { rig ->
            rig.startAndReportReady("smart-assistant")

            // The display is not real until the glasses answer PING, so the plugin cannot start yet - which is honest
            // rather than a half-started plugin.
            assertFalse("the plugin must not activate while the display is not real", rig.plugin.activated)
            assertEquals("CAPABILITY_MISSING", rig.root.state.value.errorCode)

            rig.completeExchange()

            assertTrue("the display became real, so the refused activation must be retried", rig.plugin.activated)
            assertNull("a successful activation clears the earlier refusal", rig.root.state.value.errorCode)
        }
    }

    @Test
    fun aPluginNeedingAnUnwiredCapabilityStaysRefusedEvenAfterTheDisplayBecomesReal() {
        // interview-assistant needs Speaker, which is deliberately unwired: a live display must not be turned into a
        // fake success, and the App must keep reporting the refusal.
        val descriptor = PluginDescriptor("interview-assistant", "1.0.0", 1, "interview",
            listOf(Capability.Speaker, Capability.Storage, Capability.Model, Capability.TextOutput), ConfigSchema(1))
        ActivationRig(descriptor).use { rig ->
            rig.startAndReportReady("interview-assistant")
            rig.completeExchange()

            assertFalse("a plugin needing an unwired capability must stay refused", rig.plugin.activated)
            assertEquals("CAPABILITY_MISSING", rig.root.state.value.errorCode)
        }
    }

    @Test
    fun theHostStartsNoMicrophonePathForAPluginThatDoesNotRequireAudio() {
        ActivationRig(smartDescriptor()).use { rig ->
            rig.startAndReportReady("smart-assistant")
            rig.completeExchange()

            assertTrue(rig.plugin.activated)
            assertTrue("the host must not start the microphone path for a non-audio plugin", rig.input.started.isEmpty())
        }
    }
}
