package edith.agent

import edith.plugin.MessageRole
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.MonotonicClock
import edith.plugin.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The six terminal states of one agent run (spec §6.3). */
enum class AgentOutcome { COMPLETED, INSUFFICIENT, NO_PROGRESS, DEADLINE, CANCELLED, FAILED }

/**
 * One agent run request.
 *
 * [instructions] is composed by the plugin (any context is folded in there, per the P3.1 interface ruling);
 * [utterance] is the user's own text for this turn and opens the model history. [profileId] rides along for the wiring
 * that owns a model profile — the loop never reads one itself. [deadlineNanos] is a wall-clock boundary on the same
 * [MonotonicClock] the loop is given, not a capability budget.
 */
data class AgentRequest(
    val turnId: String,
    val utterance: String,
    val instructions: String,
    val profileId: String,
    val deadlineNanos: Long,
)

/** One executed (or refused) tool call; [result] is `ok`, or the stable safe code fed back to the model. */
data class AgentStep(val index: Int, val action: String, val result: String)

/**
 * The terminal result of one run.
 *
 * [safeCode] carries the provider's reason (or the loop's own stable code for `INSUFFICIENT` / `NO_PROGRESS` /
 * `STEP_LIMIT`) and is `null` when nothing meaningful applies. [finalText] is set only for [AgentOutcome.COMPLETED].
 */
data class AgentRun(
    val outcome: AgentOutcome,
    val safeCode: String?,
    val finalText: String?,
    val steps: List<AgentStep>,
)

private const val REFUSED_BY_POLICY = "REFUSED_BY_POLICY"
private const val CONFIRMATION_UNAVAILABLE = "CONFIRMATION_UNAVAILABLE"
private const val CONFIRMATION_DENIED = "CONFIRMATION_DENIED"
private const val CONFIRMATION_TIMEOUT = "CONFIRMATION_TIMEOUT"
private const val EMPTY_TURN = "EMPTY_TURN"
private const val INCOMPLETE_TURNS = "INCOMPLETE_TURNS"
private const val NO_PROGRESS = "NO_PROGRESS"
private const val STEP_LIMIT = "STEP_LIMIT"
private const val EMPTY_TURN_TEXT = "(empty turn)"
private const val TOOL_CALL_TEXT = "(tool call)"
private const val CLOSED_TURN_TEXT = "(no reply)"
private const val RETRY_INSTRUCTION =
    "The previous turn produced no tool call and no text. Call a tool, or state the final answer."
private const val AUDIT_POLICY = "policy"
private const val AUDIT_EXECUTED = "executed"
private const val AUDIT_UNKNOWN_TOOL = "UnknownTool"
private const val AUDIT_REFUSED = "Refused"
private const val AUDIT_CONFIRMATION_UNAVAILABLE = "ConfirmationUnavailable"
/** P3.3 (spec §17.5): the policy decision values a structured audit line carries. */
private const val AUDIT_ALLOW = "allow"
private const val AUDIT_REFUSE = "refuse"
private const val AUDIT_REQUIRE_CONFIRMATION = "require_confirmation"

/** The audit summary is read from the model's own arguments string, which is kotlinx-lenient by construction. */
private val auditArguments = Json { isLenient = true }

/**
 * The agent loop: one [run] drives model turns and tool steps until a terminal state.
 *
 * Order of precedence inside the loop, because the differences were each measured:
 * 1. a turn's [ModelEnd] terminal is read **before** its payload — an `INCOMPLETE` turn is retried only when it carries
 *    no tool call; an `INCOMPLETE` turn that *does* carry calls executes them as real steps and is not counted as an
 *    unproductive turn;
 * 2. a tool call is a *step*, never a verdict — an unknown tool, invalid arguments, a policy refusal, a missing
 *    confirmation channel and an executor failure all come back to the model as `ok = false` results; only a
 *    **successful** terminal-scope tool ends the run with `COMPLETED`;
 * 3. the guard observes only payloads that exist *after* an action ran.
 *
 * The transcript is assembled so it never ends on the pending user turn (`ContextBudgeter` rejects a trailing lone
 * text item): every tool round is opened by an assistant text before its calls, a retry appends an assistant text
 * before its user instruction, and [run] closes a still-pending user turn before it returns.
 *
 * **One loop instance serves one run.** The loop carries per-run state ([cancel] is one-way and the [guard] is reset on
 * entry), so callers construct a loop per turn; a second [run] on the same instance throws [IllegalStateException]
 * rather than silently starting from a cancelled or pre-armed state.
 *
 * [maxSteps] is a hang guard, **not** a budget: spec D-P3-10 forbids token/step budgets, so this only stops a run that
 * would otherwise call tools forever, and a run that uses exactly [maxSteps] calls is not failed.
 */
