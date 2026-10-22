package edith.probe.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4A Task 9 (A9): the K40 latency matrix's only input is the loop's count-only `P4_TOOL` line
 * (`P4_TOOL tool=<name> ms=<millis> outcome=<wire>`).
 *
 * Two rules carry this file:
 *
 *  - **Only that line is read.** `P3_METRIC`/`P3_TOOLRESULT`, audit prose and a line that merely mentions the marker are
 *    not readings; a malformed `P4_TOOL` is skipped rather than guessed at, because inventing a reading would turn
 *    "no evidence" into a latency number.
 *  - **No readings is `NotMeasured`, never `0 ms`.** A tool that never ran, or a log with no latency lines at all, has
 *    an unknown latency; reporting zero would claim a measurement nobody took.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class P4ToolMetricsTest {

    private val log = listOf(
        // A real logcat line carries a timestamp/pid prefix; the marker's own field order is what is read.
        "10-22 05:00:00.100  1000  1000 I Edith   : P3_METRIC calls=2 toolCalls=2 modelMs=20 toolMs=15",
        "P4_TOOL tool=lookup ms=10 outcome=done",
        "P3_TOOLRESULT turn=t1 call=c1 tool=lookup outcome=done code=- recoverable=false",
        "P4_TOOL tool=open ms=5 outcome=failed",
        "P4_TOOL tool=lookup ms=30 outcome=done",
        "P4_TOOL tool=lookup ms=20 outcome=done",
        "10-22 05:01:02.345  1234  1234 I Edith   : P4_TOOL tool=open ms=15 outcome=done",
        "some prose mentioning P4_TOOL without the line shape",
    )

    @Test fun `only P4_TOOL lines are read and readings aggregate per tool`() {
        val metrics = parseP4ToolMetrics(log)

        assertEquals(5, metrics.readings)
        assertEquals(listOf("lookup", "open"), metrics.tools.map { it.tool }, "first-seen order, one entry per tool")
        val lookup = metrics.forTool("lookup")
        assertEquals(3, lookup.readings)
        assertEquals(20L, lookup.p50Millis)
        assertEquals(30L, lookup.p95Millis)
        assertEquals("Measured", lookup.status)
        val open = metrics.forTool("open")
        assertEquals(2, open.readings)
        assertEquals(5L, open.p50Millis)
        assertEquals(15L, open.p95Millis)
        assertEquals("Measured", metrics.status)
    }

    @Test fun `no readings reports NotMeasured rather than zero latency`() {
        val metrics = parseP4ToolMetrics(
            listOf(
                "P3_METRIC calls=1 toolCalls=0 modelMs=5 toolMs=0 observeMs=0 inputTokens=missing",
                "P3_TOOLRESULT turn=t1 call=c1 tool=lookup outcome=done code=- recoverable=false",
                "",
            ),
        )

        assertEquals(0, metrics.readings)
        assertEquals("NotMeasured", metrics.status)
        assertTrue(metrics.tools.isEmpty(), "a log with no P4_TOOL line has no tool entries: ${metrics.tools}")
        val lookup = metrics.forTool("lookup")
        assertEquals(0, lookup.readings)
        assertEquals("NotMeasured", lookup.status)
        assertNull(lookup.p50Millis)
        assertNull(lookup.p95Millis)
    }

    @Test fun `percentiles are nearest rank over the sorted samples`() {
        // 1..20: the 10th value is p50 and the 19th is p95 under nearest rank. An off-by-one would move either.
        val lines = (1..20).map { "P4_TOOL tool=tap ms=$it outcome=done" }

        val tap = parseP4ToolMetrics(lines).forTool("tap")

        assertEquals(20, tap.readings)
        assertEquals(10L, tap.p50Millis)
        assertEquals(19L, tap.p95Millis)
    }

    @Test fun `malformed or shape breaking P4_TOOL lines are skipped rather than guessed at`() {
        val metrics = parseP4ToolMetrics(
            listOf(
                "P4_TOOL tool=lookup ms=10 outcome=done",
                "P4_TOOL tool=lookup ms=10",
                "P4_TOOL lookup ms=10 outcome=done",
                "P4_TOOL tool=lookup ms=ten outcome=done",
                "P4_TOOL tool=lookup ms=-1 outcome=done",
                "P4_TOOL tool=lookup ms=10 outcome=done arguments={\"secret\":\"MARKER\"}",
                "P4_TOOL tool=lookup ms=10 outcome=",
                "P4_TOOLXP4_TOOL tool=lookup ms=10 outcome=done",
            ),
        )

        assertEquals(1, metrics.readings, "only the one exact line may be read: ${metrics.tools}")
    }
}
