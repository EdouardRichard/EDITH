package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.ToolSpec
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
 * [MonotonicClock] the loop is given, not a capability budget; it bounds **machine** time only, because time spent
 * waiting on a confirmation is shifted out of it (D49). [requestTimeoutNanos] bounds **one model request** instead, so
 * a caller may keep them separate.
 */
data class AgentRequest(
    val turnId: String,
    val utterance: String,
    val instructions: String,
    val profileId: String,
    val deadlineNanos: Long,
    /**
     * How long one model request may take, or `0` for "the turn deadline is also the request cap" — the behaviour before
     * D49, kept as the default so an existing caller is unaffected. The wiring that owns a profile passes that profile's
     * `timeoutMillis`: a request that hangs is the transport's problem to bound, while a **person** deciding is not the
     * transport's timeout to spend.
     */
    val requestTimeoutNanos: Long = 0L,
    /**
     * The transcript this turn continues, oldest first (spec §4.2: the plugin owns a session's conversation, not a
     * turn's). Empty — the default — reproduces the previous behaviour exactly: the utterance opens the history on
     * its own, which is what every caller before the session work relied on.
     *
     * The loop does **not** trim it. Bounding the transcript is the host `ContextBudgeter`'s job against the live
     * profile, and a loop that silently dropped the caller's history would make a session's length depend on how
     * many turns it happened to take.
     */
    val history: List<ModelHistoryItem> = emptyList(),
    /**
     * Phase 5: the caller's already-assembled [RequestFrame] for this run's **opening** model turn, or `null` — the
     * default — for the pre-Phase-5 history path. `null` reproduces the previous behaviour exactly, so every existing
     * caller is unaffected.
     *
     * Prefer [frameFor] when the caller owns a session: a frame describes one moment, and a run issues **more than one**
     * model request whenever a tool round or a retry follows the opening turn.
     */
    val frame: RequestFrame? = null,
    /**
     * Phase 5: builds the [RequestFrame] for **every** model request of this run from the session transcript that
     * request continues, or `null` — the default — for the history path.
     *
     * A tool round makes the loop ask the model a second time (`notify_user` alone guarantees it), and the frame that
     * opened the run no longer describes that request: it cannot carry a tool round, because a [RequestFrame]'s one
     * `USER_INPUT` must be last. Without a builder the later requests fall back to [history], which for a
     * store-backed session is empty — the model then loses every earlier turn **and** the retrieved memory, because
     * the memory exists only inside a frame. This seam is what gives every model request one renderer.
     *
     * `transcript` is the run's accumulated history **without the pending user turn**: the session's earlier turns
     * plus this turn's assistant text, tool calls and tool outputs, oldest first. The current utterance arrives
     * separately and the builder renders it as the frame's one `USER_INPUT` (last), so a builder that also appended it
     * would ask the model the same thing twice. While the utterance is still the last item it is *pending* and is
     * excluded — a history ending on it is the lone user group the budgeter refuses — and once a tool round or a retry
     * has appended after it, it is an interior turn of this run and stays where it belongs. When a builder is supplied
     * it owns the shape of every request (including the opening one) and [frame] is ignored; the builder is expected to
     * compose its system slot, the memory it captured for the turn, the given transcript, and `USER_INPUT` last.
     */
    val frameFor: ((transcript: List<ModelHistoryItem>) -> RequestFrame)? = null,
    /**
     * 0.24 S4 (spec §5.5): the streaming display observer for every model request of this run.
     *
     * Forwarded verbatim to each [GatewayTurn] — a run issues more than one request (a tool round, a retry) and the
     * answer may be assembled from the last one. The loop itself never reads the chunks: the final assembly is still
     * [AgentRun.finalText], and streaming is display-only (a lost callback can never change the answer).
     */
    val onTextDelta: ((String) -> Unit)? = null,
    /**
     * 0.24 S4 (spec §5.6): the display row for every tool this run handles.
     *
     * Called with [ToolPhase.START] before the action is decided/executed and [ToolPhase.END] after its result exists
     * (including a refusal, an unavailable confirmation channel, a denial and a timeout). The pair is emitted even when
     * the executor throws, so the wearer never keeps a "running" row. Display-only: the callback's failure is
     * swallowed, exactly like [onTextDelta], because losing a row must never change the run's outcome.
     */
    val onToolStatus: ((action: String, phase: ToolPhase) -> Unit)? = null,
)

