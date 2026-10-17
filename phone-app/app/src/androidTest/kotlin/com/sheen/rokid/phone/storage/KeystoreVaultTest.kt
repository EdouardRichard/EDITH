package com.sheen.rokid.phone.storage
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KeystoreVaultTest {
    @Test fun initialManifestPublicationFailureRetriesWithRealKeystoreAndAtomicFile() = DummyVaultFixture().use { fixture ->
        val directory = File(fixture.context.noBackupFilesDir, "edith-vault-v1")
        val keys = AndroidBlobKeys()
        val prefix = "edith.v1.${dummyDigest(directory.absolutePath)}."
        try {
            for (manifest in listOf("profiles-manifest", "vault-manifest")) {
                val targetName = dummyDigest(manifest)
                var failNext = true
                val blobs = AndroidEncryptedBlobStore({ directory }, keys) { file ->
                    val real = AndroidBlobFile(file)
                    object : BlobFile by real {
                        override fun startWrite(): FileOutputStream {
                            val stream = real.startWrite()
                            if (file.name == targetName && failNext) {
                                failNext = false
                                stream.write(byteArrayOf(1, 2, 3))
                                real.failWrite(stream)
                                throw IOException("synthetic initial publication failure")
                            }
                            return stream
                        }
                    }
                }
                val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
                assertThrows(ContractError.ConfigurationRejected::class.java) {
                    if (manifest == "profiles-manifest") repo.saveWithSecret(dummyProfile(), "synthetic-dummy-secret".toCharArray())
                    else vault.putSecret("dummy-direct", "synthetic-dummy-secret".toCharArray())
                }
                assertFalse(keys.contains(prefix + targetName))
                assertNull(AndroidEncryptedBlobStore(fixture.context).read(manifest))
                val handle = if (manifest == "profiles-manifest") repo.saveWithSecret(dummyProfile(), "synthetic-retry".toCharArray()).secret
                    else vault.putSecret("dummy-direct", "synthetic-retry".toCharArray())
                val restoredBlobs = AndroidEncryptedBlobStore(fixture.context)
                val restoredVault = KeystoreVault(restoredBlobs)
                val restoredRepo = AndroidProfileRepository(restoredBlobs, restoredVault)
                if (manifest == "profiles-manifest") assertEquals(1L, restoredRepo.get("dummy-retry")!!.revision)
                restoredVault.withSecret(handle) { assertEquals("synthetic-retry", String(it)) }
            }
            assertEquals("AndroidKeyStore", keys.providerName)
        } finally {
            keys.aliases().filter { it.startsWith(prefix) }.forEach(keys::delete)
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    @Test fun deletionRevokesSecretAndPersistence(): Unit {
        DummyVaultFixture().use { fixture ->
        val vault = KeystoreVault(fixture.blobs)
        val ref = vault.putSecret("test-profile", "synthetic-dummy-secret".toCharArray())
        vault.deleteSecret("test-profile")
        assertThrows(ContractError.ConfigurationRejected::class.java) { vault.withSecret(ref) { fail("resolved deleted secret") } }
        assertThrows(ContractError.ConfigurationRejected::class.java) { KeystoreVault(fixture.blobs).withSecret(ref) { fail("restored deleted secret") } }
        }
    }
    @Test fun reconstructionReplacementAndCiphertextAreBoundToExactGeneration() = DummyVaultFixture().use { fixture ->
        val vault = KeystoreVault(fixture.blobs); val old = vault.putSecret("test-profile", "synthetic-dummy-secret".toCharArray())
        KeystoreVault(fixture.blobs).withSecret(old) { assertEquals("synthetic-dummy-secret", String(it)) }
        val current = vault.putSecret("test-profile", "synthetic-replacement".toCharArray())
        assertThrows(ContractError.ConfigurationRejected::class.java) { vault.withSecret(old) { fail("old handle admitted") } }
        vault.revoke(old); KeystoreVault(fixture.blobs).withSecret(current) { assertEquals("synthetic-replacement", String(it)) }
        fixture.files().forEach { file -> assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("synthetic-")) }
        assertEquals("AndroidKeyStore", fixture.android.providerName)
        assertEquals(0, fixture.context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
    @Test fun corruptedAuthenticationTagFailsClosed(): Unit {
        DummyVaultFixture().use { fixture ->
        val vault = KeystoreVault(fixture.blobs); val ref = vault.putSecret("test-profile", "synthetic-dummy-secret".toCharArray())
        val file = fixture.file(ref.id); val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertThrows(ContractError.ConfigurationRejected::class.java) { KeystoreVault(fixture.blobs).withSecret(ref) { fail("corrupt key admitted") } }
        }
    }
    @Test fun missingCiphertextCannotResetCredentialIndex(): Unit {
        DummyVaultFixture().use { fixture ->
        val vault = KeystoreVault(fixture.blobs); val ref = vault.putSecret("test-profile", "synthetic-dummy-secret".toCharArray())
        assertTrue(fixture.file("vault-manifest").delete())
        assertThrows(ContractError.ConfigurationRejected::class.java) { KeystoreVault(fixture.blobs).withSecret(ref) { fail("missing manifest accepted") } }
        }
    }
    @Test fun profileModalitySelectionAndSpeakerDeletionSurviveReconstruction() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        val draft = ModelProfile("dummy-a", 0, "Synthetic", "Synthetic", "https://synthetic.example/v1", "CUSTOM", "synthetic-model", opaqueSecret("placeholder"),
            4096, 2048, 1024, 128, 5000, InputModality.entries.toSet(), OutputModality.entries.toSet(), emptyList())
        val saved = repo.saveWithSecret(draft, "synthetic-dummy-secret".toCharArray()); repo.select("interview-assistant", saved.id)
        val restored = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        assertEquals(InputModality.entries.toSet(), restored.get(saved.id)!!.inputs)
        assertEquals(OutputModality.entries.toSet(), restored.get(saved.id)!!.outputs)
        assertEquals(mapOf("interview-assistant" to saved.id), restored.selections())
        val speakers = AndroidSpeakerProfileStore(fixture.blobs); val ref = speakers.save(floatArrayOf(1f, 2f, 3f))
        speakers.delete(SpeakerProfileRef(ref.id, 1)); assertNotNull(speakers.load(ref)); speakers.delete(ref)
        assertNull(AndroidSpeakerProfileStore(fixture.blobs).load(ref))
        restored.delete(saved.id); assertTrue(AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs)).selections().isEmpty())
    }
}

private fun dummyProfile() = ModelProfile("dummy-retry", 0, "Synthetic", "Synthetic", "https://synthetic.example/v1", "CUSTOM", "synthetic-model", opaqueSecret("placeholder"),
    4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())
private fun dummyDigest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal class DummyVaultFixture : AutoCloseable {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolated = File(base.noBackupFilesDir, "t15-dummy-${UUID.randomUUID()}").apply { check(mkdirs()) }
    val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = isolated }
    val android = AndroidEncryptedBlobStore(context)
    private val ids = linkedSetOf<String>()
    val blobs = object : EncryptedBlobStore {
        override fun read(id: String) = android.read(id)
        override fun write(id: String, value: ByteArray) { ids += id; android.write(id, value) }
        override fun delete(id: String) { android.delete(id) }
    }
    fun files() = File(isolated, "edith-vault-v1").listFiles()?.toList() ?: emptyList()
    fun file(id: String): File {
        val prefix = when { id.startsWith("credential-") -> "credential-"; id.startsWith("profile-") -> "profile-"; else -> "" }
        return File(File(isolated, "edith-vault-v1"), prefix + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) })
    }
    override fun close() { ids.forEach { android.delete(it) }; File(isolated, "edith-vault-v1").delete(); isolated.delete() }
}
