package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.ModelListError
import com.sheen.rokid.phone.model.catalog.CatalogValidation
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelField
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.model.catalog.RefreshCounts
import com.sheen.rokid.plugin.InputModality
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 0.52 design §§5.4/5.9/5.10: everything the three catalog pages draw, and the single place a catalog error becomes
 * Chinese copy.
 *
 * The state is a snapshot, never a second store: [catalog] is what the encrypted repository last answered, the other
 * fields are page-only decoration (spinners, the retained refresh summary, the pending confirmation). Loading/saving
 * flags deliberately live here and never reach the repository - a fetch in flight is not catalog content.
 *
 * No state field ever carries the API key. [keyMask] is the save-time mask (`sk-••••{last4}`) only; errors and notices
 * are built from the fixed strings in [ModelCatalogUi] and never interpolate a URL, key or response body.
 */
internal data class ModelCatalogUiState(
    val catalog: ModelCatalog? = null,
    val activeProvider: Provider? = null,
    val selectedModel: ModelEntry? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val refreshSummary: String? = null,
    val lastTest: ModelTestResult? = null,
    val corrupt: Boolean = false,
    val confirmation: CatalogConfirmation? = null,
    val keyMask: Map<String, String> = emptyMap(),
    val savedProviderId: String? = null,
    val warning: String? = null,
    val testAvailable: Boolean = true,
) {
    /** The active provider's rows, in stored order; empty until a provider is active. */
    val models: List<ModelEntry> get() = activeProvider?.models.orEmpty()

    /** True while a catalog write or fetch is running; the page disables its actions. */
    val busy: Boolean get() = loading || saving
}

/** The last synthetic probe's outcome for the detail page (Task 14) and this page's optional test action. */
internal data class ModelTestResult(
    val modelId: String,
    val image: Boolean,
    val passed: Boolean,
    val message: String,
    val testedAtEpochMillis: Long = 0,
)

/**
 * A catalog change that stops the running session, raised by tapping a row rather than by a form save, so the page
 * must ask before the write. The copy is [ModelCatalogUi.SWITCH_CONFIRM]; [newActiveId] is the successor chosen when
 * deleting the active provider (null until the dialog supplies one).
 */
internal sealed interface CatalogConfirmation {
    data class SwitchProvider(val providerId: String, val providerName: String) : CatalogConfirmation
    data class DeleteProvider(val providerId: String, val providerName: String, val newActiveId: String?) :
        CatalogConfirmation
    data class DeleteModel(val modelId: String, val displayName: String) : CatalogConfirmation
}

/**
 * One provider form submission (0.52 design §5.10).
 *
 * [apiKey] is the caller's mutable buffer: the repository stages it and the view model zeroes it, so the compose form
 * never keeps a second copy. `null` means "keep the stored credential" and is only legal when [providerId] names an
 * existing provider. A new provider gets a UUID at the view-model boundary (ruling R16).
 */
internal data class ProviderDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: CharArray? = null,
    val protocolId: String = "OPENAI_RESPONSES",
    val timeoutMillis: Long = 30_000,
    val providerId: String? = null,
)

/** One manual model as the add dialog edits it: the window/output are edited in K tokens, stored in tokens. */
internal data class ManualModelDraft(
    val id: String,
    val displayName: String,
    val contextWindowK: String,
    val maxOutputK: String,
    val inputs: Set<InputModality>,
)

/**
 * The page action bundle. The shell binds every lambda to [ModelCatalogViewModel]; the pages only call these, so a
 * Compose test can drive them with a counter and no host.
 */
internal data class ModelCatalogActions(
    val refresh: () -> Unit = {},
    val saveProvider: (ProviderDraft) -> Unit = {},
    /** Opens the delete confirmation; the successor for an active provider is chosen in the dialog. */
    val deleteProvider: (String) -> Unit = {},
    val setActiveProvider: (String) -> Unit = {},
    val addManualModel: (ManualModelDraft) -> Unit = {},
    val updateModel: (ModelEntry, ModelEntry) -> Unit = { _, _ -> },
    val deleteModel: (String) -> Unit = {},
    val selectModel: (String) -> Unit = {},
    val restoreDefaults: (String) -> Unit = {},
    val testModel: (String, Boolean) -> Unit = { _, _ -> },
    val resetCatalog: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val dismissRefreshSummary: () -> Unit = {},
    /** Confirms the open [CatalogConfirmation]; [newActiveId] is the successor chosen for an active-provider delete. */
    val confirmPending: (String?) -> Unit = {},
    val cancelPending: () -> Unit = {},
    /** Clears the "saved" event after the editor has left. */
    val consumeSaved: () -> Unit = {},
)

