package edith.probe.agent

/** Screen rectangle in device pixels, as `uiautomator dump` reports it. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class UiNode(
    val text: String,
    val contentDesc: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Bounds,
)

private val NODE_TAG = Regex("""<node\b[^>]*>""")
private val BOUNDS_ATTR = Regex("""bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""")

/**
 * Reads one attribute, anchored to a word boundary.
 *
 * The boundary is load-bearing: real dumps carry `long-clickable="true"` next to `clickable="false"`, and a bare
 * `clickable="([^"]*)"` matches inside `long-clickable` first — the agent would then aim at a node the platform does
 * not consider tappable, and the failure would look like "the tap did nothing" rather than "we read the wrong field".
 */
private fun attribute(tag: String, name: String): String? =
    Regex("""(?:\s|^)${Regex.escape(name)}="([^"]*)"""").find(tag)?.groupValues?.get(1)

fun parseUiDump(xml: String): List<UiNode> =
    NODE_TAG.findAll(xml).mapNotNull { match ->
        val tag = match.value
        val bounds = BOUNDS_ATTR.find(tag) ?: return@mapNotNull null
        UiNode(
            text = attribute(tag, "text").orEmpty(),
            contentDesc = attribute(tag, "content-desc").orEmpty(),
            // Absent means not clickable; only an explicit "true" counts.
            clickable = attribute(tag, "clickable") == "true",
            // Absent means enabled: dumps sometimes omit it, and treating that as disabled would hide every node.
            enabled = attribute(tag, "enabled") != "false",
            bounds = Bounds(
                bounds.groupValues[1].toInt(),
                bounds.groupValues[2].toInt(),
                bounds.groupValues[3].toInt(),
                bounds.groupValues[4].toInt(),
            ),
        )
    }.toList()

/**
 * The agent's aim: resolve a human-named target against the live hierarchy.
 *
 * Disabled nodes are never returned — they are visible but cannot do anything, and reporting one as found sends the
 * agent into a loop of taps that never change the screen. Among the rest, an explicitly clickable node wins over a
 * matching label nested inside it.
 */
fun findTappable(nodes: List<UiNode>, query: String): UiNode? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    val matches = nodes.filter {
        it.enabled && (it.text.contains(needle, ignoreCase = true) || it.contentDesc.contains(needle, ignoreCase = true))
    }
    return matches.firstOrNull { it.clickable } ?: matches.firstOrNull()
}

/** The readable screen, for the agent's observation step. */
fun visibleText(nodes: List<UiNode>): List<String> =
    nodes.mapNotNull { node ->
        listOf(node.text, node.contentDesc).firstOrNull { it.isNotBlank() }
    }.distinct()

/** One candidate handed to the model, with the tap point already resolved. */
data class ElementRef(
    val index: Int,
    val label: String,
    val clickable: Boolean,
    val centerX: Int,
    val centerY: Int,
)

/**
 * Every matching element, numbered — the tool lists, the model chooses.
 *
 * A single-match helper looks helpful and is not: asked for "搜索" it silently returned "搜索记录" (the search *history*
 * header) while the agent meant the search field, and because the tap still "succeeded" every step reported
 * `ok = true` and the agent looped until the step limit. The ambiguity belongs in front of the model.
 *
 * Clickable candidates are ordered first: they are the ones the platform says can be acted on. Disabled nodes are
 * excluded — they are visible but can achieve nothing.
 */
fun findElements(nodes: List<UiNode>, query: String): List<ElementRef> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return nodes.asSequence()
        .filter { it.enabled }
        .filter { it.text.contains(needle, ignoreCase = true) || it.contentDesc.contains(needle, ignoreCase = true) }
        .sortedWith(compareByDescending<UiNode> { it.clickable }.thenBy { it.bounds.top }.thenBy { it.bounds.left })
        .mapIndexed { index, node ->
            ElementRef(
                index = index,
                label = node.text.ifBlank { node.contentDesc },
                clickable = node.clickable,
                centerX = node.bounds.centerX,
                centerY = node.bounds.centerY,
            )
        }
        .toList()
}

/**
 * The no-progress guard the spec names, implemented here because a real run proved it load-bearing: without it the
 * agent re-tapped the same screen until the step limit, burning device actions on an unchanged screen.
 *
 * `maxRepeats = 3` means the third consecutive identical screen is "stuck" — two repeats are tolerated because a tap
 * legitimately takes a moment to change anything.
 */
class ProgressGuard(private val maxRepeats: Int = 3) {
    private var last: String? = null
    private var repeats = 0

    /** Returns false once the same screen has been observed [maxRepeats] times in a row. */
    fun observe(fingerprint: String): Boolean {
        if (fingerprint == last) {
            repeats += 1
        } else {
            last = fingerprint
            repeats = 1
        }
        return repeats < maxRepeats
    }
}
