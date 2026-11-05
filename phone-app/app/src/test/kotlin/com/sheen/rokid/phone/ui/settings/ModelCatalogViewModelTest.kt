package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.GatewayModelSnapshot
import com.sheen.rokid.model.ModelListError
import com.sheen.rokid.model.ModelListResult
import com.sheen.rokid.model.ModelListSource
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.phone.model.ProbeConsent
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelCatalogService
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.model.domain
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.VerificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 0.52 design §5.9/§5.10: the catalog view model is the only writer of the encrypted catalog.
 *
 * Every case drives the real state machine with an in-memory blob store (or a fake repository where the case is about a
 * refusal the real store cannot produce), and asserts what the repository received, in what order, and what the page
 * state says afterwards. `Dispatchers.Unconfined` makes the serialized writes run inline, so an assertion after a call
 * sees the completed operation - the same code path production runs on its single-thread scope.
 */
class ModelCatalogViewModelTest {

    private val now = 1_700_000_000_000L

    // ---------------------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------------------

    /** The production credential contract: the store takes ownership of the caller's buffer and zeroes it. */
    private class Credentials : CatalogCredentialStore {
        val staged = mutableListOf<CharArray>()

        override fun stage(value: CharArray): SecretHandle {
            staged += value.copyOf()
            value.fill('\u0000')
            return object : SecretHandle {
                override val id = "credential-${UUID.randomUUID()}"
                override fun toString() = "SecretHandle(redacted)"
            }
        }

        override fun revoke(handle: SecretHandle) = Unit
    }

    /**
     * Pins the view model's own zeroing: unlike the production store this double keeps the caller's buffer untouched, so
     * only the view model can make it zero after the save.
     */
    private class LooseCredentials : CatalogCredentialStore {
        var lastStaged: CharArray? = null
        override fun stage(value: CharArray): SecretHandle {
            lastStaged = value
            return object : SecretHandle {
                override val id = "credential-loose"
                override fun toString() = "SecretHandle(redacted)"
            }
        }

        override fun revoke(handle: SecretHandle) = Unit
    }

    /** Records every mutation in call order and delegates; the ordering case asserts one exact sequence. */
    private class RecordingRepository(
        private val delegate: ModelCatalogRepository,
        private val order: MutableList<String>? = null,
    ) : ModelCatalogRepository by delegate {
        override fun saveProvider(provider: Provider, newSecret: CharArray?): Provider {
            order?.add("repo.saveProvider")
            return delegate.saveProvider(provider, newSecret)
        }

        override fun deleteProvider(providerId: String) {
            order?.add("repo.deleteProvider")
            delegate.deleteProvider(providerId)
        }

        override fun saveModels(
            providerId: String,
            models: List<ModelEntry>,
            selectedModelId: String?,
            lastSyncedAtEpochMillis: Long?,
        ): Provider {
            order?.add("repo.saveModels")
            return delegate.saveModels(providerId, models, selectedModelId, lastSyncedAtEpochMillis)
        }

        override fun selectModel(modelId: String) {
            order?.add("repo.selectModel")
            delegate.selectModel(modelId)
        }

        override fun setActiveProvider(providerId: String) {
            order?.add("repo.setActiveProvider")
            delegate.setActiveProvider(providerId)
        }

        override fun reset() {
            order?.add("repo.reset")
            delegate.reset()
        }
    }

    /** A fake repository that can report a corrupt catalog and nothing else. */
    private class CorruptRepository : ModelCatalogRepository {
        val calls = mutableListOf<String>()
        var corrupt = true

        override fun catalog(): ModelCatalog {
            calls += "catalog"
            if (corrupt) throw ContractError.ConfigurationRejected("Storage unavailable; reconfigure")
            return ModelCatalog()
        }

        override fun saveProvider(provider: Provider, newSecret: CharArray?): Provider {
            calls += "saveProvider"; error("corrupt catalog must not be written")
        }

        override fun deleteProvider(providerId: String) {
            calls += "deleteProvider"; error("corrupt catalog must not be written")
        }

