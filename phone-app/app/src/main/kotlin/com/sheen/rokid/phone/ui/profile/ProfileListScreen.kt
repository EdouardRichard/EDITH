package com.sheen.rokid.phone.ui.profile
import com.sheen.rokid.phone.model.formatKTokens
import com.sheen.rokid.phone.model.profileFormErrorMessage
import com.sheen.rokid.phone.ui.profile.domain
import com.sheen.rokid.phone.ui.profile.ProfileUiState
import com.sheen.rokid.phone.ui.profile.ProfileViewModel

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sheen.rokid.plugin.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ProfileListScreen(viewModel: ProfileViewModel, state: ProfileUiState, plugins: List<Pair<String, String>>) {
    var editor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ModelProfile?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { Text(profileError(it), color = MaterialTheme.colorScheme.error) }
        if (editor) {
            ProfileEditorScreen(state.profiles.firstOrNull { it.id == editingId }, { draft, secret ->
                viewModel.save(draft, secret)
                val saved = viewModel.state.value.error == null
                if (saved) { editor = false; editingId = null }
                saved
            }, { editor = false; editingId = null })
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("模型档案", style = MaterialTheme.typography.titleLarge)
                TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text("新建档案") } }, state = rememberTooltipState()) {
                    IconButton(onClick = { editingId = null; editor = true }) { Icon(Icons.Default.Add, "新建档案") }
                }
            }
            if (state.profiles.isEmpty()) Text("暂无模型档案")
            state.profiles.forEach { profile ->
                HorizontalDivider()
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text("${profile.service} · ${profile.model}", style = MaterialTheme.typography.bodyMedium)
                // Secondary detail stays bodyMedium so the identity and primary actions also fit at the
                // smallest supported viewport (720x1280, density 360, fontScale 1.5).
                Text("声明输入：${profile.inputs.joinToString { it.name }}；输出：${profile.outputs.joinToString { it.name }}", style = MaterialTheme.typography.bodyMedium)
                Text(if (profile.protocolId == "DEEPSEEK_RESPONSES") "Responses API：TEXT → TEXT" else "协议未接入：${profile.protocolId}", style = MaterialTheme.typography.bodyMedium)
                Text("最大输入：${formatKTokens(profile.usableWindowTokens)} K tokens；最大输出：${formatKTokens(profile.maxOutputTokens)} K tokens", style = MaterialTheme.typography.bodyMedium)
                val evidence = profile.verifications.filter { it.profileRevision == profile.revision && it.operation == "TEXT_REQUEST" }
                    .withIndex().maxWithOrNull(compareBy<IndexedValue<CapabilityVerification>> { it.value.testedAtEpochMillis }.thenBy { it.index })?.value
                Text("文本实测：${verificationLabel(evidence?.state ?: VerificationState.Unverified)}", style = MaterialTheme.typography.bodyMedium)
                if (InputModality.IMAGE in profile.inputs) {
                    val imageEvidence = profile.verifications.filter { it.profileRevision == profile.revision && it.operation == "IMAGE_REQUEST" }
                        .withIndex().maxWithOrNull(compareBy<IndexedValue<CapabilityVerification>> { it.value.testedAtEpochMillis }.thenBy { it.index })?.value
                    Text("图片实测：${verificationLabel(imageEvidence?.state ?: VerificationState.Unverified)}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    // R74: a text-only profile cannot start the assistant session; the prompt says what to change.
                    Text("图片：未声明（本版要求 IMAGE+TEXT，会话无法启动；请更换支持图片的模型/档案）",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (InputModality.AUDIO in profile.inputs || OutputModality.AUDIO in profile.outputs) Text("语音：未接入 / 未验证", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolIcon("编辑 ${profile.name}", { editingId = profile.id; editor = true }) { Icon(Icons.Default.Edit, "编辑 ${profile.name}") }
                    ToolIcon("删除 ${profile.name}", { deleting = profile }) { Icon(Icons.Default.Delete, "删除 ${profile.name}") }
                    if (state.loadingId == profile.id) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        OutlinedButton(onClick = viewModel::cancelTest) { Icon(Icons.Default.Close, null); Text("取消测试") }
                    } else {
                        OutlinedButton(onClick = { viewModel.testText(profile.id) }) { Icon(Icons.Default.NetworkCheck, null); Spacer(Modifier.width(4.dp)); Text("文本连接测试") }
                        if (InputModality.IMAGE in profile.inputs) {
                            OutlinedButton(onClick = { viewModel.testImage(profile.id) }) { Icon(Icons.Default.Star, null); Spacer(Modifier.width(4.dp)); Text("图片连接测试") }
                        }
                    }
                }
            }
            HorizontalDivider()
            plugins.forEach { (id, name) ->
                var expanded by remember(id) { mutableStateOf(false) }
                val selected = state.profiles.firstOrNull { it.id == state.selections[id] }
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(selected?.name ?: "未选择", {}, readOnly = true, label = { Text("$name 档案") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded, { expanded = false }) { state.profiles.forEach { profile ->
                        DropdownMenuItem(text = { Text(profile.name) }, onClick = { viewModel.select(id, profile.id); expanded = false })
                    } }
                }
            }
        }
    }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${profile.name}？") },
        text = { Text("该档案的凭证与验证结果将被删除。") }, confirmButton = { TextButton(onClick = { viewModel.delete(profile.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    state.confirmation?.let { consent -> AlertDialog(onDismissRequest = viewModel::cancelTest,
        title = { Text(if (consent.image) "确认图片上传" else "确认文本上传") },
        text = { Column { Text("目标域：${consent.domain}")
            Text(if (consent.image) "内容类别：固定合成测试图片" else "内容类别：固定合成测试文本")
            Text(if (consent.image) "图片检验：模型描述合成图片" else "只回复测试通过") } },
        confirmButton = { TextButton(onClick = viewModel::confirmTest) { Text("同意并测试") } },
        dismissButton = { TextButton(onClick = viewModel::cancelTest) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ToolIcon(label: String, action: () -> Unit, content: @Composable () -> Unit) {
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(action, content = content)
    }
}
private fun verificationLabel(state: VerificationState) = when (state) { VerificationState.Unverified -> "未验证"; VerificationState.Verified -> "已验证"; VerificationState.Failed -> "失败" }
private fun profileError(code: String) = when (code) {
    "STORAGE_RECONFIGURE" -> "存储不可用，请重新配置"
    "CAPABILITY_MISSING" -> "该档案不支持所选的连接测试"
    "SAVE_FAILED" -> "保存未完成，请检查档案状态"
    "DELETE_FAILED" -> "删除未完成，请检查档案状态"
    "SELECTION_FAILED" -> "选择未完成"
    "BASE_URL_INVALID", "API_KEY_REQUIRED", "PROFILE_MODEL_NOT_MULTIMODAL" -> profileFormErrorMessage(code)
    else -> "连接测试失败"
}
