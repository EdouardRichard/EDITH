package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.lock.LockPinValidator
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm
import com.sheen.rokid.phone.ui.settings.AssistantBehaviorUi
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.PermissionKind
import com.sheen.rokid.phone.ui.settings.PermissionsSecurityLogic
import com.sheen.rokid.phone.ui.settings.PermissionsSecurityUi
import com.sheen.rokid.phone.ui.settings.SettingsPageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 design §3.5/§4.2/§6.1: the pure half of the 权限与安全 page.
 *
 * The Composable is thin, so the decisions that must not drift are pinned here:
 *
 * - **only off → on** asks for the second confirmation; on → off is immediate. The page wiring is tested, not only
 *   [NoInterceptionUi]'s truth table;
 * - the tool-permission form has its own restart scope of exactly two fields (`maxLevel` + `whitelist`), separate from
 *   the assistant page's four;
 * - the immediate 「不拦截」 write updates the page's base/live value, so a later form save cannot write the old value
 *   back;
 * - the PIN section keeps its validator, its saved/cleared notices and its privacy sentence, and a PIN never appears in
 *   a notice or an error;
 * - a missing system grant keeps its old inline red wording and the 「去授权」 action.
 */
class PermissionsSecurityTest {

    private fun settings(
        wakeWords: List<String> = listOf("伊迪丝", "edith"),
        maxLevel: String = "L1",
        whitelist: List<String> = emptyList(),
        noInterception: Boolean = false,
    ) = SmartSettings(
        wakeWords = wakeWords,
        maxLevel = maxLevel,
        whitelist = whitelist,
        noInterception = noInterception,
    )

    private fun initial(
        base: SmartSettings = settings(),
        live: Boolean = base.noInterception,
        pinConfigured: Boolean = false,
    ) = PermissionsSecurityLogic.initial(base, live, pinConfigured)

    // ---- no interception: the dangerous direction only ---------------------------------------------------------

    @Test
    fun `only the off to on transition asks for the no interception confirmation`() {
        val base = settings(noInterception = false)
        var state = initial(base, live = false)
        val written = mutableListOf<SmartSettings>()
        val save: (SmartSettings) -> SettingsSaveResult = { value ->
            written += value
            SettingsSaveResult(saved = true)
        }

        // off -> on: the switch does not move and nothing is written until the user confirms
        state = PermissionsSecurityLogic.requestNoInterception(state, true, save)
        assertTrue("the dangerous direction must raise the dialog", state.noInterceptionConfirmPending)
        assertTrue("an unconfirmed move must not write", written.isEmpty())
        assertFalse(state.noInterceptionLive)

        // cancelling keeps the mode off
        state = PermissionsSecurityLogic.dismissNoInterceptionConfirm(state)
        assertFalse(state.noInterceptionConfirmPending)
        assertFalse(state.noInterceptionLive)
        assertTrue(written.isEmpty())

        // confirming applies the one immediate write
        state = PermissionsSecurityLogic.requestNoInterception(state, true, save)
        state = PermissionsSecurityLogic.confirmNoInterception(state, save)
        assertEquals(1, written.size)
        assertTrue("the confirmed write carries the mode on", written.single().noInterception)
        assertTrue(state.noInterceptionLive)
        assertTrue("a successful immediate write becomes the page's base", state.base.noInterception)
        assertFalse(state.noInterceptionConfirmPending)

        // on -> off is the safe direction: immediate, no dialog
        state = PermissionsSecurityLogic.requestNoInterception(state, false, save)
        assertFalse("turning it off must never wait for a confirmation", state.noInterceptionConfirmPending)
        assertEquals(2, written.size)
        assertFalse(written.last().noInterception)
        assertFalse(state.noInterceptionLive)
    }

