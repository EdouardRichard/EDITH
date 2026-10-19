package com.sheen.rokid.device

/**
 * The one executable step [ActionPlanner] found for an [ActKind].
 *
 * [Node] and [GestureAction] are both accessibility-channel executions; [ShellKey] is the shell
 * fallback; [Unsupported] means "no channel this kernel has can express the request" and the
 * kernel turns it into [DeviceErrorCodes.INVALID_ARGUMENTS].
 */
sealed interface PlannedAction {

    /**
     * A semantic node action: [action] is one of the [DeviceActions] tokens and [arguments] are
     * encoded for the port by [DeviceActions.encode].
     */
    data class Node(val ref: Ref, val action: String, val arguments: Map<String, String> = emptyMap()) : PlannedAction

    /** A synthetic gesture, with coordinates derived from an observation, never from the model. */
    data class GestureAction(val gesture: Gesture) : PlannedAction

    /** `input keyevent <key>` through the shell channel. */
    data class ShellKey(val key: String) : PlannedAction

    /** No route applies (missing target, unbound channel, no shell, unknown direction, ...). */
    data object Unsupported : PlannedAction
}

/** The semantic actions the `device.act` tool exposes (spec §4.7). */
enum class ActKind {
    TAP,
    LONG_PRESS,
    DOUBLE_TAP,
    SWIPE,
    SCROLL,
    SCROLL_TO_TEXT,
    TYPE,
    PRESS_KEY,
    HOME,
    BACK,
    RECENTS,
    NOTIFICATIONS,
    WAIT,
}

/**
 * Whether the action can only be executed by the shell channel. Used by the kernel to pick the
 * ready-gate ([ReadyKind.SHELL]) and the envelope channel before planning, so a shell-only action
 * does not require a bound accessibility service.
 */
internal val ActKind.isShellKeyAction: Boolean
    get() = when (this) {
        ActKind.PRESS_KEY, ActKind.HOME, ActKind.BACK, ActKind.RECENTS, ActKind.NOTIFICATIONS -> true
        else -> false
    }

/**
 * Turns a model-facing [ActKind] and its arguments into one executable [PlannedAction].
 *
 * **What the planner may consult.** It owns no [RefRegistry] and no observation cache. A ref is
 * opaque (D84), so a ref-based plan can only be a semantic node action; coordinate gestures are
 * used only when the target is a *text* (the planner observes the screen and taps the centre of the
 * node — or of its nearest clickable ancestor, which is where the platform usually puts the click
 * handler) or when the action is a screen-level swipe (the planner reads the screen area from the
 * window bounds, falling back to the union of node bounds). Coordinates therefore never come from
 * the model.
 *
 * **Routes, in order.**
 * - `TAP`/`LONG_PRESS`/`DOUBLE_TAP`: a ref → node action (`click`/`long_click`/`double_click`);
 *   otherwise a text → gesture at the matched node; otherwise unsupported.
 * - `TYPE`: a ref plus `value` (falling back to `text`) → `set_text` with a `text` argument. Typing
 *   has no gesture form, so a ref is required; a payload containing [DeviceActions.ARGUMENT_SEPARATOR]
 *   is unsupported because the port's wire format cannot escape it.
 * - `SCROLL`/`SCROLL_TO_TEXT`: a ref → `scroll_forward`/`scroll_backward`; otherwise a screen
 *   swipe. `SCROLL_TO_TEXT` is **one** scroll step: the caller repeats it with
 *   [DeviceKernel.waitFor] until the text appears, because multi-step loops belong to the tool layer
 *   (Task 10).
 * - `SWIPE`: always a screen-level gesture from the observed screen area (`up`/`down`/`left`/
 *   `right`); a ref is ignored because a swipe is a screen gesture.
 * - `PRESS_KEY`/`HOME`/`BACK`/`RECENTS`/`NOTIFICATIONS`: `input keyevent` through the shell. Keys
 *   normalize to `KEYCODE_*` (numbers pass through); anything outside `[A-Za-z0-9_]+` and numeric
 *   keycodes is unsupported, because the key is interpolated into a shell command and a model
 *   must not be able to inject shell syntax (`HOME; ...`). These are the only global navigation
 *   routes this version has: [AccessibilityPort] exposes no global-action method.
 * - `WAIT` is not planned; the kernel runs it as a wait operation.
 *
 * Every mismatch returns [PlannedAction.Unsupported] instead of throwing: the kernel owns the error
 * code (unsupported target vs unavailable channel).
 */
