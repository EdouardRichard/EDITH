package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.withP3Sink
import com.sheen.rokid.phone.ui.profile.TestBlobs
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
import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
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
    fun proactiveAssistanceIsOnByDefaultAndFollowsTheSwitch() {
        val arming = ProactiveArming()

        // `D53`②: 默认开、装一次即生效. The old model ("off until the user asks, off again when the session ends") is
        // exactly what the ruling replaced - and the reset it relied on (`deactivate()`) no longer exists, because no
        // path may re-arm a switch the user turned off.
        assertTrue("D53-②: 主动辅助 默认开", arming.enabled.value)
        arming.set(false)
        assertFalse("and the user's own switch turns it off", arming.enabled.value)
    }

    // ---- the arming gate -----------------------------------------------------------------------------------------

    @Test
    fun aDisarmedCompositionProducesNoAcousticEventEvenThoughTheChainReportsOne() {
        // `D53`②: the switch now defaults on, so "disarmed" is a state the test must set up explicitly rather than
        // inherit. That is the test's subject, not a default it can rely on.
        val arming = ProactiveArming()
        arming.set(false)
        val rig = Rig(arming, session, verified)
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
    fun stoppingTheCompositionLeavesTheSwitchAlone() {
        val arming = ProactiveArming()
        val root = root(arming)
        try {
            // `D53`②: a persisted global switch is the user's, not the session's. The old behaviour this test pinned -
            // "the arming must not outlive the session it was given for" - is exactly what the ruling removed, because
            // a stop that disarms silently turns off a feature the user configured once and expects to stay on.
            arming.set(true)

            root.stop()

            assertTrue("停止 must not change the user's global setting", arming.enabled.value)
        } finally {
            root.close()
        }
    }

    @Test
    fun aDirectHostStopAlsoLeavesTheSwitchAlone() {
        val arming = ProactiveArming()
        val root = root(arming)
        try {
            root.host.startSession(session)
            arming.set(true)

            // A host-level stop that this composition's `stop` never sees is the same rule: nothing resets the switch.
            root.host.stop()

            assertTrue("no path back to Idle may change a global setting", arming.enabled.value)
        } finally {
            root.close()
        }
    }

    @Test
    fun aDisarmedUserSwitchIsNotReArmedByStartingASession() {
        val arming = ProactiveArming()
        val root = root(arming)
        try {
            arming.set(false)

            root.host.startSession(session)
            root.stop()

            assertFalse("D53-②: 关掉后不得被任何路径自动打开", arming.enabled.value)
        } finally {
            root.close()
        }
    }

    // ---- 0.4 Task 5 (`D202`): detaching suppresses proactive assistance effectively --------------------------------

    @Test
    fun detachMakesEffectiveProactiveFalseWhileTheStoredSwitchStaysOn() {
        var detached = true
        val arming = ProactiveArming(detached = { detached })

        assertTrue("the stored/user switch is untouched", arming.enabled.value)
        assertFalse("D202: effective = stored && !detached", arming.effectiveProactiveArmed)

        detached = false
        assertTrue("closing detach restores the user's stored choice", arming.effectiveProactiveArmed)
    }

    @Test
    fun aStoredOffSwitchStaysEffectivelyOffInGlassesModeToo() {
        var detached = false
        val arming = ProactiveArming(detached = { detached })
        arming.set(false)

        assertFalse("effective is still the stored value AND-ed with the mode", arming.effectiveProactiveArmed)
        detached = true
        assertFalse(arming.effectiveProactiveArmed)
    }

    @Test
    fun aDetachedCompositionProducesNoAcousticEventEvenWithTheStoredSwitchOn() {
        // The store says 「主动辅助」 on; only the detach mode is suppressing. No event may reach the plugin, and no
        // P3_ACOUSTIC line may claim the label was emitted.
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(proactiveEnabled = true))
        val arming = ProactiveArming(store, detached = { true }).also { it.load() }
        assertTrue("the stored switch must still read on", arming.enabled.value)

        val rig = Rig(arming, session, verified)
        try {
            assertEquals(ActivationResult.Activated, rig.activate())
            withP3Sink { lines ->
                rig.feedFrames(12)

                assertTrue("a detached composition must deliver no acoustic event",
                    rig.plugin.handled.none { it is AcousticEvent })
                assertTrue("and it must not even reach the emission line",
                    lines.none { it.startsWith("P3_ACOUSTIC") })
            }
            assertEquals("the gate must not write the stored proactive value back",
                true, store.load().proactiveEnabled)
        } finally {
            rig.close()
        }
    }

    @Test
    fun closingDetachRestoresTheAcousticGateWithoutReWritingSettings() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(proactiveEnabled = true))
        var detached = true
        val arming = ProactiveArming(store, detached = { detached }).also { it.load() }

        val rig = Rig(arming, session, verified)
        try {
            assertEquals(ActivationResult.Activated, rig.activate())
            rig.feedFrames(12)
            assertTrue(rig.plugin.handled.none { it is AcousticEvent })

            detached = false
            // The chain reports each transition once; closing detach makes the **next** transition (another 12 frames
            // start a fresh speech segment) observable again.
            rig.feedFrames(12)

            assertEquals("glasses mode is unchanged: the same frames produce their label again",
                listOf(AcousticLabel.Speech),
                rig.plugin.handled.filterIsInstance<AcousticEvent>().map { it.label })
            assertEquals("the stored switch is still the user's on value", true, store.load().proactiveEnabled)
        } finally {
            rig.close()
        }
    }
}
