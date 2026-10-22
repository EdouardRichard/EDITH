package com.sheen.rokid.phone.actions.a11y

import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.CollectionInfo
import com.sheen.rokid.device.DeviceActions
import com.sheen.rokid.device.DeviceNode
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Point
import com.sheen.rokid.device.RangeInfo
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.RefResult
import com.sheen.rokid.device.ScreenFilter as DeviceScreenFilter
import com.sheen.rokid.device.Stroke

/**
 * The platform-neutral half of the 0.25 accessibility adapter (Task 9).
 *
 * This file is the boundary that keeps the adapter testable as a plain JVM unit test: it must not
 * import `android.*`. [AccessibilityPlatform] is implemented by
 * [EdithAccessibilityService]'s private Android platform; [AccessibilityAdapter] turns that
 * platform shape into device-control's [Observation]/[DeviceNode] vocabulary and executes
 * [DeviceActions] tokens against the last observed nodes.
 *
 * **R36 — one registry.** The adapter never mints refs on its own: it assigns every observation
 * into the [RefRegistry] it was constructed with, and the production service exposes that same
 * instance as `EdithAccessibilityService.registry`. Task 10 MUST construct the kernel as
 * `DeviceKernel(accessibility = <service>.port(), ..., registry = <service>.registry)`; two
 * registries would render refs the kernel cannot resolve.
 *
 * **R37 — collection is always complete.** [observe] ignores its [DeviceScreenFilter] and collects
 * every window and every node. A node's ref comes from its structural path in the complete pre-order
 * list (and from its identity fields); filtering during collection would give the same node a
 * different path — and therefore a different ref — depending on the filter used. The filter is a
 * *rendering* concern and is applied only by `ObservationRenderer.render`.
 *
 * **R38 — platform fields map 1:1, never fabricated.** [DeviceNode.role] is the lowercased simple
 * class name (`android.widget.Button` -> `button`, no class name -> `unknown`); a platform action id
 * the vocabulary does not know is preserved as `action_<id>`; collection/range/tooltip/paneTitle/hint
 * stay null when the platform has no value. `AccessibilityNodeInfo.isVisibleToUser` is carried into
 * [DeviceNode.visibleToUser] for the same reason: the collector reports the platform's answer and the
 * perception layer decides what is model-facing.
 */

/**
 * Raw collection metadata, mirroring the platform `AccessibilityNodeInfo.CollectionInfo` integers so
 * this file can stay Android-free.
 */
data class PlatformCollectionInfo(
    val rowCount: Int,
    val columnCount: Int,
    val hierarchical: Boolean,
    /** Platform selection mode: 0 none, 1 single, 2 multiple, anything else unknown. */
    val selectionMode: Int,
)

/** Raw range metadata, mirroring the platform `AccessibilityNodeInfo.RangeInfo` integers. */
data class PlatformRangeInfo(
    val current: Float,
    val min: Float,
    val max: Float,
    /** Platform range type: 0 int, 1 float, 2 percent, anything else unknown. */
    val type: Int,
)

/**
 * One node as the Android collection produced it.
 *
 * [nodeId] is the platform's transient handle for the latest [AccessibilityPlatform.collect] call;
 * the adapter only ever hands ids from its most recent bind to the platform, so an id never
 * outlives the collection that minted it. [depth] is the 0-based depth inside [windowId]'s window,
 * and nodes are supplied in pre-order, one window at a time.
 */
data class PlatformNode(
    val nodeId: Int,
    val windowId: Int,
    val depth: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val bounds: Bounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val hintText: String?,
    val paneTitle: String?,
    val tooltipText: String?,
    val drawingOrder: Int,
    val actionIds: List<Int>,
    val collection: PlatformCollectionInfo?,
    val range: PlatformRangeInfo?,
    /**
     * `AccessibilityNodeInfo.isVisibleToUser`, carried 1:1 (edith-0.3 Phase 2). Defaults to `true`
     * so an older producer keeps every node visible; the perception layer is what drops invisible
     * nodes, never the collector.
     */
    val visibleToUser: Boolean = true,
)