    @Test
    fun `a failed immediate no interception write is visible and the switch keeps the live value`() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED)
        var state = initial(settings(noInterception = false), live = false)

        state = PermissionsSecurityLogic.requestNoInterception(state, true) { failure }
        state = PermissionsSecurityLogic.confirmNoInterception(state) { failure }

        assertEquals(failure, state.noInterceptionResult)
        assertEquals(SettingsFeedback.saveFailedText(failure), state.noInterceptionMessage)
        assertFalse(
            "the host applies no-interception on only after a successful write",
            state.noInterceptionLive,
        )
        assertFalse("a failed write is not remembered as the stored value", state.base.noInterception)
        assertFalse("the immediate switch is not a dirty form edit", state.dirty)
    }

    @Test
    fun `a later form save cannot resurrect the old no interception value`() {
        // The host applies "off" before the blob write and keeps it applied if the write then fails (the safe-direction
        // half-live rule). The page must therefore carry the live off into the next form save instead of writing back
        // the stored `true`, which would re-arm unconfirmed execution from an unrelated field edit.
        var state = initial(settings(noInterception = true), live = true)
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED)
        state = PermissionsSecurityLogic.requestNoInterception(state, false) { failure }
        assertFalse("the live gate really moved off", state.noInterceptionLive)
        assertTrue("the failed write did not change persistence", state.base.noInterception)

        state = PermissionsSecurityLogic.editMaxLevel(state, "L2")
        var written: SmartSettings? = null
        state = PermissionsSecurityLogic.save(state) { value ->
            written = value
            SettingsSaveResult(saved = true)
        }

        val saved = requireNotNull(written)
        assertFalse("the next form save must persist the live off value", saved.noInterception)
        assertEquals("L2", saved.maxLevel)
        assertFalse(state.base.noInterception)
    }

    // ---- the tool permission form: two restart fields ----------------------------------------------------------

    @Test
    fun `the tool permission save reports two restart fields`() {
        val base = settings(maxLevel = "L1", whitelist = emptyList())
        var state = initial(base)
        assertFalse("a freshly loaded page is not dirty", state.dirty)

        state = PermissionsSecurityLogic.editMaxLevel(state, "L3")
        state = PermissionsSecurityLogic.editWhitelist(state, "launch|app")
        assertTrue(state.dirty)

        var written: SmartSettings? = null
        state = PermissionsSecurityLogic.save(state) { value ->
            written = value
            SettingsSaveResult(saved = true)
        }

        assertEquals(2, state.restartCount)
        assertEquals("已保存 · 2 项需重启 App 生效", state.saveMessage)
        assertFalse("a successful save clears the dirty state", state.dirty)
        val saved = requireNotNull(written)
        assertEquals("L3", saved.maxLevel)
        assertEquals(listOf("launch|app"), saved.whitelist)

        // one field at a time counts one, and an untouched save reports immediate
        var one = initial(base)
        one = PermissionsSecurityLogic.editWhitelist(one, "note|storage")
        one = PermissionsSecurityLogic.save(one) { SettingsSaveResult(saved = true) }
        assertEquals(1, one.restartCount)
        assertEquals("已保存 · 1 项需重启 App 生效", one.saveMessage)

        val untouched = PermissionsSecurityLogic.save(initial(base)) { SettingsSaveResult(saved = true) }
        assertEquals(0, untouched.restartCount)
        assertEquals("已保存，立即生效", untouched.saveMessage)
    }

    @Test
    fun `an invalid permission field is refused and the draft survives`() {
        var saves = 0
        var state = initial(settings(maxLevel = "L1"))
        state = PermissionsSecurityLogic.editMaxLevel(state, "L9")
        state = PermissionsSecurityLogic.editWhitelist(state, "launch|app")
        state = PermissionsSecurityLogic.save(state) {
            saves++
            SettingsSaveResult(saved = true)
        }

        assertEquals("a rejected form must not be written", 0, saves)
        assertTrue(state.validationErrors.any { it.contains("L0") })
        assertEquals("L9", state.maxLevel)
        assertEquals("launch|app", state.whitelist)
        assertTrue(state.dirty)
    }

    @Test
    fun `a refused tool permission save stays visible and keeps the draft`() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_STORE_UNAVAILABLE)
        var state = initial(settings())
        state = PermissionsSecurityLogic.editMaxLevel(state, "L2")
        state = PermissionsSecurityLogic.save(state) { failure }

        assertEquals(failure, state.result)
        assertEquals(SettingsFeedback.saveFailedText(failure), state.saveMessage)
        assertEquals("L2", state.maxLevel)
        assertTrue("a refused save leaves the page dirty", state.dirty)
    }

    // ---- the lock-screen PIN ------------------------------------------------------------------------------------

    @Test
    fun `the pin section keeps its saved and cleared notices`() {
        var state = initial(settings(), pinConfigured = false)
        var saves = 0
        var clears = 0

        // the validator is the save gate: too short is refused without touching the store
        state = PermissionsSecurityLogic.editPin(state, "123")
        state = PermissionsSecurityLogic.savePin(state) { saves++; true }
        assertEquals(0, saves)
        assertEquals(PermissionsSecurityUi.PIN_INVALID, state.pinError)
        assertNull(state.pinNotice)

        // a valid PIN is stored and the page says so, without repeating the PIN
        state = PermissionsSecurityLogic.editPin(state, "1234")
        var savedPin: String? = null
        state = PermissionsSecurityLogic.savePin(state) { pin ->
            savedPin = pin
            saves++
            true
        }
        assertEquals("1234", savedPin)
        assertEquals(1, saves)
        assertTrue(state.pinConfigured)
        assertEquals(PermissionsSecurityUi.PIN_SAVED, state.pinNotice)
        assertEquals("a successful save clears the input", "", state.pinInput)
        assertNull(state.pinError)
        assertFalse("the PIN is not a form edit and must not dirty the page", state.dirty)
        assertFalse("a PIN must never be echoed in a notice", state.pinNotice!!.contains("1234"))

        // a refused write is visible and keeps the typed PIN for a retry
        state = PermissionsSecurityLogic.editPin(state, "5678")
        state = PermissionsSecurityLogic.savePin(state) { false }
        assertTrue("a failed write does not pretend the PIN is gone", state.pinConfigured)
        assertEquals(PermissionsSecurityUi.PIN_SAVE_FAILED, state.pinError)
        assertNull(state.pinNotice)
        assertEquals("5678", state.pinInput)
        assertFalse("a PIN must never be echoed in an error", state.pinError!!.contains("5678"))

        // clearing is its own immediate action and says so
        state = PermissionsSecurityLogic.clearPin(state) { clears++ }
        assertEquals(1, clears)
        assertFalse(state.pinConfigured)
        assertEquals(PermissionsSecurityUi.PIN_CLEARED, state.pinNotice)
        assertEquals("", state.pinInput)
        assertNull(state.pinError)
        assertFalse(state.dirty)
    }

    @Test
    fun `the pin field accepts only ascii digits within the validator bounds`() {
        var state = initial(settings())
        state = PermissionsSecurityLogic.editPin(state, "12a34")
        assertEquals("digits only", "1234", state.pinInput)
        state = PermissionsSecurityLogic.editPin(state, "1".repeat(20))
        assertEquals("the field is capped at the validator maximum", "1".repeat(LockPinValidator.MAX_LENGTH), state.pinInput)
        state = PermissionsSecurityLogic.editPin(state, "１２３４")
        assertEquals("full-width digits are not PIN key codes", "", state.pinInput)
        assertEquals("1234", LockPinValidator.normalize("1234"))
        assertNull(LockPinValidator.normalize("123"))
        assertNull(LockPinValidator.normalize("12345678901234567"))
    }

    // ---- system grants -----------------------------------------------------------------------------------------

    @Test
    fun `a missing grant keeps its inline red text and action`() {
        val missing = PermissionsSecurityUi.permissionStates(
            foregroundGranted = false,
            notificationsConnected = false,
            calendarGranted = false,
            overlayGranted = false,
        )
        assertEquals("all four grants are always listed", PermissionKind.entries, missing.map { it.kind })
        assertFalse(missing.any { it.granted })
        assertEquals("使用情况访问", missing[0].label)
        assertEquals("通知使用权", missing[1].label)
        assertEquals("日历权限", missing[2].label)
        assertEquals("悬浮窗", missing[3].label)
        // The old inline red copy is preserved verbatim (appendix A17).
        assertEquals("未授予使用情况访问，主动辅助看不到前台 App。", missing[0].missingText)
        assertEquals("未连接通知监听，主动辅助看不到通知分类（只读分类，不读通知内容）。", missing[1].missingText)
        assertEquals("未授予日历权限，主动辅助看不到今天的日程标题。", missing[2].missingText)
        assertEquals("未授予悬浮窗权限，确认弹窗无法在其他 App 上方弹出。", missing[3].missingText)
        missing.forEach { grant ->
            assertEquals("每行缺失时都有「去授权」动作", PermissionsSecurityUi.GRANT_ACTION, grant.actionLabel)
        }

        assertEquals(
            "缺失行的状态文案沿用旧页的语义",
            listOf("未授权", "未连接", "未授权", "未授权"),
            missing.map { PermissionsSecurityUi.missingStatusText(it.kind) },
        )
        assertEquals(
            listOf("已授权", "已连接", "已授权", "已授权"),
            PermissionKind.entries.map(PermissionsSecurityUi::grantedStatusText),
        )

        val granted = PermissionsSecurityUi.permissionStates(true, true, true, true)
        assertTrue(granted.all { it.granted })
        assertTrue(granted.all { it.missingText.isNotBlank() })

        val mixed = PermissionsSecurityUi.permissionStates(
            foregroundGranted = true,
            notificationsConnected = false,
            calendarGranted = true,
            overlayGranted = false,
        )
        assertEquals(listOf(true, false, true, false), mixed.map { it.granted })
    }

    // ---- copy --------------------------------------------------------------------------------------------------

    @Test
    fun `the page keeps the shared restart and discard copy`() {
        assertEquals("带「需重启」的项会在完全关闭并重新打开 App 后生效。", SettingsPageUi.RESTART_NOTE)
        assertEquals(SettingsPageUi.RESTART_NOTE, AssistantBehaviorUi.RESTART_NOTE)
        assertEquals("需重启", SettingsPageUi.RESTART_CHIP)
        assertEquals("放弃修改？", SettingsPageUi.DISCARD_TITLE)
        assertEquals("放弃", SettingsPageUi.DISCARD_CONFIRM)
        assertEquals("继续编辑", SettingsPageUi.DISCARD_KEEP)
        assertEquals("保存", SettingsPageUi.SAVE)
        assertFalse("the retired term must not appear", SettingsPageUi.RESTART_NOTE.contains("档案"))
    }

    @Test
    fun `the pin section keeps its privacy sentence`() {
        assertNotNull(HelpContent.LOCK_PIN)
        val privacy = PermissionsSecurityUi.PIN_PRIVACY
        assertTrue("PIN 只保存在本机 Keystore", privacy.contains("只保存在本机 Keystore"))
        assertTrue("不写日志", privacy.contains("不写日志"))
        assertTrue("不上传", privacy.contains("不上传"))
        assertEquals("the visible sentence is the (?) body, not a second copy", HelpContent.LOCK_PIN.body, privacy)
        assertEquals("锁屏 PIN", HelpContent.LOCK_PIN.title)
        assertEquals("不拦截模式（高风险）", NoInterceptionUi.SWITCH_TITLE)
    }
}
