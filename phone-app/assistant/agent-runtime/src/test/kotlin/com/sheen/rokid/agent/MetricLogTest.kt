package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 Task 14 (R78): one `P3_METRIC` line per agent run, carrying **counts only**.
 *
 * The line is asserted on its exact field list and order, because Task 15 reads it: a renamed field is a silently
 * unread metric. The usage three-state rule (R21) is pinned separately: no accounting at all is `missing`, a reported
 * usage object with both counters absent is `unknown`, and a real zero is `0`.
 *
 * 0.3 Phase 1 Task 13 adds a **separate** `P3_CACHE` line when the run reported accounting; `P3_METRIC` keeps its
 * exact shape. This file still asserts that old shape, and filters for it rather than assuming it is the only line.
 */
class MetricLogTest {

    private fun request(
        history: List<ModelHistoryItem> = emptyList(),
        allowPlanNudge: Boolean = false,
        utterance: String = "把音量调小一点",
        instructions: String = "You are Edith.",
    ) = AgentRequest(
        turnId = "turn-metric",
        utterance = utterance,
        instructions = instructions,
        profileId = "deepseek-default",
        deadlineNanos = Long.MAX_VALUE,
        history = history,
        allowPlanNudge = allowPlanNudge,
    )

    private fun loop(
        gateway: ModelGateway,
        registry: ToolRegistry = ToolRegistry(emptyList()),
        clock: MonotonicClock = FakeMetricClock(),
        lines: MutableList<String>,
    ) = AgentLoop(gateway, registry, ScriptedPolicy(), ProgressGuard(), RecordingAuditSink(), clock,
        metricLine = { lines += it })

    @Test
    fun `one run emits one metric line with exactly the count fields`() {
        val lines = mutableListOf<String>()
        val clock = FakeMetricClock()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup", observation = true)))
        executor.outcome = { _, _ ->
            clock.now += 3_000_000L
            ActionResult(ActionOutcome.Done, "done")
        }
        val image = ImageRef("img-1", "image/png", 8, 8, "sha")
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "MARKER-PREVIOUS-QUESTION"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "MARKER-PREVIOUS-ANSWER"),
            ModelHistoryItem.ToolCall("c0", "lookup", "{}", "fc0"),
            ModelHistoryItem.ToolOutput("c0", "MARKER-OBSERVATION", listOf(image)),
        )
        val gateway = MetricGateway(clock, listOf(
            GatewayReply(listOf(call("c1", "lookup")), "checking", ModelEnd.COMPLETED, null, ModelUsage(12, 5)),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, ModelUsage(3, 2)),
        ))

        loop(gateway, ToolRegistry(listOf(executor)), clock, lines).run(request(history = history))

        assertEquals(2, lines.size, lines.toString())
        assertEquals(
            "P3_METRIC calls=2 toolCalls=1 modelMs=8 toolMs=3 observeMs=3 " +
                "inputTokens=15 outputTokens=7 images=1 nudges=0 overflows=0",
            lines.first(),
        )
        // The usage the run reported had no cache counters, so the separate line is present with both as `-`.
        assertEquals("P3_CACHE cached=- miss=- inputTokens=15 outputTokens=7", lines.last())
        // Both lines are counts only: no request/history/tool body, and no credential-shaped text.
        assertFalse(lines.joinToString("\n").contains("MARKER"), lines.toString())
    }

    @Test
    fun `usage null is missing null counters are unknown and a real zero is zero`() {
        assertEquals(
            "missing/missing",
            tokenFields(runWithUsage(null)),
        )
        assertEquals(
            "unknown/unknown",
            tokenFields(runWithUsage(ModelUsage(null, null))),
        )
        assertEquals(
            "0/0",
            tokenFields(runWithUsage(ModelUsage(0, 0))),
        )
        assertEquals(
            "unknown/4",
            tokenFields(runWithUsage(ModelUsage(null, 4))),
        )
    }

    @Test
    fun `overflow recoveries are counted in the metric line`() {
        val lines = mutableListOf<String>()
        val gateway = MetricGateway(FakeMetricClock(), listOf(
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null, overflowRecoveries = 2),
        ))

        loop(gateway, clock = FakeMetricClock(), lines = lines).run(request())

        assertTrue(lines.single().contains(" overflows=2"), lines.single())
    }

    @Test
    fun `every emitted plan nudge is counted`() {
        val lines = mutableListOf<String>()
        val plan = "接下来我会先观察当前屏幕，然后再点按确认。"
        val replies = listOf(
            GatewayReply(listOf(call("c1", "lookup")), "checking", ModelEnd.COMPLETED, null, null),
            GatewayReply(emptyList(), "$plan 一", ModelEnd.COMPLETED, null, null),
            GatewayReply(emptyList(), "$plan 二", ModelEnd.COMPLETED, null, null),
            GatewayReply(emptyList(), "$plan 三", ModelEnd.COMPLETED, null, null),
            GatewayReply(emptyList(), "$plan 四", ModelEnd.COMPLETED, null, null),
        )
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup")))

        val run = loop(MetricGateway(FakeMetricClock(), replies), ToolRegistry(listOf(executor)),
            lines = lines).run(request(allowPlanNudge = true))

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertTrue(lines.single().contains(" nudges=3"), lines.single())
    }

    // ---------------------------------------------------------------- helpers

    private fun runWithUsage(usage: ModelUsage?): String {
        val lines = mutableListOf<String>()
        val gateway = MetricGateway(FakeMetricClock(), listOf(
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, usage),
        ))
        loop(gateway, clock = FakeMetricClock(), lines = lines).run(request())
        // The `P3_METRIC` line only: the separate P3_CACHE line carries its own three-state rule in CacheMetricTest.
        return lines.single { it.startsWith("P3_METRIC ") }
    }

    private fun tokenFields(line: String): String {
        val match = Regex("inputTokens=(\\S+) outputTokens=(\\S+)").find(line)
            ?: error("no usage fields in: $line")
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    /** A clock the fake gateway advances so model milliseconds are deterministic instead of wall-clock flaky. */
    private class FakeMetricClock(var now: Long = 0L) : MonotonicClock {
        override fun nowNanos(): Long = now
    }

    /** Scripted gateway that charges a deterministic 4 ms to every model turn and records each request. */
    private class MetricGateway(
        private val clock: FakeMetricClock,
        private val replies: List<GatewayReply>,
    ) : ModelGateway {
        val requests = mutableListOf<GatewayTurn>()
        override fun turn(request: GatewayTurn): GatewayReply {
            requests += request
            clock.now += 4_000_000L
            return replies[requests.size - 1]
        }
    }
}
