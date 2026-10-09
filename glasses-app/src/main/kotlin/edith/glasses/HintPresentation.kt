package edith.glasses

import edith.output.HintWireCodec

/**
 * What the wearer actually sees, decided without Android so it can be tested off-device.
 *
 * The rule the product fixes is "short points, nothing invented, nothing clipped mid-word". The phone bounds a hint to
 * [HintWireCodec.MAX_TEXT_CODEPOINTS] codepoints, so the only job left here is **layout**: split the text on sentence
 * boundaries, one point per line, and show all of them.
 *
 * **Changed for `P3.2-CLIP` (2026-10-09).** This layer used to hand the view at most [MAX_POINTS] points, so a
 * four-sentence answer lost its tail *before* layout ever saw it; an 89-character wake answer was silently cut to its
 * first three sentences on top of the view's own six-line window. The wire bound is the only limit that has a reason
 * to exist, so it is now the only one: a longer hint is not dropped here, it is walked through by [HintAutoScroll].
 */
object HintPresentation {
    /**
     * The most codepoints this layer will render.
     *
     * Not a product rule - a defence. The phone already bounds every hint on the wire, and a hint that somehow arrives
     * longer than that is a bug worth reporting ([truncated]), not a document worth laying out.
     */
    const val MAX_RENDER_CODEPOINTS = HintWireCodec.MAX_TEXT_CODEPOINTS

    /** Sentence and line boundaries a hint may be split on. Deliberately conservative: no mid-word breaking. */
    private val separators = Regex("[\\r\\n]+|[。；;!?！？]")

    /** The points to show, all of them; a blank or absent hint renders as nothing at all. */
    fun points(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.take(MAX_RENDER_CODEPOINTS).split(separators).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The exact string the view shows: one point per line, or empty when there is nothing to display. */
    fun displayText(text: String?): String = points(text).joinToString("\n")

    /**
     * True when the hint carried more than this layer will render, so the caller can record the shortfall.
     *
     * Only reachable with text the phone should never have sent; a merely *long* hint is no longer truncated, it
     * scrolls.
     */
    fun truncated(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.length > MAX_RENDER_CODEPOINTS
    }

    /**
     * What the layout actually did with the hint, as one greppable line.
     *
     * `maxLines` is the **window** the fixed layout shows at once ([HintAutoScroll.VISIBLE_LINES]); `clipped` keeps its
     * P3.2 Stage 0 meaning - the laid-out text is taller than that window - and `scrollMillis` says how long the slide
     * that reveals the rest takes (`0` when everything fits). The field the Stage 0 probe reads (`clipped`) is
     * unchanged; `pages` from the first P3.2-CLIP build was replaced by `scrollMillis` when page jumps became a smooth
     * slide.
     *
     * The line count is only knowable after a layout pass, so the caller reports it from a `post {}` - and this
     * formatter is separate from the reading so it stays testable off-device. Carries counts and sizes, never the
     * text: the same rule as every other `P3_` line.
     */
    fun renderReport(lines: Int, chars: Int): String =
        "hint rendered lines=$lines maxLines=${HintAutoScroll.VISIBLE_LINES} chars=$chars " +
            "clipped=${lines > HintAutoScroll.VISIBLE_LINES} scrollMillis=${HintAutoScroll.scrollMillis(lines)}"
}
