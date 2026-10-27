package com.sheen.rokid.phone.model

import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.ModelArtifact
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 0.4 Task 3 / spec §6.3: first-start materialization of the Laya and EmbeddingGemma files, with a bundle receipt
 * that makes the second start free and an APK update re-copy.
 *
 * The fixture builds a **complete, real** `models-manifest.json` shape (15 entries: 7 ASR incl. the engine manifest,
 * 6 Laya, 2 Embedding) and imports the ASR half through a real [EngineModelStore], so every size and digest is
 * actually checked. The bytes are tiny; the code paths are the production ones.
 */
class BundledModelProvisionerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val roles = listOf("encoder", "decoder", "joiner", "tokens", "vad", "speaker")

    private val layaFileNames = listOf(
        "laya_ml_act_head_fp32.tflite",
        "laya_ml_calibration.json",
        "laya_ml_s256_embeds_wfp16.tflite",
        "token_embeddings.json",
        "token_embeddings_fp16.bin",
        "tokenizer.json",
    )

    private val embeddingFileNames = listOf(
        "embeddinggemma-300M_seq256_mixed-precision.tflite",
        "sentencepiece.model",
    )

    private val bundleId = "bundle-one"
    private val receiptName = ".edith-model-bundle"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun entry(group: String, role: String, fileName: String, bytes: ByteArray): BundledModelFile =
        BundledModelFile(group, role, "$group/$fileName", bytes.size.toLong(), sha256(bytes))

    private inner class Fixture(
        val id: String = bundleId,
        embeddingDirectoryOverride: File? = null,
    ) {
        val asrBytes: Map<String, ByteArray> = roles.associateWith { role -> "$role-asr-model".toByteArray() }
        val layaBytes: Map<String, ByteArray> = layaFileNames.associateWith { name -> "$name-laya".toByteArray() }
        val embeddingBytes: Map<String, ByteArray> = embeddingFileNames.associateWith { name ->
            "$name-embedding".toByteArray()
        }
        val engineManifestBytes: ByteArray = """{"schemaVersion":1}""".toByteArray()

        val asrManifest: EngineManifest = EngineManifest("sherpa-onnx-1.13.8", roles.map { role ->
            val bytes = asrBytes.getValue(role)
            ModelArtifact(role, "$role.bin", bytes.size.toLong(), sha256(bytes),
                "https://example.invalid/$role", "r1", "licenses/$role.txt")
        })

        fun modelsManifest(
            bundleId: String = id,
            includeAsr: Boolean = true,
            includeLaya: Boolean = true,
            includeEmbedding: Boolean = true,
        ): BundledModelsManifest {
            val files = mutableListOf<BundledModelFile>()
            if (includeAsr) {
                roles.forEach { role ->
                    files += entry("asr", role, "$role.bin", asrBytes.getValue(role))
                }
                files += entry("asr", "manifest", "engine-manifest.json", engineManifestBytes)
            }
            if (includeLaya) layaFileNames.forEach { name -> files += entry("laya", role = name, fileName = name,
                bytes = layaBytes.getValue(name)) }
            if (includeEmbedding) embeddingFileNames.forEach { name ->
                files += entry("embeddinggemma", role = name, fileName = name, bytes = embeddingBytes.getValue(name))
            }
            return BundledModelsManifest(bundleId, files)
        }

        val assetBytes: Map<String, ByteArray> = buildMap {
            roles.forEach { role -> put("asr/$role.bin", asrBytes.getValue(role)) }
            put("asr/engine-manifest.json", engineManifestBytes)
            layaFileNames.forEach { name -> put("laya/$name", layaBytes.getValue(name)) }
            embeddingFileNames.forEach { name -> put("embeddinggemma/$name", embeddingBytes.getValue(name)) }
        }

        val storeRoot: Path = temporary.newFolder("asr-store").toPath()
        var store: EngineModelStore = EngineModelStore(storeRoot)
        val layaDirectory: File = temporary.newFolder("laya")

        /**
         * Normally an existing directory (the update/restart tests); a test may name a path that does not exist yet
         * to reproduce the production first install, where only the provisioner creates it.
         */
        val embeddingDirectory: File = embeddingDirectoryOverride ?: temporary.newFolder("embedding")

        fun preimportAsr() {
            store.import(asrManifest, roles.associateWith { role ->
                { ByteArrayInputStream(asrBytes.getValue(role)) }
            })
        }

        fun provisioner(
            source: ModelAssetSource = RecordingSource(assetBytes),
            modelsManifest: BundledModelsManifest = modelsManifest(),
            freeSpace: (File) -> Long = { Long.MAX_VALUE },
        ): BundledModelProvisioner = BundledModelProvisioner(
            source = source,
            store = store,
            asrManifest = asrManifest,
            modelsManifest = modelsManifest,
            layaDirectory = layaDirectory,
            embeddingDirectory = embeddingDirectory,
            freeSpace = freeSpace,
            log = {},
        )

        /** A fresh provisioner over a fresh store on the same directory, like the next process after a restart. */
        fun restarted(
            source: ModelAssetSource = RecordingSource(assetBytes),
            modelsManifest: BundledModelsManifest = modelsManifest(),
            freeSpace: (File) -> Long = { Long.MAX_VALUE },
        ): BundledModelProvisioner {
            store = EngineModelStore(storeRoot)
            return provisioner(source, modelsManifest, freeSpace)
        }

        fun assertFiles(directory: File, contents: Map<String, ByteArray>) {
            contents.forEach { (name, bytes) ->
                val file = File(directory, name)
                assertTrue("$name must exist", file.isFile)
                assertEquals("$name bytes", bytes.toList(), file.readBytes().toList())
            }
        }

        fun receipt(directory: File): File = File(directory, receiptName)

        /**
         * Replaces the embedding directory with a regular file, so the next materialization's `mkdirs` fails for a
         * reason that is not "the directory already exists".
         */
        fun replaceEmbeddingDirectoryWithFile() {
            embeddingDirectory.listFiles().orEmpty().forEach { it.delete() }
            check(embeddingDirectory.delete()) { "embedding directory must be empty to replace it" }
            embeddingDirectory.writeText("not-a-directory")
        }
    }

    private class RecordingSource(
        private val files: Map<String, ByteArray>,
        private val overrides: Map<String, ByteArray> = emptyMap(),
        private val failMidStream: String? = null,
        private val failure: Throwable = IOException("synthetic copy interruption"),
    ) : ModelAssetSource {
        val opened = mutableListOf<String>()

        override fun open(path: String): InputStream {
            opened += path
            val bytes = overrides[path] ?: files[path] ?: throw IOException("no asset $path")
            if (path == failMidStream) {
                return FailingInputStream(bytes.copyOfRange(0, (bytes.size / 2).coerceAtLeast(1)), failure)
            }
            return ByteArrayInputStream(bytes)
        }
    }

    /** Serves a prefix and then throws, exactly like a storage or asset read failing mid-copy. */
    private class FailingInputStream(
        private val prefix: ByteArray,
        private val failure: Throwable,
    ) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index < prefix.size) prefix[index++].toInt() and 255 else throw failure
    }

    /**
     * A stand-in for `android.system.ErrnoException`: on Android a copy can fail with a bare errno-carrying
     * exception whose class is not an `IOException` and whose message is not the English ENOSPC text, so the
     * mapper must recognize `errno == 28` as well as the message.
     */
    private class ErrnoException(val errno: Int) : IOException("write failed")

    private fun assertNoReceipt(directory: File) {
        assertFalse("no receipt may be written on failure", File(directory, receiptName).exists())
    }

    /** The receipt is two non-blank lines: the bundle id and the digest over the emitted file paths/SHA-256 values. */
    private fun receiptLines(directory: File): List<String> = File(directory, receiptName).readText()
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun assertNoTempFiles(directory: File) {
        val leftovers = directory.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("no temp file may be left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun firstRunCopiesLayaAndEmbeddingAndWritesReceipts() {
        val fixture = Fixture()

        val result = fixture.provisioner().run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        fixture.assertFiles(fixture.layaDirectory, fixture.layaBytes)
        fixture.assertFiles(fixture.embeddingDirectory, fixture.embeddingBytes)
        assertEquals(
            listOf(bundleId, fixture.modelsManifest().manifestDigest()),
            receiptLines(fixture.layaDirectory),
        )
        assertEquals(
            listOf(bundleId, fixture.modelsManifest().manifestDigest()),
            receiptLines(fixture.embeddingDirectory),
        )
        assertNotNull("the ASR half must be activated from the packaged assets", fixture.store.active())
        assertNoTempFiles(fixture.layaDirectory)
        assertNoTempFiles(fixture.embeddingDirectory)
    }

    @Test
    fun secondRunSkipsAndDoesNotRewriteWhenReceiptAndSizesMatch() {
        val fixture = Fixture()
        fixture.provisioner().run()
        val untouched = File(fixture.layaDirectory, layaFileNames.first())
        assertTrue(untouched.setLastModified(1_000_000L))
        val secondSource = RecordingSource(fixture.assetBytes)

        val result = fixture.restarted(secondSource).run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        assertEquals("a matching receipt must skip the copy", 1_000_000L, untouched.lastModified())
        assertTrue("a skip must not open any asset: ${secondSource.opened}", secondSource.opened.isEmpty())
    }

    @Test
    fun aStaleReceiptForcesARecopy() {
        val fixture = Fixture()
        fixture.provisioner().run()
        File(fixture.layaDirectory, receiptName).writeText("some-older-bundle")
        File(fixture.embeddingDirectory, receiptName).writeText("some-older-bundle")
        val target = File(fixture.layaDirectory, layaFileNames.first())
        assertTrue(target.setLastModified(1_000_000L))
        val source = RecordingSource(fixture.assetBytes)

        val result = fixture.restarted(source).run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        assertTrue("stale receipt must re-read the runtime assets", source.opened.contains("laya/${layaFileNames.first()}"))
        assertEquals(listOf(bundleId, fixture.modelsManifest().manifestDigest()),
            receiptLines(fixture.layaDirectory))
        assertEquals(listOf(bundleId, fixture.modelsManifest().manifestDigest()),
            receiptLines(fixture.embeddingDirectory))
        assertTrue("the file must have been rewritten", target.lastModified() != 1_000_000L)
    }

    @Test
    fun aDifferentBundleIdForcesARecopyEvenWhenTheFileNamesAreTheSame() {
        val fixture = Fixture()
        fixture.provisioner().run()
        val target = File(fixture.layaDirectory, layaFileNames.first())
        assertTrue(target.setLastModified(1_000_000L))
        val source = RecordingSource(fixture.assetBytes)
        val nextManifest = fixture.modelsManifest(bundleId = "new-bundle")

        val result = fixture.restarted(
            source = source,
            modelsManifest = nextManifest,
        ).run()

        assertEquals(ProvisioningState.Ready("new-bundle"), result)
        assertEquals(listOf("new-bundle", nextManifest.manifestDigest()), receiptLines(fixture.layaDirectory))
        assertEquals(listOf("new-bundle", nextManifest.manifestDigest()), receiptLines(fixture.embeddingDirectory))
        assertTrue("an APK bundle change must re-materialize the runtime files",
            source.opened.contains("laya/${layaFileNames.first()}"))
        assertTrue(target.lastModified() != 1_000_000L)
    }

    @Test
    fun aChangedFileDigestForcesARecopyEvenWhenTheBundleIdIsUnchanged() {
        // The whole-branch finding: receipts used to compare only the manually pinned id, so a new APK whose file
        // digest changed but whose id did not would silently keep the old Laya weights. The manifest digest recorded
        // in the receipt must close that hole even independently of the build's id check.
        val fixture = Fixture()
        fixture.provisioner().run()
        val name = layaFileNames.first()
        val original = fixture.layaBytes.getValue(name)
        val changed = original.copyOf().also { it[0] = (it[0].toInt() + 1).toByte() }
        val changedManifest = fixture.modelsManifest().let { base ->
            BundledModelsManifest(
                base.bundleId,
                base.files.map { file ->
                    if (file.path == "laya/$name") file.copy(sha256 = sha256(changed)) else file
                },
            )
        }
        val source = RecordingSource(fixture.assetBytes, overrides = mapOf("laya/$name" to changed))

        val result = fixture.restarted(source, modelsManifest = changedManifest).run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        assertTrue("a changed file digest must re-copy even when bundleId did not change: ${source.opened}",
            source.opened.contains("laya/$name"))
        assertEquals("the target must hold the newly pinned bytes", changed.toList(),
            File(fixture.layaDirectory, name).readBytes().toList())
    }

    @Test
    fun aBundleIdOnlyReceiptFromAnOlderBuildIsUpgradedByOneRecopy() {
        val fixture = Fixture()
        fixture.provisioner().run()
        val name = layaFileNames.first()
        val target = File(fixture.layaDirectory, name)
        assertTrue(target.setLastModified(1_000_000L))
        // Receipts written by the pre-fix build contain the bundle id alone; they cannot prove the file digests.
        fixture.receipt(fixture.layaDirectory).writeText(bundleId)
        fixture.receipt(fixture.embeddingDirectory).writeText(bundleId)
        val source = RecordingSource(fixture.assetBytes)

        val result = fixture.restarted(source).run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        assertTrue("a legacy id-only receipt must re-materialize once: ${source.opened}",
            source.opened.contains("laya/$name"))
        assertTrue("the legacy receipt's copy must be replaced", target.lastModified() != 1_000_000L)
        assertEquals(
            "the upgraded receipt records the id and the manifest digest",
            listOf(bundleId, fixture.modelsManifest().manifestDigest()),
            receiptLines(fixture.layaDirectory),
        )
    }

    @Test
    fun anInterruptedCopyLeavesOldFilesAndDoesNotWriteReceipt() {
        val fixture = Fixture()
        val oldTarget = File(fixture.layaDirectory, layaFileNames.first())
        oldTarget.writeText("old-working-file")
        val failingSource = RecordingSource(
            files = fixture.assetBytes,
            failMidStream = "laya/${layaFileNames[1]}",
        )

        val result = fixture.provisioner(source = failingSource).run()

        assertEquals(ProvisioningState.Failed("MODELS_LAYA_FAILED"), result)
        assertEquals("the old runtime file must survive a failed materialization", "old-working-file",
            oldTarget.readText())
        assertNoReceipt(fixture.layaDirectory)
        assertNoReceipt(fixture.embeddingDirectory)
        assertNoTempFiles(fixture.layaDirectory)
    }

    @Test
    fun aWrongSizeAssetFailsTheLayaStage() {
        val fixture = Fixture()
        val name = layaFileNames.first()
        val wrong = "too-long-for-the-declaration".toByteArray()
        val source = RecordingSource(fixture.assetBytes, overrides = mapOf("laya/$name" to wrong))

        val result = fixture.provisioner(source = source).run()

        assertEquals(ProvisioningState.Failed("MODELS_LAYA_FAILED"), result)
        assertFalse(File(fixture.layaDirectory, name).exists())
        assertNoReceipt(fixture.layaDirectory)
        assertNoTempFiles(fixture.layaDirectory)
    }

    @Test
    fun aWrongShaAssetFailsTheLayaStage() {
        val fixture = Fixture()
        val name = layaFileNames.first()
        val declared = fixture.layaBytes.getValue(name)
        val sameLengthDifferent = ByteArray(declared.size) { declared[it].inc() }
        val source = RecordingSource(fixture.assetBytes, overrides = mapOf("laya/$name" to sameLengthDifferent))

        val result = fixture.provisioner(source = source).run()

        assertEquals(ProvisioningState.Failed("MODELS_LAYA_FAILED"), result)
        assertFalse(File(fixture.layaDirectory, name).exists())
        assertNoReceipt(fixture.layaDirectory)
    }

    @Test
    fun aMissingLayaGroupFailsBeforeAnythingIsCopied() {
        val fixture = Fixture()
        val manifest = fixture.modelsManifest(includeLaya = false)

        val result = fixture.provisioner(modelsManifest = manifest).run()

        assertEquals(ProvisioningState.Failed("MODELS_LAYA_FAILED"), result)
        assertNull("manifest validation must happen before the ASR import", fixture.store.active())
        assertNoReceipt(fixture.layaDirectory)
        assertNoReceipt(fixture.embeddingDirectory)
    }

    @Test
    fun aMissingEmbeddingGroupFailsTheEmbeddingStage() {
        val fixture = Fixture()
        val manifest = fixture.modelsManifest(includeEmbedding = false)

        val result = fixture.provisioner(modelsManifest = manifest).run()

        assertEquals(ProvisioningState.Failed("MODELS_EMBEDDING_FAILED"), result)
        assertTrue("a manifest-level refusal must copy nothing at all",
            layaFileNames.none { File(fixture.layaDirectory, it).isFile })
        assertNoReceipt(fixture.layaDirectory)
        assertNoReceipt(fixture.embeddingDirectory)
    }

    @Test
    fun anEmptyManifestIsRefusedInsteadOfPassingAsAReadyBundle() {
        val fixture = Fixture()

        val result = fixture.provisioner(modelsManifest = BundledModelsManifest(bundleId, emptyList())).run()

        assertEquals(ProvisioningState.Failed("MODELS_ASR_FAILED"), result)
        assertNull(fixture.store.active())
    }

    @Test
    fun diskFullMapsToModelsNoSpace() {
        val fixture = Fixture()
        fixture.preimportAsr()

        val result = fixture.provisioner(freeSpace = { 0L }).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertTrue("nothing may be copied when there is no space",
            fixture.layaDirectory.listFiles().orEmpty().none { it.isFile })
        assertNoReceipt(fixture.layaDirectory)
    }

    @Test
    fun diskFullDuringTheAsrImportAlsoMapsToModelsNoSpace() {
        val fixture = Fixture()

        val result = fixture.provisioner(freeSpace = { 0L }).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertNull("a no-space refusal must not activate an ASR bundle", fixture.store.active())
        assertNoReceipt(fixture.layaDirectory)
    }

    @Test
    fun aDiskBelowTheDocumentedFirstRunTotalIsRefusedBeforeAnythingIsCopied() {
        val fixture = Fixture()
        val source = RecordingSource(fixture.assetBytes)
        // 1.05 GiB: more than any single group needs, less than the documented 1.2 GiB a first run may consume.
        val lessThanRequired = BundledModelProvisioner.TOTAL_FREE_SPACE_BYTES * 105 / 120

        val result = fixture.provisioner(source = source, freeSpace = { lessThanRequired }).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertTrue("a refused first run must not open a single asset: ${source.opened}", source.opened.isEmpty())
        assertNull("a refused first run must not import or activate an ASR bundle", fixture.store.active())
        assertTrue("a refused first run must not create a temp file or receipt",
            fixture.layaDirectory.listFiles().orEmpty().isEmpty())
        assertTrue("a refused first run must not create a temp file or receipt",
            fixture.embeddingDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun spaceForTheAsrGroupAloneIsStillRefusedBeforeAnyCopy() {
        val fixture = Fixture()
        val source = RecordingSource(fixture.assetBytes)

        // ~242 MiB is enough for the real 241 MB ASR group but far below the whole first-run materialization.
        val result = fixture.provisioner(source = source, freeSpace = { 242L * 1024 * 1024 }).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertTrue("the early refusal must precede the ASR import: ${source.opened}", source.opened.isEmpty())
        assertNull("a refused first run must not import or activate an ASR bundle", fixture.store.active())
        assertNoReceipt(fixture.layaDirectory)
        assertNoReceipt(fixture.embeddingDirectory)
    }

    @Test
    fun noSpaceWhileImportingTheAsrAssetsMapsToModelsNoSpace() {
        val fixture = Fixture()
        val source = RecordingSource(
            files = fixture.assetBytes,
            failMidStream = "asr/encoder.bin",
            failure = IOException("No space left on device"),
        )

        val result = fixture.provisioner(source = source).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertNull("a full disk during the import must leave no active bundle", fixture.store.active())
        assertTrue("the Laya stage must not run after a failed ASR import",
            fixture.layaDirectory.listFiles().orEmpty().isEmpty())
        assertNoReceipt(fixture.embeddingDirectory)
    }

    @Test
    fun anErrnoNoSpaceWhileCopyingALayaFileMapsToModelsNoSpaceAndKeepsOldFiles() {
        val fixture = Fixture()
        val oldTarget = File(fixture.layaDirectory, layaFileNames.first())
        oldTarget.writeText("old-working-file")
        fixture.receipt(fixture.layaDirectory).writeText("older-bundle")
        val source = RecordingSource(
            files = fixture.assetBytes,
            failMidStream = "laya/${layaFileNames[1]}",
            failure = ErrnoException(28),
        )

        val result = fixture.provisioner(source = source).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertEquals("the old runtime file must survive a full disk", "old-working-file", oldTarget.readText())
        assertEquals("the old receipt must survive a failed update", "older-bundle",
            fixture.receipt(fixture.layaDirectory).readText().trim())
        assertNoReceipt(fixture.embeddingDirectory)
        assertNoTempFiles(fixture.layaDirectory)
    }

    @Test
    fun anErrnoPermissionFailureIsNotReportedAsNoSpace() {
        val fixture = Fixture()
        val source = RecordingSource(
            files = fixture.assetBytes,
            failMidStream = "laya/${layaFileNames.first()}",
            failure = ErrnoException(13),
        )

        val result = fixture.provisioner(source = source).run()

        assertEquals("only ENOSPC may become MODELS_NO_SPACE", ProvisioningState.Failed("MODELS_LAYA_FAILED"), result)
    }

    @Test
    fun noSpaceWhileCopyingAnEmbeddingFileMapsToModelsNoSpaceAndKeepsTheOldBundle() {
        val fixture = Fixture()
        val oldTarget = File(fixture.embeddingDirectory, embeddingFileNames.first())
        oldTarget.writeText("old-embedding-file")
        fixture.receipt(fixture.embeddingDirectory).writeText("older-bundle")
        val source = RecordingSource(
            files = fixture.assetBytes,
            failMidStream = "embeddinggemma/${embeddingFileNames[1]}",
            failure = IOException("No space left on device"),
        )

        // An update (a receipt exists from the previous bundle) needs only the bytes it rewrites - not the whole
        // first-run 1.2 GiB headroom - and still maps a full disk to MODELS_NO_SPACE.
        val result = fixture.provisioner(source = source, freeSpace = { 1_000_000_000L }).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertEquals("the old runtime file must survive a full disk", "old-embedding-file", oldTarget.readText())
        assertEquals("the old receipt must survive a failed update", "older-bundle",
            fixture.receipt(fixture.embeddingDirectory).readText().trim())
        assertNoTempFiles(fixture.embeddingDirectory)
    }

    @Test
    fun aNonNoSpaceAsrFailureStillReportsModelsAsrFailed() {
        val fixture = Fixture()
        val source = RecordingSource(
            files = fixture.assetBytes,
            failMidStream = "asr/vad.bin",
            failure = IOException("asset stream broke"),
        )

        val result = fixture.provisioner(source = source).run()

        assertEquals("a non-ENOSPC import failure must keep its group reason",
            ProvisioningState.Failed("MODELS_ASR_FAILED"), result)
    }

    @Test
    fun anAlreadyMaterializedBundleIsReadyEvenWithoutFreeSpace() {
        val fixture = Fixture()
        fixture.provisioner().run()
        val secondSource = RecordingSource(fixture.assetBytes)

        val result = fixture.restarted(source = secondSource, freeSpace = { 0L }).run()

        assertEquals("a ready install must not fail because the disk later filled up",
            ProvisioningState.Ready(bundleId), result)
        assertTrue("a skip must not open any asset: ${secondSource.opened}", secondSource.opened.isEmpty())
    }

    @Test
    fun aFirstRunIsAlsoGatedOnTheEmbeddingVolumeBeforeTheAsrImport() {
        val fixture = Fixture()
        val source = RecordingSource(fixture.assetBytes)
        val space = { file: File ->
            if (file == fixture.embeddingDirectory) {
                BundledModelProvisioner.TOTAL_FREE_SPACE_BYTES * 105 / 120
            } else {
                Long.MAX_VALUE
            }
        }

        val result = fixture.provisioner(source = source, freeSpace = space).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertTrue("the embedding-volume refusal must precede every copy: ${source.opened}", source.opened.isEmpty())
        assertNull("a refused first run must not import or activate an ASR bundle", fixture.store.active())
    }

    @Test
    fun aMissingEmbeddingDirectoryOnFirstRunDoesNotReadAsNoSpace() {
        // Production on a fresh install: `filesDir` exists, but `<externalFilesDir>/models/embeddinggemma` has
        // never been created. Only the provisioner creates it, so the space gate runs before the first `mkdirs`.
        val embeddingDirectory = File(temporary.root, "external/models/embeddinggemma")
        val fixture = Fixture(embeddingDirectoryOverride = embeddingDirectory)
        assertFalse("the provisioner must create this directory itself", embeddingDirectory.exists())

        // Production-shaped reading: the volume is probed from the nearest existing ancestor, never from the absent
        // target itself.
        val result = fixture.provisioner(freeSpace = ModelStorageSpace::usableBytes).run()

        assertEquals(
            "a target directory that does not exist yet is not a full disk",
            ProvisioningState.Ready(bundleId),
            result,
        )
        fixture.assertFiles(embeddingDirectory, fixture.embeddingBytes)
        assertEquals(
            listOf(bundleId, fixture.modelsManifest().manifestDigest()),
            receiptLines(embeddingDirectory),
        )
        assertNoTempFiles(embeddingDirectory)
    }

    @Test
    fun aMissingEmbeddingDirectoryThatCannotBeCreatedKeepsTheGroupReason() {
        // The parent is a regular file, so `mkdirs` fails on a directory that does not exist while the volume itself
        // has plenty of room. That failure must stay an Embedding failure, not be blamed on free space.
        val blockingParent = temporary.newFile("embedding-parent-is-a-file")
        val embeddingDirectory = File(blockingParent, "models/embeddinggemma")
        val fixture = Fixture(embeddingDirectoryOverride = embeddingDirectory)
        assertFalse(embeddingDirectory.exists())

        val result = fixture.provisioner(freeSpace = ModelStorageSpace::usableBytes).run()

        assertEquals(
            "only a genuinely full volume may be reported as MODELS_NO_SPACE",
            ProvisioningState.Failed("MODELS_EMBEDDING_FAILED"),
            result,
        )
    }

    @Test
    fun aDirectoryCreationFailureOnAFillingVolumeMapsToModelsNoSpace() {
        val fixture = Fixture()
        fixture.provisioner().run()
        fixture.replaceEmbeddingDirectoryWithFile()
        val source = RecordingSource(fixture.assetBytes)
        var readings = 0

        // The gate and the pre-copy check see room (readings 1-2); the volume fills up before `mkdirs`, whose
        // false return carries no cause of its own.
        val result = fixture.restarted(source = source, freeSpace = {
            readings++
            if (readings <= 2) Long.MAX_VALUE else 0L
        }).run()

        assertEquals(ProvisioningState.Failed("MODELS_NO_SPACE"), result)
        assertTrue("the unusable embedding path must stay untouched", fixture.embeddingDirectory.isFile)
        assertEquals("an update that only needs embedding must not re-open Laya: ${source.opened}",
            emptyList<String>(), source.opened)
    }

    @Test
    fun aDirectoryCreationFailureWithSpaceAvailableKeepsTheGroupReason() {
        val fixture = Fixture()
        fixture.provisioner().run()
        fixture.replaceEmbeddingDirectoryWithFile()

        val result = fixture.restarted(freeSpace = { Long.MAX_VALUE }).run()

        assertEquals(
            "only a full volume may become MODELS_NO_SPACE",
            ProvisioningState.Failed("MODELS_EMBEDDING_FAILED"),
            result,
        )
    }

    @Test
    fun asrImportIsSkippedWhenTheStoreAlreadyMatches() {
        val fixture = Fixture()
        fixture.preimportAsr()
        val activeBefore = fixture.store.active()
        val source = RecordingSource(fixture.assetBytes - fixture.assetBytes.filterKeys { it.startsWith("asr/") }.keys)

        val result = fixture.provisioner(source = source).run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        assertTrue("a matching active bundle must not trigger the ASR import: ${source.opened}",
            source.opened.none { it.startsWith("asr/") })
        assertEquals(activeBefore?.directory, fixture.store.active()?.directory)
    }

    @Test
    fun asrImportIsAttemptedWhenTheStoreHasNoBundle() {
        val fixture = Fixture()
        val source = RecordingSource(fixture.assetBytes)

        val result = fixture.provisioner(source = source).run()

        assertEquals(ProvisioningState.Ready(bundleId), result)
        assertNotNull("the packaged ASR bundle must be imported", fixture.store.active())
        assertEquals("every ASR artifact is streamed from asr/<path>",
            roles.map { "asr/$it.bin" }.toSet(), source.opened.filter { it.startsWith("asr/") }.toSet())
    }
}