/** One top-level window and its pre-order nodes, as the Android collector saw them. */
data class PlatformWindow(
    val id: Int,
    val type: Int,
    val layer: Int,
    val bounds: Bounds,
    val packageName: String?,
    val title: String?,
    val nodes: List<PlatformNode>,
)

/**
 * One raw platform collection.
 *
 * [truncated] means the collector stopped before the end of the tree on its own; the adapter also
 * sets it when the combined node count would exceed `MAX_NODES`.
 */
data class PlatformSnapshot(
    val windows: List<PlatformWindow>,
    val truncated: Boolean = false,
)

/**
 * The Android-specific collection and action surface the pure adapter calls.
 *
 * The production implementation lives in [EdithAccessibilityService]. Keeping this an interface is
 * what lets the whole mapping and action vocabulary be tested on the JVM.
 */
interface AccessibilityPlatform {

    /**
     * Collects up to [maxNodes] nodes across all windows, in pre-order and in window order.
     *
     * Implementations must fall back to the active window when the platform reports no windows,
     * and must set [PlatformSnapshot.truncated] when they stop early.
     */
    fun collect(maxNodes: Int): PlatformSnapshot

    /** Performs the platform click action on the node [nodeId] currently addresses. */
    fun click(nodeId: Int): Boolean

    /** Performs the platform long-click action on [nodeId]. */
    fun longClick(nodeId: Int): Boolean

    /** Performs the platform scroll-forward action on [nodeId]. */
    fun scrollForward(nodeId: Int): Boolean

    /** Performs the platform scroll-backward action on [nodeId]. */
    fun scrollBackward(nodeId: Int): Boolean

    /** Performs the platform set-text action on [nodeId] with [text] passed through verbatim. */
    fun setText(nodeId: Int, text: String): Boolean

    /** Dispatches one synthetic gesture and reports whether the platform completed it. */
    fun dispatchGesture(gesture: Gesture): Boolean
}

/** `AccessibilityWindowInfo.TYPE_*` values, mirrored here because this file cannot import Android. */
object PlatformWindowTypes {
    const val APPLICATION = 1
    const val INPUT_METHOD = 2
    const val SYSTEM = 3
    const val ACCESSIBILITY_OVERLAY = 4
}

/** `AccessibilityNodeInfo.ACTION_*` ids the vocabulary knows, mirrored here without importing Android. */
object PlatformActionIds {
    const val CLICK = 16
    const val LONG_CLICK = 32
    const val SET_TEXT = 2097152
    const val SCROLL_FORWARD = 4096
    const val SCROLL_BACKWARD = 8192
    const val EXPAND = 262144
    const val COLLAPSE = 524288
    const val DISMISS = 1048576
    const val SELECT = 4
    const val CLEAR_SELECTION = 8
    const val FOCUS = 1
    const val CLEAR_FOCUS = 2
    const val SCROLL_UP = 16384
    const val SCROLL_DOWN = 32768
    const val SCROLL_LEFT = 65536
    const val SCROLL_RIGHT = 131072
}

/** A tap is a short stroke; a double tap is two of them, 120 ms apart (Task 8's stroke semantics). */
private const val TAP_DURATION_MILLIS = 50L
private const val DOUBLE_TAP_INTERVAL_MILLIS = 120L

/** Platform action id -> the rendered/action token the kernel and renderer use. */
private val ACTION_TOKENS: Map<Int, String> = linkedMapOf(
    PlatformActionIds.CLICK to DeviceActions.CLICK,
    PlatformActionIds.LONG_CLICK to DeviceActions.LONG_CLICK,
    PlatformActionIds.SET_TEXT to DeviceActions.SET_TEXT,
    PlatformActionIds.SCROLL_FORWARD to DeviceActions.SCROLL_FORWARD,
    PlatformActionIds.SCROLL_BACKWARD to DeviceActions.SCROLL_BACKWARD,
    PlatformActionIds.EXPAND to "expand",
    PlatformActionIds.COLLAPSE to "collapse",
    PlatformActionIds.DISMISS to "dismiss",
    PlatformActionIds.SELECT to "select",
    PlatformActionIds.CLEAR_SELECTION to "clear_selection",
    PlatformActionIds.FOCUS to "focus",
    PlatformActionIds.CLEAR_FOCUS to "clear_focus",
    PlatformActionIds.SCROLL_UP to "scroll_up",
    PlatformActionIds.SCROLL_DOWN to "scroll_down",
    PlatformActionIds.SCROLL_LEFT to "scroll_left",
    PlatformActionIds.SCROLL_RIGHT to "scroll_right",
)

