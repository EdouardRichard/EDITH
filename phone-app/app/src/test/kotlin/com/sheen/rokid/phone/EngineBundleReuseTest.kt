package com.sheen.rokid.phone
import com.sheen.rokid.phone.model.EngineBundleProvisioning
import com.sheen.rokid.phone.app.activateBundle
import com.sheen.rokid.phone.app.CandidateEngines
import com.sheen.rokid.phone.app.AudioChainEngines
import com.sheen.rokid.phone.app.EngineBundleStore
import com.sheen.rokid.phone.app.EngineBundleProvisioner
import com.sheen.rokid.phone.app.provisionAudioChainFrom

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.ModelArtifact
import com.sheen.rokid.audio.android.ValidatedModelBundle
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Task 15c D3: a healthy engine bundle must be **reused**, not re-imported on every cold start.
 *
 * The device evidence (2026-10-09) showed two different `ENGINE_IMPORTED: ACTIVE:…` bundle ids across two runs plus
 * the import directory: ~670 MiB of the App's storage for one bundle, re-copied and re-hashed on every process start.
 * The rule this file pins is that the composition asks `store.active()` and then `store.recover(manifest)` before it
 * ever considers the debug import, and that a refused import activates nothing and leaves the ASR chain unwired.
 *
 * The store is a fake with two independent answers, so "this process already validated it" and "a previous process
 * activated it" cannot be confused. The bundle values are real: they are imported through a real `EngineModelStore`
 * over a temporary directory, so every size and digest is actually checked.
 */
class EngineBundleReuseTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")

    /** A manifest that validates; the store re-checks every artifact, so [validatedBundle] really is validated. */
    private fun manifest(): EngineManifest = EngineManifest("sherpa-onnx-1.13.8", roles.map { role ->
        val bytes = "$role-model".toByteArray()
        ModelArtifact(role, "$role.bin", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            "https://example.org/$role", "r1", "licenses/test.txt")
    })

    /** A genuinely validated bundle, imported through a real store so every size and digest is really checked. */
    private fun validatedBundle(): ValidatedModelBundle {
        // An auto-named folder: a test may build more than one bundle, and `TemporaryFolder` refuses a repeat name.
        val store = EngineModelStore(temporary.newFolder().toPath())
        val contents = roles.associateWith { role -> "$role-model".toByteArray() }
        return store.import(manifest(),
            roles.associateWith { role -> { ByteArrayInputStream(contents.getValue(role)) } })
    }

    private fun importRoot(): File = temporary.newFolder()

    /**
     * The store seam with two independent answers, so "active" and "recoverable" can be told apart, and with the
     * call counts that make "the import was not attempted" an assertion rather than an inference.
     */
    private class FakeStore(
        var activeBundle: ValidatedModelBundle? = null,
        var recoverable: ValidatedModelBundle? = null,
    ) : EngineBundleStore {
        var recoverCalls = 0
        override fun active(): ValidatedModelBundle? = activeBundle
        override fun recover(manifest: EngineManifest): ValidatedModelBundle? {
            recoverCalls++
            return recoverable
        }
    }

    /** Models the production provisioner: a successful import activates the bundle in the store. */
    private class ImportingProvisioner(
        private val store: FakeStore,
        private val bundle: ValidatedModelBundle,
    ) : EngineBundleProvisioner {
        var calls = 0
        override fun importBundle(root: File): String {
            calls++
            store.activeBundle = bundle
            return "ACTIVE:${bundle.directory}"
        }
    }

    /** The documented refusal a failed import produces: `ENGINE_MISSING`, exactly as `EngineBundleProvisioning` raises it. */
    private class RefusingProvisioner : EngineBundleProvisioner {
        var calls = 0
        override fun importBundle(root: File): String {
            calls++
            throw ContractError.CapabilityMissing("ENGINE_MISSING")
        }
    }

    @Test
    fun anActiveValidBundleIsReusedWithoutImportingOrRecovering() {
        val bundle = validatedBundle()
        val store = FakeStore(activeBundle = bundle)
        val provisioner = ImportingProvisioner(store, validatedBundle())

        val resolved = activateBundle(store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest())

        assertSame("the active bundle is authoritative and must be the one used", bundle, resolved)
        assertEquals("an active bundle means the import directory is never read", 0, provisioner.calls)
        assertEquals("nor is a bundle recovered from a previous process", 0, store.recoverCalls)
    }

    @Test
    fun aRecoverableBundleIsReusedWithoutImportingAnotherCopy() {
        val bundle = validatedBundle()
        val store = FakeStore(recoverable = bundle)
        val provisioner = ImportingProvisioner(store, validatedBundle())

        val resolved = activateBundle(store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest())

        assertSame("a bundle a previous process activated must be reused", bundle, resolved)
        assertEquals("re-importing it would copy ~230 MiB and leak the previous copy", 0, provisioner.calls)
        assertEquals("the recovery was the check that found it", 1, store.recoverCalls)
    }

    @Test
    fun withNoUsableBundleTheDebugImportIsAttemptedAndActivatesOne() {
        val bundle = validatedBundle()
        val store = FakeStore()
        val provisioner = ImportingProvisioner(store, bundle)

        val resolved = activateBundle(store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest())

        assertSame("the imported bundle must be the one used", bundle, resolved)
        assertEquals("with neither an active nor a recoverable bundle the import is the only path left",
            1, provisioner.calls)
        assertSame("and the import must have activated it in the store", bundle, store.activeBundle)
    }

    @Test
    fun aRefusedImportLeavesNoActiveBundleAndTheAsrChainUnwired() {
        val store = FakeStore()
        val provisioner = RefusingProvisioner()

        val chain = provisionAudioChainFrom(
            store = store, provisioner = provisioner, importRoot = importRoot(), manifest = manifest(),
            allowDebugImport = true, clock = FakeClock(0), scheduler = ManualScheduler(FakeClock(0)),
            ingress = { null }, emit = {}, cancelTurn = {}, engines = StubEngines())

        try {
            assertNull("a refused import is ENGINE_MISSING, so no ASR chain may be built", chain)
            assertEquals("the import was attempted exactly once", 1, provisioner.calls)
            assertNull("a refused import must activate nothing", store.activeBundle)
        } finally {
            chain?.close()
        }
    }

    // ---- 0.4 Task 2: the APK's own ASR bundle is the last automatic path before the debug drop -------------------

    @Test
    fun anActiveBundleWinsWithoutAttemptingTheAssetImport() {
        val active = validatedBundle()
        val store = FakeStore(activeBundle = active)
        val provisioner = ImportingProvisioner(store, validatedBundle())
        var assetCalls = 0

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest(),
            assetImport = { assetCalls++; validatedBundle() })

        assertSame("an already-validated bundle is authoritative", active, resolved)
        assertEquals("the packaged assets are not even opened when a bundle is active", 0, assetCalls)
        assertEquals("nor is the debug drop read", 0, provisioner.calls)
    }

    @Test
    fun aRecoverableBundleWinsOverTheAssetImport() {
        val recovered = validatedBundle()
        val store = FakeStore(recoverable = recovered)
        val provisioner = ImportingProvisioner(store, validatedBundle())
        var assetCalls = 0

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest(),
            assetImport = { assetCalls++; validatedBundle() })

        assertSame("a bundle a previous process activated is reused before re-importing assets", recovered, resolved)
        assertEquals(1, store.recoverCalls)
        assertEquals("recovering beats materializing the packaged copy", 0, assetCalls)
        assertEquals(0, provisioner.calls)
    }

    @Test
    fun theAssetImportIsUsedWhenTheStoreIsEmptyEvenWhenDebugImportIsDisabled() {
        // This is the release path: `allowDebugImport=false`, the store has nothing, and the only bundle left is
        // the one the APK ships. Release must import it rather than report ENGINE_MISSING.
        val asset = validatedBundle()
        val store = FakeStore()
        val provisioner = ImportingProvisioner(store, validatedBundle())
        var assetCalls = 0

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = false, manifest = manifest(),
            assetImport = { assetCalls++; asset })

        assertSame("the packaged ASR bundle must be used in release", asset, resolved)
        assertEquals(1, assetCalls)
        assertEquals("recovery is still attempted first", 1, store.recoverCalls)
        assertEquals("a release build must never read the debug import drop", 0, provisioner.calls)
    }

    @Test
    fun theAssetImportRunsAfterRecoveryAndBeforeTheDebugDrop() {
        val asset = validatedBundle()
        val store = FakeStore()
        val provisioner = ImportingProvisioner(store, validatedBundle())
        var assetCalls = 0

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest(),
            assetImport = { assetCalls++; asset })

        assertSame("the packaged bundle is preferred over a pushed debug bundle", asset, resolved)
        assertEquals("recovery had its chance before the asset copy", 1, store.recoverCalls)
        assertEquals(1, assetCalls)
        assertEquals("the debug drop is only the last resort", 0, provisioner.calls)
    }

    @Test
    fun whenTheAssetImportFailsTheDebugDropIsTriedInDebug() {
        val debugBundle = validatedBundle()
        val store = FakeStore()
        val provisioner = ImportingProvisioner(store, debugBundle)

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest(),
            assetImport = { throw ContractError.CapabilityMissing("ENGINE_MISSING") })

        assertSame("a refused asset copy falls through to the debug drop in a debug build", debugBundle, resolved)
        assertEquals(1, provisioner.calls)
    }

    @Test
    fun whenTheAssetImportFailsAReleaseBuildActivatesNothing() {
        val store = FakeStore()
        val provisioner = ImportingProvisioner(store, validatedBundle())

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = false, manifest = manifest(),
            assetImport = { throw ContractError.CapabilityMissing("ENGINE_MISSING") })

        assertNull("a release build has no fallback after a refused asset copy", resolved)
        assertEquals("the debug drop must not be read in release", 0, provisioner.calls)
        assertNull("nothing may be activated", store.activeBundle)
    }

    @Test
    fun anAssetImportThatFindsNothingAlsoFallsThroughToTheDebugDropInDebug() {
        val debugBundle = validatedBundle()
        val store = FakeStore()
        val provisioner = ImportingProvisioner(store, debugBundle)

        val resolved = activateBundle(
            store, provisioner, importRoot(), allowDebugImport = true, manifest = manifest(),
            assetImport = { null })

        assertSame("no packaged model means the debug drop is still usable", debugBundle, resolved)
        assertEquals(1, provisioner.calls)
    }

    // ---- engine stubs, so the provision path is exercised without the JNI libraries ---------------------------------

    private class StubEngines : AudioChainEngines() {
        override fun vad(bundle: ValidatedModelBundle): VadEngine = StubVad()
        override fun asrCandidate(bundle: ValidatedModelBundle, verified: VerifiedPcm): CandidateEngines =
            CandidateEngines(StubVad(), StubAsr(), StubSpeaker())
        // `wakeDetector` is not overridden: its default needs packaged assets, which is honestly "no detector here".
    }

    private class StubVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(true, false)
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
}
