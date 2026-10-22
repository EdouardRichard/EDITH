package com.sheen.rokid.device

/**
 * Which nodes [ObservationRenderer] puts on the observed screen.
 *
 * [INTERACTIVE] keeps nodes that expose an action or one of the interaction-relevant states
 * (`clickable`, `long_clickable`, `editable`, `scrollable`, `checkable`, `checked`, `selected`,
 * `focused`); it is the most compact view. [TEXT] keeps nodes carrying text, a content description
 * or a hint. [ALL] keeps every node and is meant for diagnostics.
 */
enum class ScreenFilter {
    INTERACTIVE,
    TEXT,
    ALL,
}

/**
 * Renders one [Observation] as budgeted, line-oriented text for the model.
 *
 * Node lines follow spec §4.4:
 * `#n16 button "Wi-Fi" id=android:id/title bounds=[0,0,100,100] states=[clickable,focused] actions=[click,long_click]`.
 * Optional trailing fields are `desc="..."` (when the content description is not already the
 * text), `hint="..."` and `pane="..."`. [DeviceNode.states] are sorted so the output is
 * deterministic; actions keep the order the producer supplied. Text, descriptions, hints and
 * titles are escaped onto one line: quotes and backslashes are escaped and control characters
 * become spaces.
 *
 * Window headers (`window=<id> type=<type> layer=<n> pkg=<pkg> title="<title>" bounds=[...]`)
 * precede that window's nodes, so IME and floating windows appear as their own section. A window
 * with no visible node after the filter is omitted. When the observation has no window metadata,
 * only node lines are emitted.
 *
 * Refs come from [registry], injected through the constructor. The kernel MUST pass the same
 * registry it uses for [RefRegistry.resolve]; the default `RefRegistry()` exists only for one-shot
 * diagnostics and tests. A renderer that owns a private registry prints refs the kernel cannot
 * resolve, so production wiring must always inject the shared instance.
 *
 * Refs are assigned for every node — including nodes hidden by [filter] — so
 * changing the filter never renumbers the visible refs.
 *
 * [budgetBytes] is a UTF-8 byte budget and [maxLines] a line budget (including headers and the
 * marker). When the observation does not fit, whole lines are dropped and the final line is
 * exactly `truncated: true`. Non-empty output ends with a newline. That marker is also emitted when
 * the observation itself was truncated ([Observation.truncated]) even if its visible nodes fit,
 * because the model must know it is not seeing the whole screen. If the budget cannot hold the
 * marker, the render is empty.
 *
 * Renders are expected to be serialized by the caller: ref numbers are allocated in node order, so
 * interleaved renders of different observations would interleave the numbering.
 */
