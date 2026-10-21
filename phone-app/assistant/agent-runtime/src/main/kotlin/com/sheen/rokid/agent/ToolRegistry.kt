package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val INVALID_TOOL_SCHEMA = "INVALID_TOOL_SCHEMA"
private const val DUPLICATE_TOOL_MERGED = "DUPLICATE_TOOL_MERGED"
private const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"

/** Strict parser: the schema check and the first arguments pass. */
private val strictJson = Json

/** Lenient fallback for model-authored arguments; kotlinx only accepts these member forms in lenient mode. */
private val lenientJson = Json { isLenient = true }

/**
 * Enforces that a tool's `parametersJson` is well-formed JSON *and* an object.
 *
 * `plugin-api` deliberately stays dependency-free and checks only the brace shape of an
 * [ActionDescriptor.parametersJson], so this is the only place in the repository where a tool schema is parsed.
 * Throws [IllegalArgumentException] (the contract Task 5's loop and the task-4 ruling rely on) naming the tool.
 */
internal fun validateToolSchema(toolName: String, parametersJson: String) {
    val parsed = try {
        strictJson.parseToJsonElement(parametersJson)
    } catch (_: SerializationException) {
        null
    }
    require(parsed is JsonObject) {
        "$INVALID_TOOL_SCHEMA: tool '$toolName' parametersJson must be a well formed JSON object"
    }
}

/**
 * The set of tools the agent may offer this run, built once from the executors' descriptors.
 *
 * Construction validates every schema and merges duplicate tool names: the surviving descriptor is the declaration
 * with the highest [com.sheen.rokid.plugin.RiskLevel] (ties keep the first registration) together with *its* executor, so a
 * descriptor never gets paired with an executor that did not declare it. Each merged name reports exactly one
 * `DUPLICATE_TOOL_MERGED:<name>` to [note].
 *
 * The registry is deliberately policy-free: it neither gates on risk, nor asks for confirmation, nor spends a budget.
 * Those belong to Tasks 7/8 and the loop; `execute` only turns one [ToolCall] into one [ToolResult] step.
 */
