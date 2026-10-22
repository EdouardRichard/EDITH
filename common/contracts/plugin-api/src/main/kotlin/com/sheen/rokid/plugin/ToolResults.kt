package com.sheen.rokid.plugin

/**
 * How a tool invocation ended, as a machine-readable fact rather than prose.
 *
 * This is deliberately finer than [ActionOutcome]: a confirmed timeout and an unconfirmed one are different facts
 * (`Timeout` vs `Uncertain`), and a cancellation is not a failure. The lowercase wire spelling of each entry is pinned
 * by `ToolResultEnvelope` in `agent-runtime`.
 */
enum class ToolOutcome { Done, Refused, Failed, Timeout, Uncertain, Cancelled }

/**
 * Whether the post-call observation carries a picture the model can use.
 *
 * The reason for a non-[Ok] value lives on [ToolObservation.visionReason]; this value only says what the vision channel
 * did. The lowercase wire spelling of each entry is pinned by `ToolResultEnvelope` in `agent-runtime`.
 */
enum class ToolVision { Ok, Disabled, Unavailable, NotRequested }

/**
 * What actually happened during one tool invocation — the execution facts half of the model-facing result envelope.
 *
 * The tri-state fields [accepted], [applied], [verified] and [changed] use `null` for **unknown** and must never be
 * collapsed to `false`: a timed-out write is not known to have failed to apply. [recoverable], [uncertain] and
 * [partial] are non-null flags so every result states them explicitly.
 *
 * [code] is a stable machine code (for example `STATE_UNCHANGED` or `ACTION_TIMEOUT_UNCERTAIN`); [message] is a stable
 * human-readable reason. Neither carries user content.
 */
data class ToolExecution(
    val outcome: ToolOutcome,
    val code: String? = null,
    val message: String? = null,
    val recoverable: Boolean = false,
    val accepted: Boolean? = null,
    val applied: Boolean? = null,
    val verified: Boolean? = null,
    val changed: Boolean? = null,
    val uncertain: Boolean = false,
    val partial: Boolean = false,
)

/**
 * The post-call observation half of the model-facing result envelope: what the device looked like after the tool ran.
 *
 * A tool that does not observe the screen (for example a notification tool) reports no observation at all rather than
 * an invented one. An observation may itself be empty — a valid capture with no [screen], no [foregroundPackage] and
 * no [coverage] is still a real attempted observation — and [vision] plus [visionReason] say whether a picture was
 * available.
 */
data class ToolObservation(
    val snapshotId: String? = null,
    val capturedAtEpochMillis: Long? = null,
    val foregroundPackage: String? = null,
    val foregroundActivity: String? = null,
    val rootPackage: String? = null,
    val coverage: String? = null,
    val screen: String? = null,
    val truncated: Boolean = false,
    val vision: ToolVision = ToolVision.NotRequested,
    val visionReason: String? = null,
)
