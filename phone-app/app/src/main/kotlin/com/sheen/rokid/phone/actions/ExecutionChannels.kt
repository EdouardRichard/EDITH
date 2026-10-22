package com.sheen.rokid.phone.actions
import com.sheen.rokid.phone.storage.unavailable

/**
 * One execution channel as the capability panel shows it (spec §17.2 rule 1).
 *
 * @param reason the **stable code** the panel shows while the channel is down, or null when it is up. `intent` has no
 *   unavailable state, so it never invents one.
 * @param note the Root row's informational line. A missing `su` is a fact about the device, not a failure of this App,
 *   so the panel states it instead of rendering it as an error (spec §17.2's "告知行").
 */
data class ExecutionChannelStatus(
    val id: String,
    val available: Boolean,
    val reason: String?,
    val note: String? = null,
)
/** The channel ids, the same strings the `P3_EXEC` probe lines carry. */
internal const val INTENT_CHANNEL = "intent"
internal const val A11Y_CHANNEL = "accessibility"
internal const val SHIZUKU_CHANNEL = "shizuku"
internal const val ROOT_CHANNEL = "root"

/** The stable per-channel code of spec §17.2 rule 1, or null for a channel with no unavailable state. */
internal fun executionChannelReason(id: String): String? = when (id) {
    A11Y_CHANNEL -> "A11Y_DISABLED"
    SHIZUKU_CHANNEL -> "SHIZUKU_UNAVAILABLE"
    ROOT_CHANNEL -> "ROOT_UNAVAILABLE"
    else -> null
}

/** The panel's name for a channel; an unknown id prints itself rather than a label that would mean something else. */
internal fun executionChannelLabel(id: String): String = when (id) {
    INTENT_CHANNEL -> "指令执行（Intent）"
    A11Y_CHANNEL -> "无障碍通道"
    SHIZUKU_CHANNEL -> "Shizuku 通道"
    ROOT_CHANNEL -> "Root"
    else -> id
}

/**
 * Projects live channel availability into the panel's rows.
 *
 * Pure on purpose. The rule this pins - **every channel that is down is named** - is exactly the kind that rots
 * silently when it can only be checked by looking at a device, which is how the panel came to advertise 指令执行 as
 * available while every L2-L4 tool had vanished from the model's table. The composition passes the *same* probes the
 * `P3_EXEC` lines use, so the panel and the log cannot disagree about which channel is up.
 */
internal fun executionChannelStatuses(
    probes: List<Pair<String, Boolean>>,
    rootNote: String? = null,
): List<ExecutionChannelStatus> = probes.map { (id, available) ->
    ExecutionChannelStatus(
        id = id,
        available = available,
        reason = if (available) null else executionChannelReason(id),
        note = if (id == ROOT_CHANNEL) rootNote else null,
    )
}
