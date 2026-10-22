package com.sheen.rokid.audio.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KeywordSpotterConfigTest {
    private fun request(
        maxActivePaths: Int = 7,
        keywordsScore: Float = 2.5f,
        keywordsThreshold: Float = 0.5f,
        numTrailingBlanks: Int = 3,
    ) = KeywordSpotterRequest(
        encoder = "kws/encoder.onnx",
        decoder = "kws/decoder.onnx",
        joiner = "kws/joiner.onnx",
        tokens = "kws/tokens.txt",
        keywordsFile = "kws/keywords.txt",
        maxActivePaths = maxActivePaths,
        keywordsScore = keywordsScore,
        keywordsThreshold = keywordsThreshold,
        numTrailingBlanks = numTrailingBlanks,
    )

    private fun bundle() = KeywordAssetBundle(
        encoder = KeywordAssetBundle.ENCODER,
        decoder = KeywordAssetBundle.DECODER,
        joiner = KeywordAssetBundle.JOINER,
        tokens = KeywordAssetBundle.TOKENS,
        keywordsFile = KeywordAssetBundle.KEYWORDS,
        keywords = listOf("y ī d í s ī @伊迪丝", "IY1 D IH0 TH @EDITH"),
    )

    @Test fun keywordSpotterConfigMapsEveryFieldFromTheRequest() {
        val source = request()
        val config = keywordSpotterConfig(source)
        assertEquals(16000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(0f, config.featConfig.dither, 0f)
        assertEquals(source.encoder, config.modelConfig.transducer.encoder)
        assertEquals(source.decoder, config.modelConfig.transducer.decoder)
        assertEquals(source.joiner, config.modelConfig.transducer.joiner)
        assertEquals(source.tokens, config.modelConfig.tokens)
        assertEquals(7, config.maxActivePaths)
        assertEquals(source.keywordsFile, config.keywordsFile)
        assertEquals(2.5f, config.keywordsScore, 0f)
        assertEquals(0.5f, config.keywordsThreshold, 0f)
        assertEquals(3, config.numTrailingBlanks)
    }

    @Test fun keywordSpotterRequestDefaultsMatchTheWiringValues() {
        val defaults = KeywordSpotterRequest(
            encoder = "kws/encoder.onnx",
            decoder = "kws/decoder.onnx",
            joiner = "kws/joiner.onnx",
            tokens = "kws/tokens.txt",
            keywordsFile = "kws/keywords.txt",
        )
        assertEquals(4, defaults.maxActivePaths)
        assertEquals(1.5f, defaults.keywordsScore, 0f)
        assertEquals(0.25f, defaults.keywordsThreshold, 0f)
        assertEquals(1, defaults.numTrailingBlanks)
    }

    @Test fun keywordSpotterConfigDerivesEveryPathAndThresholdFromTheBundle() {
        val source = bundle()
        val request = source.toRequest()
        val config = keywordSpotterConfig(request)
        assertEquals("kws/encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", config.modelConfig.transducer.encoder)
        assertEquals("kws/decoder-epoch-13-avg-2-chunk-8-left-64.onnx", config.modelConfig.transducer.decoder)
        assertEquals("kws/joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx", config.modelConfig.transducer.joiner)
        assertEquals("kws/tokens.txt", config.modelConfig.tokens)
        assertEquals("kws/keywords.txt", config.keywordsFile)
        assertEquals(4, config.maxActivePaths)
        assertEquals(1.5f, config.keywordsScore, 0f)
        assertEquals(0.25f, config.keywordsThreshold, 0f)
        assertEquals(1, config.numTrailingBlanks)
        assertEquals(16000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(0f, config.featConfig.dither, 0f)
        assertEquals("y ī d í s ī @伊迪丝/IY1 D IH0 TH @EDITH", source.keywordsArgument)
    }

    @Test fun absentKeywordSpotterBundleMeansNoPort() {
        assertNull(SherpaKeywordSpotterFactory.create(null, null))
    }

    @Test fun keywordSpotterBundleWithoutAnAssetManagerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SherpaKeywordSpotterFactory.create(bundle(), null)
        }
    }
}