/** One executed (or refused) tool call; [result] is `ok`, or the stable safe code fed back to the model. */
data class AgentStep(val index: Int, val action: String, val result: String)

/**
 * The terminal result of one run.
 *
 * [safeCode] carries the provider's reason (or the loop's own stable code for `INSUFFICIENT` / `NO_PROGRESS` /
 * `STEP_LIMIT`) and is `null` when nothing meaningful applies. [finalText] is set only for [AgentOutcome.COMPLETED].
 *
 * [humanWaitNanos] is the wall clock this run spent waiting for a **person** (the confirmation port), and it is
 * additive: `0` is the behaviour every caller and test had before it existed. The loop shifts its own budget by this
 * amount (D49) but knows nothing about what the caller derives from the deadline — the plugin's hint window is
 * `deadline + grace`, so without this number a result assembled after a late answer is reported as already expired and
 * the glasses stay blank (review I-1). Reporting the measurement is what lets the caller move the window it owns.
 */
data class AgentRun(
    val outcome: AgentOutcome,
    val safeCode: String?,
    val finalText: String?,
    val steps: List<AgentStep>,
    val humanWaitNanos: Long = 0L,
    /**
     * The transcript this run leaves behind, with any still-pending user turn closed.
     *
     * Handed over so the caller that supplied [AgentRequest.history] can store what the turn actually said instead
     * of reconstructing it — the retry nudges and the tool-round echoes are the loop's, and a caller rebuilding them
     * from [`finalText`] would get a different conversation than the one the model saw.
     */
    val transcript: List<ModelHistoryItem> = emptyList(),
    /**
     * Phase 6 (design D-9): how many tool calls this run's `maxSteps` hang guard counted — every call **except** the
     * ones whose descriptor declared `countsTowardStepLimit = false`.
     *
     * [steps] stays the full record of tool calls, exempt ones included: two production consumers (`P3_RUN … steps=`
     * and `p3-eval-trigger`'s `toolCalls` / `toolSuccessRate`) read `steps.size` as "how many tools the model called",
     * so gating and recording are separate concerns. This is the gate's own number, and it is `0` — the behaviour
     * before it existed — for a run with no counted calls.
     *
     * Test-observable only: it is deliberately **not** appended to the `P3_TURN` log line or the eval trigger's
     * `toolCalls`, whose exact shapes are pinned and whose meaning is `steps.size`, not this number.
     */
    val spentSteps: Int = 0,
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
     * The human wait this run has measured so far, per-run state like the guard: it is reset on entry and read by every
     * terminal path through [finish], so a run that ends **after** a confirmation still reports the time the person
     * spent (and no call site can forget to pass it along).
     */
    private var humanWaitNanos = 0L

    /**
     * The wall clock this run has measured waiting for a person **so far** — the same number the returned [AgentRun]
     * carries, exposed for the caller that never gets an [AgentRun] at all.
     *
     * A transport failure makes [run] throw by design (`ModelClientGateway` lets it escape), and a caller whose own
     * windows are derived from the deadline still has to move them or its honest failure sentence is dropped as
     * expired: the phone would have acted and the glasses would stay blank (review I-1). Read-only; the loop owns the
     * measurement.
     */
    val measuredHumanWaitNanos: Long get() = humanWaitNanos

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
        humanWaitNanos = 0L
        val tools = registry.descriptors().map { ToolSpec(it.name, it.description, it.parametersJson) }
        // Phase 3: TurnRunner owns request assembly. For now, the system slot carries only INSTRUCTIONS;
        // memory evidence, perception, and conversation store will be wired in later phases.
        //
        // Phase 5 note: this runner is **not** the live renderer on a caller-owned store path. It is constructed and
        // `assemble` is called every iteration (below), but when the caller supplies a `frame`/`frameFor` the only
        // thing that survives from the result is `assembled.systemText`, used **only as the instruction fallback** in
        // the `GatewayTurn` below (`request.frame?.systemText ?: assembled.systemText`). Do not read it as proof that
        // this loop still assembles the session: the frame builder does that, and everything else here is dead on that
        // path. This is left in place deliberately (no restructuring in Phase 5).
        val turnRunner = TurnRunner(
            listOf(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = request.instructions)),
        )
        // The user's turn opens the history: the loop has no other channel to hand the model what was asked. When the
        // caller supplies a session transcript, this turn's utterance is appended to it rather than replacing it.
        val history = (request.history + ModelHistoryItem.Text(MessageRole.USER, request.utterance)).toMutableList()
        // Where this turn's utterance sits in that transcript. It is the pending user turn until a tool round or a
        // retry appends after it, and a frame renders the pending user turn as its one `USER_INPUT` rather than as a
        // history entry (a history that ended on it would be the lone user group `ContextBudgeter` refuses) — so the
        // transcript handed to a builder excludes it **only while nothing follows it**.
        val utteranceIndex = request.history.size
        // Phase 5: a caller with a frame **builder** renders every request through it (see `AgentRequest.frameFor`), and
        // this flag is then irrelevant; without one, a plain `frame` is the opening turn's shape and later turns keep
        // the history path that owns the tool rounds.
        var openingTurn = true
        val steps = mutableListOf<AgentStep>()
        // D-9: the gate's input. It is deliberately **not** `steps.size`: `steps` records every call (exempt ones
        // included) because two production consumers read it as the tool-call count, while the hang guard must count
        // only calls whose descriptor declared `countsTowardStepLimit` (an unknown name declared nothing, so it counts).
        var spentSteps = 0
        var unproductiveTurns = 0
        // The turn's budget counts **machine** time only. A confirmation spends the *user's* time, so the deadline is
        // shifted forward by however long that call waits (D49): with a 120 s decision window (D47) charging the wait to
        // the turn would make every considered answer end the turn as DEADLINE.
        var turnDeadlineNanos = request.deadlineNanos

        while (true) {
            if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "", spentSteps)
            if (clock.nowNanos() >= turnDeadlineNanos) {
                return finish(AgentOutcome.DEADLINE, null, null, steps, history, "", spentSteps)
            }

            // Assemble the RequestFrame through TurnRunner; the gateway still receives a plain instructions string.
            // The system slot is one string and stays identical across the run: a caller-supplied frame's whole system
            // slot when there is one, the assembler's text otherwise.
            val assembled = turnRunner.assemble(userInput = request.utterance, tools = tools)
            // One renderer for every request: with a builder the frame is rebuilt from the transcript this request
            // continues (earlier turns plus this turn's tool rounds), so a continuation keeps the session and the
            // memory the opening frame carried. The builder renders the current utterance itself as `USER_INPUT`, so
            // the transcript leaves out the pending user turn while it is still pending and keeps it — where it
            // belongs, at the head of this turn's tool round — once something follows it.
            val frameForTurn = when {
                request.frameFor != null -> request.frameFor(frameTranscript(history, utteranceIndex))
                openingTurn -> request.frame
                else -> null
            }
            openingTurn = false
            val reply = gateway.turn(
                GatewayTurn(
                    request.turnId,
                    request.profileId,
                    request.frame?.systemText ?: assembled.systemText,
                    history.toList(),
                    tools,
                    // D49: the turn's budget and one request's cap are different numbers. A caller that supplies no cap
                    // keeps the old shape exactly.
                    if (request.requestTimeoutNanos > 0L) {
                        minOf(turnDeadlineNanos, clock.nowNanos() + request.requestTimeoutNanos)
                    } else {
                        turnDeadlineNanos
                    },
                    frame = frameForTurn,
                    // 0.24 S4: one observer for every request of the run; it owns no request state itself.
                    onTextDelta = request.onTextDelta,
                ),
            )
            // A turn that was blocked when `cancel()` arrived must not complete the run with a late answer.
            if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "", spentSteps)

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
                            spentSteps,
                        )
                    }
                    appendRetry(history, reply.text)
                    continue
                }
                ModelEnd.FAILED -> return finish(
                    AgentOutcome.FAILED, reply.safeCode, null, steps, history, reply.text, spentSteps,
                )
                ModelEnd.DEADLINE -> return finish(
                    AgentOutcome.DEADLINE, reply.safeCode, null, steps, history, reply.text, spentSteps,
                )
                ModelEnd.CANCELLED -> return finish(
                    AgentOutcome.CANCELLED, reply.safeCode, null, steps, history, reply.text, spentSteps,
                )
                ModelEnd.COMPLETED, null -> Unit
            }

            if (reply.toolCalls.isEmpty()) {
                if (reply.text.isBlank()) {
                    unproductiveTurns++
                    if (unproductiveTurns >= maxEmptyTurns) {
                        return finish(AgentOutcome.INSUFFICIENT, EMPTY_TURN, null, steps, history, reply.text, spentSteps)
                    }
                    appendRetry(history, reply.text)
                    continue
                }
                return finish(AgentOutcome.COMPLETED, null, reply.text, steps, history, reply.text, spentSteps)
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
                if (spentSteps >= maxSteps) return finish(AgentOutcome.FAILED, STEP_LIMIT, null, steps, history, "", spentSteps)
                val registered = registry.resolve(call.name)
                // D-9: one declaration decides both halves — an exempt call is recorded but spends no step, while an
                // unknown name declared nothing and therefore still spends one.
                if (registered == null || registered.descriptor.countsTowardStepLimit) spentSteps++
                // 0.24 S4: the row opens before any execution attempt and closes only once the result exists. The pair
                // covers refusals, an unavailable/denied confirmation and a throwing executor; the callback itself is
                // display-only (see `AgentRequest.onToolStatus`).
                notifyToolStatus(request, call.name, ToolPhase.START)
                val result = try {
                    if (registered == null) {
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
                            // Waiting on a person is not the turn spending its own budget (D49): measure the call and
                            // shift the deadline by the same amount. `finally` so a channel that throws is not punished
                            // for the time it already spent waiting. The same measurement is **published** as it is taken
                            // ([measuredHumanWaitNanos]), so a caller whose own windows derive from the deadline can move
                            // them even when this run ends by throwing instead of returning (review I-1).
                            val waitStartedNanos = clock.nowNanos()
                            val outcome = try {
                                confirmation?.request(call.id, decision.summary)
                            } finally {
                                val waitedNanos = clock.nowNanos() - waitStartedNanos
                                if (waitedNanos > 0L) {
                                    humanWaitNanos += waitedNanos
                                    turnDeadlineNanos += waitedNanos
                                }
                            }
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
                } finally {
                    notifyToolStatus(request, call.name, ToolPhase.END)
                }
                steps += AgentStep(steps.size + 1, call.name, if (result.ok) "ok" else result.payload)
                history += ModelHistoryItem.ToolOutput(call.id, result.payload)
                // The guard sees only a fingerprint that exists after this action ran — never a pre-action observation.
                guard.observeAfterAction(result.payload)
                if (guard.tripped) return finish(AgentOutcome.NO_PROGRESS, NO_PROGRESS, null, steps, history, "", spentSteps)
                if (registered?.descriptor?.scope == SCOPE_TERMINAL && result.ok) {
                    return finish(AgentOutcome.COMPLETED, null, result.payload, steps, history, "", spentSteps)
                }
                if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "", spentSteps)
                if (clock.nowNanos() >= turnDeadlineNanos) {
                    return finish(AgentOutcome.DEADLINE, null, null, steps, history, "", spentSteps)
                }
            }
        }
    }

    /** Display-only tool-row notification: a throwing/blocking observer must never change the run's outcome. */
    private fun notifyToolStatus(request: AgentRequest, action: String, phase: ToolPhase) {
        runCatching { request.onToolStatus?.invoke(action, phase) }
    }

    /**
     * The transcript a frame builder continues: the run's accumulated history, minus this turn's utterance while that
     * utterance is still the pending user turn — a history ending on it is the lone user group the budgeter refuses,
     * and a frame renders the pending turn as its one `USER_INPUT` instead. Once a tool round or a retry appended after
     * it, the utterance is an interior turn of this run and stays.
     */
    private fun frameTranscript(history: List<ModelHistoryItem>, utteranceIndex: Int): List<ModelHistoryItem> =
        if (history.size == utteranceIndex + 1) history.take(utteranceIndex) else history.toList()

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
        spentSteps: Int,
    ): AgentRun {
        val last = history.lastOrNull()
        if (last is ModelHistoryItem.Text && last.role == MessageRole.USER) {
            history += ModelHistoryItem.Text(MessageRole.ASSISTANT, closingText.ifBlank { CLOSED_TURN_TEXT })
        }
        lastTranscript = history.toList()
        return AgentRun(outcome, safeCode, finalText, steps.toList(), humanWaitNanos, lastTranscript, spentSteps)
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
