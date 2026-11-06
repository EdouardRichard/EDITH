package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.ui.settings.ModelStatus
import com.sheen.rokid.phone.ui.settings.SettingsSummaries
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.52 design §3.2/§5.10: the settings home's summaries are a pure projection of the catalog and the live switches.
 *
 * The status vocabulary is the plan's binding one: 未配置 (no provider), 未获取模型 (provider, no models), 请选择模型
 * (models, no selection), 不可用 (selected model missing), 已连通 (a current-revision verified TEXT_REQUEST). An image
 * declaration whose current-revision IMAGE_REQUEST is not Verified appends 图片未验证.
 */
class SettingsSummariesTest {

    private fun entry(
        id: String,
        image: Boolean = false,
        missing: Boolean = false,
        verifications: List<CapabilityVerification> = emptyList(),
    ) = ModelEntry(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = if (image) {
            setOf(InputModality.TEXT, InputModality.IMAGE)
        } else {
            setOf(InputModality.TEXT)
        },
        outputs = setOf(OutputModality.TEXT),
        origin = ModelOrigin.MANUAL,
        missing = missing,
        verifications = verifications,
    )

    private fun provider(
        id: String = "p-1",
        name: String = "DeepSeek",
        models: List<ModelEntry> = listOf(entry("m-1")),
    ) = Provider(
        id = id,
        name = name,
        baseUrl = "https://api.deepseek.com/v1",
        models = models,
    )

    private fun catalog(provider: Provider?, selectedModelId: String? = null) = ModelCatalog(
        activeProviderId = provider?.id,
        selectedModelId = selectedModelId,
        providers = listOfNotNull(provider),
    )

    private fun home(
        catalog: ModelCatalog?,
        textVerified: Boolean = false,
        detached: Boolean = true,
        ballOpacityPercent: Int = 70,
        speechEnabled: Boolean = true,
        pendingPermissions: Int = 0,
        unavailableCapabilities: Int = 0,
        versionName: String = "0.5.2",
    ) = SettingsSummaries.from(
        catalog = catalog,
        textVerified = textVerified,
        detached = detached,
        ballOpacityPercent = ballOpacityPercent,
        speechEnabled = speechEnabled,
        pendingPermissions = pendingPermissions,
        unavailableCapabilities = unavailableCapabilities,
        versionName = versionName,
    )

    @Test
    fun `no provider is unconfigured and uses the error text`() {
        val home = home(catalog = null)

        assertEquals("未配置", home.modelSummary)
        assertEquals(ModelStatus.UNCONFIGURED, home.modelStatus)
    }

    @Test
    fun `a provider without models says models were not fetched`() {
        val home = home(catalog = catalog(provider(models = emptyList())))

        assertEquals("未获取模型", home.modelSummary)
        assertEquals(ModelStatus.UNAVAILABLE, home.modelStatus)
    }

    @Test
    fun `models without a selection ask the user to pick one`() {
        val home = home(catalog = catalog(provider(), selectedModelId = null))

        assertEquals("请选择模型", home.modelSummary)
        assertEquals(ModelStatus.UNAVAILABLE, home.modelStatus)
    }

    @Test
    fun `a verified selection shows provider and model`() {
        val home = home(catalog = catalog(provider(), selectedModelId = "m-1"), textVerified = true)

        assertEquals("DeepSeek · Model m-1", home.modelSummary)
        assertEquals(ModelStatus.READY, home.modelStatus)
    }

    @Test
    fun `a selection missing from the gateway is unavailable`() {
        val home = home(
            catalog = catalog(provider(models = listOf(entry("m-1", missing = true))), selectedModelId = "m-1"),
            textVerified = true,
        )

        assertEquals("不可用", home.modelSummary)
        assertEquals(ModelStatus.UNAVAILABLE, home.modelStatus)
    }

    @Test
    fun `an image unverified selection appends the warning`() {
        val unverified = entry("m-1", image = true)
        val unverifiedHome = home(
            catalog = catalog(provider(models = listOf(unverified)), selectedModelId = "m-1"),
            textVerified = true,
        )
        assertEquals("DeepSeek · Model m-1 · 图片未验证", unverifiedHome.modelSummary)
        assertEquals(ModelStatus.READY, unverifiedHome.modelStatus)

        // A current-revision Verified IMAGE_REQUEST is the only evidence that removes the warning.
        val verified = unverified.copy(
            verifications = listOf(
                CapabilityVerification(1, "IMAGE_REQUEST", VerificationState.Verified, 1_700_000_000_000),
            ),
        )
        val verifiedHome = home(
            catalog = catalog(provider(models = listOf(verified)), selectedModelId = "m-1"),
            textVerified = true,
        )
        assertEquals("DeepSeek · Model m-1", verifiedHome.modelSummary)
    }

    @Test
    fun `permission and capability counts are carried into the home model`() {
        val home = home(
            catalog = catalog(provider(), selectedModelId = "m-1"),
            textVerified = true,
            pendingPermissions = 2,
            unavailableCapabilities = 3,
        )

        assertEquals(2, home.pendingPermissionCount)
        assertEquals(3, home.unavailableCapabilityCount)
        assertEquals("工具权限、系统授权、锁屏 PIN", home.permissionsSummary)
        assertEquals("本地模型、执行通道、能力状态", home.capabilitiesSummary)
    }

    @Test
    fun `the static summaries describe the pages they open`() {
        val home = home(
            catalog = catalog(provider(), selectedModelId = "m-1"),
            textVerified = true,
            versionName = "9.9.9",
        )

        assertEquals("唤醒词、持续对话、感知截图、主动辅助", home.assistantSummary)
        assertEquals("已收起 · 进入后加载", home.auditSummary)
        assertEquals("EDITH 9.9.9 · 预览版", home.aboutSummary)
    }

    @Test
    fun `the runtime summary reflects the mode opacity and speech switch`() {
        val detached = home(catalog = null, detached = true, ballOpacityPercent = 70, speechEnabled = true)
        assertEquals("纯手机模式 · 悬浮球 70% · 语音朗读", detached.runtimeSummary)

        val glasses = home(catalog = null, detached = false, ballOpacityPercent = 55, speechEnabled = false)
        assertEquals("眼镜模式 · 悬浮球 55% · 语音关闭", glasses.runtimeSummary)
    }
}