        override fun saveModels(
            providerId: String,
            models: List<ModelEntry>,
            selectedModelId: String?,
            lastSyncedAtEpochMillis: Long?,
        ): Provider {
            calls += "saveModels"; error("corrupt catalog must not be written")
        }

        override fun selectModel(modelId: String) {
            calls += "selectModel"; error("corrupt catalog must not be written")
        }

        override fun setActiveProvider(providerId: String) {
            calls += "setActiveProvider"; error("corrupt catalog must not be written")
        }

        override fun verify(profileId: String, result: CapabilityVerification) = Unit
        override fun providerSecret(providerId: String): SecretHandle? = null
        override fun isCredentialReferenced(handleId: String): Boolean = false

        override fun reset() {
            calls += "reset"
            corrupt = false
        }

        override fun listenChanges(listener: (Set<String>) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class Source(var result: ModelListResult) : ModelListSource {
        val calls = mutableListOf<String>()
        override fun fetch(baseUrl: String, secret: SecretHandle): ModelListResult {
            calls += baseUrl
            return result
        }
    }

    private class RecordingTester : ProfileTester {
        val calls = mutableListOf<String>()
        val events = mutableListOf<ModelStreamEvent>()

        override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            run(profileId, listener, image = false)

        override fun testImage(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            run(profileId, listener, image = true)

        private fun run(profileId: String, listener: (ModelStreamEvent) -> Unit, image: Boolean): CancellationHandle {
            calls += if (image) "testImage:$profileId" else "testText:$profileId"
            val event = ModelStreamEvent.Terminal("probe", ModelEnd.COMPLETED, ModelUsage(null, null), null)
            events += event
            listener(event)
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    private fun repository(credentials: CatalogCredentialStore = Credentials()): ModelCatalogRepository =
        AndroidModelCatalogRepository(TestBlobs(), credentials)

    private fun entry(
        id: String,
        image: Boolean = true,
        origin: ModelOrigin = ModelOrigin.MANUAL,
        window: Int = 131_072,
        output: Int = 8_192,
        verifications: List<CapabilityVerification> = emptyList(),
        missing: Boolean = false,
    ) = ModelEntry(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = if (image) setOf(InputModality.TEXT, InputModality.IMAGE) else setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        origin = origin,
        missing = missing,
        verifications = verifications,
    )

    private fun provider(id: String, name: String = "Provider $id", vararg models: ModelEntry) = Provider(
        id = id,
        name = name,
        baseUrl = "https://synthetic.example/v1",
        models = models.toList(),
    )

    private fun save(repository: ModelCatalogRepository, provider: Provider) {
        repository.saveProvider(provider, "sk-fixture-${provider.id}".toCharArray())
    }

    private fun snapshot(id: String, image: Boolean = true) = GatewayModelSnapshot(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = if (image) setOf(InputModality.TEXT, InputModality.IMAGE) else setOf(InputModality.TEXT),
    )

    private fun viewModel(
        repository: ModelCatalogRepository,
        source: ModelListSource = Source(ModelListResult.Success(emptyList(), false)),
        service: ModelCatalogService? = ModelCatalogService(repository, source) { now },
        tester: ProfileTester? = null,
        probeConsent: (ProbeConsent) -> Unit = {},
        beforeSelectionChange: () -> Unit = {},
        selectionChanged: (ModelProfile?) -> Unit = {},
        sessionRunning: () -> Boolean = { false },
    ) = ModelCatalogViewModel(
        repository = repository,
        service = service,
        tester = tester,
        probeConsent = probeConsent,
        beforeSelectionChange = beforeSelectionChange,
        selectionChanged = selectionChanged,
        sessionRunning = sessionRunning,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
    )

    private fun draft(
        providerId: String? = null,
        name: String = "DeepSeek",
        baseUrl: String = "https://api.deepseek.com/v1",
        key: CharArray? = "sk-test-1234".toCharArray(),
    ) = ProviderDraft(
        name = name,
        baseUrl = baseUrl,
        apiKey = key,
        protocolId = "OPENAI_RESPONSES",
        timeoutMillis = 30_000,
        providerId = providerId,
    )

    private fun manual(
        id: String = "m-manual",
        displayName: String = "Manual",
        windowK: String = "128",
        outputK: String = "8",
        inputs: Set<InputModality> = setOf(InputModality.TEXT, InputModality.IMAGE),
    ) = ManualModelDraft(
        id = id,
        displayName = displayName,
        contextWindowK = windowK,
        maxOutputK = outputK,
        inputs = inputs,
    )

    // ---------------------------------------------------------------------------------------------------------
    // provider save / first fetch
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `saving a provider stages the key and tries one fetch`() {
        val credentials = LooseCredentials()
        val repository = repository(credentials)
        val source = Source(ModelListResult.Success(listOf(snapshot("m-1")), truncated = false))
        val model = viewModel(repository, source)
        val key = "sk-test-1234".toCharArray()
        model.enter()

        model.saveProvider(draft(key = key))

        assertEquals("the provider save is followed by exactly one gateway fetch", listOf("https://api.deepseek.com/v1"), source.calls)
        val catalog = repository.catalog()
        val active = requireNotNull(catalog.activeProvider()) { "the first provider must become active" }
        assertEquals("m-1", active.models.single().id)
        assertEquals("the gateway-fetched row is stored on the provider", ModelOrigin.GATEWAY, active.models.single().origin)
        assertTrue("the view model zeroes the caller's key after the save", key.all { it == '\u0000' })
        assertEquals("sk-••••1234", model.state.value.keyMask[active.id])
        assertNull(model.state.value.error)
    }

    @Test
    fun `a failed first fetch keeps the provider and shows the mapped error`() {
        val repository = repository()
        val source = Source(ModelListResult.Failure(ModelListError.AUTH))
        val model = viewModel(repository, source)
        model.enter()

        model.saveProvider(draft())

        val catalog = repository.catalog()
        val saved = requireNotNull(catalog.providers.firstOrNull()) { "a failed fetch must not delete the provider" }
        assertEquals("DeepSeek", saved.name)
        assertEquals("the provider still becomes active", saved.id, catalog.activeProviderId)
        assertEquals("API Key 无效或无权限", model.state.value.error)
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `editing a provider copies the stored provider so its models survive`() {
        val repository = repository()
        save(repository, provider("p-1", "Original", entry("m-1")))
        val model = viewModel(repository)
        model.enter()

        model.saveProvider(draft(providerId = "p-1", name = "Renamed", key = null))

        val saved = requireNotNull(repository.catalog().providers.firstOrNull { it.id == "p-1" })
        assertEquals("Renamed", saved.name)
        assertEquals("saveProvider replaces the whole provider, so the edit must copy the stored one",
            listOf("m-1"), saved.models.map { it.id })
        assertEquals("an edit without a new key keeps the stored credential", false, saved.secretHandleId.isNullOrBlank())
    }

    // ---------------------------------------------------------------------------------------------------------
    // switching
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `switching provider stops the session before writing the selection`() {
        val order = mutableListOf<String>()
        val repository = RecordingRepository(repository(), order)
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-2")))
        repository.setActiveProvider("p-1")
        order.clear()
        val model = viewModel(
            repository,
            beforeSelectionChange = { order += "stop" },
            selectionChanged = { order += "changed" },
        )
        model.enter()

        model.setActiveProvider("p-2")

        assertEquals(listOf("stop", "repo.setActiveProvider", "changed"), order)
        assertEquals("p-2", model.state.value.activeProvider?.id)
        assertEquals("已切换到 Two", model.state.value.notice)
    }

    @Test
    fun `switching while a session runs asks for confirmation first`() {
        val order = mutableListOf<String>()
        val repository = RecordingRepository(repository(), order)
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-2")))
        repository.setActiveProvider("p-1")
        order.clear()
        val model = viewModel(
            repository,
            beforeSelectionChange = { order += "stop" },
            selectionChanged = { order += "changed" },
            sessionRunning = { true },
        )
        model.enter()

        model.setActiveProvider("p-2")

        assertTrue("a running session must be confirmed before it is stopped",
            model.state.value.confirmation is CatalogConfirmation.SwitchProvider)
        assertEquals("nothing is stopped or written before the confirmation", emptyList<String>(), order)
        assertEquals("p-1", repository.catalog().activeProviderId)

        model.confirmPending()

        assertEquals(listOf("stop", "repo.setActiveProvider", "changed"), order)
        assertEquals("p-2", repository.catalog().activeProviderId)
        assertNull(model.state.value.confirmation)
    }

    @Test
    fun `switching provider restores its last model`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-3"), entry("m-4")))
        repository.setActiveProvider("p-1")
        repository.setActiveProvider("p-2")
        repository.selectModel("m-4")
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()
        assertEquals("m-1", model.state.value.selectedModel?.id)

        model.setActiveProvider("p-2")

        assertEquals("the repository's restored selection reaches the page", "m-4", model.state.value.selectedModel?.id)
        assertEquals("m-4", repository.catalog().selectedModelId)
    }

    // ---------------------------------------------------------------------------------------------------------
    // deleting
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `deleting a provider and a model asks for confirmation`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1"), entry("m-2")))
        save(repository, provider("p-2", "Two", entry("m-3")))
        repository.setActiveProvider("p-1")
        repository.selectModel("m-1")
        val model = viewModel(repository)
        model.enter()

        model.deleteProvider("p-2", newActiveId = null)
        assertTrue(model.state.value.confirmation is CatalogConfirmation.DeleteProvider)
        assertNotNull("nothing is deleted before the confirmation", repository.catalog().providers.firstOrNull { it.id == "p-2" })
        model.confirmPending()
        assertNull(repository.catalog().providers.firstOrNull { it.id == "p-2" })
        assertNull(model.state.value.confirmation)

        model.deleteModel("m-1")
        assertTrue(model.state.value.confirmation is CatalogConfirmation.DeleteModel)
        assertNotNull("nothing is deleted before the confirmation", repository.catalog().activeProvider()?.models?.firstOrNull { it.id == "m-1" })
        model.confirmPending()
        assertNull(repository.catalog().activeProvider()?.models?.firstOrNull { it.id == "m-1" })
    }

    @Test
    fun `deleting the active provider requires a new active provider`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-2")))
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()

        model.deleteProvider("p-1", newActiveId = null)
        model.confirmPending()

        assertEquals("请先选择新的生效提供商", model.state.value.error)
        assertNotNull("the active provider must not be deleted without a successor",
            repository.catalog().providers.firstOrNull { it.id == "p-1" })
        assertEquals("p-1", repository.catalog().activeProviderId)

        model.confirmPending(newActiveId = "p-2")

        assertNull(repository.catalog().providers.firstOrNull { it.id == "p-1" })
        assertEquals("p-2", repository.catalog().activeProviderId)
        assertEquals("p-2", model.state.value.activeProvider?.id)
    }

    @Test
    fun `deleting the selected model falls back to the first image capable model`() {
        val repository = repository()
        save(repository, provider(
            "p-1",
            "One",
            entry("m-text", image = false),
            entry("m-image", image = true),
            entry("m-image-2", image = true),
        ))
        repository.setActiveProvider("p-1")
        repository.selectModel("m-image")
        val model = viewModel(repository)
        model.enter()

        model.deleteModel("m-image")
        model.confirmPending()

        assertEquals("the first remaining image-capable row becomes selected", "m-image-2", repository.catalog().selectedModelId)
        assertEquals("m-image-2", model.state.value.selectedModel?.id)

        model.deleteModel("m-image-2")
        model.confirmPending()

        assertNull("no image-capable row left, so nothing is selected", repository.catalog().selectedModelId)
        assertNull(model.state.value.selectedModel)
    }

    // ---------------------------------------------------------------------------------------------------------
    // model selection / manual rows
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `selecting a model requires a fresh text verification`() {
        val repository = repository()
        // The first image-capable row is auto-selected on activation; m-1 must be selected explicitly, so a preceding
        // row keeps the page from starting on the target.
        save(repository, provider(
            "p-1",
            "One",
            entry("m-pre"),
            entry("m-1"),
            entry("m-text", image = false),
        ))
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()

        model.selectModel("m-1")
        assertEquals("请先在模型详情测试连接", model.state.value.error)
        assertEquals("the failed selection leaves the previous choice in place", "m-pre", repository.catalog().selectedModelId)

        val profileId = "p-1:m-1"
        repository.verify(profileId, CapabilityVerification(0, "TEXT_REQUEST", VerificationState.Verified, now))
        model.selectModel("m-1")
        assertEquals("evidence from a previous revision must not count", "请先在模型详情测试连接", model.state.value.error)
        assertEquals("m-pre", repository.catalog().selectedModelId)

        repository.verify(profileId, CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now))
        model.selectModel("m-1")
        assertNull(model.state.value.error)
        assertEquals("m-1", repository.catalog().selectedModelId)

        model.selectModel("m-text")
        assertEquals("不支持图片，助手不可用", model.state.value.error)
    }

    @Test
    fun `a manual model with a duplicate id is refused`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()

        model.addManualModel(manual(id = "m-1"))

        assertEquals("模型 ID 已存在", model.state.value.error)
        assertEquals(1, repository.catalog().activeProvider()?.models?.size)

        model.addManualModel(manual(id = "m-new", displayName = "New Model"))

        assertNull(model.state.value.error)
        val added = requireNotNull(repository.catalog().activeProvider()?.models?.firstOrNull { it.id == "m-new" })
        assertEquals(ModelOrigin.MANUAL, added.origin)
        assertEquals("New Model", added.displayName)
        assertEquals("128 K is 131072 tokens", 131_072, added.contextWindowTokens)
        assertEquals(8_192, added.maxOutputTokens)
    }

