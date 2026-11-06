package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm
import com.sheen.rokid.phone.ui.FirstRunModelUpgradeUi
import com.sheen.rokid.phone.ui.capabilityReasonLabel
import com.sheen.rokid.phone.ui.settings.AboutUi
import com.sheen.rokid.phone.ui.settings.AssistantBehaviorUi
import com.sheen.rokid.phone.ui.settings.AuditUi
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.HelpText
import com.sheen.rokid.phone.ui.settings.LocalCapabilitiesUi
import com.sheen.rokid.phone.ui.settings.ModelCatalogUi
import com.sheen.rokid.phone.ui.settings.ModelDetailUi
import com.sheen.rokid.phone.ui.settings.PermissionKind
import com.sheen.rokid.phone.ui.settings.PermissionsSecurityUi
import com.sheen.rokid.phone.ui.settings.RuntimeAppearanceUi
import com.sheen.rokid.phone.ui.settings.SettingsHomeUi
import com.sheen.rokid.phone.ui.settings.SettingsPageUi
import com.sheen.rokid.phone.ui.settings.SettingsRoute
import com.sheen.rokid.phone.ui.settings.SettingsSummaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 Task 15 / design §4.3: the settings surface's copy is one vocabulary.
 *
 * The pages render constants, so this JVM test is the cheap place to pin the whole sweep instead of the K40:
 *
 * - no user-visible settings string contains a retired term (「模型配置」 / 「档案」), except the whole approved
 *   first-run prompt (ruling R27: title and body stay verbatim);
 * - the retired model forms (模型档案 / 新建档案 / …, 声明输入/输出, 文本实测/实测) are gone;
 * - the replacement vocabulary is present exactly (模型服务 / 模型 ID / 支持的输入 / 输出 /
 *   上下文窗口（最大输入） / 文本测试 / 图片测试);
 * - About renders the build version, not a literal;
 * - the T15 copy corrections are settled: the immediate 不拦截 switch no longer says 「保存后」, the shared restart
 *   sentence names only the settings that are really read at launch, and the bundle-readiness codes are Chinese;
 * - the model detail page's remaining user copy lives in its page object, and audio stays off in 0.5.3.
 */
class SettingsCopyTest {

    private val helpEntries: List<HelpText> = listOf(
        HelpContent.WAKE_WORDS,
        HelpContent.CONTINUE_DIALOGUE,
        HelpContent.VISION,
        HelpContent.PROACTIVE,
        HelpContent.PROACTIVE_COOLDOWN,
        HelpContent.PROACTIVE_SENSITIVITY,
        HelpContent.PHONE_ONLY,
        HelpContent.BALL_OPACITY,
        HelpContent.BUBBLE_OPACITY,
        HelpContent.SPEECH,
        HelpContent.WHITELIST,
        HelpContent.MAX_LEVEL,
        HelpContent.NO_INTERCEPTION,
        HelpContent.LOCK_PIN,
        HelpContent.AUDIT,
        HelpContent.MODEL_CONTEXT_WINDOW,
        HelpContent.MODEL_OUTPUT,
        HelpContent.MODEL_INPUT_IMAGE,
        HelpContent.MODEL_THINKING,
        HelpContent.PROVIDER_PROTOCOL,
        HelpContent.PROVIDER_TIMEOUT,
        HelpContent.FETCH_MODELS,
    )

