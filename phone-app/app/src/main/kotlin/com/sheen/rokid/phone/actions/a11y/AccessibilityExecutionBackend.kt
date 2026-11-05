package com.sheen.rokid.phone.actions.a11y
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.phone.platform.P3Log
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

/**
 * 0.5.4 T11 coordinate codes.
 *
 * [MISSING_POINT] means "the request does not designate a usable on-screen point": one coordinate without the other,
 * a point outside the display, or a display rectangle the channel cannot read. [ARGUMENTS_INVALID] stays the shape
 * error (both targets or neither), exactly as the spec's "两者都缺/都给" rule says.
 */
private const val MISSING_POINT = "MISSING_POINT"
private const val COORD_REFUSED_SYSTEM_UI = "COORD_REFUSED_SYSTEM_UI"
private const val COORD_REFUSED_KEYGUARD = "COORD_REFUSED_KEYGUARD"

/**
 * Foreground packages that are system surfaces, not a target app (0.5.4 T11).
 *
 * A coordinate gesture lands wherever the finger point is; unlike an index click it cannot be attributed to a node, so
 * the channel must prove the app in front is a normal app before injecting it (spec §6.3.3/§8). The status/navigation
 * bar owner, the permission controllers and the package installers are the surfaces the spec names. This list is the
 * package-level witness the existing `AccessibilityPort.foreground()` probe can supply; a system window that does not
 * raise a window-state event is not detectable through this probe and stays out of scope here.
 */
private val SYSTEM_FOREGROUND_PACKAGES: Set<String> = setOf(
    "android",
    "com.android.systemui",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.miui.packageinstaller",
    "com.lbe.security.miui",
)

/** Bounded on purpose: this moves another app's UI, not a document-typing service. */
private const val MAX_TEXT_CHARS = 100

/**
 * How many elements one `read_screen` page renders (0.5.4 T9).
 *
 * The snapshot itself still holds up to [MAX_NODES]; `offset` only slices what is printed, so a 400-node screen is four
 * pages. Pages exist to keep a single tool result model-sized now that every line also carries geometry.
 */
private const val READ_SCREEN_PAGE_SIZE = 100

private val argumentsJson = Json { isLenient = true }

