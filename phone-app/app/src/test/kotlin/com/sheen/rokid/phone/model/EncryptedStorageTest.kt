package com.sheen.rokid.phone.model
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.phone.storage.BlobKeys
import com.sheen.rokid.phone.storage.BlobFile
import com.sheen.rokid.phone.storage.BlobCipher
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.storage.AndroidNamespaceStorage
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.AndroidEncryptedBlobStore
import com.sheen.rokid.phone.storage.AndroidSpeakerProfileStore
import com.sheen.rokid.phone.storage.KeystoreCatalogCredentialStore
import com.sheen.rokid.phone.storage.ModelCatalogJson
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.phone.storage.*
import com.sheen.rokid.plugin.SpeakerProfileRef
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptedStorageTest {
    @get:Rule val temporary = TemporaryFolder(File(requireNotNull(System.getProperty("java.io.tmpdir"))))

    @Test fun firstManifestEncryptionFailureCanBeRetriedAndReconstructed() = initialManifestFailure(PublicationFailure.ENCRYPT)
    @Test fun firstManifestStartWriteFailureCanBeRetriedAndReconstructed() = initialManifestFailure(PublicationFailure.START)
    @Test fun firstManifestPartialWriteFailureCanBeRetriedAndReconstructed() = initialManifestFailure(PublicationFailure.WRITE)
    @Test fun firstManifestFinishWriteFailureCanBeRetriedAndReconstructed() = initialManifestFailure(PublicationFailure.FINISH)
    @Test fun keyCreationFailureAfterAliasInstallationCanBeRetriedAndReconstructed() = initialManifestFailure(PublicationFailure.CREATE)

    /**
     * The two owners of the shared encrypted blob layer: the catalog (0.52's only model configuration document) and the
     * Keystore vault's own manifest. A failed first publication must leave neither a blob nor a credential behind, and a
     * retry must reconstruct both.
     */
    private fun initialManifestFailure(phase: PublicationFailure) {
        for (manifest in listOf(ModelCatalogJson.BLOB_ID, "vault-manifest")) {
            val fixture = PublicationFixture(temporary.newFolder())
            val blobs = fixture.store(); val vault = KeystoreVault(blobs)
            val catalog = AndroidModelCatalogRepository(blobs, KeystoreCatalogCredentialStore(vault))
            fixture.fail(manifest, phase)
            assertThrows(ContractError.ConfigurationRejected::class.java) {
                if (manifest == ModelCatalogJson.BLOB_ID) {
                    catalog.saveProvider(catalogProvider(), "synthetic-dummy-secret".toCharArray())
                } else {
                    vault.putSecret("a", "synthetic-dummy-secret".toCharArray())
                }
            }
            assertNull("failed initial publication must remain absent: $manifest/$phase", blobs.read(manifest))
            val restoredBlobs = fixture.store(); val restoredVault = KeystoreVault(restoredBlobs)
            if (manifest == ModelCatalogJson.BLOB_ID) {
                val restored = AndroidModelCatalogRepository(restoredBlobs, KeystoreCatalogCredentialStore(restoredVault))
                assertTrue(restored.catalog().providers.isEmpty())
                val saved = catalog.saveProvider(catalogProvider(), "synthetic-retry".toCharArray())
                val finalVault = KeystoreVault(fixture.store())
                val reconstructed = AndroidModelCatalogRepository(
                    fixture.store(), KeystoreCatalogCredentialStore(finalVault),
                )
                // R13: the vault only admits handles the catalog still references.
                finalVault.authorize(reconstructed::isCredentialReferenced)
                assertEquals(1L, reconstructed.catalog().providers.single().revision)
                finalVault.withSecret(reconstructed.providerSecret(saved.id)!!) {
                    assertEquals("synthetic-retry", String(it))
                }
            } else {
                assertTrue(restoredVault.directHandles().isEmpty())
                val saved = vault.putSecret("a", "synthetic-retry".toCharArray())
                KeystoreVault(fixture.store()).withSecret(saved) { assertEquals("synthetic-retry", String(it)) }
            }
        }
    }

    @Test fun replacementFailurePreservesCommittedManifestCiphertextAndKeys() {
        for (manifest in listOf(ModelCatalogJson.BLOB_ID, "vault-manifest")) {
            for (phase in listOf(PublicationFailure.ENCRYPT, PublicationFailure.START, PublicationFailure.WRITE, PublicationFailure.FINISH)) {
                val fixture = PublicationFixture(temporary.newFolder())
                val blobs = fixture.store(); val vault = KeystoreVault(blobs)
                val catalog = AndroidModelCatalogRepository(blobs, KeystoreCatalogCredentialStore(vault))
                val handle = if (manifest == ModelCatalogJson.BLOB_ID) {
                    val provider = catalog.saveProvider(catalogProvider(), "synthetic-original".toCharArray())
                    catalog.providerSecret(provider.id)!!
                } else {
                    vault.putSecret("a", "synthetic-original".toCharArray())
                }
                val ciphertext = fixture.file(manifest).readBytes()
                val key = fixture.keys.get(fixture.alias(manifest))
                fixture.fail(manifest, phase)
                assertThrows(ContractError.ConfigurationRejected::class.java) {
                    if (phase == PublicationFailure.ENCRYPT) blobs.write(manifest, ByteArray(BlobCipher.MAX_BYTES + 1))
                    else if (manifest == ModelCatalogJson.BLOB_ID) {
                        catalog.saveProvider(
                            catalogProvider(baseUrl = "https://other.example/v1"),
                            "synthetic-replacement".toCharArray(),
                        )
                    } else vault.putSecret("a", "synthetic-replacement".toCharArray())
                }
                assertArrayEquals(ciphertext, fixture.file(manifest).readBytes())
                assertSame(key, fixture.keys.get(fixture.alias(manifest)))
                val restoredVault = KeystoreVault(fixture.store())
                if (manifest == ModelCatalogJson.BLOB_ID) {
                    val restored = AndroidModelCatalogRepository(
                        fixture.store(), KeystoreCatalogCredentialStore(restoredVault),
                    )
                    // R13: the vault only admits handles the catalog still references.
                    restoredVault.authorize(restored::isCredentialReferenced)
                    assertEquals(1L, restored.catalog().providers.single().revision)
                }
                restoredVault.withSecret(handle) { assertEquals("synthetic-original", String(it)) }
            }
        }
    }

    @Test fun missingOrCorruptCommittedCiphertextAndMissingKeyRemainRejected() {
        val fixture = PublicationFixture(temporary.newFolder()); val blobs = fixture.store()
        blobs.write(ModelCatalogJson.BLOB_ID, byteArrayOf(1, 2, 3))
        val original = fixture.file(ModelCatalogJson.BLOB_ID).readBytes()
        fixture.file(ModelCatalogJson.BLOB_ID).writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertThrows(ContractError.ConfigurationRejected::class.java) { blobs.read(ModelCatalogJson.BLOB_ID) }
        fixture.file(ModelCatalogJson.BLOB_ID).delete()
        assertThrows(ContractError.ConfigurationRejected::class.java) { blobs.read(ModelCatalogJson.BLOB_ID) }
        fixture.fail(ModelCatalogJson.BLOB_ID, PublicationFailure.START)
        assertThrows(ContractError.ConfigurationRejected::class.java) { blobs.write(ModelCatalogJson.BLOB_ID, byteArrayOf(4)) }
        assertThrows(ContractError.ConfigurationRejected::class.java) { blobs.read(ModelCatalogJson.BLOB_ID) }
        fixture.file(ModelCatalogJson.BLOB_ID).writeBytes(original)
        fixture.keys.delete(fixture.alias(ModelCatalogJson.BLOB_ID))
        assertThrows(ContractError.ConfigurationRejected::class.java) { blobs.read(ModelCatalogJson.BLOB_ID) }
        assertThrows(ContractError.ConfigurationRejected::class.java) { blobs.write(ModelCatalogJson.BLOB_ID, byteArrayOf(4)) }
        assertArrayEquals(original, fixture.file(ModelCatalogJson.BLOB_ID).readBytes())
    }

    @Test fun encryptedNamespacesKeepIndependentDurableCopiesAndClearOnlyTheirOwner() {
        val blobs = TestBlobs(); val a = AndroidNamespaceStorage("smart-assistant", blobs); val b = AndroidNamespaceStorage("interview-assistant", blobs)
        val value = byteArrayOf(1, 2, 3); a.write("context", value); value[0] = 9; b.write("context", byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3), AndroidNamespaceStorage("smart-assistant", blobs).read("context"))
        a.read("context")!![0] = 8; assertArrayEquals(byteArrayOf(1, 2, 3), a.read("context"))
        a.clear(); assertNull(a.read("context")); assertArrayEquals(byteArrayOf(4, 5), b.read("context"))
        assertThrows(IllegalArgumentException::class.java) { a.write("../context", byteArrayOf(1)) }
    }
    @Test fun gcmUsesFreshNonceAndRejectsTagIdentityVersionAndLengths() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); val cipher = BlobCipher()
        val plain = "synthetic-dummy-secret".toByteArray(); val a = cipher.encrypt("a", plain, key); val b = cipher.encrypt("a", plain, key)
        assertFalse(a.contentEquals(b)); assertFalse(a.toString(Charsets.UTF_8).contains("synthetic-dummy-secret"))
        assertArrayEquals(plain, cipher.decrypt("a", a, key))
        assertThrows(ContractError.ConfigurationRejected::class.java) { cipher.decrypt("b", a, key) }
        assertThrows(ContractError.ConfigurationRejected::class.java) { cipher.decrypt("a", a.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, key) }
        assertThrows(ContractError.ConfigurationRejected::class.java) { cipher.decrypt("a", a.copyOf().also { it[0] = 8 }, key) }
        assertThrows(ContractError.ConfigurationRejected::class.java) { cipher.decrypt("a", a.copyOf(4), key) }
    }

    @Test fun speakerExactReferenceCopiesAndDeleteSurviveReconstruction() {
        val blobs = TestBlobs(); val store = AndroidSpeakerProfileStore(blobs)
        val input = floatArrayOf(1f, -2f, 0.5f); val a = store.save(input); val b = store.save(floatArrayOf(3f, 4f))
        input[0] = 99f; assertArrayEquals(floatArrayOf(1f, -2f, 0.5f), store.load(a), 0f)
        store.load(a)!![0] = 44f
        assertArrayEquals(floatArrayOf(1f, -2f, 0.5f), store.load(a), 0f)
        store.delete(SpeakerProfileRef(a.id, 1)); assertNotNull(store.load(a)); assertNull(store.load(SpeakerProfileRef(a.id, 1)))
        store.delete(a); val restored = AndroidSpeakerProfileStore(blobs)
        assertNull(restored.load(a)); assertArrayEquals(floatArrayOf(3f, 4f), restored.load(b), 0f)
    }

    @Test fun speakerRejectsMalformedNonfiniteZeroOrExcessiveVectors() {
        val blobs = TestBlobs(); val store = AndroidSpeakerProfileStore(blobs)
        listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(Float.NaN), FloatArray(4097) { 1f }).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.save(invalid) }
        }
        val ref = store.save(floatArrayOf(1f)); blobs.values[ref.id] = byteArrayOf(1, 0, 0, 0)
        assertThrows(ContractError.ConfigurationRejected::class.java) { store.load(ref) }
    }
}