/**
 * Maps [AccessibilityPlatform] collections into [Observation]s and executes [DeviceActions]
 * tokens against the nodes of the latest observation.
 *
 * Instances are not thread-safe: the kernel's port contract serialises every call on one worker
 * thread. [registry] is thread-safe on its own, so the caller may read it between calls.
 */
class AccessibilityAdapter(
    private val platform: AccessibilityPlatform,
    private val registry: RefRegistry,
) {

    private var lastObservation: Observation? = null
    private var refToNodeIds: Map<String, Int> = emptyMap()
    private var nodeBoundsById: Map<Int, Bounds> = emptyMap()
    private var nodeClickableById: Map<Int, Boolean> = emptyMap()

    /**
     * Collects the whole current screen and assigns refs into [registry] (R36).
     *
     * [filter] is deliberately unused (R37): this producer always walks every window and every
     * node, because the structural path a ref is derived from must not depend on which rendering
     * filter the caller intends to use later. `ObservationRenderer.render` applies the filter.
     *
     * The returned [Observation.revision] is `0`; the kernel stamps its own revision.
     */
    fun observe(filter: DeviceScreenFilter): Observation {
        val snapshot = platform.collect(MAX_NODES)
        val windows = snapshot.windows.map { it.toObservationWindow() }
        val collectedCount = snapshot.windows.sumOf { it.nodes.size }
        val nodes = ArrayList<DeviceNode>(minOf(MAX_NODES, collectedCount))
        val platformNodes = ArrayList<PlatformNode>(minOf(MAX_NODES, collectedCount))
        var truncated = snapshot.truncated

        windowLoop@ for (window in snapshot.windows) {
            for (node in window.nodes) {
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    break@windowLoop
                }
                nodes += node.toDeviceNode()
                platformNodes += node
            }
        }

        val observation = Observation(
            revision = 0L,
            windows = windows,
            nodes = nodes,
            truncated = truncated,
        )
        bind(observation, platformNodes)
        return observation
    }

    /**
     * Executes one [DeviceActions]-encoded [action] against [ref].
     *
     * The ref is looked up in the latest observation's mapping first; on a miss the adapter asks
     * [registry] to resolve it against the latest observation (a text-only change keeps the same
     * node, up to the registry's rebind budget). An unknown ref, an unknown token, a missing
     * `set_text` argument or a platform refusal always answers `false` — this method never throws
     * at the kernel.
     */
    fun performNodeAction(ref: Ref, action: String): Boolean {
        return try {
            val (token, arguments) = DeviceActions.decode(action)
            val nodeId = nodeIdFor(ref) ?: return false
            when (token) {
                DeviceActions.CLICK -> platform.click(nodeId) || tapCentreForNonClickable(nodeId)
                DeviceActions.LONG_CLICK -> platform.longClick(nodeId)
                DeviceActions.SCROLL_FORWARD -> platform.scrollForward(nodeId)
                DeviceActions.SCROLL_BACKWARD -> platform.scrollBackward(nodeId)
                DeviceActions.SET_TEXT -> {
                    val text = arguments["text"]
                    if (text == null) false else platform.setText(nodeId, text)
                }
                DeviceActions.DOUBLE_CLICK -> {
                    val bounds = nodeBoundsById[nodeId] ?: return false
                    val centre = bounds.centre()
                    // One real double tap: two short strokes at the same point, the second starting
                    // 120 ms after the first. Two ACTION_CLICK calls are NOT a double tap (R33).
                    platform.dispatchGesture(
                        Gesture(
                            listOf(
                                Stroke(listOf(centre), 0L, TAP_DURATION_MILLIS),
                                Stroke(listOf(centre), DOUBLE_TAP_INTERVAL_MILLIS, TAP_DURATION_MILLIS),
                            ),
                        ),
                    )
                }
                else -> false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** Delegates one synthetic gesture to the platform. */
    fun dispatchGesture(gesture: Gesture): Boolean = platform.dispatchGesture(gesture)

    /**
     * The model commonly targets the text label inside a row/button; the platform's ACTION_CLICK on that text node
     * returns false even though tapping its centre hits the clickable parent. Only non-clickable nodes get this
     * fallback, so a genuine clickable node whose ACTION_CLICK is refused is still reported as a refusal.
     */
    private fun tapCentreForNonClickable(nodeId: Int): Boolean {
        if (nodeClickableById[nodeId] != false) return false
        val bounds = nodeBoundsById[nodeId] ?: return false
        return platform.dispatchGesture(
            Gesture(listOf(Stroke(listOf(bounds.centre()), 0L, TAP_DURATION_MILLIS))),
        )
    }

    private fun bind(observation: Observation, platformNodes: List<PlatformNode>) {
        val refs = registry.assign(observation)
        val refToNodeIds = HashMap<String, Int>(refs.size)
        val nodeBounds = HashMap<Int, Bounds>(refs.size)
        val nodeClickable = HashMap<Int, Boolean>(refs.size)
        refs.values.forEachIndexed { index, ref ->
            val node = platformNodes[index]
            refToNodeIds[ref.value] = node.nodeId
            nodeBounds[node.nodeId] = node.bounds
            nodeClickable[node.nodeId] = node.clickable
        }
        lastObservation = observation
        this.refToNodeIds = refToNodeIds
        nodeBoundsById = nodeBounds
        nodeClickableById = nodeClickable
    }

    private fun nodeIdFor(ref: Ref): Int? {
        refToNodeIds[ref.value]?.let { return it }
        val observation = lastObservation ?: return null
        return when (val resolved = registry.resolve(observation, ref)) {
            is RefResult.Ok -> refToNodeIds[resolved.ref.value]
            is RefResult.Stale -> null
        }
    }
}

private fun PlatformWindow.toObservationWindow(): ObservationWindow = ObservationWindow(
    id = id,
    type = when (type) {
        PlatformWindowTypes.APPLICATION -> "application"
        PlatformWindowTypes.INPUT_METHOD -> "ime"
        PlatformWindowTypes.SYSTEM -> "system"
        PlatformWindowTypes.ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        else -> "unknown"
    },
    layer = layer,
    bounds = bounds,
    packageName = packageName,
    title = title,
)

private fun PlatformNode.toDeviceNode(): DeviceNode = DeviceNode(
    role = className?.substringAfterLast('.')?.lowercase() ?: "unknown",
    text = text,
    contentDescription = contentDescription,
    viewId = viewIdResourceName,
    bounds = bounds,
    states = buildSet {
        if (clickable) add("clickable")
        if (longClickable) add("long_clickable")
        if (editable) add("editable")
        if (scrollable) add("scrollable")
        if (checkable) add("checkable")
        if (checked) add("checked")
        if (selected) add("selected")
        if (enabled) add("enabled")
        if (focused) add("focused")
    },
    actions = actionIds.map { actionId -> ACTION_TOKENS[actionId] ?: "action_$actionId" },
    hint = hintText,
    paneTitle = paneTitle,
    collection = collection?.let {
        CollectionInfo(
            rowCount = it.rowCount,
            columnCount = it.columnCount,
            hierarchical = it.hierarchical,
            selectionMode = when (it.selectionMode) {
                0 -> "none"
                1 -> "single"
                2 -> "multiple"
                else -> null
            },
        )
    },
    range = range?.let {
        RangeInfo(
            current = it.current,
            min = it.min,
            max = it.max,
            type = when (it.type) {
                0 -> "int"
                1 -> "float"
                2 -> "percent"
                else -> null
            },
        )
    },
    tooltip = tooltipText,
    drawingOrder = drawingOrder,
    windowId = windowId,
    depth = depth,
    visibleToUser = visibleToUser,
)

private fun Bounds.centre(): Point = Point(
    x = (left + right) / 2,
    y = (top + bottom) / 2,
)
