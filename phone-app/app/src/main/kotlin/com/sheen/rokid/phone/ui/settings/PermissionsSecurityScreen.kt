package com.sheen.rokid.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult

/**
 * 0.52 design §3.5/§6.1: the 权限与安全 page.
 *
 * Three sections with three different save models, which is the whole point of the page:
 *
 * - **工具权限** is the form: level and whitelist have an explicit 「保存」 button, are dirty until saved, and carry a
 *   「需重启」 chip each. The save result counts exactly those two fields.
 * - **不拦截** is immediate and **outside** that save: only off → on raises the second confirmation, the write happens
 *   on the switch move, and a refusal is shown inline. A successful write updates the page's base/live value so the
 *   later form save cannot write the old value back. The risk copy is always visible; the enabled warning is red.
 * - **锁屏 PIN** and **系统授权** are immediate actions, never dirty form edits. The PIN is digit-only, 4-16 characters,
 *   and is never logged or echoed; the four grants keep the old red missing-grant sentences and 「去授权」 actions.
 *
 * The page is host-free: all values and callbacks come from the shell, so the Compose test can drive it with fakes.
 * [load] is called once on entry (design §4.4). [requestPop]/[registerBackGuard] are the shell seam: the top-left arrow
 * and system back both ask 「放弃修改？」 while the form is dirty.
 */