private fun catalogProvider(baseUrl: String = "https://synthetic.example/v1") = Provider(
    id = "provider-1",
    name = "Synthetic",
    baseUrl = baseUrl,
    models = listOf(
        ModelEntry(
            id = "m-1",
            displayName = "Model m-1",
            contextWindowTokens = 131_072,
            maxOutputTokens = 8_192,
            origin = ModelOrigin.MANUAL,
        ),
    ),
)

private enum class PublicationFailure { CREATE, ENCRYPT, START, WRITE, FINISH }

private class PublicationFixture(private val directory: File) {
    private var failure: Pair<String, PublicationFailure>? = null
    fun fail(id: String, phase: PublicationFailure) { failure = digest(id) to phase }
    private fun consume(name: String, phase: PublicationFailure): Boolean {
        if (failure != (name.substringAfterLast('.') to phase)) return false
        failure = null; return true
    }
    val keys = object : BlobKeys {
        private val values = mutableMapOf<String, SecretKey>()
        override val providerName = "JVM synthetic AES"
        override fun contains(name: String) = name in values
        override fun get(name: String) = values[name]
        override fun create(name: String): SecretKey {
            val key = if (consume(name, PublicationFailure.ENCRYPT)) SecretKeySpec(byteArrayOf(1), "AES")
                else KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            values[name] = key
            if (consume(name, PublicationFailure.CREATE)) throw IOException("synthetic create failure after alias installation")
            return key
        }
        override fun delete(name: String) { values.remove(name) }
        override fun aliases() = values.keys.toList()
    }
    fun store() = AndroidEncryptedBlobStore({ directory }, keys) { file ->
        object : BlobFile {
            override val baseFile = file
            private val pending = File(file.path + ".new")
            override fun openRead() = FileInputStream(file)
            override fun startWrite(): FileOutputStream {
                if (consume(file.name, PublicationFailure.START)) throw IOException("synthetic start failure")
                return object : FileOutputStream(pending) {
                    override fun write(bytes: ByteArray) {
                        if (consume(file.name, PublicationFailure.WRITE)) {
                            super.write(bytes, 0, bytes.size / 2)
                            throw IOException("synthetic partial write failure")
                        }
                        super.write(bytes)
                    }
                }
            }
            override fun finishWrite(stream: FileOutputStream) {
                if (consume(file.name, PublicationFailure.FINISH)) throw IOException("synthetic finish failure")
                stream.close()
                java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            override fun failWrite(stream: FileOutputStream) { stream.close(); pending.delete() }
            override fun delete() { file.delete(); pending.delete() }
        }
    }
    fun file(id: String) = File(directory, digest(id))
    fun alias(id: String) = "edith.v1.${digest(directory.absolutePath)}.${digest(id)}"
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
