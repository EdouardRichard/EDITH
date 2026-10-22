package com.sheen.rokid.phone.ui
import com.sheen.rokid.phone.bridge.CxrHostEnvironment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.session.SessionService
import com.sheen.rokid.phone.session.SessionUiState
import com.sheen.rokid.phone.bridge.cxrAuthorizationLabel
import com.sheen.rokid.phone.bridge.cxrStatusLabel
import com.sheen.rokid.phone.app.sessionLabel

/**
 * The left-hand menu (spec §6.7 左上角菜单页 + C10).
 *
 * Three blocks, and all three are here for the same reason — they are about the *session*, not about any one plugin:
 *
 * 1. **会话管理** — start/resume/pause/stop/emergency-stop/help, moved from the old 会话 tab with their behaviour
 *    unchanged. Together with the CXR link's own facts (spec §6.7 「CXR 数据管道会话的创建/切换/断开」, C10), which
 *    used to sit at the bottom of that tab.
 * 2. **选择插件** — the spec's reserved entry, showing the one plugin that is actually delivered plus whatever the
 *    debug picker offers.
 * 3. **诊断** — the old diagnostics page, kept rather than dropped: the owner's ruling was to put it in this menu.
 *
 * Nothing here is smart-assistant-specific, which is what makes the rest of the page free to be exactly that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneMenuDrawer(
    state: SessionUiState,
    root: HostCompositionRoot,
    plugins: List<Pair<String, String>>,
    selected: String?,
    start: (String) -> Unit,
    command: (String) -> Unit,
    onSelectPlugin: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("会话管理", style = MaterialTheme.typography.titleLarge)
        Text(sessionLabel(state.session), style = MaterialTheme.typography.bodyMedium)
        var helpResult by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val inactive = state.session == SessionState.Idle || state.session == SessionState.Paused
            // Equal thirds, because the drawer is narrow: content-sized buttons squeezed the last one until its label
            // was clipped to "停" (measured on the K40). A fixed share of the row is what makes all three readable.
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (state.session == SessionState.Paused) command(SessionService.RESUME)
                    else selected?.let(start)
                },
                enabled = inactive && selected != null,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (state.session == SessionState.Paused) "继续" else "开始", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { command(SessionService.PAUSE) },
                enabled = !inactive,
            ) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("暂停", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    // P3.3 急停: a question that is still up is withdrawn and the turn in flight is cancelled, then the
                    // session is stopped the way the service owns it.
                    root.emergencyStop()
                    command(SessionService.STOP)
                },
                enabled = state.session != SessionState.Idle,
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("停止", maxLines = 1, softWrap = false)
            }
        }
        OutlinedButton(onClick = {
            helpResult = if (root.requestHelp()) "已请求帮助：将回应本会话最近一次最终转写"
            else "当前没有可响应的会话（需已开始并处于就绪/监听中）"
        }) {
            Icon(Icons.Default.HelpOutline, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("手动求助")
        }
        helpResult?.let { Text(it) }

        // C10: the CXR link's own facts, moved here unchanged. Readiness is the **transport's** signal, never
        // `Capability.Audio` - `CxrHostEnvironment` deliberately never advertises that, so the branch was dead.
        Text(cxrStatusLabel(state.cxrTransportMissing, state.cxrLinkReady))
        state.cxrAuthorization?.let { Text(cxrAuthorizationLabel(it)) }

        HorizontalDivider()
        Text("选择插件", style = MaterialTheme.typography.titleLarge)
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = plugins.firstOrNull { it.first == selected }?.second ?: (selected ?: ""),
                onValueChange = {},
                readOnly = true,
                label = { Text("插件") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                plugins.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onSelectPlugin(id)
                        expanded = false
                    })
                }
            }
        }
        Text(
            "本版只交付智能助手；其余条目是调试入口，不随发布构建出现。",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("诊断", style = MaterialTheme.typography.titleLarge)
        if (!BuildConfig.DEBUG) {
            Text("诊断信息只在调试构建里显示。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("会话：${sessionLabel(state.session)}")
            val marks = root.trace.snapshot()
            Text("事件数：${marks.size}")
            marks.takeLast(20).forEach { Text("${it.mark.name} · ${it.safeCode ?: ""}", style = MaterialTheme.typography.bodySmall) }
            // Debug-only acceptance affordance (kept from the old page): the only way a wearer can check what the
            // glasses actually show while the shipped plugins are shells.
            root.diagnosticHintSender?.let { sender ->
                var sent by remember { mutableStateOf<String?>(null) }
                OutlinedButton(onClick = {
                    sent = if (selected == "p2-hint" && sender()) "已提交一条测试提示，请看眼镜"
                    else "未能提交：请先选择「提示探针（调试）」并开始，且眼镜显示已就绪"
                }) { Text("发送测试提示（眼镜）") }
                sent?.let { Text(it) }
            }
        }
        Spacer(Modifier.width(1.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("收起菜单") }
    }
}
