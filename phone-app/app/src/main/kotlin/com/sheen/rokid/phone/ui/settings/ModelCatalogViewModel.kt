package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.model.ProbeConsent
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelCatalogService
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.model.catalog.RefreshOutcome
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.phone.model.domain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 0.52 design §§5.9/5.10: the page state owner for the model service, provider list and provider form.
 *
 * Every catalog read-modify-write runs inside one [Mutex] on the host-provided [scope], so a fetch, a save, a switch and
 * a delete can never interleave even when the user taps quickly. The repository stays the only persisted authority; this
 * class only decides *when* to write and turns the outcome into page copy.
 *
 * Three safety rules are enforced here rather than in the composables:
 *  - [beforeSelectionChange] runs before any write that changes `activeProviderId`/`selectedModelId`; a switch made while
 *    a session runs first becomes a [CatalogConfirmation.SwitchProvider] (copy: 「切换会先停止当前会话，之后需重新开始」),
 *    then the stop, then the write, then [selectionChanged] with the restored profile (making the old provider's rows
 *    untouched - switching never deletes a provider);
 *  - the caller's API key buffer is zeroed after every save, successful or refused, and only a `sk-••••{last4}` mask ever
 *    enters [ModelCatalogUiState];
 *  - [leave] cancels the scope, so in-flight fetches/writes die with the page; the shell builds a fresh view model when the
 *    page is entered again and [enter] re-reads the catalog.
 */
