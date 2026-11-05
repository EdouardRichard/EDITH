package com.sheen.rokid.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.model.catalog.Provider

/**
 * 0.52 design §5.10: the add/edit provider form - three required fields plus a collapsed 高级 section.
 *
 * The plaintext key never leaves this form: the save button converts it into a `CharArray` for the view model and
 * clears the field only after the catalog write succeeded ([ModelCatalogUiState.savedProviderId]); every error shown
 * here is a fixed field sentence or [ModelCatalogUiState.error], never the key, the URL or a response body. An edit
 * pre-fills everything except the key (the repository never reads it back) and saving with a blank key keeps the stored
 * credential. Leaving with unsaved edits asks 「放弃修改？」 like the other form pages.
 */
@Composable
internal fun ProviderEditorScreen(
    state: ModelCatalogUiState,
    actions: ModelCatalogActions,
    help: HelpState,
    providerId: String? = null,
    requestPop: () -> Unit = {},
    registerBackGuard: (() -> Unit) -> Unit = {},
) {
    val stored: Provider? = remember(providerId, state.catalog) {
        providerId?.let { id -> state.catalog?.providers?.firstOrNull { it.id == id } }
    }
    var name by remember(providerId) { mutableStateOf(stored?.name.orEmpty()) }
    var baseUrl by remember(providerId) { mutableStateOf(stored?.baseUrl.orEmpty()) }
    var apiKey by remember(providerId) { mutableStateOf("") }
    var protocolId by remember(providerId) { mutableStateOf(stored?.protocolId ?: "OPENAI_RESPONSES") }
    var timeoutText by remember(providerId) { mutableStateOf((stored?.timeoutMillis ?: 30_000L).toString()) }
    var advanced by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<String>()) }

    val editing = providerId != null
    val dirty = name != stored?.name.orEmpty() ||
        baseUrl != stored?.baseUrl.orEmpty() ||
        apiKey.isNotEmpty() ||
        protocolId != (stored?.protocolId ?: "OPENAI_RESPONSES") ||
        timeoutText != (stored?.timeoutMillis ?: 30_000L).toString()
    var discardPrompt by remember { mutableStateOf(false) }
    val dirtyNow by rememberUpdatedState(dirty)
    val pop by rememberUpdatedState(requestPop)
    val attemptLeave: () -> Unit = { if (dirtyNow) discardPrompt = true else pop() }

    BackHandler { attemptLeave() }
    DisposableEffect(Unit) {
        registerBackGuard(attemptLeave)
        onDispose { }
    }

    // Saved: the page consumes the event, drops the plaintext field and returns to the provider list/model page.
    LaunchedEffect(state.savedProviderId) {
        if (state.savedProviderId != null && (providerId == null || state.savedProviderId == providerId)) {
            apiKey = ""
            actions.consumeSaved()
            pop()
        }
    }

    // A known provider that vanished from the catalog (deleted elsewhere): keep the form usable but say so.
    if (editing && stored == null) {
        Text("该提供商已不存在。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (editing) "修改名称、地址或 Key；留空 Key 会保留已保存的凭证。" else "名称、网关 Base URL 和 API Key 为必填。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; errors = emptyList() },
            label = { Text(ModelCatalogUi.NAME) },
            singleLine = true,
            isError = errors.any { it == ModelCatalogUi.providerNameError(name) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "提供商名称输入" },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; errors = emptyList() },
            label = { Text(ModelCatalogUi.BASE_URL) },
            singleLine = true,
            isError = errors.any { it == ModelCatalogUi.providerBaseUrlError(baseUrl, BuildConfig.DEBUG) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "提供商 Base URL 输入" },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; errors = emptyList() },
            label = { Text(ModelCatalogUi.API_KEY) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                if (editing && stored?.secretHandleId != null) {
                    Text("${ModelCatalogUi.maskFor(state, requireNotNull(providerId))} · ${ModelCatalogUi.KEY_KEEP}")
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "提供商 API Key 输入" },
        )

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "收起${ModelCatalogUi.ADVANCED}" else ModelCatalogUi.ADVANCED)
        }
        if (advanced) {
            Row {
                Text(ModelCatalogUi.PROTOCOL, style = MaterialTheme.typography.bodyMedium)
                HelpIcon(HelpContent.PROVIDER_PROTOCOL, help)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = protocolId == "OPENAI_RESPONSES",
                    onClick = { protocolId = "OPENAI_RESPONSES"; errors = emptyList() },
                    label = { Text(ModelCatalogUi.RESPONSES) },
                )
                FilterChip(
                    selected = protocolId == "OPENAI_CHAT_COMPLETIONS",
                    onClick = { protocolId = "OPENAI_CHAT_COMPLETIONS"; errors = emptyList() },
                    label = { Text(ModelCatalogUi.CHAT_COMPLETIONS) },
                )
            }
            Row {
                Text(ModelCatalogUi.TIMEOUT, style = MaterialTheme.typography.bodyMedium)
                HelpIcon(HelpContent.PROVIDER_TIMEOUT, help)
            }
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it; errors = emptyList() },
                label = { Text(ModelCatalogUi.TIMEOUT) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "提供商超时输入" },
            )
        }

        errors.forEach { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val problems = buildList {
                    ModelCatalogUi.providerNameError(name)?.let(::add)
                    ModelCatalogUi.providerBaseUrlError(baseUrl, BuildConfig.DEBUG)?.let(::add)
                    if (!editing && apiKey.isBlank()) add(ModelCatalogUi.KEY_REQUIRED)
                    timeoutText.trim().toLongOrNull()?.takeIf { it > 0 } ?: add("超时需为大于 0 的毫秒整数")
                }
                if (problems.isNotEmpty()) {
                    errors = problems
                } else {
                    errors = emptyList()
                    actions.saveProvider(
                        ProviderDraft(
                            name = name.trim(),
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.takeIf { it.isNotEmpty() }?.toCharArray(),
                            protocolId = protocolId,
                            timeoutMillis = requireNotNull(timeoutText.trim().toLongOrNull()),
                            providerId = providerId,
                        ),
                    )
                }
            },
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(ModelCatalogUi.SAVE_AND_FETCH)
        }
    }

    if (discardPrompt) {
        AlertDialog(
            onDismissRequest = { discardPrompt = false },
            title = { Text(SettingsPageUi.DISCARD_TITLE) },
            text = { Text(SettingsPageUi.DISCARD_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    discardPrompt = false
                    pop()
                }) { Text(SettingsPageUi.DISCARD_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { discardPrompt = false }) { Text(SettingsPageUi.DISCARD_KEEP) }
            },
        )
    }
}
