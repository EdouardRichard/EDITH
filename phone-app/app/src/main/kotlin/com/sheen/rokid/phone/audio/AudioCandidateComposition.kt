package com.sheen.rokid.phone.audio

import android.content.Context
import com.sheen.rokid.audio.android.EngineManifestLoader
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.SherpaEngineCandidate
import com.sheen.rokid.audio.android.SherpaEngineFactory
import com.sheen.rokid.audio.android.ValidatedModelBundle
import com.sheen.rokid.contracts.VerifiedPcm
import java.io.InputStream

enum class AudioCandidateAvailability { MISSING, READY_FOR_MEASUREMENT, INVALID }

/** App-owned import/recovery seam; the production capability remains gated by measured selection. */
class AudioCandidateComposition(context: Context) {
    private val manifest = context.applicationContext.assets.open("engine-manifest.json").use(EngineManifestLoader::load)
    private val store = EngineModelStore(context.applicationContext.noBackupFilesDir.toPath().resolve("engine-models"))
    val productionCapabilityReason: String get() = "ENGINE_MISSING"

    @Synchronized fun recover(): AudioCandidateAvailability = try {
        if (store.recover(manifest) == null) AudioCandidateAvailability.MISSING
        else AudioCandidateAvailability.READY_FOR_MEASUREMENT
    } catch (_: IllegalArgumentException) {
        AudioCandidateAvailability.INVALID
    } catch (_: java.io.IOException) {
        AudioCandidateAvailability.INVALID
    }

    @Synchronized fun importModels(sources: Map<String, () -> InputStream>): ValidatedModelBundle = store.import(manifest, sources)

    @Synchronized fun openForMeasurement(verified: VerifiedPcm): SherpaEngineCandidate {
        val bundle = requireNotNull(store.active() ?: store.recover(manifest)) { "No validated candidate models" }
        return SherpaEngineFactory.createCandidate(bundle, verified)
    }
}
