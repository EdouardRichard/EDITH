package com.sheen.rokid.phone.model

import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.ValidatedModelBundle
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
 * The receipt makes the normal start cheap: when it names the manifest's `bundleId` and records the same
 * [BundledModelsManifest.manifestDigest] (a digest over every emitted file's path and SHA-256), and every target still
 * has the declared size, nothing is read. No whole-bundle re-hash runs on that path (spec §6.4). A changed manifest
 * changes the digest even if its id were not bumped, so an APK update always forces one fresh copy.
 *
 * **Migration from the pre-digest receipt.** Builds before this fix wrote the bundle id alone. Such a receipt passes
 * the id check but cannot prove the files match, so the first start after upgrading treats it as stale, copies once,
 * and rewrites the receipt with both values. Existing installs therefore keep working without manual action; they pay
 * one materialization pass (the ~863 MiB Laya/Embedding copy) on the first launch after the update, documented in the
 * 0.4 bundled-model evidence/report.
 *
 * Failures are mapped to a stable reason and never leave a half-written file behind: a source failure while copying
 * is detected while the target is still a temp file, so the old runtime files and the old receipt stay untouched.
 * The one exception is a failure *between* the renames of a multi-file group, which still writes no receipt - the
 * next attempt therefore copies the whole group again from verified assets.
 *
 * Disk space is refused **before the first write** when a first materialization is needed (plan Step 2: less than
 * 1.2 GiB free is `MODELS_NO_SPACE`). Without that early refusal a tight volume could accept the ASR import and the
 * Laya copy, write their receipts, and only then fail Embedding - a `Failed` App on a volume with no room left.
 * Later updates only need the bytes of the groups they rewrite, which the per-group checks enforce.
 *
 * [freeSpace] is a callback so JVM tests can decide the answer without filling a volume. The default is
 * [ModelStorageSpace.usableBytes], which resolves the nearest existing ancestor: a first install's EmbeddingGemma
 * directory does not exist until this class creates it, and `File.usableSpace` on an absent path would read 0 and
 * refuse the install for ever. Production passes the same probe.
 */
class BundledModelProvisioner(
    private val source: ModelAssetSource,
    private val store: EngineModelStore,
    private val asrManifest: EngineManifest,
    private val modelsManifest: BundledModelsManifest,
    private val layaDirectory: File,
    private val embeddingDirectory: File,
    private val freeSpace: (File) -> Long = { ModelStorageSpace.usableBytes(it) },
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
) {

    /** The last bundle this instance materialized in this process; lets the defense-in-depth calls stay cheap. */
    @Volatile
    private var lastReadyBundleId: String? = null

    /** The digest recorded beside the id in every receipt; see [BundledModelsManifest.manifestDigest]. */
    private val manifestDigest: String = modelsManifest.manifestDigest()

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

        // Decide what this pass must materialize *before* writing anything. A first run may consume the whole
        // bundled set; failing halfway (ASR + Laya copied and receipted, Embedding refused) leaves a Failed App on
        // a nearly full volume - exactly the state the plan's early 1.2 GiB refusal exists to prevent.
        val reusableAsr = reusableAsrBundle()
        val asrNeedsImport = reusableAsr == null
        val layaNeedsWork = needsMaterialization(layaDirectory, layaFiles)
        val embeddingNeedsWork = needsMaterialization(embeddingDirectory, embeddingFiles)
        if ((asrNeedsImport || layaNeedsWork || embeddingNeedsWork) &&
            !hasSpaceFor(
                asrFiles = asrFiles,
                layaFiles = layaFiles,
                embeddingFiles = embeddingFiles,
                asrNeedsImport = asrNeedsImport,
                layaNeedsWork = layaNeedsWork,
                embeddingNeedsWork = embeddingNeedsWork,
            )
        ) {
            return refusedForSpace()
        }

        try {
            provisionAsr(asrFiles, reusableAsr)
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

    /** The store's already-validated bundle, recovered from disk when this process has not activated one yet. */
    private fun reusableAsrBundle(): ValidatedModelBundle? =
        runCatching { store.active() }.getOrNull() ?: runCatching { store.recover(asrManifest) }.getOrNull()

    /**
     * The pre-write space gate.
     *
     * A first materialization (no receipt in either runtime directory) must have the plan's 1.2 GiB on the target
     * volume, and must have it *before* the ASR import writes anything. An update only needs the groups it rewrites,
     * so a volume with less headroom can still install a changed bundle.
     */
    private fun hasSpaceFor(
        asrFiles: List<BundledModelFile>,
        layaFiles: List<BundledModelFile>,
        embeddingFiles: List<BundledModelFile>,
        asrNeedsImport: Boolean,
        layaNeedsWork: Boolean,
        embeddingNeedsWork: Boolean,
    ): Boolean {
        val firstMaterialization = !hasReceipt(layaDirectory) && !hasReceipt(embeddingDirectory)
        // The ASR store lives in the App's internal data area, the same volume as `filesDir`; ask about the Laya
        // directory because it is the reachable representative of that volume the caller hands us.
        val internalNeeded = (if (asrNeedsImport) asrFiles.sumOf { it.bytes } else 0L) +
            (if (layaNeedsWork) layaFiles.sumOf { it.bytes } else 0L)
        val internalRequired = if (firstMaterialization) TOTAL_FREE_SPACE_BYTES else internalNeeded
        if (internalRequired > 0L && freeSpace(layaDirectory) < internalRequired) return false
        if (embeddingNeedsWork) {
            val embeddingRequired =
                if (firstMaterialization) TOTAL_FREE_SPACE_BYTES else embeddingFiles.sumOf { it.bytes }
            if (freeSpace(embeddingDirectory) < embeddingRequired) return false
        }
        return true
    }

    /** Reuses the store's validated bundle when there is one, otherwise imports the packaged ASR files. */
    private fun provisionAsr(asrFiles: List<BundledModelFile>, reusable: ValidatedModelBundle?) {
        if (reusable != null) {
            log(
                "P4_MODELS state=preparing stage=asr bytes=${asrFiles.sumOf { it.bytes }} " +
                    "bundle=${modelsManifest.bundleId} source=ENGINE_REUSED",
            )
            return
        }
        // Kept as a per-group check for update passes; a first materialization was already gated by [hasSpaceFor].
        // A full disk must surface as MODELS_NO_SPACE, not as the store's generic ENGINE_MISSING.
        if (freeSpace(layaDirectory) < asrFiles.sumOf { it.bytes }) throw IOException(NO_SPACE_MESSAGE)
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
        val required = files.sumOf { it.bytes }
        // Per-group check, which is what an update needs; a first materialization also passed [hasSpaceFor].
        if (freeSpace(directory) < required) throw IOException(NO_SPACE_MESSAGE)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            // `mkdirs` only reports false. When the volume filled up, that false is still an ENOSPC for the user.
            // The callback resolves the nearest existing ancestor, so a directory that is still missing after
            // `mkdirs` failed is not itself read as zero bytes free (only a genuinely full volume becomes NO_SPACE).
            if (freeSpace(directory) < required) throw IOException(NO_SPACE_MESSAGE)
            throw IOException("model directory unavailable")
        }

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
            // Two lines: the bundle id and the digest over the emitted file paths/SHA-256 values.
            temp.writeText("${modelsManifest.bundleId}\n$manifestDigest\n", Charsets.UTF_8)
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    private fun receiptMatches(directory: File): Boolean = runCatching {
        val lines = File(directory, RECEIPT_FILE_NAME).readText()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // A pre-fix receipt holds only the id and is intentionally not accepted: it cannot prove the file digests.
        lines == listOf(modelsManifest.bundleId, manifestDigest)
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

    /** True when the receipt names the current bundle and every target file still has the declared size. */
    private fun needsMaterialization(directory: File, files: List<BundledModelFile>): Boolean =
        !(receiptMatches(directory) && files.all { targetMatches(directory, it) })

    /** Whether the directory already carries any receipt, current or stale; a stale receipt means "update". */
    private fun hasReceipt(directory: File): Boolean = File(directory, RECEIPT_FILE_NAME).isFile

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
            isNoSpaceFailure(failure) -> MODELS_NO_SPACE
            stage == Stage.ASR -> MODELS_ASR_FAILED
            stage == Stage.LAYA -> MODELS_LAYA_FAILED
            else -> MODELS_EMBEDDING_FAILED
        }
        // Count/state only: no path and no model content reaches the log.
        log("P4_MODELS state=failed stage=${stage.logName} reason=$reason bundle=${modelsManifest.bundleId}")
        return ProvisioningState.Failed(reason)
    }

    /** The pre-write refusal: nothing has been written or imported when this is returned. */
    private fun refusedForSpace(): ProvisioningState.Failed {
        log("P4_MODELS state=failed stage=space reason=$MODELS_NO_SPACE bundle=${modelsManifest.bundleId}")
        return ProvisioningState.Failed(MODELS_NO_SPACE)
    }

    private enum class Stage(val logName: String) { ASR("asr"), LAYA("laya"), EMBEDDING("embedding") }

    companion object {
        /** The receipt file's name inside each materialized directory; it holds the bundle id then the digest. */
        const val RECEIPT_FILE_NAME = ".edith-model-bundle"

        const val MODELS_NO_SPACE = "MODELS_NO_SPACE"
        const val MODELS_ASR_FAILED = "MODELS_ASR_FAILED"
        const val MODELS_LAYA_FAILED = "MODELS_LAYA_FAILED"
        const val MODELS_EMBEDDING_FAILED = "MODELS_EMBEDDING_FAILED"

        /**
         * The free space a first materialization requires before it may write its first byte (plan Step 2: a
         * `StatFs` reading below 1.2 GiB is `MODELS_NO_SPACE`). The bundled set is ~1.11 GiB; the rest is headroom
         * for the temp files that coexist with the final ones and for the ASR store's own staging directory.
         */
        internal const val TOTAL_FREE_SPACE_BYTES = 1_288_490_189L // 1.2 GiB

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