/**
 * 0.52 design §§4.3/5.4/5.10: the catalog pages' fixed copy and the one central error mapping.
 *
 * Every string a page can show for a technical failure comes from here. Nothing interpolates a URL, an API key or a
 * gateway response body - the mapped sentence is all the user sees.
 */
internal object ModelCatalogUi {

    const val UNAVAILABLE = "模型服务不可用"
    const val UNAVAILABLE_DETAIL = "本机的模型目录或网关访问未装配，请重新启动 App 后重试。"

    const val EMPTY_TITLE = "尚未配置模型服务"
    const val EMPTY_DETAIL = "添加服务提供商后即可填写名称、网关 Base URL 与 API Key，并获取模型列表。"
    const val ADD_PROVIDER = "添加提供商"
    const val NO_MODELS_TITLE = "尚未获取模型"
    const val NO_MODELS_DETAIL = "点「获取模型」从网关拉取；网关不支持时也可以手动新增。"
    const val FETCH_MODELS = "获取模型"
    const val FETCHING = "获取中…"
    const val ADD_MODEL = "新增模型"
    const val NO_MODEL_SELECTED = "尚未选择模型，请在上方列表中选择一个。"
    const val SWITCH_PROVIDER = "切换提供商"
    const val ACTIVE_CHIP = "生效中"
    const val KEY_UNKNOWN = "sk-••••"

    const val PROVIDER_LIST_TITLE = "服务提供商"
    const val PROVIDER_LIST_DETAIL = "可添加多个提供商；同一时刻只有一个全局生效。"
    const val EDIT = "编辑"
    const val DELETE = "删除"
    const val SAVE_AND_FETCH = "保存并获取模型"
    const val NAME = "名称"
    const val BASE_URL = "网关 Base URL"
    const val API_KEY = "API Key"
    const val ADVANCED = "高级"
    const val PROTOCOL = "协议"
    const val TIMEOUT = "超时（毫秒）"
    const val RESPONSES = "Responses"
    const val CHAT_COMPLETIONS = "Chat Completions"
    const val KEY_KEEP = "留空则保留已保存的 Key"

    const val RESET_TITLE = "模型目录无法读取"
    const val RESET_DETAIL = "加密的模型目录已损坏或版本不受支持。重置只会删除模型目录，不会删除其他设置。"
    const val RESET = "重置模型目录"
    const val RESET_CONFIRM = "重置"
    const val RESET_CANCEL = "取消"

    const val SELECT_NEEDS_TEST = "请先在模型详情测试连接"
    const val TEXT_ONLY_DISABLED = "不支持图片，助手不可用"
    const val MISSING_SELECTED = "当前选择的模型已不存在，请重新选择模型"
    const val MISSING_GATEWAY = "当前选择的模型已被网关移除，建议重新选择模型"
    const val PROVIDER_MISSING = "提供商不存在，请返回后重新进入"
    const val MODEL_MISSING = "模型不存在，请刷新后重试"
    const val ADD_PROVIDER_FIRST = "请先添加提供商"
    const val KEY_REQUIRED = "API Key 不能为空"
    const val MISSING_SECRET = "缺少 API Key，请重新保存提供商"
    const val NEW_ACTIVE_REQUIRED = "请先选择新的生效提供商"
    const val MODEL_ID_IMMUTABLE = "模型 ID 不可修改"
    const val TEST_UNAVAILABLE = "模型测试不可用"
    const val TESTING = "测试中…"
    const val TEST_PASSED = "测试通过"
    const val IMAGE_INPUT_MISSING = "该模型未声明图片输入"
    const val RESET_DONE = "模型目录已重置"
    const val NO_SECRET = "该提供商还没有保存 API Key。"

