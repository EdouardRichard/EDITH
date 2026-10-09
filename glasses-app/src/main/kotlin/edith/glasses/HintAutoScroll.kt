package edith.glasses

/**
 * How a hint that is taller than the glasses' window is walked through, decided without Android so it can be tested
 * off-device (P3.2 `P3.2-CLIP`).
 *
 * The measured problem: the panel shows six lines at 22sp, and a `TextView` with `maxLines` **silently clipped**
 * whatever did not fit - an 89-character answer lost its last lines with nothing said (found in the P3.2 ambient
 * window). The ring's middle key cannot be intercepted (vendor FAQ), so there is no input path for the wearer to
 * scroll: the display has to walk itself.
 *
 * The plan is therefore a list of **line offsets** (one page each) with a dwell per page, and the last page is always
 * clamped to the bottom so the tail of the text is never skipped. Pages overlap by one line, because a line split
 * across two pages reads as a repeat rather than as a continuation.
 */
object HintAutoScroll {

    /** Lines the fixed layout can show at once (P3.2 Stage 0 measured: 12 characters per line, six visible lines). */
    const val VISIBLE_LINES = 6

    /**
     * How long one page stays still.
     *
     * The phone's own hint TTL is 5 s (passive) / 8 s (proactive), so a two-page answer has to cycle twice or more
     * inside that window: 1.2 s per page is short enough to finish a ten-line answer twice and long enough to read a
     * line of Chinese.
     */
    const val DWELL_MILLIS = 1_200L

    /**
     * The line offsets to show, in order: `[0]` when everything fits, otherwise successive pages ending at the bottom.
     *
     * @param lines the laid-out line count (from `TextView.lineCount`, so only knowable after a layout pass)
     */
    fun offsets(lines: Int, visibleLines: Int = VISIBLE_LINES): List<Int> {
        require(lines >= 0) { "line count must not be negative, was $lines" }
        require(visibleLines > 0) { "the visible window must be at least one line, was $visibleLines" }
        if (lines <= visibleLines) return listOf(0)
        val step = (visibleLines - 1).coerceAtLeast(1)
        val last = lines - visibleLines
        val pages = mutableListOf<Int>()
        var offset = 0
        while (offset < last) {
            pages += offset
            offset = minOf(offset + step, last)
        }
        pages += last
        return pages.distinct()
    }

    /** How many times the plan has to be walked to show everything once; `1` when it fits. */
    fun pages(lines: Int, visibleLines: Int = VISIBLE_LINES): Int = offsets(lines, visibleLines).size
}
