package com.sheen.rokid.phone.storage

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * The encrypted catalog repository (0.52 design §5.3, rulings R15/R16/R17).
 *
 * Pure JVM: a fake blob store and a fake credential store stand in for the Keystore, so the transactional credential
 * lifecycle (stage -> blob commit -> revoke old) is proven without touching AndroidKeyStore.
 */
class AndroidModelCatalogRepositoryTest {
    private class Blobs : EncryptedBlobStore {
        val values = linkedMapOf<String, ByteArray>()
        var failId: String? = null
        var failReadId: String? = null
        override fun read(id: String): ByteArray? {
            if (id == failReadId) throw IOException("synthetic read failure")
            return values[id]?.copyOf()
        }
        override fun write(id: String, value: ByteArray) {
            if (id == failId) throw IOException("synthetic disk failure")
            values[id] = value.copyOf()
        }
        override fun delete(id: String) {
            values.remove(id)?.fill(0)
        }
    }

    private class Credentials : CatalogCredentialStore {
        var stageCalls = 0
        val stagedIds = mutableListOf<String>()
        val staged = linkedMapOf<String, CharArray>()
        val revoked = mutableListOf<SecretHandle>()
        override fun stage(value: CharArray): SecretHandle {
            try {
                stageCalls += 1
                val id = "credential-${UUID.randomUUID()}"
                stagedIds += id
                staged[id] = value.copyOf()
                return handle(id)
            } finally {
                value.fill('\u0000')
            }
        }
        override fun revoke(handle: SecretHandle) {
            revoked += handle
            staged.remove(handle.id)?.fill('\u0000')
        }
        private fun handle(id: String) = object : SecretHandle {
            override val id = id
            override fun toString() = "SecretHandle(redacted)"
        }
    }

    private fun entry(id: String = "m-1", displayName: String = "Model $id") = ModelEntry(
        id = id,
        displayName = displayName,
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        outputs = setOf(OutputModality.TEXT),
        thinkingSupported = true,
        origin = ModelOrigin.GATEWAY,
    )

    private fun provider(
        id: String = "provider-1",
        name: String = "DeepSeek",
        models: List<ModelEntry> = emptyList(),
    ) = Provider(
        id = id,
        name = name,
        baseUrl = "https://api.deepseek.com/v1",
        models = models,
    )

    private fun bytes(blobs: Blobs, id: String) = blobs.values.getValue(id).toString(Charsets.UTF_8)

    @Test fun `a new provider is stored with revision one and a staged credential`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        val newKey = "synthetic-new-key".toCharArray()

        val saved = repository.saveProvider(provider(), newKey)

