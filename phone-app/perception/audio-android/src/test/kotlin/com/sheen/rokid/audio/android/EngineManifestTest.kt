package com.sheen.rokid.audio.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class EngineManifestTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = byteArrayOf(1, 2, 3)
    private val artifacts = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker").map {
        ModelArtifact(it, "$it.bin", 3, MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { byte -> "%02x".format(byte) }, "https://example.org/$it", "revision", "licenses/test.txt")
    }
    private fun manifest(files: List<ModelArtifact> = artifacts) = EngineManifest("sherpa-onnx-1.13.8", files)
    private fun sources() = artifacts.associate { it.role to { ByteArrayInputStream(payload) } }

    @Test fun completeImportIsValidatedAgainBeforeActivation() {
        val store = EngineModelStore(temp.root.toPath())
        val bundle = store.import(manifest(), sources())
        assertEquals(6, bundle.validatedPaths().size)
        bundle.validatedPaths().getValue("vad").toFile().writeBytes(byteArrayOf(3, 2, 1))
        assertThrows(IllegalArgumentException::class.java) { bundle.validatedPaths() }
    }

    @Test fun reconstructionUsesOnlyTheCompleteValidatedPublishedBundle() {
        val store = EngineModelStore(temp.root.toPath())
        val bundle = store.import(manifest(), sources())
        assertEquals(bundle.directory, EngineModelStore(temp.root.toPath()).recover(manifest())?.directory)
        temp.root.resolve("active").writeText("../outside")
        assertThrows(IllegalArgumentException::class.java) { EngineModelStore(temp.root.toPath()).recover(manifest()) }
    }

    @Test fun failedImportPreservesPreviouslyValidatedBundle() {
        val store = EngineModelStore(temp.root.toPath())
        val previous = store.import(manifest(), sources())
        assertEquals(previous.directory, store.active()?.directory)
        val broken = sources().toMutableMap().apply { put("speaker") { ByteArrayInputStream(byteArrayOf(1)) } }
        assertThrows(IllegalArgumentException::class.java) { store.import(manifest(), broken) }
        assertEquals(6, previous.validatedPaths().size)
        assertEquals(previous, store.active())
    }

    @Test fun traversalDuplicatesMissingAndExtraRolesAreRejected() {
        val store = EngineModelStore(temp.root.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            store.import(manifest(artifacts.map { if (it.role == "vad") it.copy(path = "../escape") else it }), sources())
        }
        assertThrows(IllegalArgumentException::class.java) { store.import(manifest(artifacts + artifacts.first()), sources()) }
        assertThrows(IllegalArgumentException::class.java) { store.import(manifest(artifacts.dropLast(1)), sources()) }
        assertThrows(IllegalArgumentException::class.java) { store.import(manifest(), sources() + ("extra" to { ByteArrayInputStream(payload) })) }
    }
}
