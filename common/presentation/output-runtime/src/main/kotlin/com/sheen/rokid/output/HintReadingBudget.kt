package com.sheen.rokid.output

/**
 * How long a hint of N characters has to stay on the glasses (P3.2 `P3.2-CLIP`).
 *
 * The defect this exists for: a hint's lifetime was a fixed 5 s, while the glasses slide a long answer at a person's
 * reading pace. An answer of a hundred characters spends several seconds moving, so the panel cleared the text
 * mid-sentence - the owner's report was exact: "有滚动，但是一会突然内容清空了，好像是翻页".
 *
 * **The rule is the owner's, verbatim: "滚动完再触发5秒"** - the slide's own duration, then the sender's base lifetime
 * on top of it. A hint that fits the window never slides and keeps exactly the lifetime it asked for.
 *
 * Both sides read this one object: the sender sizes the lifetime through [readMillis], the renderer sizes its pace from
 * the same constants. Two copies of "twelve characters per line, six visible lines, one and a half seconds of
 * stillness, one and a half seconds per line" would drift, and the drift would show up as content vanishing again.
 */
object HintReadingBudget {

    /** Characters one line holds at the fixed 22sp layout - measured on the glasses in P3.2 Stage 0. */
    const val CHARS_PER_LINE = 12

    /** Lines the window shows at once - the same six lines the auto-scroll slides within. */
    const val VISIBLE_LINES = 6

    /** Stillness before the slide starts, so the first window can be read before it moves (the owner asked for 1-2 s). */
    const val READ_DELAY_MILLIS = 1_500L

    /** How long one hidden line takes to slide into view: a person reads a twelve-character line about this fast. */
    const val LINE_READ_MILLIS = 1_400L

    /**
     * How long the panel must hold [chars] characters: the sender's [baseMillis], plus the slide when there is one.
     *
     * @param chars the hint's codepoint count, as the sender knows it before anything is laid out
     * @param baseMillis the lifetime the sender would give a hint that fits on one screen; it is always kept
     */
    fun readMillis(chars: Int, baseMillis: Long): Long {
        require(baseMillis > 0L) { "the base lifetime must be positive, was $baseMillis" }
        if (chars <= 0) return baseMillis
        val lines = (chars + CHARS_PER_LINE - 1) / CHARS_PER_LINE
        val hidden = (lines - VISIBLE_LINES).coerceAtLeast(0)
        if (hidden == 0) return baseMillis
        return READ_DELAY_MILLIS + hidden * LINE_READ_MILLIS + baseMillis
    }
}
