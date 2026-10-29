package com.sheen.rokid.phone.plugin.smarthelper.host.ui

import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate

/**
 * 0.5: the settings page's 「纯手机模式」 copy and switch state (renamed from 0.4's 「脱离眼镜」; the 0.5 preview name
 * 「纯手机模型」 was retired in 0.5.1, `C1`).
 *
 * Pure by construction (no Compose, no Android types) so the JVM pins the parts the owner ruled on: what the switch
 * promises, that 「主动辅助」 is disabled only while the phone-only mode is on, and that every gate failure is visible
 * copy which never claims the change was saved. The Composable around it only lays these strings out.
 *
 * The internal `DetachGate`/`glassesDetached` identifiers are deliberately unchanged (D209): they are not user copy, and
 * renaming the persisted key would need a migration for no user-visible benefit.
 */
internal object DetachUi {

    /** Owner wording (2026-10-29 / 0.5.1 `C1`): the mode's user-facing name, used verbatim by the settings switch. */
    const val SWITCH_TITLE = "纯手机模式"

    /**
     * Every consequence in one inline explanation (`D199`, `D202`, `D53`, D209/D210).
     *
     * Deliberately explicit about the facts a user must not have to infer: no CXR/glasses connection, the desktop
     * floating ball is the new interaction surface, proactive assistance is ineffective (without touching its persisted
     * value), this is a capture/display choice rather than an upload consent, and a running session is stopped before
     * the change is saved and takes effect on the next start.
     */
    const val SWITCH_SUMMARY =
        "开启后不连接眼镜（CXR），只用手机麦克风和手机屏幕；桌面显示可拖动的 EDITH 悬浮球（长按说话、气泡显示回复）；" +
            "主动辅助不生效（其持久值不变）；" +
            "这是采音与显示方式的选择，不是上传授权——会话运行时仍会按既有规则把转写与当回合上下文发送给所配置的模型服务；" +
            "会话运行中修改会先停止会话且不自动重启；下次开始会话生效。"

    /** Shown next to the (disabled) 「主动辅助」 switch while the phone-only mode is on. */
    const val PROACTIVE_DETACHED_NOTE = "纯手机模式下不生效（主动辅助的持久值不变）"

    /** `D202`: the persisted proactive switch stays visible, but is not operable in detached mode. */
    fun proactiveSwitchEnabled(detached: Boolean): Boolean = !detached

    /** What the switch says after a successful save; [stoppedSession] is the gate's own result, never guessed. */
    fun successText(stoppedSession: Boolean): String =
        if (stoppedSession) "已保存：会话已先停止，下次开始会话生效。"
        else "已保存：下次开始会话生效。"

    /** Stable failure code to visible Chinese copy. Nothing here says "saved". */
    fun errorText(error: String, stoppedSession: Boolean = false): String {
        val base = when (error) {
            DetachGate.ERROR_STORE_UNAVAILABLE -> "保存失败：设置存储不可用，未保存。"
            DetachGate.ERROR_STOP_FAILED -> "保存失败：会话未能先停止，设置未保存。"
            DetachGate.ERROR_SETTINGS_UNREADABLE -> "保存失败：原设置无法读取，未覆盖原设置。"
            DetachGate.ERROR_SAVE_FAILED -> "保存失败：设置写入失败，未保存。"
            else -> "保存失败：$error（未保存）。"
        }
        // A save can refuse **after** the stop already happened; the mode was not persisted, but the session is gone.
        // Saying only "not saved" would hide a real side effect the user must know about.
        return if (stoppedSession) base.removeSuffix("。") + "（会话已先停止）。" else base
    }
}
