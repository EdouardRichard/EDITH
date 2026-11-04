package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.app.HostCompositionRoot.PhoneOnlyOpacityResult
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachSetResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.RuntimeAppearanceScreen
import com.sheen.rokid.phone.ui.settings.RuntimeAppearanceUi
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsShellContent
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §3.4/§4.2: the runtime/appearance page on the K40-safe foreground harness.
 *
 * The JVM suite (`SettingsFeedbackTest`) owns the result-to-copy mapping; these cases drive the real Compose
 * semantics the plan names:
 *
 * - the speech switch shows a refused write inline instead of only logging it;
 * - a slider drag previews on every move and commits **only** on release (the split touch gesture proves nothing is
 *   written while the finger is still down);
 * - the phone-only switch reports the stop/result wording from [DetachUi];
 * - the running state and a start failure are visible;
 * - the sliders stay visible while the mode is off, with the 「模式开启后生效」 note;
 * - the shell's `RuntimeAppearance` route opens the page (Task 7 left a placeholder there).
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity] (the K40 aborts the Compose rule's own background
 * Activity start; see `SettingsHomeTest`), content installed on the real foreground MainActivity.
 */
class RuntimeAppearanceUiTest {

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

    /** Composes the page inside a scrolling host so every control can be reached at fontScale 1. */
    private fun setPage(
        detached: Boolean = false,
        ballOpacity: Int = 82,
        bubbleOpacity: Int = 75,
        speechEnabled: Boolean = false,
        onDetach: (Boolean) -> DetachSetResult = { DetachSetResult(saved = true) },
        onPreview: (Int, Int) -> Unit = { _, _ -> },
        onCommit: (Int, Int) -> PhoneOnlyOpacityResult = { _, _ -> PhoneOnlyOpacityResult(saved = true) },
        onSpeech: (Boolean) -> SettingsSaveResult = { SettingsSaveResult(saved = true) },
        running: Boolean = false,
        startFailure: String? = null,
    ) {
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                RuntimeAppearanceScreen(
                    detached = detached,
                    onDetach = onDetach,
                    ballOpacity = ballOpacity,
                    bubbleOpacity = bubbleOpacity,
                    onPreviewOpacity = onPreview,
                    onCommitOpacity = onCommit,
                    speechEnabled = speechEnabled,
                    onSpeech = onSpeech,
                    phoneOnlyRunning = running,
                    startFailure = startFailure,
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

    // ---- speech ------------------------------------------------------------------------------------------------

    // a refused speech write is visible inline; the switch still asks the host to run the setter once
    @Test
    fun turningSpeechOffShowsTheFailureNoticeWhenTheWriteFails() {
        val toggles = mutableListOf<Boolean>()
        setPage(
            speechEnabled = true,
            onSpeech = { enabled ->
                toggles += enabled
                SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_STORE_UNAVAILABLE)
            },
        )

        compose.onNodeWithContentDescription(RuntimeAppearanceUi.SPEECH_SWITCH)
            .performScrollTo()
            .performClick()

        assertEquals("the switch calls the immediate setter once", listOf(false), toggles)
        compose.onNodeWithText("保存失败：设置存储不可用，未保存。").assertExists()
    }

    // a successful speech switch says the change already took effect
    @Test
    fun aSuccessfulSpeechToggleSaysItIsImmediate() {
        setPage(speechEnabled = false, onSpeech = { SettingsSaveResult(saved = true) })

        compose.onNodeWithContentDescription(RuntimeAppearanceUi.SPEECH_SWITCH)
            .performScrollTo()
            .performClick()

        compose.onNodeWithText("已保存，立即生效").assertExists()
    }

    // ---- opacity -----------------------------------------------------------------------------------------------

    // the drag previews on every move and the durable write happens only when the finger lifts
    @Test
    fun draggingOpacityPreviewsAndOnlyCommitsOnRelease() {
        val previews = mutableListOf<Pair<Int, Int>>()
        val commits = mutableListOf<Pair<Int, Int>>()
        setPage(
            onPreview = { ball, bubble -> previews += ball to bubble },
            onCommit = { ball, bubble ->
                commits += ball to bubble
                PhoneOnlyOpacityResult(saved = true)
            },
        )

        val slider = compose.onNodeWithContentDescription(RuntimeAppearanceUi.BALL_SLIDER)
            .performScrollTo()

        slider.performTouchInput { down(center) }
        slider.performTouchInput { moveTo(Offset(center.x - width * 0.2f, center.y)) }
        compose.waitForIdle()

        assertTrue("dragging must preview the live value", previews.isNotEmpty())
        assertTrue("no write may happen while the finger is still down", commits.isEmpty())

        slider.performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("release is the one commit point", 1, commits.size)
        assertEquals("the committed values are the previewed values", previews.last(), commits.single())
        val (ball, bubble) = commits.single()
        compose.onNodeWithText(
            RuntimeAppearanceUi.opacityText(
                saved = true,
                error = null,
                ballPercent = ball,
                bubblePercent = bubble,
                detached = false,
            ),
        ).assertExists()
    }

    // the reset control keeps the shipped 82/75 defaults and saves them
    @Test
    fun resettingOpacitySavesTheShippedDefaults() {
        val previews = mutableListOf<Pair<Int, Int>>()
        val commits = mutableListOf<Pair<Int, Int>>()
        setPage(
            ballOpacity = 40,
            bubbleOpacity = 45,
            onPreview = { ball, bubble -> previews += ball to bubble },
            onCommit = { ball, bubble ->
                commits += ball to bubble
                PhoneOnlyOpacityResult(saved = true)
            },
        )

        compose.onNodeWithText(RuntimeAppearanceUi.RESET_OPACITY).performScrollTo().performClick()

        assertEquals(listOf(82 to 75), previews)
        assertEquals(listOf(82 to 75), commits)
        compose.onNodeWithText(
            RuntimeAppearanceUi.opacityText(true, null, 82, 75, detached = false),
        ).assertExists()
    }

    // a refused opacity write is visible inline instead of only logged
    @Test
    fun aFailedOpacityWriteIsVisible() {
        setPage(
            onCommit = { _, _ ->
                PhoneOnlyOpacityResult(saved = false, error = SettingsSaveResult.ERROR_SAVE_FAILED)
            },
        )

        compose.onNodeWithText(RuntimeAppearanceUi.RESET_OPACITY).performScrollTo().performClick()

        compose.onNodeWithText("保存失败：设置写入失败，未保存。").assertExists()
    }

    // the sliders stay visible with the mode off and say when they will take effect
    @Test
    fun theModeOffPageKeepsTheSlidersAndExplainsWhenTheyApply() {
        setPage(detached = false)

        compose.onNodeWithContentDescription(RuntimeAppearanceUi.BALL_SLIDER).assertExists()
        compose.onNodeWithContentDescription(RuntimeAppearanceUi.BUBBLE_SLIDER).assertExists()
        compose.onNodeWithText(RuntimeAppearanceUi.MODE_OFF_NOTE).assertExists()
    }

    // ---- phone-only mode ---------------------------------------------------------------------------------------

    // the switch reports the gate's single result, including the session that had to stop first
    @Test
    fun thePhoneOnlySwitchReportsTheStopBeforeSaveResult() {
        val toggles = mutableListOf<Boolean>()
        setPage(
            detached = true,
            onDetach = { enabled ->
                toggles += enabled
                DetachSetResult(saved = true, stoppedSession = true)
            },
        )

        compose.onNodeWithContentDescription(RuntimeAppearanceUi.PHONE_ONLY_SWITCH)
            .performScrollTo()
            .performClick()

        assertEquals(listOf(false), toggles)
        compose.onNodeWithText(DetachUi.successText(stoppedSession = true)).assertExists()
        // The long explanation is in the (?) popover, not inline on the page.
        compose.onNodeWithText(DetachUi.SWITCH_SUMMARY).assertDoesNotExist()
    }

    // a refused mode change keeps the DetachUi failure wording visible
    @Test
    fun aFailedPhoneOnlySwitchShowsTheDetachFailure() {
        setPage(
            detached = true,
            onDetach = {
                DetachSetResult(
                    saved = false,
                    stoppedSession = true,
                    error = DetachGate.ERROR_STOP_FAILED,
                )
            },
        )

        compose.onNodeWithContentDescription(RuntimeAppearanceUi.PHONE_ONLY_SWITCH)
            .performScrollTo()
            .performClick()

        compose.onNodeWithText(
            DetachUi.errorText(DetachGate.ERROR_STOP_FAILED, stoppedSession = true),
        ).assertExists()
    }

    // ---- running state and help --------------------------------------------------------------------------------

    // a running overlay and a recorded start failure each get their visible line
    @Test
    fun thePageShowsTheRunningOverlay() {
        setPage(detached = true, running = true)

        compose.onNodeWithText(RuntimeAppearanceUi.RUNNING).assertExists()
    }

    @Test
    fun thePageShowsTheStartFailure() {
        setPage(
            detached = true,
            running = false,
            startFailure = "悬浮球窗口未能显示（系统拒绝了悬浮窗）",
        )

        compose.onNodeWithText(
            "悬浮球启动失败：悬浮球窗口未能显示（系统拒绝了悬浮窗）",
        ).assertExists()
    }

    // every control's explanation lives behind its own (?) icon
    @Test
    fun everyRuntimeControlHasItsHelpIcon() {
        var activity: MainActivity? = null
        foreground.onActivity { activity = it }
        setPage()

        listOf(
            "查看纯手机模式说明",
            "查看悬浮球透明度说明",
            "查看气泡透明度（整泡含文字）说明",
            "查看语音朗读说明",
        ).forEach { description -> compose.onNodeWithContentDescription(description).assertExists() }

        compose.onNodeWithContentDescription("查看纯手机模式说明").performScrollTo().performClick()
        compose.onNodeWithText(HelpContent.PHONE_ONLY.body).assertExists()

        // The focusable popup owns window focus; close it through the Activity dispatcher before the harness finishes.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(activity).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    // ---- shell wiring ------------------------------------------------------------------------------------------

    // Task 7's placeholder for 运行与外观 is replaced by the real page through the shell slot
    @Test
    fun theRuntimeAppearanceRouteOpensThePage() {
        setContent {
            SettingsShellContent(
                homeModel = homeModel(),
                runtimeAppearance = {
                    RuntimeAppearanceScreen(
                        detached = false,
                        onDetach = { DetachSetResult(saved = true) },
                        ballOpacity = 82,
                        bubbleOpacity = 75,
                        onPreviewOpacity = { _, _ -> },
                        onCommitOpacity = { _, _ -> PhoneOnlyOpacityResult(saved = true) },
                        speechEnabled = false,
                        onSpeech = { SettingsSaveResult(saved = true) },
                        phoneOnlyRunning = false,
                        startFailure = null,
                        help = rememberHelpState(),
                    )
                },
            )
        }

        compose.onNodeWithText("运行与外观").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithContentDescription(RuntimeAppearanceUi.SPEECH_SWITCH).assertExists()
        compose.onNodeWithText("后续任务接入").assertDoesNotExist()
    }
}