@Composable
internal fun PermissionsSecurityScreen(
    load: () -> SmartSettings,
    save: (SmartSettings) -> SettingsSaveResult,
    noInterceptionLive: Boolean,
    savePin: (String) -> Boolean,
    clearPin: () -> Unit,
    pinConfigured: Boolean,
    permissionStates: List<PermissionState>,
    help: HelpState,
    requestPop: () -> Unit = {},
    registerBackGuard: (() -> Unit) -> Unit = {},
) {
    // One load per page entry (design §4.4): re-reading on recomposition would overwrite the draft under the user.
    val loaded = remember { load() }
    var state by remember {
        mutableStateOf(PermissionsSecurityLogic.initial(loaded, noInterceptionLive, pinConfigured))
    }
    var discardPrompt by remember { mutableStateOf(false) }

    // Updated-state reads keep the guard current without re-registering on every keystroke.
    val dirty by rememberUpdatedState(state.dirty)
    val pop by rememberUpdatedState(requestPop)
    val attemptLeave: () -> Unit = {
        if (dirty) discardPrompt = true else pop()
    }

    BackHandler { attemptLeave() }
    DisposableEffect(Unit) {
        registerBackGuard(attemptLeave)
        // No unregister in onDispose: the shell's pop clears the slot, and a page leaving composition because the
        // whole shell went away takes the guard with it (same contract as the assistant page).
        onDispose { }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsRestartNote()

        SettingsGroup(PermissionsSecurityUi.TOOL_PERMISSIONS_TITLE) {
            MaxLevelRow(state, help) { level -> state = PermissionsSecurityLogic.editMaxLevel(state, level) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            WhitelistRow(state, help) { text -> state = PermissionsSecurityLogic.editWhitelist(state, text) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NoInterceptionRow(state, help) { requested ->
                state = PermissionsSecurityLogic.requestNoInterception(state, requested, save)
            }
            state.noInterceptionMessage?.let { message ->
                Text(
                    message,
                    color = if (state.noInterceptionResult?.saved == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.validationErrors.forEach { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.saveMessage?.let { message ->
                Text(
                    message,
                    color = if (state.result?.saved == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Button(
                onClick = { state = PermissionsSecurityLogic.save(state, save) },
                enabled = state.dirty,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(SettingsPageUi.SAVE)
            }
        }

        PinSection(
            state = state,
            help = help,
            onText = { text -> state = PermissionsSecurityLogic.editPin(state, text) },
            onSave = { state = PermissionsSecurityLogic.savePin(state, savePin) },
            onClear = { state = PermissionsSecurityLogic.clearPin(state, clearPin) },
        )

        SettingsGroup(PermissionsSecurityUi.GRANTS_TITLE) {
            permissionStates.forEachIndexed { index, grant ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                GrantRow(grant)
            }
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
                }) {
                    Text(SettingsPageUi.DISCARD_CONFIRM)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardPrompt = false }) {
                    Text(SettingsPageUi.DISCARD_KEEP)
                }
            },
        )
    }

    if (state.noInterceptionConfirmPending) {
        AlertDialog(
            onDismissRequest = {
                state = PermissionsSecurityLogic.dismissNoInterceptionConfirm(state)
            },
            title = { Text(NoInterceptionUi.CONFIRM_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoInterceptionUi.CONFIRM_LINES.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    state = PermissionsSecurityLogic.confirmNoInterception(state, save)
                }) { Text(PermissionsSecurityUi.CONFIRM_ON) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    state = PermissionsSecurityLogic.dismissNoInterceptionConfirm(state)
                }) { Text(PermissionsSecurityUi.CONFIRM_CANCEL) }
            },
        )
    }
}

/** The title + `(?)` icon + restart badge shared by the two restart rows (design §4.1 keeps badges visible). */
@Composable
private fun PermissionHeading(title: String, helpText: HelpText, help: HelpState, restart: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        HelpIcon(helpText, help)
        if (restart) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(50)) {
                Text(
                    SettingsPageUi.RESTART_CHIP,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 权限上限: five selectable levels. The parser (and so the stored spelling) stays [SmartSettingsForm]'s. */
@Composable
private fun MaxLevelRow(
    state: PermissionsSecurityState,
    help: HelpState,
    onLevel: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        PermissionHeading("权限上限", HelpContent.MAX_LEVEL, help, restart = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..4).forEach { index ->
                val level = "L$index"
                FilterChip(
                    selected = state.maxLevel.equals(level, ignoreCase = true),
                    onClick = { onLevel(level) },
                    label = { Text(level) },
                )
            }
        }
    }
}

/** 白名单: the `action|scope` text field; [SmartSettingsForm] owns the format rule and reports the errors. */
@Composable
private fun WhitelistRow(
    state: PermissionsSecurityState,
    help: HelpState,
    onText: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        PermissionHeading("白名单", HelpContent.WHITELIST, help, restart = true)
        OutlinedTextField(
            value = state.whitelist,
            onValueChange = onText,
            label = { Text("action|scope，逗号分隔") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "白名单输入" },
        )
    }
}

/**
 * 不拦截: immediate and outside the form. The risk sentence and the enabled red line are both always/conditionally
 * visible; only the off → on move raises [NoInterceptionUi.CONFIRM_TITLE] (the page state decides that).
 */
@Composable
private fun NoInterceptionRow(
    state: PermissionsSecurityState,
    help: HelpState,
    onRequest: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(NoInterceptionUi.SWITCH_TITLE, style = MaterialTheme.typography.bodyLarge)
                    HelpIcon(HelpContent.NO_INTERCEPTION, help)
                }
                Text(NoInterceptionUi.SWITCH_SUMMARY, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = state.noInterceptionLive,
                onCheckedChange = onRequest,
                modifier = Modifier.semantics { contentDescription = NoInterceptionUi.SWITCH_TITLE },
            )
        }
        if (state.noInterceptionLive) {
            Text(
                NoInterceptionUi.ENABLED_WARNING,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The 锁屏 PIN group: the visible privacy sentence, one digit field, and the two old immediate buttons. */
@Composable
private fun PinSection(
    state: PermissionsSecurityState,
    help: HelpState,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsGroup(PermissionsSecurityUi.PIN_TITLE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("锁屏 PIN", style = MaterialTheme.typography.bodyLarge)
                HelpIcon(HelpContent.LOCK_PIN, help)
            }
            Text(PermissionsSecurityUi.PIN_PRIVACY, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = state.pinInput,
                onValueChange = onText,
                label = { Text("锁屏 PIN（4-16 位数字）") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "锁屏 PIN 输入" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) {
                    Text(
                        if (state.pinConfigured) {
                            PermissionsSecurityUi.PIN_UPDATE
                        } else {
                            PermissionsSecurityUi.PIN_SAVE
                        },
                    )
                }
                if (state.pinConfigured) {
                    OutlinedButton(onClick = onClear) {
                        Text(PermissionsSecurityUi.PIN_CLEAR)
                    }
                }
            }
            if (state.pinConfigured) {
                Text(PermissionsSecurityUi.PIN_CONFIGURED, style = MaterialTheme.typography.bodySmall)
            }
            state.pinError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.pinNotice?.let { notice ->
                Text(notice, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One 系统授权 row: the label and status, plus the missing red sentence and its 「去授权」 action. */
@Composable
private fun GrantRow(grant: PermissionState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(grant.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (grant.granted) {
                Text(
                    PermissionsSecurityUi.grantedStatusText(grant.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = grant.onGrant,
                    modifier = Modifier.semantics { contentDescription = "${grant.actionLabel}：${grant.label}" },
                ) {
                    Text(grant.actionLabel)
                }
            }
        }
        if (!grant.granted) {
            Text(
                grant.missingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
