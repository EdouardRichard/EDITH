package com.sheen.rokid.audio.android

import org.junit.Assert.*
import org.junit.Test

class EngineManifestLoaderTest {
    private fun document(): String = """
        {"schemaVersion":1,"runtimeVersion":"sherpa-onnx-1.13.8",
         "runtime":{"sha256":"633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96"},
         "sampleRateHz":16000,"threadCount":2,"provider":"cpu","frameMillis":20,"endpointMillis":350,
         "boundaryOwner":"VAD","recognizerEndpointEnabled":false,"vadWindowSamples":512,
         "vadThreshold":0.5,"vadMinSpeechSeconds":0.25,"vadMaxSpeechSeconds":20,
         "artifacts":[${listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker").joinToString(",") {
             """{"role":"$it","path":"$it.onnx","bytes":3,"sha256":"${"a".repeat(64)}",
                  "sourceUrl":"https://example.org/$it","revision":"revision","licensePath":"licenses/license.txt"}"""
         }}]}
    """.trimIndent()

    @Test fun parsesAllSixArtifactsAndExplicitCandidateConfiguration() {
        val manifest = EngineManifestLoader.load(document().byteInputStream())
        assertEquals(6, manifest.artifacts.size)
        assertEquals(2, manifest.threadCount)
        assertEquals(350, manifest.endpointMillis)
        assertEquals("speaker.onnx", manifest.artifacts.single { it.role == "speaker" }.path)
    }
    @Test fun rejectsTraversalHashMismatchAndMissingConfiguration() {
        listOf(document().replace("vad.onnx", "../vad.onnx"),
            document().replace("633C2432", "00000000"),
            document().replace("\"threadCount\":2,", ""),
            document().replace("\"sampleRateHz\":16000", "\"sampleRateHz\":\"16000\""),
            document().replace("\"recognizerEndpointEnabled\":false", "\"recognizerEndpointEnabled\":true")
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { EngineManifestLoader.load(invalid.byteInputStream()) }
        }
    }
}