    fun savedProvider(name: String): String = "已保存「$name」"
    fun switchedTo(name: String): String = "已切换到 $name"
    fun selectedModel(name: String): String = "已选择「$name」"
    fun addedModel(name: String): String = "已新增模型「$name」"
    fun deletedProvider(name: String): String = "已删除提供商「$name」"
    fun deletedModel(name: String): String = "已删除模型「$name」"
    fun restoredDefaults(name: String): String = "已恢复「$name」的默认值"
    const val SWITCH_CONFIRM = "切换会先停止当前会话，之后需重新开始"
    const val SWITCH_CONFIRM_TITLE = "切换服务提供商？"
    const val SWITCH_CONFIRM_ACTION = "切换"
    const val DELETE_PROVIDER_TITLE = "删除服务提供商？"
    const val DELETE_PROVIDER_ACTION = "删除"
    const val DELETE_MODEL_TITLE = "删除模型？"
    const val CHOOSE_NEW_ACTIVE = "请选择继续生效的服务提供商"
    const val DISMISS = "关闭"
    const val COPY = "复制"
    const val COPIED = "已复制"
    const val FULL_VALUE_TITLE = "完整内容"
    const val SYNC_NEVER = "尚未同步"
    const val SUMMARY_DISMISS = "关闭刷新结果"
    const val WARNING_CHIP = "未验证"
    const val IMAGE_UNVERIFIED = "图片未验证"
    const val VERIFIED_CHIP = "已验证"
    const val MANUAL_CHIP = "手动"
    const val THINKING_CHIP = "深度思考"
    const val MISSING_CHIP = "网关已移除"
    const val MODEL_COUNT = "模型"
    const val LAST_SYNC = "上次同步"

    const val FOOTER = "切换提供商后，模型目录与凭证随之切换；仅一个提供商全局生效。"
    const val TRUNCATED = "仅显示前 500 个模型"

    /** 0.52 design §5.4: the five user-visible failure families, mapped once. */
    fun refreshError(error: ModelListError): String = when (error) {
        ModelListError.AUTH -> "API Key 无效或无权限"
        ModelListError.UNSUPPORTED -> "该网关不支持模型列表，请手动新增模型"
        ModelListError.TIMEOUT, ModelListError.NETWORK -> "连接网关超时，请检查 Base URL 与网络"
        ModelListError.INVALID_RESPONSE -> "网关返回格式无法识别，请手动新增模型"
        ModelListError.EMPTY -> "网关未返回模型，请手动新增"
    }

    /**
     * Any catalog write refusal as Chinese copy. A [ContractError.ConfigurationRejected] message is a repository-internal
     * contract string (and can contain English); it is classified here, never shown verbatim.
     */
    fun saveError(error: Throwable): String = when (error) {
        is ContractError.ConfigurationRejected -> when (error.message) {
            "提供商不存在", "Model provider unavailable" -> "提供商不存在，请返回后重新进入"
            "模型不存在" -> "模型不存在，请刷新后重试"
            "缺少 API Key" -> "缺少 API Key，请重新保存提供商"
            "模型的上下文预算无效" -> "模型的上下文预算无效，请调整窗口与输出"
            "尚未选择提供商" -> "请先添加并选择提供商"
            "Storage unavailable; reconfigure", "Model configuration unavailable" ->
                "模型目录无法读取，请重置模型目录"
            else -> "保存失败，请重试"
        }
        else -> "保存失败，请重试"
    }

    /** A probe terminal's safe code, mapped without echoing the response body. */
    fun testFailure(safeCode: String?): String =
        if (safeCode.isNullOrBlank()) "测试失败，请稍后重试" else "测试失败：$safeCode"

    /** 0.52 design §5.5: the retained refresh summary, with the 500-row truncation appended when the transport cut it. */
    fun refreshSummary(counts: RefreshCounts): String = buildString {
        append("新增 ").append(counts.added)
        append(" · 更新 ").append(counts.updated)
        append(" · 未变 ").append(counts.unchanged)
        append(" · 网关已移除 ").append(counts.missing)
        if (counts.truncated) append(" · ").append(TRUNCATED)
    }

    /** The save-time mask; the only key-derived value the state ever sees. */
    fun keyMask(key: CharArray): String {
        val tail = if (key.size > 4) key.copyOfRange(key.size - 4, key.size) else key
        return "sk-••••${String(tail)}"
    }

    /** `xK 输入 · yK 输出` (design §5.2/§5.10). Whole K when divisible, one decimal otherwise. */
    fun budgetText(entry: ModelEntry): String =
        "${kText(entry.contextWindowTokens)} 输入 · ${kText(entry.maxOutputTokens)} 输出"

