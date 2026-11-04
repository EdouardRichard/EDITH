package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.actions.A11Y_CHANNEL
import com.sheen.rokid.phone.actions.ExecutionChannelStatus
import com.sheen.rokid.phone.actions.INTENT_CHANNEL
import com.sheen.rokid.phone.actions.ROOT_CHANNEL
import com.sheen.rokid.phone.actions.SHIZUKU_CHANNEL
import com.sheen.rokid.phone.model.ModelReadiness
import com.sheen.rokid.phone.ui.settings.LocalCapabilitiesScreen
import com.sheen.rokid.phone.ui.settings.LocalCapabilitiesUi
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsShellContent
import com.sheen.rokid.phone.ui.settings.localCapabilityReasons
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import com.sheen.rokid.plugin.Capability
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §3.6: the 本地能力 page is a **read-only** status page.
 *
 * It names the two local models (决策模型/语义嵌入) and the four execution channels (指令执行/无障碍/Shizuku/Root), each
 * with 可用 or 尚不可用 plus the reason from the one central dictionary (`capabilityReasonLabel`), and it offers no save,
 * switch or other write - the page exists to tell the wearer why something is down, not to change it.
 *
 * Fix round 1 (design §3.6): the model rows are fed by [localCapabilityReasons], the same adapter the shell applies to
 * the bundled-model gate (the capabilities are advertised unconditionally, so the raw map cannot report a failed
 * bundle); and before the first session, when the host has published no channel rows, the page still names all four
 * channels with 「尚未运行，开始会话后检测」 instead of omitting the section.
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity] (the K40 aborts the Compose rule's own background
 * Activity start; see `SettingsHomeTest`), content installed on the real foreground MainActivity.
 */
class LocalCapabilitiesTest {

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

    private fun homeModel() = SettingsHomeModel(
        modelSummary = "未配置",
        modelStatus = ModelStatus.UNCONFIGURED,
        assistantSummary = "唤醒词、持续对话、感知截图、主动辅助",
        runtimeSummary = "纯手机模式 · 悬浮球 82% · 语音朗读",
        permissionsSummary = "工具权限、系统授权、锁屏 PIN",
        pendingPermissionCount = 0,
        capabilitiesSummary = "本地模型、执行通道、能力状态",
        unavailableCapabilityCount = 2,
        auditSummary = "已收起 · 进入后加载",
        aboutSummary = "EDITH ${BuildConfig.VERSION_NAME} · 预览版",
    )

