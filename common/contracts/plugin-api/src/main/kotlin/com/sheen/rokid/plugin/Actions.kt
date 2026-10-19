package com.sheen.rokid.plugin

/** Upper bound for [ActionDescriptor.parametersJson]: 16 KB. */
private const val MAX_PARAMETERS_JSON_LENGTH = 16 * 1024

enum class RiskLevel { L0, L1, L2, L3, L4 }

enum class ActionOutcome { Done, Refused, Failed }

/**
 * Platform-independent description of one action a plugin can execute.
 *
 * [parametersJson] is the argument schema the host shows for confirmation; it must be *shaped* like a JSON object so a
 * caller can address its members by name (`ActionExecutor.execute` receives matching `argsJson`).
 *
 * `plugin-api` stays dependency-free, so validation is structural only: non-blank, `trim()` starts with `{` and ends
 * with `}`, and at most 16 KB. Whether the payload is well-formed JSON is enforced later, when `agent-runtime`'s
 * `ToolRegistry` registers the descriptor.
 */
data class ActionDescriptor(
    val name: String,
    val title: String,
    val parametersJson: String,
    val risk: RiskLevel,
    val requiresConfirmation: Boolean,
    val idempotent: Boolean,
    val reversible: Boolean,
    val scope: String,
    /**
     * P3.3 (spec §17.5): the name of the one argument, if any, this action's executor declares **safe to audit**.
     *
     * The audit trail records an action's target so a line answers "what did it touch", but raw arguments never leave
     * the executor: the executor is the only side that knows which member is a benign identifier (`page`, `package`,
     * `permission`) and which is user content. `null` — the default, and what `dial` declares — means no argument is
     * ever recorded for this action, so a phone number cannot reach the trail.
     */
    val auditTargetArgument: String? = null,
    /**
     * Phase 6 (design D-9): whether one call of this action spends a step of the agent loop's `maxSteps` hang guard.
     *
     * `maxSteps` is not a budget (spec D-P3-10): it only stops a run that would otherwise call tools forever. An action
     * that merely **reads reference material** — no device side effect, idempotent — should not be able to end a run by
     * being read often, so it declares `false`. The default is `true`, i.e. today's behaviour, so every existing action
     * keeps spending its step without changing a line.
     *
     * The declaration lives here, not in the runtime: `agent-runtime` may not single a tool out by name (§3.8.1 —
     * plugins declare capability, the framework implements the channel). The loop still **records** every call
     * (including exempt ones) in `AgentRun.steps`; only its local step counter ignores exempt calls.
     */
    val countsTowardStepLimit: Boolean = true,
    /**
     * 0.25 spec §4.8/§6.1: whether one call of this action only **reads** — no device side effect, no state change.
     *
     * The concurrent tool executor may run read-only calls beside each other (at most three) while every write stays
     * serial. The declaration lives here because plugins declare capability and the framework schedules; the default
     * `false` keeps every existing action on the serial path unchanged.
     */
    val readOnly: Boolean = false,
) {
    init {
        require(name.isNotBlank())
        require(title.isNotBlank())
        require(scope.isNotBlank())
        require(auditTargetArgument == null || auditTargetArgument.isNotBlank()) {
            "auditTargetArgument must be null or non-blank"
        }
        require(parametersJson.isNotBlank())
        require(parametersJson.length <= MAX_PARAMETERS_JSON_LENGTH) {
            "parametersJson must be at most $MAX_PARAMETERS_JSON_LENGTH characters"
        }
        val schema = parametersJson.trim()
        require(schema.startsWith("{") && schema.endsWith("}")) {
            "parametersJson must be shaped like a JSON object"
        }
    }
}

data class ActionResult(val outcome: ActionOutcome, val detail: String)

interface ActionExecutor {
    fun descriptors(): List<ActionDescriptor>
    fun execute(name: String, argsJson: String): ActionResult
    fun cancel()
}
