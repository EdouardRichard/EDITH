package edith.audio.android

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

    fun vad(bundle: ValidatedModelBundle): VadModelConfig {
        val files = bundle.validatedPaths()
        val manifest = bundle.manifest
        return VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = files.getValue("vad").toString(), threshold = 0.5f,
            minSilenceDuration = manifest.endpointMillis / 1000f, minSpeechDuration = 0.25f,
            windowSize = 512, maxSpeechDuration = 20f),
            sampleRate = 16000, numThreads = manifest.threadCount, provider = manifest.provider)
    }
}
