package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.plugin.InputModality

/**
 * 0.52 design §§5.5/5.9/5.10: the model service page.
 *
 * Host-free: [state] is the view model's snapshot and every user action is a lambda in [actions]. The page owns only
 * view-local state (the open manual-model dialog and the expanded full-value dialogs), so the refresh summary, the
 * provider card and the radio selection always come from the one catalog snapshot the view model holds.
 *
 * The three empty states follow the design's table: no provider (说明 + 添加提供商), provider without models (获取模型
 * primary), and models without a selection (list + hint). A corrupt catalog offers the destructive-but-narrow reset.
 */
@Composable
internal fun ModelServiceScreen(
    state: ModelCatalogUiState,
    actions: ModelCatalogActions,
    help: HelpState,
    onAddProvider: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onOpenModel: (String) -> Unit = {},
) {
    var addModelOpen by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.corrupt) {
            CorruptCatalogCard(
                onReset = { resetConfirm = true },
                busy = state.saving,
            )
        } else {
            state.warning?.let { warning ->
                Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.notice?.let { notice ->
                Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            state.refreshSummary?.let { summary ->
                RefreshSummaryRow(summary) { actions.dismissRefreshSummary() }
            }

            val provider = state.activeProvider
            if (provider == null) {
                EmptyCatalogCard(onAddProvider)
            } else {
                ProviderCard(state, provider, onOpenProviders)
                FetchRow(state, actions, help, onAddModel = { addModelOpen = true })
                if (state.models.isEmpty()) {
                    Text(ModelCatalogUi.NO_MODELS_TITLE, style = MaterialTheme.typography.titleSmall)
                    Text(
                        ModelCatalogUi.NO_MODELS_DETAIL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (state.selectedModel == null) {
                        Text(
                            ModelCatalogUi.NO_MODEL_SELECTED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.models.forEach { entry ->
                            ModelRow(
                                entry = entry,
                                provider = provider,
                                selected = state.selectedModel?.id == entry.id,
                                enabled = ModelCatalogUi.canSelect(entry, provider),
                                onSelect = { actions.selectModel(entry.id) },
                                onOpen = { onOpenModel(entry.id) },
                            )
                        }
                    }
                }
            }
            Text(
                ModelCatalogUi.FOOTER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (addModelOpen) {
        AddManualModelDialog(
            existing = state.activeProvider,
            help = help,
            onDismiss = { addModelOpen = false },
            onSubmit = { draft ->
                actions.addManualModel(draft)
                addModelOpen = false
            },
        )
    }
    (state.confirmation as? CatalogConfirmation.DeleteModel)?.let { pending ->
        AlertDialog(
            onDismissRequest = { actions.cancelPending() },
            title = { Text(ModelCatalogUi.DELETE_MODEL_TITLE) },
            text = { Text("「${pending.displayName}」将从目录中删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = { actions.confirmPending(null) }) { Text(ModelCatalogUi.DELETE) }
            },
            dismissButton = {
                TextButton(onClick = { actions.cancelPending() }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
    }
    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("重置模型目录？") },
            text = { Text(ModelCatalogUi.RESET_DETAIL) },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirm = false
                    actions.resetCatalog()
                }) { Text(ModelCatalogUi.RESET_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
    }
}

@Composable
private fun EmptyCatalogCard(onAddProvider: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ModelCatalogUi.EMPTY_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                ModelCatalogUi.EMPTY_DETAIL,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "必填：名称、网关 Base URL、API Key。协议与超时在「高级」里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddProvider) { Text(ModelCatalogUi.ADD_PROVIDER) }
        }
    }
}

@Composable
private fun CorruptCatalogCard(onReset: () -> Unit, busy: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ModelCatalogUi.RESET_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(ModelCatalogUi.RESET_DETAIL, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onReset, enabled = !busy) { Text(ModelCatalogUi.RESET) }
        }
    }
}

@Composable
private fun RefreshSummaryRow(summary: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = ModelCatalogUi.SUMMARY_DISMISS)
            }
        }
    }
}

