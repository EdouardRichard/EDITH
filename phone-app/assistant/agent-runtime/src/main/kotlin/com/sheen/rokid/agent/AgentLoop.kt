package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
    /**
     * 0.25 Task 12 (R58): whether a text-only **plan** answer may be nudged back into executing it.
     *
     * `false` — the default — keeps every pre-0.25 caller byte for byte: a text turn with no tool call is the answer.
     * The production plugin sets it `true` for the WAKE route (the wearer is waiting for an action) and leaves it
     * `false` for PROACTIVE, whose single request is a proposal, not a plan to execute (D3 §1.6). A nudge is loop
     * scaffolding: it is never persisted and `withoutRetryScaffolding` strips it.
     */
    val allowPlanNudge: Boolean = false,
    /**
     * 0.25 Task 12 (R59): the device/loop state to fold into each request as one untrusted `RUNTIME_STATE` segment.
     *
     * `null` — the default — injects nothing. The provider may return `null` when it has no honest state to report. The
     * loop calls it once per model request and **skips a state identical to the previous request's**, so a steady screen
     * does not pay the same few hundred bytes every round. The state never enters the transcript, a `ConversationStore`
     * or a tool result; it lives only in the assembled request and is folded into the request input by the gateway.
     */
    val runtimeState: (() -> TurnRuntimeState?)? = null,
)

/** One executed (or refused) tool call; [result] is `ok`, or the stable safe code fed back to the model. */
data class AgentStep(
    val index: Int,
    val action: String,
    val result: String,
    /**
     * 0.25 Task 12 (R55): `false` means a loop detector **skipped** this call (the warning path): it was recorded and
     * consumed its descriptor's step allowance, but it produced no observation fingerprint, no global no-progress
     * count and no error-streak entry. Every other step — an execution, a policy refusal, an unavailable/denied
     * confirmation — keeps the default `true`, exactly as before Task 12. The default keeps every pre-0.25 `AgentStep`
     * comparison unchanged.
     */
    val executed: Boolean = true,
)

/**
 * 0.25 Task 14 (R78): one run's **count-only** metrics, the source of the single `P3_METRIC` line.
 *
 * [modelMillis] is the run's summed time inside model turns, [toolMillis] the summed machine time of its tool calls,
 * and [observeMillis] the subset of that time spent in calls whose descriptor declared `observation = true`. [images]
 * is the number of distinct image artifacts the run's requests carried. Every field is a count; none can carry a
 * page, a transcript or a credential.
 */
data class AgentMetrics(
    val calls: Int = 0,
    val toolCalls: Int = 0,
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    val observeMillis: Long = 0,
    val images: Int = 0,
    val nudges: Int = 0,
    val overflows: Int = 0,
)

/**
 * The terminal result of one run.
 *
 * [safeCode] carries the provider's reason (or the loop's own stable code for `INSUFFICIENT` / `NO_PROGRESS` /
 * `STEP_LIMIT`) and is `null` when nothing meaningful applies. [finalText] is set only for [AgentOutcome.COMPLETED].
 *
 * [humanWaitNanos] is the wall clock this run spent waiting for a **person** — the confirmation port and the bounded
 * mayAskHuman wait a tool reports as its deadline shift (the shell/`ConfirmingActions` wrapper) — and it is
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
     * from [`finalText`] would get a different conversation than the one the model saw. A caller that stores the
     * conversation (rather than continuing it) additionally applies [withoutRetryScaffolding]: a retry nudge is
     * loop-internal and must not be persisted as the wearer's speech (0.25 review ruling R16).
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
    /**
     * Task 6 (spec §4.8 / §4.13): the provider's token accounting for the run, summed over **every** model turn it
     * made — a tool round and a retry each cost a turn, so the run's total is their sum, not the last turn's counts.
     * Each component is summed independently (a turn that reported only one counter leaves the other total intact).
     *
     * `null` — the default — means no turn reported usage at all, which is different from a reported zero. Counts only:
     * no text, prompt or completion body reaches this field. It is the source Task 14's `P3_METRIC` reads; the existing
     * `P3_TURN` / `P3_TOOL` log shapes deliberately do not change.
     */
    val usage: ModelUsage? = null,
    /**
     * 0.25 Task 12 (R61): how many calls whose descriptor declared `observation = true` this run made, including the
     * ones a detector skipped. It is a separate budget from [spentSteps]: observing is how the model learns, but
     * unlimited observing is how a run burns a turn without acting. At `maxObservations` (24, D3 §1.8) the run ends
     * `NO_PROGRESS / OBSERVATION_BUDGET`.
     */
    val spentObservations: Int = 0,
    /**
     * 0.25 Task 12 (R58): `1` when a plan-only answer reached the nudge cap (three nudges) and was accepted as the final
     * answer, `0` otherwise. It is the count Task 14's `P3_METRIC` reads as `planNudgeCap`; no log line changes here.
     */
    val planNudgeCap: Int = 0,
    /**
     * 0.25 Task 14 (R78): the count-only metrics this run reports, and the values its one `P3_METRIC` line carries.
     * A default instance keeps every pre-0.25 `AgentRun` comparison and construction unchanged.
     */
    val metrics: AgentMetrics = AgentMetrics(),
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

