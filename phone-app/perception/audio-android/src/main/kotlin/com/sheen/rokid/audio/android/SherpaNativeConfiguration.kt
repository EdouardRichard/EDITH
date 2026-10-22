package com.sheen.rokid.audio.android

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

internal object SherpaNativeConfiguration {
    fun asr(bundle: ValidatedModelBundle): OnlineRecognizerConfig {
        val files = bundle.validatedPaths()
        val manifest = bundle.manifest
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = files.getValue("encoder").toString(),
                    decoder = files.getValue("decoder").toString(), joiner = files.getValue("joiner").toString()),
                tokens = files.getValue("tokens").toString(), numThreads = manifest.threadCount,
                provider = manifest.provider, modelType = "zipformer"),
            enableEndpoint = false,
            decodingMethod = "greedy_search")
    }

    fun vad(bundle: ValidatedModelBundle): VadModelConfig = vad(bundle.validatedPaths().getValue("vad"), bundle.manifest)

    /**
     * The same configuration from an already-resolved `vad` artifact. Task 11b's `SherpaVadFactory` resolves
     * the bundle's paths once (so the validated map is not computed twice) and still gets a VAD tuned exactly
     * like the pipeline's.
     */
    fun vad(vadArtifact: java.nio.file.Path, manifest: EngineManifest): VadModelConfig =
        VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = vadArtifact.toString(), threshold = 0.5f,
            minSilenceDuration = manifest.endpointMillis / 1000f, minSpeechDuration = 0.25f,
            windowSize = 512, maxSpeechDuration = 20f),
            sampleRate = 16000, numThreads = manifest.threadCount, provider = manifest.provider)
}