    /**
     * Every user-visible settings string that a page can put on screen.
     *
     * The list is deliberately made of the same objects the page composables consume: HelpContent's popovers, the
     * home's summaries and labels, every route title, the shared form copy, each page object's fixed strings, and the
     * one app-level first-run notice. A page that bypasses its object is the one drift this list cannot see, which is
     * why the one-time `git grep` sweep for both retired terms is a separate check (Task 15 brief) rather than a test.
     */
    private fun settingsCopy(): List<String> = buildList {
        helpEntries.forEach { text ->
            add(text.title)
            add(text.body)
            text.impact?.let { add(it) }
        }

        val home = SettingsSummaries.from(
            catalog = null,
            textVerified = false,
            detached = true,
            ballOpacityPercent = 82,
            speechEnabled = true,
            pendingPermissions = 0,
            unavailableCapabilities = 0,
            versionName = BuildConfig.VERSION_NAME,
        )
        addAll(
            listOf(
                SettingsSummaries.UNCONFIGURED,
                SettingsSummaries.MODELS_NOT_FETCHED,
                SettingsSummaries.NO_MODEL_SELECTED,
                SettingsSummaries.UNAVAILABLE,
                SettingsSummaries.IMAGE_UNVERIFIED,
                SettingsSummaries.ASSISTANT_SUMMARY,
                SettingsSummaries.PERMISSIONS_SUMMARY,
                SettingsSummaries.CAPABILITIES_SUMMARY,
                SettingsSummaries.AUDIT_SUMMARY,
                home.modelSummary,
                home.assistantSummary,
                home.runtimeSummary,
                home.permissionsSummary,
                home.capabilitiesSummary,
                home.auditSummary,
                home.aboutSummary,
                SettingsHomeUi.TITLE,
                SettingsHomeUi.INTRO,
                SettingsHomeUi.GROUP_MODELS,
                SettingsHomeUi.GROUP_ASSISTANT,
                SettingsHomeUi.GROUP_SECURITY,
                SettingsHomeUi.GROUP_ADVANCED,
                SettingsHomeUi.MODEL_SERVICE,
                SettingsHomeUi.ASSISTANT_BEHAVIOR,
                SettingsHomeUi.RUNTIME_APPEARANCE,
                SettingsHomeUi.PERMISSIONS_SECURITY,
                SettingsHomeUi.LOCAL_CAPABILITIES,
                SettingsHomeUi.AUDIT,
                SettingsHomeUi.ABOUT,
                SettingsHomeUi.pendingPermissions(2),
                SettingsHomeUi.unavailableCapabilities(3),
            ),
        )

        addAll(
            listOf(
                SettingsRoute.ModelService.title,
                SettingsRoute.Providers.title,
                SettingsRoute.AddProvider.title,
                SettingsRoute.ProviderEditor("p-1").title,
                SettingsRoute.ModelDetail("m-1").title,
                SettingsRoute.AssistantBehavior.title,
                SettingsRoute.RuntimeAppearance.title,
                SettingsRoute.PermissionsSecurity.title,
                SettingsRoute.LocalCapabilities.title,
                SettingsRoute.Audit.title,
                SettingsRoute.About.title,
                SettingsPageUi.RESTART_NOTE,
                SettingsPageUi.RESTART_CHIP,
                SettingsPageUi.DISCARD_TITLE,
                SettingsPageUi.DISCARD_BODY,
                SettingsPageUi.DISCARD_CONFIRM,
                SettingsPageUi.DISCARD_KEEP,
                SettingsPageUi.SAVE,
            ),
        )

        addAll(
            listOf(
                AboutUi.versionLine(BuildConfig.VERSION_NAME),
                AboutUi.buildTypeLine(debug = true),
                AboutUi.buildTypeLine(debug = false),
                AboutUi.MODEL_SERVICE,
                AboutUi.MODEL_SERVICE_DETAIL,
                AboutUi.OPEN_MODEL_SERVICE,
                AboutUi.PRIVACY_TITLE,
                AboutUi.PRIVACY_BODY,
            ),
        )

        addAll(
            listOf(
                AssistantBehaviorUi.RESTART_NOTE,
                AssistantBehaviorUi.RESTART_CHIP,
                AssistantBehaviorUi.SENSITIVE_BADGE,
                AssistantBehaviorUi.DISCARD_TITLE,
                AssistantBehaviorUi.DISCARD_CONFIRM,
                AssistantBehaviorUi.DISCARD_KEEP,
                AssistantBehaviorUi.SAVE,
                AssistantBehaviorUi.PROACTIVE_SAVED,
                AssistantBehaviorUi.COOLDOWN_SECONDS_ERROR,
                SmartSettingsForm.CONTINUE_DIALOGUE_PHONE_ONLY_NOTE,
                RuntimeAppearanceUi.MODE_OFF_NOTE,
                RuntimeAppearanceUi.RESET_OPACITY,
                RuntimeAppearanceUi.RUNNING,
                RuntimeAppearanceUi.NOT_RUNNING,
                RuntimeAppearanceUi.START_FAILURE_PREFIX,
                RuntimeAppearanceUi.PHONE_ONLY_SWITCH,
                RuntimeAppearanceUi.SPEECH_SWITCH,
                RuntimeAppearanceUi.BALL_SLIDER,
                RuntimeAppearanceUi.BUBBLE_SLIDER,
                RuntimeAppearanceUi.speechText(SettingsSaveResult(saved = true)),
                RuntimeAppearanceUi.opacityText(
                    saved = true,
                    error = null,
                    ballPercent = 82,
                    bubblePercent = 75,
                    detached = true,
                ),
                RuntimeAppearanceUi.opacityText(
                    saved = true,
                    error = null,
                    ballPercent = 82,
                    bubblePercent = 75,
                    detached = false,
                ),
                requireNotNull(RuntimeAppearanceUi.runningText(detached = true, running = true, startFailure = null)),
                requireNotNull(
                    RuntimeAppearanceUi.runningText(detached = true, running = false, startFailure = "启动失败"),
                ),
                requireNotNull(RuntimeAppearanceUi.runningText(detached = true, running = false, startFailure = null)),
                requireNotNull(
                    RuntimeAppearanceUi.autoOnSpeechText(
                        SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED),
                    ),
                ),
                DetachUi.SWITCH_TITLE,
                DetachUi.SWITCH_SUMMARY,
                DetachUi.PROACTIVE_DETACHED_NOTE,
                DetachUi.successText(stoppedSession = false),
                DetachUi.successText(stoppedSession = true),
                DetachUi.errorText("CUSTOM"),
                DetachUi.errorText("CUSTOM", stoppedSession = true),
            ),
        )

