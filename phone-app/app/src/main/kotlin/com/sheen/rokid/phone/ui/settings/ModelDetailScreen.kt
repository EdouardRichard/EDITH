package com.sheen.rokid.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sheen.rokid.model.ModelFamilies
import com.sheen.rokid.phone.model.catalog.CatalogValidation
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelField
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ThinkingEffort
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 0.52 design §5.6: one model as the detail page edits it.
 *
 * The page is host-free, so the value conversion and the two invariants live here: a K-token field round-trips the
 * stored tokens exactly (gateway metadata is not always a whole K), and [toEntry] adds exactly the fields the user
 * changed to [ModelEntry.overrides] so the next gateway refresh cannot silently undo an edit. [thinkingSupported] is
 * the persisted capability: it is derived from the shared DeepSeek-family rule (an id or gateway metadata saying
 * `deepseek`) plus the advanced marker, and an unsupported model can never store an enabled thinking flag.
 */
internal data class ModelDetailDraft(
    val modelId: String,
    val displayName: String,
    val maxInputK: String,
    val maxOutputK: String,
    val inputs: Set<InputModality>,
    val thinkingEnabled: Boolean = false,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.LOW,
    val thinkingSupported: Boolean = false,
) {
    companion object {
        /** One read of the stored row; the page re-seeds only on [ModelCatalogUiState.modelEditEpoch]. */
        fun from(entry: ModelEntry): ModelDetailDraft = ModelDetailDraft(
            modelId = entry.id,
            displayName = entry.displayName,
            maxInputK = formatK(entry.contextWindowTokens),
            maxOutputK = formatK(entry.maxOutputTokens),
            inputs = entry.inputs,
            thinkingEnabled = entry.thinkingEnabled,
            thinkingEffort = entry.thinkingEffort,
            // 0.53 T8: the shared family rule derives the capability, so an old row stored `false` still opens the
            // switch instead of asking the user to re-mark a model the gateway already identified.
            thinkingSupported = entry.thinkingSupported || ModelFamilies.isDeepSeekFamily(entry.id),
        )

        /**
         * Builds the row to store. Text stays mandatory and the reserved audio declaration is preserved untouched;
         * every field that differs from [previous] is added to `overrides` (the existing set is kept - switching a
         * value back cannot know the gateway's original value, and "恢复默认" is the one operation that clears it).
         */
        fun toEntry(draft: ModelDetailDraft, previous: ModelEntry): ModelEntry {
            require(draft.modelId == previous.id) { ModelCatalogUi.MODEL_ID_IMMUTABLE }
            val displayName = draft.displayName.trim()
            CatalogValidation.displayNameError(displayName)?.let { throw IllegalArgumentException(it) }
            val window = requireNotNull(parseKTokens(draft.maxInputK)) { ModelDetailUi.WINDOW_ERROR }
            val output = requireNotNull(parseKTokens(draft.maxOutputK)) { ModelDetailUi.OUTPUT_ERROR }
            CatalogValidation.budgetError(window, output)?.let { throw IllegalArgumentException(it) }

            val inputs = draft.inputs + InputModality.TEXT
            val overrides = previous.overrides.toMutableSet()
            if (displayName != previous.displayName) overrides += ModelField.DISPLAY_NAME
            if (window != previous.contextWindowTokens) overrides += ModelField.CONTEXT_WINDOW_TOKENS
            if (output != previous.maxOutputTokens) overrides += ModelField.MAX_OUTPUT_TOKENS
            if (inputs != previous.inputs) overrides += ModelField.INPUTS

            // The derived capability is an invariant of the row, not an editable value: a DeepSeek-family id stays
            // supported even if the draft was un-marked, and an unrelated model keeps what the user marked.
            val thinkingSupported = draft.thinkingSupported || ModelFamilies.isDeepSeekFamily(previous.id)
            return previous.copy(
                displayName = displayName,
                contextWindowTokens = window,
                maxOutputTokens = output,
                inputs = inputs,
                // An unsupported row cannot carry enabled thinking; the block is disabled in that case anyway.
                thinkingEnabled = thinkingSupported && draft.thinkingEnabled,
                thinkingEffort = draft.thinkingEffort,
                thinkingSupported = thinkingSupported,
                overrides = overrides,
            )
        }

        /** The field errors in display order; [ModelDetailErrors.any] is false only when the row can be stored. */
        fun validate(draft: ModelDetailDraft): ModelDetailErrors {
            val displayName = CatalogValidation.displayNameError(draft.displayName.trim())
            val window = parseKTokens(draft.maxInputK)
            val output = parseKTokens(draft.maxOutputK)
            val budget = if (window != null && output != null) {
                CatalogValidation.budgetError(window, output)
            } else {
                null
            }
            return ModelDetailErrors(
                displayName = displayName,
                window = if (window == null) ModelDetailUi.WINDOW_ERROR else null,
                output = if (output == null) ModelDetailUi.OUTPUT_ERROR else null,
                budget = budget,
            )
        }

        /**
         * K tokens → tokens. Fractional K is accepted because a gateway can report a non-whole-K window and the page
         * must still round-trip it; zero, negative, non-numeric and overflowing values are refused.
         */
        fun parseKTokens(text: String): Int? {
            val normalized = text.trim()
            if (!K_TOKENS_PATTERN.matches(normalized)) return null
            val k = BigDecimal(normalized)
            if (k.signum() <= 0) return null
            val tokens = k.multiply(BigDecimal(1024)).setScale(0, RoundingMode.HALF_UP)
            if (tokens > BigDecimal(Int.MAX_VALUE)) return null
            return tokens.toInt()
        }

        /** tokens → K tokens: whole K when divisible, otherwise at most four decimals (enough to round-trip any Int). */
        fun formatK(tokens: Int): String {
            require(tokens > 0) { "tokens must be positive" }
            if (tokens % 1024 == 0) return (tokens / 1024).toString()
            return BigDecimal(tokens)
                .divide(BigDecimal(1024), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }

        private val K_TOKENS_PATTERN = Regex("""\d+(\.\d{1,4})?""")
    }
}

/** The page's per-field validation result; a shared [budget] error covers the window/output pair. */
internal data class ModelDetailErrors(
    val displayName: String? = null,
    val window: String? = null,
    val output: String? = null,
    val budget: String? = null,
) {
    val any: Boolean get() = displayName != null || window != null || output != null || budget != null
}

/**
 * 0.52 design §§5.6/5.7 and 0.53 design §§5.7/T9: the detail page's fixed copy and its pure display rules.
 *
 * Every user-visible string the page draws lives here, including the field units, the input rows, the footer and the
 * delete confirmation (Task 15). The audio row is deliberately a constant, not a stored field: 0.52 has no audio input
 * path and the switch stays disabled until 0.53 ([AUDIO_ENABLED] is wired to `checked` so the row cannot drift into a
 * different default). 0.53 keeps the thinking block always visible, because "this model cannot think" is information
 * the user needs; [thinkingEditable] is the capability gate and the block states the DeepSeek-only reason when closed.
 */
internal object ModelDetailUi {
    const val MODEL_ID = "模型 ID"
    const val DISPLAY_NAME = "显示名"
    const val WINDOW = "上下文窗口（最大输入）"
    const val OUTPUT = "最大输出（K）"
    const val K_TOKENS = "K tokens"
    const val PRESET = "常用档位"
    val PRESETS = listOf(32, 128, 256, 1024)
    const val WINDOW_ERROR = "上下文窗口需为大于 0 的 K tokens 数值"
    const val OUTPUT_ERROR = "最大输出需为大于 0 的 K tokens 数值"

    const val DISPLAY_NAME_INPUT = "模型显示名输入"
    const val WINDOW_INPUT = "模型上下文窗口输入"
    const val OUTPUT_INPUT = "模型最大输出输入"

    const val INPUTS = "支持的输入"
    const val INPUT_TEXT = "文本（必选）"
    const val IMAGE = "图片"
    const val AUDIO = "音频"
    const val IMAGE_SWITCH = "模型图片输入"
    const val AUDIO_SWITCH = "模型音频输入"
    const val AUDIO_DISABLED = "0.52 未接入，0.53 开放"
    const val AUDIO_ENABLED = false

    const val THINKING = "深度思考"
    const val THINKING_SWITCH = "启用深度思考"
    const val THINKING_EFFORT = "思考档位"
    const val EFFORT_LOW = "低"
    const val EFFORT_HIGH = "高"
    const val EFFORT_MAX = "最高"
    const val THINKING_MARK = "标记为 DeepSeek 思考模型"
    const val THINKING_MARK_DETAIL = "网关元数据未指明思考能力时手动启用；保存后按 DeepSeek 思考模型处理。"
    const val THINKING_MARK_DERIVED_DETAIL =
        "该模型属于 DeepSeek 家族（模型 id 或网关元数据），已自动标记为思考模型，不能关闭。"
    const val THINKING_ONLY_DEEPSEEK = "思考模式仅支持 DeepSeek 模型；当前模型按通用 OpenAI 协议请求。"
    const val THINKING_SUPPORTED_HINT = "仅支持 DeepSeek 模型；开启后工具回合保留思考，思考历史随请求回传。"
    const val ADVANCED = "高级"
    const val COLLAPSE = "收起"

    const val TEST_TITLE = "连接测试"
    const val TEST_TEXT = "文本测试"
    const val TEST_IMAGE = "图片测试"
    const val TEST_CONFIRM_TITLE = "测试连接？"
    const val TEST_CONFIRM_TEXT = "测试会发送固定合成内容"
    const val TEST_CONFIRM_DETAIL = "测试只验证连接，不包含你的对话或屏幕内容。"
    const val TEST_CONFIRM_ACTION = "开始测试"
    const val TEST_UNAVAILABLE_REASON = "模型测试不可用：本机未装配模型测试器。"
    const val TEST_RESULT_PREFIX = "上次测试"
    const val RESTORE = "恢复默认"
    const val RESTORE_TITLE = "恢复默认？"
    const val RESTORE_DETAIL = "将清除该模型的全部手动修改，并用网关元数据重新计算。只影响该模型。"
    const val RESTORE_ACTION = "恢复"
    const val DELETE_MODEL = "删除模型"
    const val DELETE_MODEL_DETAIL = "将从目录中删除，无法恢复。"
    const val ORIGIN_GATEWAY = "网关"
    const val ORIGIN_MANUAL = "手动"
    const val FULL_MODEL_ID = "查看完整模型 ID"
    const val FOOTER = "编辑只影响该模型；保存后立即生效。"
    const val FOOTER_PROVIDER = "提供商"

    /** 0.53 T9: the block is information for every model, so it is never hidden. */
    fun thinkingVisible(draft: ModelDetailDraft): Boolean = true

    /** 0.53 T9: only a thinking-capable model can change the switch and the effort. */
    fun thinkingEditable(draft: ModelDetailDraft): Boolean = draft.thinkingSupported

    fun originLabel(entry: ModelEntry): String = when (entry.origin) {
        ModelOrigin.GATEWAY -> ORIGIN_GATEWAY
        ModelOrigin.MANUAL -> ORIGIN_MANUAL
    }

    fun effortLabel(effort: ThinkingEffort): String = when (effort) {
        ThinkingEffort.LOW -> EFFORT_LOW
        ThinkingEffort.HIGH -> EFFORT_HIGH
        ThinkingEffort.MAX -> EFFORT_MAX
    }

    /** 「上次测试：测试通过 · yyyy-MM-dd HH:mm」; the message is the view model's safe code, never a response body. */
    fun testResultText(result: ModelTestResult): String =
        "$TEST_RESULT_PREFIX：${result.message} · ${ModelCatalogUi.lastSyncText(result.testedAtEpochMillis)}"

    /** The long model id's action description; the id itself is data, the wording is copy. */
    fun fullModelIdDescription(modelId: String): String = "$FULL_MODEL_ID $modelId"

    /** The delete confirmation's sentence, parameterised by the model's display name. */
    fun deleteModelText(displayName: String): String = "「$displayName」$DELETE_MODEL_DETAIL"

    /** 「编辑只影响该模型；保存后立即生效。提供商 X」. */
    fun footer(providerName: String): String = "$FOOTER$FOOTER_PROVIDER $providerName"
}

/**
 * 0.52 design §5.6: the model detail page - window/output, supported inputs, thinking, synthetic tests, restore
 * defaults and the delete affordance.
 *
 * Host-free like the other catalog pages: [state] is the one catalog snapshot the view model holds, and every write is
 * a lambda in [actions]. The page reads the stored row once (keyed by the route's model id and the view model's
 * [ModelCatalogUiState.modelEditEpoch]) and keeps the edits local; its back guard is the shell's one guarded exit, so a
 * dirty draft asks 「放弃修改？」 before the route closes. A successful save or delete pops back to the model service.
 *
 * The test section never sends anything on its own: the first tap is the 「测试会发送固定合成内容」 confirmation, then
 * [ModelCatalogActions.testModel] grants the probe consent and the host's `ControlledProfileTester` records the
 * `TEXT_REQUEST`/`IMAGE_REQUEST` evidence. A failure renders only the view model's safe code. With no tester wired, the
 * buttons are disabled and the reason is visible instead of pretending a probe could run.
 */
@Composable
internal fun ModelDetailScreen(
    state: ModelCatalogUiState,
    actions: ModelCatalogActions,
    help: HelpState,
    modelId: String,
    requestPop: () -> Unit = {},
    registerBackGuard: (() -> Unit) -> Unit = {},
) {
    val provider = state.activeProvider
    val stored = provider?.models?.firstOrNull { it.id == modelId }
    val seed = remember(modelId, state.modelEditEpoch) { stored?.let(ModelDetailDraft::from) }
    var draft by remember(modelId, state.modelEditEpoch) { mutableStateOf(seed) }
    var errors by remember(modelId, state.modelEditEpoch) { mutableStateOf(ModelDetailErrors()) }
    var advanced by remember { mutableStateOf(false) }
    var pendingTest by remember { mutableStateOf<Boolean?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }
    var awaitingSave by remember { mutableStateOf(false) }
    var awaitingDelete by remember { mutableStateOf(false) }

    val current = draft
    val dirty = current != null && current != seed
    val dirtyNow by rememberUpdatedState(dirty)
    val pop by rememberUpdatedState(requestPop)
    val attemptLeave: () -> Unit = { if (dirtyNow) discardPrompt = true else pop() }
    BackHandler { attemptLeave() }
    DisposableEffect(Unit) {
        registerBackGuard(attemptLeave)
        onDispose { }
    }

    // A successful update publishes the saved id: consume it exactly once and return to the model service. A stale event
    // (a save completed after this page left) is consumed without popping the fresh page.
    LaunchedEffect(state.savedModelId, awaitingSave) {
        val savedId = state.savedModelId ?: return@LaunchedEffect
        if (awaitingSave && savedId == modelId) {
            awaitingSave = false
            actions.consumeSavedModel()
            pop()
        } else if (!awaitingSave) {
            actions.consumeSavedModel()
        }
    }

    // A confirmed delete removes the row; the page must not stay on a model that no longer exists.
    LaunchedEffect(awaitingDelete, stored, state.confirmation, state.saving) {
        if (awaitingDelete && stored == null && state.confirmation == null && !state.saving) pop()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (provider == null || stored == null || current == null) {
            Text(
                ModelCatalogUi.MODEL_MISSING,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            DetailBody(
                state = state,
                provider = provider,
                stored = stored,
                draft = current,
                errors = errors,
                advanced = advanced,
                help = help,
                onDraft = { next -> draft = next; errors = ModelDetailErrors() },
                onToggleAdvanced = { advanced = !advanced },
                onSave = {
                    val problems = ModelDetailDraft.validate(current)
                    if (problems.any) {
                        errors = problems
                    } else {
                        val changed = runCatching { ModelDetailDraft.toEntry(current, stored) }.getOrElse { failure ->
                            errors = ModelDetailErrors(budget = ModelCatalogUi.saveError(failure))
                            null
                        }
                        if (changed != null) {
                            errors = ModelDetailErrors()
                            awaitingSave = true
                            actions.updateModel(provider.id, stored, changed)
                        }
                    }
                },
                onTest = { withImage -> pendingTest = withImage },
                onRestore = { restoreConfirm = true },
                onDelete = { actions.deleteModel(modelId) },
            )
        }
    }

    if (state.testAvailable && pendingTest != null && stored != null) {
        val withImage = requireNotNull(pendingTest)
        AlertDialog(
            onDismissRequest = { pendingTest = null },
            title = { Text(ModelDetailUi.TEST_CONFIRM_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ModelDetailUi.TEST_CONFIRM_TEXT)
                    Text(
                        ModelDetailUi.TEST_CONFIRM_DETAIL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingTest = null
                    actions.testModel(modelId, withImage)
                }) { Text(ModelDetailUi.TEST_CONFIRM_ACTION) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTest = null }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
    }

    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text(ModelDetailUi.RESTORE_TITLE) },
            text = { Text(ModelDetailUi.RESTORE_DETAIL) },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirm = false
                    actions.restoreDefaults(modelId)
                }) { Text(ModelDetailUi.RESTORE_ACTION) }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirm = false }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
    }

    (state.confirmation as? CatalogConfirmation.DeleteModel)?.takeIf { it.modelId == modelId }?.let { pending ->
        AlertDialog(
            onDismissRequest = { actions.cancelPending() },
            title = { Text(ModelCatalogUi.DELETE_MODEL_TITLE) },
            text = { Text(ModelDetailUi.deleteModelText(pending.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    awaitingDelete = true
                    actions.confirmPending(null)
                }) { Text(ModelCatalogUi.DELETE) }
            },
            dismissButton = {
                TextButton(onClick = { actions.cancelPending() }) { Text(ModelCatalogUi.RESET_CANCEL) }
            },
        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    state: ModelCatalogUiState,
    provider: Provider,
    stored: ModelEntry,
    draft: ModelDetailDraft,
    errors: ModelDetailErrors,
    advanced: Boolean,
    help: HelpState,
    onDraft: (ModelDetailDraft) -> Unit,
    onToggleAdvanced: () -> Unit,
    onSave: () -> Unit,
    onTest: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.notice?.let { notice ->
        Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ModelDetailUi.MODEL_ID, style = MaterialTheme.typography.labelMedium)
        LongValueText(
            stored.id,
            description = ModelDetailUi.fullModelIdDescription(stored.id),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CatalogChip(ModelDetailUi.originLabel(stored))
        if (stored.missing) CatalogChip(ModelCatalogUi.MISSING_CHIP)
    }

    OutlinedTextField(
        value = draft.displayName,
        onValueChange = { value ->
            onDraft(draft.copy(displayName = value))
        },
        label = { Text(ModelDetailUi.DISPLAY_NAME) },
        singleLine = true,
        isError = errors.displayName != null,
        supportingText = errors.displayName?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = ModelDetailUi.DISPLAY_NAME_INPUT },
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ModelDetailUi.WINDOW, style = MaterialTheme.typography.labelMedium)
        HelpIcon(HelpContent.MODEL_CONTEXT_WINDOW, help)
    }
    OutlinedTextField(
        value = draft.maxInputK,
        onValueChange = { value -> onDraft(draft.copy(maxInputK = value)) },
        label = { Text(ModelDetailUi.K_TOKENS) },
        singleLine = true,
        isError = errors.window != null,
        supportingText = errors.window?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = ModelDetailUi.WINDOW_INPUT },
    )
    PresetRow(selected = draft.maxInputK) { value -> onDraft(draft.copy(maxInputK = value)) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ModelDetailUi.OUTPUT, style = MaterialTheme.typography.labelMedium)
        HelpIcon(HelpContent.MODEL_OUTPUT, help)
    }
    OutlinedTextField(
        value = draft.maxOutputK,
        onValueChange = { value -> onDraft(draft.copy(maxOutputK = value)) },
        label = { Text(ModelDetailUi.K_TOKENS) },
        singleLine = true,
        isError = errors.output != null,
        supportingText = errors.output?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = ModelDetailUi.OUTPUT_INPUT },
    )
    PresetRow(selected = draft.maxOutputK) { value -> onDraft(draft.copy(maxOutputK = value)) }
    errors.budget?.let { budget ->
        Text(budget, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ModelDetailUi.INPUTS, style = MaterialTheme.typography.labelMedium)
        HelpIcon(HelpContent.MODEL_INPUT_IMAGE, help)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            ModelDetailUi.INPUT_TEXT,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = true, onCheckedChange = null, enabled = false)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ModelDetailUi.IMAGE, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = InputModality.IMAGE in draft.inputs,
            onCheckedChange = { checked ->
                onDraft(
                    draft.copy(
                        inputs = if (checked) {
                            draft.inputs + InputModality.IMAGE
                        } else {
                            draft.inputs - InputModality.IMAGE
                        },
                    ),
                )
            },
            modifier = Modifier.semantics { contentDescription = ModelDetailUi.IMAGE_SWITCH },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ModelDetailUi.AUDIO, style = MaterialTheme.typography.bodyMedium)
            Text(
                ModelDetailUi.AUDIO_DISABLED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = ModelDetailUi.AUDIO_ENABLED,
            onCheckedChange = null,
            enabled = false,
            // `Switch` only publishes `Disabled` through the toggleable semantics it installs for a change handler;
            // with no handler the switch is inert but would still be announced as an enabled control. State the
            // 0.52 reservation's disabled state explicitly so accessibility matches what the row says and does.
            modifier = Modifier.semantics {
                contentDescription = ModelDetailUi.AUDIO_SWITCH
                disabled()
            },
        )
    }

    // 0.53 T9: the block is always drawn. A non-DeepSeek model gets the disabled switch and the reason instead of a
    // silently missing section.
    val thinkingEditable = ModelDetailUi.thinkingEditable(draft)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ModelDetailUi.THINKING, style = MaterialTheme.typography.labelMedium)
        HelpIcon(HelpContent.MODEL_THINKING, help)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            ModelDetailUi.THINKING_SWITCH,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            // A disabled switch announces its inert state explicitly (see the audio row): with no change handler the
            // toggleable semantics are not installed, so `disabled()` is stated by hand.
            checked = thinkingEditable && draft.thinkingEnabled,
            onCheckedChange = if (thinkingEditable) {
                { checked -> onDraft(draft.copy(thinkingEnabled = checked)) }
            } else {
                null
            },
            enabled = thinkingEditable,
            modifier = Modifier.semantics {
                contentDescription = ModelDetailUi.THINKING_SWITCH
                if (!thinkingEditable) disabled()
            },
        )
    }
    if (thinkingEditable) {
        Text(
            ModelDetailUi.THINKING_SUPPORTED_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(ModelDetailUi.THINKING_EFFORT, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThinkingEffort.values().forEach { effort ->
                FilterChip(
                    selected = draft.thinkingEffort == effort,
                    onClick = { onDraft(draft.copy(thinkingEffort = effort)) },
                    label = { Text(ModelDetailUi.effortLabel(effort)) },
                )
            }
        }
    } else {
        Text(
            ModelDetailUi.THINKING_ONLY_DEEPSEEK,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(onClick = onToggleAdvanced) {
        Text(if (advanced) "${ModelDetailUi.COLLAPSE}${ModelDetailUi.ADVANCED}" else ModelDetailUi.ADVANCED)
    }
    if (advanced) {
        // 0.53 T8: a derived model shows the marker checked and locked, with the derivation named; only a model the
        // family rule does not cover keeps the editable escape hatch.
        val derivedThinking = ModelFamilies.isDeepSeekFamily(stored.id)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ModelDetailUi.THINKING_MARK,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = derivedThinking || draft.thinkingSupported,
                // A locked derived capability still needs to announce "checked, disabled": `enabled=false` with no
                // handler would drop the toggle state entirely, so the inert handler is what installs it.
                onCheckedChange = if (derivedThinking) {
                    { }
                } else {
                    { checked ->
                        // Un-marking a thinking model also drops the enabled flag; it can no longer be projected.
                        onDraft(
                            draft.copy(
                                thinkingSupported = checked,
                                thinkingEnabled = checked && draft.thinkingEnabled,
                            ),
                        )
                    }
                },
                enabled = !derivedThinking,
                modifier = Modifier.semantics { contentDescription = ModelDetailUi.THINKING_MARK },
            )
        }
        Text(
            if (derivedThinking) ModelDetailUi.THINKING_MARK_DERIVED_DETAIL else ModelDetailUi.THINKING_MARK_DETAIL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(ModelDetailUi.TEST_TITLE, style = MaterialTheme.typography.labelMedium)
    if (!state.testAvailable) {
        Text(
            ModelDetailUi.TEST_UNAVAILABLE_REASON,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    state.lastTest?.takeIf { it.modelId == stored.id }?.let { result ->
        Text(
            ModelDetailUi.testResultText(result),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onTest(false) }, enabled = state.testAvailable && !state.busy) {
            Text(ModelDetailUi.TEST_TEXT)
        }
        if (InputModality.IMAGE in draft.inputs) {
            OutlinedButton(onClick = { onTest(true) }, enabled = state.testAvailable && !state.busy) {
                Text(ModelDetailUi.TEST_IMAGE)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.weight(1f)) {
            Text(SettingsPageUi.SAVE)
        }
        OutlinedButton(onClick = onRestore, enabled = !state.saving) {
            Text(ModelDetailUi.RESTORE)
        }
    }
    OutlinedButton(onClick = onDelete, enabled = !state.saving) {
        Text(ModelDetailUi.DELETE_MODEL, color = MaterialTheme.colorScheme.error)
    }
    Text(
        ModelDetailUi.footer(provider.name),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            ModelDetailUi.PRESET,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelDetailUi.PRESETS.forEach { k ->
            FilterChip(
                selected = selected == k.toString(),
                onClick = { onSelect(k.toString()) },
                label = { Text(k.toString()) },
            )
        }
    }
}