// 0.25 Task 12 (R55–R61): loop protection codes and bounds. Each one is a wire value the plugin/eval may read.
private const val ACTION_TIMEOUT = "ACTION_TIMEOUT"
private const val ACTION_TIMEOUT_UNCERTAIN = "ACTION_TIMEOUT_UNCERTAIN"
private const val REPEATED_ERROR = "REPEATED_ERROR"
private const val OBSERVATION_BUDGET = "OBSERVATION_BUDGET"
private const val ACTION_TIMEOUT_NOTE =
    "the action may or may not have taken effect; observe the screen before deciding."
private const val ACTION_TIMEOUT_UNCERTAIN_NOTE =
    "the tool did not stop within the grace window; the run is stopping and the channel must be re-observed."
/** R58: the loop instruction that turns a plan-only answer back into a tool call. Never stored. */
private const val PLAN_NUDGE_INSTRUCTION =
    "You described a plan but did not execute it. Observe first, then act: call the tool now instead of only promising it."
private const val MAX_PLAN_NUDGES = 3
private const val MAX_PLAN_TEXT_CHARS = 1_200
private const val TOOL_EXECUTOR_THREAD_NAME = "agent-tool-call"
/** R78: nanoseconds in one reported millisecond (integer division keeps the line count-only and deterministic). */
private const val NANOS_PER_MILLI = 1_000_000L
/** R60: how long a timed-out call is given to really stop before the run reports `ACTION_TIMEOUT_UNCERTAIN`. */
private const val TOOL_TIMEOUT_GRACE_NANOS = 2_000_000_000L
/**
 * R99: how long a `mayAskHuman` call may additionally wait for a person inside `execute`.
 *
 * It is D47's 120 s confirmation window, the same window the plugin's confirming wrapper uses. The allowance is added
 * to the descriptor's machine cap and is never charged to the turn (D49): the loop shifts the measured part back out
 * of `turnDeadlineNanos` after the call returns.
 */
private const val HUMAN_CONFIRMATION_ALLOWANCE_NANOS = 120_000L * 1_000_000L
/** While waiting for a tool, the loop re-checks `cancel()` this often instead of blocking on the whole timeout. */
private const val TOOL_POLL_STEP_NANOS = 10_000_000L
private const val DEFAULT_TOOL_TIMEOUT_MILLIS = 30_000L

/** Plan wording (D3 §1.6). Matched case-insensitively for the Latin markers; Chinese markers are literal. */
private val PLAN_MARKERS = listOf(
    "接下来", "下一步", "我将", "我会", "稍后", "先", "然后", "再",
    "i will", "i'll", "next", "let me", "first",
)

