package edith.phone

import edith.audio.AsrHypothesis
import edith.audio.PcmFramer
import edith.audio.PcmFrame
import edith.audio.PcmIngress
import edith.audio.SpeakerEngine
import edith.audio.StreamingAsrEngine
import edith.audio.VadDecision
import edith.audio.VadEngine
import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.AudioFormat
import edith.contracts.HostEvent
import edith.contracts.InputEpoch
import edith.contracts.PcmEncoding
import edith.contracts.SessionId
import edith.contracts.SpeakerAssessment
import edith.contracts.VerifiedPcm
import edith.host.ActivationResult
import edith.host.HostRuntime
import edith.phone.model.ProactiveArming
import edith.plugin.CancellationHandle
import edith.plugin.Capability
import edith.plugin.ConfigSchema
import edith.plugin.ContextSignals
import edith.plugin.ContextSnapshot
import edith.plugin.DeactivationReason
import edith.plugin.ModelClient
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.PluginContext
import edith.plugin.PluginDescriptor
import edith.plugin.PluginLifecycle
import edith.plugin.SpeakerWindow
import edith.storage.MemoryProfileRepository
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Task 5: 「主动辅助」is a session-level switch, and the switch **is** the arming gate.
 *
 * The ruling (spec §16.2) is that arming is not a new contract value: the host simply does not produce the
 * perception events while the switch is off, so a plugin that receives no signal is a plugin that cannot act. This
 * file pins the two halves of that - the switch's own lifecycle, and the production sink
 * (`HostCompositionRoot.armedAcousticSink`) refusing to emit while it is off, with the real chain still reporting
 * label transitions underneath.
 */
class ProactiveArmingTest {

    private val session = SessionId("s-1")
    private val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test")

    // ---- fakes ---------------------------------------------------------------------------------------------------

    private class SpeechVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(true, false)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentAsr : StreamingAsrEngine {
        override fun accept(frame: PcmFrame): List<AsrHypothesis> = emptyList()
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

    private class InertContext : ContextSignals {
        override fun snapshot() = ContextSnapshot(0L, emptyMap())
        override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class Recorder : PluginLifecycle {
        override val descriptor = PluginDescriptor("p-1", "1.0.0", 1, "P1", listOf(Capability.Context), ConfigSchema(1))
        val handled = CopyOnWriteArrayList<HostEvent>()
        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) { handled += event }
    }

    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    /**
     * The chain + host wiring of `HostCompositionRoot.create`, with the one difference that matters here: the chain's
     * acoustic sink is the **production** armed sink over the test's own [ProactiveArming].
     */
    private class Rig(val arming: ProactiveArming, private val session: SessionId, private val verified: VerifiedPcm) {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val ingress = PcmIngress(clock)
        val plugin = Recorder()
        val chain: CxrAudioChain
        val host: HostRuntime

        init {
            val holder = arrayOfNulls<HostRuntime>(1)
            chain = CxrAudioChain.assemble(ingress = { ingress }, framer = PcmFramer(verified, 20),
                vad = SpeechVad(), asr = SilentAsr(), speaker = SilentSpeaker(), wake = null,
                scheduler = scheduler, clock = clock,
                emit = { event -> holder[0]?.onEvent(event) },
                acousticEmit = HostCompositionRoot.armedAcousticSink({ holder[0] }, clock, arming))
            val environment = CxrHostEnvironment(clock = clock, input = chain.input, liveOutput = { null },
                scheduler = ManualScheduler(clock),
                providers = mapOf(Capability.Context to InertContext(), Capability.Asr to chain.asrSession))
            host = HostRuntime(1, environment)
            holder[0] = host
            host.register(plugin)
        }

        fun activate(): ActivationResult {
            host.startSession(session)
            host.markSceneReady()
            return host.activate(plugin.descriptor.pluginId)
        }

        /** Offers one 20 ms frame at a time and lets the chain's own poll loop consume each one. */
        fun feedFrames(count: Int) {
            val epoch = InputEpoch(session, host.currentGeneration, 1)
            chain.input.start(epoch)
            ingress.open(epoch, verified)
            repeat(count) {
                ingress.offer(epoch, ByteArray(640) { 1 })
                clock.advance(20_000_000)
                scheduler.runDue()
            }
            host.currentState
        }

        fun close() {
            runCatching { chain.close() }
            runCatching { host.close() }
        }
    }

    // ---- the switch ----------------------------------------------------------------------------------------------

    @Test
    fun proactiveAssistanceIsOffByDefaultAndFollowsTheSwitch() {
        val arming = ProactiveArming()

        assertFalse("主动辅助 must be off until the user asks for it", arming.enabled.value)
        arming.set(true)
        assertTrue(arming.enabled.value)
        arming.deactivate()
        assertFalse("the session ending must return it to off", arming.enabled.value)
    }

    // ---- the arming gate -----------------------------------------------------------------------------------------

    @Test
    fun aDisarmedCompositionProducesNoAcousticEventEvenThoughTheChainReportsOne() {
        val rig = Rig(ProactiveArming(), session, verified)
        try {
            assertEquals(ActivationResult.Activated, rig.activate())
            withP3Sink { lines ->
                rig.feedFrames(12)

                assertTrue("a disarmed composition must deliver nothing",
                    rig.plugin.handled.none { it is AcousticEvent })
                assertTrue("and it must not even reach the emission line",
                    lines.none { it.startsWith("P3_ACOUSTIC") })
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun anArmedCompositionDeliversTheChainsTransition() {
        val arming = ProactiveArming()
        arming.set(true)
        val rig = Rig(arming, session, verified)
        try {
            assertEquals(ActivationResult.Activated, rig.activate())

            rig.feedFrames(12)

            assertEquals("the same frames must produce exactly one label once armed",
                listOf(AcousticLabel.Speech),
                rig.plugin.handled.filterIsInstance<AcousticEvent>().map { it.label })
        } finally {
            rig.close()
        }
    }

    // ---- the lifecycle -------------------------------------------------------------------------------------------

    private fun root(arming: ProactiveArming) = HostCompositionRoot(
        HostRuntime(1), MemoryProfileRepository(), InertModel(), null, proactiveArming = arming)

    @Test
    fun stoppingTheCompositionDisarmsTheSwitch() {
        val arming = ProactiveArming()
        val root = root(arming)
        try {
            arming.set(true)

            root.stop()

            assertFalse("the arming must not outlive the session it was given for", arming.enabled.value)
        } finally {
            root.close()
        }
    }

    @Test
    fun aDirectHostStopThatNeverReachesTheCompositionStopAlsoDisarms() {
        val arming = ProactiveArming()
        val root = root(arming)
        try {
            root.host.startSession(session)
            arming.set(true)

            // The path the D13 comment calls out: a host-level stop that this composition's `stop` never sees must
            // still end the session-scoped grant.
            root.host.stop()

            assertFalse("every path back to Idle must disarm", arming.enabled.value)
        } finally {
            root.close()
        }
    }
}