        addAll(
            listOf(
                PermissionsSecurityUi.GRANT_ACTION,
                PermissionsSecurityUi.PIN_PRIVACY,
                PermissionsSecurityUi.PIN_INVALID,
                PermissionsSecurityUi.PIN_SAVE_FAILED,
                PermissionsSecurityUi.PIN_SAVED,
                PermissionsSecurityUi.PIN_CLEARED,
                PermissionsSecurityUi.PIN_CONFIGURED,
                PermissionsSecurityUi.PIN_SAVE,
                PermissionsSecurityUi.PIN_UPDATE,
                PermissionsSecurityUi.PIN_CLEAR,
                PermissionsSecurityUi.NO_INTERCEPTION_SAVED,
                PermissionsSecurityUi.CONFIRM_ON,
                PermissionsSecurityUi.CONFIRM_CANCEL,
                PermissionsSecurityUi.TOOL_PERMISSIONS_TITLE,
                PermissionsSecurityUi.PIN_TITLE,
                PermissionsSecurityUi.GRANTS_TITLE,
                PermissionsSecurityUi.grantedStatusText(PermissionKind.USAGE_ACCESS),
                PermissionsSecurityUi.grantedStatusText(PermissionKind.NOTIFICATION_ACCESS),
                PermissionsSecurityUi.missingStatusText(PermissionKind.USAGE_ACCESS),
                PermissionsSecurityUi.missingStatusText(PermissionKind.NOTIFICATION_ACCESS),
            ),
        )
        PermissionsSecurityUi.permissionStates(
            foregroundGranted = false,
            notificationsConnected = false,
            calendarGranted = false,
            overlayGranted = false,
        ).forEach { state ->
            add(state.label)
            add(state.missingText)
            add(state.actionLabel)
        }

