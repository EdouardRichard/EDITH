package com.sheen.rokid.glasses.display

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
        val oversized = "字".repeat(com.sheen.rokid.output.HintWireCodec.MAX_TEXT_CODEPOINTS + 1)

        assertTrue(HintPresentation.truncated(oversized))
        assertEquals(com.sheen.rokid.output.HintWireCodec.MAX_TEXT_CODEPOINTS,
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
        // nothing said about it. P4 replaced the paced slide with the official ticker, so the report now carries the
        // ticker fact and the maximum offset; it still never carries the hint's text.
        assertEquals("hint rendered lines=5 chars=53 ticker=true maxOffset=0",
            HintPresentation.renderReport(lines = 5, chars = 53, maxOffsetPx = 0))
    }

    @Test
    fun aHintTallerThanTheWindowReportsTheTickerMaximum() {
        assertEquals("hint rendered lines=7 chars=81 ticker=true maxOffset=42",
            HintPresentation.renderReport(lines = 7, chars = 81, maxOffsetPx = 42))
    }

    @Test
    fun anInboundCommandReportsItsReceivedLengthSoATruncatedOneIsDistinguishable() {
        // 2026-10-14 ceiling measurement: above a measured transport ceiling the payload arrives cut short and the
        // codec answers `undecodable`, which on its own cannot tell "truncated in transit" from "the codec refused a
        // well-formed message". The received length is the missing fact, and it is a count - never the text.
        assertEquals("inbound Show chars=120", HintPresentation.inboundReport("x".repeat(120), "Show"))
        assertEquals("inbound undecodable chars=400", HintPresentation.inboundReport("x".repeat(400), null))
    }

    @Test
    fun aConversationReportsItsSizePerRouteAndNeverItsText() {
        // Phase C: without this line, "the wearer saw nothing" and "the conversation stopped growing" are the same
        // reading. Counts and a route name only - the same rule as every other instrument here.
        assertEquals("conversation route=WAKE entries=3 chars=57",
            HintPresentation.conversationReport(com.sheen.rokid.contracts.AssistantRoute.WAKE, entries = 3, chars = 57))
        assertEquals("conversation route=PROACTIVE entries=0 chars=0",
            HintPresentation.conversationReport(com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, entries = 0, chars = 0))
    }
}
