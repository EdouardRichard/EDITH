package com.sheen.rokid.output

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
 * **Bounded text stays honest.** A screen the host had to cut ends with [TRUNCATION_MARKER], so the model can tell a
 * complete screen from one that was bounded. The marker follows the **screen** coverage, not
 * [PerceptionSnapshot.truncated]: that snapshot-wide flag is also set when a different source (for example the
 * notification list) was cut, and a complete screen must not claim to be truncated (review I3). Every platform text
 * is flattened onto its element's line (control characters become spaces, quotes/backslashes are escaped); a
 * multi-line platform string can never fabricate extra element lines.
 */
object CompactSnapshotRenderer {

    /** The one marker a bounded text ends with; downstream renderers reuse the same word. */
    const val TRUNCATION_MARKER = "… truncated"

    /** The screen's coverage token that means "the read itself was bounded". */
    private const val SCREEN_TRUNCATED = "truncated"

    /**
     * Whether the **screen** source was cut.
     *
     * The snapshot-wide [PerceptionSnapshot.truncated] is an "any source was bounded" flag (notifications included),
     * so it cannot answer this question; the coverage line can. `observe`/`act` report this value as `screen.truncated`
     * and this renderer appends the marker exactly when it is true.
     */
    fun screenTruncated(snapshot: PerceptionSnapshot): Boolean = snapshot.coverage.screen == SCREEN_TRUNCATED

    /** The complete text for [snapshot], or an empty string when it has no elements and the screen is not truncated. */
    fun render(snapshot: PerceptionSnapshot): String {
        val lines = ArrayList<String>(snapshot.elements.size + 1)
        snapshot.elements.forEach { lines += line(it) }
        if (screenTruncated(snapshot)) lines += TRUNCATION_MARKER
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
                // U+2028/U+2029 are line/paragraph separators (category Zl/Zp), not ISO control characters, so the
                // branch above misses them; a platform string carrying one would still wrap the model-facing line.
                character == '\u2028' || character == '\u2029' -> append(' ')
                else -> append(character)
            }
        }
    }

    private const val UNKNOWN_ROLE = "unknown"
    private const val FLAG_CLICKABLE = "clickable"
    private const val FLAG_EDITABLE = "editable"
}