        addAll(
            listOf(
                LocalCapabilitiesUi.MODELS_TITLE,
                LocalCapabilitiesUi.CHANNELS_TITLE,
                LocalCapabilitiesUi.OTHER_TITLE,
                LocalCapabilitiesUi.AVAILABLE,
                LocalCapabilitiesUi.UNAVAILABLE,
                LocalCapabilitiesUi.CHANNEL_NOT_PROBED,
                LocalCapabilitiesUi.statusText(available = false, reason = "A11Y_DISABLED"),
                LocalCapabilitiesUi.statusText(available = false, reason = null),
            ),
        )
        LocalCapabilitiesUi.channelLines(emptyList()).forEach { line ->
            add(line.label)
            add(line.status)
        }

        addAll(listOf(AuditUi.EMPTY, AuditUi.CLEAR))

        addAll(
            listOf(
                ModelCatalogUi.UNAVAILABLE,
                ModelCatalogUi.UNAVAILABLE_DETAIL,
                ModelCatalogUi.EMPTY_TITLE,
                ModelCatalogUi.EMPTY_DETAIL,
                ModelCatalogUi.ADD_PROVIDER,
                ModelCatalogUi.NO_MODELS_TITLE,
                ModelCatalogUi.NO_MODELS_DETAIL,
                ModelCatalogUi.FETCH_MODELS,
                ModelCatalogUi.FETCHING,
                ModelCatalogUi.ADD_MODEL,
                ModelCatalogUi.NO_MODEL_SELECTED,
                ModelCatalogUi.SWITCH_PROVIDER,
                ModelCatalogUi.ACTIVE_CHIP,
                ModelCatalogUi.KEY_UNKNOWN,
                ModelCatalogUi.PROVIDER_LIST_TITLE,
                ModelCatalogUi.PROVIDER_LIST_DETAIL,
                ModelCatalogUi.EDIT,
                ModelCatalogUi.DELETE,
                ModelCatalogUi.SAVE_AND_FETCH,
                ModelCatalogUi.NAME,
                ModelCatalogUi.BASE_URL,
                ModelCatalogUi.API_KEY,
                ModelCatalogUi.ADVANCED,
                ModelCatalogUi.PROTOCOL,
                ModelCatalogUi.TIMEOUT,
                ModelCatalogUi.RESPONSES,
                ModelCatalogUi.CHAT_COMPLETIONS,
                ModelCatalogUi.KEY_KEEP,
                ModelCatalogUi.RESET_TITLE,
                ModelCatalogUi.RESET_DETAIL,
                ModelCatalogUi.RESET,
                ModelCatalogUi.RESET_CONFIRM,
                ModelCatalogUi.RESET_CANCEL,
                ModelCatalogUi.SELECT_NEEDS_TEST,
                ModelCatalogUi.TEXT_ONLY_DISABLED,
                ModelCatalogUi.MISSING_SELECTED,
                ModelCatalogUi.MISSING_GATEWAY,
                ModelCatalogUi.PROVIDER_MISSING,
                ModelCatalogUi.MODEL_MISSING,
                ModelCatalogUi.ADD_PROVIDER_FIRST,
                ModelCatalogUi.KEY_REQUIRED,
                ModelCatalogUi.MISSING_SECRET,
                ModelCatalogUi.NEW_ACTIVE_REQUIRED,
                ModelCatalogUi.MODEL_ID_IMMUTABLE,
                ModelCatalogUi.TEST_UNAVAILABLE,
                ModelCatalogUi.TESTING,
                ModelCatalogUi.TEST_PASSED,
                ModelCatalogUi.IMAGE_INPUT_MISSING,
                ModelCatalogUi.RESET_DONE,
                ModelCatalogUi.NO_SECRET,
                ModelCatalogUi.SWITCH_CONFIRM,
                ModelCatalogUi.SWITCH_CONFIRM_TITLE,
                ModelCatalogUi.SWITCH_CONFIRM_ACTION,
                ModelCatalogUi.DELETE_PROVIDER_TITLE,
                ModelCatalogUi.DELETE_PROVIDER_ACTION,
                ModelCatalogUi.DELETE_MODEL_TITLE,
                ModelCatalogUi.CHOOSE_NEW_ACTIVE,
                ModelCatalogUi.DISMISS,
                ModelCatalogUi.COPY,
                ModelCatalogUi.COPIED,
                ModelCatalogUi.FULL_VALUE_TITLE,
                ModelCatalogUi.SYNC_NEVER,
                ModelCatalogUi.SUMMARY_DISMISS,
                ModelCatalogUi.WARNING_CHIP,
                ModelCatalogUi.IMAGE_UNVERIFIED,
                ModelCatalogUi.VERIFIED_CHIP,
                ModelCatalogUi.MANUAL_CHIP,
                ModelCatalogUi.THINKING_CHIP,
                ModelCatalogUi.MISSING_CHIP,
                ModelCatalogUi.MODEL_COUNT,
                ModelCatalogUi.LAST_SYNC,
                ModelCatalogUi.FOOTER,
                ModelCatalogUi.TRUNCATED,
            ),
        )

