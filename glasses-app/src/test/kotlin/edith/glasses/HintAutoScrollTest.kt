package edith.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The auto-scroll plan (P3.2 `P3.2-CLIP`): a long answer must be walked through, not clipped.
 *
 * The invariants that matter: everything that fits is one page; a longer text always ends exactly at the bottom (the
 * tail is never skipped); and consecutive pages overlap by one line so a line is not cut in half between them.
 */
class HintAutoScrollTest {

    @Test
    fun aHintThatFitsItsWindowIsASingleStillPage() {
        assertEquals(listOf(0), HintAutoScroll.offsets(lines = 1))
        assertEquals(listOf(0), HintAutoScroll.offsets(lines = 6))
        assertEquals(listOf(0), HintAutoScroll.offsets(lines = 0))
        assertEquals(1, HintAutoScroll.pages(lines = 6))
    }

    @Test
    fun everyPageExceptTheLastStartsWhereTheWindowEndsMinusOneLine() {
        // Twelve lines at six visible: page 1 = 0..5, page 2 = 5..10, page 3 = 6..11 (clamped to the bottom).
        assertEquals(listOf(0, 5, 6), HintAutoScroll.offsets(lines = 12))
    }

    @Test
    fun theLastPageAlwaysEndsAtTheBottomOfTheText() {
        for (lines in 7..40) {
            val pages = HintAutoScroll.offsets(lines)
            assertEquals("lines=$lines must end at the bottom", lines - HintAutoScroll.VISIBLE_LINES, pages.last())
            assertEquals("lines=$lines must start at the top", 0, pages.first())
        }
    }

    @Test
    fun aTextThatOverflowsByOneLineStillGetsTwoPages() {
        assertEquals(listOf(0, 1), HintAutoScroll.offsets(lines = 7))
        assertEquals(2, HintAutoScroll.pages(lines = 7))
    }

    @Test
    fun thePageCountGrowsWithTheTextAndNeverRepeatsAnOffset() {
        assertEquals(2, HintAutoScroll.pages(lines = 11))
        assertEquals(3, HintAutoScroll.pages(lines = 12))
        assertEquals(4, HintAutoScroll.pages(lines = 21))
        val pages = HintAutoScroll.offsets(lines = 40)
        assertEquals("offsets are strictly increasing", pages.sorted(), pages)
        assertEquals("and distinct", pages.size, pages.distinct().size)
    }

    @Test
    fun aNarrowWindowIsHonouredSoThePlanIsNotTiedToThisPanel() {
        assertEquals(listOf(0), HintAutoScroll.offsets(lines = 3, visibleLines = 3))
        assertEquals(listOf(0, 2), HintAutoScroll.offsets(lines = 5, visibleLines = 3))
    }

    @Test
    fun impossibleInputsAreRefusedRatherThanGuessed() {
        assertThrows(IllegalArgumentException::class.java) { HintAutoScroll.offsets(lines = -1) }
        assertThrows(IllegalArgumentException::class.java) { HintAutoScroll.offsets(lines = 5, visibleLines = 0) }
    }
}
