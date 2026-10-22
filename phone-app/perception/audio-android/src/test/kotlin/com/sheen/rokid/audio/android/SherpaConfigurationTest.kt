package com.sheen.rokid.audio.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class SherpaConfigurationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun bundle(endpoint: Int = 350): ValidatedModelBundle {
        val bytes = byteArrayOf(42)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")
        val manifest = EngineManifest("sherpa-onnx-1.13.8", roles.map {
            ModelArtifact(it, "$it.bin", 1, hash, "https://example.org/$it", "test", "licenses/test.txt")
        }, threadCount = 4, endpointMillis = endpoint)
        return EngineModelStore(temp.root.toPath()).import(manifest, roles.associateWith { { ByteArrayInputStream(bytes) } })
    }

    @Test fun asrUsesVerifiedBundleWithProductionVadEndpointOwnership() {
        val config = SherpaNativeConfiguration.asr(bundle())
        assertEquals(16000, config.featConfig.sampleRate)
        assertEquals(4, config.modelConfig.numThreads)
        assertEquals("cpu", config.modelConfig.provider)
        assertFalse(config.enableEndpoint)
        assertTrue(config.modelConfig.transducer.encoder.endsWith("encoder.bin"))
        assertTrue(config.modelConfig.transducer.joiner.endsWith("joiner.bin"))
    }

    @Test fun vadEndpointCandidateChangesTheActualBoundaryConfiguration() {
        assertEquals(0.25f, SherpaNativeConfiguration.vad(bundle(250)).sileroVadModelConfig.minSilenceDuration, 0f)
        assertEquals(0.45f, SherpaNativeConfiguration.vad(bundle(450)).sileroVadModelConfig.minSilenceDuration, 0f)
        assertEquals(512, SherpaNativeConfiguration.vad(bundle()).sileroVadModelConfig.windowSize)
    }
}
