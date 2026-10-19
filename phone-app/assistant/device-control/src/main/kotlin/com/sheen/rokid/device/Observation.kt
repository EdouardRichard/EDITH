package com.sheen.rokid.device

/**
 * A screen rectangle in pixels.
 *
 * Bounds are part of a node's [RefRegistry] identity: a node that moved is a different identity,
 * because position is part of what the model reasoned about when it chose a ref.
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * One accessibility node flattened out of the screen tree.
 *
 * Nodes are listed in pre-order (the order the accessibility service walks the tree) and [depth]
 * is the 0-based tree depth. [NodeStructure] turns that pair into a structural path such as
 * `0/2/1`; that path is the node key used by [RefRegistry.assign] and [ObservationDiff].
 *
 * [states] and [actions] carry lowercase platform tokens (`clickable`, `long_clickable`,
 * `editable`, `scrollable`, `checkable`, `checked`, `selected`, `enabled`, `focused` and
 * `click`, `long_click`, `set_text`, ...). This module only compares and renders them; it never
 * parses them back into platform types.
 */
data class DeviceNode(
    val role: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: Bounds,
    val states: Set<String>,
    val actions: List<String>,
    val hint: String?,
    val paneTitle: String?,
    val windowId: Int,
    val depth: Int,
)

/**
 * One top-level window, as reported by the accessibility service's `getWindows()`.
 *
 * [layer] keeps the producer's window order; [id] is the identifier [DeviceNode.windowId] points
 * at. A rendered multi-window observation is grouped by these windows (see [ObservationRenderer]).
 */
data class ObservationWindow(
    val id: Int,
    val type: String,
    val layer: Int,
    val bounds: Bounds,
    val packageName: String?,
    val title: String?,
)

/**
 * One consistent snapshot of the screen.
 *
 * [revision] is the kernel's monotonically increasing observation counter. [truncated] means the
 * producer cut the tree before the end of [nodes]: only [nodes] and [windows] are known to be
 * visible, so a node missing from a truncated snapshot must not be reported as removed (see
 * [ObservationDiff.between]).
 */
data class Observation(
    val revision: Long,
    val windows: List<ObservationWindow>,
    val nodes: List<DeviceNode>,
    val truncated: Boolean,
)

/**
 * Derives structural node keys from a pre-order node list.
 *
 * The producer guarantee is that nodes appear in a depth-first pre-order walk and
 * [DeviceNode.depth] is their 0-based depth. A node's key is its slash-separated index among its
 * siblings at every level: the first root is `0`, its first child is `0/0`, its second child is
 * `0/1`, and a child of that second child is `0/1/0`.
 *
 * A malformed depth jump (a root followed by depth 2, for example) is normalised by zero-filling
 * the skipped level, so this function always returns exactly one path per node and never throws.
 */
internal object NodeStructure {

    fun paths(nodes: List<DeviceNode>): List<String> {
        val counters = ArrayList<Int>()
        var lastDepth = -1
        return nodes.map { node ->
            val depth = node.depth.coerceAtLeast(0)
            if (depth > lastDepth) {
                while (counters.size <= depth) counters.add(0)
                for (level in (lastDepth + 1)..depth) counters[level] = 0
            } else {
                counters[depth] += 1
                while (counters.size > depth + 1) counters.removeAt(counters.lastIndex)
            }
            lastDepth = depth
            (0..depth).joinToString("/") { counters[it].toString() }
        }
    }
}