class ActionPlanner(
    private val accessibility: AccessibilityPort,
    private val shell: ShellPort?,
) {

    fun plan(
        kind: ActKind,
        ref: Ref?,
        text: String?,
        value: String?,
        direction: String?,
        key: String?,
    ): PlannedAction {
        if (kind.isShellKeyAction) return shellKey(kind, key)
        if (kind == ActKind.WAIT) return PlannedAction.Unsupported
        if (!bound()) return PlannedAction.Unsupported
        return when (kind) {
            ActKind.TAP, ActKind.LONG_PRESS, ActKind.DOUBLE_TAP -> nodeOrGesture(kind, ref, text)
            ActKind.TYPE -> type(ref, text, value)
            ActKind.SCROLL, ActKind.SCROLL_TO_TEXT -> scroll(ref, direction)
            ActKind.SWIPE -> swipe(direction)
            else -> PlannedAction.Unsupported
        }
    }

    // ── accessibility routes ────────────────────────────────────────────────

    private fun nodeOrGesture(kind: ActKind, ref: Ref?, text: String?): PlannedAction {
        val action = nodeAction(kind) ?: return PlannedAction.Unsupported
        if (ref != null) return PlannedAction.Node(ref, action)
        val bounds = locate(text) ?: return PlannedAction.Unsupported
        return PlannedAction.GestureAction(gestureFor(kind, bounds))
    }

    private fun type(ref: Ref?, text: String?, value: String?): PlannedAction {
        if (ref == null) return PlannedAction.Unsupported
        val payload = (value ?: text)?.takeIf { it.isNotEmpty() } ?: return PlannedAction.Unsupported
        if (payload.contains(DeviceActions.ARGUMENT_SEPARATOR)) return PlannedAction.Unsupported
        return PlannedAction.Node(ref, DeviceActions.SET_TEXT, mapOf(ARGUMENT_TEXT to payload))
    }

    private fun scroll(ref: Ref?, direction: String?): PlannedAction {
        val token = direction?.trim()?.lowercase()
        val forward = when (token) {
            null, "forward", "up", "left" -> true
            "backward", "down", "right" -> false
            else -> return PlannedAction.Unsupported
        }
        if (ref != null) {
            val action = if (forward) DeviceActions.SCROLL_FORWARD else DeviceActions.SCROLL_BACKWARD
            return PlannedAction.Node(ref, action)
        }
        return swipe(if (forward) "forward" else "backward")
    }

    private fun swipe(direction: String?): PlannedAction {
        val token = direction?.trim()?.lowercase() ?: return PlannedAction.Unsupported
        val area = screenArea() ?: return PlannedAction.Unsupported
        val gesture = swipeGesture(token, area) ?: return PlannedAction.Unsupported
        return PlannedAction.GestureAction(gesture)
    }

    private fun nodeAction(kind: ActKind): String? = when (kind) {
        ActKind.TAP -> DeviceActions.CLICK
        ActKind.LONG_PRESS -> DeviceActions.LONG_CLICK
        ActKind.DOUBLE_TAP -> DeviceActions.DOUBLE_CLICK
        else -> null
    }

    private fun gestureFor(kind: ActKind, bounds: Bounds): Gesture {
        val point = Point(
            bounds.left + (bounds.right - bounds.left) / 2,
            bounds.top + (bounds.bottom - bounds.top) / 2,
        )
        return when (kind) {
            ActKind.LONG_PRESS -> Gesture(listOf(Stroke(listOf(point), 0L, LONG_PRESS_DURATION_MILLIS)))
            ActKind.DOUBLE_TAP -> Gesture(
                listOf(
                    Stroke(listOf(point), 0L, TAP_DURATION_MILLIS),
                    Stroke(listOf(point), DOUBLE_TAP_INTERVAL_MILLIS, TAP_DURATION_MILLIS),
                ),
            )
            else -> Gesture(listOf(Stroke(listOf(point), 0L, TAP_DURATION_MILLIS)))
        }
    }

    /**
     * Finds the bounds a text-targeted gesture should hit.
     *
     * The first node whose text or content description equals [text] (case-insensitive, no
     * substring matching) wins; the touch point then moves to the nearest ancestor that offers
     * `click`/`clickable` (or `long_click`/`long_clickable` for a long press), because the text
     * node itself is usually not the clickable one. Pre-order plus depth is enough to walk
     * ancestors, so no ref registry is needed.
     */
    private fun locate(text: String?): Bounds? {
        val query = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val observation = observeForLookup() ?: return null
        val nodes = observation.nodes
        val index = nodes.indexOfFirst { node ->
            node.text?.trim().equals(query, ignoreCase = true) ||
                node.contentDescription?.trim().equals(query, ignoreCase = true)
        }
        if (index < 0) return null
        val target = nearestGestureAncestor(nodes, index) ?: index
        return nodes[target].bounds.takeIf { it.hasArea() }
    }

    private fun nearestGestureAncestor(nodes: List<DeviceNode>, index: Int): Int? {
        var depth = nodes[index].depth
        var cursor = index - 1
        while (cursor >= 0) {
            val node = nodes[cursor]
            if (node.depth < depth) {
                if (isClickTarget(node)) return cursor
                depth = node.depth
            }
            cursor -= 1
        }
        return null
    }

    /**
     * The nearest ancestor that offers a tap or long-press handler. The text node itself is
     * usually not the clickable one, so a coordinate tap goes to the ancestor's centre; if no
     * ancestor is clickable either, the text node's own centre is used, which still lands on the
     * same spot.
     */
    private fun isClickTarget(node: DeviceNode): Boolean =
        DeviceActions.CLICK in node.actions ||
            "clickable" in node.states ||
            DeviceActions.LONG_CLICK in node.actions ||
            "long_clickable" in node.states

    /** The screen area a screen-level swipe may use: the largest window, else the node union. */
    private fun screenArea(): Bounds? {
        val observation = observeForLookup() ?: return null
        val window = observation.windows.maxByOrNull { it.bounds.area() }?.bounds
        if (window != null && window.hasArea()) return window
        val nodes = observation.nodes
        if (nodes.isEmpty()) return null
        val union = Bounds(
            left = nodes.minOf { it.bounds.left },
            top = nodes.minOf { it.bounds.top },
            right = nodes.maxOf { it.bounds.right },
            bottom = nodes.maxOf { it.bounds.bottom },
        )
        return union.takeIf { it.hasArea() }
    }

    private fun swipeGesture(direction: String, area: Bounds): Gesture? {
        val width = area.right - area.left
        val height = area.bottom - area.top
        if (width <= 0 || height <= 0) return null
        val centerX = area.left + width / 2
        val centerY = area.top + height / 2
        val verticalEnd = area.top + height / 4
        val verticalStart = area.top + height * 3 / 4
        val horizontalStart = area.left + width / 4
        val horizontalEnd = area.left + width * 3 / 4
        val (from, to) = when (direction) {
            "up", "forward" -> Point(centerX, verticalStart) to Point(centerX, verticalEnd)
            "down", "backward" -> Point(centerX, verticalEnd) to Point(centerX, verticalStart)
            "left" -> Point(horizontalEnd, centerY) to Point(horizontalStart, centerY)
            "right" -> Point(horizontalStart, centerY) to Point(horizontalEnd, centerY)
            else -> return null
        }
        return Gesture(listOf(Stroke(listOf(from, to), 0L, SWIPE_DURATION_MILLIS)))
    }

    private fun observeForLookup(): Observation? =
        runCatching { accessibility.observe(ScreenFilter.ALL) }.getOrNull()

    private fun bound(): Boolean = runCatching { accessibility.isBound() }.getOrDefault(false)

    // ── shell route ─────────────────────────────────────────────────────────

    private fun shellKey(kind: ActKind, key: String?): PlannedAction {
        if (shell == null) return PlannedAction.Unsupported
        val raw = when (kind) {
            ActKind.PRESS_KEY -> key?.trim()?.takeIf { it.isNotEmpty() } ?: return PlannedAction.Unsupported
            ActKind.HOME -> "HOME"
            ActKind.BACK -> "BACK"
            ActKind.RECENTS -> "APP_SWITCH"
            ActKind.NOTIFICATIONS -> "NOTIFICATION"
            else -> return PlannedAction.Unsupported
        }
        return PlannedAction.ShellKey(DeviceKeyCodes.normalize(raw) ?: return PlannedAction.Unsupported)
    }

    private fun Bounds.hasArea(): Boolean = right > left && bottom > top

    private fun Bounds.area(): Long = (right - left).toLong() * (bottom - top).toLong()

    private companion object {
        const val ARGUMENT_TEXT = "text"

        /** Short enough that the platform reads it as a tap. */
        const val TAP_DURATION_MILLIS = 50L

        /** `ViewConfiguration.getLongPressTimeout()` is 500 ms; stay clear of it. */
        const val LONG_PRESS_DURATION_MILLIS = 600L

        /** Inside the platform's double-tap timeout, far enough apart to be two strokes. */
        const val DOUBLE_TAP_INTERVAL_MILLIS = 120L

        const val SWIPE_DURATION_MILLIS = 300L
    }
}

