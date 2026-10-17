package com.sheen.rokid.glasses.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-scroll plan (P3.2 `P3.2-CLIP`): a long answer must be walked through, not clipped.
 *
 * The pacing is part of the requirement, not a detail. The first version jumped a whole page at a time and the owner's
 * verdict was exact: "看到自动翻页了，但这不是友好的效果。应该要实现窗口慢慢向下滚动的效果，同时滚动要延迟一两秒
 * （按人类评价阅读速度定延迟时间）再滚动，让用户读完前面的字再滚动". So the plan is now a **duration**: hold still long
 * enough to read the first window, then move one line at a reading pace.
 */
class HintAutoScrollTest {

    @Test
    fun aHintThatFitsItsWindowNeverMoves() {
        assertEquals(0L, HintAutoScroll.scrollMillis(lines = 1))
        assertEquals(0L, HintAutoScroll.scrollMillis(lines = 6))
        assertEquals(0L, HintAutoScroll.scrollMillis(lines = 0))
        assertFalse(HintAutoScroll.needsScroll(lines = 6))
    }

    @Test
    fun theMovementLastsOneReadingPacePerHiddenLine() {
        // One line past the window is one line of movement; ten lines is four.
        assertEquals(HintAutoScroll.LINE_READ_MILLIS, HintAutoScroll.scrollMillis(lines = 7))
        assertEquals(4 * HintAutoScroll.LINE_READ_MILLIS, HintAutoScroll.scrollMillis(lines = 10))
        assertTrue(HintAutoScroll.needsScroll(lines = 7))
    }

    @Test
    fun theMovementIsSlowEnoughToReadAndNeverStartsImmediately() {
        // The owner's two numbers: a 1-2 s hold before anything moves, and a pace a person can read at.
        assertTrue("the first window must be readable before it moves",
            HintAutoScroll.READ_DELAY_MILLIS in 1_000L..2_000L)
        assertTrue("a line of twelve characters needs about a second, not a fraction of one",
            HintAutoScroll.LINE_READ_MILLIS >= 1_000L)
    }

    @Test
    fun aLongerAnswerTakesProportionallyLongerToWalk() {
        val short = HintAutoScroll.scrollMillis(lines = 8)
        val long = HintAutoScroll.scrollMillis(lines = 20)
        assertTrue("more hidden lines must mean more time, not the same jump", long > short)
        assertEquals(14 * HintAutoScroll.LINE_READ_MILLIS, long)
    }

    @Test
    fun aNarrowWindowIsHonouredSoThePlanIsNotTiedToThisPanel() {
        assertEquals(0L, HintAutoScroll.scrollMillis(lines = 3, visibleLines = 3))
        assertEquals(2 * HintAutoScroll.LINE_READ_MILLIS, HintAutoScroll.scrollMillis(lines = 5, visibleLines = 3))
    }

    @Test
    fun impossibleInputsAreRefusedRatherThanGuessed() {
        assertThrows(IllegalArgumentException::class.java) { HintAutoScroll.scrollMillis(lines = -1) }
        assertThrows(IllegalArgumentException::class.java) { HintAutoScroll.scrollMillis(lines = 5, visibleLines = 0) }
    }

    // ---------------------------------------------------------------- Phase C: where the slide ends (spec §6.6)

    @Test
    fun theEndOffsetPutsTheLastLineAtTheBottomOfTheWindow() {
        // A conversation that fits does not move at all.
        assertEquals(0, HintAutoScroll.endOffsetPx(totalLines = 4, lineHeightPx = 30))
        // Otherwise it moves exactly as far as the lines that do not fit - the same quantity `scrollMillis` paces.
        assertEquals((10 - HintAutoScroll.VISIBLE_LINES) * 30, HintAutoScroll.endOffsetPx(totalLines = 10, lineHeightPx = 30))
    }

    @Test
    fun aWindowWithNoMeasuredLineHeightNeverProducesAnOffset() {
        // Before the first layout pass `lineHeight` is 0; scrolling by a guessed height would land anywhere.
        assertEquals(0, HintAutoScroll.endOffsetPx(totalLines = 10, lineHeightPx = 0))
        assertEquals(0, HintAutoScroll.endOffsetPx(totalLines = 0, lineHeightPx = 30))
    }

    @Test
    fun aNegativeLineCountIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { HintAutoScroll.endOffsetPx(totalLines = -1, lineHeightPx = 30) }
    }
}
