package com.sheen.rokid.agent

/**
 * Test-only [ActionPolicy]: answers from a fixed per-tool map and records which tools were asked about, so a test can
 * prove an unknown tool name never reaches the policy.
 */
internal class ScriptedPolicy(
    private val decisions: Map<String, PolicyDecision> = emptyMap(),
    private val fallback: PolicyDecision = PolicyDecision.Allow,
) : ActionPolicy {
    val seen = mutableListOf<String>()

    override fun decide(descriptor: ToolDescriptor): PolicyDecision {
        seen += descriptor.name
        return decisions[descriptor.name] ?: fallback
    }
}

/**
 * One recorded audit line, comparable field by field. Named `Recorded*` because production `AuditEntry` (Task 8) owns
 * the `com.sheen.rokid.agent.AuditEntry` name; two declarations sharing a fully-qualified name would make the sink's entries
 * resolve to the wrong class at test runtime.
 */
internal data class RecordedAuditEntry(val turnId: String, val action: String, val summary: String, val outcome: String)

/** The structured half of a line (P3.3): what P3.1's four fields did not carry. */
internal data class RecordedAuditFields(
    val scope: String?,
    val risk: String?,
    val decision: String?,
    val confirmationSource: ConfirmationSource?,
)

/** Test-only [AuditSink]: keeps every line in call order, four-field view plus the P3.3 structured fields. */
internal class RecordingAuditSink : AuditSink {
    val entries = mutableListOf<RecordedAuditEntry>()
    val structured = mutableListOf<RecordedAuditFields>()

    override fun record(
        turnId: String,
        action: String,
        summary: String,
        outcome: String,
        scope: String?,
        risk: String?,
        decision: String?,
        confirmationSource: ConfirmationSource?,
    ) {
        entries += RecordedAuditEntry(turnId, action, summary, outcome)
        structured += RecordedAuditFields(scope, risk, decision, confirmationSource)
    }
}

/** A [ConfirmationPort] that answers once and records what it was asked. */
internal class ScriptedConfirmation(private val outcome: ConfirmationOutcome) : ConfirmationPort {
    val asked = mutableListOf<Pair<String, String>>()

    override fun request(requestId: String, summary: String): ConfirmationOutcome {
        asked += requestId to summary
        return outcome
    }
}

/** An executor offering exactly one terminal-scoped tool. */
internal fun terminalExecutor(name: String) =
    RecordingActionExecutor(listOf(actionDescriptor(name, scope = SCOPE_TERMINAL)))
