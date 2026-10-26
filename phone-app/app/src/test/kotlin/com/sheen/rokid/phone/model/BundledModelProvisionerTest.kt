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

    private inner class Fixture(val id: String = bundleId) {
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
        val embeddingDirectory: File = temporary.newFolder("embedding")

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

    private fun assertNoReceipt(directory: File) {
        assertFalse("no receipt may be written on failure", File(directory, receiptName).exists())
    }

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
        assertEquals(bundleId, fixture.receipt(fixture.layaDirectory).readText().trim())
        assertEquals(bundleId, fixture.receipt(fixture.embeddingDirectory).readText().trim())
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
        assertEquals(bundleId, fixture.receipt(fixture.layaDirectory).readText().trim())
        assertEquals(bundleId, fixture.receipt(fixture.embeddingDirectory).readText().trim())
        assertTrue("the file must have been rewritten", target.lastModified() != 1_000_000L)
    }

    @Test
    fun aDifferentBundleIdForcesARecopyEvenWhenTheFileNamesAreTheSame() {
        val fixture = Fixture()
        fixture.provisioner().run()
        val target = File(fixture.layaDirectory, layaFileNames.first())
        assertTrue(target.setLastModified(1_000_000L))
        val source = RecordingSource(fixture.assetBytes)

        val result = fixture.restarted(
            source = source,
            modelsManifest = fixture.modelsManifest(bundleId = "new-bundle"),
        ).run()

        assertEquals(ProvisioningState.Ready("new-bundle"), result)
        assertEquals("new-bundle", fixture.receipt(fixture.layaDirectory).readText().trim())
        assertEquals("new-bundle", fixture.receipt(fixture.embeddingDirectory).readText().trim())
        assertTrue("an APK bundle change must re-materialize the runtime files",
            source.opened.contains("laya/${layaFileNames.first()}"))
        assertTrue(target.lastModified() != 1_000_000L)
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