class ObservationRenderer(
    private val registry: RefRegistry = RefRegistry(),
) {

    fun render(
        observation: Observation,
        filter: ScreenFilter,
        budgetBytes: Int = DEFAULT_BUDGET_BYTES,
        maxLines: Int = DEFAULT_MAX_LINES,
    ): String {
        val lines = buildLines(observation, filter)
        return fit(lines, observation.truncated, budgetBytes, maxLines)
    }

    /**
     * Renders only the nodes at [paths] (the structural paths [ObservationDiff] and [RefRegistry]
     * use), in the observation's pre-order.
     *
     * This is the diff half of spec §4.6's `data.screen`: the tool layer needs the same node line an
     * `added`/`updated` entry would have had in a full render, and re-implementing the format there
     * would let the two drift. The ref comes from the same [registry] [render] uses, so a tool that
     * renders a diff line does not mint a ref the kernel cannot resolve. No window headers and no
     * budget are applied: the caller is rendering a handful of changed nodes, and the diff mode is
     * only chosen below the full-render thresholds. Unknown paths are ignored; an empty selection is
     * an empty string.
     */
    fun renderNodes(observation: Observation, paths: Collection<String>): String {
        if (paths.isEmpty()) return ""
        val wanted = paths.toSet()
        val refs = registry.assign(observation)
        val nodePaths = NodeStructure.paths(observation.nodes)
        val lines = observation.nodes.indices
            .filter { nodePaths[it] in wanted }
            .map { nodeLine(refs.getValue(nodePaths[it]), observation.nodes[it]) }
        return renderLines(lines)
    }

    private fun buildLines(observation: Observation, filter: ScreenFilter): List<String> {
        val refs = registry.assign(observation)
        val paths = NodeStructure.paths(observation.nodes)

        if (observation.windows.isEmpty()) {
            return observation.nodes.indices
                .filter { isVisible(observation.nodes[it], filter) }
                .map { nodeLine(refs.getValue(paths[it]), observation.nodes[it]) }
        }

        val windowsById = observation.windows.associateBy { it.id }
        val indicesByWindow = LinkedHashMap<Int, MutableList<Int>>()
        observation.nodes.indices.forEach { index ->
            val windowId = observation.nodes[index].windowId
            indicesByWindow.getOrPut(windowId) { mutableListOf() }.add(index)
        }

        val lines = ArrayList<String>(observation.nodes.size + indicesByWindow.size)
        indicesByWindow.forEach { (windowId, indices) ->
            val visible = indices.filter { isVisible(observation.nodes[it], filter) }
            if (visible.isEmpty()) return@forEach
            lines += windowLine(windowId, windowsById[windowId])
            visible.forEach { index ->
                lines += nodeLine(refs.getValue(paths[index]), observation.nodes[index])
            }
        }
        return lines
    }

    private fun isVisible(node: DeviceNode, filter: ScreenFilter): Boolean = when (filter) {
        ScreenFilter.ALL -> true
        ScreenFilter.TEXT ->
            node.text?.isNotBlank() == true ||
                node.contentDescription?.isNotBlank() == true ||
                node.hint?.isNotBlank() == true
        ScreenFilter.INTERACTIVE ->
            node.actions.isNotEmpty() || node.states.any { it in INTERACTIVE_STATES }
    }

    private fun nodeLine(ref: Ref, node: DeviceNode): String = buildString {
        append(ref.value).append(' ')
        append(node.role.ifBlank { "node" })
        node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(escape(it)).append('"') }
        node.viewId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        append(" bounds=")
        appendBounds(node.bounds)
        append(" states=[").append(node.states.sorted().joinToString(",")).append(']')
        append(" actions=[").append(node.actions.joinToString(",")).append(']')
        node.contentDescription
            ?.takeIf { it.isNotBlank() && it != node.text }
            ?.let { append(" desc=\"").append(escape(it)).append('"') }
        node.hint?.takeIf { it.isNotBlank() }?.let { append(" hint=\"").append(escape(it)).append('"') }
        node.paneTitle?.takeIf { it.isNotBlank() }?.let { append(" pane=\"").append(escape(it)).append('"') }
    }

    private fun windowLine(windowId: Int, window: ObservationWindow?): String = buildString {
        append("window=").append(windowId)
        if (window == null) return@buildString
        append(" type=").append(window.type)
        append(" layer=").append(window.layer)
        window.packageName?.takeIf { it.isNotBlank() }?.let { append(" pkg=").append(it) }
        window.title?.takeIf { it.isNotBlank() }?.let { append(" title=\"").append(escape(it)).append('"') }
        append(" bounds=")
        appendBounds(window.bounds)
    }

    private fun StringBuilder.appendBounds(bounds: Bounds) {
        append('[').append(bounds.toFieldString()).append(']')
    }

    private fun fit(
        lines: List<String>,
        observationTruncated: Boolean,
        budgetBytes: Int,
        maxLines: Int,
    ): String {
        val marker = TRUNCATION_MARKER
        val markerBytes = marker.utf8Bytes() + 1
        if (maxLines < 1 || budgetBytes < markerBytes) return ""

        val totalBytes = lines.sumOf { it.utf8Bytes() + 1 }
        if (!observationTruncated && lines.size <= maxLines && totalBytes <= budgetBytes) {
            return renderLines(lines)
        }
        if (observationTruncated && lines.size + 1 <= maxLines && totalBytes + markerBytes <= budgetBytes) {
            return renderLines(lines + marker)
        }

        // Reserve room for the marker, then keep whole lines until either budget would be crossed.
        val kept = ArrayList<String>(minOf(lines.size, maxLines - 1))
        var usedBytes = 0
        for (line in lines) {
            if (kept.size + 2 > maxLines) break
            val lineBytes = line.utf8Bytes() + 1
            if (usedBytes + lineBytes + markerBytes > budgetBytes) break
            kept += line
            usedBytes += lineBytes
        }
        kept += marker
        return renderLines(kept)
    }

    private fun renderLines(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' || char == '\r' || char == '\t' -> append(' ')
                char.isISOControl() -> append(' ')
                else -> append(char)
            }
        }
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    companion object {
        /** Spec §4.4 observation budget: 16 KiB of text. */
        const val DEFAULT_BUDGET_BYTES = 16_384

        /** Spec §4.4 observation budget: 400 lines. */
        const val DEFAULT_MAX_LINES = 400

        /** The one line that tells the model the render/observation was cut. */
        private const val TRUNCATION_MARKER = "truncated: true"

        private val INTERACTIVE_STATES = setOf(
            "clickable",
            "long_clickable",
            "editable",
            "scrollable",
            "checkable",
            "checked",
            "selected",
            "focused",
        )
    }
}
