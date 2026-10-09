package edith.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses-side presentation rule, testable off-device.
 *
 * This is the half of the glasses app that is not an Android API call: what a hint becomes on a fixed three-point
 * region. The plan's rule is "at most three points, fixed safe area, never squeezed" - and it must not silently drop
 * content, which is why the shortfall is reported separately.
 */
class HintPresentationTest {

    @Test
    fun aHintWithSeveralSentencesBecomesOnePointPerLine() {
        assertEquals(listOf("先讲结论", "再讲项目背景"), HintPresentation.points("先讲结论。再讲项目背景"))
        assertEquals("先讲结论\n再讲项目背景", HintPresentation.displayText("先讲结论。再讲项目背景"))
    }

    @Test
    fun everySentenceOfAnAllowedHintIsShownSoNothingIsDroppedBeforeTheView() {
        // P3.2-CLIP: the old rule handed the view at most three points, so a four-sentence answer lost its tail
        // before layout ever saw it. The wire already bounds the text (HintWireCodec), so this layer shows all of it.
        val long = "一。二。三。四。五"

        assertEquals(listOf("一", "二", "三", "四", "五"), HintPresentation.points(long))
        assertFalse("nothing is dropped at this layer any more", HintPresentation.truncated(long))
    }

    @Test
    fun onlyTextBeyondTheWireBoundIsReportedAsTruncated() {
        // The one loss this layer still guards against is a hint the phone should never have sent.
        val oversized = "字".repeat(edith.output.HintWireCodec.MAX_TEXT_CODEPOINTS + 1)

        assertTrue(HintPresentation.truncated(oversized))
        assertEquals(edith.output.HintWireCodec.MAX_TEXT_CODEPOINTS,
            HintPresentation.displayText(oversized).length)
    }

    @Test
    fun blankAndAbsentHintsRenderAsNothingRatherThanAsEmptyText() {
        // An empty view is the clear state: a blank hint must not leave a blank line pretending to be content.
        assertEquals(emptyList<String>(), HintPresentation.points(null))
        assertEquals(emptyList<String>(), HintPresentation.points(""))
        assertEquals(emptyList<String>(), HintPresentation.points("   \n  "))
        assertEquals("", HintPresentation.displayText(null))
        assertFalse(HintPresentation.truncated(null))
    }

    @Test
    fun surroundingWhitespaceAndEmptySegmentsAreDropped() {
        assertEquals(listOf("第一点", "第二点"), HintPresentation.points("  第一点 \n\n 。 第二点。 "))
    }

    @Test
    fun asciiSeparatorsBehaveTheSameWayAsChineseOnes() {
        assertEquals(listOf("first", "second", "third", "fourth"),
            HintPresentation.points("first; second! third? fourth"))
    }

    @Test
    fun aRenderedHintReportsItsLineCountAndSizeWithoutItsText() {
        // P3.2 Stage 0: the layout's own limit was invisible - a hint that overflowed six lines was clipped with
        // nothing said about it. This line is the measurement instrument for the capacity probe, and it must never
        // carry the hint's text. P3.2-CLIP appended `scrollMillis`: the same overflow is now slid through, not lost.
        assertEquals("hint rendered lines=5 maxLines=6 chars=53 clipped=false scrollMillis=0",
            HintPresentation.renderReport(lines = 5, chars = 53))
    }

    @Test
    fun aHintTallerThanTheWindowIsReportedWithHowLongItsSlideTakes() {
        // Seven lines is one hidden line, so the slide lasts exactly one reading pace.
        assertEquals("hint rendered lines=7 maxLines=6 chars=81 clipped=true scrollMillis=1400",
            HintPresentation.renderReport(lines = 7, chars = 81))
    }
}
