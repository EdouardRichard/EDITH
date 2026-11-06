package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelUsage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5 review fix: the three value types that can carry a chain of thought (`AgentRun`, `GatewayReply`, `Segment`) used to
 * inherit their data-class `toString()`, which prints **every** member — answer text, reasoning and, for a run, the
 * whole transcript. A single stray log line, debugger view or assertion message would then leak content that the design
 * keeps memory-only. Each type now renders a fixed, content-free shape instead.
 */
class RedactedToStringTest {

    private val answer = "SECRET-ANSWER"
    private val reasoning = "SECRET-REASONING"

    @Test
    fun `the run reply and segment toString never print answer or reasoning text`() {
        val run = AgentRun(
            outcome = AgentOutcome.COMPLETED,
            safeCode = null,
            finalText = answer,
            steps = listOf(AgentStep(1, "device", answer)),
            transcript = listOf(ModelHistoryItem.Text(MessageRole.ASSISTANT, answer, reasoning)),
            finalReasoning = reasoning,
        )
        val reply = GatewayReply(
            toolCalls = emptyList(),
            text = answer,
            terminal = ModelEnd.COMPLETED,
            safeCode = null,
            usage = ModelUsage(1, 2),
            reasoning = reasoning,
        )
        val segment = Segment(
            origin = SegmentOrigin.TRANSCRIPT,
            trusted = true,
            text = answer,
            role = SegmentRole.ASSISTANT,
            reasoning = reasoning,
        )

        for (rendered in listOf(run.toString(), reply.toString(), segment.toString())) {
            assertFalse(rendered.contains(answer), "the answer text must not leak: $rendered")
            assertFalse(rendered.contains(reasoning), "the reasoning must not leak: $rendered")
            assertTrue(rendered.contains("redacted"), "the content-free shape must say so: $rendered")
        }
    }

    @Test
    fun `the content-free shapes expose only non-content fields`() {
        val run = AgentRun(
            outcome = AgentOutcome.FAILED,
            safeCode = "DEADLINE",
            finalText = answer,
            steps = listOf(AgentStep(1, "device", answer), AgentStep(2, "notify_user", answer)),
            finalReasoning = reasoning,
        )
        val reply = GatewayReply(listOf(ToolCall("c1", "lookup", "{}")), answer, ModelEnd.INCOMPLETE, "X", null, reasoning = reasoning)
        val segment = Segment(SegmentOrigin.TOOL_ROUND, true, answer, SegmentRole.USER)

        assertTrue(run.toString().contains("outcome=FAILED"))
        assertTrue(run.toString().contains("steps=2"))
        assertTrue(reply.toString().contains("toolCalls=1"))
        assertTrue(reply.toString().contains("terminal=INCOMPLETE"))
        assertTrue(segment.toString().contains("origin=TOOL_ROUND"))
        assertTrue(segment.toString().contains("role=USER"))
    }
}
