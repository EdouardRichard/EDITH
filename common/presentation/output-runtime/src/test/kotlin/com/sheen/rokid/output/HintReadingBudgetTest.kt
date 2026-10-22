package com.sheen.rokid.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The reading budget: how long a hint of N characters needs to stay on the glasses (P3.2 `P3.2-CLIP`).
 *
 * The defect this exists for: a hint's lifetime was a fixed 5 s, while the glasses now slide a long answer at a
 * person's reading pace. A 100-character answer needs roughly fifteen seconds to be read as it moves, so the panel
 * used to clear the text mid-sentence - the owner's report was exact: "有滚动，但是一会突然内容清空了，好像是翻页".
 *
 * Both sides read this one object: the sender sizes the lifetime, the renderer sizes the pace. Two copies of "twelve
 * characters per line, six visible lines, one and a half seconds to look before it moves" would drift, and the drift
 * would show up as content vanishing again.
 */
class HintReadingBudgetTest {

    @Test
    fun aHintThatFitsTheWindowKeepsTheLifetimeItsSenderChose() {
        // Six lines of twelve characters is exactly the window, so there is nothing to walk and no reason to extend.
        assertEquals(5_000L, HintReadingBudget.readMillis(chars = 72, baseMillis = 5_000L))
        assertEquals(5_000L, HintReadingBudget.readMillis(chars = 1, baseMillis = 5_000L))
        assertEquals(5_000L, HintReadingBudget.readMillis(chars = 0, baseMillis = 5_000L))
    }

    @Test
    fun aHintTallerThanTheWindowIsGivenItsSlidePlusTheBaseTheSenderAskedFor() {
        // The owner's rule, verbatim: "滚动完再触发5秒" - the slide's own duration, then the sender's base on top of it.
        // 89 characters is eight lines, so two lines move: 1.5 s of stillness + 2 * 1.4 s of sliding + 5 s to read the
        // window the slide finished on.
        val expected = HintReadingBudget.READ_DELAY_MILLIS + 2 * HintReadingBudget.LINE_READ_MILLIS + 5_000L

        assertEquals(9_300L, expected)
        assertEquals(expected, HintReadingBudget.readMillis(chars = 89, baseMillis = 5_000L))
    }

    @Test
    fun theLongestHintTheWireAllowsStillGetsAWalkableLifetime() {
        // 120 codepoints is ten lines, four of which move.
        assertEquals(HintReadingBudget.READ_DELAY_MILLIS + 4 * HintReadingBudget.LINE_READ_MILLIS + 5_000L,
            HintReadingBudget.readMillis(chars = 120, baseMillis = 5_000L))
    }

    @Test
    fun theBaseIsAlwaysOnTopSoTheLifetimeNeverShrinks() {
        // A caller with a longer base (the debug probe holds for 10 s) keeps it, and gains the slide as well.
        assertEquals(HintReadingBudget.READ_DELAY_MILLIS + 2 * HintReadingBudget.LINE_READ_MILLIS + 20_000L,
            HintReadingBudget.readMillis(chars = 89, baseMillis = 20_000L))
        assertEquals(10_000L, HintReadingBudget.readMillis(chars = 24, baseMillis = 10_000L))
    }

    @Test
    fun moreCharactersNeverMeanLessTime() {
        val short = HintReadingBudget.readMillis(chars = 80, baseMillis = 5_000L)
        val long = HintReadingBudget.readMillis(chars = 118, baseMillis = 5_000L)
        assertTrue(long >= short, "$long must not be shorter than $short")
    }

    @Test
    fun theLayoutNumbersAreTheOnesTheDeviceMeasured() {
        // P3.2 Stage 0 measured twelve characters per line and six visible lines; the sweep and the rendering agree
        // only if they are the same numbers.
        assertEquals(12, HintReadingBudget.CHARS_PER_LINE)
        assertEquals(6, HintReadingBudget.VISIBLE_LINES)
        assertTrue(HintReadingBudget.READ_DELAY_MILLIS in 1_000L..2_000L)
        assertTrue(HintReadingBudget.LINE_READ_MILLIS >= 1_000L)
    }

    @Test
    fun anImpossibleBaseIsRefusedRatherThanGuessed() {
        assertFailsWith<IllegalArgumentException> {
            HintReadingBudget.readMillis(chars = 10, baseMillis = 0L)
        }
    }
}
