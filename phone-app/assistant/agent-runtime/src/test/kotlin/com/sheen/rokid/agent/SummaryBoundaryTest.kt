package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryBoundaryTest {

    @Test fun `render wraps the body in the open and close markers`() {
        val rendered = SummaryBoundary.render("earlier talk")
        assertTrue(rendered.startsWith(SummaryBoundary.OPEN), rendered)
        assertTrue(rendered.endsWith(SummaryBoundary.CLOSE), rendered)
        assertTrue("earlier talk" in rendered)
    }

    @Test fun `the open marker carries origin=SUMMARY`() {
        assertTrue("origin=\"SUMMARY\"" in SummaryBoundary.OPEN, SummaryBoundary.OPEN)
    }

    @Test fun `the marker wording cannot be read as the wearer speaking`() {
        // H1: 措辞"不可能被读成用户发言"——不得出现第一人称、不得含权威词。
        val marker = SummaryBoundary.OPEN + SummaryBoundary.CLOSE
        for (forbidden in listOf("我已经", "用户说", "已核实", "可靠", "权威", "trusted")) {
            assertFalse(forbidden in marker, "boundary must not contain '$forbidden': $marker")
        }
    }

    @Test fun `isRendered recognises exactly what render produced`() {
        val rendered = SummaryBoundary.render("body")
        assertTrue(SummaryBoundary.isRendered(rendered))
        assertFalse(SummaryBoundary.isRendered("body"))
        // 一个用户发言里出现 OPEN 但整体不是 render 的产物 ⇒ 不是摘要。
        assertFalse(SummaryBoundary.isRendered("${SummaryBoundary.OPEN} body"))
    }

    @Test fun `Segment role defaults to null so existing positional calls compile unchanged`() {
        val s = Segment(SegmentOrigin.TRANSCRIPT, true, "text")
        assertEquals(null, s.role)
        assertEquals(SegmentRole.ASSISTANT, s.copy(role = SegmentRole.ASSISTANT).role)
    }
}