/** Wording that means the turn is reporting an outcome, not describing work it is about to do. */
private val PLAN_COMPLETION_MARKERS = listOf(
    "已完成", "已经完成", "完成如下", "总结如下", "无法继续", "没有权限", "无权限", "抱歉",
    "无法", "不能", "已处理", "已为你", "以下是",
    "done", "unable", "cannot", "no permission", "summary",
)

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
 * 3. the progress guard observes only a **fingerprint that exists after an action ran** — never a payload and never a
 *    pre-action observation. Task 12's four detectors decide **before** execution, so a repeated step is answered with a
 *    synthetic tool result in the same tool round instead of another model request; the first critical detector ends the
 *    run as `NO_PROGRESS` with its own `LOOP_*` safe code.
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
    /**
     * 0.25 Task 12 (R61): the observation budget (D3 §1.8's 24). Counts calls whose descriptor declared
     * `observation = true`, whether or not they also spend a step; reaching it ends the run as
     * `NO_PROGRESS / OBSERVATION_BUDGET`. It is a parameter so a test can prove the boundary without 24 fixture calls'
     * worth of unrelated setup, not a caller-tunable production knob.
     */
    private val maxObservations: Int = 24,
    /**
     * 0.25 Task 14 (R78): where the run's one `P3_METRIC` line goes. `{}` (the default) emits nothing, keeping every
     * pre-0.25 caller and test byte-for-byte; the production plugin passes its log sink. The loop still reports the
     * same numbers in [AgentRun.metrics] for a caller that has its own consumer.
     *
     * Observability must never change the outcome, so a throwing sink is swallowed like the display callbacks are.
     */
    private val metricLine: (String) -> Unit = {},
) {
    init {
        require(maxObservations >= 1) { "maxObservations must be at least 1, was $maxObservations" }
    }
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
     * The token accounting this run has accumulated so far — per-run state like [humanWaitNanos]: reset on entry and
     * summed from every model turn's [GatewayReply.usage]. It is a field rather than a `finish` argument so **every**
     * terminal path reports it, including one that ends on a failed or cancelled turn.
     */
    private var usage: ModelUsage? = null

    /** 0.25 Task 12 (R61): observation-budget spent, per-run like [usage]; reported on every finish path. */
    private var spentObservations = 0

    /** 0.25 Task 12 (R58): `1` once the plan-nudge cap was reached and the text was accepted (metrics input). */
    private var planNudgeCap = 0

    /** R59: the hash of the runtime state last injected, so an unchanged state is sent only once. */
    private var lastRuntimeStateHash: String? = null

    /** R58: the last text that was nudged, so an identical plan cannot be nudged again in place. */
    private var lastNudgedText: String? = null
    private var nudges = 0

    // 0.25 Task 14 (R78): per-run metric counters, reset on entry like `usage` so every terminal path reports this run's
    // numbers and a reused loop (not supported, but cheap to keep honest) starts clean.
    private var metricCalls = 0
    private var metricModelNanos = 0L
    private var metricToolNanos = 0L
    private var metricObserveNanos = 0L
    private var metricOverflows = 0
    private val metricImageIds = LinkedHashSet<String>()

    /** R100: loop-owned runtime-state flags — the latest state-changing call could not be verified by an observation. */
    private var lastWriteUnverified = false

    /** R100: loop-owned runtime-state flag — a text-entry call ran and no later state-changing call followed it. */
    private var typedUnsent = false

    /** R58: "the run has really used a tool" — a plan from a pure Q&A turn is never nudged. */
    private var successfulToolExecutions = 0

    /** R61: `cancel()` asks the executors to stop at most once, so repeated cancels are idempotent. */
    private val cancelSignalled = AtomicBoolean(false)

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
     * or a tool call is in flight: the flag is read again as soon as either returns, and the first cancel also asks
     * every executor to stop the work it is running (R61 — before Task 12 a blocked tool never saw the flag at all).
     * Repeated calls are idempotent: the executors are asked exactly once.
     */
    fun cancel() {
        cancelled = true
        if (cancelSignalled.compareAndSet(false, true)) {
            runCatching { registry.cancel() }
        }
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
        // R60: every `registry.execute` goes through this one executor, so a tool that blocks cannot hold the loop
        // past the call's timeout. Daemon, so a tool that ignores interruption can never keep the process alive;
        // `shutdownNow` in the `finally` means a normal run leaks no worker thread.
        val toolExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, TOOL_EXECUTOR_THREAD_NAME).apply { isDaemon = true }
        }
        return try {
            runInternal(request, toolExecutor)
        } finally {
            toolExecutor.shutdownNow()
        }
    }

    private fun runInternal(request: AgentRequest, toolExecutor: ExecutorService): AgentRun {
        // A guard may be shared with (or reused after) another run: this run's first observation must be a first
        // observation, not the continuation of an inherited streak.
        guard.reset()
        humanWaitNanos = 0L
        usage = null
        spentObservations = 0
        planNudgeCap = 0
        lastRuntimeStateHash = null
        lastNudgedText = null
        nudges = 0
        lastWriteUnverified = false
        typedUnsent = false
        successfulToolExecutions = 0
        metricCalls = 0
        metricModelNanos = 0L
        metricToolNanos = 0L
        metricObserveNanos = 0L
        metricOverflows = 0
        metricImageIds.clear()
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
        // Task 12: per-run protection state. Both are per-run by construction (a loop instance is single-use).
        val detectors = LoopDetectors()
        val errors = ErrorTracker()

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
            }?.let { withRuntimeState(it, request, spentSteps) }
            openingTurn = false
            // R78: count this request's images before it is sent. Distinct artifact ids, so a picture carried by two
            // requests of the same run is one image, not two.
            recordMetricImages(frameForTurn, history)
            metricCalls++
            val modelStartedNanos = clock.nowNanos()
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
                    // 0.25 Task 27 (R143): the gateway's trim safety net restores this question if cut ① evicts the
                    // current turn's USER transcript; the continuation cue alone would otherwise be the whole input.
                    userUtterance = request.utterance,
                ),
            )
            metricModelNanos += (clock.nowNanos() - modelStartedNanos).coerceAtLeast(0L)
            // A turn's accounting belongs to the run even when the turn's verdict is about to end it: accumulate here,
            // before any branch below can return, so every finish path reports what the provider billed.
            usage = accumulatedUsage(usage, reply.usage)
            // R78: an overflow recovery and a terminal context overflow are both overflow events for the metric.
            metricOverflows += reply.overflowRecoveries
            if (reply.terminal == ModelEnd.FAILED && reply.safeCode == CONTEXT_OVERFLOW) metricOverflows++
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
                // R58: a text-only plan while the run has already acted is not an answer. Nudge it back into action
                // (at most three times), then accept the text honestly as the final answer.
                if (shouldPlanNudge(reply.text, request, turnDeadlineNanos, spentSteps)) {
                    if (nudges < MAX_PLAN_NUDGES && reply.text != lastNudgedText) {
                        lastNudgedText = reply.text
                        nudges++
                        appendPlanNudge(history, reply.text)
                        continue
                    }
                    if (nudges >= MAX_PLAN_NUDGES) planNudgeCap = 1
                    return finish(AgentOutcome.COMPLETED, null, reply.text, steps, history, reply.text, spentSteps)
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
                // R60: the per-call cap is min(descriptor timeout, deadline - now); an already-expired deadline must not
                // start another tool at all.
                if (clock.nowNanos() >= turnDeadlineNanos) {
                    return finish(AgentOutcome.DEADLINE, null, null, steps, history, "", spentSteps)
                }
                val registered = registry.resolve(call.name)
                // D-9: one declaration decides both halves — an exempt call is recorded but spends no step, while an
                // unknown name declared nothing and therefore still spends one.
                if (registered == null || registered.descriptor.countsTowardStepLimit) spentSteps++
                val descriptor = registered?.descriptor
                // R61: every call whose descriptor declares an observation spends the observation budget, executed or
                // skipped, step-exempt or not.
                if (descriptor?.observation == true) spentObservations++
                // 0.24 S4: the row opens before any execution attempt and closes only once the result exists. The pair
                // covers refusals, an unavailable/denied confirmation and a throwing executor; the callback itself is
                // display-only (see `AgentRequest.onToolStatus`).
                notifyToolStatus(request, call.name, ToolPhase.START)
                // R78: machine time of one tool call. The person's confirmation wait and the mayAskHuman overrun are
                // excluded exactly as the turn budget excludes them (D49), so a considered approval is not billed to
                // the tool. A run-ending branch inside the block returns before the addition below; the counts it must
                // report are still complete, and the time of the one call that ended the run is the only undercount.
                val toolStartedNanos = clock.nowNanos()
                val humanWaitBefore = humanWaitNanos
                val execution = try {
                    // R55: detection happens **before** execution. A warning returns a synthetic tool result in the
                    // same tool round (no extra model request); a critical ends the run without executing the call.
                    when (val decision = detectors.beforeCall(call.name, call.argsJson, descriptor?.observation == true)) {
                        is LoopDecision.Warn -> {
                            val warning = loopWarningPayload(decision.pattern)
                            steps += AgentStep(steps.size + 1, call.name, warning, executed = false)
                            history += ModelHistoryItem.ToolOutput(call.id, warning)
                            // A skipped observation still spends the observation budget (D3 §1.8), and a cancel or an
                            // expired deadline still ends the run. Nothing else about a skipped call is recorded: no
                            // fingerprint, no guard feed, no global no-progress count, no error streak.
                            if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "", spentSteps)
                            if (spentObservations >= maxObservations) {
                                return finish(
                                    AgentOutcome.NO_PROGRESS, OBSERVATION_BUDGET, null, steps, history, "", spentSteps,
                                )
                            }
                            if (clock.nowNanos() >= turnDeadlineNanos) {
                                return finish(AgentOutcome.DEADLINE, null, null, steps, history, "", spentSteps)
                            }
                            continue
                        }
                        is LoopDecision.Critical -> {
                            val code = decision.pattern.safeCode
                            steps += AgentStep(steps.size + 1, call.name, code, executed = false)
                            history += ModelHistoryItem.ToolOutput(call.id, code)
                            return finish(AgentOutcome.NO_PROGRESS, code, null, steps, history, "", spentSteps)
                        }
                        LoopDecision.Allow -> Unit
                    }
                    if (registered == null) {
                        // An unknown name is a step to feed back and never reaches the policy. It is *not* an execution,
                        // so it carries its own stable audit label instead of sharing `executed` with a real tool run.
                        val unknown = runTool(call, DEFAULT_TOOL_TIMEOUT_MILLIS, turnDeadlineNanos, toolExecutor)
                        if (!unknown.cancelled) {
                            audit.record(request.turnId, call.name, AUDIT_UNKNOWN_TOOL, outcomeOf(unknown.result))
                        }
                        unknown
                    } else {
                        val shape = registered.descriptor
                        when (val decision = policy.decide(shape)) {
                            PolicyDecision.Allow -> {
                                val executed = runTool(
                                    call, shape.timeoutMillis, turnDeadlineNanos, toolExecutor,
                                    mayAskHuman = shape.mayAskHuman,
                                )
                                if (!executed.cancelled) {
                                    // P3.1's summary stays verbatim on a plain allow: only the structured fields are
                                    // new, and rewriting a P3.1-era audit line's four original fields would be a silent
                                    // record change.
                                    audit.record(
                                        request.turnId, call.name, AUDIT_EXECUTED, outcomeOf(executed.result),
                                        scope = shape.scope, risk = shape.risk.name, decision = AUDIT_ALLOW,
                                    )
                                }
                                executed
                            }
                            PolicyDecision.Refuse -> {
                                audit.record(
                                    request.turnId, call.name, AUDIT_POLICY, AUDIT_REFUSED,
                                    scope = shape.scope, risk = shape.risk.name, decision = AUDIT_REFUSE,
                                )
                                ToolExecution(ToolResult.error(call.id, REFUSED_BY_POLICY), executed = false)
                            }
                            is PolicyDecision.RequireConfirmation -> {
                                // Waiting on a person is not the turn spending its own budget (D49): measure the call and
                                // shift the deadline by the same amount. `finally` so a channel that throws is not
                                // punished for the time it already spent waiting. The same measurement is **published**
                                // as it is taken ([measuredHumanWaitNanos]), so a caller whose own windows derive from
                                // the deadline can move them even when this run ends by throwing instead of returning
                                // (review I-1).
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
                                        ToolExecution(ToolResult.error(call.id, CONFIRMATION_UNAVAILABLE), executed = false)
                                    }
                                    outcome.approved -> {
                                        val executed = runTool(
                                            call, shape.timeoutMillis, turnDeadlineNanos, toolExecutor,
                                            mayAskHuman = shape.mayAskHuman,
                                        )
                                        if (!executed.cancelled) {
                                            audit.record(
                                                request.turnId, call.name, auditSummary(shape, call.argsJson),
                                                outcomeOf(executed.result), scope = shape.scope, risk = shape.risk.name,
                                                decision = AUDIT_REQUIRE_CONFIRMATION, confirmationSource = outcome.source,
                                            )
                                        }
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
                                        ToolExecution(ToolResult.error(call.id, code), executed = false)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    notifyToolStatus(request, call.name, ToolPhase.END)
                }
                val machineToolNanos = (clock.nowNanos() - toolStartedNanos -
                    (humanWaitNanos - humanWaitBefore) - execution.deadlineShiftNanos).coerceAtLeast(0L)
                metricToolNanos += machineToolNanos
                if (descriptor?.observation == true) metricObserveNanos += machineToolNanos
                // R99/D49: a mayAskHuman call may have spent part of D47's decision window inside `execute`; that time
                // belongs to the person, not the turn. Shift it before any deadline check below can charge the run for
                // a legal approval. Every other call contributes nothing.
                if (execution.deadlineShiftNanos > 0L) {
                    turnDeadlineNanos += execution.deadlineShiftNanos
                    // Review I7: the same wait is the person's wall clock the run must hand back, exactly as the policy
                    // confirmation path above adds its own measured wait. It is added *after* `machineToolNanos` (which
                    // already subtracts the shift) so this call's machine time cannot be double-counted; the next call's
                    // `humanWaitBefore` reads the updated total, so the next subtraction covers only its own wait too.
                    humanWaitNanos += execution.deadlineShiftNanos
                }
                // A cancel that arrived while the tool was running is not a tool failure: no output is appended for the
                // unfinished call (R61), so the model never sees a half-finished step as if it had completed.
                if (execution.cancelled) {
                    return finish(AgentOutcome.CANCELLED, null, null, steps, history, "", spentSteps)
                }
                // R60: a call that did not stop within the grace window ends the run; no later write may run while the
                // previous one may still be in flight.
                if (execution.uncertain) {
                    steps += AgentStep(steps.size + 1, call.name, execution.result.payload, executed = true)
                    history += ModelHistoryItem.ToolOutput(
                        call.id, execution.result.payload, execution.result.images,
                    )
                    return finish(AgentOutcome.FAILED, ACTION_TIMEOUT_UNCERTAIN, null, steps, history, "", spentSteps)
                }
                // R97: the stop a timeout issued is turn-scoped on the host side (DeviceKernel.cancel refuses every call
                // until beginTurn). The call really stopped, so re-arm the executors here, once the timeout result exists,
                // and the rest of this same turn can still act. An uncertain stop ended the run above; a STOP, a cancel
                // and a teardown must stay cancelled, so those paths never reach this re-arm.
                if (execution.timedOut && !cancelled) {
                    registry.beginTurn()
                }
                val result = execution.result
                // R98/R100: the per-call state-changing declaration is the only write signal — `device` declares
                // observation=true for act too, so `!descriptor.observation` would treat `notify_user` as a write and a
                // real device act as a read. It feeds the detectors' progress reset, the error streak's reset and the
                // runtime-state flags below.
                val stateChanging = execution.executed && result.stateChanging
                // R57/R62/R98: the guard sees only a fingerprint that exists **after** a state-changing call ran. A read
                // never feeds it and the payload is never used as a fingerprint (a payload with timing or a counter
                // could never trip the guard).
                val after = detectors.afterCall(execution.executed, stateChanging, result.fingerprint)
                if (stateChanging && result.fingerprint != null) {
                    guard.observeAfterAction(result.fingerprint)
                }
                // R100: the state line's flags are loop-owned. A state-changing call that came back without an
                // observation fingerprint is a write the loop could not verify; a successful call carrying the
                // text-entry payload is typed-but-unsent until the next successful state-changing call.
                if (stateChanging) {
                    lastWriteUnverified = result.fingerprint == null
                    if (result.ok) typedUnsent = carriesTypedInput(call.argsJson)
                }
                // R56: the error streak is fed by every attempt with a stable code (a policy refusal counts, a
                // confirmation decision does not, see `ErrorTracker`). A success resets it only when it changed state.
                val errorsSeen = errors.assess(
                    call.name, result, writeSucceeded = stateChanging && result.ok,
                )
                // The step keeps the raw stable code (`ok` on success, as before Task 12); the model-facing output
                // additionally carries the one-time hints and the one-time global notice.
                val stepResult = if (result.ok) "ok" else result.payload
                val historyPayload = buildString {
                    append(result.payload)
                    errorsSeen.hint?.let { append("\n\n").append(it) }
                    if (after.notice) append("\n\n").append(GLOBAL_NO_PROGRESS_NOTICE)
                }
                steps += AgentStep(steps.size + 1, call.name, stepResult, executed = true)
                // 0.25 Task 24 (R133): the executor's images ride on the tool output exactly as the payload does. An
                // empty list keeps the pre-0.25 item shape, and the plugin's TOOL_ROUND segment is the one channel that
                // carries these references to the next model request.
                history += ModelHistoryItem.ToolOutput(call.id, historyPayload, result.images)
                if (execution.executed && result.ok) successfulToolExecutions++
                if (guard.tripped) return finish(AgentOutcome.NO_PROGRESS, NO_PROGRESS, null, steps, history, "", spentSteps)
                if (errorsSeen.repeatTerminated) {
                    return finish(AgentOutcome.FAILED, REPEATED_ERROR, null, steps, history, "", spentSteps)
                }
                if (after.critical) {
                    return finish(
                        AgentOutcome.NO_PROGRESS, LoopPattern.GLOBAL_CIRCUIT_BREAKER.safeCode,
                        null, steps, history, "", spentSteps,
                    )
                }
                if (registered?.descriptor?.scope == SCOPE_TERMINAL && result.ok) {
                    return finish(AgentOutcome.COMPLETED, null, result.payload, steps, history, "", spentSteps)
                }
                if (cancelled) return finish(AgentOutcome.CANCELLED, null, null, steps, history, "", spentSteps)
                if (spentObservations >= maxObservations) {
                    return finish(AgentOutcome.NO_PROGRESS, OBSERVATION_BUDGET, null, steps, history, "", spentSteps)
                }
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

    // ---------------------------------------------------------------- Task 12: tool timeout (R60)

    /**
     * One tool attempt's outcome, including the three states the old synchronous call could not express: cancelled,
     * timed out (confirmed stop) and uncertain (the tool did not stop).
     */
    private class ToolExecution(
        val result: ToolResult,
        val executed: Boolean,
        val timedOut: Boolean = false,
        val uncertain: Boolean = false,
        val cancelled: Boolean = false,
        /**
         * R99/D49: the time this call spent waiting for a person beyond its machine cap, which the loop adds to the
         * turn deadline. `0` for every ordinary call.
         */
        val deadlineShiftNanos: Long = 0L,
    )

    /**
     * Runs one `registry.execute` on the loop's single tool executor, bounded by the earlier of the descriptor's own
     * timeout and the turn's remaining budget (R60).
     *
     * A completed call is returned as-is. A call that overruns is cancelled through [ToolRegistry.cancel] and then given
     * a fixed grace window to really stop: a stop confirmed by its future completing is `ACTION_TIMEOUT` (the model gets
     * one more chance and must observe before deciding); a tool still running after the grace is
     * `ACTION_TIMEOUT_UNCERTAIN`, which the caller turns into the run's terminal state — no later write may run while an
     * earlier one may still be in flight. The real result of a timed-out call is deliberately discarded: it arrived
     * after the model was told the call failed, and reporting two outcomes for one call would be a lie either way.
     *
     * R99: [mayAskHuman] is the descriptor's declaration that the executor may spend D47's decision window inside
     * `execute`. Such a call is bounded by `timeoutMillis + HUMAN_CONFIRMATION_ALLOWANCE_NANOS` and **not** clamped by
     * the turn deadline; whatever it spent beyond the machine cap is reported as [ToolExecution.deadlineShiftNanos] so
     * the caller can shift the turn deadline (D49). Every other call keeps `min(timeoutMillis, deadline - now)`.
     */
    private fun runTool(
        call: ToolCall,
        timeoutMillis: Long,
        turnDeadlineNanos: Long,
        toolExecutor: ExecutorService,
        mayAskHuman: Boolean = false,
    ): ToolExecution {
        val machineBudgetNanos = timeoutMillis.coerceAtLeast(1L) * 1_000_000L
        val timeoutNanos = if (mayAskHuman) {
            machineBudgetNanos + HUMAN_CONFIRMATION_ALLOWANCE_NANOS
        } else {
            val budgetNanos = (turnDeadlineNanos - clock.nowNanos()).coerceAtLeast(1L)
            minOf(machineBudgetNanos, budgetNanos)
        }
        val future: Future<ToolResult> = toolExecutor.submit(Callable { registry.execute(call) })
        val startedAt = System.nanoTime()
        while (true) {
            if (cancelled) {
                awaitToolStop(future)
                return ToolExecution(ToolResult.error(call.id, ACTION_TIMEOUT), executed = false, cancelled = true)
            }
            val remaining = timeoutNanos - (System.nanoTime() - startedAt)
            if (remaining <= 0L) break
            try {
                // A short poll (not one long get) is what lets `cancel()` reach a running tool promptly. A result
                // that does arrive is a real completed call: it is recorded like before Task 12, and the loop's own
                // cancellation check right after the call ends the run.
                val completed = future.get(minOf(remaining, TOOL_POLL_STEP_NANOS), TimeUnit.NANOSECONDS)
                return ToolExecution(
                    completed,
                    executed = true,
                    deadlineShiftNanos = humanWaitShiftNanos(mayAskHuman, startedAt, machineBudgetNanos),
                )
            } catch (_: TimeoutException) {
                // Keep waiting; the loop above re-checks cancellation and the remaining budget.
            } catch (_: ExecutionException) {
                return ToolExecution(ToolResult.error(call.id, "TOOL_THREW"), executed = true)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                runCatching { registry.cancel() }
                awaitToolStop(future)
                return ToolExecution(ToolResult.error(call.id, ACTION_TIMEOUT), executed = false, cancelled = true)
            }
        }
        runCatching { registry.cancel() }
        val stopped = awaitToolStop(future)
        val humanShift = humanWaitShiftNanos(mayAskHuman, startedAt, machineBudgetNanos)
        return if (stopped) {
            ToolExecution(
                ToolResult(call.id, ok = false, payload = "$ACTION_TIMEOUT: $ACTION_TIMEOUT_NOTE"),
                executed = true,
                timedOut = true,
                deadlineShiftNanos = humanShift,
            )
        } else {
            ToolExecution(
                ToolResult(call.id, ok = false, payload = "$ACTION_TIMEOUT_UNCERTAIN: $ACTION_TIMEOUT_UNCERTAIN_NOTE"),
                executed = true,
                uncertain = true,
            )
        }
    }

    /**
     * R99/D49: the part of a `mayAskHuman` call that must not be charged to the turn — the time it spent beyond its
     * machine cap, bounded by the fixed allowance. Machine time inside the cap was already the turn's own budget.
     */
    private fun humanWaitShiftNanos(
        mayAskHuman: Boolean,
        startedAtNanos: Long,
        machineBudgetNanos: Long,
    ): Long {
        if (!mayAskHuman) return 0L
        return (System.nanoTime() - startedAtNanos - machineBudgetNanos)
            .coerceIn(0L, HUMAN_CONFIRMATION_ALLOWANCE_NANOS)
    }

    /** Waits up to the fixed grace window for [future] to finish; returns whether it did. */
    private fun awaitToolStop(future: Future<ToolResult>): Boolean {
        val untilNanos = System.nanoTime() + TOOL_TIMEOUT_GRACE_NANOS
        while (System.nanoTime() < untilNanos) {
            if (future.isDone) return true
            try {
                val remaining = (untilNanos - System.nanoTime()).coerceAtLeast(1L)
                future.get(minOf(remaining, TOOL_POLL_STEP_NANOS), TimeUnit.NANOSECONDS)
                return true
            } catch (_: TimeoutException) {
                // Still running inside the grace window.
            } catch (_: ExecutionException) {
                return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return future.isDone
            }
        }
        return future.isDone
    }

    // ---------------------------------------------------------------- Task 12: runtime state (R59)

    /**
     * Folds the current [AgentRequest.runtimeState] into [frame] as one untrusted `RUNTIME_STATE` segment before the
     * frame's `USER_INPUT`, unless it is byte-identical to the state the previous request carried.
     *
     * R100: the line is composed, not only forwarded. The loop-owned `spentSteps`/`spentObservations` and the
     * last-write-unverified / typed-unsent flags come first (so the cap can never hide them), then the provider's device
     * state fits in what remains. The result is capped at [TurnRuntimeState.MAX_CHARS] and carries no user content.
     *
     * The segment is request metadata: it is not appended to `history`, so it never reaches [AgentRun.transcript], a
     * `ConversationStore` or a tool result. A provider that returns `null` or throws contributes nothing — the model
     * simply gets no state line, never a fabricated one.
     */
    private fun withRuntimeState(frame: RequestFrame, request: AgentRequest, spentSteps: Int): RequestFrame {
        val state = runCatching { request.runtimeState?.invoke() }.getOrNull() ?: return frame
        val composed = TurnRuntimeState.compose(
            deviceText = state.text,
            spentSteps = spentSteps,
            spentObservations = spentObservations,
            lastWriteUnverified = lastWriteUnverified,
            typedUnsent = typedUnsent,
        )
        val hash = sha256Hex(composed.text)
        if (hash == lastRuntimeStateHash) return frame
        lastRuntimeStateHash = hash
        val flow = frame.conversationFlow
        val inputIndex = flow.indexOfLast { it.origin == SegmentOrigin.USER_INPUT }
        if (inputIndex < 0) return frame
        return frame.copy(
            conversationFlow = flow.take(inputIndex) +
                Segment(SegmentOrigin.RUNTIME_STATE, trusted = false, text = composed.text) +
                flow.drop(inputIndex),
        )
    }

    /**
     * R100: whether one call carries the model's text-entry payload — the `value` member the `device` schema uses for
     * a typed value (`ActionPlanner` types `value` and falls back to `text`).
     *
     * Only the **presence** of a non-blank member is read; its content never leaves the tool call and never reaches the
     * runtime state. The seam has no declarative "this call types" signal (the descriptor only says
     * `observation`), so this is deliberately conservative: a call that does not use `value` cannot set the flag, which
     * is better than reporting text the model never typed. The next successful state-changing call clears it.
     */
    private fun carriesTypedInput(argsJson: String): Boolean {
        val args = runCatching { auditArguments.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return false
        val value = (args["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return !value.isNullOrBlank()
    }

    // ---------------------------------------------------------------- Task 12: plan nudge (R58)

    /**
     * Whether [text] is a **plan** that should be nudged into execution instead of accepted as the answer.
     *
     * Every condition of R58 must hold: the route allows nudging, this run has already executed a tool successfully,
     * the text is short enough, it carries a plan marker and no completion marker, and the run is not at its deadline /
     * step / observation budget. A pure Q&A turn (no successful tool) is never nudged; neither is a text that reports
     * an outcome. The "identical text after a nudge" rule lives at the call site, where the nudge count is known.
     */
    private fun shouldPlanNudge(
        text: String,
        request: AgentRequest,
        turnDeadlineNanos: Long,
        spentSteps: Int,
    ): Boolean {
        if (!request.allowPlanNudge) return false
        if (successfulToolExecutions == 0) return false
        if (text.length > MAX_PLAN_TEXT_CHARS) return false
        if (clock.nowNanos() >= turnDeadlineNanos) return false
        if (spentSteps >= maxSteps) return false
        if (spentObservations >= maxObservations) return false
        val lower = text.lowercase()
        if (PLAN_COMPLETION_MARKERS.any { text.contains(it) || lower.contains(it) }) return false
        return PLAN_MARKERS.any { marker -> text.contains(marker) || lower.contains(marker) }
    }

    /**
     * Adds `[assistant plan text, USER nudge]`. The assistant half keeps the history alternating (the endpoint is
     * stateless and a lone user instruction would be an unpaired turn); the user half is loop scaffolding and
     * [withoutRetryScaffolding] strips the pair before a caller stores the transcript.
     */
    private fun appendPlanNudge(history: MutableList<ModelHistoryItem>, text: String) {
        history += ModelHistoryItem.Text(MessageRole.ASSISTANT, text.ifBlank { EMPTY_TURN_TEXT })
        history += ModelHistoryItem.Text(MessageRole.USER, PLAN_NUDGE_INSTRUCTION)
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
        spentSteps: Int,
    ): AgentRun {
        val last = history.lastOrNull()
        if (last is ModelHistoryItem.Text && last.role == MessageRole.USER) {
            history += ModelHistoryItem.Text(MessageRole.ASSISTANT, closingText.ifBlank { CLOSED_TURN_TEXT })
        }
        lastTranscript = history.toList()
        val metrics = AgentMetrics(
            calls = metricCalls,
            toolCalls = steps.size,
            modelMillis = metricModelNanos / NANOS_PER_MILLI,
            toolMillis = metricToolNanos / NANOS_PER_MILLI,
            observeMillis = metricObserveNanos / NANOS_PER_MILLI,
            images = metricImageIds.size,
            nudges = nudges,
            overflows = metricOverflows,
        )
        // 0.25 Task 14 (R78): exactly one count-only line per run. The sink is observability, never a verdict: a
        // throwing logger must not turn a completed run into an exception.
        runCatching { metricLine(renderMetric(metrics)) }
        return AgentRun(
            outcome, safeCode, finalText, steps.toList(), humanWaitNanos, lastTranscript, spentSteps, usage,
            spentObservations, planNudgeCap, metrics,
        )
    }

    /**
     * The one `P3_METRIC` line's exact shape (R78): counts only, fixed field order. The usage three-state rule (R21) is
     * carried by the two token fields: no accounting at all is `missing`, an accounting object with an absent counter
     * is `unknown`, and a reported zero is `0`.
     */
    private fun renderMetric(metrics: AgentMetrics): String = buildString {
        append("P3_METRIC")
        append(" calls=").append(metrics.calls)
        append(" toolCalls=").append(metrics.toolCalls)
        append(" modelMs=").append(metrics.modelMillis)
        append(" toolMs=").append(metrics.toolMillis)
        append(" observeMs=").append(metrics.observeMillis)
        append(" inputTokens=").append(tokenField(usage, usage?.inputTokens))
        append(" outputTokens=").append(tokenField(usage, usage?.outputTokens))
        append(" images=").append(metrics.images)
        append(" nudges=").append(metrics.nudges)
        append(" overflows=").append(metrics.overflows)
    }

    private fun tokenField(total: ModelUsage?, value: Long?): String = when {
        total == null -> "missing"
        value == null -> "unknown"
        else -> value.toString()
    }

    /**
     * Adds the images one model request carries to the run's distinct-image count (R78): a request-flow segment's
     * `images` and a history tool output's `images`. Artifact ids key the set, so the same screenshot echoed by two
     * requests of one run counts once.
     */
    private fun recordMetricImages(frame: RequestFrame?, history: List<ModelHistoryItem>) {
        frame?.conversationFlow?.forEach { segment -> segment.images.forEach { metricImageIds += it.artifactId } }
        history.forEach { item ->
            if (item is ModelHistoryItem.ToolOutput) item.images.forEach { metricImageIds += it.artifactId }
        }
    }

    /**
     * Adds one turn's accounting to the run's total. A turn that reported nothing contributes nothing; the two
     * counters are summed independently because [ModelUsage] admits one side being absent. The total stays `null`
     * until the first turn reports something, so "no accounting" is never fabricated as a zero count.
     */
    private fun accumulatedUsage(total: ModelUsage?, turn: ModelUsage?): ModelUsage? {
        if (turn == null) return total
        if (total == null) return turn
        return ModelUsage(
            inputTokens = sumTokens(total.inputTokens, turn.inputTokens),
            outputTokens = sumTokens(total.outputTokens, turn.outputTokens),
        )
    }

    private fun sumTokens(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> first + second
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

    companion object {
        /**
         * [items] without the loop-internal retry **and plan-nudge** scaffolding: each retry contributes exactly
         * `[assistant text, RETRY_INSTRUCTION]` and each plan nudge `[assistant plan text, PLAN_NUDGE_INSTRUCTION]`.
         *
         * The instructions are the loop talking to the model, not the wearer, and the assistant half is either a
         * placeholder, a cut-off partial reply or a plan the model never executed — spec §5.1's stored conversation has
         * only the user's own turns, the assistant's real replies and tool rounds. A caller that persists a run's
         * transcript therefore filters the pairs out (0.25 review ruling R16, Task 12 R58). The instructions are
         * matched by their constants here, in the file that owns them, rather than duplicated in the caller.
         *
         * A scaffolding pair is never split by the caller's tail boundary: both items are appended together, after the
         * turn's utterance. The only false positive would be a wearer typing one of the loop's instructions verbatim
         * directly after an assistant turn; those strings are loop-authored and not user-facing.
         */
        fun withoutRetryScaffolding(items: List<ModelHistoryItem>): List<ModelHistoryItem> {
            if (items.size < 2) return items
            val kept = ArrayList<ModelHistoryItem>(items.size)
            var index = 0
            while (index < items.size) {
                val item = items[index]
                val next = items.getOrNull(index + 1)
                val scaffoldingPair = item is ModelHistoryItem.Text && item.role == MessageRole.ASSISTANT &&
                    next is ModelHistoryItem.Text && next.role == MessageRole.USER &&
                    (next.text == RETRY_INSTRUCTION || next.text == PLAN_NUDGE_INSTRUCTION)
                if (scaffoldingPair) {
                    index += 2
                } else {
                    kept += item
                    index++
                }
            }
            return kept
        }
    }
}
