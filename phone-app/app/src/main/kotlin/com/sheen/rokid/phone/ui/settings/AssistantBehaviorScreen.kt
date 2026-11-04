package com.sheen.rokid.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm

/**
 * 0.52 design §3.3: the assistant behavior page.
 *
 * It is host-free by design: the host supplies [load], [save] and [setProactive] as lambdas, so the page never reaches
 * into `HostCompositionRoot` (and the Compose test can drive it with fakes). The pure state machine lives in
 * [AssistantBehaviorLogic]; this file only lays out [AssistantBehaviorState] and routes clicks.
 *
 * The page's two save paths are deliberately different:
 *
 * - the **form** (wake words, continue dialogue, vision, cooldown, sensitivity) has an explicit 「保存」 button that is
 *   enabled only while the draft differs, and its success line counts the fields that need a full App restart;
 * - the **proactive switch** is immediate and outside the form: it calls [setProactive] on the tap and shows that
 *   call's result inline. In the phone-only mode it stays visible but disabled, and the A12 reason stays in `(?)`.
 *
 * [requestPop] and [registerBackGuard] are the shell seam (design §4.4): the top-left arrow and system back both ask
 * 「放弃修改？」 while the draft is dirty. The page registers the guarded back action; the shell consults it from
 * `PhoneDetailPage`'s `onBack`, and a local [BackHandler] covers the system back when the page is composed outside a
 * dialog (the Compose test does this). The defaults keep a standalone composition usable.
 */