    private fun page(
        reasons: Map<Capability, String>,
        channels: List<ExecutionChannelStatus>,
    ) {
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                LocalCapabilitiesScreen(
                    capabilityReasons = reasons,
                    channels = channels,
                    help = rememberHelpState(),
                )
            }
        }
    }

    /** The state a first-start provisioning failure leaves behind; the adapter is what the shell applies. */
    private fun failedReadiness() =
        ModelReadiness().apply { markFailed("MODELS_PROVISIONING_FAILED") }.state.value

    /** The four channel rows the host publishes, with the real reason codes each channel reports. */
    private fun channels() = listOf(
        ExecutionChannelStatus(INTENT_CHANNEL, available = true, reason = null),
        ExecutionChannelStatus(A11Y_CHANNEL, available = false, reason = "A11Y_DISABLED"),
        ExecutionChannelStatus(SHIZUKU_CHANNEL, available = false, reason = "SHIZUKU_UNAVAILABLE"),
        ExecutionChannelStatus(
            ROOT_CHANNEL,
            available = false,
            reason = "ROOT_UNAVAILABLE",
            note = "Root：不可用（设备无 su）",
        ),
    )

    // every model and channel row names its state and its reason, and nothing on the page writes
    @Test
    fun localCapabilitiesListModelAndChannelReasonsWithoutASaveAction() {
        page(
            // The failed bundle is driven through the same adapter the shell uses; the page never receives a synthetic
            // model code production could not produce (fix round 1, design §3.6).
            reasons = localCapabilityReasons(
                capabilityReasons = mapOf(Capability.Actions to "ACTION_PROVIDER_MISSING"),
                readiness = failedReadiness(),
            ),
            channels = channels(),
        )

        compose.onNodeWithText(LocalCapabilitiesUi.MODELS_TITLE).assertExists()
        compose.onNodeWithText("决策模型").assertExists()
        compose.onNodeWithText("语义嵌入").assertExists()
        // Both models come from the one bundle, so both rows name the same gate refusal. The code is not in the
        // dictionary yet; the helper's documented rule is to keep it visible rather than invent a label.
        compose.onAllNodesWithText("尚不可用（MODELS_UNAVAILABLE）").assertCountEquals(2)

        compose.onNodeWithText(LocalCapabilitiesUi.CHANNELS_TITLE).assertExists()
        compose.onNodeWithText("指令执行（Intent）").assertExists()
        compose.onNodeWithText("无障碍通道").assertExists()
        compose.onNodeWithText("尚不可用（未启用无障碍）").assertExists()
        compose.onNodeWithText("Shizuku 通道").assertExists()
        compose.onNodeWithText("尚不可用（Shizuku 未运行或未授权）").assertExists()
        compose.onNodeWithText("Root").assertExists()
        compose.onNodeWithText("Root：不可用（设备无 su）").assertExists()

        compose.onNodeWithText(LocalCapabilitiesUi.OTHER_TITLE).assertExists()
        compose.onNodeWithText("指令执行").assertExists()
        compose.onNodeWithText("尚不可用（指令执行未装配）").assertExists()

        // 可用 appears only for the one available channel: the failed bundle turns both model rows unavailable.
        compose.onAllNodesWithText(LocalCapabilitiesUi.AVAILABLE).assertCountEquals(1)

        // Read-only: no save control and no switch/toggle anywhere on the page.
        compose.onAllNodesWithText("保存").assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    // before the first session the host has published no channel rows, so the page names the four as not-yet-probed
    @Test
    fun beforeTheFirstSessionEveryKnownChannelNamesTheUnknownReason() {
        page(reasons = emptyMap(), channels = emptyList())

        compose.onNodeWithText(LocalCapabilitiesUi.MODELS_TITLE).assertExists()
        compose.onNodeWithText(LocalCapabilitiesUi.CHANNELS_TITLE).assertExists()
        compose.onNodeWithText("指令执行（Intent）").assertExists()
        compose.onNodeWithText("无障碍通道").assertExists()
        compose.onNodeWithText("Shizuku 通道").assertExists()
        compose.onNodeWithText("Root").assertExists()
        // One honest unknown per row; the page must not hide the section and must not invent availability.
        compose.onAllNodesWithText(LocalCapabilitiesUi.CHANNEL_NOT_PROBED).assertCountEquals(4)

        compose.onAllNodesWithText("保存").assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    // the shell's 本地能力 route opens the real page instead of Task 7's placeholder
    @Test
    fun theLocalCapabilitiesRouteOpensTheReadOnlyPage() {
        setContent {
            SettingsShellContent(
                homeModel = homeModel(),
                localCapabilities = {
                    LocalCapabilitiesScreen(
                        capabilityReasons = localCapabilityReasons(emptyMap(), failedReadiness()),
                        channels = channels(),
                        help = rememberHelpState(),
                    )
                },
            )
        }

        compose.onNodeWithText("本地能力").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("后续任务接入").assertDoesNotExist()
        compose.onNodeWithText("决策模型").assertExists()
        compose.onNodeWithText("指令执行（Intent）").assertExists()
        compose.onAllNodesWithText("保存").assertCountEquals(0)
    }

    // an all-available device still renders both model rows and every channel as 可用
    @Test
    fun anAvailableDeviceShowsEveryRowAsAvailable() {
        page(
            reasons = emptyMap(),
            channels = listOf(
                ExecutionChannelStatus(INTENT_CHANNEL, available = true, reason = null),
                ExecutionChannelStatus(A11Y_CHANNEL, available = true, reason = null),
                ExecutionChannelStatus(SHIZUKU_CHANNEL, available = true, reason = null),
                ExecutionChannelStatus(ROOT_CHANNEL, available = true, reason = null),
            ),
        )

        compose.onAllNodesWithText(LocalCapabilitiesUi.OTHER_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText(LocalCapabilitiesUi.AVAILABLE).assertCountEquals(6)
    }
}
