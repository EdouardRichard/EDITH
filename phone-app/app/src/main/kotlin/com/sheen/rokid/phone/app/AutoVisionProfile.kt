package com.sheen.rokid.phone.app

import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.VerificationState

/**
 * The profile that applies to one plugin's turn: the caller's selection first, the fallback only when the selection is
 * absent or unreadable.
 *
 * 0.52 moved the only model selection into the catalog: the production call site passes the **active catalog
 * projection** as `selection` and resolves it through the repository, so there is no second selection source to
 * disagree with. The three lambdas stay because the same resolution rules are what make a repository refusal
 * (locked keystore, deleted payload) `null` rather than an exception; a caller that finds no profile fails closed.
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
 * Turn configuration resolves the active catalog projection first (see [resolveTurnProfile]); the resolution is
 * deliberately re-read per call, so a provider/model switch applies to the next capture instead of being frozen at
 * assembly. Null/exception at any step is `false` (fail closed, no image).
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