    fun kText(tokens: Int): String =
        if (tokens % 1024 == 0) "${tokens / 1024}K" else String.format(java.util.Locale.ROOT, "%.1fK", tokens / 1024.0)

    /** Parses a K-tokens field into tokens; null for blank, non-numeric, non-positive or overflowing values. */
    fun parseKTokens(text: String): Int? {
        val k = text.trim().toLongOrNull() ?: return null
        if (k <= 0 || k > Int.MAX_VALUE / 1024L) return null
        return (k * 1024L).toInt()
    }

    /** The stored sync time (local zone), or the never-synced sentence. */
    fun lastSyncText(epochMillis: Long?): String {
        if (epochMillis == null) return SYNC_NEVER
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }

    /** True when [entry] carries current-revision TEXT_REQUEST evidence (the CapabilityGate rule). */
    fun freshTextVerified(entry: ModelEntry, revision: Long): Boolean =
        SettingsSummaries.latestVerified(entry.verifications, revision, SettingsSummaries.TEXT_OPERATION)

    fun imageVerified(entry: ModelEntry, revision: Long): Boolean =
        SettingsSummaries.latestVerified(entry.verifications, revision, "IMAGE_REQUEST")

    /** The 0.52 design §5.10 selectability rule: image-capable, present, and tested at the current revision. */
    fun canSelect(entry: ModelEntry, provider: Provider): Boolean =
        !entry.missing && InputModality.IMAGE in entry.inputs && freshTextVerified(entry, provider.revision)

    /** Why a row's radio is disabled, or null when it is selectable. */
    fun disabledReason(entry: ModelEntry, provider: Provider): String? = when {
        InputModality.IMAGE !in entry.inputs -> TEXT_ONLY_DISABLED
        !freshTextVerified(entry, provider.revision) -> SELECT_NEEDS_TEST
        else -> null
    }

    /** The row chips, in the design's fixed order. */
    fun chips(entry: ModelEntry, provider: Provider): List<String> = buildList {
        if (entry.origin == ModelOrigin.MANUAL) add(MANUAL_CHIP)
        if (entry.missing) add(MISSING_CHIP)
        if (entry.thinkingEnabled && entry.thinkingSupported) add(THINKING_CHIP)
        if (freshTextVerified(entry, provider.revision)) {
            add(VERIFIED_CHIP)
        } else if (InputModality.IMAGE in entry.inputs && !imageVerified(entry, provider.revision)) {
            add(IMAGE_UNVERIFIED)
        }
    }

    /** The provider list's current key mask, or the vendor-shaped placeholder when the key predates this session. */
    fun maskFor(state: ModelCatalogUiState, providerId: String): String =
        state.keyMask[providerId] ?: KEY_UNKNOWN

    /** The add/edit provider form's field validation, shared by the screen and the view model. */
    fun providerNameError(name: String): String? = CatalogValidation.providerNameError(name)

    fun providerBaseUrlError(baseUrl: String, allowLocalHttp: Boolean): String? =
        CatalogValidation.baseUrlError(baseUrl, allowLocalHttp)

    /** The manual model form's field errors, in field order; empty when the row can be built. */
    fun manualModelErrors(draft: ManualModelDraft, existing: Provider?): List<String> = buildList {
        CatalogValidation.modelIdError(draft.id)?.let(::add)
        if (draft.displayName.isBlank()) add("显示名不能为空")
        val window = parseKTokens(draft.contextWindowK)
        val output = parseKTokens(draft.maxOutputK)
        if (window == null) add("上下文窗口需为大于 0 的 K tokens 整数")
        if (output == null) add("最大输出需为大于 0 的 K tokens 整数")
        if (window != null && output != null) CatalogValidation.budgetError(window, output)?.let(::add)
        if (existing?.models?.any { it.id == draft.id } == true) add("模型 ID 已存在")
    }

    /** The fields an edit is allowed to protect from the next gateway refresh (Task 14 reuses this list). */
    val editableFields: Set<ModelField> = setOf(
        ModelField.DISPLAY_NAME,
        ModelField.CONTEXT_WINDOW_TOKENS,
        ModelField.MAX_OUTPUT_TOKENS,
        ModelField.INPUTS,
    )

    /** The derived profile id for a provider/model pair (the confirmation and test seams need it). */
    fun profileId(providerId: String, modelId: String): String =
        com.sheen.rokid.phone.model.catalog.ModelProfileProjection.profileId(providerId, modelId)
}
