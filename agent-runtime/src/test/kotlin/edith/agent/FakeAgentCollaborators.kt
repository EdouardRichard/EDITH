package edith.agent

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
 * the `edith.agent.AuditEntry` name; two declarations sharing a fully-qualified name would make the sink's entries
 * resolve to the wrong class at test runtime.
 */
internal data class RecordedAuditEntry(val turnId: String, val action: String, val summary: String, val outcome: String)

/** Test-only [AuditSink]: keeps every line in call order. */
internal class RecordingAuditSink : AuditSink {
    val entries = mutableListOf<RecordedAuditEntry>()

    override fun record(turnId: String, action: String, summary: String, outcome: String) {
        entries += RecordedAuditEntry(turnId, action, summary, outcome)
    }
}

/** An executor offering exactly one terminal-scoped tool. */
internal fun terminalExecutor(name: String) =
    RecordingActionExecutor(listOf(actionDescriptor(name, scope = SCOPE_TERMINAL)))