        addAll(
            listOf(
                ModelDetailUi.MODEL_ID,
                ModelDetailUi.DISPLAY_NAME,
                ModelDetailUi.WINDOW,
                ModelDetailUi.OUTPUT,
                ModelDetailUi.PRESET,
                ModelDetailUi.WINDOW_ERROR,
                ModelDetailUi.OUTPUT_ERROR,
                ModelDetailUi.DISPLAY_NAME_INPUT,
                ModelDetailUi.WINDOW_INPUT,
                ModelDetailUi.OUTPUT_INPUT,
                ModelDetailUi.INPUTS,
                ModelDetailUi.INPUT_TEXT,
                ModelDetailUi.IMAGE_SWITCH,
                ModelDetailUi.AUDIO_SWITCH,
                ModelDetailUi.AUDIO_DISABLED,
                ModelDetailUi.K_TOKENS,
                ModelDetailUi.IMAGE,
                ModelDetailUi.AUDIO,
                ModelDetailUi.THINKING,
                ModelDetailUi.THINKING_SWITCH,
                ModelDetailUi.THINKING_EFFORT,
                ModelDetailUi.EFFORT_LOW,
                ModelDetailUi.EFFORT_HIGH,
                ModelDetailUi.EFFORT_MAX,
                ModelDetailUi.THINKING_ONLY_DEEPSEEK,
                ModelDetailUi.THINKING_DEFAULT_BUDGET_HINT,
                ModelDetailUi.THINKING_WINDOW_HINT,
                ModelDetailUi.TEST_TITLE,
                ModelDetailUi.TEST_TEXT,
                ModelDetailUi.TEST_IMAGE,
                ModelDetailUi.TEST_CONFIRM_TITLE,
                ModelDetailUi.TEST_CONFIRM_TEXT,
                ModelDetailUi.TEST_CONFIRM_DETAIL,
                ModelDetailUi.TEST_CONFIRM_ACTION,
                ModelDetailUi.TEST_UNAVAILABLE_REASON,
                ModelDetailUi.TEST_RESULT_PREFIX,
                ModelDetailUi.RESTORE,
                ModelDetailUi.RESTORE_TITLE,
                ModelDetailUi.RESTORE_DETAIL,
                ModelDetailUi.RESTORE_ACTION,
                ModelDetailUi.DELETE_MODEL,
                ModelDetailUi.DELETE_MODEL_DETAIL,
                ModelDetailUi.ORIGIN_GATEWAY,
                ModelDetailUi.ORIGIN_MANUAL,
                ModelDetailUi.fullModelIdDescription("m-1"),
                ModelDetailUi.footer("DeepSeek"),
                ModelDetailUi.deleteModelText("Model m-1"),
            ),
        )

        addAll(
            listOf(
                NoInterceptionUi.SWITCH_TITLE,
                NoInterceptionUi.SWITCH_SUMMARY,
                NoInterceptionUi.ENABLED_WARNING,
                NoInterceptionUi.CONFIRM_TITLE,
            ),
        )
        addAll(NoInterceptionUi.CONFIRM_LINES)

