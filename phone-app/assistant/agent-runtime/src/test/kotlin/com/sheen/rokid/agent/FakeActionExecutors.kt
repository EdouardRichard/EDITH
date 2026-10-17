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
    var failure: RuntimeException? = null
    var cancellations = 0

    override fun descriptors(): List<ActionDescriptor> = actions

    override fun execute(name: String, argsJson: String): ActionResult {
        calls += name to argsJson
        failure?.let { throw it }
        return outcome(name, argsJson)
    }

    override fun cancel() {
        cancellations++
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
)

/** An executor offering exactly one tool, described by [actionDescriptor] defaults. */
internal fun executorOf(
    name: String,
    risk: RiskLevel = RiskLevel.L0,
    parametersJson: String = """{"type":"object"}""",
) = RecordingActionExecutor(listOf(actionDescriptor(name, risk = risk, parametersJson = parametersJson)))
