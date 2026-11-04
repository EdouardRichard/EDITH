package com.sheen.rokid.phone.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ui.settings.AboutScreen
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsHomeScreen
import com.sheen.rokid.phone.ui.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §3.1/§3.2: the settings home is navigation plus summaries only - seven entries in four groups, a
 * clickable row per entry, an error-coloured model summary when nothing is configured, an audit row that never reads
 * the audit log on the home, and an About page that names the build and reopens the model service.
 */
class SettingsHomeTest {

    @get:Rule
    val compose = createComposeRule()

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
        compose.setContent { SettingsHomeScreen(model = homeModel(), onOpen = {}) }

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
        compose.setContent {
            SettingsHomeScreen(model = homeModel(), onOpen = { opened = it })
        }

        compose.onNodeWithText("模型服务").performClick()

        assertEquals(SettingsRoute.ModelService, opened)
    }

    // the model service summary renders the unconfigured error style
    @Test
    fun theModelServiceSummaryRendersTheUnconfiguredErrorStyle() {
        compose.setContent { SettingsHomeScreen(model = homeModel(), onOpen = {}) }

        compose.onNode(hasText("未配置") and hasStateDescription("错误")).assertExists()
    }

    // the audit row says collapsed and never calls a loader
    @Test
    fun theAuditRowSaysCollapsedAndNeverCallsALoader() {
        var loaderCalls = 0
        compose.setContent {
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
        compose.setContent { AboutScreen(onOpenModelService = { opened = true }) }

        compose.onNodeWithText("EDITH ${BuildConfig.VERSION_NAME}", substring = true).assertExists()
        compose.onNodeWithText(if (BuildConfig.DEBUG) "调试版" else "发布版", substring = true).assertExists()
        compose.onNodeWithText("模型服务").performClick()

        assertTrue("the About page's model-service entry must open the page", opened)
    }
}
