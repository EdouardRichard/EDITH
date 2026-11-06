package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.phone.lock.LockPinValidator
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm

/*
 * 0.52 design §3.5/§4.2/§6.1: the pure half of the 权限与安全 page.
 *
 * The Composable renders [PermissionsSecurityState] and forwards every user action here. Keeping the transitions on
 * the JVM is what makes three easy-to-lose rules provable without the K40:
 *
 * - the 「不拦截」 switch is **immediate and outside the form**; only off → on asks for the second confirmation, and a
 *   successful immediate write updates `base` so a later form save carries the new value instead of resurrecting the
 *   old one;
 * - the tool-permission form's restart scope is exactly `maxLevel` + `whitelist`;
 * - the PIN section is its own immediate action: it never dirties the form, never logs the PIN, and keeps the old
 *   saved/cleared notices.
 *
 * No Compose or Android types are imported.
 */

/** The four OS grants the helper's perception needs (design §3.5: usage / notification / calendar / overlay). */
internal enum class PermissionKind { USAGE_ACCESS, NOTIFICATION_ACCESS, CALENDAR, OVERLAY }

/**
 * One row of the 系统授权 section.
 *
 * [missingText] is the old inline red sentence (appendix A17) kept verbatim; [actionLabel] is 「去授权」.
 * [onGrant] is the host callback that opens the matching system page (or the calendar permission request), so the page
 * itself never touches an Intent.
 */
internal data class PermissionState(
    val kind: PermissionKind,
    val label: String,
    val granted: Boolean,
    val missingText: String,
    val actionLabel: String = PermissionsSecurityUi.GRANT_ACTION,
    val onGrant: () -> Unit = {},
)

/** The user-visible copy of the page; the JVM test pins the exact strings. */
internal object PermissionsSecurityUi {

    /** The `(?)` titles reused from [HelpContent]; kept as references so the popover and the icons cannot drift. */
    const val GRANT_ACTION = "去授权"

    /** The PIN section's old privacy sentence, taken from the `(?)` body so there is one copy (appendix A9). */
    val PIN_PRIVACY: String = HelpContent.LOCK_PIN.body

    const val PIN_INVALID = "请输入 4-16 位数字 PIN"
    const val PIN_SAVE_FAILED = "PIN 保存失败，请重试"
    const val PIN_SAVED = "PIN 已保存"
    const val PIN_CLEARED = "PIN 已清除"
    const val PIN_CONFIGURED = "已配置：设备工具调用时会自动解锁"

    /** The old button labels (appendix A23): save before a PIN exists, update after. */
    const val PIN_SAVE = "保存 PIN"
    const val PIN_UPDATE = "更新 PIN"
    const val PIN_CLEAR = "清除 PIN"

    /** A successful immediate 「不拦截」 write took effect without a restart. */
    const val NO_INTERCEPTION_SAVED = "已保存，立即生效"

    /** The second-confirmation dialog's answer labels (appendix A8 keeps the old wording). */
    const val CONFIRM_ON = "确认开启"
    const val CONFIRM_CANCEL = "取消"

    /** Group titles (design §3.5). */
    const val TOOL_PERMISSIONS_TITLE = "工具权限"
    const val PIN_TITLE = "锁屏自动解锁"
    const val GRANTS_TITLE = "系统授权"

    /** The grant rows' labels, in the order the design lists them. */
    private const val USAGE_LABEL = "使用情况访问"
    private const val NOTIFICATION_LABEL = "通知使用权"
    private const val CALENDAR_LABEL = "日历权限"
    private const val OVERLAY_LABEL = "悬浮窗"

    /** appendix A17: the old missing-grant red text, verbatim. */
    private const val USAGE_MISSING = "未授予使用情况访问，主动辅助看不到前台 App。"
    private const val NOTIFICATION_MISSING = "未连接通知监听，主动辅助看不到通知分类（只读分类，不读通知内容）。"
    private const val CALENDAR_MISSING = "未授予日历权限，主动辅助看不到今天的日程标题。"
    private const val OVERLAY_MISSING = "未授予悬浮窗权限，确认弹窗无法在其他 App 上方弹出。"

    /**
     * The four grant rows. Null sources are not a page concern: the host passes the four booleans it actually read, so
     * a hand-built rig with no source answers "not granted" and the row explains what the assistant cannot see.
     */
    fun permissionStates(
        foregroundGranted: Boolean,
        notificationsConnected: Boolean,
        calendarGranted: Boolean,
        overlayGranted: Boolean,
    ): List<PermissionState> = listOf(
        PermissionState(PermissionKind.USAGE_ACCESS, USAGE_LABEL, foregroundGranted, USAGE_MISSING),
        PermissionState(
            PermissionKind.NOTIFICATION_ACCESS,
            NOTIFICATION_LABEL,
            notificationsConnected,
            NOTIFICATION_MISSING,
        ),
        PermissionState(PermissionKind.CALENDAR, CALENDAR_LABEL, calendarGranted, CALENDAR_MISSING),
        PermissionState(PermissionKind.OVERLAY, OVERLAY_LABEL, overlayGranted, OVERLAY_MISSING),
    )

