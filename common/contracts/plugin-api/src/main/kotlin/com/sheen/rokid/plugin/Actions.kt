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
    /**
     * 0.25 Task 12 (R57): whether one call of this action returns a **post-call observation fingerprint** — the identity
     * the loop's poll/global detectors and its observation budget work with.
     *
     * This is a declaration, not something the runtime infers from a tool's name (D110's rule: plugins declare
     * capability, the framework implements the channel). `false` — the default — keeps every existing action on the
     * pre-0.25 behaviour: no fingerprint is expected, `spentObservations` does not count it and the poll/global
     * detectors ignore it. A tool that both reads and writes (`device`: `observe` and `act`) declares `true` once,
     * because every one of its calls comes back with an observation.
     */
    val observation: Boolean = false,
    /**
     * 0.25 Task 12 (R60): the longest one call of this action may run before the loop treats it as timed out.
     *
     * The loop uses `min(timeoutMillis, turnDeadline - now)`, so this can only shorten a turn, never extend one. The
     * default is the D3 §1.7 ordinary-tool value (30 s); a very long operation can declare a larger budget, and a
     * cheap local tool a smaller one. A timeout is a tool result (`ACTION_TIMEOUT`), never a silent retry.
     */
    val timeoutMillis: Long = 30_000L,
    /**
     * 0.25 Task 12 fix (R99): whether one call of this action may **wait for a person** inside `execute`.
     *
     * The `shell` tool is the one such action in this version: the plugin's confirming wrapper asks the wearer
     * (D47's 120 s decision window) inside the same `execute` call the loop time-bounds, so the loop cannot see the
     * human decision itself. For a `true` descriptor the call's budget is its [timeoutMillis] **machine** cap plus a
     * fixed human allowance, not clamped by the turn deadline; after it returns, the time spent beyond the machine cap
     * is shifted out of the turn (D49). `false` — the default, and every other action — keeps the pre-R99
     * `min(timeoutMillis, turnDeadline - now)` behaviour exactly; `timeoutMillis` must not be inflated to fake a
     * human wait, because the turn deadline would still clamp it.
     */
    val mayAskHuman: Boolean = false,
    /**
     * edith-0.3 Phase 2.2 Task 3: whether one call of this action **touches the screen**, so its non-successful result
     * should still carry a best-effort post-call observation for the model.
     *
     * This is deliberately **not** [observation]: that flag means the call returns a fingerprint for the loop's
     * progress detectors (`open` changes the screen but declares `observation = false`), while this one means the host
     * has a screen to observe after the call. Only `device`/`open`/`shell`/`dial` declare it `true` (Tasks 4-6); a pure
     * plugin tool (`notify_user`, `load_skill`) keeps the default `false`, because inventing a screen observation for a
     * tool that never looks at one would be a fabricated fact.
     *
     * `agent-runtime` reads it only for a **non-`Done`** result that carries no observation of its own: it calls the
     * injected `ToolObservationSource.observeWithImages(250)` **once**. A missing source is `unavailable(no_source)`;
     * a source that returns null, throws or times out is `unavailable(observation_timeout)`; a real capture may add at
     * most one failure image (the provider reports its own `provider_threw`/timeout reason). A successful call's
     * observation belongs to the tool. The model-facing form is the single `{execution, observation|null, data}`
     * envelope derived by `ToolResultEnvelope` in `agent-runtime`; this flag is not a second payload and this KDoc is
     * not a wire contract.
     */
    val screenObservation: Boolean = false,
    /**
     * edith-0.3 Phase 4A (A1): the **model-facing** description of when and how to use this action.
     *
     * Unlike [title] — the short, user-visible row copy — this is the prose the model reads in the tool schema: when to
     * choose the action (and when not to), what a failure means, and what the arguments constrain. The default keeps
     * every pre-4A descriptor byte-for-byte: a plugin that declares no description gets its title, exactly the copy the
     * model received before this field existed.
     *
     * The value must be non-blank: an empty description would offer the model a tool it cannot decide to call. The
     * user-visible label channel (`ToolLabelMap`) stays a name→label table and is not derived from this field.
     */
    val description: String = title,
) {
    init {
        require(name.isNotBlank())
        require(title.isNotBlank())
        require(description.isNotBlank())
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
        require(timeoutMillis > 0L) { "timeoutMillis must be positive, was $timeoutMillis" }
    }
}

/**
 * 0.25 Task 12 (R57): one action's result, plus the optional observation fingerprint described above.
 *
 * [fingerprint] is a stable hash of the **screen/window content after the call** (never of the call's output text and
 * never a timing value: a timestamp would make every result look like progress). Two calls that leave the same screen
 * produce the same fingerprint, which is what lets `poll_no_progress` and the global breaker see "nothing changed".
 * `null` — the default, and what every action except `device` returns — means this call has no observation state.
 */
