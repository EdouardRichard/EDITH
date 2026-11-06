package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.model.catalog.Provider

/**
 * 0.52 design §5.9: the provider list - any number of providers, exactly one globally active.
 *
 * Tapping a row requests a switch. If the view model knows a session is running it opens the
 * [CatalogConfirmation.SwitchProvider] dialog first (「切换会先停止当前会话，之后需重新开始」); the stop, the catalog write
 * and the 「已切换到 X」 notice all happen after that confirmation. Deleting an active provider requires choosing the
 * successor in the confirmation dialog; the model list and credential of the old provider are deleted with it.
 *
 * The page is host-free: every mutation is an [ModelCatalogActions] lambda, so the Compose test drives one dialog path
 * without a repository.
 */
@Composable
internal fun ProviderListScreen(
    state: ModelCatalogUiState,
    actions: ModelCatalogActions,
    help: HelpState,
    onAddProvider: () -> Unit = {},
    onEditProvider: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            ModelCatalogUi.PROVIDER_LIST_DETAIL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("提供商列表", style = MaterialTheme.typography.titleSmall)
            HelpIcon(HelpContent.FETCH_MODELS, help)
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.notice?.let { notice ->
            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        val providers = state.catalog?.providers.orEmpty()
        if (providers.isEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ModelCatalogUi.EMPTY_TITLE, style = MaterialTheme.typography.titleMedium)
                    Text(ModelCatalogUi.EMPTY_DETAIL, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onAddProvider) { Text(ModelCatalogUi.ADD_PROVIDER) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { provider ->
                    ProviderListRow(
                        provider = provider,
                        active = provider.id == state.activeProvider?.id,
                        modelCount = provider.models.size,
                        onSwitch = { actions.setActiveProvider(provider.id) },
                        onEdit = { onEditProvider(provider.id) },
                        onDelete = { actions.deleteProvider(provider.id) },
                    )
                }
            }
            Button(onClick = onAddProvider) { Text(ModelCatalogUi.ADD_PROVIDER) }
        }
    }

    (state.confirmation as? CatalogConfirmation.SwitchProvider)?.let { pending ->
        AlertDialog(
            onDismissRequest = { actions.cancelPending() },
            title = { Text(ModelCatalogUi.SWITCH_CONFIRM_TITLE) },
            text = { Text(ModelCatalogUi.SWITCH_CONFIRM) },
            confirmButton = {
                TextButton(onClick = { actions.confirmPending(null) }) {
                    Text(ModelCatalogUi.SWITCH_CONFIRM_ACTION)
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.cancelPending() }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
    }
    (state.confirmation as? CatalogConfirmation.DeleteProvider)?.let { pending ->
        val others = state.catalog?.providers.orEmpty().filterNot { it.id == pending.providerId }
        val deletingActive = state.activeProvider?.id == pending.providerId
        var chosen by remember(pending.providerId, others.size) {
            mutableStateOf(others.firstOrNull()?.id ?: pending.newActiveId)
        }
        AlertDialog(
            onDismissRequest = { actions.cancelPending() },
            title = { Text(ModelCatalogUi.DELETE_PROVIDER_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${pending.providerName}」及其模型与凭证将被删除，无法恢复。")
                    if (deletingActive && others.isNotEmpty()) {
                        Text(ModelCatalogUi.CHOOSE_NEW_ACTIVE, style = MaterialTheme.typography.bodySmall)
                        others.forEach { provider ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = chosen == provider.id,
                                        role = Role.RadioButton,
                                        onClick = { chosen = provider.id },
                                    ),
                            ) {
                                RadioButton(
                                    selected = chosen == provider.id,
                                    onClick = null,
                                    modifier = Modifier.semantics {
                                        contentDescription = "新生效提供商 ${provider.name}"
                                    },
                                )
                                Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { actions.confirmPending(chosen) },
                    enabled = !deletingActive || others.isEmpty() || chosen != null,
                ) { Text(ModelCatalogUi.DELETE_PROVIDER_ACTION) }
            },
            dismissButton = {
                TextButton(onClick = { actions.cancelPending() }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
    }
}

@Composable
private fun ProviderListRow(
    provider: Provider,
    active: Boolean,
    modelCount: Int,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickableRow(active, onSwitch)
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        CatalogChip(ModelCatalogUi.ACTIVE_CHIP, MaterialTheme.colorScheme.primaryContainer)
                    }
                }
                Text(
                    "${ModelCatalogUi.MODEL_COUNT} $modelCount · ${ModelCatalogUi.lastSyncText(provider.lastSyncedAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑提供商 ${provider.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除提供商 ${provider.name}")
            }
        }
    }
}

/**
 * Row click = switch. The active row is not a switch target; it is still clickable so a screen reader can focus it,
 * but the action is a no-op (the view model also refuses to rewrite the same provider).
 */
private fun Modifier.clickableRow(active: Boolean, onSwitch: () -> Unit): Modifier =
    this.clickable(
        onClickLabel = if (active) "当前生效" else "切换到该提供商",
        enabled = !active,
        onClick = onSwitch,
    )