    @Test
    fun `a missing selected model keeps the choice and warns`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        repository.setActiveProvider("p-1")
        repository.selectModel("m-1")
        repository.saveModels("p-1", emptyList(), selectedModelId = "m-1")
        val model = viewModel(repository)
        model.enter()

        assertNull(model.state.value.selectedModel)
        assertNotNull(model.state.value.warning)
        assertTrue("the warning tells the user to pick again", model.state.value.warning!!.contains("重新选择"))
        assertEquals("the stored choice is not silently cleared", "m-1", repository.catalog().selectedModelId)
    }

    // ---------------------------------------------------------------------------------------------------------
    // corruption / reset
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a corrupt catalog offers reset that only deletes the catalog`() {
        val repository = CorruptRepository()
        val model = viewModel(repository)
        model.enter()

        assertTrue(model.state.value.corrupt)
        assertNull(model.state.value.catalog)

        model.resetCatalog()

        assertEquals(listOf("catalog", "reset", "catalog"), repository.calls)
        assertFalse(model.state.value.corrupt)
        assertNotNull(model.state.value.catalog)
    }

    // ---------------------------------------------------------------------------------------------------------
    // page lifecycle (design §4.4)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `leaving the page cancels in flight work and re entering re reads the catalog`() {
        val reads = mutableListOf<String>()
        val delegate = repository()
        save(delegate, provider("p-1", "One", entry("m-1")))
        val source = Source(ModelListResult.Failure(ModelListError.AUTH))
        val repository = object : ModelCatalogRepository by delegate {
            override fun catalog(): ModelCatalog {
                reads += "catalog"
                return delegate.catalog()
            }
        }
        val model = viewModel(repository, source)
        model.enter()
        assertEquals("entering reads the catalog once", 1, reads.size)

        model.leave()
        model.refresh()
        assertTrue("an operation requested after leave is not started", source.calls.isEmpty())

        model.enter()
        assertEquals("re-entering re-reads the catalog", 2, reads.size)
    }

    // ---------------------------------------------------------------------------------------------------------
    // probe seam (the detail page consumes this)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `testing a model grants probe consent before the tester runs`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        repository.setActiveProvider("p-1")
        val order = mutableListOf<String>()
        val tester = RecordingTester()
        val model = viewModel(
            repository,
            tester = tester,
            probeConsent = { consent -> order += "consent:${consent.id}:${consent.revision}:${consent.image}" },
        )
        model.enter()

        model.testModel("m-1", withImage = false)

        assertEquals(
            listOf("consent:p-1:m-1:1:false", "testText:p-1:m-1"),
            order + tester.calls,
        )
        assertTrue(model.state.value.lastTest?.passed == true)
        assertEquals(domain("https://synthetic.example/v1"), "synthetic.example")
    }
}
