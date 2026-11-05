package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.GatewayModelSnapshot
import com.sheen.rokid.model.ModelListError
import com.sheen.rokid.model.ModelListResult
import com.sheen.rokid.model.ModelListSource
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ModelCatalogService` owns the orchestration half of a refresh: provider/secret lookup, transport call, pure merge,
 * persistence. The merge rules themselves are covered by `ModelCatalogMergeTest`; this suite pins the service
 * boundary - exact counts, what reaches storage, the clock, and the in-memory gateway cache used by "restore defaults".
 */
class ModelCatalogServiceTest {
    private val now = 1_700_000_000_000L

    private fun entry(
        id: String = "m-1",
        displayName: String = id,
        window: Int = 131_072,
        output: Int = 8_192,
        inputs: Set<InputModality> = setOf(InputModality.TEXT),
        origin: ModelOrigin = ModelOrigin.GATEWAY,
        overrides: Set<ModelField> = emptySet(),
        missing: Boolean = false,
        lastSeenAt: Long? = null,
        thinkingSupported: Boolean = false,
    ): ModelEntry = ModelEntry(
        id = id,
        displayName = displayName,
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = inputs,
        outputs = setOf(OutputModality.TEXT),
        thinkingSupported = thinkingSupported,
        origin = origin,
        overrides = overrides,
        missing = missing,
        lastSeenAtEpochMillis = lastSeenAt,
    )

    private fun provider(
        vararg models: ModelEntry,
        id: String = "p-1",
        secretHandleId: String? = "secret-1",
        lastSyncedAt: Long? = null,
    ): Provider = Provider(
        id = id,
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        secretHandleId = secretHandleId,
        revision = 3,
        lastSyncedAtEpochMillis = lastSyncedAt,
        models = models.toList(),
    )

    private fun catalog(
        vararg providers: Provider,
        activeProviderId: String? = providers.firstOrNull()?.id,
        selectedModelId: String? = null,
        lastSelectedModelIdByProvider: Map<String, String> = emptyMap(),
    ): ModelCatalog = ModelCatalog(
        activeProviderId = activeProviderId,
        selectedModelId = selectedModelId,
        providers = providers.toList(),
        lastSelectedModelIdByProvider = lastSelectedModelIdByProvider,
    )

    private fun snapshot(
        id: String,
        displayName: String? = null,
        window: Int? = null,
        output: Int? = null,
        inputs: Set<InputModality>? = null,
        thinkingSupported: Boolean = false,
    ): GatewayModelSnapshot = GatewayModelSnapshot(
        id = id,
        displayName = displayName,
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = inputs,
        thinkingSupported = thinkingSupported,
    )

    @Test fun `a successful refresh merges models and reports added updated unchanged missing`() {
        val repo = FakeRepository(
            catalog(
                provider(
                    entry(
                        id = "m-1",
                        displayName = "Model One",
                        window = 65_536,
                        output = 4_096,
                        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
                        thinkingSupported = true,
                        lastSeenAt = 1,
                    ),
                    entry(id = "m-2", displayName = "Model Two", window = 131_072, output = 8_192, lastSeenAt = 2),
                    entry(id = "m-3", displayName = "Model Three", window = 32_768, output = 4_096, lastSeenAt = 3),
                    lastSyncedAt = 111,
                ),
                selectedModelId = "m-3",
                lastSelectedModelIdByProvider = mapOf("p-1" to "m-3"),
            ),
        )
        val source = FakeSource(
            ModelListResult.Success(
                listOf(
                    snapshot(
                        "m-1",
                        displayName = "Model One",
                        window = 65_536,
                        output = 4_096,
                        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
                        thinkingSupported = true,
                    ),
                    snapshot("m-2", displayName = "Model Two", window = 131_072, output = 2_048, inputs = setOf(InputModality.TEXT)),
                    snapshot("m-4", displayName = "Model Four", window = 262_144, output = 16_384, inputs = setOf(InputModality.TEXT)),
                ),
                truncated = false,
            ),
        )
        var clockCalls = 0
        val service = ModelCatalogService(repo, source) {
            clockCalls += 1
            now
        }

        val outcome = service.refresh("p-1")

        assertEquals(
            RefreshOutcome.Success(RefreshCounts(added = 1, updated = 1, unchanged = 1, missing = 1)),
            outcome,
        )
        assertEquals(1, clockCalls)
        val sourceCall = source.calls.single()
        assertEquals("https://api.deepseek.com/v1", sourceCall.baseUrl)
        assertEquals("secret-1", sourceCall.secret.id)

        val save = repo.saveModelsCalls.single()
        assertEquals("p-1", save.providerId)
        // The selected model went missing on the gateway: keep it selected and report it, never switch models.
        assertEquals("m-3", save.selectedModelId)
        assertEquals(now, save.lastSyncedAtEpochMillis)
        assertEquals(listOf("m-1", "m-2", "m-4", "m-3"), save.models.map { it.id })
        assertFalse(save.models.first { it.id == "m-1" }.missing)
        assertEquals(2_048, save.models.first { it.id == "m-2" }.maxOutputTokens)
        assertEquals("Model Four", save.models.first { it.id == "m-4" }.displayName)
        assertEquals(now, save.models.first { it.id == "m-4" }.lastSeenAtEpochMillis)
        // A model the gateway no longer lists is flagged, reported, and kept.
        assertTrue(save.models.first { it.id == "m-3" }.missing)
        assertEquals(32_768, save.models.first { it.id == "m-3" }.contextWindowTokens)

        val storedProvider = repo.stored.providers.single()
        assertEquals(save.models, storedProvider.models)
        assertEquals(now, storedProvider.lastSyncedAtEpochMillis)
        assertEquals("m-3", repo.stored.selectedModelId)
        assertTrue(repo.stored.providers.single().models.first { it.id == "m-3" }.missing)
    }

    @Test fun `refresh keeps user overrides and manual models untouched`() {
        val manual = entry(
            id = "m-manual",
            displayName = "Manual",
            window = 123_456,
            output = 4_096,
            origin = ModelOrigin.MANUAL,
            missing = true,
            lastSeenAt = 6,
        )
        val repo = FakeRepository(
            catalog(
                provider(
                    entry(
                        id = "m-1",
                        displayName = "User Name",
                        window = 200_000,
                        output = 4_000,
                        overrides = setOf(ModelField.DISPLAY_NAME, ModelField.CONTEXT_WINDOW_TOKENS),
                        lastSeenAt = 5,
                    ),
                    manual,
                ),
                selectedModelId = "m-manual",
                lastSelectedModelIdByProvider = mapOf("p-1" to "m-manual"),
            ),
        )
        val source = FakeSource(
            ModelListResult.Success(
                listOf(
                    snapshot("m-1", displayName = "Gateway Name", window = 65_536, output = 7_000, inputs = setOf(InputModality.TEXT, InputModality.IMAGE)),
                    snapshot("m-manual", displayName = "Gateway Manual", window = 65_536, output = 4_096, inputs = setOf(InputModality.TEXT)),
                ),
                truncated = false,
            ),
        )
        val service = ModelCatalogService(repo, source) { now }

        val outcome = service.refresh("p-1") as RefreshOutcome.Success

        assertEquals(RefreshCounts(updated = 2), outcome.counts)
        val save = repo.saveModelsCalls.single()
        val refreshed = save.models.first { it.id == "m-1" }
        assertEquals("User Name", refreshed.displayName) // overridden field
        assertEquals(200_000, refreshed.contextWindowTokens) // overridden field
        assertEquals(7_000, refreshed.maxOutputTokens) // untouched field
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), refreshed.inputs) // untouched field
        assertEquals(setOf(ModelField.DISPLAY_NAME, ModelField.CONTEXT_WINDOW_TOKENS), refreshed.overrides)
        assertEquals(now, refreshed.lastSeenAtEpochMillis)
        // A manual row only loses its missing flag; the gateway never rewrites its fields.
        assertEquals(manual.copy(missing = false), save.models.first { it.id == "m-manual" })
        assertEquals("m-manual", save.selectedModelId)
    }

    @Test fun `refresh stores lastSyncedAt from the clock`() {
        val repo = FakeRepository(
            catalog(
                provider(entry(id = "m-1", lastSeenAt = 1), lastSyncedAt = 42),
                selectedModelId = "m-1",
                lastSelectedModelIdByProvider = mapOf("p-1" to "m-1"),
            ),
        )
        val source = FakeSource(
            ModelListResult.Success(
                listOf(snapshot("m-1", window = 131_072, output = 8_192)),
                truncated = false,
            ),
        )
        val service = ModelCatalogService(repo, source) { now }

        assertEquals(RefreshOutcome.Success(RefreshCounts(unchanged = 1)), service.refresh("p-1"))

        assertEquals(now, repo.saveModelsCalls.single().lastSyncedAtEpochMillis)
        assertEquals(now, repo.stored.providers.single().lastSyncedAtEpochMillis)
    }

    @Test fun `a failed fetch leaves the catalog and lastSyncedAt unchanged`() {
        val before = catalog(
            provider(entry(id = "m-1", lastSeenAt = 1), lastSyncedAt = 42),
            selectedModelId = "m-1",
            lastSelectedModelIdByProvider = mapOf("p-1" to "m-1"),
        )
        val repo = FakeRepository(before)
        val source = FakeSource(ModelListResult.Failure(ModelListError.TIMEOUT))
        var clockCalls = 0
        val service = ModelCatalogService(repo, source) {
            clockCalls += 1
            now
        }

        assertEquals(RefreshOutcome.Failed(ModelListError.TIMEOUT), service.refresh("p-1"))

        assertEquals(1, source.calls.size)
        assertEquals(0, clockCalls)
        assertTrue(repo.saveModelsCalls.isEmpty())
        assertEquals(before, repo.stored)
        assertEquals(42L, repo.stored.providers.single().lastSyncedAtEpochMillis)
    }

    @Test fun `restore defaults clears only that model overrides and rethrows on invalid metadata`() {
        val repo = FakeRepository(
            catalog(
                provider(
                    entry(
                        id = "m-1",
                        displayName = "Model One",
                        window = 200_000,
                        output = 4_000,
                        overrides = setOf(ModelField.CONTEXT_WINDOW_TOKENS),
                        lastSeenAt = 3,
                    ),
                    entry(id = "m-2", displayName = "Model Two", lastSeenAt = 4),
                    entry(id = "m-3", displayName = "Model Three", lastSeenAt = 5),
                    lastSyncedAt = 7,
                ),
                selectedModelId = "m-1",
                lastSelectedModelIdByProvider = mapOf("p-1" to "m-1"),
            ),
        )
        val source = FakeSource(
            ModelListResult.Success(
                listOf(
                    snapshot("m-1", displayName = "Gateway One", window = 65_536, output = 4_096, inputs = setOf(InputModality.TEXT)),
                    snapshot("m-2", displayName = "Model Two", window = 131_072, output = 8_192, inputs = setOf(InputModality.TEXT)),
                    // Deliberately unprojectable: the merge clamps this for storage, but "restore defaults" must report it.
                    snapshot("m-3", displayName = "Gateway Three", window = 1_000, output = 2_000, inputs = setOf(InputModality.TEXT)),
                ),
                truncated = false,
            ),
        )
        val service = ModelCatalogService(repo, source) { now }

        assertTrue(service.refresh("p-1") is RefreshOutcome.Success)
        val refreshed = repo.stored.providers.single().models

        val restored = checkNotNull(service.restoreDefaults("p-1", "m-1"))

        assertEquals(emptySet<ModelField>(), restored.overrides)
        assertEquals("Gateway One", restored.displayName)
        assertEquals(65_536, restored.contextWindowTokens)
        assertEquals(4_096, restored.maxOutputTokens)
        assertEquals(now, restored.lastSeenAtEpochMillis)

        assertEquals(2, repo.saveModelsCalls.size)
        val restoreSave = repo.saveModelsCalls[1]
        assertEquals("p-1", restoreSave.providerId)
        assertEquals("m-1", restoreSave.selectedModelId)
        // Restoring defaults is not a sync: the stored lastSyncedAt must stay untouched.
        assertNull(restoreSave.lastSyncedAtEpochMillis)
        assertEquals(restored, restoreSave.models.first { it.id == "m-1" })
        assertEquals(refreshed.first { it.id == "m-2" }, restoreSave.models.first { it.id == "m-2" })
        assertEquals(refreshed.first { it.id == "m-3" }, restoreSave.models.first { it.id == "m-3" })
        assertEquals(now, repo.stored.providers.single().lastSyncedAtEpochMillis)

        val storedBeforeRejection = repo.stored
        assertThrows(ContractError.ConfigurationRejected::class.java) {
            service.restoreDefaults("p-1", "m-3")
        }
        assertEquals(2, repo.saveModelsCalls.size)
        assertEquals(storedBeforeRejection, repo.stored)
    }

    @Test fun `a provider without a secret fails without calling the source`() {
        val before = catalog(
            provider(entry(id = "m-1"), secretHandleId = null),
            selectedModelId = "m-1",
            lastSelectedModelIdByProvider = mapOf("p-1" to "m-1"),
        )
        val repo = FakeRepository(before)
        val source = FakeSource(ModelListResult.Success(listOf(snapshot("m-1")), truncated = false))
        var clockCalls = 0
        val service = ModelCatalogService(repo, source) {
            clockCalls += 1
            now
        }

        assertEquals(RefreshOutcome.MissingSecret, service.refresh("p-1"))

        assertTrue(source.calls.isEmpty())
        assertTrue(repo.saveModelsCalls.isEmpty())
        assertEquals(0, clockCalls)
        assertEquals(before, repo.stored)
    }

    @Test fun `refresh reports no provider without calling the source`() {
        val repo = FakeRepository(catalog())
        val source = FakeSource(ModelListResult.Failure(ModelListError.AUTH))
        val service = ModelCatalogService(repo, source) { now }

        assertEquals(RefreshOutcome.NoProvider, service.refresh("missing"))

        assertTrue(source.calls.isEmpty())
        assertTrue(repo.saveModelsCalls.isEmpty())
    }

    @Test fun `restore defaults returns null when the provider or model is unknown`() {
        val repo = FakeRepository(catalog(provider(entry(id = "m-1"))))
        val source = FakeSource(ModelListResult.Failure(ModelListError.NETWORK))
        val service = ModelCatalogService(repo, source) { now }

        assertNull(service.restoreDefaults("p-x", "m-1"))
        assertNull(service.restoreDefaults("p-1", "m-x"))

        assertTrue(repo.saveModelsCalls.isEmpty())
    }

    @Test fun `restore defaults without a gateway fetch falls back to the known table`() {        val repo = FakeRepository(
            catalog(
                provider(
                    entry(
                        id = "deepseek-v4-pro",
                        displayName = "User V4 Pro",
                        window = 200_000,
                        output = 4_096,
                        overrides = setOf(ModelField.DISPLAY_NAME, ModelField.CONTEXT_WINDOW_TOKENS),
                        lastSeenAt = 9,
                    ),
                    lastSyncedAt = 42,
                ),
                selectedModelId = "deepseek-v4-pro",
                lastSelectedModelIdByProvider = mapOf("p-1" to "deepseek-v4-pro"),
            ),
        )
        val service = ModelCatalogService(repo, FakeSource(ModelListResult.Failure(ModelListError.NETWORK))) { now }

        val restored = checkNotNull(service.restoreDefaults("p-1", "deepseek-v4-pro"))

        assertEquals(emptySet<ModelField>(), restored.overrides)
        assertEquals("deepseek-v4-pro", restored.displayName)
        assertEquals(1_048_576, restored.contextWindowTokens)
        assertEquals(393_216, restored.maxOutputTokens)
        assertEquals(setOf(InputModality.TEXT), restored.inputs)
        assertTrue(restored.thinkingSupported)
        val save = repo.saveModelsCalls.single()
        assertEquals("deepseek-v4-pro", save.selectedModelId)
        assertNull(save.lastSyncedAtEpochMillis)
        assertEquals(42L, repo.stored.providers.single().lastSyncedAtEpochMillis)
    }

    @Test fun `restore defaults derives the thinking capability from the model id`() {
        // 0.53 T8: a legacy row stored unsupported becomes thinking-capable for a DeepSeek-family id.
        val repo = FakeRepository(
            catalog(
                provider(entry(id = "deepseek-chat", thinkingSupported = false, lastSeenAt = 1)),
                selectedModelId = "deepseek-chat",
                lastSelectedModelIdByProvider = mapOf("p-1" to "deepseek-chat"),
            ),
        )
        val service = ModelCatalogService(repo, FakeSource(ModelListResult.Failure(ModelListError.NETWORK))) { now }

        val restored = checkNotNull(service.restoreDefaults("p-1", "deepseek-chat"))

        assertTrue(restored.thinkingSupported)
        assertEquals("deepseek-chat", repo.saveModelsCalls.single().selectedModelId)
    }

    @Test fun `refresh propagates the gateway truncation flag`() {
        val repo = FakeRepository(catalog(provider(lastSyncedAt = 1)))
        val source = FakeSource(
            ModelListResult.Success(
                listOf(snapshot("m-1", window = 131_072, output = 8_192)),
                truncated = true,
            ),
        )
        val service = ModelCatalogService(repo, source) { now }

        val outcome = service.refresh("p-1") as RefreshOutcome.Success

        assertEquals(RefreshCounts(added = 1, truncated = true), outcome.counts)
    }

    private data class SourceCall(val baseUrl: String, val secret: SecretHandle)

    private class FakeSource(private val result: ModelListResult) : ModelListSource {
        val calls = mutableListOf<SourceCall>()

        override fun fetch(baseUrl: String, secret: SecretHandle): ModelListResult {
            calls += SourceCall(baseUrl, secret)
            return result
        }
    }

    private class FakeSecret(override val id: String) : SecretHandle

    /**
     * An in-memory catalog that behaves like the repository contract where the service depends on it: `saveModels`
     * replaces the model list, keeps the previous `lastSyncedAt` when the argument is null, and mirrors the selection.
     */
    private class FakeRepository(initial: ModelCatalog) : ModelCatalogRepository {
        data class SaveModelsCall(
            val providerId: String,
            val models: List<ModelEntry>,
            val selectedModelId: String?,
            val lastSyncedAtEpochMillis: Long?,
        )

        var stored: ModelCatalog = initial
        val saveModelsCalls = mutableListOf<SaveModelsCall>()

        override fun catalog(): ModelCatalog = stored

        override fun providerSecret(providerId: String): SecretHandle? =
            stored.providers.firstOrNull { it.id == providerId }?.secretHandleId?.let { FakeSecret(it) }

        override fun saveModels(
            providerId: String,
            models: List<ModelEntry>,
            selectedModelId: String?,
            lastSyncedAtEpochMillis: Long?,
        ): Provider {
            saveModelsCalls += SaveModelsCall(providerId, models, selectedModelId, lastSyncedAtEpochMillis)
            val index = stored.providers.indexOfFirst { it.id == providerId }
            check(index >= 0) { "saveModels for an unknown provider" }
            val old = stored.providers[index]
            val saved = old.copy(
                models = models,
                lastSyncedAtEpochMillis = lastSyncedAtEpochMillis ?: old.lastSyncedAtEpochMillis,
            )
            var next = stored.copy(providers = stored.providers.toMutableList().also { it[index] = saved })
            if (selectedModelId != null) {
                next = next.copy(
                    lastSelectedModelIdByProvider = next.lastSelectedModelIdByProvider + (providerId to selectedModelId),
                )
            }
            if (next.activeProviderId == providerId) next = next.copy(selectedModelId = selectedModelId)
            stored = next
            return saved
        }

        override fun saveProvider(provider: Provider, newSecret: CharArray?): Provider = error("unused")
        override fun deleteProvider(providerId: String) = error("unused")
        override fun selectModel(modelId: String) = error("unused")
        override fun setActiveProvider(providerId: String) = error("unused")
        override fun verify(profileId: String, result: CapabilityVerification) = error("unused")
        override fun isCredentialReferenced(handleId: String): Boolean = false
        override fun reset() = error("unused")
        override fun listenChanges(listener: (Set<String>) -> Unit): CancellationHandle = error("unused")
    }
}
