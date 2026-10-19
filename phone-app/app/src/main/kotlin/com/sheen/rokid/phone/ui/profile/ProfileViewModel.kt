package com.sheen.rokid.phone.ui.profile
import com.sheen.rokid.phone.model.ProfileFormException
import com.sheen.rokid.phone.model.ProfileField
import com.sheen.rokid.phone.storage.unavailable

import androidx.lifecycle.ViewModel
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.phone.storage.AndroidProfileRepository
import com.sheen.rokid.plugin.*
import com.sheen.rokid.contracts.ContractError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * The user's confirmation for one probe.
 *
 * [image] distinguishes the synthetic image probe (0.25 R76) from the text probe: the two operations are verified
 * separately (`TEXT_REQUEST` / `IMAGE_REQUEST`), so the confirmation dialog and the gate admission must name which one
 * the user is about to allow.
 */
data class ProbeConsent(val id: String, val revision: Long, val domain: String, val image: Boolean = false)
data class ProfileUiState(val profiles: List<ModelProfile> = emptyList(), val selections: Map<String, String> = emptyMap(),
    val loadingId: String? = null, val error: String? = null, val confirmation: ProbeConsent? = null)
class ProfileViewModel(private val repository: AndroidProfileRepository, private val tester: ProfileTester,
    private val grant: (ProbeConsent?) -> Unit = {}, private val selectionChanged: (String, ModelProfile?) -> Unit = { _, _ -> }) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileUiState())
    val state = mutable.asStateFlow()
    private var serial = 0L
    private var pending: CancellationHandle? = null
    private var closed = false
    private val changes = repository.listenChanges { id -> changed(id) }
    private val errors = repository.listenStorageErrors { id, revision -> storageFailed(id, revision) }
    init { refresh() }
    @Synchronized private fun refresh(error: String? = mutable.value.error) {
        try { mutable.value = mutable.value.copy(profiles = repository.list(), selections = repository.selections(), error = error) }
        catch (_: Exception) { mutable.value = mutable.value.copy(profiles = emptyList(), selections = emptyMap(), error = "STORAGE_RECONFIGURE") }
    }
    @Synchronized private fun changed(id: String) {
        if (closed) return
        if (mutable.value.loadingId == id || mutable.value.confirmation?.id == id) cancelTest()
        refresh()
        repository.selections().forEach { (plugin, profile) -> if (profile == id) selectionChanged(plugin, repository.get(id)) }
    }
    @Synchronized private fun storageFailed(id: String, revision: Long) {
        if (closed) return
        if (mutable.value.loadingId == id && mutable.value.profiles.firstOrNull { it.id == id }?.revision == revision) cancelTest()
        refresh("STORAGE_RECONFIGURE")
    }
    @Synchronized fun save(draft: ModelProfile, newSecret: CharArray?) {
        try {
            if (closed) return
            try { domain(draft.baseUrl) }
            catch (_: Exception) { throw ProfileFormException(ProfileField.BASE_URL, "BASE_URL_INVALID") }
            if (mutable.value.loadingId == draft.id || mutable.value.confirmation?.id == draft.id) cancelTest()
            val actualSecret = newSecret?.takeIf { it.any { char -> !char.isWhitespace() } }
            if (actualSecret == null && repository.get(draft.id) == null)
                throw ProfileFormException(ProfileField.API_KEY, "API_KEY_REQUIRED")
            repository.saveWithSecret(draft, actualSecret)
            refresh(null)
        } catch (error: Exception) { refresh(if (error is ProfileFormException) error.code else "SAVE_FAILED") }
        finally { newSecret?.fill('\u0000') }
    }
    @Synchronized fun delete(id: String) {
        if (closed) return
        val selected = mutable.value.selections.filterValues { it == id }.keys
        if (mutable.value.loadingId == id || mutable.value.confirmation?.id == id) cancelTest()
        var error: String? = null
        try { repository.delete(id) } catch (_: Exception) { error = "DELETE_FAILED" }
        try { if (repository.get(id) == null) selected.forEach { selectionChanged(it, null) } }
        catch (_: Exception) { error = "DELETE_FAILED" }
        refresh(error)
    }
    @Synchronized fun select(pluginId: String, profileId: String) {
        if (closed) return
        try { repository.select(pluginId, profileId); selectionChanged(pluginId, repository.get(profileId)); refresh(null) }
        catch (_: Exception) { refresh("SELECTION_FAILED") }
    }
    @Synchronized fun testText(id: String) {
        if (closed) return
        cancelTest()
        try {
            val profile = repository.get(id) ?: throw IllegalArgumentException()
            // P5: both DEEPSEEK_RESPONSES and OPENAI_CHAT_COMPLETIONS support text-only probe requests.
            if (profile.protocolId !in setOf("DEEPSEEK_RESPONSES", "OPENAI_CHAT_COMPLETIONS") ||
                InputModality.TEXT !in profile.inputs || OutputModality.TEXT !in profile.outputs)
                throw ContractError.CapabilityMissing("Text test unavailable")
            mutable.value = mutable.value.copy(confirmation = ProbeConsent(id, profile.revision, domain(profile.baseUrl)), error = null)
        } catch (error: Exception) { refresh(if (error is ContractError.CapabilityMissing) "CAPABILITY_MISSING" else "STORAGE_RECONFIGURE") }
    }

    /**
     * 0.25 Task 14 (R76): the synthetic image probe. It needs a profile that declares both TEXT (the fixed question)
     * and IMAGE (the synthetic picture) and a protocol that supports images; anything else is a visible capability
     * refusal, never a hidden text-only test.
     */
    @Synchronized fun testImage(id: String) {
        if (closed) return
        cancelTest()
        try {
            val profile = repository.get(id) ?: throw IllegalArgumentException()
            if (profile.protocolId !in setOf("DEEPSEEK_RESPONSES", "OPENAI_CHAT_COMPLETIONS") ||
                InputModality.TEXT !in profile.inputs || InputModality.IMAGE !in profile.inputs ||
                OutputModality.TEXT !in profile.outputs)
                throw ContractError.CapabilityMissing("Image test unavailable")
            mutable.value = mutable.value.copy(
                confirmation = ProbeConsent(id, profile.revision, domain(profile.baseUrl), image = true), error = null)
        } catch (error: Exception) { refresh(if (error is ContractError.CapabilityMissing) "CAPABILITY_MISSING" else "STORAGE_RECONFIGURE") }
    }
    @Synchronized fun confirmTest() {
        if (closed) return
        val consent = mutable.value.confirmation ?: return
        mutable.value = mutable.value.copy(confirmation = null)
        try {
            val profile = repository.get(consent.id) ?: throw IllegalArgumentException()
            require(profile.revision == consent.revision && domain(profile.baseUrl) == consent.domain)
            val operation = ++serial
            mutable.value = mutable.value.copy(loadingId = consent.id, error = null)
            grant(consent)
            val returned = if (consent.image) tester.testImage(consent.id) { event -> terminal(operation, consent, event) }
            else tester.testText(consent.id) { event -> terminal(operation, consent, event) }
            if (serial == operation && mutable.value.loadingId == consent.id) pending = returned
        } catch (error: Exception) {
            ++serial; pending = null
            mutable.value = mutable.value.copy(loadingId = null)
            refresh(when (error) {
                is ContractError.CapabilityMissing -> "CAPABILITY_MISSING"
                is ContractError.ConfigurationRejected -> "STORAGE_RECONFIGURE"
                else -> "TEST_FAILED"
            })
        } finally { grant(null) }
    }
    @Synchronized private fun terminal(operation: Long, consent: ProbeConsent, event: ModelStreamEvent) {
        if (closed || operation != serial || event !is ModelStreamEvent.Terminal) return
        ++serial; pending = null
        mutable.value = mutable.value.copy(loadingId = null)
        val current = runCatching { repository.get(consent.id) }.getOrNull()
        if (current?.revision != consent.revision) { refresh(); return }
        // Success is the repository's durable evidence, not the network terminal itself.
        refresh(if (event.end in setOf(ModelEnd.COMPLETED, ModelEnd.CANCELLED)) null else "TEST_FAILED")
    }
    @Synchronized fun cancelTest() {
        ++serial; val handle = pending; pending = null
        mutable.value = mutable.value.copy(loadingId = null, confirmation = null)
        grant(null); handle?.cancel()
    }
    @Synchronized fun close() { if (!closed) { cancelTest(); closed = true; changes.cancel(); errors.cancel() } }
    override fun onCleared() { close() }
}

internal fun domain(url: String): String {
    val uri = URI(url)
    require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.fragment == null)
    return uri.host.lowercase(java.util.Locale.ROOT) + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
}
