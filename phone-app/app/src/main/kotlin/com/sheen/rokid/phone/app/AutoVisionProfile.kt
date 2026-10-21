package com.sheen.rokid.phone.app

import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile

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
 * 0.3 Phase 2.1 Task 4 fix: whether the **turn's** profile declares the `IMAGE` input, so automatic vision may attach
 * a screenshot.
 *
 * Turn configuration resolves the repository selection first (see [resolveTurnProfile]); using only
 * `SmartWiring.selectedProfile()` here silently disabled auto vision whenever the settings profile was text-only while
 * the user had selected an image profile. Null/exception at any step is `false` (fail closed, no image).
 */
internal fun autoVisionProfileSupportsImage(
    selection: () -> String?,
    load: (String) -> ModelProfile?,
    fallback: () -> ModelProfile?,
): Boolean = resolveTurnProfile(selection, load, fallback)?.inputs?.contains(InputModality.IMAGE) == true
