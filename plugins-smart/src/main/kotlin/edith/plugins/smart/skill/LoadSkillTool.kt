package edith.plugins.smart.skill

import edith.plugin.ActionDescriptor
import edith.plugin.ActionExecutor
import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.RiskLevel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The `load_skill` tool (Phase 6, design D-1/D-2/D-4/D-8): the model's only way to read a SKILL body, which the
 * catalog deliberately leaves out of the system frame.
 *
 * It is an [ActionExecutor] with a pure [execute] and one callback, the same shape as `NotifyUserTool`. Each
 * consequence of that choice is deliberate:
 *
 * - **L0, no confirmation.** Reading the user's own installed text is not a device action, so it is never gated by the
 *   action policy.
 * - **`scope` is not `SCOPE_TERMINAL`.** A pull in the middle of a multi-step task must not end the run.
 * - **`auditTargetArgument` is `null`.** The argument is the user's own SKILL name, so it never reaches the audit
 *   trail; the trail answers "what did it touch" with the action alone.
 * - **The detail is a counter, never the body** (D-8). `AgentLoop` feeds every tool result's text to `ProgressGuard`,
 *   which trips on three identical consecutive fingerprints; a constant receipt would make two pulls of the same
 *   SKILL look like `NO_PROGRESS` and end the run. `loaded:<n>` advances on every successful pull, and
 *   `SKILL_DORMANT loaded:<n>` additionally says the entry was reached by name while not in the catalog (D-4's second
 *   gate). The body itself travels only through [onLoaded], which the plugin routes into the system slot (D-2), so it
 *   never appears in a receipt, a log line or the descriptor.
 * - **A refusal is a step the model can correct, never an exception into the loop.** Every parse failure — non-JSON,
 *   a JSON non-object, a missing/blank/non-string `name` — is `ActionOutcome.Refused` with a stable code. A name that
 *   is not pullable is refused too, and the two reasons stay distinguishable: an unknown name is [NOT_FOUND] (the
 *   model misremembered the catalog), a disabled entry is [DISABLED] (the user turned it off, so retrying is futile
 *   until the user acts).
 */
class LoadSkillTool(
    private val lookup: (String) -> SkillLoad,
    private val onLoaded: (Skill) -> Unit,
) : ActionExecutor {

    private val sequence = AtomicInteger()

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun execute(name: String, argsJson: String): ActionResult {
        val args = runCatching { JSON.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return refused(NOT_FOUND)
        val skillName = (args["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
        if (skillName.isNullOrEmpty()) return refused(NOT_FOUND)

        return when (val load = lookup(skillName)) {
            is SkillLoad.Loaded -> {
                onLoaded(load.skill)
                val n = sequence.incrementAndGet()
                val detail = if (load.wasDormant) "$DORMANT loaded:$n" else "loaded:$n"
                ActionResult(ActionOutcome.Done, detail)
            }

            is SkillLoad.NotFound -> when (load.reason) {
                SkillLoadReason.NOT_IN_LIBRARY -> refused(NOT_FOUND)
                SkillLoadReason.DISABLED -> refused(DISABLED)
            }
        }
    }

    override fun cancel() = Unit

    /** A refusal is a step the model can correct, never an exception into the loop. */
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)

    companion object {
        private val JSON = Json { isLenient = true }

        const val NAME = "load_skill"

        /** The name is not in the library, or the call was malformed; either way the model may correct it. */
        const val NOT_FOUND = "SKILL_NOT_FOUND"

        /** The entry exists but the user disabled it — not a retryable condition. */
        const val DISABLED = "SKILL_DISABLED"

        /** The body was returned, but the entry was dormant (not in the catalog) before this pull. */
        const val DORMANT = "SKILL_DORMANT"

        val DESCRIPTOR = ActionDescriptor(
            name = NAME,
            title = "读取 SKILL 正文",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"SKILL 目录中的 name"}},"required":["name"],"additionalProperties":false}""",
            risk = RiskLevel.L0,
            requiresConfirmation = false,
            idempotent = true,
            reversible = true,
            scope = "skill",
            auditTargetArgument = null,
        )
    }
}
