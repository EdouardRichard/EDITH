package edith.agent

import edith.plugin.ActionDescriptor
import edith.plugin.ActionExecutor
import edith.plugin.ActionOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val INVALID_TOOL_SCHEMA = "INVALID_TOOL_SCHEMA"
private const val DUPLICATE_TOOL_MERGED = "DUPLICATE_TOOL_MERGED"
private const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val TOOL_THREW = "TOOL_THREW"

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
 * with the highest [edith.plugin.RiskLevel] (ties keep the first registration) together with *its* executor, so a
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
            return ToolResult.error(call.id, TOOL_THREW)
        }
        return when (result.outcome) {
            ActionOutcome.Done -> ToolResult(call.id, ok = true, payload = result.detail)
            ActionOutcome.Refused -> ToolResult(call.id, ok = false, payload = "${ActionOutcome.Refused.name}: ${result.detail}")
            ActionOutcome.Failed -> ToolResult(call.id, ok = false, payload = "${ActionOutcome.Failed.name}: ${result.detail}")
        }
    }

    private fun argumentsParse(argsJson: String): Boolean =
        runCatching { strictJson.parseToJsonElement(argsJson) }.isSuccess ||
            runCatching { lenientJson.parseToJsonElement(argsJson) }.isSuccess
}

private fun ActionDescriptor.toToolDescriptor() = ToolDescriptor(
    name = name,
    description = title,
    parametersJson = parametersJson,
    risk = risk,
    requiresConfirmation = requiresConfirmation,
    idempotent = idempotent,
    reversible = reversible,
    scope = scope,
)
