package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 0.3 Phase 2 Task 11 (spec §5.3): the old run-total `P3_CACHE` line is gone from `AgentLoop`.
 *
 * Phase 1 emitted one line per run from `AgentLoop.finish`, which could only describe the summed usage and could not
 * cover the proactive path. Task 11 moves the line into [ModelClientGateway], once per model request; this file pins
 * the other half of the move — an agent run emits exactly one `P3_METRIC` line and **no** `P3_CACHE` line, even when
 * the provider reports cache counters. The per-request line itself is asserted in `GatewayCacheMetricTest`.
 */
class CacheMetricTest {

    private fun request() = AgentRequest(
        turnId = "turn-cache",
        utterance = "把音量调小一点",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = Long.MAX_VALUE,
    )

    private fun loop(
        gateway: ModelGateway,
        clock: FakeClock,
        lines: MutableList<String>,
        registry: ToolRegistry = ToolRegistry(emptyList()),
    ) = AgentLoop(gateway, registry, ScriptedPolicy(), ProgressGuard(), RecordingAuditSink(), clock,
        metricLine = { lines += it })

    @Test
    fun `an agent run emits P3_METRIC and no run-total P3_CACHE line`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock()
        val gateway = CacheGateway(clock, listOf(
            GatewayReply(
                emptyList(), "answer", ModelEnd.COMPLETED, null,
                ModelUsage(inputTokens = 100, outputTokens = 20, cachedInputTokens = 64, cacheMissInputTokens = 36),
            ),
        ))

        val run = loop(gateway, clock, lines).run(request())

        assertEquals(1, lines.size, lines.toString())
        assertEquals(
            "P3_METRIC calls=1 toolCalls=0 modelMs=4 toolMs=0 observeMs=0 " +
                "inputTokens=100 outputTokens=20 images=0 nudges=0 overflows=0",
            lines.single(),
        )
        assertFalse(lines.any { it.startsWith("P3_CACHE") }, lines.toString())
        assertEquals(ModelUsage(100, 20, 64, 36), run.usage)
    }

    @Test
    fun `a multi-turn run still emits no run-total P3_CACHE line`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup")))
        executor.outcome = { _, _ ->
            clock.nowNanos += 3_000_000L
            ActionResult(ActionOutcome.Done, "done")
        }
        val gateway = CacheGateway(clock, listOf(
            GatewayReply(
                listOf(call("c1", "lookup")), "checking", ModelEnd.COMPLETED, null,
                ModelUsage(100, 20, 64, 36),
            ),
            GatewayReply(
                emptyList(), "answer", ModelEnd.COMPLETED, null,
                ModelUsage(10, 5, 6, 4),
            ),
        ))

        val run = loop(gateway, clock, lines, ToolRegistry(listOf(executor))).run(request())

        assertEquals(2, gateway.requests.size, "the tool round must make two model turns for the sum to mean anything")
        // Phase 3 Task 4 shares the sink with one per-call `P3_TOOLRESULT` line; the run-level line is still one.
        val metricLines = lines.filter { it.startsWith("P3_METRIC") }
        assertEquals(1, metricLines.size, lines.toString())
        assertEquals(
            "P3_METRIC calls=2 toolCalls=1 modelMs=8 toolMs=3 observeMs=0 " +
                "inputTokens=110 outputTokens=25 images=0 nudges=0 overflows=0",
            metricLines.single(),
        )
        assertFalse(lines.any { it.startsWith("P3_CACHE") }, lines.toString())
        assertEquals(ModelUsage(110, 25, 70, 40), run.usage)
    }

    /** Fake clock plus a scripted gateway that charges a deterministic 4 ms to every model turn. */
    private class CacheGateway(
        private val clock: FakeClock,
        private val replies: List<GatewayReply>,
    ) : ModelGateway {
        val requests = mutableListOf<GatewayTurn>()

        override fun turn(request: GatewayTurn): GatewayReply {
            requests += request
            clock.nowNanos += 4_000_000L
            return replies[requests.size - 1]
        }
    }
}
