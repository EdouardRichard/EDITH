package com.sheen.rokid.phone.settings

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsHomeScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 T17 acceptance failure: 720x1280 / density 360 / fontScale 1.5 showed only two settings entries and wrapped the
 * app-bar title. The fixed-size body below is the compact settings viewport (720/2.25 = 320dp wide); the font scale is
 * injected instead of read from the host so the assertion does not depend on the K40's current system setting.
 *
 * The first screen must carry the first five entries (模型服务 / 助手行为 / 运行与外观 / 权限与安全 / 本地能力), keep the
 * red status counts visible, and stay inside 320dp horizontally. A control composition at fontScale 1.0 proves the
 * normal two-line summary layout was not removed.
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity], like `SettingsHomeTest` - the K40 aborts the Compose
 * rule's own background Activity start.
 */
class SettingsResponsiveTest {

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

    /** Compose content under the supplied font scale and a fixed compact-screen configuration. */
    private fun setContent(
        fontScale: Float,
        screenHeightDp: Int,
        content: @Composable () -> Unit,
    ) {
        foreground.onActivity { activity ->
            activity.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    this.screenWidthDp = 320
                    this.screenHeightDp = screenHeightDp
                }
                // 720x1280 at density 360 is 2.25x; the K40 test build may currently be at another density, so only
                // fontScale is a hard requirement here. The viewport size is expressed in dp and stays 320x424.
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(density = 2.25f, fontScale = fontScale),
                ) {
                    content()
                }
            }
        }
    }

    private fun homeModel(pendingPermissions: Int = 4, unavailableCapabilities: Int = 4) =
        SettingsHomeModel(
            modelSummary = "未配置",
            modelStatus = ModelStatus.UNCONFIGURED,
            assistantSummary = "唤醒词、持续对话、感知截图、主动辅助",
            runtimeSummary = "纯手机模式 · 悬浮球 82% · 语音朗读",
            permissionsSummary = "工具权限、系统授权、锁屏 PIN",
            pendingPermissionCount = pendingPermissions,
            capabilitiesSummary = "本地模型、执行通道、能力状态",
            unavailableCapabilityCount = unavailableCapabilities,
            auditSummary = "已收起 · 进入后加载",
            aboutSummary = "EDITH ${BuildConfig.VERSION_NAME} · 预览版",
        )

    private val firstFive = listOf(
        "模型服务",
        "助手行为",
        "运行与外观",
        "权限与安全",
        "本地能力",
    )

    // compact font scale: the first five entries must all be on the first screen
    @Test
    fun compactFontScaleShowsTheFirstFiveEntriesWithoutHorizontalOverflow() {
        setContent(fontScale = 1.5f, screenHeightDp = 424) {
            Box(Modifier.size(320.dp, 424.dp).testTag(COMPACT_BODY)) {
                SettingsHomeScreen(model = homeModel(), onOpen = {})
            }
        }

        val body = compose.onNodeWithTag(COMPACT_BODY).getUnclippedBoundsInRoot()
        firstFive.forEach { entry ->
            val node = compose.onNodeWithText(entry)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "$entry is wider than the compact body: bounds=$bounds body=$body",
                bounds.width <= body.width + 1.dp,
            )
            assertTrue(
                "$entry leaves the compact body: bounds=$bounds body=$body",
                bounds.left >= body.left - 1.dp &&
                    bounds.right <= body.right + 1.dp &&
                    bounds.top >= body.top - 1.dp &&
                    bounds.bottom <= body.bottom + 1.dp,
            )
        }

        // The red counts are status, not the hidden row summary: compact mode must keep them visible.
        compose.onNodeWithText("4 项待授权").assertIsDisplayed()
        compose.onNodeWithText("4 项不可用").assertIsDisplayed()
    }

    // control: at fontScale 1.0 the normal layout still shows the row summaries
    @Test
    fun normalFontScaleKeepsTheRowSummaries() {
        setContent(fontScale = 1.0f, screenHeightDp = 900) {
            Box(Modifier.size(320.dp, 424.dp).testTag(NORMAL_BODY)) {
                SettingsHomeScreen(model = homeModel(), onOpen = {})
            }
        }

        compose.onNodeWithText("未配置").assertIsDisplayed()
        compose.onNodeWithText("唤醒词、持续对话、感知截图、主动辅助").assertIsDisplayed()
        compose.onNodeWithText("纯手机模式 · 悬浮球 82% · 语音朗读").assertExists()
    }

    private companion object {
        const val COMPACT_BODY = "settings-compact-body"
        const val NORMAL_BODY = "settings-normal-body"
    }
}
