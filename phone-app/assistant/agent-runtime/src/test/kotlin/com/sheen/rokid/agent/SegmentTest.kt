package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentTest {

    @Test fun `SegmentOrigin has exactly 12 values and each named value exists`() {
        val values = SegmentOrigin.values()
        assertEquals(12, values.size)

        val expected = setOf(
            "INSTRUCTIONS", "SKILL", "MEMORY_POLICY", "MEMORY_EVIDENCE",
            "PERCEPTION", "SUMMARY", "TRANSCRIPT", "USER_INPUT",
            "TOOL_ROUND", "RETRY", "RUNTIME_STATE", "SKILL_BODY"
        )
        val actual = values.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test fun `Segment construction with blank text throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "")
        }
        assertFailsWith<IllegalArgumentException> {
            Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "   ")
        }
    }

    @Test fun `trusted flag is independent of origin`() {
        val trusted = Segment(SegmentOrigin.TOOL_ROUND, trusted = true, text = "data")
        val untrusted = Segment(SegmentOrigin.TOOL_ROUND, trusted = false, text = "data")

        assertTrue(trusted.trusted)
        assertFalse(untrusted.trusted)
        assertEquals(trusted.origin, untrusted.origin)
        assertEquals(trusted.text, untrusted.text)
    }

    @Test fun `Segment with valid inputs constructs successfully`() {
        val segment = Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "system prompt")

        assertEquals(SegmentOrigin.INSTRUCTIONS, segment.origin)
        assertTrue(segment.trusted)
        assertEquals("system prompt", segment.text)
    }
}
