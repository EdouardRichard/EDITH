package edith.plugin

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
) {
    init {
        require(name.isNotBlank())
        require(title.isNotBlank())
        require(scope.isNotBlank())
        require(parametersJson.isNotBlank())
        require(parametersJson.length <= MAX_PARAMETERS_JSON_LENGTH) {
            "parametersJson must be at most $MAX_PARAMETERS_JSON_LENGTH characters"
        }
        val schema = parametersJson.trim()
        require(schema.startsWith("{") && schema.endsWith("}")) {
            "parametersJson must be shaped like a JSON object"
        }
    }
}

data class ActionResult(val outcome: ActionOutcome, val detail: String)

interface ActionExecutor {
    fun descriptors(): List<ActionDescriptor>
    fun execute(name: String, argsJson: String): ActionResult
    fun cancel()
}
