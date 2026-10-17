package com.sheen.rokid.audio.android

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

data class ModelArtifact(val role: String, val path: String, val bytes: Long, val sha256: String,
    val sourceUrl: String, val revision: String, val licensePath: String)

data class EngineManifest(val runtimeVersion: String, val artifacts: List<ModelArtifact>,
    val sampleRateHz: Int = 16000, val threadCount: Int = 2, val provider: String = "cpu",
    val frameMillis: Int = 20, val endpointMillis: Int = 350) {
    internal fun validate() {
        require(runtimeVersion == "sherpa-onnx-1.13.8")
        require(sampleRateHz == 16000 && threadCount in setOf(1, 2, 4) && provider == "cpu")
        require(frameMillis == 20 && endpointMillis in setOf(250, 350, 450))
        require(artifacts.size == 6 && artifacts.map { it.role }.toSet() == requiredRoles)
        require(artifacts.map { it.path }.toSet().size == artifacts.size)
        artifacts.forEach {
            require(it.path.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && it.path != "." && it.path != "..")
            require(it.bytes in 1..536_870_912 && it.sha256.matches(Regex("[A-Fa-f0-9]{64}")))
            val url = URI(it.sourceUrl)
            require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null)
            require(it.revision.isNotBlank() && it.licensePath.matches(Regex("licenses/[A-Za-z0-9][A-Za-z0-9._-]*")))
        }
    }
    companion object {
        internal val requiredRoles = setOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")
    }
}

class ValidatedModelBundle internal constructor(val directory: Path, internal val manifest: EngineManifest) {
    fun validatedPaths(): Map<String, Path> {
        manifest.validate()
        require(Files.isDirectory(directory, NOFOLLOW_LINKS))
        val root = directory.toRealPath()
        return manifest.artifacts.associate { artifact ->
            val path = directory.resolve(artifact.path)
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().parent == root)
            require(Files.size(path) == artifact.bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } finally { buffer.fill(0) }
            }
            require(digest.digest().hex().equals(artifact.sha256, true))
            artifact.role to path
        }
    }
}

class EngineModelStore(private val root: Path) {
    private var current: ValidatedModelBundle? = null

    @Synchronized fun active(): ValidatedModelBundle? = current?.also { it.validatedPaths() }

    @Synchronized fun recover(manifest: EngineManifest): ValidatedModelBundle? {
        val frozen = manifest.copy(artifacts = manifest.artifacts.toList())
        frozen.validate()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return null
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        val canonical = root.toRealPath()
        val pointer = canonical.resolve("active")
        if (!Files.exists(pointer, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(pointer, NOFOLLOW_LINKS) && Files.size(pointer) == 36L)
        val name = Files.readAllBytes(pointer).toString(Charsets.US_ASCII)
        require(name.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        val bundle = ValidatedModelBundle(canonical.resolve(name), frozen)
        bundle.validatedPaths()
        current = bundle
        return bundle
    }

    @Synchronized fun import(manifest: EngineManifest, sources: Map<String, () -> InputStream>): ValidatedModelBundle {
        val frozen = manifest.copy(artifacts = manifest.artifacts.toList())
        frozen.validate()
        require(sources.keys == EngineManifest.requiredRoles)
        Files.createDirectories(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        val canonical = root.toRealPath()
        val staged = Files.createDirectory(canonical.resolve(UUID.randomUUID().toString()))
        try {
            frozen.artifacts.forEach { artifact ->
                sources.getValue(artifact.role).invoke().use { input ->
                    Files.newOutputStream(staged.resolve(artifact.path)).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        try {
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= artifact.bytes)
                                output.write(buffer, 0, count)
                            }
                            require(total == artifact.bytes)
                        } finally { buffer.fill(0) }
                    }
                }
            }
            val bundle = ValidatedModelBundle(staged, frozen)
            bundle.validatedPaths()
            val pointer = Files.createTempFile(canonical, "active-", ".tmp")
            try {
                Files.write(pointer, staged.fileName.toString().toByteArray(Charsets.US_ASCII))
                Files.move(pointer, canonical.resolve("active"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(pointer) }
            current = bundle
            return bundle
        } catch (failure: Throwable) {
            try {
                Files.newDirectoryStream(staged).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(staged)
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
