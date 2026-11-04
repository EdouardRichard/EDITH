package com.sheen.rokid.phone.plugin.smarthelper.host.ui

/**
 * The result of one settings-page write (0.52 design §4.2).
 *
 * The settings pages no longer treat a save as "call a unit function and hope": a refusal must become visible Chinese
 * copy, never a log line. [saved] distinguishes a persisted write from a refusal, and [errorCode] is one of the stable
 * codes below (or a caller-defined code) that [SettingsFeedback] maps to text. A successful result carries no code.
 *
 * The type lives beside [DetachUi]/[NoInterceptionUi] (`R5`): both the host's save path and [ProactiveArming] need it,
 * while placing it under `ui/settings` would invert the host→UI direction.
 */
internal data class SettingsSaveResult(
    val saved: Boolean,
    val errorCode: String? = null,
) {
    companion object {
        /** The composition has no settings store (a hand-built rig) or was assembled without one. */
        const val ERROR_STORE_UNAVAILABLE = "SETTINGS_STORE_UNAVAILABLE"

        /** The blob exists but cannot be read/parsed; writing defaults over it would erase the user's settings. */
        const val ERROR_SETTINGS_UNREADABLE = "SETTINGS_UNREADABLE"

        /** The blob write was refused; nothing was persisted. */
        const val ERROR_SAVE_FAILED = "SAVE_FAILED"
    }
}

/**
 * The one place a [SettingsSaveResult] becomes user-visible text (0.52 design §3.3/§4.2).
 *
 * Centralised on purpose: the same settings write reaches the page through the form save, the immediate 「主动辅助」
 * switch and the host's store, and every one of those paths must describe a refusal the same way. Nothing here says
 * "已保存" unless [SettingsSaveResult.saved] is true, and an unknown code is still surfaced (the code is a stable
 * identifier, not a credential) rather than replaced by a silent success.
 */
internal object SettingsFeedback {

    /**
     * The success line, with the exact count of fields that need a full App restart (design §4.2).
     *
     * `0` is the common case for the two live switches: the change already took effect, so the line must not tell the
     * user to restart for nothing. A negative count is treated as zero; it cannot be produced by
     * [SmartSettingsForm.needsRestartCount], and a total function is safer than a crash in the result line.
     */
    fun savedRestartText(count: Int): String =
        if (count <= 0) "已保存，立即生效" else "已保存 · $count 项需重启 App 生效"

    /** The inline failure copy for [result], or null when the write succeeded (there is nothing to report). */
    fun saveFailedText(result: SettingsSaveResult): String? {
        if (result.saved) return null
        return when (result.errorCode) {
            SettingsSaveResult.ERROR_STORE_UNAVAILABLE -> "保存失败：设置存储不可用，未保存。"
            SettingsSaveResult.ERROR_SETTINGS_UNREADABLE -> "保存失败：原设置无法读取，未覆盖原设置。"
            SettingsSaveResult.ERROR_SAVE_FAILED -> "保存失败：设置写入失败，未保存。"
            null -> "保存失败：未保存。"
            else -> "保存失败：${result.errorCode}（未保存）。"
        }
    }
}
