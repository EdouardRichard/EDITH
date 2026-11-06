package com.sheen.rokid.phone.settings

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.PermissionKind
import com.sheen.rokid.phone.ui.settings.PermissionState
import com.sheen.rokid.phone.ui.settings.PermissionsSecurityScreen
import com.sheen.rokid.phone.ui.settings.PermissionsSecurityUi
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsPageUi
import com.sheen.rokid.phone.ui.settings.SettingsShellContent
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §3.5/§4.2: the 权限与安全 page on the K40-safe foreground harness.
 *
 * The JVM suite ([PermissionsSecurityTest]) owns the state machine; these cases drive the real Compose semantics the
 * plan names: exactly two 「需重启」 chips (level + whitelist), the second confirmation only on off → on with the
 * immediate-write failure visible inline, the PIN saved/cleared notices, the missing-grant red text and 「去授权」
 * action, and the dirty guard on both exits.
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity] (the K40 aborts the Compose rule's own background
 * Activity start; see `SettingsHomeTest`), content installed on the real foreground MainActivity. The dirty-back cases
 * compose the production shape — the page inside the shell's dialog — and the system-back case injects a real BACK key
 * into the focused dialog window, the same shape Task 9's test uses.
 */
class PermissionsSecurityUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var foreground: ForegroundTestActivity

    @Before
    fun launchForegroundHost() {
        foreground = ForegroundTestActivity.launch()
    }

    @After
    fun closeForegroundHost() {
        if (::foreground.isInitialized) foreground.close()
    }

    private fun setContent(content: @Composable () -> Unit) {
        foreground.onActivity { activity -> activity.setContent { content() } }
    }

    private fun baseSettings(
        maxLevel: String = "L1",
        whitelist: List<String> = emptyList(),
        noInterception: Boolean = false,
    ) = SmartSettings(maxLevel = maxLevel, whitelist = whitelist, noInterception = noInterception)

    private fun allGranted(): List<PermissionState> =
        PermissionsSecurityUi.permissionStates(
            foregroundGranted = true,
            notificationsConnected = true,
            calendarGranted = true,
            overlayGranted = true,
        )

    /** Composes just the page inside a scrolling host so the save and PIN controls can be reached. */
    private fun setPage(
        base: SmartSettings = baseSettings(),
        live: Boolean = base.noInterception,
        pinConfigured: Boolean = false,
        permissions: List<PermissionState> = allGranted(),
        save: (SmartSettings) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
        savePin: (String) -> Boolean = { true },
        clearPin: () -> Unit = {},
    ) {
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PermissionsSecurityScreen(
                    load = { base },
                    save = save,
                    noInterceptionLive = live,
                    savePin = savePin,
                    clearPin = clearPin,
                    pinConfigured = pinConfigured,
                    permissionStates = permissions,
                    help = rememberHelpState(),
                )
            }
        }
    }

    private fun homeModel() = SettingsHomeModel(
        modelSummary = "未配置",
        modelStatus = ModelStatus.UNCONFIGURED,
        assistantSummary = "唤醒词、持续对话、感知截图、主动辅助",
        runtimeSummary = "纯手机模式 · 悬浮球 82% · 语音朗读",
        permissionsSummary = "工具权限、系统授权、锁屏 PIN",
        pendingPermissionCount = 0,
        capabilitiesSummary = "本地模型、执行通道、能力状态",
        unavailableCapabilityCount = 0,
        auditSummary = "已收起 · 进入后加载",
        aboutSummary = "EDITH ${BuildConfig.VERSION_NAME} · 预览版",
    )

    /** Composes the real shell and pushes the permissions page through the 「权限与安全」 row. */
    private fun setShellPage(
        base: SmartSettings = baseSettings(),
        permissions: List<PermissionState> = allGranted(),
        save: (SmartSettings) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
    ) {
        setContent {
            SettingsShellContent(
                homeModel = homeModel(),
                permissionsSecurity = { requestPop, registerBackGuard ->
                    PermissionsSecurityScreen(
                        load = { base },
                        save = save,
                        noInterceptionLive = base.noInterception,
                        savePin = { true },
                        clearPin = {},
                        pinConfigured = false,
                        permissionStates = permissions,
                        help = rememberHelpState(),
                        requestPop = requestPop,
                        registerBackGuard = registerBackGuard,
                    )
                },
            )
        }
        compose.onNodeWithText("权限与安全").performClick()
        compose.waitForIdle()
    }

    /** A real BACK key into the focused window — after [setShellPage], that is the page's dialog. */
    private fun pressBackOnFocusedWindow() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    // ---- restart scope and help --------------------------------------------------------------------------------

    // only maxLevel and whitelist restart; the immediate no-interception switch must not carry a chip
    @Test
    fun thePageShowsRestartChipsOnExactlyTheTwoToolPermissionFields() {
        setPage()

        compose.onNodeWithText(SettingsPageUi.RESTART_NOTE).assertExists()
        compose.onAllNodesWithText(SettingsPageUi.RESTART_CHIP).assertCountEquals(2)
        compose.onNodeWithContentDescription("查看权限上限说明").assertExists()
        compose.onNodeWithContentDescription("查看白名单说明").assertExists()
        compose.onNodeWithContentDescription("查看不拦截说明").assertExists()
        compose.onNodeWithContentDescription("查看锁屏 PIN说明").assertExists()
    }

    // the form writes both fields and reports the two-field permission restart count
    @Test
    fun aToolPermissionSaveReportsTwoRestartFields() {
        var saved: SmartSettings? = null
        setPage(save = { value ->
            saved = value
            SettingsSaveResult(saved = true)
        })

        compose.onNodeWithText(SettingsPageUi.SAVE).assertIsNotEnabled()
        compose.onNodeWithText("L3").performScrollTo().performClick()
        compose.onNodeWithContentDescription("白名单输入").performScrollTo()
            .performTextReplacement("launch|app")
        compose.onNodeWithText(SettingsPageUi.SAVE).performScrollTo().assertIsEnabled().performClick()

        compose.onNodeWithText("已保存 · 2 项需重启 App 生效").assertExists()
        val written = requireNotNull(saved)
        assertEquals("L3", written.maxLevel)
        assertEquals(listOf("launch|app"), written.whitelist)
    }

    // a refused save is visible and the draft is not lost
    @Test
    fun aFailedToolPermissionSaveStaysVisible() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED)
        setPage(save = { failure })

        compose.onNodeWithText("L2").performScrollTo().performClick()
        compose.onNodeWithText(SettingsPageUi.SAVE).performScrollTo().performClick()

        compose.onNodeWithText(requireNotNull(SettingsFeedback.saveFailedText(failure))).assertExists()
        compose.onAllNodesWithText("L2").assertCountEquals(1)
        compose.onNodeWithText(SettingsPageUi.SAVE).assertIsEnabled()
    }

    // ---- no interception: confirmation only off -> on, immediate write ---------------------------------------

    // turning it on asks first; turning it off never does; each confirmed move writes immediately
    @Test
    fun onlyTheOffToOnMoveAsksForTheNoInterceptionConfirmation() {
        val writes = mutableListOf<SmartSettings>()
        setPage(
            base = baseSettings(noInterception = false),
            live = false,
            save = { value ->
                writes += value
                SettingsSaveResult(saved = true)
            },
        )

        val noInterception = compose.onNodeWithContentDescription(NoInterceptionUi.SWITCH_TITLE)
        noInterception.performScrollTo().assertIsOff().performClick()

        compose.onNodeWithText(NoInterceptionUi.CONFIRM_TITLE).assertExists()
        assertTrue("the unconfirmed move must not write", writes.isEmpty())
        compose.onNodeWithText("取消").performClick()
        noInterception.performScrollTo().assertIsOff()

        noInterception.performClick()
        compose.onNodeWithText(NoInterceptionUi.CONFIRM_TITLE).assertExists()
        compose.onNodeWithText("确认开启").performClick()
        compose.waitForIdle()
        assertEquals(1, writes.size)
        assertTrue(writes.single().noInterception)
        noInterception.performScrollTo().assertIsOn()
        compose.onNodeWithText(NoInterceptionUi.ENABLED_WARNING).assertExists()

        // on -> off is immediate: no dialog, one write
        noInterception.performScrollTo().performClick()
        compose.onNodeWithText(NoInterceptionUi.CONFIRM_TITLE).assertDoesNotExist()
        assertEquals(2, writes.size)
        assertFalse(writes.last().noInterception)
        noInterception.performScrollTo().assertIsOff()
        compose.onNodeWithText(NoInterceptionUi.ENABLED_WARNING).assertDoesNotExist()
    }

    // a failed immediate write is visible and the switch keeps the live value (on applies only after the write)
    @Test
    fun aFailedImmediateNoInterceptionWriteIsVisible() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_STORE_UNAVAILABLE)
        setPage(base = baseSettings(noInterception = false), live = false, save = { failure })

        compose.onNodeWithContentDescription(NoInterceptionUi.SWITCH_TITLE).performScrollTo().performClick()
        compose.onNodeWithText("确认开启").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(requireNotNull(SettingsFeedback.saveFailedText(failure))).assertExists()
        compose.onNodeWithContentDescription(NoInterceptionUi.SWITCH_TITLE).performScrollTo().assertIsOff()
    }

    // ---- lock-screen PIN --------------------------------------------------------------------------------------

    // saving and clearing are immediate and each keeps its own notice
    @Test
    fun thePinSectionSavesAndClearsWithNotices() {
        val savedPins = mutableListOf<String>()
        var clears = 0
        var configured = false
        setPage(
            pinConfigured = false,
            savePin = { pin ->
                savedPins += pin
                configured = true
                true
            },
            clearPin = {
                clears++
                configured = false
            },
        )

        compose.onNodeWithText(PermissionsSecurityUi.PIN_PRIVACY).assertExists()
        compose.onNodeWithContentDescription("锁屏 PIN 输入").performScrollTo()
            .performTextReplacement("1234")
        compose.onNodeWithText(PermissionsSecurityUi.PIN_SAVE).performScrollTo().performClick()

        assertEquals(listOf("1234"), savedPins)
        compose.onNodeWithText(PermissionsSecurityUi.PIN_SAVED).assertExists()
        compose.onNodeWithText(PermissionsSecurityUi.PIN_CONFIGURED).assertExists()
        compose.onNodeWithText(PermissionsSecurityUi.PIN_UPDATE).assertExists()

        compose.onNodeWithText(PermissionsSecurityUi.PIN_CLEAR).performScrollTo().performClick()

        assertEquals(1, clears)
        compose.onNodeWithText(PermissionsSecurityUi.PIN_CLEARED).assertExists()
        compose.onNodeWithText(PermissionsSecurityUi.PIN_CONFIGURED).assertDoesNotExist()
        compose.onNodeWithText(PermissionsSecurityUi.PIN_SAVE).assertExists()
    }

    // ---- system grants ----------------------------------------------------------------------------------------

    // a missing grant keeps the old red sentence and gets a working 「去授权」 action
    @Test
    fun aMissingGrantKeepsItsRedTextAndAction() {
        val actions = mutableListOf<PermissionKind>()
        val permissions = PermissionsSecurityUi.permissionStates(
            foregroundGranted = false,
            notificationsConnected = true,
            calendarGranted = true,
            overlayGranted = false,
        ).map { state ->
            state.copy(onGrant = { actions += state.kind })
        }
        setPage(permissions = permissions)

        compose.onNodeWithText("未授予使用情况访问，主动辅助看不到前台 App。").assertExists()
        compose.onNodeWithText("未授予悬浮窗权限，确认弹窗无法在其他 App 上方弹出。").assertExists()
        compose.onAllNodesWithText(PermissionsSecurityUi.GRANT_ACTION).assertCountEquals(2)

        compose.onNodeWithContentDescription("去授权：使用情况访问").performScrollTo().performClick()
        compose.onNodeWithContentDescription("去授权：悬浮窗").performScrollTo().performClick()
        assertEquals(listOf(PermissionKind.USAGE_ACCESS, PermissionKind.OVERLAY), actions)
    }

    // ---- dirty back guard -------------------------------------------------------------------------------------

    // the top-left arrow asks before discarding a permission edit
    @Test
    fun theTopLeftBackArrowAsksBeforeDiscarding() {
        setShellPage()

        compose.onNodeWithText("L3").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(SettingsPageUi.DISCARD_KEEP).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertExists()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(SettingsPageUi.DISCARD_CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    // system back is a real focused-window back and takes the same guard
    @Test
    fun systemBackAsksBeforeDiscarding() {
        setShellPage()

        // Dirty through a chip, not a text field: with the IME open the platform routes BACK to the keyboard.
        compose.onNodeWithText("L2").performScrollTo().performClick()
        compose.onNodeWithText(SettingsPageUi.SAVE).assertIsEnabled()

        pressBackOnFocusedWindow()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(SettingsPageUi.DISCARD_KEEP).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertExists("继续编辑 must keep the page open")

        pressBackOnFocusedWindow()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(SettingsPageUi.DISCARD_CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithText("设置").assertExists()
    }

    // an untouched page leaves without asking
    @Test
    fun anUntouchedPageLeavesWithoutAsking() {
        setShellPage()

        pressBackOnFocusedWindow()

        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithText("设置").assertExists()
    }

    // ---- shell wiring -----------------------------------------------------------------------------------------

    // Task 7's placeholder for 权限与安全 is replaced by the real page through the shell slot
    @Test
    fun thePermissionsSecurityRouteOpensThePage() {
        setShellPage()

        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithContentDescription(NoInterceptionUi.SWITCH_TITLE).assertExists()
        compose.onNodeWithText("后续任务接入").assertDoesNotExist()
    }
}
