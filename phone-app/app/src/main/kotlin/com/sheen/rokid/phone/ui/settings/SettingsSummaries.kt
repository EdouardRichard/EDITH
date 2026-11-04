package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.VerificationState

/**
 * 0.52 design §3.2 / §5.10: the home's summaries, as a pure JVM function.
 *
 * The model row is the only dynamic one. Its status vocabulary is the approved plan's binding one:
 *  - 未配置 - no active provider (error colour);
 *  - 未获取模型 - an active provider with no models;
 *  - 请选择模型 - models exist but nothing is selected;
 *  - 不可用 - the selected model is missing (or is absent from the gateway's latest list);
 *  - `${provider} · ${model}` - the selected model exists and carries current-revision TEXT_REQUEST evidence
 *    ("已连通"); without that evidence the same name is shown but the row stays unavailable.
 * An IMAGE declaration whose current-revision IMAGE_REQUEST is not Verified appends 「图片未验证」.
 *
 * The other summaries name what their page edits, plus the two counts the row shows in red. Text is deliberately short:
 * the home carries navigation and state, not explanations (0.52 G1).
 */
internal object SettingsSummaries {

    const val UNCONFIGURED = "未配置"
    const val MODELS_NOT_FETCHED = "未获取模型"
    const val NO_MODEL_SELECTED = "请选择模型"
    const val UNAVAILABLE = "不可用"
    const val IMAGE_UNVERIFIED = "图片未验证"

    const val ASSISTANT_SUMMARY = "唤醒词、持续对话、感知截图、主动辅助"
    const val PERMISSIONS_SUMMARY = "工具权限、系统授权、锁屏 PIN"
    const val CAPABILITIES_SUMMARY = "本地模型、执行通道、能力状态"
    const val AUDIT_SUMMARY = "已收起 · 进入后加载"

    /** The operation a text probe records; named here so the shell and the tests share one spelling. */
    const val TEXT_OPERATION = "TEXT_REQUEST"
    private const val IMAGE_REQUEST = "IMAGE_REQUEST"

    /**
     * Builds the home model from the catalog and the live switches.
     *
     * [textVerified] is the caller's current-revision TEXT_REQUEST verdict for the selected model. The image warning is
     * derived from the catalog itself (the model entry carries its evidence), so callers cannot forget it.
     */
    fun from(
        catalog: ModelCatalog?,
        textVerified: Boolean,
        detached: Boolean,
        ballOpacityPercent: Int,
        speechEnabled: Boolean,
        pendingPermissions: Int,
        unavailableCapabilities: Int,
        versionName: String,
    ): SettingsHomeModel {
        val (summary, status) = modelSummary(catalog, textVerified)
        return SettingsHomeModel(
            modelSummary = summary,
            modelStatus = status,
            assistantSummary = ASSISTANT_SUMMARY,
            runtimeSummary = runtimeSummary(detached, ballOpacityPercent, speechEnabled),
            permissionsSummary = PERMISSIONS_SUMMARY,
            pendingPermissionCount = pendingPermissions.coerceAtLeast(0),
            capabilitiesSummary = CAPABILITIES_SUMMARY,
            unavailableCapabilityCount = unavailableCapabilities.coerceAtLeast(0),
            auditSummary = AUDIT_SUMMARY,
            aboutSummary = "EDITH $versionName · 预览版",
        )
    }

    /** True when the newest verification for [operation] on [revision] is Verified (the `CapabilityGate` rule). */
    fun latestVerified(
        verifications: List<CapabilityVerification>,
        revision: Long,
        operation: String,
    ): Boolean {
        val latest = verifications.withIndex()
            .filter { it.value.profileRevision == revision && it.value.operation == operation }
            .maxWithOrNull(
                compareBy<IndexedValue<CapabilityVerification>> { it.value.testedAtEpochMillis }
                    .thenBy { it.index },
            )
        return latest?.value?.state == VerificationState.Verified
    }

    private fun modelSummary(catalog: ModelCatalog?, textVerified: Boolean): Pair<String, ModelStatus> {
        val provider = catalog?.activeProvider()
            ?: return UNCONFIGURED to ModelStatus.UNCONFIGURED
        if (provider.models.isEmpty()) return MODELS_NOT_FETCHED to ModelStatus.UNAVAILABLE
        val selectedId = catalog.selectedModelId ?: return NO_MODEL_SELECTED to ModelStatus.UNAVAILABLE
        val model = provider.models.firstOrNull { it.id == selectedId }
        if (model == null || model.missing) return UNAVAILABLE to ModelStatus.UNAVAILABLE

        val name = "${provider.name} · ${model.displayName}"
        val imageWarning = InputModality.IMAGE in model.inputs &&
            !latestVerified(model.verifications, provider.revision, IMAGE_REQUEST)
        val summary = if (imageWarning) "$name · $IMAGE_UNVERIFIED" else name
        return summary to if (textVerified) ModelStatus.READY else ModelStatus.UNAVAILABLE
    }

    private fun runtimeSummary(detached: Boolean, ballOpacityPercent: Int, speechEnabled: Boolean): String =
        buildString {
            append(if (detached) "纯手机模式" else "眼镜模式")
            append(" · 悬浮球 ").append(ballOpacityPercent).append('%')
            append(" · ").append(if (speechEnabled) "语音朗读" else "语音关闭")
        }
}
