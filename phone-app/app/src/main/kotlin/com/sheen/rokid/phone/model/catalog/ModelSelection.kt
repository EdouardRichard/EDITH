package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.VerificationState

/**
 * 0.52 I1: the one pure selectability rule for a catalog model.
 *
 * The product's assistant sends TEXT + IMAGE, and the existing `CapabilityGate` refuses a turn without current-revision
 * `TEXT_REQUEST` evidence. A model that cannot pass that gate must therefore never become the selection implicitly -
 * otherwise a session starts and its first request is refused. This rule is shared by the radio, the repository's
 * provider-switch/delete fallbacks and the host's session-start gate; keeping one function is what stops those four
 * paths from drifting apart (the final review found exactly that drift).
 */
object ModelSelection {
    const val TEXT_OPERATION = "TEXT_REQUEST"
    const val IMAGE_OPERATION = "IMAGE_REQUEST"

    /** Start-gate code: the selected row no longer exists (or the gateway removed it). */
    const val PROFILE_MODEL_MISSING = "PROFILE_MODEL_MISSING"

    /** Start-gate code: the selected row exists but needs a fresh, current-revision text test. */
    const val PROFILE_MODEL_UNVERIFIED = "PROFILE_MODEL_UNVERIFIED"

    /** The `CapabilityGate` evidence rule, extracted so catalog and UI share one implementation. */
    fun latestVerified(
        verifications: List<CapabilityVerification>,
        revision: Long,
        operation: String,
    ): Boolean {
        val latest = verifications.withIndex()
            .filter { it.value.profileRevision == revision && it.value.operation == operation }
            .maxWithOrNull(
                compareBy<IndexedValue<CapabilityVerification>> { it.value.testedAtEpochMillis }
                    .thenBy { it.index },
            )
        return latest?.value?.state == VerificationState.Verified
    }

    /** Present, image-capable, and TEXT_REQUEST-verified at the provider's current revision (design §5.6/§8.2). */
    fun isSelectable(entry: ModelEntry, providerRevision: Long): Boolean =
        !entry.missing &&
            InputModality.IMAGE in entry.inputs &&
            latestVerified(entry.verifications, providerRevision, TEXT_OPERATION)

    /** The first row satisfying [isSelectable], in stored order, or null when none does. */
    fun firstSelectable(models: List<ModelEntry>, providerRevision: Long): ModelEntry? =
        models.firstOrNull { isSelectable(it, providerRevision) }

    /**
     * The host's start-gate answer for the catalog's current selection, or null when there is nothing to refuse
     * (no provider/no selection keeps the pre-existing "start, then the plugin reports its own missing profile" path).
     */
    fun startRefusal(catalog: ModelCatalog): String? {
        val provider = catalog.activeProvider() ?: return null
        val selectedId = catalog.selectedModelId ?: return null
        val entry = provider.models.firstOrNull { it.id == selectedId } ?: return PROFILE_MODEL_MISSING
        return when {
            entry.missing -> PROFILE_MODEL_MISSING
            !isSelectable(entry, provider.revision) -> PROFILE_MODEL_UNVERIFIED
            else -> null
        }
    }
}
