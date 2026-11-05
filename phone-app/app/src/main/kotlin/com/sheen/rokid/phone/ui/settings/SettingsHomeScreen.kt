package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 0.52 design §3.2/§4.3: the home's fixed copy, kept beside the only screen that draws it.
 *
 * The two red status suffixes are functions because their count is data; everything else is a constant so Task 15's
 * copy test can scan the same strings the page renders.
 */
internal object SettingsHomeUi {
    const val TITLE = "设置"
    const val INTRO = "按用途分组，点进二级页修改；所有设置保存在本机。"

    const val GROUP_MODELS = "模型"
    const val GROUP_ASSISTANT = "助手"
    const val GROUP_SECURITY = "安全与设备"
    const val GROUP_ADVANCED = "高级"

    const val MODEL_SERVICE = "模型服务"
    const val ASSISTANT_BEHAVIOR = "助手行为"
    const val RUNTIME_APPEARANCE = "运行与外观"
    const val PERMISSIONS_SECURITY = "权限与安全"
    const val LOCAL_CAPABILITIES = "本地能力"
    const val AUDIT = "审计记录"
    const val ABOUT = "关于"

    /** The row's red count (「N 项待授权」). */
    fun pendingPermissions(count: Int): String = "$count 项待授权"

    /** The row's red count (「N 项不可用」). */
    fun unavailableCapabilities(count: Int): String = "$count 项不可用"
}

/**
 * 0.52 design §3.2: the settings home - seven entries in four groups, nothing else.
 *
 * The screen is a pure function of [model]; it holds no host dependency, so it is unit-testable as a Compose
 * composition. Only the whole row navigates; there are no switches, no inline forms and no long explanations.
 *
 * [auditLines] is the loader the audit page will use (Task 12). The home receives it only so the laziness contract is
 * pinned by a test: composing the home must not touch the audit log, because the home is not the audit page. The screen
 * therefore never invokes it.
 *
 * [scrollState] is a parameter rather than screen-owned state because the home must keep its position while a secondary
 * page is open (design §4.4): the shell holds the one [ScrollState] across page pushes, and the default keeps a
 * standalone composition self-contained. The tab switch still resets the home because the whole shell leaves composition.
 */
@Composable
internal fun SettingsHomeScreen(
    model: SettingsHomeModel,
    onOpen: (SettingsRoute) -> Unit,
    auditLines: () -> List<String> = { emptyList() },
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(SettingsHomeUi.TITLE, style = MaterialTheme.typography.headlineMedium)
        Text(
            SettingsHomeUi.INTRO,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsGroup(SettingsHomeUi.GROUP_MODELS) {
            SettingsRow(
                title = SettingsHomeUi.MODEL_SERVICE,
                summary = model.modelSummary,
                onClick = { onOpen(SettingsRoute.ModelService) },
                summaryIsError = model.modelStatus != ModelStatus.READY,
            )
        }

        SettingsGroup(SettingsHomeUi.GROUP_ASSISTANT) {
            SettingsRow(
                title = SettingsHomeUi.ASSISTANT_BEHAVIOR,
                summary = model.assistantSummary,
                onClick = { onOpen(SettingsRoute.AssistantBehavior) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = SettingsHomeUi.RUNTIME_APPEARANCE,
                summary = model.runtimeSummary,
                onClick = { onOpen(SettingsRoute.RuntimeAppearance) },
            )
        }

        SettingsGroup(SettingsHomeUi.GROUP_SECURITY) {
            SettingsRow(
                title = SettingsHomeUi.PERMISSIONS_SECURITY,
                summary = model.permissionsSummary,
                onClick = { onOpen(SettingsRoute.PermissionsSecurity) },
                trailingStatus = model.pendingPermissionCount
                    .takeIf { it > 0 }
                    ?.let(SettingsHomeUi::pendingPermissions),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = SettingsHomeUi.LOCAL_CAPABILITIES,
                summary = model.capabilitiesSummary,
                onClick = { onOpen(SettingsRoute.LocalCapabilities) },
                trailingStatus = model.unavailableCapabilityCount
                    .takeIf { it > 0 }
                    ?.let(SettingsHomeUi::unavailableCapabilities),
            )
        }

        SettingsGroup(SettingsHomeUi.GROUP_ADVANCED) {
            SettingsRow(
                title = SettingsHomeUi.AUDIT,
                summary = model.auditSummary,
                onClick = { onOpen(SettingsRoute.Audit) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = SettingsHomeUi.ABOUT,
                summary = model.aboutSummary,
                onClick = { onOpen(SettingsRoute.About) },
            )
        }
    }
}