@Composable
private fun ProviderCard(
    state: ModelCatalogUiState,
    provider: Provider,
    onOpenProviders: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                CatalogChip(ModelCatalogUi.ACTIVE_CHIP, MaterialTheme.colorScheme.primaryContainer)
                TextButton(onClick = onOpenProviders) { Text(ModelCatalogUi.SWITCH_PROVIDER) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Base URL", style = MaterialTheme.typography.labelMedium)
                LongValueText(
                    provider.baseUrl,
                    description = "查看完整 Base URL",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API Key", style = MaterialTheme.typography.labelMedium)
                Text(
                    ModelCatalogUi.maskFor(state, provider.id),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ModelCatalogUi.MODEL_COUNT} ${provider.models.size}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    " · ${ModelCatalogUi.LAST_SYNC} ${ModelCatalogUi.lastSyncText(provider.lastSyncedAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (provider.secretHandleId == null) {
                Text(ModelCatalogUi.NO_SECRET, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FetchRow(
    state: ModelCatalogUiState,
    actions: ModelCatalogActions,
    help: HelpState,
    onAddModel: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { actions.refresh() }, enabled = !state.busy) {
            Text(if (state.loading) ModelCatalogUi.FETCHING else ModelCatalogUi.FETCH_MODELS)
        }
        OutlinedButton(onClick = onAddModel, enabled = !state.busy) { Text(ModelCatalogUi.ADD_MODEL) }
        HelpIcon(HelpContent.FETCH_MODELS, help)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(
    entry: ModelEntry,
    provider: Provider,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val disabledReason = if (enabled) null else ModelCatalogUi.disabledReason(entry, provider)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "选择模型 ${entry.displayName}"
                },
            )
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "查看模型「${entry.displayName}」详情",
                        )
                    }
                }
                Text(
                    ModelCatalogUi.budgetText(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelIdText(entry.id)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelCatalogUi.chips(entry, provider).forEach { chip -> CatalogChip(chip) }
                    inputChips(entry.inputs).forEach { chip -> CatalogChip(chip) }
                }
                if (disabledReason != null) {
                    Text(
                        disabledReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelIdText(id: String) {
    LongValueText(id, description = "查看完整模型 ID $id", style = MaterialTheme.typography.bodySmall)
}

/** The supported-input chips; audio is explicitly reserved (design §5.6). */
private fun inputChips(inputs: Set<InputModality>): List<String> = buildList {
    if (InputModality.TEXT in inputs) add("文本")
    if (InputModality.IMAGE in inputs) add("图片")
    if (InputModality.AUDIO in inputs) add("音频")
}

@Composable
private fun AddManualModelDialog(
    existing: Provider?,
    help: HelpState,
    onDismiss: () -> Unit,
    onSubmit: (ManualModelDraft) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var windowK by remember { mutableStateOf("128") }
    var outputK by remember { mutableStateOf("8") }
    var image by remember { mutableStateOf(true) }
    var errors by remember { mutableStateOf(emptyList<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelCatalogUi.ADD_MODEL) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文窗口", style = MaterialTheme.typography.bodyMedium)
                    HelpIcon(HelpContent.MODEL_CONTEXT_WINDOW, help)
                }
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "模型 ID 输入" },
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "模型显示名输入" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = windowK,
                        onValueChange = { windowK = it },
                        label = { Text("窗口（K）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "模型上下文窗口输入" },
                    )
                    OutlinedTextField(
                        value = outputK,
                        onValueChange = { outputK = it },
                        label = { Text("输出（K）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "模型最大输出输入" },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图片输入", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(
                        checked = image,
                        onCheckedChange = { image = it },
                        modifier = Modifier.semantics { contentDescription = "模型图片输入" },
                    )
                }
                errors.forEach { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val draft = ManualModelDraft(
                    id = id,
                    displayName = displayName,
                    contextWindowK = windowK,
                    maxOutputK = outputK,
                    inputs = if (image) setOf(InputModality.TEXT, InputModality.IMAGE) else setOf(InputModality.TEXT),
                )
                val problems = ModelCatalogUi.manualModelErrors(draft, existing)
                // Submit the same normalized values that were validated, so the dialog and the view model agree on the
                // id and display name (a trailing-space id must not produce a hidden duplicate).
                if (problems.isEmpty()) onSubmit(ModelCatalogUi.normalizeManualDraft(draft)) else errors = problems
            }) { Text("新增") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ModelCatalogUi.RESET_CANCEL) }
        },
    )
}

/**
 * A one-line value that expands into a dialog with the full text and a copy button (design §4.5: long URLs and model
 * ids may be elided, but must stay readable and copyable). The copy action is the platform clipboard, never a log.
 */
@Composable
internal fun LongValueText(
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        value,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = style,
        modifier = modifier
            .clickable(onClickLabel = description) { expanded = true }
            .semantics { contentDescription = description },
    )
    if (expanded) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(ModelCatalogUi.FULL_VALUE_TITLE) },
            text = { Text(value) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(value))
                    expanded = false
                }) { Text(ModelCatalogUi.COPY) }
            },
            dismissButton = {
                TextButton(onClick = { expanded = false }) { Text(ModelCatalogUi.DISMISS) }
            },
        )
    }
}

/** One compact status chip; the pages use it for origin / missing / thinking / verification / inputs. */
@Composable
internal fun CatalogChip(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The page a composition without a catalog/service shows instead of inventing an empty catalog. */
@Composable
internal fun ModelCatalogUnavailableScreen() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ModelCatalogUi.UNAVAILABLE, style = MaterialTheme.typography.titleMedium)
            Text(ModelCatalogUi.UNAVAILABLE_DETAIL, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
