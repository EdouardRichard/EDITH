package com.sheen.rokid.phone.actions.a11y
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val READ_SCREEN = "read_screen"
private const val TAP = "tap"
private const val LONG_PRESS = "long_press"
private const val SCROLL = "scroll"
private const val INPUT_TEXT = "input_text"

private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
private const val NODE_NOT_CLICKABLE = "NODE_NOT_CLICKABLE"
private const val NO_SCREEN_CHANGE = "NO_SCREEN_CHANGE"
private const val ACTION_CHANNEL_UNAVAILABLE = "ACTION_CHANNEL_UNAVAILABLE"

/** Bounded on purpose: this moves another app's UI, not a document-typing service. */
private const val MAX_TEXT_CHARS = 100

private val argumentsJson = Json { isLenient = true }

private val DESCRIPTORS: List<ActionDescriptor> = listOf(
    ActionDescriptor(
        name = READ_SCREEN,
        title = "读取当前屏幕内容",
        description = "读取当前屏幕，返回按索引编号的元素列表（文本与 clickable/editable/scrollable 等标记），供 tap、long_press、input_text 使用。只想快速看一眼、或准备紧接着做一次索引动作时用它：一次 read_screen 之后索引才有效；需要 ref 引用、截图、等待界面稳定、读取通知/媒体/剪贴板等多来源，或一次读取后连续多步操作时用 device。filter 取 interactive（默认，只列可交互项）、text 或 all，all 输出最大。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，不要改用 shell 模拟点击，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"filter":{"type":"string","enum":["all","interactive","text"],"default":"interactive"}},"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = TAP,
        title = "点击指定索引的元素",
        description = "点击最近一次 read_screen 列表中的第 index 个元素（index 必须来自本回合上一次 read_screen；滚动后旧快照失效，失效或越界返回 ARGUMENTS_INVALID）。只做一次点击、不需要引用或验证时用它；需要按 ref 操作、连续多步、或点完还要等待/校验界面时用 device 的 act（kind=tap）。界面不接受点击返回 NODE_NOT_CLICKABLE，接受但屏幕没变化返回 NO_SCREEN_CHANGE；失败后重新 read_screen 再决定，不要重复同一 index。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"index":{"type":"integer"}},"required":["index"],"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = LONG_PRESS,
        title = "长按指定索引的元素",
        description = "长按最近一次 read_screen 列表中的第 index 个元素（index 来自本回合上一次 read_screen；滚动后旧快照失效，失效或越界返回 ARGUMENTS_INVALID）。用于呼出上下文菜单、多选、拖动前抓取等长按手势；普通单击用 tap，需要 ref、连续多步或长按后还要验证时用 device 的 act（kind=long_press）。界面不接受返回 NODE_NOT_CLICKABLE，接受但屏幕没变化返回 NO_SCREEN_CHANGE；失败后重新 read_screen 再决策，不要重复同一 index。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"index":{"type":"integer"}},"required":["index"],"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = SCROLL,
        title = "滚动屏幕",
        description = "按 direction 滚动当前界面，direction 只能是 forward 或 backward。用于一屏内翻页浏览列表、文章；需要滚到指定文字（scroll_to_text）、按 ref 操作，或滚动后继续多步操作时用 device 的 act（kind=scroll/scroll_to_text）。已到边界返回 NO_SCREEN_CHANGE；滚动会让之前的 read_screen 索引失效，继续索引动作前要重新 read_screen。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"direction":{"type":"string","enum":["forward","backward"]}},"required":["direction"],"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = INPUT_TEXT,
        title = "在指定索引的输入框输入文字",
        description = "在最近一次 read_screen 列表中的第 index 个可编辑元素输入 text（index 来自本回合上一次 read_screen，滚动后旧快照失效；text 不能为空、不能含换行、最多 100 个字符，否则返回 ARGUMENTS_INVALID）。用于搜索词、名称、号码等短文本；需要按 ref 定位、输入长文本或输入后还要等待/校验时用 device 的 act（kind=type）。元素不可编辑返回 NODE_NOT_CLICKABLE，写入后屏幕没变化返回 NO_SCREEN_CHANGE；失败后重新 read_screen，不要重复同一 index。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"index":{"type":"integer"},"text":{"type":"string"}},"required":["index","text"],"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
)

/** Scroll direction for semantic scroll actions. */
enum class ScrollDirection { FORWARD, BACKWARD }

/**
 * What an accessibility service can do here, as a port.
 *
 * The port exists because P0's T3b probe learned two things that must stay testable without a device: the node that
 * carries a text is usually **not** the clickable one (its ancestor is), and "the action returned true" is not success.
 * The Android implementation walks the real node tree; the JVM tests answer from a fake.
 *
 * Extended in T1.3 with snapshot-based semantic actions: the agent loop reads a snapshot, reasons about indices,
 * then issues actions by index rather than by text matching. This fixes the substring-matching defect in [clickText]
 * and removes the dependency on [findFocus] for text entry.
 */
interface AccessibilityUi {
    /** Clicks the nearest clickable ancestor of the first node carrying [text]. `false` when there is none. */
    fun clickText(text: String): Boolean

    /** Sets the text of the focused editable node. `false` when nothing focusable/editable is there. */
    fun setText(text: String): Boolean

    /** A digest of what is currently on screen, used only to compare before and after - never logged, never stored. */
    fun screenFingerprint(): String

    /** Returns a snapshot of the current screen using the given filter. */
    fun snapshot(filter: ScreenFilter): ScreenSnapshot

    /** Clicks the node at [index] in the snapshot identified by [snapshotId]. Returns false if not clickable or stale. */
    fun clickByIndex(snapshotId: String, index: Int): Boolean

    /** Long-presses the node at [index]. Returns false if not long-clickable or stale. */
    fun longPressByIndex(snapshotId: String, index: Int): Boolean

    /** Scrolls in the given direction. Returns false when at boundary. */
    fun scroll(direction: ScrollDirection): Boolean

    /** Sets text on the editable node at [index]. Returns false if not editable or stale. */
    fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean

    /** Structural fingerprint with node counts and truncation flag. */
    fun structuralFingerprint(): ScreenFingerprint
}

/**
 * The accessibility channel: cross-app semantic UI operations (spec §17.2/§17.3).
 *
 * Success is **the screen changing**, not the action being accepted. T3b's first probe version judged success by
 * "the target text disappeared", which was false for a navigation whose destination page carried the same title; 20/20
 * real clicks were reported as failures. The fingerprint comparison is that correction.
 *
 * Two refusals are distinguished on purpose, because they mean different things to whoever reads the audit line:
 * [NODE_NOT_CLICKABLE] (the UI did not offer the action) and [NO_SCREEN_CHANGE] (the action was accepted and nothing
 * happened). Neither is a success, and neither is an exception.
 *
 * T1.5: Replaced tap_text/type_text with five index-based tools (read_screen, tap, long_press, scroll, input_text).
 * Write actions use fingerprint comparison for success determination. read_screen serializes the snapshot as a
 * numbered element list for model consumption.
 */
class AccessibilityExecutionBackend(
    private val ui: AccessibilityUi,
    private val connected: () -> Boolean,
) : ExecutionBackend {

    override val id = "accessibility"

    override fun available(): Boolean = runCatching { connected() }.getOrDefault(false)

    override fun descriptors(): List<ActionDescriptor> = DESCRIPTORS

    /**
     * Tracks the latest snapshot for index-based actions. Set by read_screen, consumed by tap/long_press/input_text;
     * cleared by scroll and by the turn boundary, because the descriptor contract is one snapshot per turn.
     */
    private var currentSnapshot: ScreenSnapshot? = null

    /**
     * edith-0.3 Phase 4A close fix: ends the turn that owns [currentSnapshot].
     *
     * The five descriptors promise that an index comes from "本回合上一次 read_screen". The snapshot used to be
     * process-scoped - only a successful scroll cleared it - so a later assistant turn could act on a screen the model
     * had not read in that turn. The production path is `ToolSurface.beginTurn()` -> the atomic adapter's turn seam ->
     * this method, called by the plugin at the start of every turn. It is deliberately **not** on [ExecutionBackend]:
     * the legacy probe contract does not widen, and a backend without turn state pays nothing.
     */
    internal fun beginTurn() {
        currentSnapshot = null
    }

    override fun execute(name: String, argsJson: String): ActionResult {
        if (!available()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        return when (name) {
            READ_SCREEN -> executeReadScreen(argsJson)
            TAP -> executeTap(argsJson)
            LONG_PRESS -> executeLongPress(argsJson)
            SCROLL -> executeScroll(argsJson)
            INPUT_TEXT -> executeInputText(argsJson)
            else -> refused(UNKNOWN_ACTION)
        }
    }

    // ── read_screen ──────────────────────────────────────────────────────────

    private fun executeReadScreen(argsJson: String): ActionResult {
        val filter = parseFilter(argsJson)
        val snapshot = runCatching { ui.snapshot(filter) }.getOrElse { return refused(ACTION_CHANNEL_UNAVAILABLE) }
        currentSnapshot = snapshot
        val serialized = serializeSnapshot(snapshot)
        return done(serialized)
    }

    private fun parseFilter(argsJson: String): ScreenFilter {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return ScreenFilter.INTERACTIVE
        val filterStr = (args["filter"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return ScreenFilter.INTERACTIVE
        return when (filterStr.lowercase()) {
            "all" -> ScreenFilter.ALL
            "text" -> ScreenFilter.TEXT
            "interactive" -> ScreenFilter.INTERACTIVE
            else -> ScreenFilter.INTERACTIVE
        }
    }

    /**
     * Serializes a [ScreenSnapshot] into a compact numbered list format for model consumption.
     * Example:
     * ```
     * [0] "Settings" clickable
     * [1] "Search" editable
     * [2] "Wi-Fi" clickable checked
     * truncated: false
     * ```
     */
    private fun serializeSnapshot(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        for (element in snapshot.elements) {
            sb.append("[${element.index}]")
            if (element.text != null) {
                sb.append(" \"${element.text}\"")
            }
            val traits = mutableListOf<String>()
            if (element.clickable) traits += "clickable"
            if (element.editable) traits += "editable"
            if (element.scrollable) traits += "scrollable"
            if (element.checked) traits += "checked"
            if (element.isHintText) traits += "hint"
            if (traits.isNotEmpty()) {
                sb.append(" ")
                sb.append(traits.joinToString(" "))
            }
            sb.append("\n")
        }
        sb.append("truncated: ${snapshot.truncated}")
        return sb.toString().trimEnd()
    }

    // ── tap ──────────────────────────────────────────────────────────────────

    private fun executeTap(argsJson: String): ActionResult {
        val index = parseIndex(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val snapshot = currentSnapshot ?: return refused(ARGUMENTS_INVALID)
        if (index < 0 || index >= snapshot.elements.size) return refused(ARGUMENTS_INVALID)

        val before = runCatching { ui.screenFingerprint() }.getOrDefault("")
        val accepted = ui.clickByIndex(snapshot.snapshotId, index)
        if (!accepted) return refused(NODE_NOT_CLICKABLE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) done("tapped index $index") else refused(NO_SCREEN_CHANGE)
    }

    // ── long_press ───────────────────────────────────────────────────────────

    private fun executeLongPress(argsJson: String): ActionResult {
        val index = parseIndex(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val snapshot = currentSnapshot ?: return refused(ARGUMENTS_INVALID)
        if (index < 0 || index >= snapshot.elements.size) return refused(ARGUMENTS_INVALID)

        val before = runCatching { ui.screenFingerprint() }.getOrDefault("")
        val accepted = ui.longPressByIndex(snapshot.snapshotId, index)
        if (!accepted) return refused(NODE_NOT_CLICKABLE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) done("long pressed index $index") else refused(NO_SCREEN_CHANGE)
    }

    // ── scroll ───────────────────────────────────────────────────────────────

    private fun executeScroll(argsJson: String): ActionResult {
        val direction = parseDirection(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val accepted = ui.scroll(direction)
        if (!accepted) return refused(NO_SCREEN_CHANGE)
        // Scroll invalidates the current snapshot
        currentSnapshot = null
        return done("scrolled ${direction.name.lowercase()}")
    }

    private fun parseDirection(argsJson: String): ScrollDirection? {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return null
        val dir = (args["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return when (dir.lowercase()) {
            "forward" -> ScrollDirection.FORWARD
            "backward" -> ScrollDirection.BACKWARD
            else -> null
        }
    }

    // ── input_text ───────────────────────────────────────────────────────────

    private fun executeInputText(argsJson: String): ActionResult {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return refused(ARGUMENTS_INVALID)
        val index = (args["index"] as? JsonPrimitive)?.intOrNull ?: return refused(ARGUMENTS_INVALID)
        val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return refused(ARGUMENTS_INVALID)
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT_CHARS) return refused(ARGUMENTS_INVALID)
        if (trimmed.any { it == '\n' || it == '\r' }) return refused(ARGUMENTS_INVALID)

        val snapshot = currentSnapshot ?: return refused(ARGUMENTS_INVALID)
        if (index < 0 || index >= snapshot.elements.size) return refused(ARGUMENTS_INVALID)

        val before = runCatching { ui.screenFingerprint() }.getOrDefault("")
        val accepted = ui.setTextByIndex(snapshot.snapshotId, index, trimmed)
        if (!accepted) return refused(NODE_NOT_CLICKABLE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) done("typed into index $index") else refused(NO_SCREEN_CHANGE)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun parseIndex(argsJson: String): Int? {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return null
        return (args["index"] as? JsonPrimitive)?.intOrNull
    }

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
}
