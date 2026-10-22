package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel

/**
 * Test-only action executor: records every `execute` call verbatim so a test can prove which tool name and which
 * arguments string reached the plugin side, and scripts the returned [ActionResult] or a thrown failure.
 */
internal class RecordingActionExecutor(
    private val actions: List<ActionDescriptor>,
) : ActionExecutor {
    val calls = mutableListOf<Pair<String, String>>()
    var outcome: (String, String) -> ActionResult = { _, _ -> ActionResult(ActionOutcome.Done, "done") }
    /** `Throwable` so a test can let an `Error` escape the registry's `Exception` guard, as `runTool` must survive. */
    var failure: Throwable? = null
    var cancellations = 0
    var begins = 0
    var beginTurnFailure: RuntimeException? = null

    override fun descriptors(): List<ActionDescriptor> = actions

    override fun execute(name: String, argsJson: String): ActionResult {
        calls += name to argsJson
        failure?.let { throw it }
        return outcome(name, argsJson)
    }

    override fun cancel() {
        cancellations++
    }

    override fun beginTurn() {
        begins++
        beginTurnFailure?.let { throw it }
    }
}

/** [ActionDescriptor] fixture; defaults describe a harmless, idempotent, reversible tool. */
internal fun actionDescriptor(
    name: String,
    risk: RiskLevel = RiskLevel.L0,
    parametersJson: String = """{"type":"object"}""",
    requiresConfirmation: Boolean = false,
    idempotent: Boolean = true,
    reversible: Boolean = true,
    scope: String = "test",
    auditTargetArgument: String? = null,
    countsTowardStepLimit: Boolean = true,
    // Task 12 (R57): additive descriptor metadata. The defaults keep every pre-0.25 fixture byte-for-byte.
    observation: Boolean = false,
    timeoutMillis: Long = 30_000L,
    // Task 12 fix (R99): whether the executor may wait for a person inside `execute`.
    mayAskHuman: Boolean = false,
    // edith-0.3 Phase 2.2 Task 3: whether a failed call should carry a best-effort post-call observation.
    screenObservation: Boolean = false,
) = ActionDescriptor(
    name = name,
    title = "Title of $name",
    parametersJson = parametersJson,
    risk = risk,
    requiresConfirmation = requiresConfirmation,
    idempotent = idempotent,
    reversible = reversible,
    scope = scope,
    auditTargetArgument = auditTargetArgument,
    countsTowardStepLimit = countsTowardStepLimit,
    observation = observation,
    timeoutMillis = timeoutMillis,
    mayAskHuman = mayAskHuman,
    screenObservation = screenObservation,
)

/** An executor offering exactly one tool, described by [actionDescriptor] defaults. */
internal fun executorOf(
    name: String,
    risk: RiskLevel = RiskLevel.L0,
    parametersJson: String = """{"type":"object"}""",
) = RecordingActionExecutor(listOf(actionDescriptor(name, risk = risk, parametersJson = parametersJson)))