        addAll(
            listOf(
                SettingsFeedback.savedRestartText(0),
                SettingsFeedback.savedRestartText(2),
                requireNotNull(
                    SettingsFeedback.saveFailedText(
                        SettingsSaveResult(false, SettingsSaveResult.ERROR_STORE_UNAVAILABLE),
                    ),
                ),
                requireNotNull(
                    SettingsFeedback.saveFailedText(
                        SettingsSaveResult(false, SettingsSaveResult.ERROR_SETTINGS_UNREADABLE),
                    ),
                ),
                requireNotNull(
                    SettingsFeedback.saveFailedText(SettingsSaveResult(false, SettingsSaveResult.ERROR_SAVE_FAILED)),
                ),
                requireNotNull(SettingsFeedback.saveFailedText(SettingsSaveResult(saved = false))),
                requireNotNull(SettingsFeedback.saveFailedText(SettingsSaveResult(false, "CUSTOM"))),
            ),
        )

        addAll(
            listOf(
                FirstRunModelUpgradeUi.TITLE,
                FirstRunModelUpgradeUi.BODY,
                FirstRunModelUpgradeUi.CONFIRM,
                FirstRunModelUpgradeUi.LATER,
            ),
        )
    }

    @Test
    fun `the retired terms appear only in the approved first run prompt`() {
        val copy = settingsCopy()

        // R27 exempts the whole first-run prompt, so both of its strings are in the audited surface and are the only
        // per-string hits. Any other user-visible string containing 「模型配置」/「档案」 fails the equality below.
        assertTrue("the first-run title is part of the audited surface", copy.contains(R27_FIRST_RUN_TITLE))
        assertTrue("the first-run body is part of the audited surface", copy.contains(R27_FIRST_RUN_BODY))

        val hits = copy.filter { text -> RETIRED_TERMS.any(text::contains) }

        assertEquals(
            "the only user-visible retired terms are the whole approved first-run prompt (ruling R27)",
            listOf(R27_FIRST_RUN_TITLE, R27_FIRST_RUN_BODY),
            hits,
        )
        assertEquals("R27 pins the approved title verbatim", R27_FIRST_RUN_TITLE, FirstRunModelUpgradeUi.TITLE)
        assertEquals("R27 pins the approved body verbatim", R27_FIRST_RUN_BODY, FirstRunModelUpgradeUi.BODY)
    }

    @Test
    fun `the retired model terminology is absent from every settings string`() {
        val retired = listOf(
            "模型档案",
            "新建档案",
            "编辑档案",
            "档案服务不可用",
            "声明输入",
            "声明输出",
            // The design retires the 实测 vocabulary as a whole (文本实测/图片实测 → 文本测试/图片测试), so catch
            // variants such as 「图片未实测」 that the two exact forms above would miss.
            "实测",
        )
        val offenders = settingsCopy().filter { text -> retired.any(text::contains) }

        assertTrue("retired model terminology must not appear, was: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the terminology replacements are exact`() {
        val copy = settingsCopy()

        listOf(
            "模型服务",
            "模型 ID",
            "支持的输入",
            "输出",
            "上下文窗口（最大输入）",
            "文本测试",
            "图片测试",
        ).forEach { replacement ->
            assertTrue("visible settings copy must contain 「$replacement」", copy.any { it.contains(replacement) })
        }

        assertEquals("模型服务", SettingsRoute.ModelService.title)
        assertEquals("模型 ID", ModelDetailUi.MODEL_ID)
        assertEquals("支持的输入", ModelDetailUi.INPUTS)
        assertEquals("上下文窗口（最大输入）", ModelDetailUi.WINDOW)
        assertEquals("文本测试", ModelDetailUi.TEST_TEXT)
        assertEquals("图片测试", ModelDetailUi.TEST_IMAGE)
        assertTrue("the model detail page names the output side", ModelDetailUi.OUTPUT.contains("输出"))
    }

    @Test
    fun `the version in about is the build version`() {
        assertEquals("0.5.4", BuildConfig.VERSION_NAME)
        assertEquals(8, BuildConfig.VERSION_CODE)
        assertEquals("EDITH 0.5.4 · 预览版", AboutUi.versionLine(BuildConfig.VERSION_NAME))
        // A literal in AboutUi would ignore the argument; the 9.9.9 case is what proves the build version is read.
        assertEquals("EDITH 9.9.9 · 预览版", AboutUi.versionLine("9.9.9"))

        val home = SettingsSummaries.from(
            catalog = null,
            textVerified = false,
            detached = true,
            ballOpacityPercent = 82,
            speechEnabled = true,
            pendingPermissions = 0,
            unavailableCapabilities = 0,
            versionName = BuildConfig.VERSION_NAME,
        )
        assertEquals("the home's About summary is the same build version line", AboutUi.versionLine(BuildConfig.VERSION_NAME), home.aboutSummary)
    }

    @Test
    fun `the no interception copy describes the immediate switch`() {
        assertEquals(
            "当前已选择开启：开启后会立即更新宿主的「不拦截」门，并在关闭 App、被杀或重启后保持。",
            NoInterceptionUi.ENABLED_WARNING,
        )
        assertFalse("the switch is immediate now, not save-time", NoInterceptionUi.ENABLED_WARNING.contains("保存后"))
        assertEquals(
            "影响：开启后立即生效；关闭 App、被杀或重启后仍保持",
            HelpContent.NO_INTERCEPTION.impact,
        )
    }

    @Test
    fun `the restart sentence names only the settings that are read at launch`() {
        val sentence = HelpContent.WAKE_WORDS.body

        listOf("唤醒词", "持续对话", "权限上限", "白名单", "主动辅助冷却", "敏感度").forEach { field ->
            assertTrue("RESTART_SEMANTICS must name the launch-read field 「$field」", sentence.contains(field))
        }
        assertFalse(
            "the proactive switch is immediate, so it must not be listed as launch-read",
            sentence.contains("白名单、主动辅助等设置"),
        )
    }

    @Test
    fun `bundle readiness codes are mapped to user facing chinese reasons`() {
        assertEquals("模型准备中", capabilityReasonLabel("MODELS_PREPARING"))
        assertEquals("模型不可用", capabilityReasonLabel("MODELS_UNAVAILABLE"))
        assertEquals("模型服务不可用", capabilityReasonLabel("MODEL_OPERATION_UNAVAILABLE"))
    }

    @Test
    fun `model detail user copy lives in the page object and audio stays off`() {
        assertFalse("0.5.3 has no audio input path; the switch must stay off", ModelDetailUi.AUDIO_ENABLED)
        assertEquals("未接入（后续版本评估开放）", ModelDetailUi.AUDIO_DISABLED)
        assertFalse(
            "the capped reserve means a small legal window still admits input; copy must not promise rejection",
            ModelDetailUi.THINKING_WINDOW_HINT.contains("会被拒绝"),
        )
        assertEquals("K tokens", ModelDetailUi.K_TOKENS)
        assertEquals("图片", ModelDetailUi.IMAGE)
        assertEquals("音频", ModelDetailUi.AUDIO)
        assertEquals("编辑只影响该模型；保存后立即生效。提供商 DeepSeek", ModelDetailUi.footer("DeepSeek"))
        assertEquals("查看完整模型 ID m-1", ModelDetailUi.fullModelIdDescription("m-1"))
        assertEquals("「Model m-1」将从目录中删除，无法恢复。", ModelDetailUi.deleteModelText("Model m-1"))
    }

    @Test
    fun `about uses the model service vocabulary`() {
        assertTrue("About's privacy statement keeps the product term", AboutUi.PRIVACY_BODY.contains("模型服务"))
        assertFalse("the retired 模型配置 wording must not survive in About", AboutUi.PRIVACY_BODY.contains("模型配置"))
    }

    private companion object {
        /** Ruling R27: the first-run prompt is the verbatim legacy-upgrade notice for both retired terms. */
        const val R27_FIRST_RUN_TITLE = "模型配置已升级"
        const val R27_FIRST_RUN_BODY =
            "模型配置方式已升级，需要重新填写服务提供商与 API Key；旧档案不会被读取。"

        /** R27: the whole first-run prompt is exempt; everywhere else 「模型配置」 and 「档案」 are retired. */
        val RETIRED_TERMS = listOf("模型配置", "档案")
    }
}
