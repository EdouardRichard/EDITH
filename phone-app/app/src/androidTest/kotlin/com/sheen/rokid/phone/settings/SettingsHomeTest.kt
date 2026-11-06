package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.ui.settings.AboutScreen
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsHomeScreen
import com.sheen.rokid.phone.ui.settings.SettingsRoute
import com.sheen.rokid.phone.ui.settings.SettingsShellContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §3.1/§3.2/§4.4: the settings home is navigation plus summaries only - seven entries in four groups, a
 * clickable row per entry, an error-coloured model summary when nothing is configured, an audit row that never reads
 * the audit log on the home, an About page that names the build and reopens the model service, and a shell that keeps
 * the home composed (and scrolled) under a secondary page.
 */
class SettingsHomeTest {

    // Empty rule: the K40 aborts instrumented background Activity starts, so the rule may not launch its own host.
    // The project harness shell-starts the real MainActivity (shell UID) and waits for RESUMED + window focus.
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

    /** Sets the test composition on the foreground MainActivity owned by [ForegroundTestActivity]. */
    private fun setContent(content: @Composable () -> Unit) {
        foreground.onActivity { activity -> activity.setContent { content() } }
    }

    private fun homeModel(
        modelSummary: String = "未配置",
        modelStatus: ModelStatus = ModelStatus.UNCONFIGURED,
    ) = SettingsHomeModel(
        modelSummary = modelSummary,
        modelStatus = modelStatus,
        assistantSummary = "唤醒词、持续对话、感知截图、主动辅助",
        runtimeSummary = "纯手机模式 · 悬浮球 82% · 语音朗读",
        permissionsSummary = "工具权限、系统授权、锁屏 PIN",
        pendingPermissionCount = 0,
        capabilitiesSummary = "本地模型、执行通道、能力状态",
        unavailableCapabilityCount = 0,
        auditSummary = "已收起 · 进入后加载",
        aboutSummary = "EDITH ${BuildConfig.VERSION_NAME} · 预览版",
    )

    // the settings home shows exactly seven entries in four groups
    @Test
    fun theSettingsHomeShowsExactlySevenEntriesInFourGroups() {
        setContent { SettingsHomeScreen(model = homeModel(), onOpen = {}) }

        // One clickable row per entry: the home has no other clickable surface.
        compose.onAllNodes(hasClickAction()).assertCountEquals(7)
        listOf("模型", "助手", "安全与设备", "高级").forEach { group ->
            compose.onNodeWithText(group).assertExists("missing group header $group")
        }
        listOf(
            "模型服务",
            "助手行为",
            "运行与外观",
            "权限与安全",
            "本地能力",
            "审计记录",
            "关于",
        ).forEach { entry ->
            compose.onNodeWithText(entry).assertExists("missing settings entry $entry")
        }
    }

    // clicking the model service row opens that route
    @Test
    fun clickingTheModelServiceRowOpensThatRoute() {
        var opened: SettingsRoute? = null
        setContent {
            SettingsHomeScreen(model = homeModel(), onOpen = { opened = it })
        }

        compose.onNodeWithText("模型服务").performClick()

        assertEquals(SettingsRoute.ModelService, opened)
    }

    // the model service summary renders the unconfigured error style
    @Test
    fun theModelServiceSummaryRendersTheUnconfiguredErrorStyle() {
        setContent { SettingsHomeScreen(model = homeModel(), onOpen = {}) }

        compose.onNode(hasText("未配置") and hasStateDescription("错误")).assertExists()
    }

    // the audit row says collapsed and never calls a loader
    @Test
    fun theAuditRowSaysCollapsedAndNeverCallsALoader() {
        var loaderCalls = 0
        setContent {
            SettingsHomeScreen(
                model = homeModel(),
                onOpen = {},
                auditLines = {
                    loaderCalls++
                    throw AssertionError("the settings home must not read the audit log")
                },
            )
        }

        compose.onNodeWithText("已收起 · 进入后加载").assertExists()
        assertEquals("the home may not load the audit log", 0, loaderCalls)
    }

    // about renders the build name and opens model service
    @Test
    fun aboutRendersTheBuildNameAndOpensModelService() {
        var opened = false
        setContent { AboutScreen(onOpenModelService = { opened = true }) }

        compose.onNodeWithText("EDITH ${BuildConfig.VERSION_NAME}", substring = true).assertExists()
        compose.onNodeWithText(if (BuildConfig.DEBUG) "调试版" else "发布版", substring = true).assertExists()
        compose.onNodeWithText("模型服务").performClick()

        assertTrue("the About page's model-service entry must open the page", opened)
    }

    // 二级页返回首页时保留滚动位置（design §4.4）
    @Test
    fun returningFromASecondaryPageKeepsTheHomeScrollPosition() {
        val scrollState = ScrollState(0)
        setContent {
            // Smaller than the seven-row home, so the home really scrolls inside the test host.
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                SettingsShellContent(
                    homeModel = homeModel(),
                    homeScrollState = scrollState,
                )
            }
        }

        // Slow swipe: past touch slop but too slow to fling, so the offset stays in the range the row below needs.
        compose.onNode(hasScrollAction()).performTouchInput {
            swipeUp(startY = height * 0.8f, endY = height * 0.8f - 40.dp.toPx(), durationMillis = 1_000)
        }
        compose.waitForIdle()
        val scrolled = scrollState.value
        assertTrue("the home must scroll inside the 300dp host, offset was $scrolled", scrolled > 0)

        compose.onNodeWithText("模型服务").performClick()

        // The page is a dialog over the still-composed home: both roots are alive here, and the home has not lost
        // the scroll state that lives in the shell.
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithText("后续任务接入").assertExists("the model-service page must be the open detail page")
        compose.onNodeWithText("设置").assertExists("the home must stay composed under a secondary page")

        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNode(hasScrollAction()).assertIsDisplayed()
        assertEquals("returning must keep the home scroll offset", scrolled, scrollState.value)
    }
}
