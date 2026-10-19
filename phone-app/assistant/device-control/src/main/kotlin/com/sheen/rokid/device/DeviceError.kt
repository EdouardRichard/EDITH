package com.sheen.rokid.device

/**
 * A semantic, actionable reason a device operation did not succeed.
 *
 * The code is the stable contract a model or the app can branch on; [message] is a one-line human
 * explanation that may mention the ref or the command, never screen text (spec §4.13: no正文 in
 * logs/evidence). [recoverable] means "retrying after the suggested user action or a re-observation
 * may succeed"; it is false when the request itself was invalid or the platform has no such
 * capability.
 */
data class DeviceError(val code: String, val message: String, val recoverable: Boolean)

/**
 * The error codes of spec §7 that this module can produce, plus the ones reserved here for the
 * adapters and tools of Tasks 9–11.
 *
 * Produced by [DeviceKernel] today: [CHANNEL_UNAVAILABLE], [DEVICE_SCREEN_OFF], [DEVICE_LOCKED],
 * [NODE_NOT_CLICKABLE] (coarse refusal from the port), [ACCEPTED_NO_EFFECT], [INVALID_ARGUMENTS],
 * [UNSUPPORTED], [VERIFY_TIMEOUT], [ACTION_TIMEOUT], [CANCELLED], [SHELL_FAILED] and any
 * [ShellOutcome.failure] code the shell adapter supplies.
 *
 * Produced later and therefore declared now so every layer spells them the same way:
 * [DEVICE_LOCKED_SECURE] (screen state the kernel cannot distinguish through the current port),
 * [NODE_NOT_FOUND]/[NODE_STALE]/[AMBIGUOUS_TARGET]/[NODE_DISABLED] (ref resolution in the adapter
 * and tools), [SCROLL_AT_BOUNDARY], [FOREGROUND_MISMATCH] (wait/verify projections),
 * [SHELL_NOT_BOUND], [SHELL_REPLY_TOO_LARGE].
 */
object DeviceErrorCodes {

    // Channel layer.
    const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
    const val DEVICE_SCREEN_OFF = "DEVICE_SCREEN_OFF"
    const val DEVICE_LOCKED = "DEVICE_LOCKED"
    const val DEVICE_LOCKED_SECURE = "DEVICE_LOCKED_SECURE"

    // Target layer.
    const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
    const val NODE_STALE = "NODE_STALE"
    const val AMBIGUOUS_TARGET = "AMBIGUOUS_TARGET"
    const val NODE_DISABLED = "NODE_DISABLED"
    const val NODE_NOT_CLICKABLE = "NODE_NOT_CLICKABLE"

    // Action layer.
    const val ACCEPTED_NO_EFFECT = "ACCEPTED_NO_EFFECT"
    const val SCROLL_AT_BOUNDARY = "SCROLL_AT_BOUNDARY"
    const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    const val UNSUPPORTED = "UNSUPPORTED"

    // Verification / timing layer.
    const val VERIFY_TIMEOUT = "VERIFY_TIMEOUT"
    const val FOREGROUND_MISMATCH = "FOREGROUND_MISMATCH"
    const val ACTION_TIMEOUT = "ACTION_TIMEOUT"
    const val CANCELLED = "CANCELLED"

    // Shell layer.
    const val SHELL_NOT_BOUND = "SHELL_NOT_BOUND"
    const val SHELL_REPLY_TOO_LARGE = "SHELL_REPLY_TOO_LARGE"
    const val SHELL_FAILED = "SHELL_FAILED"
}