        assertEquals(1L, saved.revision)
        assertEquals(1, credentials.stageCalls)
        assertEquals(credentials.staged.keys.single(), saved.secretHandleId)
        assertTrue(newKey.all { it == '\u0000' })
        assertEquals(listOf(saved), repository.catalog().providers)
        assertEquals(saved.secretHandleId, repository.providerSecret("provider-1")?.id)
        assertTrue(blobs.values.containsKey(ModelCatalogJson.BLOB_ID))
        assertTrue(repository.isCredentialReferenced(saved.secretHandleId!!))
        assertFalse(repository.isCredentialReferenced("credential-00000000-0000-0000-0000-000000000000"))
        assertFalse(bytes(blobs, ModelCatalogJson.BLOB_ID).contains("synthetic-new-key"))
    }

    @Test fun `saving a provider increments revision and keeps the handle when the key is blank`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        val first = repository.saveProvider(provider(), "synthetic-first-key".toCharArray())

        val second = repository.saveProvider(first.copy(name = "Renamed provider"), null)

        assertEquals(2L, second.revision)
        assertEquals(first.secretHandleId, second.secretHandleId)
        assertEquals(1, credentials.stageCalls)
        assertTrue(credentials.revoked.isEmpty())
        assertEquals("Renamed provider", repository.catalog().providers.single().name)
        assertEquals(first.secretHandleId, repository.providerSecret("provider-1")?.id)
    }

    @Test fun `a failed catalog commit revokes the staged credential and keeps the old one`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        val old = repository.saveProvider(provider(), "synthetic-old-key".toCharArray())
        blobs.failId = ModelCatalogJson.BLOB_ID
        val newKey = "synthetic-new-key".toCharArray()

        val error = assertThrows(ContractError.ConfigurationRejected::class.java) {
            repository.saveProvider(old.copy(name = "Renamed provider"), newKey)
        }

        assertEquals("Storage unavailable; reconfigure", error.message)
        assertEquals(2, credentials.stageCalls)
        assertEquals(listOf(credentials.stagedIds[1]), credentials.revoked.map { it.id })
        assertFalse(credentials.revoked.any { it.id == old.secretHandleId })
        assertTrue(newKey.all { it == '\u0000' })
        assertEquals(listOf(old), repository.catalog().providers)
        assertEquals(old.secretHandleId, repository.providerSecret("provider-1")?.id)
        assertTrue(repository.isCredentialReferenced(old.secretHandleId!!))
        assertFalse(repository.isCredentialReferenced(credentials.revoked.single().id))
    }

    @Test fun `the derived profile secret is admitted by the vault authority predicate`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        val saved = repository.saveProvider(provider(), "synthetic-key".toCharArray())
        repository.saveModels("provider-1", listOf(entry()), "m-1")

        val projected = CatalogProfileRepository(repository).get("provider-1:m-1")

        assertNotNull(projected)
        assertEquals(saved.secretHandleId, projected!!.secret.id)
        assertTrue(repository.isCredentialReferenced(projected.secret.id))
        assertFalse(repository.isCredentialReferenced("credential-00000000-0000-0000-0000-000000000000"))
    }

    @Test fun `deleting a provider revokes only its credential and clears the active selection`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        val first = repository.saveProvider(provider("p-1"), "synthetic-first-key".toCharArray())
        repository.saveModels("p-1", listOf(entry("m-1"), entry("m-2")), "m-1")
        val second = repository.saveProvider(provider("p-2", name = "Second"), "synthetic-second-key".toCharArray())
        repository.saveModels("p-2", listOf(entry("m-3")), null)
        ModelCatalogJson.write(
            blobs,
            repository.catalog().copy(
                activeProviderId = "p-1",
                selectedModelId = "m-1",
                lastSelectedModelIdByProvider = mapOf("p-1" to "m-1", "p-2" to "m-3"),
            ),
        )
        val notified = mutableListOf<Set<String>>()
        val subscription = repository.listenChanges { notified += it }

        repository.deleteProvider("p-1")

        val catalog = repository.catalog()
        assertNull(catalog.activeProviderId)
        assertNull(catalog.selectedModelId)
        assertEquals(listOf("p-2"), catalog.providers.map { it.id })
        assertFalse(catalog.lastSelectedModelIdByProvider.containsKey("p-1"))
        assertEquals(listOf(setOf("p-1:m-1", "p-1:m-2")), notified)
        assertEquals(listOf(first.secretHandleId), credentials.revoked.map { it.id })
        assertTrue(repository.isCredentialReferenced(second.secretHandleId!!))
        assertFalse(repository.isCredentialReferenced(first.secretHandleId!!))
        subscription.cancel()
    }

    @Test fun `verification fences the provider revision without incrementing it`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        repository.saveProvider(provider(), "synthetic-key".toCharArray())
        val saved = repository.saveModels("provider-1", listOf(entry()), "m-1", lastSyncedAtEpochMillis = 7_654_321)
        assertEquals(7_654_321L, repository.catalog().providers.single().lastSyncedAtEpochMillis)
        val profileId = "provider-1:m-1"

        repository.verify(
            profileId,
            CapabilityVerification(saved.revision, "TEXT_REQUEST", VerificationState.Verified, 123),
        )

        val current = repository.catalog().providers.single()
        assertEquals(saved.revision, current.revision)
        assertEquals(1, current.models.single().verifications.size)
        assertEquals("TEXT_REQUEST", current.models.single().verifications.single().operation)

        repository.verify(
            profileId,
            CapabilityVerification(saved.revision + 1, "IMAGE_REQUEST", VerificationState.Failed, 456),
        )
        repository.verify(
            "provider-1:missing",
            CapabilityVerification(saved.revision, "TEXT_REQUEST", VerificationState.Verified, 789),
        )

        assertEquals(1, repository.catalog().providers.single().models.single().verifications.size)
    }

    @Test fun `changing a model bumps the revision and invalidates stale verification`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        repository.saveProvider(provider(), "synthetic-key".toCharArray())
        val first = repository.saveModels("provider-1", listOf(entry()), "m-1")
        repository.verify(
            "provider-1:m-1",
            CapabilityVerification(first.revision, "TEXT_REQUEST", VerificationState.Verified, 1),
        )
        assertEquals(1, repository.catalog().providers.single().models.single().verifications.size)

        val changed = repository.saveModels(
            "provider-1",
            listOf(entry(displayName = "Renamed model")),
            "m-1",
        )

        assertEquals(first.revision + 1, changed.revision)
        assertTrue(changed.models.single().verifications.isEmpty())
        repository.verify(
            "provider-1:m-1",
            CapabilityVerification(first.revision, "TEXT_REQUEST", VerificationState.Verified, 2),
        )
        assertTrue(repository.catalog().providers.single().models.single().verifications.isEmpty())
    }

    @Test fun `a corrupt catalog reports reconfigure and reset deletes only the catalog blob`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        blobs.write(ModelCatalogJson.BLOB_ID, """{"version":1}""".toByteArray())
        blobs.write("namespace-smart-assistant", "keep".toByteArray())
        blobs.write("profiles-manifest", "keep".toByteArray())
        blobs.write("speaker-1", "keep".toByteArray())

        val error = assertThrows(ContractError.ConfigurationRejected::class.java) { repository.catalog() }
        assertEquals("Storage unavailable; reconfigure", error.message)

        val notified = mutableListOf<Set<String>>()
        repository.listenChanges { notified += it }

        repository.reset()

        assertFalse(blobs.values.containsKey(ModelCatalogJson.BLOB_ID))
        assertEquals("keep", bytes(blobs, "namespace-smart-assistant"))
        assertEquals("keep", bytes(blobs, "profiles-manifest"))
        assertEquals("keep", bytes(blobs, "speaker-1"))
        repository.saveProvider(provider(), "synthetic-key".toCharArray())
        assertTrue(notified.isEmpty())
    }

    @Test fun `reset notifies the last derived profiles before deleting the catalog`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        repository.saveProvider(provider("p-1"), "synthetic-key".toCharArray())
        repository.saveModels("p-1", listOf(entry("m-1"), entry("m-2")), "m-1")
        val notified = mutableListOf<Set<String>>()
        repository.listenChanges {
            assertTrue(blobs.values.containsKey(ModelCatalogJson.BLOB_ID))
            notified += it
        }

        repository.reset()

        assertEquals(listOf(setOf("p-1:m-1", "p-1:m-2")), notified)
        assertTrue(repository.catalog().providers.isEmpty())
        assertNull(repository.catalog().activeProviderId)
    }

    @Test fun `derived profiles list every model and look up by provider colon model`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        val first = repository.saveProvider(provider("p-1"), "synthetic-first-key".toCharArray())
        repository.saveModels("p-1", listOf(entry("m-1"), entry("m-2", displayName = "Second model")), "m-1")
        repository.saveProvider(provider("p-2", name = "Second"), "synthetic-second-key".toCharArray())
        repository.saveModels("p-2", listOf(entry("m-3")), null)
        repository.saveProvider(provider("p-3", name = "Keyless"), null)
        repository.saveModels("p-3", listOf(entry("m-4")), null)

        val profiles = CatalogProfileRepository(repository)

        assertEquals(listOf("p-1:m-1", "p-1:m-2", "p-2:m-3"), profiles.list().map { it.id })
        val second = profiles.get("p-1:m-2")
        assertNotNull(second)
        assertEquals("Second model", second!!.name)
        assertEquals("p-1:m-2", second.id)
        assertEquals(first.secretHandleId, second.secret.id)
        assertNull(profiles.get("p-3:m-4"))
        assertNull(profiles.get("p-1:missing"))
        assertNull(profiles.get("malformed"))
        assertThrows(UnsupportedOperationException::class.java) { profiles.save(second) }
        assertThrows(UnsupportedOperationException::class.java) { profiles.delete("p-1:m-2") }
    }

    @Test fun `catalog profile changes fan out to every affected id once`() {
        val blobs = Blobs()
        val credentials = Credentials()
        val repository = AndroidModelCatalogRepository(blobs, credentials)
        repository.saveProvider(provider("p-1"), "synthetic-key".toCharArray())
        val models = listOf(entry("m-1"), entry("m-2"), entry("m-3"))
        repository.saveModels("p-1", models, "m-1")
        ModelCatalogJson.write(
            blobs,
            repository.catalog().copy(
                activeProviderId = "p-1",
                selectedModelId = "m-1",
                lastSelectedModelIdByProvider = mapOf("p-1" to "m-1"),
            ),
        )
        val profiles = CatalogProfileRepository(repository)
        val seen = mutableListOf<String>()
        val changedModels = listOf(entry("m-1", displayName = "Changed"), entry("m-2"), entry("m-3"))

        val subscription = profiles.listenChanges { seen += it }
        repository.saveModels("p-1", changedModels, "m-1")

        assertEquals(setOf("p-1:m-1", "p-1:m-2", "p-1:m-3"), seen.toSet())
        assertEquals(3, seen.size)

        // A selection-only save bumps no revision and reports only the old and new selection.
        seen.clear()
        val revision = repository.catalog().providers.single().revision
        repository.saveModels("p-1", changedModels, "m-2")
        assertEquals(setOf("p-1:m-1", "p-1:m-2"), seen.toSet())
        assertEquals(2, seen.size)
        assertEquals(revision, repository.catalog().providers.single().revision)
        subscription.cancel()
        repository.selectModel("m-3")
        assertEquals(2, seen.size)
    }
}
