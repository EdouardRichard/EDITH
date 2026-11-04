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
import androidx.compose.ui.test.assertTextContains
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
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.ui.settings.AssistantBehaviorScreen
import com.sheen.rokid.phone.ui.settings.AssistantBehaviorUi
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
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
 * 0.52 design §3.3/§4.4: the assistant behavior page on the K40-safe foreground harness.
 *
 * The JVM suite (`AssistantBehaviorTest`) owns the state machine; these cases drive the real Compose semantics:
 * exactly four 「需重启」 chips, the persistent vision 「敏感」 badge, the form's restart-count result, the inline failure
 * that keeps the draft, the immediate proactive switch outside the save, the phone-only disabled state with A12 in
 * `(?)`, and both ways out (top-left arrow and system back) asking 「放弃修改？」 only while the draft is dirty.
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity] (the K40 aborts the Compose rule's own background
 * Activity start; see `SettingsHomeTest`). Content is installed on the real foreground MainActivity. The two system-back
 * cases compose the production shape instead — the page inside the shell's `PhoneDetailPage` dialog — and inject a real
 * BACK key so the dialog window, not the Activity dispatcher, owns the event.
 */
class AssistantBehaviorUiTest {

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
        wakeWords: List<String> = listOf("伊迪丝", "edith"),
        proactiveCooldownMillis: Long = 60_000L,
        proactiveSensitivity: String = "LOW",
        proactiveEnabled: Boolean = true,
        continueDialogue: Boolean = true,
        visionEnabled: Boolean = true,
        glassesDetached: Boolean = false,
    ) = SmartSettings(
        wakeWords = wakeWords,
        proactiveCooldownMillis = proactiveCooldownMillis,
        proactiveSensitivity = proactiveSensitivity,
        proactiveEnabled = proactiveEnabled,
        continueDialogue = continueDialogue,
        visionEnabled = visionEnabled,
        glassesDetached = glassesDetached,
    )

    /**
     * Composes just the page (content, badges, save and the immediate switch), with a scrolling host so the save
     * control can be reached at fontScale 1.
     *
     * The dirty-back cases deliberately do not use this: they must go through [setShellPage].
     */
    private fun setPage(
        base: SmartSettings = baseSettings(),
        save: (SmartSettings) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
        setProactive: (Boolean) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
    ) {
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AssistantBehaviorScreen(
                    load = { base },
                    save = save,
                    setProactive = setProactive,
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

    /**
     * Composes the real shell and pushes the assistant page through the 「助手行为」 row.
     *
     * Production puts the page inside `SettingsShellContent`'s `PhoneDetailPage` **Dialog**, so system back must be
     * driven there: composing the page directly on the Activity would address the Activity's back dispatcher, while
     * the dialog window owns the back event the user actually presses.
     */
    private fun setShellPage(
        base: SmartSettings = baseSettings(),
        save: (SmartSettings) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
        setProactive: (Boolean) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
    ) {
        setContent {
            SettingsShellContent(
                homeModel = homeModel(),
                assistantBehavior = { requestPop, registerBackGuard ->
                    AssistantBehaviorScreen(
                        load = { base },
                        save = save,
                        setProactive = setProactive,
                        help = rememberHelpState(),
                        requestPop = requestPop,
                        registerBackGuard = registerBackGuard,
                    )
                },
            )
        }
        compose.onNodeWithText("助手行为").performClick()
        compose.waitForIdle()
    }

    /**
     * A real BACK key press on the window that currently has focus — after [setShellPage] that is the page's dialog.
     *
     * This is the same focused-window path `Espresso.pressBack()` wraps (`Instrumentation` injects the key into the
     * input pipeline); Espresso itself is only a runtime dependency of this test APK, so the injection is used
     * directly. Calling `MainActivity.onBackPressedDispatcher.onBackPressed()` instead addresses the Activity's
     * dispatcher and never the dialog's own, which is the dispatcher production system back reaches.
     */
    private fun pressBackOnFocusedWindow() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    // ---- the page's copy and badges ---------------------------------------------------------------------------

    // exactly the four restart fields carry a 「需重启」 chip
    @Test
    fun theAssistantPageShowsRestartChipsOnExactlyTheFourRestartFields() {
        setPage()

        compose.onNodeWithText(AssistantBehaviorUi.RESTART_NOTE).assertExists()
        compose.onAllNodesWithText(AssistantBehaviorUi.RESTART_CHIP).assertCountEquals(4)
    }

    // the vision row keeps the sensitive badge even though the risk text moved into (?)
    @Test
    fun theVisionRowKeepsTheSensitiveBadgeVisible() {
        setPage()

        compose.onAllNodesWithText(AssistantBehaviorUi.SENSITIVE_BADGE).assertCountEquals(1)
        compose.onNodeWithContentDescription("查看感知截图说明").assertExists()
    }

    // ---- the form save ----------------------------------------------------------------------------------------

    // the save button is only enabled after a real edit, and its result counts restart fields
    @Test
    fun aSaveResultCountsTheRestartFields() {
        var saved: SmartSettings? = null
        setPage(save = { settings ->
            saved = settings
            SettingsSaveResult(saved = true)
        })

        compose.onNodeWithText(AssistantBehaviorUi.SAVE).assertIsNotEnabled()

        compose.onNodeWithContentDescription("唤醒词输入").performTextReplacement("EDITH")
        compose.onNodeWithContentDescription("持续对话").performClick()

        compose.onNodeWithText(AssistantBehaviorUi.SAVE).performScrollTo().assertIsEnabled().performClick()

        compose.onNodeWithText("已保存 · 2 项需重启 App 生效").assertExists()
        val written = requireNotNull(saved)
        assertEquals(listOf("EDITH"), written.wakeWords)
        assertFalse(written.continueDialogue)
        assertTrue(written.visionEnabled)
        assertTrue(written.proactiveEnabled)
    }

    // a refused save is visible inline and the user's draft survives it
    @Test
    fun aFailedSaveShowsTheMappedErrorAndKeepsTheDraft() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED)
        setPage(save = { failure })

        compose.onNodeWithContentDescription("唤醒词输入").performTextReplacement("EDITH")
        compose.onNodeWithText(AssistantBehaviorUi.SAVE).performScrollTo().performClick()

        val failureText = requireNotNull(SettingsFeedback.saveFailedText(failure))
        compose.onNodeWithText(failureText).assertExists()
        compose.onNodeWithContentDescription("唤醒词输入").assertTextContains("EDITH")
        compose.onNodeWithText(AssistantBehaviorUi.SAVE).assertIsEnabled()
    }

    // ---- the immediate proactive switch ----------------------------------------------------------------------

    // the proactive switch saves immediately, outside the form, and a refusal is visible inline
    @Test
    fun theProactiveToggleIsImmediateAndReportsFailureInline() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED)
        val toggles = mutableListOf<Boolean>()
        var formSaves = 0
        setPage(
            save = { formSaves++; SettingsSaveResult(saved = true) },
            setProactive = { enabled ->
                toggles += enabled
                failure
            },
        )

        val switch = compose.onNodeWithContentDescription("未唤醒时给一句短提示")
        switch.assertIsOn().performClick()

        assertEquals("the switch calls the immediate setter once", listOf(false), toggles)
        assertEquals("the proactive switch never goes through the form save", 0, formSaves)
        switch.assertIsOff()
        compose.onNodeWithText(requireNotNull(SettingsFeedback.saveFailedText(failure))).assertExists()
    }

    // a successful immediate toggle says it took effect without touching the form
    @Test
    fun aSuccessfulProactiveToggleSaysItIsImmediate() {
        val toggles = mutableListOf<Boolean>()
        setPage(setProactive = { enabled ->
            toggles += enabled
            SettingsSaveResult(saved = true)
        })

        compose.onNodeWithContentDescription("未唤醒时给一句短提示").performClick()

        assertEquals(listOf(false), toggles)
        compose.onNodeWithContentDescription("未唤醒时给一句短提示").assertIsOff()
        compose.onNodeWithText(AssistantBehaviorUi.PROACTIVE_SAVED).assertExists()
        compose.onNodeWithText(AssistantBehaviorUi.SAVE).assertIsNotEnabled()
    }

    // phone-only mode disables but keeps the switch, and A12 stays in the (?) body
    @Test
    fun phoneOnlyModeKeepsTheProactiveSwitchVisibleButDisabledWithA12InHelp() {
        var activity: MainActivity? = null
        foreground.onActivity { activity = it }
        setPage(base = baseSettings(glassesDetached = true))

        compose.onNodeWithContentDescription("未唤醒时给一句短提示").assertExists().assertIsNotEnabled()

        compose.onNodeWithContentDescription("查看主动辅助说明").performClick()
        compose.onNodeWithText(HelpContent.PROACTIVE.body).assertExists()
        compose.onNodeWithText(DetachUi.PROACTIVE_DETACHED_NOTE, substring = true).assertExists()

        // The focusable popup owns window focus; close it through the Activity dispatcher before the harness finishes.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(activity).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    // ---- the dirty-back guard ---------------------------------------------------------------------------------

    // the top-left arrow asks before discarding and leaving keeps the draft
    @Test
    fun theTopLeftBackArrowAsksBeforeDiscarding() {
        setContent {
            SettingsShellContent(
                homeModel = homeModel(),
                assistantBehavior = { requestPop, registerBackGuard ->
                    AssistantBehaviorScreen(
                        load = { baseSettings() },
                        save = { SettingsSaveResult(saved = true) },
                        setProactive = { SettingsSaveResult(saved = true) },
                        help = rememberHelpState(),
                        requestPop = requestPop,
                        registerBackGuard = registerBackGuard,
                    )
                },
            )
        }

        compose.onNodeWithText("助手行为").performClick()
        compose.onNodeWithContentDescription("唤醒词输入").performTextReplacement("EDITH")

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_KEEP).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription("唤醒词输入").assertTextContains("EDITH")

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    // system back is a real focused-window back on the page's dialog and takes the same dirty guard
    @Test
    fun systemBackAsksBeforeDiscarding() {
        setShellPage()
        compose.onNodeWithContentDescription("唤醒词输入").performTextReplacement("EDITH")
        compose.waitForIdle()

        pressBackOnFocusedWindow()

        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_KEEP).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertExists("继续编辑 must keep the page open")
        compose.onNodeWithContentDescription("唤醒词输入").assertTextContains("EDITH")

        pressBackOnFocusedWindow()

        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertExists()
        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithContentDescription("唤醒词输入").assertDoesNotExist()
        // The shell is still composed on its home: 放弃 closed the page, not the Activity.
        compose.onNodeWithText("设置").assertExists()
    }

    // an untouched page leaves immediately on system back, with no discard question
    @Test
    fun anUntouchedPageLeavesWithoutAsking() {
        setShellPage()

        pressBackOnFocusedWindow()

        compose.onNodeWithText(AssistantBehaviorUi.DISCARD_TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithContentDescription("唤醒词输入").assertDoesNotExist()
        // The shell is still composed on its home: the page closed, not the Activity.
        compose.onNodeWithText("设置").assertExists()
    }
}