data class ActionResult(
    val outcome: ActionOutcome,
    val detail: String,
    val fingerprint: String? = null,
    /**
     * 0.25 Task 12 fix (R98): whether this call **changes state** — a write, not a read-only observation.
     *
     * A descriptor cannot answer this for a multi-action tool: `device` declares [ActionDescriptor.observation] once
     * because every one of its calls returns a post-call fingerprint, so "not an observation" would misclassify
     * `device.act` as a read. The tool therefore declares it per **call**. The agent loop feeds its `ProgressGuard` only
     * a state-changing result that also carries a [fingerprint], which keeps R62's read-only exclusion while letting a
     * write that returns an observation be recognised as a write.
     *
     * `false` — the default, and what `device.observe`/`screenshot`/`wait`/`status` and the plugin's own tools return —
     * means this call only reads.
     */
    val stateChanging: Boolean = false,
    /**
     * 0.25 Task 24 (R133): the image artifacts this call produced, referenced by [ImageRef] — never inline bytes.
     *
     * `device.screenshot` is the one 0.25 action that answers an image: the kernel stores the captured bytes and reports
     * the reference here (a real artifact, or the `pending` placeholder plus its diagnostic). The host's agent loop
     * projects the list onto `ToolResult.images` and then onto the tool output it feeds back to the model, which is the
     * only channel from a tool's picture to the model request (0.25 spec §4.6/§5.3).
     *
     * Empty — the default — keeps every existing action and every pre-0.25 construction source-compatible, and a tool
     * that produces no image pays nothing extra on the wire.
     */
    val images: List<ImageRef> = emptyList(),
    /**
     * edith-0.3 Phase 2 final review (C2): this result's [detail] carries **perception content** (screen text,
     * notifications, clipboard, calendar, ...), which may live only in the current model request and must never be
     * persisted to a `ConversationStore` or an `AgentRun.transcript` (spec §7.8).
     *
     * The agent loop still feeds the full detail back to the model for this run; at `finish` it replaces the stored
     * form of a marked tool output with a safe marker and drops its images. Every content-bearing `device` action
     * result (`observe`/`act`/`screenshot`/`wait`/`status`) sets it: the perception-backed actions carry the compact
     * screen, the legacy projector path still renders raw screen text into `observe`/`wait`/`act`, and `screenshot`
     * carries artifact references (final-review C2 re-review; argument-validation refusals carry no content). `false` —
     * the default — keeps every other tool's construction and output byte-for-byte unchanged.
     */
    val nonPersistent: Boolean = false,
    /**
     * edith-0.3 Phase 2.2: the structured execution facts of this call, or `null` for a tool that has not been migrated
     * to the unified result contract yet.
     *
     * `null` is not "success": the host projects the plain [outcome] onto `ToolOutcome.Done/Refused/Failed` and the tool
     * keeps its [detail] as the envelope's `data`. A migrated tool fills this with the facts the model actually needs
     * (`accepted/applied/verified/changed` as tri-state, `uncertain`/`partial` flags) instead of encoding them in prose.
     */
    val execution: ToolExecution? = null,
    /**
     * edith-0.3 Phase 2.2: what the device looked like after this call, or `null` when the tool does not observe the
     * screen (a pure tool such as `notify_user`) or did not attempt an observation.
     *
     * An observation is a device fact, never a restatement of [detail]; a tool that fills this must not repeat its
     * fields in [detail]. An empty-but-attempted observation is a real observation (see [ToolObservation]).
     */
    val observation: ToolObservation? = null,
)

/**
 * 0.25 Task 11 (R49): what an action executor thinks should happen to one call **before** it runs.
 *
 * The declaration is deliberately separate from [ActionDescriptor.requiresConfirmation]: that flag is static (the
 * `shell` descriptor is one tool), while one call's risk depends on its arguments (`dumpsys window` vs `pm clear`).
 * [Allow] means "no human confirmation is needed", never "the action is safe"; [Reject] means the arguments are not a
 * well-formed call and must not reach the executor at all.
 */
enum class PrecheckDecision { Allow, RequireConfirmation, Reject }

/**
 * The answer of [ActionExecutor.precheck].
 *
 * [summary] is the human-readable question a [RequireConfirmation] decision shows the wearer (it must never carry raw
 * user content); [reason] is a machine-readable category that may reach the audit trail, which is why neither is the
 * action's arguments.
 */
data class ActionPrecheck(
    val decision: PrecheckDecision,
    val summary: String? = null,
    val reason: String? = null,
)

interface ActionExecutor {
    fun descriptors(): List<ActionDescriptor>
    fun execute(name: String, argsJson: String): ActionResult
    fun cancel()

    /**
     * 0.25 Task 11 (R49): classifies **one call** before it executes.
     *
     * The host's action catalog overrides this to classify a raw `shell` command; every other executor keeps the
     * default so the seam is additive and no existing tool gains a confirmation it never had. A wrapper that gates
     * execution must call this first and must never execute a [PrecheckDecision.Reject] call.
     */
    fun precheck(name: String, argsJson: String): ActionPrecheck =
        ActionPrecheck(PrecheckDecision.Allow)

    /**
     * 0.25 Task 10 (R42): called at the start of every new assistant turn.
     *
     * [cancel] is **turn-scoped**: the host cancels an executor when a turn is torn down
     * (`Stop`, a replaced turn, a session end), and without a matching begin the executor would stay
     * cancelled for the rest of the process. The default is a no-op so every existing executor and
     * every hand-built test executor keeps compiling and behaving exactly as before; an executor
     * that owns turn-scoped state (the device kernel) overrides it to re-arm.
     *
     * 0.25 Task 12 fix (R97): a **confirmed** tool timeout is the one mid-turn re-arm. The loop cancels
     * the executors to stop the overrunning call and calls `beginTurn` again once that call has really
     * stopped, so the same turn can still act. A STOP/cancel, a teardown and an uncertain timeout never
     * re-arm — they must stay cancelled.
     */
    fun beginTurn() = Unit

    /**
     * 0.25 Task 12 (R59): one short, plain-state line describing where the device currently is, or `null`.
     *
     * The plugin folds this into the next model request as an untrusted `RUNTIME_STATE` segment so the model can reason
     * about the current screen without paying for another full observation. The line is pure state — foreground
     * package/window, latest revision/generation — never user text, and the agent runtime caps it at
     * `TurnRuntimeState.MAX_CHARS`.
     *
     * The default is `null`: an executor with no device state has nothing honest to say, and every pre-0.25 executor
     * keeps its behaviour exactly. The host's scoped wrapper must forward this method (R81), or the plugin would only
     * ever see this default.
     */
    fun runtimeState(): String? = null
}
