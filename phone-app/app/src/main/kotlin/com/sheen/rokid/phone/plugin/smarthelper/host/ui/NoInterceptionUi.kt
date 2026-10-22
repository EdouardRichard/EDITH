package com.sheen.rokid.phone.plugin.smarthelper.host.ui

/**
 * 0.3 Phase 4B (`D189`): the pure half of the 「不拦截」 interaction - its copy and its one gate decision.
 *
 * The surrounding Compose switch/dialog cannot be exercised on the JVM (this module has no Compose UI-test dependency),
 * so the two things that must not drift are pinned here by `NoInterceptionUiTest`:
 *
 * - **Only off → on requires the second confirmation.** Turning the mode off is the safe direction and applies at once
 *   (the dialog must never stand between a user and disabling unconfirmed execution).
 * - **The warning says what the mode really does.** It names the whole L0–L4 range, says confirmations stop, says the
 *   mode survives close/kill/restart, says only a manual disable exits it, and warns that destructive actions may not
 *   be undoable. The owner removed the persistent top banner on 2026-10-22; the settings page keeps this copy.
 *
 * The Compose wiring around this object is not claimed as device-tested in B2; it is verified by compilation and the
 * pure state test only.
 */
object NoInterceptionUi {

    /** The settings-row title. */
    const val SWITCH_TITLE = "不拦截模式（高风险）"

    /** The always-visible sentence beside the switch. */
    const val SWITCH_SUMMARY =
        "开启后 L0–L4 全部工具调用不再确认，并绕过权限上限；关闭 App、被杀或重启后仍保持，只有手动关闭才退出。" +
            "卸载/清数据/删除文件等动作可能无法撤销。"

    /** The second-confirmation dialog's title. */
    const val CONFIRM_TITLE = "确认开启「不拦截」？"

    /** The risk copy shown in the second-confirmation dialog, in order. */
    val CONFIRM_LINES = listOf(
        "开启后，L0–L4 的全部工具调用（包括 L3/L4 与危险 shell 命令）将直接执行，不再弹出确认。",
        "权限上限（maxLevel）将被绕过，插件声明需要确认的动作也不会再询问。",
        "该模式会写入本机设置：关闭 App、被系统杀死或重启手机后仍然保持开启；只有你在设置页手动关闭才会退出。",
        "卸载应用、清除数据、删除文件、修改系统设置等动作可能无法撤销，请确认你愿意在当前环境承担该风险。",
        "审计记录与「急停」仍然可用。",
    )

    /** The persistent top banner was removed by the owner on 2026-10-22; the settings copy above remains. */

    /**
     * True only for the dangerous off → on move.
     *
     * The screen calls this before mutating its draft; a `true` answer means the dialog must be shown and the switch
     * must not move until the user confirms. On → off returns `false` so disabling is immediate.
     */
    fun requiresSecondConfirmation(current: Boolean, requested: Boolean): Boolean = requested && !current
}
