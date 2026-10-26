package com.sheen.rokid.phone.model

import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

/**
 * Materializes the APK's bundled model files into the directories the runtime already reads (spec §6.3).
 *
 * The flow is the one the spec names:
 * 1. **ASR** - reuse a bundle that `EngineModelStore` already validates, else import the packaged
 *    `assets/models/asr/` files through [AssetEngineBundleProvisioning]. The store re-checks every size and digest,
 *    so a refused import never becomes the active bundle.
 * 2. **Laya** - copy the six runtime files into [layaDirectory] through a temp file, verifying each stream's size and
 *    SHA-256 before it is renamed into place, then write a receipt naming the bundle.
 * 3. **EmbeddingGemma** - the same copy + receipt into [embeddingDirectory].
 *
 * The receipt makes the normal start cheap: when it names the manifest's `bundleId` and every target still has the
 * declared size, nothing is read. No whole-bundle re-hash runs on that path (spec §6.4); a changed APK changes the
 * `bundleId`, which forces one fresh copy.
 *
 * Failures are mapped to a stable reason and never leave a half-written file behind: a source failure while copying
 * is detected while the target is still a temp file, so the old runtime files and the old receipt stay untouched.
 * The one exception is a failure *between* the renames of a multi-file group, which still writes no receipt - the
 * next attempt therefore copies the whole group again from verified assets.
 *
 * [freeSpace] is a callback so JVM tests can decide the answer without filling a volume; production passes
 * [File.usableSpace]. A group's copy requires its declared bytes to be free before the first temp file is written.
 */
