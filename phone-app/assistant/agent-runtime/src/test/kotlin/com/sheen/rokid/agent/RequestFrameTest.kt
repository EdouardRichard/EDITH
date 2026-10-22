package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestFrameTest {

    private val instructionsSegment = Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "You are a helpful assistant.")
    private val skillSegment = Segment(SegmentOrigin.SKILL, trusted = true, text = "Use polite language.")
    private val memoryPolicySegment = Segment(SegmentOrigin.MEMORY_POLICY, trusted = true, text = "Recall relevant memories.")
    private val userInputSegment = Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "Hello!")
    private val transcriptSegment = Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = "Previous turn.")
    private val memoryEvidenceSegment = Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "User likes coffee.")
    private val emptyTools = emptyList<ToolSpec>()

    @Test
    fun `system slot is concatenation of INSTRUCTIONS SKILL and MEMORY_POLICY segments`() {
        val frame = RequestFrame(
            systemSegments = listOf(instructionsSegment, skillSegment, memoryPolicySegment),
            conversationFlow = listOf(userInputSegment),
            tools = emptyTools,
        )

        assertEquals(
            "You are a helpful assistant.\nUse polite language.\nRecall relevant memories.",
            frame.systemText,
        )
    }

    @Test
    fun `memory evidence segments are separate from system slot`() {
        val frame = RequestFrame(
            systemSegments = listOf(instructionsSegment),
            conversationFlow = listOf(memoryEvidenceSegment, userInputSegment),
            tools = emptyTools,
        )

        assertEquals(listOf(memoryEvidenceSegment), frame.memoryEvidenceSegments)
        assertTrue(frame.systemSegments.none { it.origin == SegmentOrigin.MEMORY_EVIDENCE })
    }

    @Test
    fun `user input segment is always last in conversation flow`() {
        val frame = RequestFrame(
            systemSegments = listOf(instructionsSegment),
            conversationFlow = listOf(transcriptSegment, memoryEvidenceSegment, userInputSegment),
            tools = emptyTools,
        )

        assertEquals(userInputSegment, frame.userInputSegment)
        assertEquals(userInputSegment, frame.conversationFlow.last())
    }

    @Test
    fun `frame requires at least one system segment`() {
        assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = emptyList(),
                conversationFlow = listOf(userInputSegment),
                tools = emptyTools,
            )
        }
    }

    @Test
    fun `frame requires exactly one USER_INPUT segment — zero`() {
        assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = listOf(instructionsSegment),
                conversationFlow = listOf(transcriptSegment),
                tools = emptyTools,
            )
        }
    }

    @Test
    fun `frame requires exactly one USER_INPUT segment — two`() {
        val secondUserInput = Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "Second input")
        assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = listOf(instructionsSegment),
                conversationFlow = listOf(userInputSegment, secondUserInput),
                tools = emptyTools,
            )
        }
    }

    @Test
    fun `MEMORY_EVIDENCE cannot be in system segments`() {
        assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = listOf(instructionsSegment, memoryEvidenceSegment),
                conversationFlow = listOf(userInputSegment),
                tools = emptyTools,
            )
        }
    }

    @Test
    fun `system segments reject non-system origins`() {
        assertFailsWith<IllegalArgumentException> {
            RequestFrame(
                systemSegments = listOf(instructionsSegment, transcriptSegment),
                conversationFlow = listOf(userInputSegment),
                tools = emptyTools,
            )
        }
    }
}
