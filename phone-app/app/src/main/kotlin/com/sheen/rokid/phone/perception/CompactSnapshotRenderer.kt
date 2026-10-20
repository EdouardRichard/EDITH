package com.sheen.rokid.phone.perception

import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionSnapshot

/**
 * edith-0.3 Phase 2 Task 7: renders a [PerceptionSnapshot] as the compact, model-facing screen text.
 *
 * One element per line, in the observation's order:
 *
 * ```
 * #n12 button "搜索" center=(540,320) [clickable]
 * #n13 text "稻香 - 周杰伦" center=(540,180)
 * ```
 *
 * **No bounds.** [PerceptionElement.bounds] stays on the evidence/debugging contract; the model-facing view only names
 * the center, and only as a consequence of the element it describes. D84 still forbids the model from sending
 * coordinates, so there is deliberately no "click at (x,y)" instruction here.
 *
 * **No systemui/status/nav noise.** That filtering is the provider's job ([PerceptionSnapshotBuilder]), and the renderer
 * renders exactly the elements it is given - a second filter here could disagree with the coverage line.
 *
 * **Bounded text stays honest.** A `truncated` snapshot ends with [TRUNCATION_MARKER], so the model can tell a complete
 * screen from one the host had to cut. Every platform text is flattened onto its element's line (control characters
 * become spaces, quotes/backslashes are escaped); a multi-line platform string can never fabricate extra element lines.
 */
internal object CompactSnapshotRenderer {

    /** The one marker a bounded text ends with; downstream renderers reuse the same word. */
    const val TRUNCATION_MARKER = "… truncated"

    /** The complete text for [snapshot], or an empty string when it has no elements and is not truncated. */
    fun render(snapshot: PerceptionSnapshot): String {
        val lines = ArrayList<String>(snapshot.elements.size + 1)
        snapshot.elements.forEach { lines += line(it) }
        if (snapshot.truncated) lines += TRUNCATION_MARKER
        return lines.joinToString("\n")
    }

    private fun line(element: PerceptionElement): String = buildString {
        append(element.ref)
        append(' ')
        append(element.role.ifBlank { UNKNOWN_ROLE })
        val visibleText = element.text ?: element.contentDescription
        if (visibleText != null) {
            append(" \"").append(flatten(visibleText)).append('"')
        }
        val centerX = element.centerX
        val centerY = element.centerY
        if (centerX != null && centerY != null) {
            append(" center=(").append(centerX).append(',').append(centerY).append(')')
        }
        val flags = buildList {
            if (element.clickable) add(FLAG_CLICKABLE)
            if (element.editable) add(FLAG_EDITABLE)
        }
        if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
    }

    /** One platform string, one rendered line: newlines/tabs/control chars become spaces, quotes are escaped. */
    private fun flatten(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when {
                character == '\\' -> append("\\\\")
                character == '"' -> append("\\\"")
                character == '\n' || character == '\r' || character == '\t' -> append(' ')
                character.isISOControl() -> append(' ')
                else -> append(character)
            }
        }
    }

    private const val UNKNOWN_ROLE = "unknown"
    private const val FLAG_CLICKABLE = "clickable"
    private const val FLAG_EDITABLE = "editable"
}
