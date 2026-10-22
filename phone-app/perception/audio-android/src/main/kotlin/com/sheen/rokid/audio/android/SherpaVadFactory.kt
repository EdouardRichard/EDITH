package com.sheen.rokid.audio.android

import com.k2fsa.sherpa.onnx.Vad
import com.sheen.rokid.audio.VadEngine

/**
 * The public VAD-only entry point (Task 11b, Important 1).
 *
 * Why it exists: `WakeWordDetector` needs a `VadEngine` **of its own** - it calls `reset()` on the
 * speech→silence edge, so it cannot share the speech pipeline's VAD - and until now the only public way to
 * obtain one was [SherpaEngineFactory.createCandidate], which also constructs the recognizer and the speaker
 * extractor. For the wake path that is the ~200 MB ASR load the no-wake ruling forbids.
 *
 * **What it builds and what it does not.** Exactly one `VadEngine` from the already-validated `vad` role
 * artifact. It never reads the `encoder`/`decoder`/`joiner`/`tokens`/`speaker` artifacts and never constructs
 * `OnlineRecognizer`, `SpeakerEmbeddingExtractor` or any ASR state. The bundle must already be validated -
 * this factory does not download, verify or activate anything: it consumes a [ValidatedModelBundle] that
 * `EngineModelStore` or `EngineBundleProvisioning` produced.
 *
 * It mirrors [SherpaEngineFactory]'s port/owner pattern: one native `Vad` behind an `OwnedVadPort`, so the
 * engine's `reset()`/`close()` behave exactly as they do for the pipeline's VAD.
 *
 * **Refusals are returned, never thrown.** A wake path is optional, so an unusable bundle must degrade to
 * "no wake word" rather than fail the composition that owns the ASR chain. [VadFactoryResult.reason] carries
 * a stable code for the caller's log.
 *
 * **Unverified here:** the positive construction path needs the JNI library and a real `.onnx` artifact, so a
 * JVM unit test can only exercise the refusals. The native VAD load is device-verified, not unit-verified.
 */
object SherpaVadFactory {
    /** The bundle's manifest does not carry the `vad` role the manifest contract requires. */
    const val ROLE_MISSING = "VAD_ROLE_MISSING"

    /** The bundle's roles could not be validated (a size or digest mismatch, or an unreadable artifact). */
    const val BUNDLE_UNVALIDATED = "VAD_BUNDLE_UNVALIDATED"

    /** The native engine could not be constructed (the JNI load or the model read failed). */
    const val ENGINE_UNAVAILABLE = "VAD_ENGINE_UNAVAILABLE"

    private const val VAD_ROLE = "vad"

    /**
     * @return a ready [VadEngine], or `null` in [VadFactoryResult.engine] plus the code in
     *   [VadFactoryResult.reason] explaining which precondition was not met.
     */
    fun create(bundle: ValidatedModelBundle): VadFactoryResult {
        // Checked before anything is opened: a factory that touched the artifacts first would fail on the
        // missing JNI library instead of reporting the role it is actually missing. The branch is unreachable
        // through today's public surface - `EngineManifest.validate()` requires `vad` among exactly six roles,
        // so no genuine bundle lacks it - and is kept as defence in depth for that contract.
        if (bundle.manifest.artifacts.none { it.role == VAD_ROLE }) return VadFactoryResult(null, ROLE_MISSING)

        val paths = try {
            bundle.validatedPaths()
        } catch (_: Exception) {
            // A validated bundle normally cannot fail here; if it does, its artifacts are not trustworthy and
            // the caller must not be handed an engine built from them.
            return VadFactoryResult(null, BUNDLE_UNVALIDATED)
        }
        val vadArtifact = paths[VAD_ROLE] ?: return VadFactoryResult(null, ROLE_MISSING)

        val engine = try {
            SherpaVadEngine(OwnedVadPort(SherpaVadOwner(
                Vad(config = SherpaNativeConfiguration.vad(vadArtifact, bundle.manifest)))))
        } catch (_: Throwable) {
            return VadFactoryResult(null, ENGINE_UNAVAILABLE)
        }
        return VadFactoryResult(engine, null)
    }
}

/** A [SherpaVadFactory.create] outcome: exactly one of [engine] and [reason] is non-null. */
class VadFactoryResult(val engine: VadEngine?, val reason: String?)
