package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Lenient for the same reason the loop is: model-authored arguments are not always strictly formed JSON. */
private val hintArguments = Json { isLenient = true }

/**
 * The one tool a proactive **hint turn** may call (0.3 Phase 3 spec §4.2).
 *
 * The tool is the decision to speak: it executes nothing and observes nothing, it only validates that the model
 * proposed one shippable sentence. Its descriptor is `SCOPE_TERMINAL`, so a successful call ends the `AgentLoop` run
 * with the sentence as the run's `finalText` — the turn cannot act, and it cannot make a second request once the
 * sentence has been proposed.
 *
 * [lastRejection] is diagnostic only. The model-facing code is always [PROPOSE_HINT_INVALID] (the loop feeds it back
 * once), but the plugin's own silence line should keep the P3.2 vocabulary that a device triage already reads —
 * `BLANK` / `TOO_LONG` / `UNPARSABLE` — which a single wire code cannot distinguish. The loop may execute this tool at
 * most once per hint turn (`maxSteps = 1`), so the value always describes that one attempt.
 */
internal class ProposeHintTool : ActionExecutor {

    /**
     * Why the last [execute] refused the proposal, or `null` when it accepted one.
     *
     * A per-turn instance (`runHintTurn` builds one per hint turn), written by the loop's single tool thread and read
     * by the plugin after the run returns. `@Volatile` because those are two threads in production.
     */
    @Volatile
    var lastRejection: Rejection? = null
        private set

    /** The three argument refusals; [name] is the silence vocabulary the plugin logs. */
    internal enum class Rejection { UNPARSABLE, BLANK, TOO_LONG }

    override fun descriptors(): List<ActionDescriptor> = listOf(ProactiveHintSpec.DESCRIPTOR)

    /**
     * Nothing to stop: [execute] validates one in-memory sentence and returns, so a cancel can never interrupt work.
     * The loop still asks, because every executor it holds is asked on `Stop`/a cancelled run.
     */
    override fun cancel() = Unit

    override fun execute(name: String, argsJson: String): ActionResult {
        lastRejection = null
        val element = runCatching {
            hintArguments.parseToJsonElement(argsJson).jsonObject["text"]
        }.getOrNull()
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) return refuse(Rejection.UNPARSABLE)
        val text = primitive.content.trim()
        if (text.isEmpty()) return refuse(Rejection.BLANK)
        if (text.codePointCount(0, text.length) > ProactiveHintSpec.PROACTIVE_HINT_MAX_CHARS) {
            return refuse(Rejection.TOO_LONG)
        }
        return ActionResult(
            outcome = ActionOutcome.Done,
            detail = text,
            execution = ToolExecution(outcome = ToolOutcome.Done, accepted = true),
            observation = null,
        )
    }

    /**
     * One refused proposal: the call never ran, so `accepted = false` / `applied = false` are facts, not guesses, and
     * no observation is invented.
     */
    private fun refuse(rejection: Rejection): ActionResult {
        lastRejection = rejection
        return ActionResult(
            outcome = ActionOutcome.Refused,
            detail = PROPOSE_HINT_INVALID,
            execution = ToolExecution(
                outcome = ToolOutcome.Refused,
                code = PROPOSE_HINT_INVALID,
                recoverable = false,
                accepted = false,
                applied = false,
            ),
            observation = null,
        )
    }

    /** The one stable machine code a refused proposal carries. */
    companion object {
        /** Shared with the spec's declaration: one name, so the registry and the prompt cannot drift. */
        const val NAME = ProactiveHintSpec.NAME

        const val PROPOSE_HINT_INVALID = "PROPOSE_HINT_INVALID"
    }
}
