package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Phase 3 acceptance tests (spec §6.1).
 * These verify the structural invariants of the request assembly convergence.
 */
class RequestAssemblyAcceptanceTest {

    @Test
    fun `AC-1 memory evidence is separate message with boundary tag and not merged with user input`() {
        // Build a frame with memory evidence + user input
        val memory = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "<reference_context>\n用户曾让助手查过北京的天气\n</reference_context>")
        val userInput = Segment(SegmentOrigin.USER_INPUT, true, "现在几点了")
        val frame = RequestFrame(
            systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, "You are Edith.")),
            conversationFlow = listOf(memory, userInput),
            tools = emptyList(),
        )

        // Memory evidence is independent (not merged with USER_INPUT)
        assertEquals(2, frame.conversationFlow.size)
        assertEquals(SegmentOrigin.MEMORY_EVIDENCE, frame.conversationFlow[0].origin)
        assertEquals(SegmentOrigin.USER_INPUT, frame.conversationFlow[1].origin)

        // Memory text contains boundary tag
        assertTrue(frame.conversationFlow[0].text.contains("reference_context"))

        // Memory is untrusted
        assertTrue(!frame.conversationFlow[0].trusted)

        // User input is trusted
        assertTrue(frame.conversationFlow[1].trusted)
    }

    @Test
    fun `AC-1b MEMORY_EVIDENCE cannot be placed in system segments`() {
        // Attempting to put MEMORY_EVIDENCE in system slot must fail
        assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = listOf(
                    Segment(SegmentOrigin.INSTRUCTIONS, true, "instructions"),
                    Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "sneaky memory"),
                ),
                conversationFlow = listOf(Segment(SegmentOrigin.USER_INPUT, true, "hello")),
                tools = emptyList(),
            )
        }
    }

    @Test
    fun `AC-3 system slot is byte-identical across consecutive assemblies without compression`() {
        val systemSegments = listOf(
            Segment(SegmentOrigin.INSTRUCTIONS, true, "Base instructions for Edith."),
            Segment(SegmentOrigin.SKILL, true, "Active skill content."),
            Segment(SegmentOrigin.MEMORY_POLICY, true, "Use memory as reference only."),
        )

        // Two frames with SAME system segments but DIFFERENT memory/conversation
        val frame1 = RequestFrame(
            systemSegments = systemSegments,
            conversationFlow = listOf(
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "Memory A"),
                Segment(SegmentOrigin.USER_INPUT, true, "Question 1"),
            ),
            tools = emptyList(),
        )
        val frame2 = RequestFrame(
            systemSegments = systemSegments,
            conversationFlow = listOf(
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "Memory B"),
                Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "Memory C"),
                Segment(SegmentOrigin.USER_INPUT, true, "Question 2"),
            ),
            tools = emptyList(),
        )

        // System slot must be byte-identical regardless of memory/conversation changes
        assertEquals(frame1.systemText, frame2.systemText)

        // Also verify exact bytes
        val bytes1 = frame1.systemText.toByteArray(Charsets.UTF_8)
        val bytes2 = frame2.systemText.toByteArray(Charsets.UTF_8)
        assertTrue(bytes1.contentEquals(bytes2), "System slot bytes must be identical")
    }

    @Test
    fun `AC-1b regression - all non-system origins are rejected from system slot`() {
        val nonSystemOrigins = listOf(
            SegmentOrigin.MEMORY_EVIDENCE,
            SegmentOrigin.PERCEPTION,
            SegmentOrigin.SUMMARY,
            SegmentOrigin.TRANSCRIPT,
            SegmentOrigin.USER_INPUT,
            SegmentOrigin.TOOL_ROUND,
            SegmentOrigin.RETRY,
            SegmentOrigin.RUNTIME_STATE,
            SegmentOrigin.SKILL_BODY,
        )
        for (origin in nonSystemOrigins) {
            assertFailsWith<IllegalArgumentException>(
                message = "Origin $origin should be rejected from system segments"
            ) {
                RequestFrame(
                    systemSegments = listOf(
                        Segment(SegmentOrigin.INSTRUCTIONS, true, "ok"),
                        Segment(origin, true, "bad"),
                    ),
                    conversationFlow = listOf(Segment(SegmentOrigin.USER_INPUT, true, "q")),
                    tools = emptyList(),
                )
            }
        }
    }
}
