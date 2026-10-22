package com.sheen.rokid.phone.ui.profile
import com.sheen.rokid.phone.model.ProfileForm
import com.sheen.rokid.phone.model.ProfileFormException
import com.sheen.rokid.phone.model.saveProfileDraft
import com.sheen.rokid.phone.model.profileFormErrorMessage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.ui.unit.dp
import com.sheen.rokid.plugin.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ProfileEditorScreen(profile: ModelProfile?, save: (ModelProfile, CharArray?) -> Boolean, cancel: () -> Unit) {
    val id = remember(profile?.id) { profile?.id ?: UUID.randomUUID().toString() }
    val initial = remember(id) { ProfileForm.from(profile) }
    var name by remember(id) { mutableStateOf(initial.name) }
    var service by remember(id) { mutableStateOf(initial.service) }
    var url by remember(id) { mutableStateOf(initial.baseUrl) }
    var model by remember(id) { mutableStateOf(initial.model) }
    var protocol by remember(id) { mutableStateOf(initial.protocolId) }
    var custom by remember(id) { mutableStateOf(profile?.protocolId?.takeUnless { it == "DEEPSEEK_RESPONSES" } ?: "") }
    var secret by remember(id) { mutableStateOf(charArrayOf()) }
    var window by remember(id) { mutableStateOf(initial.maxInputK) }
    var output by remember(id) { mutableStateOf(initial.maxOutputK) }
    var timeout by remember(id) { mutableStateOf(initial.timeoutMillis) }
    var inputs by remember(id) { mutableStateOf(initial.inputs) }
    var outputs by remember(id) { mutableStateOf(initial.outputs) }
    var expanded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    DisposableEffect(id) { onDispose { secret.fill('\u0000'); secret = charArrayOf() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (profile == null) "新建档案" else "编辑档案", style = MaterialTheme.typography.titleLarge)
        Field("名称", name, { name = it }); Field("服务", service, { service = it })
        Field("基础 URL", url, { url = it }, KeyboardType.Uri); Field("模型", model, { model = it })
        ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
            val protocolLabel = when (protocol) {
                "DEEPSEEK_RESPONSES" -> "OpenAI Responses API（文本）"
                "OPENAI_CHAT_COMPLETIONS" -> "OpenAI Chat Completions（文本+音频）"
                else -> "自定义协议 · 未接入"
            }
            OutlinedTextField(protocolLabel,
                {}, readOnly = true, label = { Text("协议") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("OpenAI Responses API（文本）") }, onClick = { protocol = "DEEPSEEK_RESPONSES"; expanded = false })
                DropdownMenuItem(text = { Text("OpenAI Chat Completions（文本+音频）") }, onClick = { protocol = "OPENAI_CHAT_COMPLETIONS"; expanded = false })
                DropdownMenuItem(text = { Text("自定义协议 · 未接入") }, onClick = { protocol = "CUSTOM"; expanded = false })
            }
        }
        if (protocol != "DEEPSEEK_RESPONSES" && protocol != "OPENAI_CHAT_COMPLETIONS") Field("自定义协议 ID", custom, { custom = it })
        OutlinedTextField(String(secret), { value -> secret.fill('\u0000'); secret = value.toCharArray() },
            label = { Text(if (profile == null) "API Key" else "API Key（留空保留）") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("声明输入", style = MaterialTheme.typography.titleMedium)
        FlowRow { InputModality.entries.forEach { item ->
            Row(Modifier.toggleable(item in inputs, onValueChange = { checked -> inputs = if (checked) inputs + item else inputs - item })) {
                Checkbox(item in inputs, null); Text(item.name, Modifier.padding(top = 12.dp, end = 12.dp))
            }
        } }
        Text("声明输出", style = MaterialTheme.typography.titleMedium)
        FlowRow { OutputModality.entries.forEach { item ->
            Row(Modifier.toggleable(item in outputs, onValueChange = { checked -> outputs = if (checked) outputs + item else outputs - item })) {
                Checkbox(item in outputs, null); Text(item.name, Modifier.padding(top = 12.dp, end = 12.dp))
            }
        } }
        val protocolDesc = when (protocol) {
            "DEEPSEEK_RESPONSES" -> "Responses API：TEXT → TEXT"
            "OPENAI_CHAT_COMPLETIONS" -> "Chat Completions：TEXT+AUDIO → TEXT"
            else -> "协议未接入"
        }
        Text(protocolDesc, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (InputModality.IMAGE in inputs) Text("图片请求：当前无图片适配器")
        // P5 (spec §A4): AUDIO input is only available with OPENAI_CHAT_COMPLETIONS protocol.
        if (protocol == "OPENAI_CHAT_COMPLETIONS" && InputModality.AUDIO in inputs) {
            Text("音频输入：需通过一次验证（AUDIO_REQUEST）", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (protocol != "OPENAI_CHAT_COMPLETIONS" && InputModality.AUDIO in inputs) {
            Text("音频输入：仅 OPENAI_CHAT_COMPLETIONS 协议支持", color = MaterialTheme.colorScheme.error)
        }
        Field("最大输入（K tokens）", window, { window = it }, KeyboardType.Decimal)
        Field("最大输出（K tokens）", output, { output = it }, KeyboardType.Decimal)
        Text("1 K = 1024 tokens · 容量为用户声明", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("超时（毫秒）", timeout, { timeout = it }, KeyboardType.Number)
        error?.let { Text(profileFormErrorMessage(it), color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                try {
                    val resolvedProtocol = when (protocol) {
                        "DEEPSEEK_RESPONSES", "OPENAI_CHAT_COMPLETIONS" -> protocol
                        else -> custom
                    }
                    val form = ProfileForm(name, service, url, model,
                        resolvedProtocol, window, output, timeout, inputs, outputs)
                    val draft = form.draft(id, profile, secret.any { !it.isWhitespace() })
                    if (saveProfileDraft(draft, secret, save)) { secret = charArrayOf(); error = null }
                    else error = "SAVE_FAILED"
                } catch (failure: ProfileFormException) { error = failure.code }
                catch (_: Exception) { error = "SAVE_FAILED" }
            }) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text("保存") }
            OutlinedButton(onClick = { secret.fill('\u0000'); secret = charArrayOf(); cancel() }) {
                Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text("取消")
            }
        }
    }
}

@Composable private fun Field(label: String, value: String, change: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}