class AgentLoop(
    private val gateway: ModelGateway,
    private val registry: ToolRegistry,
    private val policy: ActionPolicy,
    private val guard: ProgressGuard,
    private val audit: AuditSink,
    private val clock: MonotonicClock,
    private val maxEmptyTurns: Int = 3,
    private val maxSteps: Int = 24,
    /**
     * P3.3 (spec §17.4): the channel a `RequireConfirmation` decision is put through. `null` keeps P3.1's behaviour
     * exactly - the action is refused with `CONFIRMATION_UNAVAILABLE` and never runs.
     */
    private val confirmation: ConfirmationPort? = null,
) {
    @Volatile
    private var started = false

    @Volatile
    private var cancelled = false

    /**
     * Read-only view of the cancel flag, exposed for a caller (and its tests) that must observe a cancel while a
     * gateway turn is blocked. Not part of the public contract.
     */
    internal val cancelPending: Boolean get() = cancelled

    /**
     * The transcript as it stood when [run] last returned, with any still-pending user turn closed. Exposed `internal`
     * so a test can prove the run never returns a transcript that ends on the pending user turn (`ContextBudgeter`
     * rejects a trailing lone text item); not part of the public contract.
     */
    internal var lastTranscript: List<ModelHistoryItem> = emptyList()
        private set

    /**
     * Requests that the run stop at its next check. Safe to call from another thread, including while a gateway turn
     * is in flight: the loop reads the flag again as soon as that turn returns.
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * Drives one run to its terminal state. A loop instance is single-use: [cancel] is one-way and the [guard] is reset
     * on entry, so calling [run] twice would silently start from the first run's leftovers.
     *
     * @throws IllegalStateException when this instance has already run once.
     */
    fun run(request: AgentRequest): AgentRun {
        check(!started) {
            "AgentLoop is single-use: one loop instance serves one run, so construct a loop per turn"
        }
        started = true
        // A guard may be shared with (or reused after) another run: this run's first observation must be a first
        // observation, not the continuation of an inherited streak.
        guard.reset()
        val tools = registry.descriptors().map { ToolSpec(it.name, it.description, it.parametersJson) }
        // The user's turn opens the history: the loop has no other channel to hand the model what was asked.
        val history = mutableListOf<ModelHistoryItem>(ModelHistoryItem.Text(MessageRole.USER, request.utterance))
        val steps = mutableListOf<AgentStep>()
        var unproductiveTurns = 0

        while (true) {
            if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "")
            if (clock.nowNanos() >= request.deadlineNanos) return finish(AgentOutcome.DEADLINE, null, null, steps, history, "")

            val reply = gateway.turn(
                GatewayTurn(
                    request.turnId,
                    request.profileId,
                    request.instructions,
                    history.toList(),
                    tools,
                    request.deadlineNanos,
                ),
            )
            // A turn that was blocked when `cancel()` arrived must not complete the run with a late answer.
            if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "")

            // Only an INCOMPLETE turn with no call is an unproductive turn: a cut-off turn that still carries calls
            // produced real steps, so they run below instead of being retried away.
            when (reply.terminal) {
                ModelEnd.INCOMPLETE -> if (reply.toolCalls.isEmpty()) {
                    unproductiveTurns++
                    if (unproductiveTurns >= maxEmptyTurns) {
                        return finish(
                            AgentOutcome.INSUFFICIENT,
                            reply.safeCode ?: INCOMPLETE_TURNS,
                            null,
                            steps,
                            history,
                            reply.text,
                        )
                    }
                    appendRetry(history, reply.text)
                    continue
                }
                ModelEnd.FAILED -> return finish(AgentOutcome.FAILED, reply.safeCode, null, steps, history, reply.text)
                ModelEnd.DEADLINE -> return finish(AgentOutcome.DEADLINE, reply.safeCode, null, steps, history, reply.text)
                ModelEnd.CANCELLED -> return finish(AgentOutcome.CANCELLED, reply.safeCode, null, steps, history, reply.text)
                ModelEnd.COMPLETED, null -> Unit
            }

            if (reply.toolCalls.isEmpty()) {
                if (reply.text.isBlank()) {
                    unproductiveTurns++
                    if (unproductiveTurns >= maxEmptyTurns) {
                        return finish(AgentOutcome.INSUFFICIENT, EMPTY_TURN, null, steps, history, reply.text)
                    }
                    appendRetry(history, reply.text)
                    continue
                }
                return finish(AgentOutcome.COMPLETED, null, reply.text, steps, history, reply.text)
            }

            unproductiveTurns = 0
            // The assistant's text opens the round, then its calls are echoed before any output, so a call never
            // arrives orphaned and a tool run never starts a history group on its own.
            history += ModelHistoryItem.Text(MessageRole.ASSISTANT, reply.text.ifBlank { TOOL_CALL_TEXT })
            reply.toolCalls.forEach { call ->
                history += ModelHistoryItem.ToolCall(
                    callId = call.id,
                    name = call.name,
                    argsJson = call.argsJson,
                    itemId = call.itemId,
                )
            }
            for (call in reply.toolCalls) {
                if (steps.size >= maxSteps) return finish(AgentOutcome.FAILED, STEP_LIMIT, null, steps, history, "")
                val registered = registry.resolve(call.name)
                val result = if (registered == null) {
                    // An unknown name is a step to feed back and never reaches the policy. It is *not* an execution, so
                    // it carries its own stable audit label instead of sharing `executed` with a real tool run.
                    val unknown = registry.execute(call)
                    audit.record(request.turnId, call.name, AUDIT_UNKNOWN_TOOL, outcomeOf(unknown))
                    unknown
                } else {
                    val shape = registered.descriptor
                    when (val decision = policy.decide(shape)) {
                        PolicyDecision.Allow -> {
                            val executed = registry.execute(call)
                            // P3.1's summary stays verbatim on a plain allow: only the structured fields are new, and
                            // rewriting a P3.1-era audit line's four original fields would be a silent record change.
                            audit.record(
                                request.turnId, call.name, AUDIT_EXECUTED, outcomeOf(executed),
                                scope = shape.scope, risk = shape.risk.name, decision = AUDIT_ALLOW,
                            )
                            executed
                        }
                        PolicyDecision.Refuse -> {
                            audit.record(
                                request.turnId, call.name, AUDIT_POLICY, AUDIT_REFUSED,
                                scope = shape.scope, risk = shape.risk.name, decision = AUDIT_REFUSE,
                            )
                            ToolResult.error(call.id, REFUSED_BY_POLICY)
                        }
                        is PolicyDecision.RequireConfirmation -> {
                            val outcome = confirmation?.request(call.id, decision.summary)
                            when {
                                // No channel at all: P3.1's behaviour, and an unconfirmed action never runs.
                                outcome == null -> {
                                    audit.record(
                                        request.turnId, call.name, AUDIT_POLICY, AUDIT_CONFIRMATION_UNAVAILABLE,
                                        scope = shape.scope, risk = shape.risk.name,
                                        decision = AUDIT_REQUIRE_CONFIRMATION,
                                    )
                                    ToolResult.error(call.id, CONFIRMATION_UNAVAILABLE)
                                }
                                outcome.approved -> {
                                    val executed = registry.execute(call)
                                    audit.record(
                                        request.turnId, call.name, auditSummary(shape, call.argsJson),
                                        outcomeOf(executed), scope = shape.scope, risk = shape.risk.name,
                                        decision = AUDIT_REQUIRE_CONFIRMATION, confirmationSource = outcome.source,
                                    )
                                    executed
                                }
                                else -> {
                                    val code = if (outcome.source == ConfirmationSource.TIMEOUT) CONFIRMATION_TIMEOUT
                                    else CONFIRMATION_DENIED
                                    audit.record(
                                        request.turnId, call.name, code, code, scope = shape.scope,
                                        risk = shape.risk.name, decision = AUDIT_REQUIRE_CONFIRMATION,
                                        confirmationSource = outcome.source,
                                    )
                                    ToolResult.error(call.id, code)
                                }
                            }
                        }
                    }
                }
                steps += AgentStep(steps.size + 1, call.name, if (result.ok) "ok" else result.payload)
                history += ModelHistoryItem.ToolOutput(call.id, result.payload)
                // The guard sees only a fingerprint that exists after this action ran — never a pre-action observation.
                guard.observeAfterAction(result.payload)
                if (guard.tripped) return finish(AgentOutcome.NO_PROGRESS, NO_PROGRESS, null, steps, history, "")
                if (registered?.descriptor?.scope == SCOPE_TERMINAL && result.ok) {
                    return finish(AgentOutcome.COMPLETED, null, result.payload, steps, history, "")
                }
                if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "")
                if (clock.nowNanos() >= request.deadlineNanos) return finish(AgentOutcome.DEADLINE, null, null, steps, history, "")
            }
        }
    }

    /**
     * Returns a run and records the transcript it leaves behind, closing a still-pending user turn first: a transcript
     * that ended on `Text(USER)` is the one shape `ContextBudgeter` refuses, so the loop never returns one. The steps
     * are handed over as a copy, so the run never hands out the loop's own mutable list.
     */
    private fun finish(
        outcome: AgentOutcome,
        safeCode: String?,
        finalText: String?,
        steps: List<AgentStep>,
        history: MutableList<ModelHistoryItem>,
        closingText: String,
    ): AgentRun {
        val last = history.lastOrNull()
        if (last is ModelHistoryItem.Text && last.role == MessageRole.USER) {
            history += ModelHistoryItem.Text(MessageRole.ASSISTANT, closingText.ifBlank { CLOSED_TURN_TEXT })
        }
        lastTranscript = history.toList()
        return AgentRun(outcome, safeCode, finalText, steps.toList())
    }

    /**
     * Feeds a retry back as `[assistant text, user instruction]`. The assistant half is mandatory: the endpoint is
     * stateless and the transcript alternates, so a lone user instruction would be an unpaired turn. An empty turn
     * gets a placeholder because `ModelHistoryItem.Text` refuses blank text.
     */
    private fun appendRetry(history: MutableList<ModelHistoryItem>, text: String) {
        history += ModelHistoryItem.Text(MessageRole.ASSISTANT, text.ifBlank { EMPTY_TURN_TEXT })
        history += ModelHistoryItem.Text(MessageRole.USER, RETRY_INSTRUCTION)
    }

    private fun outcomeOf(result: ToolResult) = if (result.ok) "ok" else result.payload

    /**
     * The one argument an audit line may carry (spec §17.5).
     *
     * The executor declares which member is a benign identifier; this reads **only** that member, and only as a string
     * or a number. An action that declares nothing (`null` — what `dial` declares) contributes an empty summary, so a
     * phone number can never reach the trail. A missing member and an unparsable payload are empty summaries too: an
     * audit line must never become the place where a malformed argument first becomes visible.
     */
    private fun auditSummary(descriptor: ToolDescriptor, argsJson: String): String {
        val name = descriptor.auditTargetArgument ?: return ""
        val member = runCatching {
            (auditArguments.parseToJsonElement(argsJson) as? JsonObject)?.get(name) as? JsonPrimitive
        }.getOrNull() ?: return ""
        return if (member.isString) "$name=${member.content}" else member.longOrNull?.let { "$name=$it" } ?: ""
    }
}
