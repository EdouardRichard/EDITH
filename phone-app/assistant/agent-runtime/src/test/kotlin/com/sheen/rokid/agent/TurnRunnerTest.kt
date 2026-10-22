package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnRunnerTest {

    private val instructionsSegment = Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "You are a helpful assistant.")
    private val skillSegment = Segment(SegmentOrigin.SKILL, trusted = true, text = "Use polite language.")
    private val memoryPolicySegment = Segment(SegmentOrigin.MEMORY_POLICY, trusted = true, text = "Recall relevant memories.")
    private val emptyTools = emptyList<ToolSpec>()

    @Test
    fun `assembles system slot from instructions skill and memory policy segments`() {
        val runner = TurnRunner(listOf(instructionsSegment, skillSegment, memoryPolicySegment))

        val frame = runner.assemble(
            storeSnapshot = emptyList(),
            memoryEvidence = emptyList(),
            perception = emptyList(),
            userInput = "Hello!",
            tools = emptyTools,
        )

        assertEquals(3, frame.systemSegments.size)
        assertEquals(SegmentOrigin.INSTRUCTIONS, frame.systemSegments[0].origin)
        assertEquals(SegmentOrigin.SKILL, frame.systemSegments[1].origin)
        assertEquals(SegmentOrigin.MEMORY_POLICY, frame.systemSegments[2].origin)
        assertEquals(
            "You are a helpful assistant.\nUse polite language.\nRecall relevant memories.",
            frame.systemText,
        )
    }

    @Test
    fun `places memory evidence before user input in conversation flow`() {
        val runner = TurnRunner(listOf(instructionsSegment))
        val memoryEvidence = listOf(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "User likes coffee."),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "User lives in Shanghai."),
        )

        val frame = runner.assemble(
            storeSnapshot = emptyList(),
            memoryEvidence = memoryEvidence,
            perception = emptyList(),
            userInput = "What do I like?",
            tools = emptyTools,
        )

        // Memory evidence must appear before user input
        val memoryIndices = frame.conversationFlow.mapIndexedNotNull { i, s ->
            if (s.origin == SegmentOrigin.MEMORY_EVIDENCE) i else null
        }
        val userInputIndex = frame.conversationFlow.indexOfFirst { it.origin == SegmentOrigin.USER_INPUT }

        assertTrue(memoryIndices.all { it < userInputIndex }, "All memory evidence must precede USER_INPUT")
        assertEquals(2, frame.memoryEvidenceSegments.size)
    }

    @Test
    fun `user input is never dropped`() {
        val runner = TurnRunner(listOf(instructionsSegment))

        val frame = runner.assemble(
            storeSnapshot = emptyList(),
            memoryEvidence = emptyList(),
            perception = emptyList(),
            userInput = "Important question",
            tools = emptyTools,
        )

        assertEquals("Important question", frame.userInputSegment.text)
        assertEquals(SegmentOrigin.USER_INPUT, frame.conversationFlow.last().origin)
    }

    @Test
    fun `system slot is byte-identical across two assemblies with same inputs`() {
        val runner = TurnRunner(listOf(instructionsSegment, skillSegment, memoryPolicySegment))

        val frame1 = runner.assemble(
            storeSnapshot = emptyList(),
            memoryEvidence = emptyList(),
            perception = emptyList(),
            userInput = "First",
            tools = emptyTools,
        )
        val frame2 = runner.assemble(
            storeSnapshot = emptyList(),
            memoryEvidence = emptyList(),
            perception = emptyList(),
            userInput = "Second",
            tools = emptyTools,
        )

        assertEquals(frame1.systemText, frame2.systemText)
    }

    @Test
    fun `MEMORY_EVIDENCE is not in system slot`() {
        val runner = TurnRunner(listOf(instructionsSegment))
        val memoryEvidence = listOf(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "Some fact."),
        )

        val frame = runner.assemble(
            storeSnapshot = emptyList(),
            memoryEvidence = memoryEvidence,
            perception = emptyList(),
            userInput = "Query",
            tools = emptyTools,
        )

        assertTrue(frame.systemSegments.none { it.origin == SegmentOrigin.MEMORY_EVIDENCE })
        // But it IS in the conversation flow
        assertEquals(1, frame.memoryEvidenceSegments.size)
    }

    @Test
    fun `conversation flow ordering is store plus perception plus memory plus user input`() {
        val runner = TurnRunner(listOf(instructionsSegment))
        val storeSnapshot = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = "Earlier turn."),
        )
        val perception = listOf(
            Segment(SegmentOrigin.PERCEPTION, trusted = false, text = "User is indoors."),
        )
        val memoryEvidence = listOf(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "User prefers tea."),
        )

        val frame = runner.assemble(
            storeSnapshot = storeSnapshot,
            memoryEvidence = memoryEvidence,
            perception = perception,
            userInput = "What should I drink?",
            tools = emptyTools,
        )

        // Expected order: store → perception → memory → user input
        assertEquals(4, frame.conversationFlow.size)
        assertEquals(SegmentOrigin.TRANSCRIPT, frame.conversationFlow[0].origin)
        assertEquals(SegmentOrigin.PERCEPTION, frame.conversationFlow[1].origin)
        assertEquals(SegmentOrigin.MEMORY_EVIDENCE, frame.conversationFlow[2].origin)
        assertEquals(SegmentOrigin.USER_INPUT, frame.conversationFlow[3].origin)
    }
}
