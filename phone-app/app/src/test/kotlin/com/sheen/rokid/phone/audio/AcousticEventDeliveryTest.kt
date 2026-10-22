package com.sheen.rokid.phone.audio
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.host.ActivationResult
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Task 4: the audio chain's frame signals become real `AcousticEvent`s.
 *
 * The labeler (Task 3) only means something once the chain that owns the pipeline feeds it and the host delivers
 * what it produces. This file drives the **real** path - a real `PcmIngress` polled by the chain's own
 * `AudioIngressLoop`, the real `SpeechPipeline`, the real `AcousticLabeler`, the production
 * `HostCompositionRoot.emitAcoustic` builder and `HostRuntime`'s capability gate - because the pieces it tests are
 * exactly the connections between them.
 */
class AcousticEventDeliveryTest {

    private val session = SessionId("s-1")
    private val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test")

    // ---- fakes ---------------------------------------------------------------------------------------------------

    /** Speech on every frame, so the labeler's speech hold is reached within the frames this rig feeds. */
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

    /** The provider the environment needs so a plugin that declares `Context` can activate; it answers nothing. */
    private class InertContext : ContextSignals {
        override fun snapshot() = ContextSnapshot(0L, emptyMap())
        override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    /** The plugin under test: it declares exactly one capability and records every delivery it received. */
    private class Recorder(capability: Capability) : PluginLifecycle {
        override val descriptor = PluginDescriptor("p-1", "1.0.0", 1, "P1", listOf(capability), ConfigSchema(1))
        val handled = CopyOnWriteArrayList<HostEvent>()
        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) { handled += event }
    }

    /**
     * The chain and host, wired as `HostCompositionRoot.create` wires them: the chain's event sink hands events to
     * the host, and its acoustic sink is the production `emitAcoustic` builder, which resolves the identity per
     * emission from the host itself.
     */
    private class Rig(capability: Capability, private val session: SessionId, private val verified: VerifiedPcm) {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val ingress = PcmIngress(clock)
        val plugin = Recorder(capability)
        val chain: CxrAudioChain
        val host: HostRuntime

        init {
            val holder = arrayOfNulls<HostRuntime>(1)
            chain = CxrAudioChain.assemble(ingress = { ingress }, framer = PcmFramer(verified, 20),
                vad = SpeechVad(), asr = SilentAsr(), speaker = SilentSpeaker(), wake = null,
                scheduler = scheduler, clock = clock,
                emit = { event -> holder[0]?.onEvent(event) },
                acousticEmit = { label ->
                    holder[0]?.let { HostCompositionRoot.emitAcoustic(it, clock, label) }
                })
            val environment = CxrHostEnvironment(clock = clock, input = chain.input, liveOutput = { null },
                scheduler = ManualScheduler(clock),
                providers = mapOf(Capability.Context to InertContext(),
                    Capability.Asr to chain.asrSession))
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

    // ---- the delivered path --------------------------------------------------------------------------------------

    @Test
    fun aChainSpeechTransitionReachesAPluginThatDeclaresContext() {
        val rig = Rig(Capability.Context, session, verified)
        try {
            assertEquals(ActivationResult.Activated, rig.activate())

            rig.feedFrames(12)

            assertEquals("the chain's own frames must become one delivered acoustic event",
                listOf(AcousticLabel.Speech),
                rig.plugin.handled.filterIsInstance<AcousticEvent>().map { it.label })
        } finally {
            rig.close()
        }
    }

    @Test
    fun theSameChainSpeechTransitionReachesNoPluginWithoutTheContextCapability() {
        // `Asr` is genuinely provided by this rig, so the plugin activates and the only difference from the positive
        // case is which capability it declares - the event must still be gated out.
        val rig = Rig(Capability.Asr, session, verified)
        try {
            assertEquals(ActivationResult.Activated, rig.activate())

            rig.feedFrames(12)

            assertTrue("an undeclared capability must gate the event out",
                rig.plugin.handled.none { it is AcousticEvent })
        } finally {
            rig.close()
        }
    }
}