class ToolRegistry(
    private val executors: List<ActionExecutor>,
    private val note: (String) -> Unit = {},
) {
    private val registered: List<RegisteredTool>
    private val byName: Map<String, RegisteredTool>

    init {
        val merged = LinkedHashMap<String, RegisteredTool>()
        val noted = mutableSetOf<String>()
        for (executor in executors) {
            for (action in executor.descriptors()) {
                validateToolSchema(action.name, action.parametersJson)
                val existing = merged[action.name]
                if (existing == null) {
                    merged[action.name] = RegisteredTool(executor, action.toToolDescriptor())
                    continue
                }
                if (action.risk.ordinal > existing.descriptor.risk.ordinal) {
                    merged[action.name] = RegisteredTool(executor, action.toToolDescriptor())
                }
                if (noted.add(action.name)) note("$DUPLICATE_TOOL_MERGED:${action.name}")
            }
        }
        registered = merged.values.toList()
        byName = merged.toMap()
    }

    fun descriptors(): List<ToolDescriptor> = registered.map { it.descriptor }

    /** `null` means "no such tool": an unknown name is a step to feed back, never an error verdict. */
    fun resolve(name: String): RegisteredTool? = byName[name]

    fun execute(call: ToolCall): ToolResult {
        val tool = resolve(call.name) ?: return ToolResult.error(call.id, UNKNOWN_TOOL)
        if (!argumentsParse(call.argsJson)) return ToolResult.error(call.id, ARGUMENTS_INVALID)
        val result = try {
            // The executor receives the model's original string: validating arguments must not rewrite them.
            tool.executor.execute(call.name, call.argsJson)
        } catch (_: Exception) {
            // The call was dispatched, so it may have applied part of itself: `accepted`/`applied`/`changed` are
            // unknown and the result is Uncertain. Claiming "nothing changed" here would be a fabricated fact.
            return ToolResult.threw(call.id)
        }
        // R57: the observation fingerprint rides through verbatim, on every outcome — a refused write that left the
        // screen unchanged is exactly the evidence the poll/global detectors need. R98: so does the per-call
        // state-changing declaration, which is what tells a write that returns an observation apart from a read.
        // 0.25 Task 24 (R133): so do the images the call produced: a screenshot's reference reaches the model through
        // the tool output, and dropping it here would make the whole image path unreachable again.
        // edith-0.3 Phase 2.2: a migrated tool's explicit execution/observation are authoritative; an unmigrated tool's
        // ActionOutcome is projected onto the matching ToolOutcome, and its old `detail` becomes the envelope's `data`
        // verbatim (never prefixed, never duplicated into the observation).
        val execution = result.execution ?: ToolExecution(outcome = result.outcome.toToolOutcome())
        return ToolResult(
            id = call.id,
            // `ok` describes the execution the model (and every consumer) receives: an explicit execution is
            // authoritative and the legacy ActionOutcome must never report a success it denies.
            ok = execution.outcome == ToolOutcome.Done,
            execution = execution,
            observation = result.observation,
            data = result.detail,
            fingerprint = result.fingerprint,
            stateChanging = result.stateChanging,
            images = result.images,
            nonPersistent = result.nonPersistent,
        )
    }

    /**
     * Task 12 (R60/R61): asks every executor to stop the work a cancelled/timed-out call may still be running.
     *
     * The device kernel's `cancel` is best-effort (a wedged platform call may ignore it), which is exactly why the loop
     * waits a grace window and reports `ACTION_TIMEOUT_UNCERTAIN` when a call does not stop. A throwing executor must not
     * stop the others from being cancelled: this is the teardown path, so every executor is attempted and the first
     * failure is swallowed.
     */
    fun cancel() {
        // Cancel the executors this registry was built from, including one whose every descriptor lost a duplicate-name
        // merge: an executor that declared a tool must stop when the turn ends even if another declaration was the one
        // the model saw.
        executors.forEach { executor -> runCatching { executor.cancel() } }
    }

    /**
     * 0.25 Task 12 fix (R97): re-arms every executor after a **confirmed** tool timeout, so the rest of the same agent
     * turn can still act.
     *
     * [ActionExecutor.cancel] is turn-scoped on the host side — the device kernel refuses every later call until
     * `beginTurn` — so the stop a timeout issues would otherwise leave the executor cancelled for the whole rest of the
     * turn. The loop calls this only when the timed-out call really stopped ([AgentLoop]'s `ACTION_TIMEOUT` path); a
     * STOP/cancel, a teardown and an `ACTION_TIMEOUT_UNCERTAIN` never re-arm. Like [cancel], every executor is attempted
     * and the first failure is swallowed: one executor that cannot re-arm must not leave the others cancelled.
     */
    fun beginTurn() {
        executors.forEach { executor -> runCatching { executor.beginTurn() } }
    }

    private fun argumentsParse(argsJson: String): Boolean =
        runCatching { strictJson.parseToJsonElement(argsJson) }.isSuccess ||
            runCatching { lenientJson.parseToJsonElement(argsJson) }.isSuccess
}

private fun ActionDescriptor.toToolDescriptor() = ToolDescriptor(
    name = name,
    // edith-0.3 Phase 4A (A1): the model-facing description is the new field; its default is the title, which keeps
    // every descriptor that declares no description on the pre-4A copy.
    description = description,
    // edith-0.3 Phase 4A (A5): keep the user-facing label a separate channel. The confirmation summary and the tool
    // row read this; the model never does.
    title = title,
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

/** The pre-0.3 declaration projected onto the unified execution vocabulary; no facts beyond the outcome are invented. */
private fun ActionOutcome.toToolOutcome(): ToolOutcome = when (this) {
    ActionOutcome.Done -> ToolOutcome.Done
    ActionOutcome.Refused -> ToolOutcome.Refused
    ActionOutcome.Failed -> ToolOutcome.Failed
}
