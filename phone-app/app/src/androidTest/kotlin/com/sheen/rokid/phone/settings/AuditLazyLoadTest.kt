package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.ui.settings.AuditUi
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsHomeModel
import com.sheen.rokid.phone.ui.settings.SettingsShellContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §3.7/G6: the audit trail is **only** read when its page is opened.
 *
 * The home receives the same loader the page uses, and the shell composes the page only for the pushed `Audit` route.
 * These cases drive the real shell: the home must leave the loader untouched (the same rule Task 7 pinned on
 * `SettingsHomeScreen` alone), pushing 审计记录 must read exactly once, the page must render at most the legacy 50 lines
 * newest-first with 「暂无记录」 when empty, and 「清空审计记录」 must call the writer once and re-read - with no second
 * confirmation, because clearing is an immediate action.
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity] (the K40 aborts the Compose rule's own background
 * Activity start; see `SettingsHomeTest`), content installed on the real foreground MainActivity.
 */
class AuditLazyLoadTest {

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
        unavailableCapabilityCount = 0,
        auditSummary = "已收起 · 进入后加载",
        aboutSummary = "EDITH ${BuildConfig.VERSION_NAME} · 预览版",
    )

    /**
     * Composes the real shell with a counting loader/writer pair. `stored` is the fake document; the writer clears it so
     * the page's re-read after a clear can be observed as a real state change rather than a mock call count.
     */
    private fun setShell(
        auditLines: () -> List<String>,
        auditClear: () -> Unit = {},
    ) {
        setContent {
            SettingsShellContent(
                homeModel = homeModel(),
                auditLines = auditLines,
                auditClear = auditClear,
            )
        }
    }

    // the home says collapsed and never reads; opening the page reads exactly once
    @Test
    fun theAuditPageReadsTheLogOnlyWhenItIsOpened() {
        var loaderCalls = 0
        val stored = listOf("11-04 12:00:00 | launch_app | L2 | allow | ok | user")
        setShell(auditLines = {
            loaderCalls++
            stored
        })

        compose.onNodeWithText("已收起 · 进入后加载").assertExists()
        compose.onNodeWithText(AuditUi.CLEAR).assertDoesNotExist()
        assertEquals("the settings home must not read the audit log", 0, loaderCalls)

        compose.onNodeWithText("审计记录").performClick()
        compose.waitForIdle()

        assertEquals("opening 审计记录 must read the log exactly once", 1, loaderCalls)
        // The dialog header is the page title; the home row behind it still carries the same word.
        compose.onAllNodesWithText("审计记录").assertCountEquals(2)
        compose.onNodeWithText(HelpContent.AUDIT.body).assertExists()
        compose.onNodeWithText(AuditUi.CLEAR).assertExists()
    }

    // newest-first snapshot, capped at the legacy page's 50 rows
    @Test
    fun theAuditPageRendersAtMostFiftyNewestLines() {
        val stored = (1..(AuditUi.PAGE_LIMIT + 10)).map { "audit-line-$it" }
        setShell(auditLines = { stored })

        compose.onNodeWithText("审计记录").performClick()
        compose.waitForIdle()

        compose.onAllNodes(hasText("audit-line-", substring = true))
            .assertCountEquals(AuditUi.PAGE_LIMIT)
        compose.onNodeWithText("audit-line-1").assertExists()
        compose.onNodeWithText("audit-line-${AuditUi.PAGE_LIMIT}").assertExists()
        compose.onNodeWithText("audit-line-${AuditUi.PAGE_LIMIT + 1}").assertDoesNotExist()
    }

    // an empty document says 暂无记录 and still offers the (idempotent) clear action
    @Test
    fun anEmptyAuditLogShowsTheEmptyText() {
        setShell(auditLines = { emptyList() })

        compose.onNodeWithText("审计记录").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(AuditUi.EMPTY).assertExists()
        compose.onNodeWithText(AuditUi.CLEAR).assertExists()
    }

    // clear is immediate: one writer call, no second confirmation, then a re-read of the cleared document
    @Test
    fun clearingCallsTheWriterOnceAndRereads() {
        var loaderCalls = 0
        var clearCalls = 0
        var stored = listOf("line-a", "line-b")
        setShell(
            auditLines = {
                loaderCalls++
                stored
            },
            auditClear = {
                clearCalls++
                stored = emptyList()
            },
        )

        compose.onNodeWithText("审计记录").performClick()
        compose.waitForIdle()
        assertEquals(1, loaderCalls)
        compose.onNodeWithText("line-a").assertExists()

        compose.onNodeWithText(AuditUi.CLEAR).performClick()
        compose.waitForIdle()

        assertEquals("clearing must call the writer exactly once, with no second confirmation", 1, clearCalls)
        assertEquals("the page must re-read the cleared document", 2, loaderCalls)
        compose.onNodeWithText("line-a").assertDoesNotExist()
        compose.onNodeWithText(AuditUi.EMPTY).assertExists()
        compose.onNodeWithText(AuditUi.CLEAR).assertExists()
    }
}
