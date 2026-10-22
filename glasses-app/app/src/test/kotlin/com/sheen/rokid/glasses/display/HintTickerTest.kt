package com.sheen.rokid.glasses.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.23 P4 (spec §5.2.2, U10): the official auto-scroll engine, as arithmetic.
 *
 * The official implementation is a `ValueAnimator` with `duration = Integer.MAX_VALUE` and a listener that skips the
 * first three frames and then calls `scrollBy(0, 1)`; a page key is `min(remaining, viewport * 0.85)`. Those are the
 * whole of the behaviour that can be decided without a view, so they live here: a drift in the 1 px, the 3 frames, the
 * 0.85 or the bound fails a JVM test instead of being argued about from a screen recording.
 */
class HintTickerTest {

    @Test
    fun onePixelPerFrameAfterTheFirstThreeFrames() {
        assertEquals("the first three frames wait for layout", 100, HintTicker.offsetAfterFrames(100, 0, 200))
        assertEquals(100, HintTicker.offsetAfterFrames(100, 3, 200))
        assertEquals("the fourth frame moves exactly one pixel", 101, HintTicker.offsetAfterFrames(100, 4, 200))
        assertEquals(105, HintTicker.offsetAfterFrames(100, 8, 200))
    }

    @Test
    fun theOffsetNeverPassesTheMaximum() {
        assertEquals(200, HintTicker.offsetAfterFrames(199, 10, 200))
        assertEquals("a start already past the end is clamped, never negative", 200,
            HintTicker.offsetAfterFrames(500, 0, 200))
    }

    @Test
    fun aPageIsTheSmallerOfTheRemainingDistanceAndEightyFivePercentOfTheViewport() {
        assertEquals(85, HintTicker.pageStepPx(remainingPx = 500, viewportPx = 100))
        assertEquals(30, HintTicker.pageStepPx(remainingPx = 30, viewportPx = 100))
        assertEquals(0, HintTicker.pageStepPx(remainingPx = 0, viewportPx = 100))
        assertEquals("a viewport that has not been laid out cannot invent a step", 0,
            HintTicker.pageStepPx(remainingPx = 500, viewportPx = 0))
    }

    @Test
    fun aNegativeRemainingDistanceIsTreatedAsTheEnd() {
        assertEquals(0, HintTicker.pageStepPx(remainingPx = -20, viewportPx = 100))
    }

    @Test
    fun nothingScrollsWhenEverythingFits() {
        assertFalse(HintTicker.shouldScroll(0, 0))
        assertTrue(HintTicker.shouldScroll(0, 1))
        assertFalse("at the bottom there is nothing left to move", HintTicker.shouldScroll(200, 200))
    }

    @Test
    fun theTickerRunsOnlyWhileThereIsSomethingToScrollAndNobodyStoppedIt() {
        // 0.24 spec §3.3/§5.3: the pin no longer stops the ticker. The only stops are "nothing below the fold", the
        // wearer's own page, and the host being paused/hidden.
        assertTrue(HintTicker.shouldRun(hasOverflow = true, userPaused = false, paused = false))
        assertFalse("everything fits", HintTicker.shouldRun(hasOverflow = false, userPaused = false, paused = false))
        assertFalse("the wearer paged by hand",
            HintTicker.shouldRun(hasOverflow = true, userPaused = true, paused = false))
        assertFalse("the host is paused",
            HintTicker.shouldRun(hasOverflow = true, userPaused = false, paused = true))
    }

    @Test
    fun theConstantsAreTheOfficialOnes() {
        assertEquals(1, HintTicker.PIXELS_PER_FRAME)
        assertEquals(3, HintTicker.SKIP_FRAMES)
        assertEquals(0.85f, HintTicker.PAGE_FRACTION, 0.0001f)
    }
}