    /** The status word on a granted row: a notification listener is connected, not granted. */
    fun grantedStatusText(kind: PermissionKind): String =
        if (kind == PermissionKind.NOTIFICATION_ACCESS) "已连接" else "已授权"

    /** The status word on a missing row, using the old page's granted/connected distinction. */
    fun missingStatusText(kind: PermissionKind): String =
        if (kind == PermissionKind.NOTIFICATION_ACCESS) "未连接" else "未授权"
}

/**
 * One snapshot of the 权限与安全 page.
 *
 * [base] is the last persisted snapshot the page knows about: the one load at entry, updated after a successful form
 * save and after a successful immediate 「不拦截」 write. [noInterceptionLive] is the switch's **live** value, which the
 * host can move even when a write fails in the safe direction (see [PermissionsSecurityLogic.requestNoInterception]).
 */
internal data class PermissionsSecurityState(
    val base: SmartSettings,
    val maxLevel: String,
    val whitelist: String,
    val noInterceptionLive: Boolean,
    val noInterceptionConfirmPending: Boolean = false,
    val noInterceptionResult: SettingsSaveResult? = null,
    val pinInput: String = "",
    val pinConfigured: Boolean,
    val pinError: String? = null,
    val pinNotice: String? = null,
    val validationErrors: List<String> = emptyList(),
    val result: SettingsSaveResult? = null,
    val restartCount: Int = 0,
) {

    /** True while the form would write a different level or whitelist (also true for unparseable text). */
    val dirty: Boolean
        get() {
            val loaded = SmartSettingsForm.fromSettings(base)
            return maxLevel != loaded.maxLevel || whitelist != loaded.whitelist
        }

    /** The form's result line, or null before a save attempt. */
    val saveMessage: String?
        get() = result?.let { result ->
            if (result.saved) SettingsFeedback.savedRestartText(restartCount) else SettingsFeedback.saveFailedText(result)
        }

    /** The immediate switch's result line; success is immediate, failure is the mapped refusal. */
    val noInterceptionMessage: String?
        get() = noInterceptionResult?.let { result ->
            if (result.saved) {
                PermissionsSecurityUi.NO_INTERCEPTION_SAVED
            } else {
                SettingsFeedback.saveFailedText(result)
            }
        }
}

/** The pure transitions the page uses. */
internal object PermissionsSecurityLogic {

    /** A fresh page: exactly one load at entry (design §4.4), normalized into the form's two fields. */
    fun initial(
        settings: SmartSettings,
        noInterceptionLive: Boolean,
        pinConfigured: Boolean,
    ): PermissionsSecurityState {
        val loaded = SmartSettingsForm.fromSettings(settings)
        return PermissionsSecurityState(
            base = settings,
            maxLevel = loaded.maxLevel,
            whitelist = loaded.whitelist,
            noInterceptionLive = noInterceptionLive,
            pinConfigured = pinConfigured,
        )
    }

    /** Edits the level; the previous validation/save result belongs to the previous input and is cleared. */
    fun editMaxLevel(state: PermissionsSecurityState, level: String): PermissionsSecurityState =
        state.copy(maxLevel = level, validationErrors = emptyList(), result = null, restartCount = 0)

    /** Edits the whitelist text, with the same stale-result rule as the level. */
    fun editWhitelist(state: PermissionsSecurityState, text: String): PermissionsSecurityState =
        state.copy(whitelist = text, validationErrors = emptyList(), result = null, restartCount = 0)

    /**
     * The 不拦截 switch's request.
     *
     * Only the dangerous off → on move raises the dialog; on → off is the safe direction and saves immediately, because
     * a confirmation must never stand between a user and disabling unconfirmed execution. The switch does not move
     * until the write succeeds (or, for the safe direction, until the host has applied it).
     */
    fun requestNoInterception(
        state: PermissionsSecurityState,
        requested: Boolean,
        save: (SmartSettings) -> SettingsSaveResult,
    ): PermissionsSecurityState =
        if (NoInterceptionUi.requiresSecondConfirmation(state.noInterceptionLive, requested)) {
            state.copy(noInterceptionConfirmPending = true, noInterceptionResult = null)
        } else {
            applyNoInterception(state, requested, save)
        }