@Composable
internal fun AssistantBehaviorScreen(
    load: () -> SmartSettings,
    save: (SmartSettings) -> SettingsSaveResult,
    setProactive: (Boolean) -> SettingsSaveResult,
    help: HelpState,
    requestPop: () -> Unit = {},
    registerBackGuard: (() -> Unit) -> Unit = {},
) {
    // One load per page entry (design §4.4): re-reading on recomposition would overwrite the draft under the user.
    val loaded = remember { load() }
    var state by remember { mutableStateOf(AssistantBehaviorLogic.initial(loaded)) }
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
        // No unregister in onDispose: the shell's pop (closePage) clears the slot, and a page leaving composition
        // because the whole shell went away takes the guard with it.
        onDispose { }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RestartNote()

        SettingsGroup("唤醒") {
            WakeWordsRow(state, help) { draft -> state = AssistantBehaviorLogic.editDraft(state, draft) }
        }

        SettingsGroup("对话") {
            BehaviorSwitchRow(
                title = "持续对话",
                helpText = HelpContent.CONTINUE_DIALOGUE,
                help = help,
                checked = state.draft.continueDialogue,
                onCheckedChange = { checked ->
                    state = AssistantBehaviorLogic.editDraft(state, state.draft.copy(continueDialogue = checked))
                },
                restart = true,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            BehaviorSwitchRow(
                title = "感知截图",
                helpText = HelpContent.VISION,
                help = help,
                checked = state.draft.visionEnabled,
                onCheckedChange = { checked ->
                    state = AssistantBehaviorLogic.editDraft(state, state.draft.copy(visionEnabled = checked))
                },
                sensitive = true,
            )
        }

        SettingsGroup("主动辅助") {
            // Immediate and outside the form: tapping saves through ProactiveArming, it does not change the draft.
            BehaviorSwitchRow(
                title = "未唤醒时给一句短提示",
                helpText = HelpContent.PROACTIVE,
                help = help,
                checked = state.proactiveChecked,
                onCheckedChange = { checked ->
                    state = AssistantBehaviorLogic.toggleProactive(state, checked, setProactive)
                },
                enabled = state.proactiveOperable,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CooldownRow(state, help) { text -> state = AssistantBehaviorLogic.editCooldownSeconds(state, text) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SensitivityRow(state, help) { level ->
                state = AssistantBehaviorLogic.editDraft(state, state.draft.copy(proactiveSensitivity = level))
            }
        }

        state.validationErrors.forEach { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
            )
        }
        state.proactiveMessage?.let { message ->
            Text(
                message,
                color = if (state.proactiveResult?.saved == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = { state = AssistantBehaviorLogic.save(state, save) },
            enabled = state.dirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(AssistantBehaviorUi.SAVE)
        }
    }

    if (discardPrompt) {
        AlertDialog(
            onDismissRequest = { discardPrompt = false },
            title = { Text(AssistantBehaviorUi.DISCARD_TITLE) },
            text = { Text("当前页面有未保存的修改。") },
            confirmButton = {
                TextButton(onClick = {
                    discardPrompt = false
                    pop()
                }) {
                    Text(AssistantBehaviorUi.DISCARD_CONFIRM)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardPrompt = false }) {
                    Text(AssistantBehaviorUi.DISCARD_KEEP)
                }
            },
        )
    }
}

/** The persistent 「需重启」 note at the top of the page. It is a single, always-present sentence. */
@Composable
private fun RestartNote() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                AssistantBehaviorUi.RESTART_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** The wake-word text field; the form's own parser is the authority for the 1-16/letter-or-CJK rules. */
@Composable
private fun WakeWordsRow(
    state: AssistantBehaviorState,
    help: HelpState,
    onDraft: (SmartSettingsForm.Draft) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        RowHeading("唤醒词", HelpContent.WAKE_WORDS, help, restart = true)
        OutlinedTextField(
            value = state.draft.wakeWords,
            onValueChange = { text -> onDraft(state.draft.copy(wakeWords = text)) },
            label = { Text("逗号分隔，1-16 个字符") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "唤醒词输入" },
        )
    }
}

/** The cooldown's UI field. The user types seconds; [AssistantBehaviorLogic.save] converts to the stored millis. */
@Composable
private fun CooldownRow(
    state: AssistantBehaviorState,
    help: HelpState,
    onSeconds: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        RowHeading("冷却时间", HelpContent.PROACTIVE_COOLDOWN, help, restart = true)
        OutlinedTextField(
            value = state.cooldownSeconds,
            onValueChange = onSeconds,
            label = { Text("秒（5-600）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "冷却秒数输入" },
        )
    }
}

/** The three-level sensitivity chooser; stored values stay the plugin's LOW/MEDIUM/HIGH spelling. */
@Composable
private fun SensitivityRow(
    state: AssistantBehaviorState,
    help: HelpState,
    onSensitivity: (String) -> Unit,
) {
    val choices = listOf("LOW" to "低", "MEDIUM" to "中", "HIGH" to "高")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        RowHeading("敏感度", HelpContent.PROACTIVE_SENSITIVITY, help, restart = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (stored, label) ->
                FilterChip(
                    selected = state.draft.proactiveSensitivity == stored,
                    onClick = { onSensitivity(stored) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/** One switch row. [onCheckedChange] is always supplied so the switch keeps its semantics; [enabled] is the gate. */
@Composable
private fun BehaviorSwitchRow(
    title: String,
    helpText: HelpText,
    help: HelpState,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    restart: Boolean = false,
    sensitive: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RowHeading(title, helpText, help, restart = restart, sensitive = sensitive)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** The title + `(?)` icon + risk badges that all six rows share. Badges stay outside the popover (design §4.1). */
@Composable
private fun RowHeading(
    title: String,
    helpText: HelpText,
    help: HelpState,
    restart: Boolean = false,
    sensitive: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        HelpIcon(helpText, help)
        if (restart) RestartChip()
        if (sensitive) SensitiveBadge()
    }
}

/** 「需重启」 pill on exactly the four launch-read fields. */
@Composable
private fun RestartChip() {
    StatusBadge(AssistantBehaviorUi.RESTART_CHIP, MaterialTheme.colorScheme.tertiaryContainer)
}

/** The vision row's always-visible risk badge (the long risk text itself lives in `(? VISION)`). */
@Composable
private fun SensitiveBadge() {
    StatusBadge(AssistantBehaviorUi.SENSITIVE_BADGE, MaterialTheme.colorScheme.errorContainer)
}

@Composable
private fun StatusBadge(text: String, container: Color) {
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