class BundledModelProvisioner(
    private val source: ModelAssetSource,
    private val store: EngineModelStore,
    private val asrManifest: EngineManifest,
    private val modelsManifest: BundledModelsManifest,
    private val layaDirectory: File,
    private val embeddingDirectory: File,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
) {

    /** The last bundle this instance materialized in this process; lets the defense-in-depth calls stay cheap. */
    @Volatile
    private var lastReadyBundleId: String? = null

    /**
     * Materializes everything needed for a session and returns the resulting state.
     *
     * This is called from the App's startup coroutine and, defensively, from the two model-loader threads before
     * they read their files. Synchronized because those callers race; the second call sees [lastReadyBundleId] and
     * returns without touching the ~863 MiB runtime copy or re-hashing the ASR store.
     */
    @Synchronized
    fun run(): ProvisioningState {
        val bundleId = modelsManifest.bundleId
        log(
            "P4_MODELS state=preparing stage=asr bytes=${modelsManifest.files.sumOf { it.bytes }} " +
                "bundle=$bundleId",
        )
        val asrFiles = try {
            validateAsrGroup()
        } catch (failure: Exception) {
            return failed(Stage.ASR, failure)
        }
        val layaFiles = try {
            validateGroup(LAYA_GROUP, LAYA_FILE_NAMES)
        } catch (failure: Exception) {
            return failed(Stage.LAYA, failure)
        }
        val embeddingFiles = try {
            validateGroup(EMBEDDING_GROUP, EMBEDDING_FILE_NAMES)
        } catch (failure: Exception) {
            return failed(Stage.EMBEDDING, failure)
        }
        if (lastReadyBundleId == bundleId && runtimeFilesCurrent(layaFiles, embeddingFiles)) {
            return ProvisioningState.Ready(bundleId)
        }

        try {
            provisionAsr(asrFiles)
        } catch (failure: Exception) {
            return failed(Stage.ASR, failure)
        }
        try {
            materialize(layaDirectory, layaFiles)
        } catch (failure: Exception) {
            return failed(Stage.LAYA, failure)
        }
        try {
            materialize(embeddingDirectory, embeddingFiles)
        } catch (failure: Exception) {
            return failed(Stage.EMBEDDING, failure)
        }

        lastReadyBundleId = bundleId
        log(
            "P4_MODELS state=ready stage=all bytes=${modelsManifest.files.sumOf { it.bytes }} bundle=$bundleId",
        )
        return ProvisioningState.Ready(bundleId)
    }

    /** Reuses the store's validated bundle when there is one, otherwise imports the packaged ASR files. */
    private fun provisionAsr(asrFiles: List<BundledModelFile>) {
        val active = runCatching { store.active() }.getOrNull()
        val reusable = active ?: runCatching { store.recover(asrManifest) }.getOrNull()
        if (reusable != null) {
            log(
                "P4_MODELS state=preparing stage=asr bytes=${asrFiles.sumOf { it.bytes }} " +
                    "bundle=${modelsManifest.bundleId} source=ENGINE_REUSED",
            )
            return
        }
        // The ASR store lives in the App's internal data area, the same volume as `filesDir`; `freeSpace` is asked
        // about the Laya directory because that is the reachable representative the caller hands us. Without this,
        // a full disk would surface only as the store's generic ENGINE_MISSING.
        if (freeSpace(layaDirectory) < asrFiles.sumOf { it.bytes }) throw IOException("ENOSPC")
        AssetEngineBundleProvisioning(source, store, asrManifest).import()
        log(
            "P4_MODELS state=preparing stage=asr bytes=${asrFiles.sumOf { it.bytes }} " +
                "bundle=${modelsManifest.bundleId} source=ENGINE_IMPORTED_ASSET",
        )
    }

    /**
     * Copies one group, or skips it when the receipt and every target size already match.
     *
     * Every source is streamed to a sibling temp file while its bytes and SHA-256 are checked, and only after the
     * whole group verified are the temp files renamed into place. A read/verify failure therefore cannot replace a
     * single working file; the receipt is written last, so a failed attempt stays retryable.
     */
    private fun materialize(directory: File, files: List<BundledModelFile>) {
        if (receiptMatches(directory) && files.all { targetMatches(directory, it) }) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("model directory unavailable")
        }
        val required = files.sumOf { it.bytes }
        if (freeSpace(directory) < required) throw IOException("ENOSPC")

        val staged = mutableListOf<Pair<File, File>>()
        try {
            files.forEach { file ->
                val target = targetFile(directory, file)
                val temp = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
                staged += temp to target
                copyVerified(file, temp)
            }
            staged.forEach { (temp, target) ->
                Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            }
        } finally {
            staged.forEach { (temp, _) -> temp.delete() }
        }
        writeReceipt(directory)
    }

    /** Streams [file] from the asset source into [target], then requires the declared byte count and SHA-256. */
    private fun copyVerified(file: BundledModelFile, target: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.open(file.path).use { input ->
            Files.newOutputStream(target.toPath()).use { output ->
                val buffer = ByteArray(1 shl 16)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= file.bytes) { "stream is larger than the declared size" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        }
        require(total == file.bytes) { "stream size does not match the manifest" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual.equals(file.sha256, ignoreCase = true)) { "stream SHA-256 does not match the manifest" }
    }

    private fun writeReceipt(directory: File) {
        val target = File(directory, RECEIPT_FILE_NAME)
        val temp = File(directory, ".$RECEIPT_FILE_NAME.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(modelsManifest.bundleId, Charsets.UTF_8)
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    private fun receiptMatches(directory: File): Boolean = runCatching {
        File(directory, RECEIPT_FILE_NAME).readText().trim() == modelsManifest.bundleId
    }.getOrDefault(false)

    private fun targetMatches(directory: File, file: BundledModelFile): Boolean {
        val target = targetFile(directory, file)
        return target.isFile && target.length() == file.bytes
    }

    private fun runtimeFilesCurrent(
        layaFiles: List<BundledModelFile>,
        embeddingFiles: List<BundledModelFile>,
    ): Boolean = receiptMatches(layaDirectory) && layaFiles.all { targetMatches(layaDirectory, it) } &&
        receiptMatches(embeddingDirectory) && embeddingFiles.all { targetMatches(embeddingDirectory, it) }

    private fun targetFile(directory: File, file: BundledModelFile): File =
        File(directory, file.path.substringAfter('/'))

    /**
     * The runtime set must be **exactly** the 15 files the build pins (7 ASR + 6 Laya + 2 Embedding). An empty or
     * reduced `files[]` must never read as a ready bundle - the Task 2 verifier deliberately allows that shape.
     */
    private fun validateAsrGroup(): List<BundledModelFile> {
        require(modelsManifest.files.all { it.group in KNOWN_GROUPS }) { "unknown bundled model group" }
        val entries = modelsManifest.files.filter { it.group == ASR_GROUP }
        val expectedPaths = asrManifest.artifacts.map { "$ASR_GROUP/${it.path}" }.toSet() +
            "$ASR_GROUP/engine-manifest.json"
        require(entries.size == expectedPaths.size) { "ASR group size does not match the engine manifest" }
        require(entries.map { it.path }.toSet() == expectedPaths) { "ASR group paths do not match the engine manifest" }
        asrManifest.artifacts.forEach { artifact ->
            val entry = entries.first { it.path == "$ASR_GROUP/${artifact.path}" }
            require(entry.bytes == artifact.bytes) { "ASR entry size does not match the engine manifest" }
            require(entry.sha256.equals(artifact.sha256, ignoreCase = true)) {
                "ASR entry SHA-256 does not match the engine manifest"
            }
        }
        return entries
    }

    private fun validateGroup(group: String, expectedFileNames: List<String>): List<BundledModelFile> {
        val entries = modelsManifest.files.filter { it.group == group }
        val expectedPaths = expectedFileNames.map { "$group/$it" }.toSet()
        require(entries.size == expectedPaths.size) { "$group group has an unexpected size" }
        require(entries.map { it.path }.toSet() == expectedPaths) { "$group group has unexpected paths" }
        return entries
    }

    private fun failed(stage: Stage, failure: Exception): ProvisioningState.Failed {
        val reason = when {
            noSpace(failure) -> MODELS_NO_SPACE
            stage == Stage.ASR -> MODELS_ASR_FAILED
            stage == Stage.LAYA -> MODELS_LAYA_FAILED
            else -> MODELS_EMBEDDING_FAILED
        }
        // Count/state only: no path and no model content reaches the log.
        log("P4_MODELS state=failed stage=${stage.logName} reason=$reason bundle=${modelsManifest.bundleId}")
        return ProvisioningState.Failed(reason)
    }

    private fun noSpace(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            val message = current.message?.lowercase()
            if (message != null && ("no space" in message || "enospc" in message || "not enough space" in message)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private enum class Stage(val logName: String) { ASR("asr"), LAYA("laya"), EMBEDDING("embedding") }

    companion object {
        /** The receipt file's name inside each materialized directory; its content is the manifest's bundle id. */
        const val RECEIPT_FILE_NAME = ".edith-model-bundle"

        const val MODELS_NO_SPACE = "MODELS_NO_SPACE"
        const val MODELS_ASR_FAILED = "MODELS_ASR_FAILED"
        const val MODELS_LAYA_FAILED = "MODELS_LAYA_FAILED"
        const val MODELS_EMBEDDING_FAILED = "MODELS_EMBEDDING_FAILED"

        private const val TAG = "EdithModels"

        private const val ASR_GROUP = "asr"
        private const val LAYA_GROUP = "laya"
        private const val EMBEDDING_GROUP = "embeddinggemma"
        private val KNOWN_GROUPS = setOf(ASR_GROUP, LAYA_GROUP, EMBEDDING_GROUP)

        /**
         * The six files the vendored Laya host reads at runtime (`tokenizer_config.json` is not read and is not
         * packaged). The build's manifest must name exactly these.
         */
        internal val LAYA_FILE_NAMES = listOf(
            "laya_ml_act_head_fp32.tflite",
            "laya_ml_calibration.json",
            "laya_ml_s256_embeds_wfp16.tflite",
            "token_embeddings.json",
            "token_embeddings_fp16.bin",
            "tokenizer.json",
        )

        /** The graph plus its SentencePiece vocabulary. */
        internal val EMBEDDING_FILE_NAMES = listOf(
            "embeddinggemma-300M_seq256_mixed-precision.tflite",
            "sentencepiece.model",
        )
    }
}
