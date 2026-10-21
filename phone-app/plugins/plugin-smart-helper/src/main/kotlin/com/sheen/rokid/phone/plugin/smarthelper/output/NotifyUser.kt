package com.sheen.rokid.phone.plugin.smarthelper.output

import com.sheen.rokid.contracts.SpeechRequest
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
 * The four channels of spec §6.1.
 *
 * [wireName] is the string the model sends and the schema documents; it is deliberately not `name.lowercase()`,
 * because the wire contract and the Kotlin identifier are two different things that must be free to differ.
 */
enum class NotifyChannel(val wireName: String) {
    SCREEN("screen"),
    VOICE("voice"),
    BOTH("both"),
    SILENT("silent"),
    ;

    companion object {
        fun fromWire(raw: String): NotifyChannel? =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
    }
}

/** What one notification does to the output path. */
sealed class NotifyDecision {
    /** Submit one hint; a non-null [optionalSpeech] is a request to speak it, which the host may still refuse. */
    data class Show(val optionalSpeech: SpeechRequest?) : NotifyDecision()

    /**
     * Submit nothing, and mark the turn explicitly suppressed so the §6.2 fallback does not immediately contradict
     * the model's own decision.
     */
    data object Suppress : NotifyDecision()
}

/**
 * Maps a channel onto what the output path can express (spec §6.1).
 *
 * **`VOICE` has no voice-only representation and is mapped to `BOTH`.** `OutputArbiter` calls `device.show` for every
 * accepted hint, and `core-contracts`' `Assistance` carries only `optionalSpeech: SpeechRequest?` — there is no way
 * to say "speak this and do not display it". Changing that would be a public-contract change to `core-contracts`, so
 * this version states the exposure (`NotifyChannel.VOICE.wireName` is logged by the caller) instead of inventing a
 * distinction the host cannot honour. `SILENT` is the one channel that submits nothing.
 */
fun decideChannel(channel: NotifyChannel): NotifyDecision = when (channel) {
    NotifyChannel.SCREEN -> NotifyDecision.Show(optionalSpeech = null)
    NotifyChannel.BOTH, NotifyChannel.VOICE -> NotifyDecision.Show(optionalSpeech = SpeechRequest())
    NotifyChannel.SILENT -> NotifyDecision.Suppress
}

/**
 * The `notify_user` tool (spec §6.1): the only channel through which the model reaches the wearer.
 *
 * It is an [ActionExecutor] rather than a new tool port in `agent-runtime`, and each consequence of that choice is
 * deliberate:
 *
 * - **L0, no confirmation.** The action policy allows `<= L1` outright, so a notification is never gated by the
 *   whitelist or by a question — which is right, because showing the wearer a sentence is not a device action.
 * - **`scope` is not `SCOPE_TERMINAL`.** A notification in the middle of a multi-step task must not end the run, or
 *   §6.3's "the task continues" would be impossible.
 * - **`auditTargetArgument` is `null`.** The audit line then carries no argument at all, which is what keeps the
 *   wearer's notification text out of the trail.
 * - **The result detail is `notified:<n>`.** `AgentLoop` feeds every tool result to `ProgressGuard`, which trips on
 *   three identical consecutive fingerprints; a model that sends the same sentence twice would otherwise end its own
 *   run as `NO_PROGRESS`. The counter keeps the fingerprints distinct and carries no content.
 * - **The model-facing result is the unified envelope** (0.3 Phase 2.2 Task 7). The call was accepted and completed,
 *   so it states `accepted = true`; `applied`/`verified`/`changed` stay `null` because this tool has no independent
 *   evidence that the wearer saw or heard the notification, and `observation` is `null` because it never looks at the
 *   screen. Inventing either would be a fabricated fact. The counter stays the envelope's `data`.
 * - **A refusal also states its facts** (Task 7 fix review). Invalid arguments are refused before anything runs, so
 *   `accepted = false` and `applied = false`; `recoverable = true` because the model can correct the call. Leaving the
 *   execution to the registry's projection used to read as "unknown, not recoverable", which was a false fact.
 */
class NotifyUserTool(private val onNotify: (NotifyChannel, String) -> Unit) : ActionExecutor {

    private val sequence = AtomicInteger()

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun execute(name: String, argsJson: String): ActionResult {
        val args = runCatching { JSON.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return refused()
        val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (text.isNullOrEmpty()) return refused()
        val channel = (args["channel"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let { NotifyChannel.fromWire(it) }
            ?: return refused()

        onNotify(channel, text)
        return ActionResult(
            outcome = ActionOutcome.Done,
            detail = "notified:${sequence.incrementAndGet()}",
            execution = ToolExecution(outcome = ToolOutcome.Done, accepted = true),
            observation = null,
        )
    }

    override fun cancel() = Unit

    /**
     * A refusal is a step the model can correct, never an exception into the loop.
     *
     * The facts are explicit (Task 7 fix review): a bare `ActionResult(Refused, code)` made the registry project
     * `code = null`, `accepted = null`, `applied = null` and `recoverable = false`. Nothing was dispatched, so
     * `accepted = false`/`applied = false` are facts; invalid arguments are recoverable because the model can fix the
     * call and retry; no post-condition was checked and no screen observed (`verified`/`changed` and `observation`
     * stay `null`). The stable code remains the envelope's `data`.
     */
    private fun refused() = ActionResult(
        outcome = ActionOutcome.Refused,
        detail = NOTIFY_ARGUMENTS_INVALID,
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = NOTIFY_ARGUMENTS_INVALID,
            recoverable = true,
            accepted = false,
            applied = false,
            verified = null,
            changed = null,
        ),
    )

    companion object {
        private val JSON = Json { isLenient = true }

        /** The stable code for every malformed/unsupported `notify_user` call. */
        const val NOTIFY_ARGUMENTS_INVALID = "NOTIFY_ARGUMENTS_INVALID"

        val DESCRIPTOR = ActionDescriptor(
            name = "notify_user",
            title = "向用户发送通知",
            description = "把要对用户说的话交给系统输出：text 是要显示或播报的句子，channel 取 screen（只显示）、voice（只播报，本版会同时显示）、both（显示并播报）或 silent（不打扰用户，用于不必打断的完成汇报）。final 只是模型自报本任务收尾的标记，本版不改变通知行为。需要让用户看到或听到回答时用它，收尾答复也应通过它给出；一个任务可以多次调用。参数不合法返回 NOTIFY_ARGUMENTS_INVALID。不要把工具调用过程、内部计划或屏幕原文塞进 text。",
            parametersJson = """{"type":"object","properties":{"text":{"type":"string","description":"要通知用户的内容"},"channel":{"type":"string","enum":["screen","voice","both","silent"],"description":"screen=仅眼镜显示，voice=仅语音播报，both=同时显示和播报，silent=不通知用户但阻止系统自动补发通知"},"final":{"type":"boolean","description":"true=模型声明任务结束（spec §3.7 决策5b）"}},"required":["text","channel"],"additionalProperties":false}""",
            risk = RiskLevel.L0,
            requiresConfirmation = false,
            idempotent = true,
            reversible = true,
            scope = "notify",
            auditTargetArgument = null,
        )
    }
}
