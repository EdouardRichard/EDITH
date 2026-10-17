package com.sheen.rokid.glasses.display

import com.sheen.rokid.output.HintReadingBudget

/**
 * How a hint that is taller than the glasses' window is walked through, decided without Android so it can be tested
 * off-device (P3.2 `P3.2-CLIP`).
 *
 * The measured problem: the panel shows six lines at 22sp, and a `TextView` with `maxLines` **silently clipped**
 * whatever did not fit - an 89-character answer lost its last lines with nothing said (found in the P3.2 ambient
 * window). The ring's middle key cannot be intercepted (vendor FAQ), so there is no input path for the wearer to
 * scroll: the display has to move itself.
 *
 * **The pacing is the requirement, not a detail.** The first version jumped a whole page at a time; the owner's verdict
 * on the device was "看到自动翻页了，但这不是友好的效果。应该要实现窗口慢慢向下滚动的效果，同时滚动要延迟一两秒（按人类
 * 评价阅读速度定延迟时间）再滚动，让用户读完前面的字再滚动". So the plan is a **duration**, not a list of pages:
 * [READ_DELAY_MILLIS] of stillness so the first window can be read, then a continuous slide at [LINE_READ_MILLIS] per
 * hidden line - one line of about twelve Chinese characters per pace, which is a person's reading speed rather than a
 * page turn.
 *
 * The caller gets a single number to animate with, so the movement is smooth by construction: there is no step to
 * jump.
 */
object HintAutoScroll {

    /** Lines the fixed layout can show at once (P3.2 Stage 0 measured: 12 characters per line, six visible lines). */
    const val VISIBLE_LINES = HintReadingBudget.VISIBLE_LINES

    /**
     * How long the first window holds still before anything moves.
     *
     * The owner asked for one to two seconds; 1.5 s is one comfortable pass over six lines of Chinese, which is what
     * "read what is in front of you before it slides" costs.
     */
    const val READ_DELAY_MILLIS = HintReadingBudget.READ_DELAY_MILLIS

    /**
     * How long one hidden line takes to slide into view.
     *
     * 1.4 s for a twelve-character line is roughly the pace a person reads at while also watching the text move, and
     * it is the number that makes the motion readable rather than decorative. A faster slide would satisfy "it
     * scrolls" and fail the person doing the reading.
     */
    const val LINE_READ_MILLIS = HintReadingBudget.LINE_READ_MILLIS

    /**
     * How long a **new** entry takes to come into view (device report, 2026-10-15).
     *
     * Short on purpose, and not zero. The paced slide is for reading *within* a long hint; a message that has just
     * been spoken has to be readable when the wearer looks up, and on the device the paced slide took 16.8 s to travel
     * a scrollback that began with a 204-character hint. Zero would be the page-jump the owner rejected
     * (「看到自动翻页了，但这不是友好的效果」), so this is a visible slide that is over before the answer's own speech is.
     */
    const val IMMEDIATE_MILLIS = 280L

    /**
     * How long the slide takes: one reading pace per line that does not fit, zero when everything is already visible.
     *
     * @param lines the laid-out line count (from `TextView.lineCount`, so only knowable after a layout pass)
     */
    fun scrollMillis(lines: Int, visibleLines: Int = VISIBLE_LINES): Long {
        require(lines >= 0) { "line count must not be negative, was $lines" }
        require(visibleLines > 0) { "the visible window must be at least one line, was $visibleLines" }
        return (lines - visibleLines).coerceAtLeast(0) * LINE_READ_MILLIS
    }

    /** True when the hint is taller than the window and therefore has to move. */
    fun needsScroll(lines: Int, visibleLines: Int = VISIBLE_LINES): Boolean = scrollMillis(lines, visibleLines) > 0L

    /**
     * How far the slide has to travel to bring the last line to the bottom of the window (Phase C, spec §6.6).
     *
     * The same quantity [scrollMillis] paces: the lines that do not fit, times the height of one. Phase C's
     * conversation view scrolls to its **newest entry** rather than to the end of a single hint, so the caller needs
     * the destination as a number instead of `lines * lineHeight`, and the two must not be computed in two places.
     *
     * A zero or negative [lineHeightPx] means the view has not been laid out yet; the honest answer then is not to
     * move, because any offset would be a guess.
     */
    fun endOffsetPx(totalLines: Int, lineHeightPx: Int, visibleLines: Int = VISIBLE_LINES): Int {
        require(totalLines >= 0) { "line count must not be negative, was $totalLines" }
        require(visibleLines > 0) { "the visible window must be at least one line, was $visibleLines" }
        if (lineHeightPx <= 0) return 0
        return (totalLines - visibleLines).coerceAtLeast(0) * lineHeightPx
    }
}
