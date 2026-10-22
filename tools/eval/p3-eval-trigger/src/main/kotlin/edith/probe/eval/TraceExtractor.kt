package edith.probe.eval

private val TURN_START = Regex("P3_TURN start turn=(\\S+)")
private val TURN_END = Regex("P3_TURN end turn=(\\S+) outcome=(\\S+) code=(\\S+) steps=(\\d+)")
private val TOOL_LINE = Regex("P3_TOOL call=(\\S+) ok=(true|false) detail=(.*)$")

private const val REFUSED_BY_POLICY = "REFUSED_BY_POLICY"
private const val CONFIRMATION_PREFIX = "CONFIRMATION_"
private const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"

/** A detector's synthetic warning/critical label; the call it describes was never dispatched. */
private const val LOOP_PREFIX = "LOOP_"

/**
 * The closed set of **bare** pre-dispatch codes the framework logs for a call that never reached an executor.
 *
 * `REFUSED_BY_POLICY` and the `CONFIRMATION_*` family also drive the step's decision (`refuse` /
 * `require_confirmation`); `UNKNOWN_TOOL` / `ARGUMENTS_INVALID` are registry errors with no decision. The two prefix
 * families are matched separately because they may carry an explanation after the code (`CONFIRMATION_DENIED`,
 * `LOOP_REPEAT: ...`). A new pre-dispatch code that is missing from this set is scored as executed - update it with
 * the code.
 */
private val PRE_DISPATCH_CODES = setOf(REFUSED_BY_POLICY, UNKNOWN_TOOL, ARGUMENTS_INVALID)

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
 *  - `UNKNOWN_TOOL` / `ARGUMENTS_INVALID` - the registry rejected the call before dispatch (`decision = null`,
 *    `executed = false`);
 *  - a detector's `LOOP_*` warning - the call was skipped before dispatch (`decision = null`, `executed = false`);
 *  - **any other failed stable code** - the policy **allowed** it and the executor ran; the tool, the privileged
 *    channel or the post-call verdict then failed/refused (`decision = allow`, `executed = true`). Folding this into
 *    "refused" would make the escalation rate meaningless.
 *
 * 0.3 Phase 2.2: `P3_TOOL detail=` is the loop's count-only step label (`AgentLoop.stepResultOf`), not the old tool
 * detail: for a migrated tool it is the envelope-derived `execution.code` (or `ok`), for an unmigrated one the code
 * `legacyCodeOf(data)` extracts from its legacy detail, and otherwise the outcome token. It is therefore prefix-free
 * on the current wire. That prefix used to be the only evidence that a failed call had been dispatched, so the
 * pre-dispatch codes above are now the complete "did not run" list; the parser keys on them rather than on a prefix.
 * A replayed pre-0.3 log can still carry the old `"Refused: "` / `"Failed: "` prefixes: they are opaque text here and
 * do not turn a dispatched failure into a pre-dispatch rejection.
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
    val dispatched = ok || !wasRejectedBeforeDispatch(detail)
    val decision = when {
        detail == REFUSED_BY_POLICY -> "refuse"
        detail.startsWith(CONFIRMATION_PREFIX) -> "require_confirmation"
        dispatched -> "allow"
        else -> null
    }
    return StepTrace(action = action, ok = ok, decision = decision, executed = dispatched, result = detail)
}

/**
 * Whether a `P3_TOOL detail=` value names a call the framework rejected **before** the executor ran.
 *
 * The current envelope-derived shape carries the bare `execution.code`, so "did not run" has to be a closed set:
 * the registry's two pre-dispatch errors, the loop's policy/confirmation codes, and a detector's `LOOP_*` label
 * ([PRE_DISPATCH_CODES] plus the two prefix families). Every other failed code - `STATE_UNCHANGED`,
 * `ACTION_CHANNEL_UNAVAILABLE`, `ACTION_TIMEOUT`, `TOOL_THREW`, a tool's own refusal - comes from a call that really
 * was dispatched. The old `Refused:`/`Failed:` prefix is deliberately not part of this rule; it no longer exists on
 * the wire.
 */
private fun wasRejectedBeforeDispatch(detail: String): Boolean =
    detail in PRE_DISPATCH_CODES ||
        detail.startsWith(CONFIRMATION_PREFIX) ||
        detail.startsWith(LOOP_PREFIX)
