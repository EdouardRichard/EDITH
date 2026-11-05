package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.ControlledProfileTester
import com.sheen.rokid.model.GatewayModelSnapshot
import com.sheen.rokid.model.ModelListError
import com.sheen.rokid.model.ModelListResult
import com.sheen.rokid.model.ModelListSource
import com.sheen.rokid.model.ProfileProbeTransport
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.phone.model.ProbeConsent
import com.sheen.rokid.phone.model.ProfileProbeGate
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelCatalogService
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.model.domain
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.CatalogProfileRepository
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.MonotonicClock
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

    /**
     * The production probe chain over an in-memory catalog: `ProfileProbeGate.grant` is the consent seam, the gate
     * admits the probe, and `ControlledProfileTester` records `TEXT_REQUEST`/`IMAGE_REQUEST` through
     * `CatalogProfileRepository.verify`. The transport answers synchronously with [end]/[safeCode], so a test observes
     * the exact evidence a real terminal would write (Task 14's selection prerequisite).
     */
    private fun controlledProbes(
        repository: ModelCatalogRepository,
        end: ModelEnd = ModelEnd.COMPLETED,
        safeCode: String? = null,
    ): Pair<ProfileTester, (ProbeConsent) -> Unit> {
        val profiles = CatalogProfileRepository(repository)
        val gate = ProfileProbeGate(profiles)
        val transport = ProfileProbeTransport { _, listener ->
            listener(ModelStreamEvent.Terminal("probe", end, ModelUsage(null, null), safeCode))
            object : CancellationHandle { override fun cancel() = Unit }
        }
        val controlled = ControlledProfileTester(
            client = transport,
            repository = profiles,
            clock = object : MonotonicClock { override fun nowNanos() = 0L },
            probeImageRef = { "probe-image" },
        )
        val tester = object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
                gate.test(controlled, profileId, listener)

            override fun testImage(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
                gate.testImage(controlled, profileId, listener)
        }
        return tester to gate::grant
    }

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
        // I1: a restore only picks a model whose TEXT evidence matches the provider's current revision.
        repository.verify("p-2:m-4", CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now))
        repository.verify("p-1:m-1", CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now))
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
        val current = CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now)
        save(repository, provider(
            "p-1",
            "One",
            entry("m-text", image = false),
            entry("m-image", image = true),
            entry("m-image-2", image = true, verifications = listOf(current)),
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

    @Test
    fun `deleting the selected model never auto-selects an untested image-capable row`() {
        val repository = repository()
        save(repository, provider(
            "p-1",
            "One",
            entry("m-sel", verifications = listOf(
                CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now))),
            // Image-capable but never tested: the design's radio is disabled for it, so a delete fallback must not pick it.
            entry("m-untested", image = true),
        ))
        repository.setActiveProvider("p-1")
        repository.selectModel("m-sel")
        val model = viewModel(repository)
        model.enter()

        model.deleteModel("m-sel")
        model.confirmPending()

        assertNull("no tested image-capable candidate: leave the selection empty", repository.catalog().selectedModelId)
        assertNull(model.state.value.selectedModel)
    }

    @Test
    fun `selecting a gateway-removed model is refused even with fresh evidence`() {
        val repository = repository()
        val current = CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now)
        save(repository, provider(
            "p-1",
            "One",
            entry("m-ok", verifications = listOf(current)),
            entry("m-removed", missing = true, verifications = listOf(current)),
        ))
        repository.setActiveProvider("p-1")
        repository.selectModel("m-ok")
        val model = viewModel(repository)
        model.enter()

        model.selectModel("m-removed")

        assertEquals("该模型已被网关移除，无法选择", model.state.value.error)
        assertEquals("the current choice stays", "m-ok", repository.catalog().selectedModelId)
    }

    @Test
    fun `deleting a model uses the active provider when two providers share its id`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-1")))
        repository.setActiveProvider("p-2")
        val model = viewModel(repository)
        model.enter()

        model.deleteModel("m-1")
        model.confirmPending()

        assertEquals(
            "the model is deleted from the active provider",
            emptyList<String>(),
            repository.catalog().providers.first { it.id == "p-2" }.models.map { it.id },
        )
        assertEquals(
            "the inactive provider with the same model id is untouched",
            listOf("m-1"),
            repository.catalog().providers.first { it.id == "p-1" }.models.map { it.id },
        )
    }

    @Test
    fun `updating a model uses the addressed provider when two providers share its id`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-1")))
        repository.setActiveProvider("p-2")
        val model = viewModel(repository)
        model.enter()
        val original = requireNotNull(repository.catalog().activeProvider())
            .models
            .single { it.id == "m-1" }

        model.updateModel("p-2", original, original.copy(displayName = "Renamed"))

        assertNull(model.state.value.error)
        assertEquals(
            "the addressed provider's row is the one that changed",
            "Renamed",
            repository.catalog().providers.first { it.id == "p-2" }.models.single { it.id == "m-1" }.displayName,
        )
        assertEquals(
            "the other provider's same-id row is untouched",
            "Model m-1",
            repository.catalog().providers.first { it.id == "p-1" }.models.single { it.id == "m-1" }.displayName,
        )
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
        assertNull("no row is auto-selected before its text test", repository.catalog().selectedModelId)

        val profileId = "p-1:m-1"
        repository.verify(profileId, CapabilityVerification(0, "TEXT_REQUEST", VerificationState.Verified, now))
        model.selectModel("m-1")
        assertEquals("evidence from a previous revision must not count", "请先在模型详情测试连接", model.state.value.error)
        assertNull(repository.catalog().selectedModelId)

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
    fun `a manual model id is trimmed before the duplicate check`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()

        model.addManualModel(manual(id = "m-1 "))

        assertEquals(
            "a trailing space must not slip a second m-1 past the duplicate check",
            "模型 ID 已存在",
            model.state.value.error,
        )
        assertEquals(1, repository.catalog().activeProvider()?.models?.size)

        model.addManualModel(manual(id = "  m-trim  ", displayName = "Trimmed"))

        assertNull(model.state.value.error)
        assertEquals(
            "the stored id is the normalized one",
            listOf("m-1", "m-trim"),
            repository.catalog().activeProvider()?.models?.map { it.id },
        )
    }

    @Test
    fun `a manual model display name with a control character is refused`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()

        listOf("Bad\tName", "Bad\nName").forEach { pasted ->
            model.addManualModel(manual(id = "m-control", displayName = pasted))

            assertEquals(
                "a pasted tab or newline must become a field error, never a ModelEntry invariant crash",
                "显示名不能包含控制字符",
                model.state.value.error,
            )
            assertEquals("nothing was written", 1, repository.catalog().activeProvider()?.models?.size)
        }
    }

    @Test
    fun `a selected model removed from the catalog is cleared on save`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        repository.setActiveProvider("p-1")
        repository.selectModel("m-1")
        // I2: the model list no longer contains the selected id; the write must not keep pointing at it.
        repository.saveModels("p-1", emptyList(), selectedModelId = "m-1")
        val model = viewModel(repository)
        model.enter()

        assertNull(model.state.value.selectedModel)
        assertNull("the stored selection is cleared, not silently kept", repository.catalog().selectedModelId)
        assertFalse(repository.catalog().lastSelectedModelIdByProvider.containsKey("p-1"))
    }

    @Test
    fun `a gateway-removed selected model keeps the choice and warns`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1", missing = true)))
        repository.setActiveProvider("p-1")
        repository.saveModels(
            "p-1",
            requireNotNull(repository.catalog().activeProvider()).models,
            selectedModelId = "m-1",
        )
        val model = viewModel(repository)
        model.enter()

        assertEquals("m-1", model.state.value.selectedModel?.id)
        assertEquals(ModelCatalogUi.MISSING_GATEWAY, model.state.value.warning)
    }

    @Test
    fun `a deleted model id is not resurrected by a later refresh`() {
        val repository = repository()
        save(repository, provider(
            "p-1",
            "One",
            entry("m-1", verifications = listOf(
                CapabilityVerification(1, "TEXT_REQUEST", VerificationState.Verified, now))),
        ))
        repository.setActiveProvider("p-1")
        val source = Source(ModelListResult.Success(listOf(snapshot("m-1")), truncated = false))
        val model = viewModel(repository, source)
        model.enter()

        model.deleteModel("m-1")
        model.confirmPending()
        assertNull(repository.catalog().selectedModelId)

        // The gateway offers the deleted id again; the stale restore entry must not turn it back into the selection.
        model.refresh()

        assertEquals("the refreshed row exists again", listOf("m-1"),
            repository.catalog().activeProvider()?.models?.map { it.id })
        assertNull("a deleted selection must not resurrect on refresh", repository.catalog().selectedModelId)
        assertNull(model.state.value.selectedModel)
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

    @Test
    fun `an unreadable catalog during an action shows the corrupt state instead of crashing`() {
        val delegate = repository()
        save(delegate, provider("p-1", "One", entry("m-1")))
        delegate.setActiveProvider("p-1")
        val repository = object : ModelCatalogRepository by delegate {
            var failReads = false

            override fun catalog(): ModelCatalog {
                if (failReads) throw ContractError.ConfigurationRejected("Storage unavailable; reconfigure")
                return delegate.catalog()
            }
        }
        val model = viewModel(repository)
        model.enter()
        assertFalse(model.state.value.corrupt)
        assertNotNull(model.state.value.catalog)
        repository.failReads = true

        model.selectModel("m-1")

        assertTrue(
            "an unreadable catalog read must become the modeled corrupt state, not an uncaught coroutine crash",
            model.state.value.corrupt,
        )
        assertNull("the stale snapshot is not kept as if it were the catalog", model.state.value.catalog)
        assertNull(model.state.value.activeProvider)
        assertNull(model.state.value.selectedModel)
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

    @Test
    fun `a passing text and image probe records the evidence that unlocks selection`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1", image = true)))
        repository.setActiveProvider("p-1")
        val (tester, consent) = controlledProbes(repository)
        val model = viewModel(repository, tester = tester, probeConsent = consent)
        model.enter()

        val before = requireNotNull(repository.catalog().activeProvider())
        assertFalse(
            "without evidence the image-capable row is not selectable",
            ModelCatalogUi.canSelect(before.models.single { it.id == "m-1" }, before),
        )

        model.testModel("m-1", withImage = false)

        val afterText = requireNotNull(repository.catalog().activeProvider())
        val textEntry = afterText.models.single { it.id == "m-1" }
        assertTrue(
            "a passing text probe writes current-revision TEXT_REQUEST evidence",
            textEntry.verifications.any {
                it.operation == "TEXT_REQUEST" && it.state == VerificationState.Verified &&
                    it.profileRevision == afterText.revision
            },
        )
        assertTrue("TEXT_REQUEST is the selection prerequisite", ModelCatalogUi.canSelect(textEntry, afterText))
        assertTrue(model.state.value.lastTest?.passed == true)

        model.testModel("m-1", withImage = true)

        val afterImage = requireNotNull(repository.catalog().activeProvider())
        val imageEntry = afterImage.models.single { it.id == "m-1" }
        assertTrue(
            "the image probe writes its own IMAGE_REQUEST evidence",
            imageEntry.verifications.any {
                it.operation == "IMAGE_REQUEST" && it.state == VerificationState.Verified &&
                    it.profileRevision == afterImage.revision
            },
        )
        assertTrue(ModelCatalogUi.imageVerified(imageEntry, afterImage.revision))
        assertTrue(model.state.value.lastTest?.image == true)
    }

    @Test
    fun `a failed probe shows the safe code and writes no evidence`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1", image = true)))
        repository.setActiveProvider("p-1")
        val (tester, consent) = controlledProbes(repository, end = ModelEnd.FAILED, safeCode = "HTTP_500")
        val model = viewModel(repository, tester = tester, probeConsent = consent)
        model.enter()

        model.testModel("m-1", withImage = false)

        assertEquals("测试失败：HTTP_500", model.state.value.lastTest?.message)
        assertFalse(model.state.value.lastTest?.passed == true)
        val provider = requireNotNull(repository.catalog().activeProvider())
        val entry = provider.models.single { it.id == "m-1" }
        assertTrue("a failed probe records a Failed verification", entry.verifications.any {
            it.operation == "TEXT_REQUEST" && it.state == VerificationState.Failed
        })
        assertFalse(ModelCatalogUi.canSelect(entry, provider))
    }

    // ---------------------------------------------------------------------------------------------------------
    // detail-page delete scoping (T13 review carry-over)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a model delete confirmation stays scoped to the provider that opened it`() {
        val repository = repository()
        save(repository, provider("p-1", "One", entry("m-1")))
        save(repository, provider("p-2", "Two", entry("m-1")))
        repository.setActiveProvider("p-1")
        val model = viewModel(repository)
        model.enter()

        model.deleteModel("m-1")

        val pending = model.state.value.confirmation as CatalogConfirmation.DeleteModel
        assertEquals("the confirmation remembers the provider it was opened from", "p-1", pending.providerId)
        assertEquals("m-1", pending.modelId)

        // The UI cannot switch providers behind the dialog; the confirmation must still refuse rather than deleting
        // the now-active provider's same-id row.
        repository.setActiveProvider("p-2")
        model.confirmPending()

        assertEquals(
            listOf("m-1"),
            repository.catalog().providers.first { it.id == "p-1" }.models.map { it.id },
        )
        assertEquals(
            "the non-addressed provider's same-id row is untouched",
            listOf("m-1"),
            repository.catalog().providers.first { it.id == "p-2" }.models.map { it.id },
        )
        assertNull(model.state.value.confirmation)
    }

    @Test
    fun `a key handed to a page that has already left is zeroed`() {
        val credentials = LooseCredentials()
        val repository = repository(credentials)
        val model = viewModel(repository)
        model.enter()
        model.leave()

        val key = "sk-abandoned-9876".toCharArray()
        model.saveProvider(draft(key = key))

        assertTrue("the early return must not keep the caller's key buffer", key.all { it == '\u0000' })
        assertNull("nothing is staged after the page left", credentials.lastStaged)
        assertTrue(repository.catalog().providers.isEmpty())
        assertFalse(model.state.value.saving)
    }
}
