package com.sheen.rokid.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase C follow-up (device report, 2026-10-15): **a new answer must be visible when it arrives.**
 *
 * The wearer said 「伊迪丝 现在几点了」and heard the answer — while the screen still showed the previous, much longer
 * hint. The wire was fine and the text was fine: the glasses' own log shows the new entry appended
 * (`conversation route=WAKE entries=2 chars=225`) and the previous entry still filling the window with 18 lines. What
 * failed was the scroll:
 *
 *  - `scheduleReveal` returned immediately when the wearer had paused it (a side-touch tap at 21:53:43, one minute
 *    before the answer), so the new entry was never brought into view at all; and
 *  - when it does run, it animates from the top of the **whole** scrollback at reading pace — `scrollMillis=16800` in
 *    that same log, i.e. nearly seventeen seconds before the newest entry is reached.
 *
 * The pacing itself is the owner's requirement (「让用户读完前面的字再滚动」) and is kept: it is right for reading
 * *within* a long hint. It is wrong as the way to deliver something **new**, and pausing to read must never be what
 * hides the next message. These are the decisions, as a pure function.
 */
class HintRevealTest {

    @Test
    fun aNewEntryIsBroughtIntoViewEvenWhileTheWearerHasPausedTheScroll() {
        // The device case: paused at 21:53:43, the answer arrived at 21:55:02 and was never shown.
        assertEquals(Reveal.IMMEDIATE, HintReveal.forChange(promptly = true, paused = true, atNewest = false))
    }

    @Test
    fun aNewEntryIsBroughtIntoViewWithoutWaitingForTheReadingPace() {
        assertEquals(Reveal.IMMEDIATE, HintReveal.forChange(promptly = true, paused = false, atNewest = false))
    }

    @Test
    fun aPausedScrollHoldsStillWhenNothingNewArrived() {
        // The reason pausing exists: a slide must never fight the person reading.
        assertEquals(Reveal.HOLD, HintReveal.forChange(promptly = false, paused = true, atNewest = false))
    }

    @Test
    fun anUnpausedScrollStillSlidesAtReadingPace() {
        // The owner's pacing requirement, unchanged: hold long enough to read the first window, then move.
        assertEquals(Reveal.PACED, HintReveal.forChange(promptly = false, paused = false, atNewest = false))
    }

    @Test
    fun nothingIsDoneWhenTheNewestEntryIsAlreadyInView() {
        assertEquals(Reveal.HOLD, HintReveal.forChange(promptly = false, paused = false, atNewest = true))
        assertEquals(Reveal.HOLD, HintReveal.forChange(promptly = false, paused = true, atNewest = true))
    }

    @Test
    fun aNewEntryAlreadyInViewIsStillHandledPromptly() {
        // Arriving at the bottom is the normal case for a short conversation, and it must not fall through to the
        // paced branch: that branch would re-run a seventeen-second animation from wherever the view happens to be.
        assertEquals(Reveal.IMMEDIATE, HintReveal.forChange(promptly = true, paused = false, atNewest = true))
    }

    @Test
    fun theImmediateRevealIsShortEnoughToBeSeenArriving() {
        // Not zero: a jump the wearer cannot follow is the effect the owner rejected ("看到自动翻页了，但这不是友好的
        // 效果"). Not seconds either - the message is already late by the time it is rendered.
        assertEquals(true, HintAutoScroll.IMMEDIATE_MILLIS in 1..1_000)
    }
}
