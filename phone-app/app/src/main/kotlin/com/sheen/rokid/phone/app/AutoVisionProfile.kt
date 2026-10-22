package com.sheen.rokid.phone.app

import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.VerificationState

/**
 * The profile that applies to one plugin's turn: the repository's per-plugin selection first, the settings fallback
 * only when the selection is absent or unreadable.
 *
 * This is the same resolution `HostCompositionRoot` uses to build a plugin's configuration
 * (`configurationFor`/`applyProfileSelection`). The App stores one selection per plugin in
 * `AndroidProfileRepository.selections()`; the settings-resolved profile (`SmartWiring.selectedProfile()`) is only
 * the assembly-time choice stored in the settings blob, so it can name a different profile than the user's current
 * selection. Reading the selection is deliberately a caller-supplied lambda: every call re-reads it, so a selection
 * change applies to the next turn/capture instead of being frozen at assembly.
 *
 * A repository that refuses (locked keystore, deleted payload) yields `null` rather than propagating: the fallback is
 * tried, and a caller that finds no profile fails closed.
 */
internal fun resolveTurnProfile(
    selection: () -> String?,
    load: (String) -> ModelProfile?,
    fallback: () -> ModelProfile?,
): ModelProfile? {
    val chosen = runCatching { selection() }.getOrNull()
    if (chosen != null) runCatching { load(chosen) }.getOrNull()?.let { return it }
    return runCatching { fallback() }.getOrNull()
}

/**
 * 0.3 Phase 2.1 Task 4 fix + final whole-branch fix: whether automatic vision may attach a screenshot to the
 * **turn's** request.
 *
 * Two conditions must both hold (final review Important I2):
 * 1. The turn's profile declares `InputModality.IMAGE`.
 * 2. The profile's **latest** `IMAGE_REQUEST` verification for its **current** [ModelProfile.revision] is
 *    [VerificationState.Verified].
 *
 * Condition 2 is not defensive duplication: `CapabilityGate` applies exactly that check to any request that carries
 * an image. A profile can declare `IMAGE` and still fail the image probe, and the first auto image would then throw
 * `ContractError.CapabilityMissing` and end the whole turn. Checking it here keeps the gate and the request gate in
 * agreement, so "supports image" means "the request will be admitted", not merely "the capability is declared".
 *
 * The latest-record selection copies `CapabilityGate`'s rule (same `profileRevision` and `operation`, max
 * `testedAtEpochMillis`, list index as the tie-break); the two must not drift. A verification for an older revision,
 * a failed/unverified probe, or no probe at all reads `false`.
 *
 * Turn configuration resolves the repository selection first (see [resolveTurnProfile]); using only
 * `SmartWiring.selectedProfile()` here silently disabled auto vision whenever the settings profile was text-only while
 * the user had selected an image profile. Null/exception at any step is `false` (fail closed, no image).
 */
internal fun autoVisionProfileSupportsImage(
    selection: () -> String?,
    load: (String) -> ModelProfile?,
    fallback: () -> ModelProfile?,
): Boolean {
    val profile = resolveTurnProfile(selection, load, fallback) ?: return false
    if (InputModality.IMAGE !in profile.inputs) return false
    return latestImageRequestIsVerified(profile)
}

/**
 * The same latest-verification rule `CapabilityGate.check` applies before admitting an image on the wire: only records
 * for the profile's current revision and the `IMAGE_REQUEST` operation compete, the newest `testedAtEpochMillis` wins,
 * and an equal timestamp is resolved by list order (the later entry is later). Anything else is not `Verified`.
 */
private fun latestImageRequestIsVerified(profile: ModelProfile): Boolean {
    val latest = profile.verifications.withIndex()
        .filter {
            it.value.profileRevision == profile.revision && it.value.operation == IMAGE_REQUEST_OPERATION
        }
        .maxWithOrNull(
            compareBy<IndexedValue<CapabilityVerification>> { it.value.testedAtEpochMillis }
                .thenBy { it.index },
        )
        ?.value
    return latest?.state == VerificationState.Verified
}

/** The operation name `CapabilityGate` verifies for an image request. */
private const val IMAGE_REQUEST_OPERATION = "IMAGE_REQUEST"
