package com.sheen.rokid.audio.android

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 11b (Important 1): the public VAD-only factory.
 *
 * `WakeWordDetector` needs a `VadEngine` of its own (it resets its VAD-adjacent engine on the speech→silence
 * edge, so it cannot share the pipeline's), and before this factory the only public route to one was
 * `SherpaEngineFactory.createCandidate`, which loads the recognizer and the speaker extractor as well.
 *
 * **What is unit-testable and what is not.** The positive path constructs a real
 * `com.k2fsa.sherpa.onnx.Vad`, which needs the JNI library and a real `.onnx` artifact; a JVM unit test can do
 * neither, so the positive path is unverified here and needs the device. The refusal paths do not need the
 * native library, and those are what these tests pin.
 *
 * **Finding: a `ValidatedModelBundle` whose manifest lacks the `vad` role cannot exist.** `ArtifactRoles`
 * enforces a manifest with exactly six distinct role names including `vad`, and `validate()` runs inside
 * `validatedPaths()` - so no genuine bundle reaches the factory without that role, and the brief's
 * "null when the bundle lacks the vad role" branch is unreachable through the public surface. It is still
 * implemented (defence in depth for the branch that can only be reached if that contract ever loosens), and
 * its sibling - a bundle whose roles cannot be validated - is pinned below.
 */
class SherpaVadFactoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")

    /** A genuinely validated bundle, imported through the store so every size and digest really is checked. */
    private fun bundle(): ValidatedModelBundle {
        val contents = roles.associateWith { role -> "$role-model".toByteArray() }
        val manifest = EngineManifest("sherpa-onnx-1.13.8", roles.map { role ->
            val bytes = contents.getValue(role)
            ModelArtifact(role, "$role.bin", bytes.size.toLong(), sha256(bytes),
                "https://example.org/$role", "r1", "licenses/test.txt")
        })
        return EngineModelStore(temporary.newFolder("engine-models").toPath())
            .import(manifest, roles.associateWith { role -> { ByteArrayInputStream(contents.getValue(role)) } })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test
    fun aManifestWithoutTheVadRoleIsRejectedBeforeAnyBundleCanExist() {
        // The contract that makes `SherpaVadFactory.ROLE_MISSING` unreachable in practice, pinned here so the
        // reason is visible next to the factory rather than only in EngineManifest.
        val withoutVad = listOf("encoder", "decoder", "joiner", "tokens", "noise", "speaker")
        val contents = withoutVad.associateWith { role -> "$role-model".toByteArray() }
        val manifest = EngineManifest("sherpa-onnx-1.13.8", withoutVad.map { role ->
            val bytes = contents.getValue(role)
            ModelArtifact(role, "$role.bin", bytes.size.toLong(), sha256(bytes),
                "https://example.org/$role", "r1", "licenses/test.txt")
        })

        val thrown = runCatching {
            EngineModelStore(temporary.newFolder("engine-models").toPath())
                .import(manifest, withoutVad.associateWith { role -> { ByteArrayInputStream(contents.getValue(role)) } })
        }.exceptionOrNull()

        assertTrue("a vad-less manifest must be refused so no such bundle exists, was $thrown",
            thrown is IllegalArgumentException)
    }

    @Test
    fun createReturnsNoEngineAndTheUnvalidatedReasonWhenTheArtifactsDoNotVerify() {
        val bundle = bundle()
        // The directory is a public part of the bundle, so a test can damage what it points at. The manifest
        // still names the vad role, so the role check passes and the failure has to be caught while the roles
        // are validated - which is exactly the sibling branch of ROLE_MISSING.
        Files.write(bundle.directory.resolve("vad.bin"), "tampered".toByteArray())

        val result = SherpaVadFactory.create(bundle)

        assertEquals("an unverifiable bundle must not produce an engine", null, result.engine)
        assertEquals(SherpaVadFactory.BUNDLE_UNVALIDATED, result.reason)
    }

    @Test
    fun theVadConfigurationReadsOnlyTheValidatedVadArtifact() {
        // The VAD-only path must not reach for the recognizer: the config it builds names the vad artifact and
        // nothing else, so the ~200 MB ASR load cannot be pulled in by this route.
        val config = SherpaNativeConfiguration.vad(bundle())

        assertTrue("the vad config must point at the validated vad artifact",
            config.sileroVadModelConfig.model.endsWith("vad.bin"))
        listOf("encoder.bin", "decoder.bin", "joiner.bin", "tokens.bin", "speaker.bin").forEach { asrArtifact ->
            assertTrue("the VAD-only config must not reference $asrArtifact",
                !config.sileroVadModelConfig.model.endsWith(asrArtifact))
        }
    }
}
