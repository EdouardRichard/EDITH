package com.sheen.rokid.phone.plugin.smarthelper.skill
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
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
 *   gate). The body itself travels only through [onLoaded], which the plugin keeps for the current turn and renders as
 *   request-tail `SKILL_BODY` content (0.3 Task 12; it no longer rides the system slot), so it never appears in a
 *   receipt, a log line or the descriptor.
 * - **The model-facing result is the unified envelope** (0.3 Phase 2.2 Task 7). A successful pull was accepted and
 *   completed, so it states `accepted = true`; `applied`/`verified`/`changed` stay `null` because this tool has no
 *   independent evidence about a later model request or any device state, and `observation` is `null` because reading
 *   a SKILL never looks at the screen. The counter stays the envelope's `data`.
 * - **A refusal is a step the model can correct, never an exception into the loop.** Every parse failure — non-JSON,
 *   a JSON non-object, a missing/blank/non-string `name` — is `ActionOutcome.Refused` with a stable code. A name that
 *   is not pullable is refused too, and the two reasons stay distinguishable: an unknown name is [NOT_FOUND] (the
 *   model misremembered the catalog), a disabled entry is [DISABLED] (the user turned it off, so retrying is futile
 *   until the user acts). A refusal states its execution facts explicitly (Task 7 fix review): nothing was accepted or
 *   applied, nothing was verified or changed, there is no observation, and `recoverable` is true only for [NOT_FOUND]
 *   — a bare `ActionResult(Refused, code)` used to project "unknown, not recoverable", which was a false fact.
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
                ActionResult(
                    outcome = ActionOutcome.Done,
                    detail = detail,
                    execution = ToolExecution(outcome = ToolOutcome.Done, accepted = true),
                    observation = null,
                )
            }

            is SkillLoad.NotFound -> when (load.reason) {
                SkillLoadReason.NOT_IN_LIBRARY -> refused(NOT_FOUND)
                SkillLoadReason.DISABLED -> refused(DISABLED)
            }
        }
    }

    override fun cancel() = Unit

    /**
     * A refusal is a step the model can correct, never an exception into the loop.
     *
     * The facts are explicit (Task 7 fix review): a bare `ActionResult(Refused, code)` made the registry project
     * `code = null`, `accepted = null`, `applied = null` and `recoverable = false`. A malformed call never reaches the
     * lookup, and a name that cannot be pulled is rejected by it; either way nothing was accepted or applied
     * (`accepted = false`, `applied = false`) - the same reading `OpenTool.appNotFound` records for an unknown app
     * (`APP_NOT_FOUND`), not a half-executed success. No post-condition was checked and no screen observed, so
     * `verified`/`changed` and `observation` stay `null`.
     *
     * [recoverable] separates the two reasons: [NOT_FOUND] (including a malformed call) is a catalog mistake the model
     * can correct, while [DISABLED] means the user turned the entry off, so retrying the same pull cannot help. The
     * stable code stays the envelope's `data`.
     */
    private fun refused(code: String) = ActionResult(
        outcome = ActionOutcome.Refused,
        detail = code,
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = code,
            recoverable = code == NOT_FOUND,
            accepted = false,
            applied = false,
            verified = null,
            changed = null,
        ),
    )

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
            description = "按 name 读取一个 SKILL 的完整正文；正文只在本回合的请求尾部追加，不会出现在工具结果里。当系统提示给出的 SKILL 目录里有与本任务匹配的技能时，先调用它拿到步骤再执行；不需要技能的任务不要调用。名称不在目录返回 SKILL_NOT_FOUND（可核对目录后重试），技能被用户停用返回 SKILL_DISABLED（重试无意义，转告用户）。读取正文不算一步设备操作，不消耗 maxSteps。",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"SKILL 目录中的 name"}},"required":["name"],"additionalProperties":false}""",
            risk = RiskLevel.L0,
            requiresConfirmation = false,
            idempotent = true,
            reversible = true,
            scope = "skill",
            auditTargetArgument = null,
            // AC-18 / D-9: reading a SKILL body is reference reading, not a device step. Without this declaration the
            // model could exhaust `maxSteps` by pulling the tool it was just given, and the two-layer catalog would
            // defeat itself. This is the only `false` in the repository.
            countsTowardStepLimit = false,
        )
    }
}
