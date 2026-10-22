package com.sheen.rokid.phone.storage

import android.content.ContextWrapper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileProcessPersistenceTest {
    @Test fun encryptedProfilesSurviveProcessRestartAndDelete() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("persistencePhase") ?: "roundtrip"
        require(phase in setOf("prepare", "recover", "roundtrip"))
        val run = args.getString("persistenceRun") ?: UUID.randomUUID().toString()
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(run))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(base.noBackupFilesDir, "t15-process-$run")
        if (phase != "recover") {
            assertFalse("A fresh run must not overwrite existing test state", isolated.exists())
        }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = isolated }
        val blobs = AndroidEncryptedBlobStore(context)
        val vault = KeystoreVault(blobs)
        val repo = AndroidProfileRepository(blobs, vault)
        fun prepare() {
            val draft = ModelProfile("synthetic-process", 0, "Synthetic process", "Synthetic",
                "https://synthetic.example/v1", "CUSTOM", "synthetic-model", opaqueSecret("placeholder"),
                4096, 2048, 1024, 128, 5000, InputModality.entries.toSet(), OutputModality.entries.toSet(), emptyList())
            val saved = repo.saveWithSecret(draft, "synthetic-process-secret".toCharArray())
            repo.select("smart-assistant", saved.id)
            repo.select("interview-assistant", saved.id)
            blobs.write("synthetic-origin-pid", Process.myPid().toString().toByteArray())
            assertEquals(1, repo.list().size)
        }
        fun recover(requireNewProcess: Boolean) {
            val pidBytes = requireNotNull(blobs.read("synthetic-origin-pid"))
            val oldPid = try { pidBytes.toString(Charsets.UTF_8).toInt() } finally { pidBytes.fill(0) }
            if (requireNewProcess) assertNotEquals("Recovery must run in another Android process", oldPid, Process.myPid())
            val saved = requireNotNull(repo.get("synthetic-process"))
            assertEquals(InputModality.entries.toSet(), saved.inputs)
            assertEquals(OutputModality.entries.toSet(), saved.outputs)
            assertEquals(mapOf("smart-assistant" to saved.id, "interview-assistant" to saved.id), repo.selections())
            vault.withSecret(saved.secret) { assertEquals("synthetic-process-secret", String(it)) }
            repo.delete(saved.id)
            assertTrue(repo.list().isEmpty())
            assertTrue(repo.selections().isEmpty())
            assertThrows(ContractError.ConfigurationRejected::class.java) { vault.withSecret(saved.secret) { fail("Deleted credential resolved") } }
            val restoredVault = KeystoreVault(blobs)
            assertTrue(AndroidProfileRepository(blobs, restoredVault).list().isEmpty())
            assertThrows(ContractError.ConfigurationRejected::class.java) { restoredVault.withSecret(saved.secret) { fail("Deleted credential restored") } }
        }
        try {
            when (phase) {
                "prepare" -> prepare()
                "recover" -> recover(true)
                else -> { prepare(); recover(false) }
            }
        } finally {
            if (phase != "prepare") {
                val directory = File(isolated, "edith-vault-v1")
                val digest = MessageDigest.getInstance("SHA-256").digest(directory.absolutePath.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val keys = AndroidBlobKeys()
                keys.aliases().filter { it.startsWith("edith.v1.$digest.") }.forEach(keys::delete)
                directory.listFiles()?.forEach { assertTrue("Delete owned test file", it.delete()) }
                if (directory.exists()) assertTrue(directory.delete())
                if (isolated.exists()) assertTrue(isolated.delete())
            }
        }
    }
}
