package com.sheen.rokid.phone.ui.settings

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
 * 0.52 design §3.2: the settings home - seven entries in four groups, nothing else.
 *
 * The screen is a pure function of [model]; it holds no host dependency, so it is unit-testable as a Compose
 * composition. Only the whole row navigates; there are no switches, no inline forms and no long explanations.
 *
 * [auditLines] is the loader the audit page will use (Task 12). The home receives it only so the laziness contract is
 * pinned by a test: composing the home must not touch the audit log, because the home is not the audit page. The screen
 * therefore never invokes it.
 */
@Composable
internal fun SettingsHomeScreen(
    model: SettingsHomeModel,
    onOpen: (SettingsRoute) -> Unit,
    auditLines: () -> List<String> = { emptyList() },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text(
            "按用途分组，点进二级页修改；所有设置保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsGroup("模型") {
            SettingsRow(
                title = "模型服务",
                summary = model.modelSummary,
                onClick = { onOpen(SettingsRoute.ModelService) },
                summaryIsError = model.modelStatus != ModelStatus.READY,
            )
        }

        SettingsGroup("助手") {
            SettingsRow(
                title = "助手行为",
                summary = model.assistantSummary,
                onClick = { onOpen(SettingsRoute.AssistantBehavior) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = "运行与外观",
                summary = model.runtimeSummary,
                onClick = { onOpen(SettingsRoute.RuntimeAppearance) },
            )
        }

        SettingsGroup("安全与设备") {
            SettingsRow(
                title = "权限与安全",
                summary = model.permissionsSummary,
                onClick = { onOpen(SettingsRoute.PermissionsSecurity) },
                trailingStatus = model.pendingPermissionCount
                    .takeIf { it > 0 }
                    ?.let { "$it 项待授权" },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = "本地能力",
                summary = model.capabilitiesSummary,
                onClick = { onOpen(SettingsRoute.LocalCapabilities) },
                trailingStatus = model.unavailableCapabilityCount
                    .takeIf { it > 0 }
                    ?.let { "$it 项不可用" },
            )
        }

        SettingsGroup("高级") {
            SettingsRow(
                title = "审计记录",
                summary = model.auditSummary,
                onClick = { onOpen(SettingsRoute.Audit) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = "关于",
                summary = model.aboutSummary,
                onClick = { onOpen(SettingsRoute.About) },
            )
        }
    }
}