    /** The dialog's 「确认开启」 answer: the one immediate write the dangerous direction needs. */
    fun confirmNoInterception(
        state: PermissionsSecurityState,
        save: (SmartSettings) -> SettingsSaveResult,
    ): PermissionsSecurityState =
        if (!state.noInterceptionConfirmPending) {
            state
        } else {
            applyNoInterception(state, requested = true, save = save)
        }

    /** The dialog's 「取消」 answer: nothing moves, nothing is written. */
    fun dismissNoInterceptionConfirm(state: PermissionsSecurityState): PermissionsSecurityState =
        state.copy(noInterceptionConfirmPending = false)

    /**
     * The immediate write itself.
     *
     * A successful write updates both `base` and the live switch, so a later form save carries the new value. On a
     * refusal the live value follows the host's half-live rule (design §4.2): **on** applies only after the blob
     * accepted it, so the switch stays where it was; **off** is applied before the write and stays applied if the write
     * fails, so the switch moves off even though persistence must be retried. The next form save then writes that live
     * off value instead of restoring the old stored `true`.
     */
    private fun applyNoInterception(
        state: PermissionsSecurityState,
        requested: Boolean,
        save: (SmartSettings) -> SettingsSaveResult,
    ): PermissionsSecurityState {
        val result = save(state.base.copy(noInterception = requested))
        val live = when {
            result.saved -> requested
            !requested -> false
            else -> state.noInterceptionLive
        }
        return state.copy(
            base = if (result.saved) state.base.copy(noInterception = requested) else state.base,
            noInterceptionLive = live,
            noInterceptionConfirmPending = false,
            noInterceptionResult = result,
        )
    }

    /**
     * The explicit tool-permission save.
     *
     * The live 「不拦截」 value is written into the draft explicitly: `SmartSettingsForm.fromSettings(base)` would carry
     * the **stored** value, which may be stale after a failed safe-direction write, and an unrelated edit must never
     * re-arm a mode the user just turned off. Validation errors (ours or the form's) block the write. A refusal leaves
     * the draft untouched and keeps the page dirty.
     */
    fun save(
        state: PermissionsSecurityState,
        save: (SmartSettings) -> SettingsSaveResult,
    ): PermissionsSecurityState {
        val draft = SmartSettingsForm.fromSettings(state.base).copy(
            maxLevel = state.maxLevel,
            whitelist = state.whitelist,
            noInterception = state.noInterceptionLive,
        )
        val form = SmartSettingsForm.toSettings(draft, state.base)
        if (form.errors.isNotEmpty()) {
            return state.copy(validationErrors = form.errors, result = null, restartCount = 0)
        }

        val result = save(form.settings)
        if (!result.saved) {
            return state.copy(validationErrors = emptyList(), result = result, restartCount = 0)
        }

        val saved = form.settings
        val normalized = SmartSettingsForm.fromSettings(saved)
        return state.copy(
            base = saved,
            maxLevel = normalized.maxLevel,
            whitelist = normalized.whitelist,
            noInterceptionLive = saved.noInterception,
            validationErrors = emptyList(),
            result = result,
            restartCount = SmartSettingsForm.permissionRestartCount(state.base, saved),
        )
    }

    /**
     * Types a PIN. The field is digit-only and capped at the validator's maximum; the validator itself stays the save
     * gate. A PIN is never put into an error or a log - only the stable copy constants above are.
     */
    fun editPin(state: PermissionsSecurityState, text: String): PermissionsSecurityState = state.copy(
        pinInput = text.filter { it in '0'..'9' }.take(LockPinValidator.MAX_LENGTH),
        pinError = null,
        pinNotice = null,
    )

    /** Saves the typed PIN through the host's Keystore writer; only a boolean result leaves that layer. */
    fun savePin(
        state: PermissionsSecurityState,
        savePin: (String) -> Boolean,
    ): PermissionsSecurityState {
        val normalized = LockPinValidator.normalize(state.pinInput)
            ?: return state.copy(pinError = PermissionsSecurityUi.PIN_INVALID, pinNotice = null)
        if (!savePin(normalized)) {
            // The typed PIN is kept for a retry; the error copy never repeats it.
            return state.copy(pinError = PermissionsSecurityUi.PIN_SAVE_FAILED, pinNotice = null)
        }
        return state.copy(
            pinInput = "",
            pinConfigured = true,
            pinError = null,
            pinNotice = PermissionsSecurityUi.PIN_SAVED,
        )
    }

    /** Clears the PIN immediately; there is no confirmation in the old page and none is added here. */
    fun clearPin(
        state: PermissionsSecurityState,
        clearPin: () -> Unit,
    ): PermissionsSecurityState {
        clearPin()
        return state.copy(
            pinInput = "",
            pinConfigured = false,
            pinError = null,
            pinNotice = PermissionsSecurityUi.PIN_CLEARED,
        )
    }
}
