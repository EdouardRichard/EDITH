package com.sheen.rokid.phone.app

import android.app.Application
import com.sheen.rokid.audio.android.EngineManifestLoader
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.model.BundledModelProvisioner
import com.sheen.rokid.phone.model.BundledModelsManifest
import com.sheen.rokid.phone.model.ModelAssetSource
import com.sheen.rokid.phone.model.ModelReadiness
import com.sheen.rokid.phone.model.ModelStartGate
import com.sheen.rokid.phone.model.ModelStorageSpace
import com.sheen.rokid.phone.model.ProvisioningState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class EdithApplication : Application() {
    /**
     * The one bundled-model readiness state of this process (0.4 Task 3, spec §6.3).
     *
     * The UI observes it to show the first-start preparation screen; every session start path asks it before the
     * foreground service, the composition or the host is touched.
     */
    val modelReadiness = ModelReadiness()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** False only for the development/CI `-PwithoutBundledModels=true` build (spec §6.2); release always bundles. */
    private val bundledModels = BuildConfig.BUNDLED_MODELS

    @Volatile
    private var provisioner: BundledModelProvisioner? = null

    @Volatile
    private var provisioning: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (!bundledModels) {
            // A development-only build packages no `assets/models/**`; the push-a-model workflow stays exactly as it
            // was, so pretending to provision would break that workflow, not protect it.
            modelReadiness.markReady(UNBUNDLED_BUNDLE_ID)
            android.util.Log.i(
                MODELS_LOG_TAG,
                "P4_MODELS state=ready stage=all bytes=0 bundle=$UNBUNDLED_BUNDLE_ID reason=UNBUNDLED",
            )
            return
        }
        startProvisioning()
    }

    /** Retries a failed first-start materialization; the failed screen's retry button calls this. */
    fun retryModelProvisioning() {
        if (!bundledModels) return
        startProvisioning()
    }

    private fun startProvisioning() {
        if (provisioning?.isActive == true) return
        modelReadiness.markPreparing()
        provisioning = applicationScope.launch {
            val outcome = try {
                provisioner().run()
            } catch (failure: Exception) {
                // No path/content in the reason: only the stable code is exposed to the UI and the log.
                ProvisioningState.Failed("MODELS_PROVISIONING_FAILED")
            }
            when (outcome) {
                is ProvisioningState.Ready -> modelReadiness.markReady(outcome.bundleId)
                is ProvisioningState.Failed -> modelReadiness.markFailed(outcome.reason)
                ProvisioningState.Preparing -> Unit
            }
        }
    }

    /**
     * The App's one composition, built on first use **only after the bundled models are ready**.
     *
     * The lazy construction is manual rather than `by lazy` so [liveComposition] can exist: a notification tap arrives
     * in a `BroadcastReceiver` that must be able to ask "is there a composition?" **without building one** - the App's
     * graph opens blobs, a keystore-backed vault and engine provisioning, and a receiver has seconds, not a session.
     *
     * @throws IllegalStateException `MODELS_PREPARING`/`MODELS_UNAVAILABLE` while the bundle is not ready. Every
     *   session-start caller gates first; this is the backstop that makes "no half-capability session" structural.
     */
    val composition: HostCompositionRoot
        get() {
            ModelStartGate.refusal(modelReadiness.state.value)?.let { refusal ->
                throw IllegalStateException(refusal)
            }
            return built ?: synchronized(this) { built ?: createComposition().also { built = it } }
        }

    @Volatile
    private var built: HostCompositionRoot? = null

    /** The composition only if something already asks for it; null before [composition] was first used. */
    val liveComposition: HostCompositionRoot? get() = built

    protected open fun createComposition(): HostCompositionRoot = HostCompositionRoot.create(
        context = this,
        readiness = modelReadiness,
        // Defense in depth (spec §6.3): the two model-loader threads call this before they read their files. The
        // provisioner's same-process fast path only checks receipts/sizes, so a healthy start pays no extra copy.
        provision = if (bundledModels) ({ provisioner().run() }) else null,
    )

    /**
     * Builds the production provisioner over the real App directories and the APK's own assets.
     *
     * Runs on the provisioning coroutine (IO), not on the main thread: the two manifests are small, but the directory
     * and store setup belongs to the same off-main first-start sequence.
     */
    private fun provisioner(): BundledModelProvisioner {
        provisioner?.let { return it }
        return synchronized(this) {
            provisioner ?: createProvisioner().also { provisioner = it }
        }
    }

    private fun createProvisioner(): BundledModelProvisioner {
        val source = ModelAssetSource.from(assets)
        val modelsManifest = BundledModelsManifest.parse(
            assets.open("models/models-manifest.json").bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
        // The trusted ASR manifest is the same asset the composition already loads; the App's store is the same
        // no-backup directory the debug drop and the ASR chain use, so there is one store contract, not two.
        val asrManifest = assets.open("engine-manifest.json").use(EngineManifestLoader::load)
        return BundledModelProvisioner(
            source = source,
            store = DebugImportDirectory.store(this),
            asrManifest = asrManifest,
            modelsManifest = modelsManifest,
            layaDirectory = filesDir,
            embeddingDirectory = EmbeddingModelDirectory.locate(getExternalFilesDir(null), filesDir),
            // On a first install `filesDir` exists but `<externalFilesDir>/models/embeddinggemma` does not, and
            // `File.usableSpace` on an absent path reads 0. The probe answers for the volume through the nearest
            // existing ancestor, so the pre-write gate sees the real free bytes instead of refusing permanently.
            freeSpace = ModelStorageSpace::usableBytes,
        )
    }

    private companion object {
        const val MODELS_LOG_TAG = "EdithModels"
        const val UNBUNDLED_BUNDLE_ID = "unbundled"
    }
}