private val DESCRIPTORS: List<ActionDescriptor> = listOf(
    ActionDescriptor(
        name = READ_SCREEN,
        title = "读取当前屏幕内容",
        description = "读取当前屏幕，返回按索引编号的元素列表（文本、contentDescription 回退 desc、bounds=[l,t][r,b] 与 center=(x,y)，以及 clickable/editable/scrollable 等标记），供 tap、long_press、input_text 使用。只想快速看一眼、或准备紧接着做一次索引动作时用它：一次 read_screen 之后索引才有效；需要 ref 引用、截图、等待界面稳定、读取通知/媒体/剪贴板等多来源，或一次读取后连续多步操作时用 device。filter 取 interactive（默认，只列可交互项）、text 或 all，all 输出最大；offset 从第 offset 个元素开始返回下一页（默认 0，每页最多 100 条，必须是 >=0 的整数，负数或非数字返回 ARGUMENTS_INVALID 且不读屏），索引始终是全局的，offset 只切页、不改变 tap 用的 index；同一回合内 offset>0 的后续页复用首次读取的那份快照（翻页看到的是同一屏；若中途派发过 tap、long_press、input_text 或滚动，旧快照失效，下一页会重新读取），filter 改变或本回合还没有快照时才重新读取。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，不要改用 shell 模拟点击，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"filter":{"type":"string","enum":["all","interactive","text"],"default":"interactive"},"offset":{"type":"integer","minimum":0,"default":0}},"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = TAP,
        title = "点击指定索引的元素或屏幕坐标",
        description = "点击最近一次 read_screen 列表中的第 index 个元素，或点击屏幕像素坐标 (x,y)；index 与 x,y 二选一，同时给出或都不给返回 ARGUMENTS_INVALID，只给 x 或只给 y 返回 MISSING_POINT。index 必须来自本回合上一次 read_screen（滚动后旧快照失效，失效或越界返回 ARGUMENTS_INVALID）。坐标优先用 read_screen 已给出的 center，只有元素没有可点击节点时才用坐标。坐标必须落在屏内，屏幕尺寸不可读或点不在屏内返回 MISSING_POINT；锁屏时返回 COORD_REFUSED_KEYGUARD，系统 UI、权限框、安装器等系统界面在前台或无法确认前台 App 时返回 COORD_REFUSED_SYSTEM_UI，这些情况都不会派发手势。只做一次点击、不需要引用或验证时用它；需要按 ref 操作、连续多步、或点完还要等待/校验界面时用 device 的 act（kind=tap）。界面不接受点击返回 NODE_NOT_CLICKABLE，接受但屏幕没变化返回 NO_SCREEN_CHANGE；失败后重新 read_screen 再决定，不要重复同一 index。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"index":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"}},"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = LONG_PRESS,
        title = "长按指定索引的元素或屏幕坐标",
        description = "长按最近一次 read_screen 列表中的第 index 个元素，或长按屏幕像素坐标 (x,y)；index 与 x,y 二选一，同时给出或都不给返回 ARGUMENTS_INVALID，只给 x 或只给 y 返回 MISSING_POINT（index 来自本回合上一次 read_screen，滚动后旧快照失效，失效或越界返回 ARGUMENTS_INVALID）。坐标优先用 read_screen 已给出的 center，只有元素没有可长按节点时才用坐标。坐标必须落在屏内，屏幕尺寸不可读或点不在屏内返回 MISSING_POINT；锁屏时返回 COORD_REFUSED_KEYGUARD，系统 UI、权限框、安装器等系统界面在前台或无法确认前台 App 时返回 COORD_REFUSED_SYSTEM_UI，这些情况都不会派发手势。用于呼出上下文菜单、多选、拖动前抓取等长按手势；普通单击用 tap，需要 ref、连续多步或长按后还要验证时用 device 的 act（kind=long_press）。界面不接受返回 NODE_NOT_CLICKABLE，接受但屏幕没变化返回 NO_SCREEN_CHANGE；失败后重新 read_screen 再决策，不要重复同一 index。无障碍通道掉线时返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"index":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"}},"additionalProperties":false}""",
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

    // ── Coordinate actions (0.5.4 T11) ───────────────────────────────────────
    //
    // The probes below are the existing accessibility channel's own facts, reused rather than re-invented: the Android
    // production answer is the service's `AccessibilityPort.foreground()` / `isKeyguardLocked()` / display metrics.
    // They are defaulted to the **fail-closed** answers on purpose - an implementation that cannot prove a normal
    // unlocked app is in front must never dispatch a blind gesture at a coordinate.

    /** The app in front, as the channel reports it; `null` when unknown (coordinates then fail closed). */
    fun foreground(): Foreground? = null

    /** Keyguard state; the default `true` (locked) keeps a channel that cannot answer from guessing "unlocked". */
    fun isKeyguardLocked(): Boolean = true

    /**
     * Whether the focused window is a normal application window (`TYPE_APPLICATION`), or `null` when the channel cannot
     * tell (0.5.4 T11 fix round).
     *
     * The foreground package is only a package-level witness: a system overlay that never raises a window-state event
     * can stay invisible to it. The focused window's type is the second, type-level witness. The default `null` is
     * fail-closed: an implementation that cannot prove an application window is focused must never dispatch a blind
     * coordinate gesture.
     */
    fun focusedWindowIsApplication(): Boolean? = null

    /** The display rectangle in screen pixels, matching [ScreenBounds]; `null` when the channel cannot tell. */
    fun screenBounds(): ScreenBounds? = null

    /** Dispatches one tap gesture at the screen-pixel point [x],[y]. `false` when no gesture completed. */
    fun clickAt(x: Int, y: Int): Boolean = false

    /** Dispatches one long-press gesture at the screen-pixel point [x],[y]. `false` when no gesture completed. */
    fun longPressAt(x: Int, y: Int): Boolean = false
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
     * The filter [currentSnapshot] was read with (0.5.4 T9 fix round).
     *
     * Spec §6.3.1 says pagination is "within the same snapshot", so an `offset > 0` page may only continue a snapshot
     * read with the same filter. Tracked next to the snapshot and cleared with it, so a stale filter can never pair
     * with a newer snapshot.
     */
    private var currentSnapshotFilter: ScreenFilter? = null

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
        invalidateSnapshot()
    }

    /**
     * Drops [currentSnapshot] and the filter it was read with.
     *
     * Called by the turn boundary, by a successful scroll, and - 0.5.4 T9 fix round 2 - after **every dispatched**
     * `tap`/`long_press`/`input_text`, regardless of the port's boolean. The accessibility service invalidates its own
     * snapshot after any action, so a stale backend copy would let a later `offset > 0` page reuse a tree that predates
     * the mutation and then have its indices refused as `NODE_NOT_CLICKABLE`; clearing forces that page to re-read.
     */
    private fun invalidateSnapshot() {
        currentSnapshot = null
        currentSnapshotFilter = null
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
        val offset = parseOffset(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val filter = parseFilter(argsJson)
        val snapshot = pageSnapshot(filter, offset) ?: return refused(ACTION_CHANNEL_UNAVAILABLE)
        return done(serializeSnapshot(snapshot, offset))
    }

    /**
     * Returns the snapshot an `offset` page belongs to (spec §6.3.1: pagination is "within the same snapshot").
     *
     * `offset > 0` is a continuation of an earlier page, so it **must** describe the screen that earlier page described.
     * When a snapshot from this turn exists and was read with the same [filter], it is reused and `ui.snapshot` is not
     * called again - re-reading could splice a screen the model never saw into page 1 (the fake that hides this is why
     * the 0.5.4 fix round added a changing fake).
     *
     * A fresh read is the documented fallback, used only when there is nothing to continue: `offset == 0` (a new read
     * always starts a new snapshot), no snapshot in this turn, or a different filter (a different node set, so paging
     * the old snapshot would mix two queries). In that fallback the fresh read becomes the snapshot the next page reuses
     * and the screen is read exactly once.
     */
    private fun pageSnapshot(filter: ScreenFilter, offset: Int): ScreenSnapshot? {
        val stored = currentSnapshot
        if (offset > 0 && stored != null && currentSnapshotFilter == filter) return stored
        val fresh = runCatching { ui.snapshot(filter) }.getOrElse { return null }
        currentSnapshot = fresh
        currentSnapshotFilter = filter
        return fresh
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
     * Reads the optional `offset` page start. Absent means the first page; a negative or non-numeric value is a caller
     * error, not something to clamp silently, because a wrong offset would show the wrong slice of the screen.
     */
    private fun parseOffset(argsJson: String): Int? {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return 0
        val raw = args["offset"] ?: return 0
        val offset = (raw as? JsonPrimitive)?.intOrNull ?: return null
        return offset.takeIf { it >= 0 }
    }

    /**
     * Serializes a [ScreenSnapshot] into a compact numbered list format for model consumption.
     * Example:
     * ```
     * [0] "Settings" bounds=[0,0][100,50] center=(50,25) clickable
     * [1] desc="搜索设置项" bounds=[900,120][1056,240] center=(978,180) clickable
     * [2] "Wi-Fi" bounds=[0,50][100,100] center=(50,75) clickable checked
     * truncated: false
     * ```
     *
     * [offset] slices the rendered page only. [ScreenElement.index] stays global, so an index printed on any page is
     * still the index `tap`/`long_press`/`input_text` accept.
     */
    private fun serializeSnapshot(snapshot: ScreenSnapshot, offset: Int): String {
        val sb = StringBuilder()
        for (element in snapshot.elements.drop(offset).take(READ_SCREEN_PAGE_SIZE)) {
            sb.append("[${element.index}]")
            if (!element.text.isNullOrBlank()) {
                sb.append(" \"${element.text}\"")
            }
            // contentDescription is the fallback label: shown only when it adds something the text does not already say.
            val desc = element.desc
            if (!desc.isNullOrBlank() && desc != element.text) {
                sb.append(" desc=\"$desc\"")
            }
            val bounds = element.bounds
            sb.append(" bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            sb.append(" center=(${element.center.x},${element.center.y})")
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

    // ── tap / long_press (index or coordinate target) ────────────────────────

    private fun executeTap(argsJson: String): ActionResult = executeClickTarget(argsJson, longPress = false)

    private fun executeLongPress(argsJson: String): ActionResult = executeClickTarget(argsJson, longPress = true)

    /**
     * 0.5.4 T11 (§6.3.3): `tap`/`long_press` take an index **or** a screen-pixel point, mutually exclusive.
     *
     * The shape is settled before any UI call, because a malformed target is a caller error, not something to guess at:
     * both targets or neither is [ARGUMENTS_INVALID] (the spec pins that case verbatim), while a half-given point or an
     * unusable point is [MISSING_POINT]. Index dispatch stays byte-for-byte the pre-T11 path; the coordinate branch adds
     * the foreground/keyguard/on-screen gate and reports `via=coord` on the count-only action line.
     */
    private fun executeClickTarget(argsJson: String, longPress: Boolean): ActionResult =
        when (val target = parseClickTarget(argsJson)) {
            is ClickTarget.Index -> executeIndexClick(target.index, longPress)
            is ClickTarget.Point -> executePointClick(target.x, target.y, longPress)
            is ClickTarget.Refused -> refused(target.code)
        }

    private sealed interface ClickTarget {
        data class Index(val index: Int) : ClickTarget
        data class Point(val x: Int, val y: Int) : ClickTarget
        data class Refused(val code: String) : ClickTarget
    }

    private fun parseClickTarget(argsJson: String): ClickTarget {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return ClickTarget.Refused(ARGUMENTS_INVALID)
        val hasIndex = args.containsKey("index")
        val hasX = args.containsKey("x")
        val hasY = args.containsKey("y")
        if (hasIndex && (hasX || hasY)) return ClickTarget.Refused(ARGUMENTS_INVALID)
        if (!hasIndex && !hasX && !hasY) return ClickTarget.Refused(ARGUMENTS_INVALID)
        if (hasIndex) {
            val index = (args["index"] as? JsonPrimitive)?.intOrNull
                ?: return ClickTarget.Refused(ARGUMENTS_INVALID)
            return ClickTarget.Index(index)
        }
        // x without y (or the reverse) cannot name a point at all, even before the display is read.
        if (hasX != hasY) return ClickTarget.Refused(MISSING_POINT)
        val x = (args["x"] as? JsonPrimitive)?.intOrNull ?: return ClickTarget.Refused(ARGUMENTS_INVALID)
        val y = (args["y"] as? JsonPrimitive)?.intOrNull ?: return ClickTarget.Refused(ARGUMENTS_INVALID)
        return ClickTarget.Point(x, y)
    }

    private fun executeIndexClick(index: Int, longPress: Boolean): ActionResult {
        val snapshot = currentSnapshot ?: return refused(ARGUMENTS_INVALID)
        if (index < 0 || index >= snapshot.elements.size) return refused(ARGUMENTS_INVALID)

        val before = runCatching { ui.screenFingerprint() }.getOrDefault("")
        // 0.5.4 Task 8 (§6.2.2), fix round: the count-only action line starts with the targeting mode this class really
        // has - index - and it names a **dispatched** action only. Placed after every validation and immediately before
        // the dispatch, so a refusal is never counted as an act. The index itself never rides the line.
        P3Log.line("P3_ACT via=index")
        val accepted = if (longPress) {
            ui.longPressByIndex(snapshot.snapshotId, index)
        } else {
            ui.clickByIndex(snapshot.snapshotId, index)
        }
        // Dispatched means the screen may already have changed: the snapshot goes with the action, whatever it returned.
        invalidateSnapshot()
        if (!accepted) return refused(NODE_NOT_CLICKABLE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) {
            done(if (longPress) "long pressed index $index" else "tapped index $index")
        } else {
            refused(NO_SCREEN_CHANGE)
        }
    }

    private fun executePointClick(x: Int, y: Int, longPress: Boolean): ActionResult {
        coordinateRefusal(x, y)?.let { return refused(it) }
        val before = runCatching { ui.screenFingerprint() }.getOrDefault("")
        // R2: the same P3_ACT line carries the targeting mode that really reached the device. It is emitted only for a
        // dispatched action, and the point never rides it (count-only, no screen content).
        P3Log.line("P3_ACT via=coord")
        val accepted = if (longPress) ui.longPressAt(x, y) else ui.clickAt(x, y)
        // A dispatched blind gesture may have changed the screen: the snapshot goes with it, whatever it returned.
        invalidateSnapshot()
        if (!accepted) return refused(NO_SCREEN_CHANGE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) {
            done(if (longPress) "long pressed ($x,$y)" else "tapped ($x,$y)")
        } else {
            refused(NO_SCREEN_CHANGE)
        }
    }

    /**
     * The coordinate eligibility gate (spec §6.3.3), or `null` when the point may be injected.
     *
     * Every probe fails closed: a probe that throws is the same as a probe that answers "unknown". Keyguard first (the
     * dominant fact), then the two system-surface witnesses - the focused window's type and the foreground package -
     * then the display rectangle. There is no "no answer" branch that permits the gesture; an unknown focused window,
     * an unknown foreground, or a known system surface are all [COORD_REFUSED_SYSTEM_UI] because each means "the
     * channel cannot prove a normal target app is in front".
     */
    private fun coordinateRefusal(x: Int, y: Int): String? {
        if (runCatching { ui.isKeyguardLocked() }.getOrDefault(true)) return COORD_REFUSED_KEYGUARD
        // Type-level witness: a system/IME/keyguard window that is not TYPE_APPLICATION, or an unreadable answer.
        if (runCatching { ui.focusedWindowIsApplication() }.getOrNull() != true) return COORD_REFUSED_SYSTEM_UI
        val foreground = runCatching { ui.foreground() }.getOrNull()?.packageName
        if (foreground.isNullOrBlank() || foreground in SYSTEM_FOREGROUND_PACKAGES) return COORD_REFUSED_SYSTEM_UI
        val bounds = runCatching { ui.screenBounds() }.getOrNull() ?: return MISSING_POINT
        // The rectangle is half-open: right/bottom are one past the last addressable pixel.
        if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) return MISSING_POINT
        return null
    }

    // ── scroll ───────────────────────────────────────────────────────────────

    private fun executeScroll(argsJson: String): ActionResult {
        val direction = parseDirection(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val accepted = ui.scroll(direction)
        if (!accepted) return refused(NO_SCREEN_CHANGE)
        // Scroll invalidates the current snapshot, and the filter it was read with goes with it.
        invalidateSnapshot()
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
        // 0.5.4 Task 8, same dispatch position as `tap`: `input_text` is index-targeted, so it reports the same via
        // token only when the write is actually dispatched; the typed text never rides it.
        P3Log.line("P3_ACT via=index")
        val accepted = ui.setTextByIndex(snapshot.snapshotId, index, trimmed)
        // Same invalidation rule as `tap`: typing mutates the screen, so no later page may continue the old snapshot.
        invalidateSnapshot()
        if (!accepted) return refused(NODE_NOT_CLICKABLE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) done("typed into index $index") else refused(NO_SCREEN_CHANGE)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
}