/**
 * Validation and normalization for `input keyevent` arguments.
 *
 * The key ends up inside a shell command string, so a model-supplied key must be unable to add
 * shell syntax. Two forms are accepted: a numeric keycode (`26`) or a token of ASCII letters,
 * digits and underscores (`home`, `KEYCODE_HOME`); the token is upper-cased and prefixed with
 * `KEYCODE_` when it does not already carry it. Embedded whitespace and every shell metacharacter
 * (`;`, `$`, `&`, `|`, quotes, newlines, `../`, ...) is refused before the command is built;
 * leading/trailing whitespace is trimmed first.
 */
internal object DeviceKeyCodes {

    private val SAFE_KEY = Regex("[A-Za-z0-9_]+")
    private const val KEYCODE_PREFIX = "KEYCODE_"

    /** Returns the normalized key event code, or null when [raw] is not a safe key. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text
        if (!SAFE_KEY.matches(text)) return null
        val upper = text.uppercase()
        return if (upper.startsWith(KEYCODE_PREFIX)) upper else KEYCODE_PREFIX + upper
    }

    /** Whether an already-normalized key (the only form [DeviceKernel] receives) is safe. */
    fun isSafeNormalized(key: String): Boolean =
        key.isNotEmpty() && (key.all { it.isDigit() } || SAFE_KEY.matches(key))
}
