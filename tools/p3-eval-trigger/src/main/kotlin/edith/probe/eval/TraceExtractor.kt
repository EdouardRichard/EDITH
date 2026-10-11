package edith.probe.eval

private val TURN_START = Regex("P3_TURN start turn=(\\S+)")
private val TURN_END = Regex("P3_TURN end turn=(\\S+) outcome=(\\S+) code=(\\S+) steps=(\\d+)")
private val TOOL_LINE = Regex("P3_TOOL call=(\\S+) ok=(true|false) detail=(.*)$")

/** One turn while it is being read: the steps seen so far, and whether an end line has arrived. */
private class TurnBuilder(val turnId: String) {
    val steps = mutableListOf<StepTrace>()
    var outcome: String? = null
    var safeCode: String? = null
}

/**
 * Reads the device's own `P3_TURN` / `P3_TOOL` lines into the traces [agentMetrics] scores (P3.4 §6.4).
 *
 * The one rule that must not be blurred is **who did not run**:
 *
 *  - `REFUSED_BY_POLICY` - the policy refused; the executor never ran (`decision = refuse`, `executed = false`);
 *  - `CONFIRMATION_*` - the confirmation was declined, timed out or was unavailable; the executor never ran either
 *    (`decision = require_confirmation`, `executed = false`);
 *  - `Refused: …` - the policy **allowed** it and the privileged channel then refused: the executor *did* run
 *    (`decision = allow`, `executed = true`). Folding this into "refused" would make the escalation rate meaningless.
 *
 * A turn with no `end` line is reported with outcome `UNKNOWN` rather than dropped: it is a turn that did not finish,
 * and dropping it would quietly raise the completion rate.
 *
 * **`degraded` is always false**, and that is a statement about the product rather than about this parser: nothing in
 * production constructs a `ContextPlanner`, so there is no context-degradation event on the device to hold a task
 * through. Reporting a retention rate of 100% would invent the very event that never happened; the metric is reported
 * as not measurable instead.
 */
fun extractTurns(lines: List<String>): List<TurnTrace> {
    val builders = linkedMapOf<String, TurnBuilder>()
    // Turns that have started and not yet ended, oldest first. A `P3_TOOL` line carries no turn id, so the only honest
    // way to place it is "the most recently started turn that is still open" - attributing to the last *started* turn
    // hands a late step to a turn that already ended and reported its own step count.
    val open = mutableListOf<TurnBuilder>()
    for (line in lines) {
        val start = TURN_START.find(line)
        if (start != null) {
            val id = start.groupValues[1]
            val builder = builders.getOrPut(id) { TurnBuilder(id) }
            if (builder !in open) open += builder
            continue
        }
        val end = TURN_END.find(line)
        if (end != null) {
            val builder = builders.getOrPut(end.groupValues[1]) { TurnBuilder(end.groupValues[1]) }
            builder.outcome = end.groupValues[2]
            val code = end.groupValues[3]
            builder.safeCode = if (code == "-") null else code
            open.remove(builder)
            continue
        }
        val tool = TOOL_LINE.find(line)
        if (tool != null) {
            val target = open.lastOrNull() ?: builders.values.lastOrNull() ?: continue
            target.steps += stepOf(tool.groupValues[1], tool.groupValues[2] == "true", tool.groupValues[3].trim())
        }
    }
    return builders.values.map { builder ->
        TurnTrace(
            turnId = builder.turnId,
            outcome = builder.outcome ?: "UNKNOWN",
            safeCode = builder.safeCode,
            steps = builder.steps.toList(),
            degraded = false,
        )
    }
}

private fun stepOf(action: String, ok: Boolean, detail: String): StepTrace {
    val decision = when {
        detail == "REFUSED_BY_POLICY" -> "refuse"
        detail.startsWith("CONFIRMATION_") -> "require_confirmation"
        ok || detail.startsWith("Refused:") -> "allow"
        else -> null
    }
    val executed = ok || detail.startsWith("Refused:")
    return StepTrace(action = action, ok = ok, decision = decision, executed = executed, result = detail)
}
