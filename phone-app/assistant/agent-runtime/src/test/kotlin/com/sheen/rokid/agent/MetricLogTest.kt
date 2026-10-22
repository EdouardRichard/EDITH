package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.RiskLevel
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
 * 0.3 Phase 2 Task 11 moves provider cache accounting to `ModelClientGateway` (one `P3_CACHE` line per model request,
 * asserted in `GatewayCacheMetricTest`); a finished run therefore emits exactly this one line. The shape below is the
 * run-level contract and is unchanged.
 *
 * `images` counts only artifacts produced by this run's tool calls, including one a terminal failed call never carried
 * in a later request; a picture merely carried from previous-turn history is not this run's image.
 *
 * Phase 3 Task 4 adds per-call `P3_TOOLRESULT` lines on the same sink, so a test that asserts the run-level line filters
 * by its `P3_METRIC` prefix. The run-level count keeps its distinct-artifact meaning; the per-call sum is compared with
 * it in `per call P3_TOOLRESULT images sum to the run level distinct count`.
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
        maxSteps: Int = 24,
    ) = AgentLoop(gateway, registry, ScriptedPolicy(), ProgressGuard(), RecordingAuditSink(), clock,
        maxSteps = maxSteps, metricLine = { lines += it })

    @Test
    fun `one run emits exactly one P3_METRIC line with the count fields`() {
        val lines = mutableListOf<String>()
        val clock = FakeMetricClock()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup", observation = true)))
        executor.outcome = { _, _ ->
            clock.now += 3_000_000L
            ActionResult(ActionOutcome.Done, "done")
        }
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "MARKER-PREVIOUS-QUESTION"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "MARKER-PREVIOUS-ANSWER"),
        )
        val gateway = MetricGateway(clock, listOf(
            GatewayReply(listOf(call("c1", "lookup")), "checking", ModelEnd.COMPLETED, null, ModelUsage(12, 5)),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, ModelUsage(3, 2)),
        ))

        loop(gateway, ToolRegistry(listOf(executor)), clock, lines).run(request(history = history))

        assertEquals(1, lines.count { it.startsWith("P3_METRIC") }, lines.toString())
        assertEquals(
            "P3_METRIC calls=2 toolCalls=1 modelMs=8 toolMs=3 observeMs=3 " +
                "inputTokens=15 outputTokens=7 images=0 nudges=0 overflows=0",
            metricLine(lines),
        )
        // The line is counts only: no request/history/tool body, and no credential-shaped text.
        assertFalse(lines.any { it.contains("MARKER") }, lines.toString())
    }

    @Test
    fun `an image carried from previous-turn history is not counted as this run's image`() {
        // P3_METRIC.images is produced-by-this-run only. The caller's transcript may already carry a picture from an
        // earlier turn and this run's requests do carry it, but this run's tool calls produced none.
        val lines = mutableListOf<String>()
        val carried = ImageRef("img-carried", "image/png", 8, 8, "sha-carried")
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "MARKER-PREVIOUS-QUESTION"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "MARKER-PREVIOUS-ANSWER"),
            ModelHistoryItem.ToolCall("c0", "lookup", "{}", "fc0"),
            ModelHistoryItem.ToolOutput("c0", "MARKER-OBSERVATION", listOf(carried)),
        )
        val gateway = MetricGateway(FakeMetricClock(), listOf(
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null),
        ))

        val run = loop(gateway, lines = lines).run(request(history = history))

        assertEquals(0, run.metrics.images, "a carried image is not produced by this run: ${lines.single()}")
        assertTrue(lines.single().contains(" images=0"), lines.single())
    }

    @Test
    fun `a produced image on a terminal failed call is counted even though no later request carries it`() {
        // Task 8 fix: a failure's picture is a picture, but only this run's produced one. The third identical failure
        // ends the run (REPEATED_ERROR) after its output is appended, so no later model request can carry that last
        // image — the append-time produced set must count it.
        val lines = mutableListOf<String>()
        var produced = 0
        val executor = RecordingActionExecutor(listOf(actionDescriptor("open")))
        executor.outcome = { _, _ ->
            produced++
            if (produced == 3) {
                ActionResult(
                    ActionOutcome.Failed,
                    "STATE_UNCHANGED",
                    images = listOf(ImageRef("img-terminal", "image/png", 8, 8, "sha-terminal")),
                )
            } else {
                ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED")
            }
        }
        val replies = (1..3).map { index ->
            // Distinct arguments keep the repeat detector from skipping the call: the error identity is tool+code, so
            // three real failures still terminate as REPEATED_ERROR.
            GatewayReply(
                listOf(call("c$index", "open", """{"step":$index}""")), "opening", ModelEnd.COMPLETED, null, null,
            )
        } + GatewayReply(emptyList(), "unreachable", ModelEnd.COMPLETED, null, null)

        val run = loop(MetricGateway(FakeMetricClock(), replies), ToolRegistry(listOf(executor)), lines = lines)
            .run(request())

        assertEquals(AgentOutcome.FAILED, run.outcome)
        assertEquals("REPEATED_ERROR", run.safeCode)
        assertEquals(1, run.metrics.images, "the terminal failed call produced this run's one image")
        assertTrue(metricLine(lines).contains(" images=1"), metricLine(lines))
        assertFalse(metricLine(lines).contains("sha-terminal"), "the metric line stays count-only: ${metricLine(lines)}")
        assertFalse(metricLine(lines).contains("img-terminal"), "no artifact id reaches the metric line: ${metricLine(lines)}")
    }

    @Test
    fun `two distinct produced images are counted as two`() {
        val lines = mutableListOf<String>()
        val produced = listOf(
            ImageRef("img-a", "image/png", 8, 8, "sha-a"),
            ImageRef("img-b", "image/png", 8, 8, "sha-b"),
        )
        var calls = 0
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup")))
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "done", images = listOf(produced[calls++]))
        }
        val replies = listOf(
            GatewayReply(
                listOf(call("c1", "lookup", """{"page":1}"""), call("c2", "lookup", """{"page":2}""")),
                "checking", ModelEnd.COMPLETED, null, null,
            ),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null),
        )

        val run = loop(MetricGateway(FakeMetricClock(), replies), ToolRegistry(listOf(executor)), lines = lines)
            .run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(2, run.metrics.images, "two distinct artifacts produced by this run are two images")
        assertTrue(metricLine(lines).contains(" images=2"), metricLine(lines))
    }

    @Test
    fun `the same produced artifact twice is one image`() {
        val lines = mutableListOf<String>()
        val image = ImageRef("img-same", "image/png", 8, 8, "sha-same")
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup")))
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "done", images = listOf(image)) }
        val replies = listOf(
            GatewayReply(
                listOf(call("c1", "lookup", """{"page":1}"""), call("c2", "lookup", """{"page":2}""")),
                "checking", ModelEnd.COMPLETED, null, null,
            ),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null),
        )

        val run = loop(MetricGateway(FakeMetricClock(), replies), ToolRegistry(listOf(executor)), lines = lines)
            .run(request())

        assertEquals(AgentOutcome.COMPLETED, run.outcome)
        assertEquals(1, run.metrics.images, "the artifact id keys the produced set")
        assertTrue(metricLine(lines).contains(" images=1"), metricLine(lines))
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
        assertTrue(metricLine(lines).contains(" nudges=3"), metricLine(lines))
    }

    /**
     * Q8=A: the per-call `P3_TOOLRESULT images=` counts add up to the run-level `P3_METRIC images=`.
     *
     * Two calls in one round produce two distinct artifacts, so the per-call counts are `1 + 1` and the run-level
     * distinct count is `2`. Neither line may carry an artifact id or hash.
     */
    @Test
    fun `per call P3_TOOLRESULT images sum to the run level distinct count`() {
        val lines = mutableListOf<String>()
        val produced = listOf(
            ImageRef("img-first", "image/png", 8, 8, "sha-first"),
            ImageRef("img-second", "image/png", 8, 8, "sha-second"),
        )
        var calls = 0
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup")))
        executor.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, "done", images = listOf(produced[calls++]))
        }
        val replies = listOf(
            GatewayReply(
                listOf(call("c1", "lookup", """{"page":1}"""), call("c2", "lookup", """{"page":2}""")),
                "checking", ModelEnd.COMPLETED, null, null,
            ),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null),
        )

        val run = loop(MetricGateway(FakeMetricClock(), replies), ToolRegistry(listOf(executor)), lines = lines)
            .run(request())

        val perCall = lines.filter { it.startsWith("P3_TOOLRESULT") }
            .map { Regex(" images=(\\d+) ").find(it)!!.groupValues[1].toInt() }
        assertEquals(listOf(1, 1), perCall, lines.toString())
        assertEquals(2, perCall.sum())
        assertEquals(run.metrics.images, perCall.sum(), "per-call counts add to the run-level distinct count")
        assertTrue(metricLine(lines).contains(" images=2"), metricLine(lines))
        assertFalse(lines.any { it.contains("img-first") || it.contains("sha-first") }, lines.toString())
    }

    /**
     * 0.3 Phase 4B (B7): the new per-decision `P4_POLICY` line shares this sink, and the run-level `P3_METRIC` shape is
     * **unchanged** by it - the same exact field list and order, with the same numbers as before the line existed.
     */
    @Test
    fun `a policy decision adds one P4_POLICY line and leaves the run metric shape unchanged`() {
        val lines = mutableListOf<String>()
        val clock = FakeMetricClock()
        val executor = RecordingActionExecutor(listOf(actionDescriptor("lookup", risk = RiskLevel.L1)))
        executor.outcome = { _, _ ->
            clock.now += 3_000_000L
            ActionResult(ActionOutcome.Done, "done")
        }
        val gateway = MetricGateway(clock, listOf(
            GatewayReply(listOf(call("c1", "lookup")), "checking", ModelEnd.COMPLETED, null, null),
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, null),
        ))

        loop(gateway, ToolRegistry(listOf(executor)), clock, lines).run(request())

        assertEquals(
            listOf("P4_POLICY mode=normal action=lookup risk=L1 decision=Allow"),
            lines.filter { it.startsWith("P4_POLICY") },
            lines.toString(),
        )
        assertEquals(
            "P3_METRIC calls=2 toolCalls=1 modelMs=8 toolMs=3 observeMs=0 " +
                "inputTokens=missing outputTokens=missing images=0 nudges=0 overflows=0",
            metricLine(lines),
        )
        assertFalse(lines.any { it.contains("MARKER") }, lines.toString())
    }

    // ---------------------------------------------------------------- helpers

    /** The run-level line: Phase 3 Task 4 puts per-call `P3_TOOLRESULT` lines on the same sink. */
    private fun metricLine(lines: List<String>): String = lines.single { it.startsWith("P3_METRIC") }

    private fun runWithUsage(usage: ModelUsage?): String {
        val lines = mutableListOf<String>()
        val gateway = MetricGateway(FakeMetricClock(), listOf(
            GatewayReply(emptyList(), "answer", ModelEnd.COMPLETED, null, usage),
        ))
        loop(gateway, clock = FakeMetricClock(), lines = lines).run(request())
        // The run's only line: per-request cache accounting moved to the gateway (`GatewayCacheMetricTest`).
        return lines.single()
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