internal class ModelCatalogViewModel(
    private val repository: ModelCatalogRepository,
    private val service: ModelCatalogService?,
    private val tester: ProfileTester?,
    private val probeConsent: (ProbeConsent) -> Unit = {},
    private val beforeSelectionChange: () -> Unit = {},
    private val selectionChanged: (ModelProfile?) -> Unit = {},
    private val sessionRunning: () -> Boolean = { false },
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ModelCatalogUiState(testAvailable = tester != null))
    val state: StateFlow<ModelCatalogUiState> = _state.asStateFlow()

    @Volatile
    private var entered = false

    /** The page (or catalog page group) appeared: one catalog read, then the user's actions. */
    fun enter() {
        if (entered) return
        entered = true
        reload()
    }

    /** The page left composition: cancel everything still running. A re-entry uses a new view model. */
    fun leave() {
        entered = false
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------------------------------------------

    /** Fetch the gateway's model list for the active provider; the summary stays until dismissed or the page leaves. */
    fun refresh() = launch {
        val provider = _state.value.activeProvider
        if (provider == null) {
            _state.update { it.copy(error = ModelCatalogUi.ADD_PROVIDER_FIRST) }
            return@launch
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            when (val outcome = service?.refresh(provider.id)) {
                null -> _state.update { it.copy(error = ModelCatalogUi.UNAVAILABLE) }
                is RefreshOutcome.Success -> {
                    _state.update {
                        it.copy(refreshSummary = ModelCatalogUi.refreshSummary(outcome.counts), error = null)
                    }
                }
                is RefreshOutcome.Failed -> _state.update { it.copy(error = ModelCatalogUi.refreshError(outcome.error)) }
                RefreshOutcome.NoProvider -> _state.update { it.copy(error = ModelCatalogUi.PROVIDER_MISSING) }
                RefreshOutcome.MissingSecret -> _state.update { it.copy(error = ModelCatalogUi.MISSING_SECRET) }
            }
        } catch (error: Throwable) {
            _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(loading = false) }
            reload()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // provider writes
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Creates or edits one provider, then tries one fetch (the design's "保存后自动尝试获取模型"; a failed fetch never
     * undoes the save). Editing copies the stored provider because [ModelCatalogRepository.saveProvider] replaces the
     * whole row - building a fresh `Provider` would silently drop that provider's model list.
     */
    fun saveProvider(draft: ProviderDraft) {
        val key = draft.apiKey
        val nameError = ModelCatalogUi.providerNameError(draft.name)
        val urlError = ModelCatalogUi.providerBaseUrlError(draft.baseUrl, allowLocalHttp = BuildConfig.DEBUG)
        val newProvider = draft.providerId == null
        val keyError = if (newProvider && (key == null || key.isEmpty())) ModelCatalogUi.KEY_REQUIRED else null
        if (nameError != null || urlError != null || keyError != null) {
            key?.fill('\u0000')
            _state.update { it.copy(error = nameError ?: urlError ?: keyError, notice = null) }
            return
        }
        val mask = key?.takeIf { it.isNotEmpty() }?.let(ModelCatalogUi::keyMask)
        launch {
            _state.update { it.copy(saving = true, error = null, notice = null, savedProviderId = null) }
            try {
                val catalog = repository.catalog()
                val saved = if (draft.providerId != null) {
                    val existing = catalog.providers.firstOrNull { it.id == draft.providerId }
                        ?: throw ContractError.ConfigurationRejected("提供商不存在")
                    repository.saveProvider(
                        existing.copy(
                            name = draft.name.trim(),
                            baseUrl = draft.baseUrl.trim(),
                            protocolId = draft.protocolId,
                            timeoutMillis = draft.timeoutMillis,
                        ),
                        key,
                    )
                } else {
                    repository.saveProvider(
                        Provider(
                            id = UUID.randomUUID().toString(),
                            name = draft.name.trim(),
                            baseUrl = draft.baseUrl.trim(),
                            protocolId = draft.protocolId,
                            timeoutMillis = draft.timeoutMillis,
                        ),
                        key,
                    )
                }
                // R18: the first provider becomes active explicitly; storage never guesses this UI policy.
                if (repository.catalog().activeProviderId == null) repository.setActiveProvider(saved.id)
                val fetchError = fetchAfterSave(saved.id)
                val refreshed = repository.catalog()
                if (mask != null) _state.update { it.copy(keyMask = it.keyMask + (saved.id to mask)) }
                applyCatalog(refreshed)
                _state.update {
                    it.copy(
                        savedProviderId = saved.id,
                        notice = ModelCatalogUi.savedProvider(saved.name),
                        error = fetchError,
                    )
                }
            } catch (error: Throwable) {
                _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
            } finally {
                key?.fill('\u0000')
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Activates [providerId]; when a session runs the page must confirm the stop first. */
    fun setActiveProvider(providerId: String) = launch {
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { it.id == providerId }
        if (provider == null) {
            _state.update { it.copy(error = ModelCatalogUi.PROVIDER_MISSING) }
            return@launch
        }
        if (catalog.activeProviderId == providerId) return@launch
        if (sessionRunning()) {
            _state.update {
                it.copy(confirmation = CatalogConfirmation.SwitchProvider(providerId, provider.name))
            }
            return@launch
        }
        performSwitch(providerId)
    }

    /**
     * Opens the delete confirmation for [providerId]. Deleting the active provider needs a successor: the dialog supplies
     * it through [confirmPending], and a confirmation without one is refused rather than silently deleting the active
     * configuration.
     */
    fun deleteProvider(providerId: String, newActiveId: String?) = launch {
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { it.id == providerId }
        if (provider == null) {
            _state.update { it.copy(error = ModelCatalogUi.PROVIDER_MISSING) }
            return@launch
        }
        if (newActiveId != null && catalog.providers.none { it.id == newActiveId }) {
            _state.update { it.copy(error = ModelCatalogUi.NEW_ACTIVE_REQUIRED) }
            return@launch
        }
        _state.update {
            it.copy(
                error = null,
                notice = null,
                confirmation = CatalogConfirmation.DeleteProvider(providerId, provider.name, newActiveId),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // model writes
    // ---------------------------------------------------------------------------------------------------------

    /** Selects a model; only a current-revision TEXT_REQUEST verification and image-capable rows may be selected. */
    fun selectModel(modelId: String) = launch {
        val catalog = repository.catalog()
        val provider = catalog.activeProvider()
        val model = provider?.models?.firstOrNull { it.id == modelId }
        if (provider == null || model == null) {
            _state.update { it.copy(error = ModelCatalogUi.MODEL_MISSING) }
            return@launch
        }
        if (catalog.selectedModelId == modelId) return@launch
        if (InputModality.IMAGE !in model.inputs) {
            _state.update { it.copy(error = ModelCatalogUi.TEXT_ONLY_DISABLED) }
            return@launch
        }
        if (!ModelCatalogUi.freshTextVerified(model, provider.revision)) {
            _state.update { it.copy(error = ModelCatalogUi.SELECT_NEEDS_TEST) }
            return@launch
        }
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            beforeSelectionChange()
            repository.selectModel(modelId)
            val next = repository.catalog()
            applyCatalog(next)
            selectionChanged(next.activeProfile())
            _state.update { it.copy(notice = ModelCatalogUi.selectedModel(model.displayName)) }
        } catch (error: Throwable) {
            _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    /** Adds one manual row; `origin = MANUAL` rows are never overwritten by a refresh, and duplicate ids are refused. */
    fun addManualModel(draft: ManualModelDraft) = launch {
        val catalog = repository.catalog()
        val provider = catalog.activeProvider()
        if (provider == null) {
            _state.update { it.copy(error = ModelCatalogUi.ADD_PROVIDER_FIRST) }
            return@launch
        }
        val errors = ModelCatalogUi.manualModelErrors(draft, provider)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(error = errors.first()) }
            return@launch
        }
        val window = requireNotNull(ModelCatalogUi.parseKTokens(draft.contextWindowK))
        val output = requireNotNull(ModelCatalogUi.parseKTokens(draft.maxOutputK))
        val id = draft.id.trim()
        val entry = ModelEntry(
            id = id,
            displayName = draft.displayName.trim().ifBlank { id },
            contextWindowTokens = window,
            maxOutputTokens = output,
            inputs = draft.inputs + InputModality.TEXT,
            outputs = setOf(OutputModality.TEXT),
            origin = ModelOrigin.MANUAL,
        )
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            repository.saveModels(
                providerId = provider.id,
                models = provider.models + entry,
                selectedModelId = catalog.lastSelectedModelIdByProvider[provider.id],
            )
            applyCatalog(repository.catalog())
            _state.update { it.copy(notice = ModelCatalogUi.addedModel(entry.displayName)) }
        } catch (error: Throwable) {
            _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    /** Opens the delete confirmation for one model; the fallback happens only after the user confirms. */
    fun deleteModel(modelId: String) = launch {
        val provider = repository.catalog().activeProvider()
        val model = provider?.models?.firstOrNull { it.id == modelId }
        if (model == null) {
            _state.update { it.copy(error = ModelCatalogUi.MODEL_MISSING) }
            return@launch
        }
        _state.update {
            it.copy(
                error = null,
                notice = null,
                confirmation = CatalogConfirmation.DeleteModel(modelId, model.displayName),
            )
        }
    }

    /** Saves an edited row (Task 14's detail page) through [ModelCatalogRepository.saveModels]. */
    fun updateModel(entry: ModelEntry, changed: ModelEntry) = launch {
        if (changed.id != entry.id) {
            _state.update { it.copy(error = ModelCatalogUi.MODEL_ID_IMMUTABLE) }
            return@launch
        }
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { candidate -> candidate.models.any { it.id == entry.id } }
        if (provider == null) {
            _state.update { it.copy(error = ModelCatalogUi.MODEL_MISSING) }
            return@launch
        }
        val models = provider.models.map { if (it.id == entry.id) changed else it }
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            repository.saveModels(
                providerId = provider.id,
                models = models,
                selectedModelId = catalog.lastSelectedModelIdByProvider[provider.id],
            )
            val next = repository.catalog()
            applyCatalog(next)
            if (next.activeProviderId == provider.id) selectionChanged(next.activeProfile())
            _state.update { it.copy(notice = ModelCatalogUi.savedProvider(changed.displayName)) }
        } catch (error: Throwable) {
            _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    /** Clears one model's overrides through [ModelCatalogService.restoreDefaults] (the detail page's action). */
    fun restoreDefaults(modelId: String) = launch {
        val provider = repository.catalog().activeProvider()
        if (provider == null) {
            _state.update { it.copy(error = ModelCatalogUi.ADD_PROVIDER_FIRST) }
            return@launch
        }
        val catalogService = service
        if (catalogService == null) {
            _state.update { it.copy(error = ModelCatalogUi.UNAVAILABLE) }
            return@launch
        }
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            val restored = catalogService.restoreDefaults(provider.id, modelId)
            if (restored == null) {
                _state.update { it.copy(error = ModelCatalogUi.MODEL_MISSING) }
            } else {
                val next = repository.catalog()
                applyCatalog(next)
                if (next.activeProviderId == provider.id) selectionChanged(next.activeProfile())
                _state.update { it.copy(notice = ModelCatalogUi.restoredDefaults(restored.displayName)) }
            }
        } catch (error: Throwable) {
            _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // probes (the model detail page confirms in the UI and then calls testModel)
    // ---------------------------------------------------------------------------------------------------------

    /** Grants the probe consent and runs one synthetic text/image probe, recording the outcome for the detail page. */
    fun testModel(modelId: String, withImage: Boolean) = launch {
        val provider = repository.catalog().activeProvider()
        val model = provider?.models?.firstOrNull { it.id == modelId }
        if (provider == null || model == null) {
            _state.update { it.copy(error = ModelCatalogUi.MODEL_MISSING) }
            return@launch
        }
        val activeTester = tester
        if (activeTester == null) {
            _state.update { it.copy(error = ModelCatalogUi.TEST_UNAVAILABLE, lastTest = null) }
            return@launch
        }
        if (withImage && InputModality.IMAGE !in model.inputs) {
            _state.update { it.copy(error = ModelCatalogUi.IMAGE_INPUT_MISSING) }
            return@launch
        }
        val profileId = ModelCatalogUi.profileId(provider.id, modelId)
        _state.update {
            it.copy(
                lastTest = ModelTestResult(
                    modelId = modelId,
                    image = withImage,
                    passed = false,
                    message = ModelCatalogUi.TESTING,
                    testedAtEpochMillis = clock(),
                ),
                error = null,
            )
        }
        try {
            // The host wires this to `ProfileProbeGate.grant`; the gate consumes it inside the tester call below.
            probeConsent(
                ProbeConsent(
                    id = profileId,
                    revision = provider.revision,
                    domain = domain(provider.baseUrl),
                    image = withImage,
                ),
            )
            val listener: (ModelStreamEvent) -> Unit = { event ->
                if (event is ModelStreamEvent.Terminal) {
                    val passed = event.end == ModelEnd.COMPLETED
                    _state.update {
                        it.copy(
                            lastTest = ModelTestResult(
                                modelId = modelId,
                                image = withImage,
                                passed = passed,
                                message = if (passed) ModelCatalogUi.TEST_PASSED else ModelCatalogUi.testFailure(event.safeCode),
                                testedAtEpochMillis = clock(),
                            ),
                        )
                    }
                    reload()
                }
            }
            if (withImage) activeTester.testImage(profileId, listener) else activeTester.testText(profileId, listener)
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    lastTest = ModelTestResult(
                        modelId = modelId,
                        image = withImage,
                        passed = false,
                        message = ModelCatalogUi.saveError(error),
                        testedAtEpochMillis = clock(),
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // confirmation / reset / transient page state
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Applies the open [CatalogConfirmation]. A provider switch stops the session first; a delete of the active provider
     * needs a successor ([newActiveId]) and refuses without one.
     */
    fun confirmPending(newActiveId: String? = null) = launch {
        val pending = _state.value.confirmation ?: return@launch
        when (pending) {
            is CatalogConfirmation.SwitchProvider -> performSwitch(pending.providerId)
            is CatalogConfirmation.DeleteProvider ->
                performDeleteProvider(pending, newActiveId ?: pending.newActiveId)
            is CatalogConfirmation.DeleteModel -> performDeleteModel(pending.modelId)
        }
    }

    fun cancelPending() {
        _state.update { it.copy(confirmation = null) }
    }

    /** Deletes only the encrypted catalog and re-reads the (now empty) state; other blobs are untouched. */
    fun resetCatalog() = launch {
        _state.update { it.copy(saving = true, notice = null) }
        try {
            repository.reset()
            _state.update { it.copy(confirmation = null, keyMask = emptyMap(), notice = ModelCatalogUi.RESET_DONE) }
            reload()
        } catch (error: Throwable) {
            _state.update { it.copy(error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun dismissRefreshSummary() {
        _state.update { it.copy(refreshSummary = null) }
    }

    /** Consumes the editor's "saved" event; the form then closes itself. */
    fun consumeSaved() {
        _state.update { it.copy(savedProviderId = null) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------------------

    private fun launch(block: suspend () -> Unit) {
        if (!entered) return
        scope.launch {
            mutex.withLock {
                if (!isActive) return@withLock
                block()
            }
        }
    }

    private fun reload() {
        try {
            applyCatalog(repository.catalog())
        } catch (_: Throwable) {
            _state.update {
                it.copy(
                    catalog = null,
                    activeProvider = null,
                    selectedModel = null,
                    corrupt = true,
                    warning = null,
                    loading = false,
                    saving = false,
                )
            }
        }
    }

    private fun applyCatalog(catalog: ModelCatalog) {
        val provider = catalog.activeProvider()
        val selected = catalog.activeModel()
        val warning = when {
            provider == null -> null
            catalog.selectedModelId != null && selected == null -> ModelCatalogUi.MISSING_SELECTED
            selected?.missing == true -> ModelCatalogUi.MISSING_GATEWAY
            else -> null
        }
        _state.update {
            it.copy(
                catalog = catalog,
                activeProvider = provider,
                selectedModel = selected,
                corrupt = false,
                warning = warning,
                testAvailable = tester != null,
            )
        }
    }

    private fun fetchAfterSave(providerId: String): String? {
        val catalogService = service ?: return null
        return try {
            when (val outcome = catalogService.refresh(providerId)) {
                is RefreshOutcome.Success -> {
                    _state.update {
                        it.copy(refreshSummary = ModelCatalogUi.refreshSummary(outcome.counts))
                    }
                    null
                }
                is RefreshOutcome.Failed -> ModelCatalogUi.refreshError(outcome.error)
                RefreshOutcome.NoProvider -> ModelCatalogUi.PROVIDER_MISSING
                RefreshOutcome.MissingSecret -> ModelCatalogUi.MISSING_SECRET
            }
        } catch (error: Throwable) {
            ModelCatalogUi.saveError(error)
        }
    }

    private suspend fun performSwitch(providerId: String) {
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            val provider = repository.catalog().providers.firstOrNull { it.id == providerId }
                ?: throw ContractError.ConfigurationRejected("提供商不存在")
            // R19/design §5.9: stop first, write second, then hand the restored profile to the host.
            beforeSelectionChange()
            repository.setActiveProvider(providerId)
            val next = repository.catalog()
            applyCatalog(next)
            selectionChanged(next.activeProfile())
            _state.update { it.copy(confirmation = null, notice = ModelCatalogUi.switchedTo(provider.name)) }
        } catch (error: Throwable) {
            _state.update { it.copy(confirmation = null, error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    private suspend fun performDeleteProvider(
        pending: CatalogConfirmation.DeleteProvider,
        newActiveId: String?,
    ) {
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { it.id == pending.providerId }
        if (provider == null) {
            _state.update { it.copy(confirmation = null) }
            reload()
            return
        }
        val wasActive = catalog.activeProviderId == provider.id
        val others = catalog.providers.filterNot { it.id == provider.id }
        if (wasActive && others.isNotEmpty() && others.none { it.id == newActiveId }) {
            _state.update { it.copy(error = ModelCatalogUi.NEW_ACTIVE_REQUIRED) }
            return
        }
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            if (wasActive) beforeSelectionChange()
            repository.deleteProvider(provider.id)
            if (wasActive && newActiveId != null) repository.setActiveProvider(newActiveId)
            val next = repository.catalog()
            applyCatalog(next)
            if (wasActive) selectionChanged(next.activeProfile())
            _state.update { it.copy(confirmation = null, notice = ModelCatalogUi.deletedProvider(provider.name)) }
        } catch (error: Throwable) {
            _state.update { it.copy(confirmation = null, error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    private suspend fun performDeleteModel(modelId: String) {
        val catalog = repository.catalog()
        val provider = catalog.providers.firstOrNull { candidate -> candidate.models.any { it.id == modelId } }
        val model = provider?.models?.firstOrNull { it.id == modelId }
        if (provider == null || model == null) {
            _state.update { it.copy(confirmation = null) }
            reload()
            return
        }
        val wasSelected = catalog.activeProviderId == provider.id && catalog.selectedModelId == modelId
        val remaining = provider.models.filterNot { it.id == modelId }
        // Design §5.9: a deleted selected model falls back to the provider's first image-capable row, else nothing.
        val fallback = if (wasSelected) remaining.firstOrNull { InputModality.IMAGE in it.inputs }?.id else null
        _state.update { it.copy(saving = true, error = null, notice = null) }
        try {
            if (wasSelected) beforeSelectionChange()
            repository.saveModels(
                providerId = provider.id,
                models = remaining,
                selectedModelId = if (wasSelected) fallback else catalog.lastSelectedModelIdByProvider[provider.id],
            )
            val next = repository.catalog()
            applyCatalog(next)
            if (wasSelected) selectionChanged(next.activeProfile())
            _state.update { it.copy(confirmation = null, notice = ModelCatalogUi.deletedModel(model.displayName)) }
        } catch (error: Throwable) {
            _state.update { it.copy(confirmation = null, error = ModelCatalogUi.saveError(error)) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }
}
