package com.sheen.rokid.phone.ui.settings

/**
 * 0.52 design §3.2: how the model-service row speaks about the catalog.
 *
 * [UNCONFIGURED] is "no active provider"; [READY] is a selected model with current-revision text evidence (the only
 * state in which a session can start); [UNAVAILABLE] is every state that cannot run a turn - a provider without models,
 * models without a selection, a selected model the gateway dropped, or a selection whose evidence is stale.
 */
internal enum class ModelStatus { UNCONFIGURED, READY, UNAVAILABLE }

/**
 * The immutable projection the settings home draws (0.52 design §3.2). Built only by [SettingsSummaries.from], so the
 * screen is a pure function of this value and is testable without a host or a catalog.
 *
 * The two counts are separate from their summaries on purpose: the row renders them as a red status at the end of the
 * row (「N 项待授权」/「N 项不可用」), while the summary names what the page edits.
 */
internal data class SettingsHomeModel(
    val modelSummary: String,
    val modelStatus: ModelStatus,
    val assistantSummary: String,
    val runtimeSummary: String,
    val permissionsSummary: String,
    val pendingPermissionCount: Int,
    val capabilitiesSummary: String,
    val unavailableCapabilityCount: Int,
    val auditSummary: String,
    val aboutSummary: String,
)
