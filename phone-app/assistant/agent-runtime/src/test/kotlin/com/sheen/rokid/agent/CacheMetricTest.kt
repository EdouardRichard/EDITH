package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.3 Phase 1 Task 13 (spec §5.3): the provider's prompt-cache telemetry reaches one count-only `P3_CACHE` line per run,
 * **after** the existing `P3_METRIC` line and without changing that line's shape.
 *
 * The line is asserted on its exact field list and order, because Phase 2/4 read it. Three states are pinned separately:
 * a run with no accounting at all omits the line, an unreported counter is `-` (never a fabricated zero), and a real
 * report — including an explicit zero — is the count itself. Cache counts are summed over **every** model turn of the
 * run, so a tool round reports both turns' cache counts rather than the last turn's.
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
    fun `P3_CACHE reports provider cache counts without changing P3_METRIC`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock()
        val gateway = CacheGateway(clock, listOf(
            GatewayReply(
                emptyList(), "answer", ModelEnd.COMPLETED, null,
                ModelUsage(inputTokens = 100, outputTokens = 20, cachedInputTokens = 64, cacheMissInputTokens = 36),
            ),
        ))

        val run = loop(gateway, clock, lines).run(request())

        assertEquals(2, lines.size, lines.toString())
        // The old line is byte-identical: cache counts never join it.
        assertEquals(
            "P3_METRIC calls=1 toolCalls=0 modelMs=4 toolMs=0 observeMs=0 " +
                "inputTokens=100 outputTokens=20 images=0 nudges=0 overflows=0",
            lines.first(),
        )
        assertEquals("P3_CACHE cached=64 miss=36 inputTokens=100 outputTokens=20", lines.last())
        assertEquals(ModelUsage(100, 20, 64, 36), run.usage)
    }

    @Test
    fun `a tool round sums both cache counters across turns`() {
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
        assertEquals(2, lines.size, lines.toString())
        // A multi-turn run must not lose the cache counts, and must not reshape `P3_METRIC` either.
        assertEquals(
            "P3_METRIC calls=2 toolCalls=1 modelMs=8 toolMs=3 observeMs=0 " +
                "inputTokens=110 outputTokens=25 images=0 nudges=0 overflows=0",
            lines.first(),
        )
        assertEquals("P3_CACHE cached=70 miss=40 inputTokens=110 outputTokens=25", lines.last())
        assertFalse(lines.first().contains("cached="), "the old line must keep its exact shape: ${lines.first()}")
        assertEquals(ModelUsage(110, 25, 70, 40), run.usage)
    }

    @Test
    fun `a cache counter no turn reports stays absent instead of becoming zero`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock()
        // Two turns, neither reports a miss count: the summed miss stays absent (`-`), not 0.
        val gateway = CacheGateway(clock, listOf(
            GatewayReply(emptyList(), "", ModelEnd.COMPLETED, null, ModelUsage(10, 5, 64, null)),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, ModelUsage(20, 7, 6, null)),
        ))

        loop(gateway, clock, lines).run(request())

        assertEquals("P3_CACHE cached=70 miss=- inputTokens=30 outputTokens=12", lines.last())
    }

    @Test
    fun `a run with no provider accounting omits P3_CACHE`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock()
        val gateway = CacheGateway(clock, listOf(
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null),
        ))

        loop(gateway, clock, lines).run(request())

        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines.single().startsWith("P3_METRIC "), lines.single())
        assertFalse(lines.any { it.startsWith("P3_CACHE") }, lines.toString())
    }

    @Test
    fun `usage without cache counters prints dashes instead of a fabricated zero`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock()
        val gateway = CacheGateway(clock, listOf(
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, ModelUsage(100, 20)),
        ))

        loop(gateway, clock, lines).run(request())

        // Usage exists, so the line is emitted; the provider reported no cache counts, so each is `-` — not 0.
        assertEquals("P3_CACHE cached=- miss=- inputTokens=100 outputTokens=20", lines.last())
        assertFalse(lines.last().contains("cached=0"), lines.last())
        assertFalse(lines.last().contains("miss=0"), lines.last())
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
