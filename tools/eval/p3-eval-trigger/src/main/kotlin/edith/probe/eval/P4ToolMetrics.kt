package edith.probe.eval

/**
 * Phase 4A Task 9 (A9): the per-tool latency view of a device log, built from the loop's count-only `P4_TOOL` lines.
 *
 * The line is `P4_TOOL tool=<name> ms=<millis> outcome=<wire>` and carries nothing else; this parser reads exactly that
 * shape and ignores every other line (including `P3_METRIC`/`P3_TOOLRESULT` and prose that merely mentions the marker).
 * A malformed line is skipped, never guessed at: inventing a reading would turn "no evidence" into a latency number.
 *
 * The percentile is nearest rank over the tool's ascending samples: `p50` is the lower median for an even count and
 * `p95` is the 19th of 20 readings, not the maximum. A tool with no reading is [NOT_MEASURED] — unknown latency, never
 * `0 ms`.
 */
data class P4ToolMetrics(val tools: List<P4ToolLatency>) {

    /** Every reading of the log, summed over the tools. */
    val readings: Int get() = tools.sumOf { it.readings }

    /** [MEASURED] when the log contains at least one reading, else [NOT_MEASURED]. */
    val status: String get() = if (readings == 0) NOT_MEASURED else MEASURED

    /** The summary for [tool]; a tool with no reading is reported as [NOT_MEASURED] rather than absent. */
    fun forTool(tool: String): P4ToolLatency =
        tools.firstOrNull { it.tool == tool } ?: P4ToolLatency(tool, readings = 0, p50Millis = null, p95Millis = null)

    companion object {
        /** The literal an unmeasured tool or log carries (spec §7.2): no reading is not a zero-millisecond reading. */
        const val NOT_MEASURED = "NotMeasured"
        const val MEASURED = "Measured"
    }
}

/**
 * One tool's latency over the readings of a log.
 *
 * [p50Millis]/[p95Millis] are `null` exactly when [readings] is `0`, and [status] then says [P4ToolMetrics.NOT_MEASURED].
 */
data class P4ToolLatency(
    val tool: String,
    val readings: Int,
    val p50Millis: Long?,
    val p95Millis: Long?,
) {
    val status: String get() = if (readings == 0) P4ToolMetrics.NOT_MEASURED else P4ToolMetrics.MEASURED
}

/**
 * The strict shape a `P4_TOOL` message must have to count as a reading.
 *
 * A logcat line carries a timestamp/pid prefix, so the marker may be preceded by text; the fields themselves must run
 * to the end of the line and carry no extra member (a trailing `arguments=` or an empty `outcome=` is not this line).
 * The marker must be a standalone token: text glued to it (`P4_TOOLX…`) is not a reading.
 */
private val P4_TOOL_LINE = Regex("""(?:^|\s)P4_TOOL tool=(\S+) ms=(\d+) outcome=(\S+)$""")

/**
 * Reads only the exact `P4_TOOL` shape and aggregates the readings per tool, in first-seen log order.
 *
 * Only the tool name and the millisecond value are kept: `outcome` is parsed as part of the line's contract but is not
 * aggregated, because this view answers "how slow is each tool", not "how did it end" (`P3_TOOLRESULT` owns that).
 * A non-numeric `ms`, a negative one, or a line with missing/extra fields is skipped rather than guessed at.
 */
fun parseP4ToolMetrics(lines: List<String>): P4ToolMetrics {
    val samples = linkedMapOf<String, MutableList<Long>>()
    for (line in lines) {
        val match = P4_TOOL_LINE.find(line.trimEnd()) ?: continue
        val millis = match.groupValues[2].toLongOrNull() ?: continue
        samples.getOrPut(match.groupValues[1]) { mutableListOf() } += millis
    }
    return P4ToolMetrics(
        samples.map { (tool, readings) ->
            val sorted = readings.sorted()
            P4ToolLatency(
                tool = tool,
                readings = sorted.size,
                p50Millis = percentile(sorted, 50),
                p95Millis = percentile(sorted, 95),
            )
        },
    )
}

/** Nearest rank: the `ceil(count × percent / 100)`-th smallest sample, clamped to the list. */
private fun percentile(sorted: List<Long>, percent: Int): Long {
    val rank = (sorted.size.toLong() * percent + 99) / 100
    return sorted[(rank - 1).coerceIn(0, sorted.lastIndex.toLong()).toInt()]
}
