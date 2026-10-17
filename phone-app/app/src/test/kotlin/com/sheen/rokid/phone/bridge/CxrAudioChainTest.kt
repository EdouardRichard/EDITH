package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.model.EngineBundleProvisioning
import com.sheen.rokid.phone.session.PlaybackGate
import com.sheen.rokid.phone.app.CxrAudioChain
import com.sheen.rokid.phone.app.activateBundle
import com.sheen.rokid.phone.app.CandidateEngines
import com.sheen.rokid.phone.audio.AudioIngressLoop
import com.sheen.rokid.phone.app.ModelStoreBundles
import com.sheen.rokid.phone.app.AudioChainEngines
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.app.EngineBundleProvisioner
import com.sheen.rokid.phone.app.provisionAudioChainFrom

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.KeywordHit
import com.sheen.rokid.audio.KeywordSpotterPort
import com.sheen.rokid.audio.KeywordStreamPort
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.ModelArtifact
import com.sheen.rokid.audio.android.ValidatedModelBundle
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.GapReason
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 11b: the chain-level seams that make Task 11's rulings reachable in a shipping build.
 *
 * - **Important 3 (the fault seam).** `INGRESS_OVERFLOW` / `UNKNOWN_FORMAT` / `GAP:*` are produced by
 *   `AudioIngressLoop`, but the loop is private to [CxrAudioChain], so nothing outside could hand the
 *   receiver's offer outcome to it. The first two tests assert that both a forwarded offer and a gap the loop
 *   drains reach the sink the composition wires to its diagnostics.
 * - **Important 2 (the debug import).** `EngineBundleProvisioning.importBundle` had no production caller, so
 *   the ratified conditional `Asr` advertisement could never become true. The last three tests pin the branch
 *   selection: the debug-only import directory is attempted only when asked for, success activates the bundle
 *   the chain is built from and advertises `Asr`, and every failure still leaves it unadvertised.
 * - **Important 1 (the detector's own VAD).** The factory-built detector VAD is distinct from the pipeline's
 *   and is released with the chain.
 */
class CxrAudioChainTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val epoch = InputEpoch(SessionId("s-1"), Generation(1), 0)
    private val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "test-format")

    // ---- test doubles -------------------------------------------------------------------------------------------

    private class StubVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(false, false)
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** A VAD that admits every frame, so "did the detector touch its VAD at all" is countable. */
    private class CountingVad : VadEngine {
        var frames = 0
        override fun accept(frame: PcmFrame) = VadDecision(true, false).also { frames++ }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class StubAsr : StreamingAsrEngine {
        override fun accept(frame: PcmFrame): List<AsrHypothesis> = emptyList()
        override fun finish() = AsrHypothesis("", true)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class StubSpeaker : SpeakerEngine {
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class StubKeywordSpotter : KeywordSpotterPort {
        private class Stream : KeywordStreamPort {
            override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) = Unit
            override fun release() = Unit
        }
        override fun createStream(keywords: String): KeywordStreamPort = Stream()
        override fun decode(stream: KeywordStreamPort) = Unit
        override fun isReady(stream: KeywordStreamPort) = false
        override fun result(stream: KeywordStreamPort): KeywordHit? = null
        override fun reset(stream: KeywordStreamPort) = Unit
        override fun close() = Unit
    }

    /**
     * Counts every engine the chain builds, so "the detector owns its own VAD, and the pipeline its own
     * recognizer" is asserted rather than assumed.
     */
    private class RecordingEngines(private val timeSource: FakeClock) : AudioChainEngines() {
        var vadBuilds = 0
        var candidateBuilds = 0
        override fun vad(bundle: ValidatedModelBundle): VadEngine? = StubVad().also { vadBuilds++ }
        override fun asrCandidate(bundle: ValidatedModelBundle, verified: VerifiedPcm): CandidateEngines? {
            candidateBuilds++
            return CandidateEngines(vad = StubVad().also { vadBuilds++ }, asr = StubAsr(), speaker = StubSpeaker())
        }
        override fun wakeDetector(bundle: ValidatedModelBundle, vad: VadEngine,
            emit: (word: String, confidence: Double) -> Unit,
            suppressWhile: () -> Boolean): WakeWordDetector? =
            WakeWordDetector(vad, com.sheen.rokid.audio.KeywordSpotterWakeWordEngine(StubKeywordSpotter(), "伊迪丝"),
                emit = emit, clock = timeSource, suppressWhile = suppressWhile)
    }

    /**
     * Models the production provisioner: a successful import **activates the bundle in the store**, which is
     * what the chain is then built from. A stub that only returned a string would let `activateBundle`'s
     * "activated ⇒ the store's active bundle" rule pass without ever being exercised.
     */
    private class StubProvisioner(
        private val import: ((File) -> ValidatedModelBundle)? = null,
        private val refuse: Boolean = false,
    ) : EngineBundleProvisioner {
        val importedRoots = mutableListOf<File>()
        override fun importBundle(root: File): String {
            importedRoots += root
            if (refuse) throw ContractError.CapabilityMissing("ENGINE_MISSING")
            val imported = requireNotNull(import).invoke(root)
            return "ACTIVE:${imported.directory}"
        }
    }

    private class StubModelClient : com.sheen.rokid.plugin.ModelClient {
        override fun request(request: com.sheen.rokid.plugin.ModelRequest,
            listener: (com.sheen.rokid.plugin.ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /**
     * A manifest that validates. The store's own `import` re-validates it and hashes every artifact, so a bundle
     * produced through [bundle] really is a `ValidatedModelBundle`.
     */
    private fun manifest(roles: List<String>): EngineManifest = EngineManifest("sherpa-onnx-1.13.8", roles.map { role ->
        val bytes = "$role-model".toByteArray()
        ModelArtifact(role, "$role.bin", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            "https://example.org/$role", "r1", "licenses/test.txt")
    })

    private val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")

    /** A genuinely validated bundle, imported through the store so every size and digest is really checked. */
    private fun importInto(store: EngineModelStore): ValidatedModelBundle {
        val contents = roles.associateWith { role -> "$role-model".toByteArray() }
        return store.import(manifest(roles),
            roles.associateWith { role -> { ByteArrayInputStream(contents.getValue(role)) } })
    }

    private fun provision(
        store: EngineModelStore,
        provisioner: EngineBundleProvisioner,
        allowDebugImport: Boolean,
        clock: FakeClock,
        engines: AudioChainEngines,
        ingress: () -> PcmIngress? = { null },
        /**
         * The scheduler the chain's poll loop is built on.
         *
         * A caller that wants to *drive* the loop (offer a chunk, then run the tick that polls it) must pass its own
         * `ManualScheduler`: the defaulted one is private to this call, so `runDue()` on a different instance would
         * tick nothing and the loop would look broken.
         */
        scheduler: DeadlineScheduler = ManualScheduler(clock),
        onFault: (String) -> Unit = {},
        onOffer: (PcmOffer) -> Unit = {},
        suppressWhile: () -> Boolean = { false },
    ): CxrAudioChain? = provisionAudioChainFrom(
        // The same production adapter the App uses, so this harness exercises the shipped store path.
        store = ModelStoreBundles(store),
        provisioner = provisioner,
        importRoot = temporary.newFolder("engine-models-import"),
        manifest = manifest(roles),
        allowDebugImport = allowDebugImport,
        clock = clock,
        scheduler = scheduler,
        ingress = ingress,
        emit = {},
        cancelTurn = {},
        engines = engines,
        suppressWhile = suppressWhile,
        onFault = onFault,
        onOffer = onOffer,
    )

    private fun environment(chain: CxrAudioChain, clock: FakeClock) = CxrHostEnvironment(
        clock = clock, input = chain.input, liveOutput = { null }, scheduler = ManualScheduler(clock),
        providers = mapOf(Capability.Model to StubModelClient(), Capability.Asr to chain.asrSession))

    // ---- Important 3: the fault and offer seam -------------------------------------------------------------------

    @Test
    fun everyForwardedOfferOutcomeReachesTheCompositionsSinkAndTheFaultsAreTheReportedOnes() {
        val clock = FakeClock(0)
        val faults = mutableListOf<String>()
        val offers = mutableListOf<PcmOffer>()
        val ingress = PcmIngress(clock)
        val chain = CxrAudioChain.assemble(ingress = { ingress }, framer = PcmFramer(verified, 20),
            vad = StubVad(), asr = StubAsr(), speaker = StubSpeaker(), wake = null,
            scheduler = ManualScheduler(clock), clock = clock, emit = {},
            onFault = { faults += it }, onOffer = { offers += it })
        try {
            chain.onOffer(PcmOffer.OVERFLOW)
            chain.onOffer(PcmOffer.UNKNOWN_FORMAT)
            chain.onOffer(PcmOffer.STALE_EPOCH)
            chain.onOffer(PcmOffer.ACCEPTED)

            assertEquals("OVERFLOW and UNKNOWN_FORMAT are reported, STALE_EPOCH and ACCEPTED are silent",
                listOf("INGRESS_OVERFLOW", "UNKNOWN_FORMAT"), faults)
            assertEquals("every offer outcome must be visible to diagnostics, including the silent ones",
                listOf(PcmOffer.OVERFLOW, PcmOffer.UNKNOWN_FORMAT, PcmOffer.STALE_EPOCH, PcmOffer.ACCEPTED), offers)
        } finally {
            chain.close()
        }
    }

    @Test
    fun aGapDrainedByTheRunningLoopReachesTheSameSink() {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val faults = mutableListOf<String>()
        val ingress = PcmIngress(clock)
        val chain = CxrAudioChain.assemble(ingress = { ingress }, framer = PcmFramer(verified, 20),
            vad = StubVad(), asr = StubAsr(), speaker = StubSpeaker(), wake = null,
            scheduler = scheduler, clock = clock, emit = {}, onFault = { faults += it })
        try {
            chain.input.start(epoch)
            ingress.open(epoch, verified)
            ingress.reset(GapReason.FORMAT_CHANGED)
            clock.advance(20_000_000)
            scheduler.runDue()

            assertTrue("a gap the loop drains must reach the sink, was $faults", faults.contains("GAP:FORMAT_CHANGED"))
        } finally {
            chain.close()
        }
    }

    // ---- Important 2 + 1: the debug import and the engines it builds ---------------------------------------------

    @Test
    fun theDebugImportDirectoryIsAttemptedAndItsBundleBecomesTheAdvertisedAsr() {
        val store = EngineModelStore(temporary.newFolder("store").toPath())
        assertNull("the store starts without an active bundle", store.active())
        // The import the provisioner performs is the store's own: the bundle the chain uses is the activated one.
        val provisioner = StubProvisioner(import = { importInto(store) })
        val clock = FakeClock(0)
        val engines = RecordingEngines(clock)
        val ingress = PcmIngress(clock)

        val chain = provision(store, provisioner, allowDebugImport = true, clock = clock,
            engines = engines, ingress = { ingress })
        try {
            assertNotNull("the chain must be built from the bundle the debug import activated", chain)
            assertEquals("the debug import directory must be attempted exactly once",
                1, provisioner.importedRoots.size)
            assertEquals("the attempted root must be the debug import directory",
                "engine-models-import", provisioner.importedRoots.single().name)
            assertNotNull("the activated bundle must be the store's active one", store.active())
            assertEquals("the pipeline's VAD and the detector's VAD are two distinct engines", 2, engines.vadBuilds)
            assertEquals("the ASR candidate is built exactly once", 1, engines.candidateBuilds)
            assertTrue("a provisioned chain must advertise Asr",
                Capability.Asr in environment(chain!!, clock).availableCapabilities)
        } finally {
            chain?.close()
        }
    }

    @Test
    fun releaseBuildsNeverImportFromTheExternalDirectory() {
        val store = EngineModelStore(temporary.newFolder("store").toPath())
        val provisioner = StubProvisioner(refuse = true)
        val clock = FakeClock(0)

        val chain = provision(store, provisioner, allowDebugImport = false, clock = clock,
            engines = RecordingEngines(clock))

        assertNull("no bundle means no chain and no Asr", chain)
        assertTrue("a release build must not read the external import directory", provisioner.importedRoots.isEmpty())
    }

    @Test
    fun aFailedDebugImportLeavesTheAsrCapabilityUnadvertised() {
        val store = EngineModelStore(temporary.newFolder("store").toPath())
        val provisioner = StubProvisioner(refuse = true)
        val clock = FakeClock(0)

        val chain = provision(store, provisioner, allowDebugImport = true, clock = clock,
            engines = RecordingEngines(clock))

        assertNull("a failed import must not produce a chain", chain)
        assertEquals("the import was attempted exactly once", 1, provisioner.importedRoots.size)
        assertNull("nothing may be activated by a failed import", store.active())
    }

    // ---- Task 14b: the detector's suppression gate is wired, and the loop honours it -----------------------------

    /**
     * A spotter that reports one hit per stream, so the real `KeywordSpotterWakeWordEngine` emits on a ready frame
     * and stays quiet afterwards - the in-place reset the production engine relies on.
     */
    private class HittingKeywordSpotter(private val word: String) : KeywordSpotterPort {
        private class Stream : KeywordStreamPort {
            override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) = Unit
            override fun release() = Unit
        }

        override fun createStream(keywords: String): KeywordStreamPort = Stream()
        override fun decode(stream: KeywordStreamPort) = Unit
        override fun isReady(stream: KeywordStreamPort) = true
        override fun result(stream: KeywordStreamPort) = KeywordHit(word, emptyList(), floatArrayOf())
        override fun reset(stream: KeywordStreamPort) = Unit
        override fun close() = Unit
    }

    /**
     * The whole delivered path, not the flag: the composition's own gate -> `HostCompositionRoot.playbackSuppression`
     * -> `provisionAudioChainFrom` -> `AudioChainEngines.wakeDetector` -> `WakeWordDetector.suppressWhile`, with the
     * real `AudioIngressLoop` polling real frames and the real detector's **emission** as the observable outcome
     * (Task 14b-fix items 1 and 3).
     *
     * The gate is the one the real `HostCompositionRoot` exposes - the handle P3.2's playback code calls `begin()` on
     * - and the guard is the production function `create` hands to the chain factory. `begin()` on the exposed handle
     * closing the detector is the identity that a second, local `PlaybackGate` in `create` broke: the documented
     * entry point suppressed nothing.
     *
     * Every frame carries the word. With the gate closed the detector returns before touching its VAD or its engine,
     * so nothing is emitted; once the gate opens the same chunk emits exactly once. Emission is what the seam is
     * supposed to change, so it is what is asserted - and the VAD's frame count is the second witness that a
     * suppressed frame never reached the engine.
     */
    @Test
    fun beginningOnTheCompositionsExposedGateSuppressesTheDetectorItsOwnChainBuilds() {
        val composition = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), StubModelClient(), null)
        val store = EngineModelStore(temporary.newFolder("store").toPath())
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val detectorVads = mutableListOf<CountingVad>()
        val engines = object : AudioChainEngines() {
            override fun vad(bundle: ValidatedModelBundle): VadEngine? = CountingVad().also { detectorVads += it }
            override fun asrCandidate(bundle: ValidatedModelBundle, verified: VerifiedPcm): CandidateEngines =
                CandidateEngines(vad = StubVad(), asr = StubAsr(), speaker = StubSpeaker())
            override fun wakeDetector(bundle: ValidatedModelBundle, vad: VadEngine,
                emit: (word: String, confidence: Double) -> Unit,
                suppressWhile: () -> Boolean): WakeWordDetector =
                WakeWordDetector(vad, com.sheen.rokid.audio.KeywordSpotterWakeWordEngine(HittingKeywordSpotter("伊迪丝"), "伊迪丝"),
                    emit = emit, clock = clock, suppressWhile = suppressWhile)
        }
        val ingress = PcmIngress(clock)
        val emitted = mutableListOf<String>()
        val chain = requireNotNull(provision(store, StubProvisioner(import = { importInto(store) }),
            allowDebugImport = true, clock = clock, engines = engines, ingress = { ingress },
            scheduler = scheduler,
            suppressWhile = HostCompositionRoot.playbackSuppression { composition }))
        try {
            chain.wakeEmit = { word, _ -> emitted += word }
            chain.input.start(epoch)
            ingress.open(epoch, verified)
            // One frame's worth of PCM16 mono at 16 kHz / 20 ms: 320 samples = 640 bytes.
            fun feedFrame() { ingress.offer(epoch, ByteArray(640) { 1 }) }

            composition.playbackGate.begin()
            feedFrame()
            clock.advance(20_000_000)
            scheduler.runDue()

            assertEquals("the detector was built by the factory, so its VAD exists", 1, detectorVads.size)
            assertEquals("a chunk heard during our own playback must not wake the App", 0, emitted.size)
            assertEquals("a suppressed frame must not reach the detector's VAD", 0, detectorVads.single().frames)

            composition.playbackGate.end()
            feedFrame()
            clock.advance(20_000_000)
            scheduler.runDue()

            assertEquals("the same chunk once the gate is open must wake exactly once", listOf("伊迪丝"), emitted)
            assertEquals("only the unsuppressed frame reached the detector's VAD", 1, detectorVads.single().frames)
        } finally {
            chain.close()
            composition.close()
        }
    }
}
