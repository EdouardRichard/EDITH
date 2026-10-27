package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.audit.AuditTrail
import com.sheen.rokid.phone.plugin.smarthelper.scene.ScenePolicy
import com.sheen.rokid.phone.plugin.smarthelper.confirmation.SmartConfirmation
import com.sheen.rokid.phone.plugin.smarthelper.context.ConversationContext
import com.sheen.rokid.phone.plugin.smarthelper.turn.modelRequestTimeoutNanos
import com.sheen.rokid.phone.plugin.smarthelper.memory.score
import com.sheen.rokid.phone.plugin.smarthelper.audit.safeFailureCode
import com.sheen.rokid.phone.plugin.smarthelper.turn.SmartTurnPhase
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveSkip
import com.sheen.rokid.phone.plugin.smarthelper.turn.SmartTurn
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import com.sheen.rokid.phone.plugin.smarthelper.output.decideChannel
import com.sheen.rokid.phone.plugin.smarthelper.proactive.admitByFeatures
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyChannel
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveSensitivity
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveTrigger
import com.sheen.rokid.phone.plugin.smarthelper.proactive.NoulThreshold
import com.sheen.rokid.phone.plugin.smarthelper.proactive.SmartProactive
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyDecision
import com.sheen.rokid.phone.plugin.smarthelper.output.ToolLabelMap
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.agent.AgentLoop
import com.sheen.rokid.agent.AgentOutcome
import com.sheen.rokid.agent.AgentRequest
import com.sheen.rokid.agent.AgentRun
import com.sheen.rokid.agent.AuditEntry
import com.sheen.rokid.agent.BoundedAuditLog
import com.sheen.rokid.agent.ConfirmationGate
import com.sheen.rokid.agent.ConfirmationPort
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.agent.ContextPolicy
import com.sheen.rokid.agent.ContextTuning
import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.ModelClientGateway
import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.agent.ProgressGuard
import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.RequestLayout
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.agent.SegmentRole
import com.sheen.rokid.agent.ToolObservationSource
import com.sheen.rokid.agent.ToolRegistry
import com.sheen.rokid.agent.TrimCounts
import com.sheen.rokid.agent.TurnRuntimeState
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.conversationDisplay
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.deviceState
import com.sheen.rokid.agent.WhitelistActionPolicy
import com.sheen.rokid.agent.WhitelistEntry
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.ContextChanged
import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.PinChanged
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechRequest
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.output.HintReadingBudget
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ConfiguredPlugin
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputSink
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolSpec
import com.sheen.rokid.plugin.actions
import com.sheen.rokid.plugin.confirmation
import com.sheen.rokid.plugin.context
import com.sheen.rokid.plugin.embedding
import com.sheen.rokid.plugin.model
import com.sheen.rokid.plugin.perception
import com.sheen.rokid.plugin.storage
import com.sheen.rokid.plugin.storageAccess
import com.sheen.rokid.plugin.systemOne
import com.sheen.rokid.plugin.textOutput
import com.sheen.rokid.plugin.wakeWord
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextCompressor
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.context.ModelSummarizer
import com.sheen.rokid.phone.plugin.smarthelper.context.PromptAssembly
import com.sheen.rokid.phone.plugin.smarthelper.context.estimatedLength
import com.sheen.rokid.phone.plugin.smarthelper.harness.SmartPerceptionRenderer
import com.sheen.rokid.phone.plugin.smarthelper.library.LibraryGovernor
import com.sheen.rokid.phone.plugin.smarthelper.memory.LegacyRawMemoryEvidenceRenderer
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryExtraction
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryMeasurementForm
import com.sheen.rokid.phone.plugin.smarthelper.memory.ProductionMemoryEvidenceRenderer
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ChoiceGate
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm
import com.sheen.rokid.phone.plugin.smarthelper.proactive.NoulGate
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGateState
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveHintRequest
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProposeHintTool
import com.sheen.rokid.phone.plugin.smarthelper.proactive.parseGateOptions
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.normalize
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModule
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModuleRetrieval
import com.sheen.rokid.phone.plugin.smarthelper.memory.SmartMemoryRetrieval
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.Skill
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoad
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoadReason
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillManager
import com.sheen.rokid.phone.plugin.smarthelper.skill.StorageAccessSkillDirectory
import com.sheen.rokid.phone.plugin.smarthelper.turn.ConversationCycle
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptPlan
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CONFIG_SCHEMA_VERSION = 1
private const val DEFAULT_WAKE_WORDS = "伊迪丝,edith"
private const val DEFAULT_MAX_LEVEL = "L1"
private const val HINT_LIFETIME_MILLIS = 5_000L
private const val DEFAULT_HINT_GRACE_MILLIS = 5_000L

/** 0.4 Task 10: the turn/event-id prefix of the phone's typed-input path (spec §5.2/D203). */
private const val TYPED_TURN_PREFIX = "typed-"

/** The `catalogHash`/`deviceHash` value that says "the source was absent", never an invented digest. */
private const val SKILL_HASH_NONE = "none"

/**
 * 0.24 S3 Task 14 fix round (I2): how long a promised closing fallback may take to reach the display.
 *
 * `ReadableHintAssembler` merges for 150 ms and can then still decide there is nothing readable to emit (a
 * one-character sentence like `好。`). One second is a wide margin over that single scheduled task while still
 * removing the unbounded pre-answer state: when it fires with no answer emit, the conversation closes with
 * `P3_CONV no_display`.
 */
private const val FALLBACK_EMIT_WAIT_MILLIS = 1_000L

/**
 * The agent run's wall-clock budget: effectively **none** (2026-10-22, D197).
 *
 * Mainstream agents bound a run with per-request/per-tool timeouts plus step and no-progress guards, not with a total
 * duration: Claude's Agent SDK leaves `maxTurns`/`maxBudgetUsd` off unless the caller sets them, OpenAI's Agents SDK
 * uses a turn count (10), and LangGraph uses a recursion limit. The old 180 s total cap cut a **working** 22-step
 * proactive follow-up at exactly 180 s (K70 acceptance 2026-10-22: `modelMs=120997` + `toolMs=56056`) and surfaced
 * the cut as "回答超时了，稍后再试"; the run was making progress.
 *
 * The guards that remain describe "stuck", not "slow": `AgentLoop.maxSteps` (24), `ProgressGuard`, the error streak,
 * the observation budget, the profile's per-request timeout, and the user's STOP/panel close. 24 h is a sanity
 * ceiling for arithmetic/schedulers, not a product limit; a real run ends long before it on the step guard.
 */
private const val AGENT_TURN_BUDGET_NANOS = 24L * 60L * 60L * 1_000_000_000L
private const val AUDIT_CAPACITY = 512

/**
 * The proactive cooldown and sensitivity the plugin uses when its configuration names neither of the two P3.2 keys.
 *
 * The pair mirrors `SmartSettings`' shipped defaults (60 s, `LOW`): the sensitivity was tightened on 2026-10-09 to
 * answer the measured false-trigger rate (`P3.2-FT`, both measured false triggers were 9-12 character phrases) while
 * the cooldown kept its original value by the owner's decision. A plugin driven with no configuration at all must not
 * be more willing to interrupt than a configured one.
 */
private const val DEFAULT_PROACTIVE_COOLDOWN_NANOS = 60_000_000_000L

/**
 * 0.3 Phase 3 Task 9d: the last-resort wall-clock bound on a hidden-panel hint's wait for `PANEL_SHOWN`.
 *
 * The **product** bound is the conversation cycle's own panel-acquisition cap (3 s), armed by the pre-open `onWake`;
 * its timeout cancels this wait through [SmartAssistantPlugin.cancelConversationTurn], so the wearer never waits this
 * long. This longer value only keeps a hint task from blocking forever if that callback is missed - for example a host
 * that never reports the panel and never releases the cycle round.
 */
private const val HINT_PANEL_ACQUIRE_TIMEOUT_MILLIS = 5_000L

/**
 * How long one **summary** request may take (spec §4.4 step 3).
 *
 * It is deliberately shorter than a turn's own budget: a summary is a background improvement to a transcript that is
 * already complete and usable, so waiting longer than a wearer would notice buys nothing. When the bound is reached the
 * request answers nothing and the compression stays plain truncation, which is §4.4's own degradation.
 */
private const val SUMMARY_DEADLINE_MILLIS = 8_000L

/** The `ContextChanged` key the foreground app arrives under; the key set is fixed (spec §16.6). */
private const val FOREGROUND_KEY = "app.foreground"

/** The other two ambient signals the intervention gate reads, under the same fixed key set (spec §16.6). */
private const val NOTIFICATION_KEY = "notification.category"
private const val CALENDAR_KEY = "calendar.todayTitles"

/**
 * The context key the host publishes its 「主动辅助」 state under.
 *
 * Mirrors `com.sheen.rokid.phone.model.ProactiveArming.CONTEXT_KEY`: the plugin cannot import the App's class (module
 * direction), so the string is duplicated with a pinning test on each side. The switch has to be readable *now*
 * because local transcription continues while it is off - see [SmartAssistantPlugin.armedNow].
 */
internal const val PROACTIVE_ARMED_KEY = "proactive.armed"

/** Configuration keys this plugin understands; anything else is refused rather than silently ignored. */
private val KNOWN_KEYS = setOf("profileId", "profileRevision", "wakeWords", "maxLevel", "whitelist", "requestConsent",
    "proactiveCooldownMillis", "proactiveSensitivity", "noInterception")

/** The fixed, honest sentence for each non-`COMPLETED` terminal state; never a success-looking hint. */
private val FAILURE_HINTS = mapOf(
    AgentOutcome.INSUFFICIENT to "我没听清这句请求，再说一次试试。",
    AgentOutcome.NO_PROGRESS to "这个操作一直没能推进，我先停下。",
    AgentOutcome.DEADLINE to "回答超时了，稍后再试。",
    AgentOutcome.CANCELLED to "已按你的要求取消。",
    AgentOutcome.FAILED to "智能助手这次没能给出结果。",
)

private const val BLANK_UTTERANCE_HINT = "没有听清内容，请再说一次。"

/**
 * 0.25 Task 26 (R140): the fixed, loop-internal wording a continuation request carries as its `USER_INPUT`.
 *
 * Once a tool round or a retry has appended after this turn's utterance, `AgentLoop` keeps the utterance in the
 * transcript and the frame builder must not append it a second time as the request input — the gateway maps the
 * transcript into the request history, so the same question would be asked twice. The frame still needs exactly one
 * `USER_INPUT` (a frame without one is refused, and the tool result must not become the input), so it carries this cue
 * instead. It is a constant with no user text and no turn-specific content, so a continuation can never leak or repeat
 * the wearer's words into the input.
 */
internal const val CONTINUE_AFTER_TOOL = "Continue after the tool result."

/**
 * The one sentence the assistant says when a hard dependency is missing (spec §1.3 C2).
 *
 * It is deliberately about the assistant rather than about the model: the wearer asked for something and cannot get
 * it, and "本地模型还没准备好" is true, actionable (wait or reinstall) and reveals nothing about which component
 * failed — that belongs in the log line, as a code.
 */
private const val DEPENDENCY_HINT = "本地模型还没准备好，暂时不能用。"

/**
 * 0.25 Task 11 (R52): command-level confirmation between the agent loop and the host's tools.
 *
 * `shell` is **one** descriptor, so its static risk cannot say whether *this* call is a `dumpsys` or a `pm clear`. The
 * wrapper therefore asks the host's own [ActionExecutor.precheck] before every call:
 * - [PrecheckDecision.Allow] - execute once, unchanged;
 * - [PrecheckDecision.Reject] - refuse with `SHELL_COMMAND_REJECTED`, executing nothing;
 * - [PrecheckDecision.RequireConfirmation] - ask through the plugin's existing [ConfirmationPort] and execute only after
 *   a live approval. A denial, a timeout, a cancelled question (STOP/急停) and a missing/broken channel each refuse
 *   without running the command.
 *
 * It never invents a question for a tool whose precheck says Allow, which is what keeps `device`/`open`/`dial`
 * unconfirmed (R-03); the plugin's own `notify_user`/`load_skill` are not wrapped here at all. The request id is a
 * UUID because it addresses one question, not a command, and the summary is the precheck's category text - never the
 * raw command (the same no-command-text rule as the audit trail).
 *
 * 0.3 Phase 4B (`D189`, spec §5.1): while the host's live 「不拦截」 gate answers true, [execute] calls the host directly
 * and never reaches [precheck] or [ConfirmationPort.request]. The classifier is skipped rather than consulted and
 * overruled: this mode is the host's explicit removal of command-level interception, and a `Reject` (a malformed call)
 * is not a confirmation either - the wearer chose to let calls through. The wrapper is plugin-owned and constructed
 * only by [SmartAssistantPlugin.registryFor], so this is not a bypass other executors can opt into; `precheck` itself
 * stays a truthful classifier for any caller that reads it.
 */
internal class ConfirmingActions(
    private val host: ActionExecutor,
    private val confirmation: ConfirmationPort,
    private val canAsk: () -> Boolean,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val diagnostics: (String) -> Unit = { P3Log.line(it) },
    private val noInterception: () -> Boolean = { false },
) : ActionExecutor {

    override fun descriptors(): List<ActionDescriptor> = host.descriptors()

    override fun cancel() = host.cancel()

    override fun beginTurn() = host.beginTurn()

    /** The wrapper adds no classification of its own: the host is the only side that knows a command's risk. */
    override fun precheck(name: String, argsJson: String): ActionPrecheck = host.precheck(name, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        // 0.3 Phase 4B (`D189`): no-interception is checked before the classifier, so a dangerous command runs with no
        // precheck and no question (spec §5.3). A provider that cannot answer is not an approval: fall back to the
        // normal gate, which will ask.
        if (runCatching(noInterception).getOrDefault(false)) return host.execute(name, argsJson)
        val precheck = runCatching { host.precheck(name, argsJson) }.getOrElse {
            // A precheck that cannot answer is not an approval. Refuse rather than fall through to executing.
            diagnostics("P3_CONFIRM action=$name outcome=unavailable reason=PRECHECK_FAILED")
            return refusal(SHELL_COMMAND_REJECTED, recoverable = true)
        }
        return when (precheck.decision) {
            PrecheckDecision.Allow -> host.execute(name, argsJson)
            PrecheckDecision.Reject -> {
                diagnostics("P3_CONFIRM action=$name outcome=rejected reason=${precheck.reason ?: "UNSPECIFIED"}")
                refusal(SHELL_COMMAND_REJECTED, recoverable = false)
            }
            PrecheckDecision.RequireConfirmation -> confirmThenRun(name, argsJson, precheck)
        }
    }

    private fun confirmThenRun(name: String, argsJson: String, precheck: ActionPrecheck): ActionResult {
        // No channel means there is nobody to ask. This is separate from STOP: there the question existed and was
        // withdrawn, so the honest answer is a denial, not "unavailable".
        if (!runCatching(canAsk).getOrDefault(false)) {
            diagnostics("P3_CONFIRM action=$name outcome=unavailable reason=NO_CHANNEL")
            // B7: the count-only companion of the line above. No question ever reached a surface, so the decision is
            // `unavailable` with no channel - recorded here because this is where the shell path's no-channel answer
            // is taken, and it must not be mistaken for the wearer denying anything.
            diagnostics("P4_CONFIRM tool=$name decision=unavailable channel=none")
            return refusal(CONFIRMATION_UNAVAILABLE, recoverable = true)
        }
        val requestId = newRequestId()
        val summary = precheck.summary?.takeIf { it.isNotBlank() } ?: DEFAULT_SUMMARY
        // B7: the tool's wire name (`shell`) travels with the question so the host's count-only line can count this
        // confirmation per tool; it is never shown and never the command text.
        val outcome = runCatching { confirmation.request(requestId, summary, name) }.getOrElse {
            diagnostics("P3_CONFIRM request=$requestId outcome=unavailable reason=CHANNEL_FAILED")
            return refusal(CONFIRMATION_UNAVAILABLE, recoverable = true)
        }
        if (outcome.approved) return host.execute(name, argsJson)
        val code = when (outcome.source) {
            ConfirmationSource.TIMEOUT -> CONFIRMATION_TIMEOUT
            // No channel, or the host could not present the question: nobody was asked, so this is not a denial.
            ConfirmationSource.UNAVAILABLE -> CONFIRMATION_UNAVAILABLE
            else -> CONFIRMATION_DENIED
        }
        diagnostics("P3_CONFIRM request=$requestId outcome=${outcomeName(code)} " +
            "source=${outcome.source.name.lowercase()}")
        // The wearer denied/let the question expire: retrying the same call cannot help. A missing channel can come back.
        return refusal(code, recoverable = code == CONFIRMATION_UNAVAILABLE)
    }

    /**
     * edith-0.3 Phase 2.2 Task 6 review: the structured facts of a refusal this wrapper itself synthesizes.
     *
     * The host action never ran, so `accepted = false` / `applied = false` are facts, not guesses; `verified` and
     * `changed` were never checked and stay `null`. Without this the code lived only in `detail` and the projected
     * execution reported `code`/`accepted`/`applied` as `null` - "refused, nothing ran" then read as "unknown".
     *
     * [recoverable] is false when retrying the same call cannot help (a command that is not a command, or the wearer's
     * own denial/expiry) and true when the channel that refused can come back (a failed precheck read, no confirmation
     * channel). It is the same convention the tools use for their own refusals.
     */
    private fun refusal(code: String, recoverable: Boolean): ActionResult = ActionResult(
        ActionOutcome.Refused,
        code,
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = code,
            recoverable = recoverable,
            accepted = false,
            applied = false,
            verified = null,
            changed = null,
        ),
    )

    private fun outcomeName(code: String): String = when (code) {
        CONFIRMATION_TIMEOUT -> "timeout"
        CONFIRMATION_UNAVAILABLE -> "unavailable"
        else -> "denied"
    }

    internal companion object {
        const val CONFIRMATION_DENIED = "CONFIRMATION_DENIED"
        const val CONFIRMATION_TIMEOUT = "CONFIRMATION_TIMEOUT"
        const val CONFIRMATION_UNAVAILABLE = "CONFIRMATION_UNAVAILABLE"
        const val SHELL_COMMAND_REJECTED = "SHELL_COMMAND_REJECTED"

        /** Only used if a host ever returns RequireConfirmation without a summary; still category text, never a command. */
        private const val DEFAULT_SUMMARY = "终端命令：无法确认是否安全"
    }
}

/**
 * The smart assistant plugin: the passive loop from wake to a hint on the glasses.
 *
 * One turn is: a `WakeDetected` opens it (`ScenePolicy` decides whether the wake is inside the cooldown), it **listens**
 * for a usable final `TranscriptEvent`, `agent-runtime`'s `AgentLoop` drives the model and tool calls, and the terminal
 * run is rendered through `output-runtime`'s `ReadableHintAssembler` into the host's `OutputSink`.
 *
 * The invariants this class is built around:
 * - **one loop per turn** — `AgentLoop` is explicitly single-use, so a loop is constructed for each turn inside the
 *   run and never reused; `Stop`/`deactivate` cancel the loop that is actually in flight;
 * - **a wake listens for a usable utterance, not for the first final** — the ASR finalises the segment that carried
 *   the wake word on its own, seconds before the request's own segment, so a blank final (or one that is only the
 *   wake word, with or without punctuation) is ignored instead of ending the turn (D12). Since 0.24 S3 the **official**
 *   10 s window belongs to `ConversationCycle` and every ASR message cancels it (spec §5.3), so a blank final neither
 *   ends the turn nor re-arms a timer; the panel stays listening until the wearer speaks, leaves, or the bounded
 *   `PANEL_SHOWN` wait expires (3 s, `P3_CONV panel_not_shown`);
 * - **only a final transcript runs the loop** — a partial revision never reaches the model;
 * - **never silence, never a false success** — every run that reaches a terminal state submits a hint: the model's own
 *   text for `COMPLETED`, and a fixed honest sentence for every other outcome;
 * - **`Stop` is final and session-scoped** — it cancels the loop, the conversation window and the pending output,
 *   and must not submit anything afterwards, and only a `Stop` for this session/generation may do so;
 * - **nothing is replayed across sessions** — the remembered final transcript belongs to the session that produced it,
 *   so `Help` after a re-activation runs this session's utterance or nothing at all.
 *
 * The descriptor is the host's grant list, not a wish: every capability in it is one this plugin actually calls
 * (`Context` drives the proactive path, `DeviceState` supplies the static device profile the prompt plan renders, and
 * so on). A declaration without a real call site would make the host answer a channel nothing reads.
 */
class SmartAssistantPlugin(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val scheduler: DeadlineScheduler = NoDeadlineScheduler,
    private val profileLookup: (String) -> ModelProfile? = { null },
    private val cancelOutputTurn: (SessionId, Generation, String) -> Unit = { _, _, _ -> },
    private val cooldownNanos: Long = 3_000_000_000L,
    /**
     * Test-only observation hook: called when a `Displayed` acknowledgement is accepted. The host's output arbiter
     * owns the real display state, so the plugin keeps none of it public; this exists so a JVM test can prove the ACK
     * was admitted without the plugin growing a production-only accessor.
     */
    private val onDisplayedAck: () -> Unit = {},
    /**
     * edith-0.2 Phase B: the session's transcript. `null` is the pre-Phase-B shape — the plugin keeps no conversation
     * — and is what a test that is about the passive loop wants.
     */
    private val contextForTurn: ContextManager? = null,
    /** The compressor, consulted **after** the turn's memory write (spec §4.9). `null` means never compress. */
    private val compressor: ContextCompressor? = null,
    /**
     * 0.3 design §5.2/§6.3: the ratio tuning `ContextPolicy.from` applies to the session's `ModelProfile`.
     *
     * Production passes the default. The seam exists because compression has to be reachable in a JVM test without
     * shrinking the profile's frame window: lowering only [ContextTuning.compressionThreshold] makes the history
     * allowance small while the model still receives the profile's full window.
     */
    private val contextTuning: ContextTuning = ContextTuning(),
    /**
     * 0.3 Phase 1 Task 12 (spec §4.2.1): the session's **static** device facts, read once at activation.
     *
     * Production passes nothing and [activate] resolves the host's `services.deviceState()`; this constructor parameter
     * is the test seam. A host that cannot provide the provider leaves this `null`, and the catalog then omits the
     * device section entirely - it must never be filled with a made-up model, locale or version.
     */
    private val deviceState: DeviceStateProvider? = null,
    /**
     * edith-0.2 Phase B (B11): the intervention gate — "should the model step in right now?" (spec §4.7).
     *
     * It sits **after** P3's own admission and throttle, replacing the cloud model's decision rather than adding to
     * it, so `null` means the pre-B11 behaviour: every admitted trigger goes straight to the model.
     */
    private val interventionGate: ProactiveGate? = null,
    /**
     * Which form the **production** gate uses (spec §4.7 leaves `noul` vs `choice` to measurement).
     *
     * `null` — the default — means no gate at all, which is the pre-B11 behaviour and what the tests that are about
     * the loop want. When set, the gate is built from the **host's** `SystemOneService` at call time rather than
     * injected, because the model arrives through a capability the plugin only holds once it is activated.
     */
    private val gateForm: GateForm? = null,
    /**
     * Phase 5: the session's transcript owner, one [ConversationStore] per route — or `null`, the default, for the
     * pre-Phase-5 shape, where [contextForTurn] (or the manager [activate] builds) owns the transcript and the request
     * is assembled from `ModelHistoryItem` history. Non-null switches every turn onto the production path: the route
     * records into its own store and each model turn is assembled as a [RequestFrame] the gateway trims and maps.
     *
     * `SmartWiring` builds one `ConversationStore()` per route. A route missing from a non-null map is the legacy path
     * for that route, so a partial map degrades one route rather than failing the session.
     *
     * The stores are **session** state: [activate] and [deactivate] empty them, exactly as they do the
     * manager-built transcript, so a re-activated session cannot replay the previous session's words.
     */
    private val conversationStores: Map<ContextRoute, ConversationStore>? = null,
    /**
     * 0.24 S3 (spec §5.3): whether the glasses panel is visible **right now**.
     *
     * The official 10 s timeout starts when the dialog opens. EDITH substitutes the glasses' `PANEL_SHOWN` for that
     * moment, so a slow overlay start is not charged to the wearer's silence; a turn that opens while the resident
     * panel is already up has no `PANEL_SHOWN` to wait for and starts the window immediately. The production
     * composition passes the host's live `isPanelVisible`; `{ false }` remains the default for a plugin built without
     * a panel (tests, harnesses), which keeps every pre-0.24 hidden-panel path intact.
     */
    private val isPanelVisible: () -> Boolean = { false },
    /**
     * 0.3 Phase 3 Task 9d (spec §4.3): the host's **readiness-aware** `PANEL_OPEN` request for a route, or `null` for a
     * host that has no panel opening path.
     *
     * This is the seam the proactive hint path uses when the panel is hidden: `HostCompositionRoot.requestPanelOpen`
     * carries the link's `appStart`/retry/readiness handshake, which the plain `ConversationDisplay.panelOpen` does
     * not. The request is asynchronous: it returns whether it was accepted for delivery, and the glasses answer with
     * `PANEL_SHOWN` once the overlay's first layout is up. The plugin opens **before** submitting its hint because the
     * glasses' `PANEL_OPEN` resets the panel's conversation (and would wipe an earlier-accepted hint), and the CXR
     * command channel does not guarantee delivery order. `null` keeps the pre-0.24 hidden-panel behavior for a host
     * without a window (tests, harnesses), so those hosts are not required to have a panel at all.
     */
    private val requestPanelOpen: ((AssistantRoute) -> Boolean)? = null,
    /**
     * 0.24 S3 (spec §5.5): whether an answer opens the next listening round.
     *
     * `true` is the official default (`continue_dialogue` defaults to `"open"`): a displayed answer continues with
     * status 5 and a fresh listening turn. `false` is the official `scheduleExit(1000)` path: no status 5, no new
     * turn, close one second later. Task 14 wires this to the persisted `SmartSettings.continueDialogue`.
     */
    private val continueDialogue: () -> Boolean = { true },
    /**
     * 0.24 S3 Task 14 fix round (C2, spec risk row 17): whether the host can actually speak a hint right now.
     *
     * Production builds the speech player **only when「语音朗读」is on** and the engine is ready, so a `both`/`voice`
     * answer in the default (speech-off) configuration has no playback and no `SpeechFinished` can ever arrive. A
     * plugin that treats "the model asked for speech" as "speech will happen" then waits the liveness bound and
     * closes `no_display` instead of continuing - the opposite of `continueDialogue = true`.
     *
     * When this is false, `both`/`voice` is display-only: the emitted [Assistance] carries no speech request and
     * `answerEmitSpeech` is false, so the answer is settled from its `Displayed` acknowledgement (spec risk row 17:
     * "EDITH「语音朗读」关闭时没有官方 audioFinished 事件 → 用答案 Displayed 作为下一轮触发"). `SmartWiring` passes the
     * production player's presence; the default `{ true }` keeps the official-shaped host (which always has TTS) and
     * the pre-fix test rigs behaving as before.
     */
    private val speechAvailable: () -> Boolean = { true },
    /**
     * edith-0.3 Phase 2 Task 9: the bounds the perception block renders under (element cap, notification cap, title
     * cap). The production default is the host's Phase 2 policy; the constructor seam exists so a test can pin a cap
     * without changing the provider's snapshot.
     */
    private val perceptionPolicy: PerceptionPolicy = PerceptionPolicy(),
    /**
     * 0.3 Phase 4B (`D189`, spec §5.1/§5.3): the **live** global 「不拦截」 gate.
     *
     * Production passes `NoInterceptionGate.provider()` through `SmartWiring`; the policy and the confirmation wrapper
     * read it on **every** decision, so a settings save applies from the next tool call without restarting the App. It
     * is a function rather than a boolean on purpose: a snapshot would pin the mode for one turn while the persisted
     * switch is meant to be global and live. The `{ false }` default keeps every pre-4B caller in normal mode.
     */
    private val noInterception: () -> Boolean = { false },
) : PluginLifecycle, ConfiguredPlugin {

    init {
        require(cooldownNanos >= 0) { "cooldownNanos must not be negative, was $cooldownNanos" }
    }

    override val descriptor = PluginDescriptor(
        "smart-assistant",
        "1.0.0",
        1,
        "Smart Assistant",
        listOf(
            Capability.WakeWord,
            Capability.Asr,
            Capability.Model,
            Capability.Actions,
            Capability.TextOutput,
            // P3.2 Task 8: the proactive path is driven by the host's perception signals, and the host only delivers
            // `AcousticEvent`/`ContextChanged` to a plugin that declares `Context` (HostRuntime's capability gate).
            Capability.Context,
            // P3.3 (spec §17.5): the audit trail persists into this plugin's own encrypted namespace. Declared so the
            // host hands the namespace over at activation; a host that cannot provide one still activates the plugin
            // and leaves the trail in memory, which `activate` treats as a degradation rather than a failure.
            Capability.Storage,
            // P3.3 (spec §17.4): without this the host never hands over a channel to ask the user, and every action the
            // policy flags is refused as unavailable - which is the correct behaviour for a host that cannot ask.
            Capability.Confirmation,
            // edith-0.2 Phase B (B4): the user's SKILL directory. This is the shared-storage grant, deliberately not
            // `Capability.Storage` - that one is the app-private key/value namespace. Without it the plugin has no
            // file system at all and the SKILL library is empty forever (spec §3.5).
            Capability.StorageAccess,
            // edith-0.2 Phase B (B9, spec §1.3 C2): the two local models are **hard dependencies**. Declaring them is
            // what makes the host hand them over, and the check that refuses service is what makes their absence a
            // refusal rather than a silent degradation. D60 recorded the accepted consequence: with Laya as the
            // intervention gate, "Laya unavailable" also stops the wake-word path.
            Capability.SystemOne,
            Capability.Embedding,
            // edith-0.2 Phase C (§6.7 助手页签): the port that carries "who said what" and "who is working" to the
            // host, which sends it to the glasses and fills the phone's conversation record with the same entries.
            //
            // **Declaring it is what makes it reachable.** The host grants a plugin exactly what its descriptor lists;
            // the port is optional, so a missing declaration is not a refusal - the accessor throws, the plugin's
            // `runCatching` turns that into "no port", and every report is silently dropped. That is precisely what
            // happened on the device: the spoken answer was fine while the phone's 助手 page stayed empty and not one
            // `SAID` or `BUSY` reached the glasses.
            Capability.ConversationDisplay,
            // 0.3 Phase 2 Task 9: the host's per-turn perception port. Declaring it is what makes
            // `services.perception()` reachable; the port is optional, so a host that provides none leaves the turn
            // without a perception block instead of failing it.
            Capability.Perception,
            // 0.3 Phase 1 Task 12 (spec §4.2.1): the host's static device profile, rendered once into the session's
            // prompt plan. Declaring it is what makes `services.deviceState()` reachable inside `activate`; a host
            // without the provider still activates (the device section is omitted), never a default profile.
            Capability.DeviceState,
        ),
        ConfigSchema(CONFIG_SCHEMA_VERSION),
    )

    /**
     * Whether this plugin currently owns the session. Read by the phone UI and the host tests; written only by
     * [activate] and [deactivate].
     */
    @Volatile
    var active: Boolean = false
        private set

    /**
     * Whether the configuration asked for consent (`requestConsent = true`).
     *
     * Parsed and retained deliberately, but **not** used to gate anything - and since `D53`① (2026-10-11) there is no
     * longer a host-side consent gate for it to mirror either: the session-level upload consent was removed, and
     * "the session is running" is what allows a request to reach the configured model service. This flag is therefore
     * **vestigial**: nothing reads it in production, and the host still sends `requestConsent = false` to the
     * diagnostic text plugin (which is why that plugin is inert on the phone). Kept, rather than dropped from
     * `KNOWN_KEYS`, only because removing a configuration key is a contract-shaped change this phase did not need.
     * Exposed `internal` so a test can assert the flag is parsed without this class growing a public accessor.
     */
    internal val requestConsentConfigured: Boolean get() = synchronized(gate) { config?.requestConsent == true }

    /**
     * Test-only observation that the persisted 「不拦截」 key was parsed.
     *
     * **Not** the live gate: the policy and confirmation wrapper consult the constructor `noInterception` provider, so
     * a configuration snapshot can never re-arm the mode after the user turned it off (spec §5.1). Exposed `internal`
     * solely so the shape test can prove the key is accepted and defaults to false.
     */
    internal val noInterceptionConfigured: Boolean
        get() = synchronized(gate) { config?.noInterception == true }

    /**
     * Test-only observation of the plugin's own audit trail.
     *
     * A wake the scene refuses has no turn to key an audit line by, so a JVM test can only prove the refusal was
     * recorded — rather than dropped without a trace — by reading the sink the plugin already owns. The underlying
     * `BoundedAuditLog.entries()` hands out a defensive copy, so this exposes no mutable state and no production
     * caller has any reason to reach for it.
     */
    internal fun auditTrail(): List<AuditEntry> = audit.entries()

    /**
     * R128: test-only observation of whether a confirmation question is still live.
     *
     * The STOP/teardown tests need to prove the question was **settled**, not merely that the host's card was
     * withdrawn: a stale `pending` slot would make the next question answer `BUSY` and still let a queued Allow
     * through. `SmartConfirmation.pendingCount` is already 0-or-1 by construction and hands out no state.
     */
    internal fun pendingConfirmationCountForTest(): Int = confirmation.pendingCount

    /**
     * Test-only observation of the **session's** effective history/compression allowance (0.3 design §5.2/§6.3).
     *
     * The production manager derives a [ContextPolicy] from the session's `ModelProfile` at activation, and the
     * compressor is handed `policy.softLimit`. A test reads this to prove the profile-driven policy reached the
     * session's compressor — not that a hard-coded number did. When no policy can be derived there is no manager, and
     * this accessor fails rather than inventing a budget: production refuses every turn the same way, with the
     * distinct `PROFILE_MISSING`/`PROFILE_INVALID`/`PROFILE_TOO_SMALL` code the gateway reports.
     */
    internal fun historyBudgetTokensForTest(): Int =
        checkNotNull(activeContextForTurn) {
            "no session context policy: the configured model profile is missing or invalid"
        }.compressionBudgetTokens()

    /**
     * Test-only: the wall-clock budget the next agent turn receives.
     *
     * Since 2026-10-22 (D197) this is the effectively unbounded [AGENT_TURN_BUDGET_NANOS], not the old 180 s product
     * cap. The behavioral guards are `AgentLoop.maxSteps`, `ProgressGuard` and the per-request profile timeout.
     */
    internal fun agentTurnBudgetNanosForTest(): Long = turnBudgetNanos()

    private data class Config(
        val profileId: String,
        val profileRevision: Long,
        val wakeWords: List<String>,
        val maxLevel: RiskLevel,
        val whitelist: List<WhitelistEntry>,
        val requestConsent: Boolean,
        /** P3.2: how long the proactive path stays quiet after a request, and how willing it is to interrupt. */
        val proactiveCooldownMillis: Long,
        val proactiveSensitivity: ProactiveSensitivity,
        /**
         * 0.3 Phase 4B (`D189`): the persisted 「不拦截」 value, parsed for the closed configuration shape.
         *
         * It is **not** the gate the policy reads: the host passes a live provider to the constructor (spec §5.1), and a
         * configuration snapshot must never override a mid-session save. The field keeps the key honest - the plugin
         * accepts the value `SmartSettings.pluginValues` sends and defaults to false when it is absent.
         */
        val noInterception: Boolean,
    )

    private val gate = Any()
    private val scene = ScenePolicy(cooldownNanos)
    /**
     * P3.3 (spec §17.5): P3.1's in-memory ring, now persisted into the plugin's own encrypted namespace.
     *
     * The storage handle is attached in [activate]; until then (and on a host that grants none) the trail is
     * memory-only, which is why the ring's behaviour is unchanged and its tests still hold.
     */
    private val audit = AuditTrail(null, BoundedAuditLog(AUDIT_CAPACITY, clock))

    /**
     * P3.4: the long-term-memory boundary for the session being activated.
     *
     * Rebuilt on every `activate` so it points at the namespace **that** session was granted, and cleared on
     * `deactivate` - a lookup must never reach a namespace the host has already taken back. `null` means "no session",
     * and the gateways then inject nothing at all.
     */
    @Volatile private var memoryRetrieval: MemoryRetrieval? = null

    /**
     * Phase 7 F4 (§3.1/§3.2): which memory form [activate] renders, **measurement only**.
     *
     * The default is [MemoryMeasurementForm.PRODUCTION], which keeps the production output byte-identical. A
     * debug-only receiver may set [MemoryMeasurementForm.LEGACY_RAW] before a session activates, and [activate] reads
     * the value once when it constructs the session's `MemoryModule`. **No production caller sets this**, and a release
     * build has no component that could; the property exists so the F4 experiment is a runtime measurement switch
     * rather than a second APK.
     *
     * `@Volatile` because the debug receiver runs on the main thread while `activate` runs on the host's plugin
     * thread: the write must be visible to the activation that follows it.
     */
    @Volatile private var memoryMeasurementForm = MemoryMeasurementForm.PRODUCTION

    /**
     * Selects the memory form the next [activate] will render. **Measurement only; production never calls it.**
     *
     * The value is read at activation time, not per turn, so a caller must set it **before** the session activates
     * (design §3.1); changing it mid-session deliberately does nothing to that session's renderer.
     */
    fun setMemoryMeasurementForm(form: MemoryMeasurementForm) {
        memoryMeasurementForm = form
    }

    /** Test-only observation of the measurement form; no production caller has any reason to reach for it. */
    internal fun memoryMeasurementFormForTest(): MemoryMeasurementForm = memoryMeasurementForm

    /**
     * edith-0.2 Phase B (B1/B3/B6): the **session's** prompt assembly and compressor.
     *
     * Built in [activate] rather than injected, because both hold session state — a conversation, and an ordering that
     * has to follow this session's writes — while the host keeps one plugin instance and re-activates it per session.
     * Injection at construction time would share one transcript across sessions, which is the replay the plugin's own
     * activation clears exist to prevent. The constructor parameters of the same names stay as test seams: a test that
     * supplies one is used verbatim.
     */
    @Volatile private var activeContextForTurn: ContextManager? = null
    @Volatile private var activeCompressor: ContextCompressor? = null

    /**
     * 0.3 Phase 1 Task 12 (spec §4.1/§4.4); 0.3 Phase 3 §5.1 adds the follow-up: the plans [activate] pinned for the
     * **three** prompt routes, or `null` outside a session. Built once per activation and read by every subsequent
     * request; [deactivate] drops them with the rest of the session state. A catalog version change therefore takes
     * effect on the **next** activation, never inside a live session.
     */
    @Volatile private var activePromptPlans: Map<PromptRoute, PromptPlan>? = null

    /**
     * 0.3 Phase 1 Task 12 (spec §4.1): the prompt-catalog seam, keyed by [PromptRoute] since 0.3 Phase 3.
     *
     * It is an internal property rather than a constructor parameter because [PromptRoute] is internal to this module
     * and a public constructor must not expose it. Production never touches it: the default is the shipped
     * `PromptCatalog`, and the seam exists so a test can observe **when** a plan is loaded (once per activation, never
     * per request) and prove that a catalog change cannot rewrite a live session. A test sets it before [activate].
     */
    internal var promptCatalog: (PromptRoute, DeviceProfile?) -> PromptPlan = { route, profile ->
        PromptCatalog.plan(route, profile)
    }

    /**
     * 0.3 Phase 1 final-fix wave (spec §4.4/§5.1): the SKILL catalog the session pinned at activation.
     *
     * The catalog is part of the cacheable system slot, so it must be stable for as long as the session lives: a
     * directory edit mid-session is reported (`P3_SKILL catalog_changed`) but never rewrites `instructions`. `""`
     * means the library was empty or unreadable at activation; [deactivate] drops it with the rest of the session.
     */
    @Volatile private var pinnedSkillCatalog: String = ""

    /** Deterministic hex digest of [pinnedSkillCatalog], or [SKILL_HASH_NONE] when nothing was pinned. */
    @Volatile private var pinnedSkillCatalogHash: String = SKILL_HASH_NONE

    /**
     * 0.3 Phase 2 Task 11 (spec §4.2.1 rule 4): the digest of the [DeviceProfile] the session pinned at activation, or
     * [DEVICE_HASH_NONE] when the host supplied none. Read per request by the `P3_PROMPT` line, so the digest describes
     * the plan the frame actually rendered; it is cleared with the rest of the session state in [deactivate].
     */
    @Volatile private var activeDeviceHash: String = DEVICE_HASH_NONE

    /** Whether this session already reported a detectable catalog change, so a change is named once, not per turn. */
    @Volatile private var skillCatalogChangeLogged: Boolean = false

    /**
     * 0.3 Phase 4A Task 4 review (I2): the session whose one count-only `P4_TOOLFACE` line has already been emitted.
     *
     * `registryFor` builds a registry every turn, so an unguarded emit repeated the line per turn. The tool face is
     * pinned for the session (spec §4.2: the 24 schemas are fixed for the session), which makes the count a
     * session-boundary fact: it is reported on the session's first registry build and again only when
     * [PluginContext.sessionId] changes. The id, not a boolean cleared in `deactivate`, is the reset - a re-activation
     * that keeps the session id is still the same face.
     */
    private var toolFaceLoggedSession: SessionId? = null

    /** Guards [toolFaceLoggedSession]; two turns can build their registries on different threads. */
    private val toolFaceLock = Any()

    /**
     * 0.3 Phase 1 final-fix wave (spec §5.2): the session's fail-closed policy code, or `null` when the policy built.
     *
     * A missing profile is still the gateway's `PROFILE_MISSING`; this keeps the distinct `PROFILE_INVALID` /
     * `PROFILE_TOO_SMALL` that a swallowed `ContextPolicy.from` used to erase, and every model turn of the session
     * refuses with it. The send-time trim window itself is derived from the live profile at each turn
     * ([turnHardLimit]) so a profile change is not silently frozen into an activation-time number.
     */
    @Volatile private var activeContextFailure: String? = null

    /**
     * Phase 5: the session's per-route stores, or `null` when the wiring supplied none. Session state like
     * [activeContextForTurn]: [activate] adopts the injected map **and empties it**, [deactivate] empties and drops it.
     */
    @Volatile private var activeStores: Map<ContextRoute, ConversationStore>? = null

    /**
     * edith-0.3 Phase 2 Task 9: the host's perception port, resolved once per activation. `null` — a host without the
     * capability — means "no perception block", never a failed activation or turn; [deactivate] drops it with the rest
     * of the session state so a closed turn can never be reopened under a later session.
     */
    @Volatile private var activePerception: PerceptionProvider? = null

    /**
     * edith-0.3 Phase 3 Task 3 (M4): the session's bounded failure-observation source, built with [activePerception]
     * and closed in [deactivate].
     *
     * One source per session (not per turn): it owns one daemon executor, and a per-turn source would either leak a
     * thread or pay a fresh thread for every failed screen call. `null` when the host granted no perception port; the
     * loop then reports its own explicit `no_source`, exactly as before this source existed.
     */
    @Volatile private var activeObservationSource: PerceptionObservationSource? = null

    /**
     * Task 9: the per-request device-state source (date/time/battery/foreground/media), same lifetime as
     * [activePerception]. Read per request, guarded: the provider can be absent or throw, and the renderer then leaves
     * the affected slots empty instead of inventing a clock or a battery value.
     */
    @Volatile private var activeDeviceStateProvider: DeviceStateProvider? = null

    /**
     * 0.3 Phase 2 Task 9 review minor: the **owner ticket** of the perception turn, so a superseded turn's `finally`
     * cannot close the newer turn's provider boundary.
     *
     * `beginTurn()`/`endTurn()` are host-side turn boundaries with no turn identity of their own: if turn A is
     * superseded while it is inside the model request and turn B opens afterwards, A's late `endTurn()` would clear the
     * snapshot and refs `B` just installed. The plugin therefore hands each opened boundary a process-wide monotonic
     * ticket and closes the provider only when that ticket is still the current owner. [perceptionTurnGeneration] is
     * never reset, so a stale ticket can never match a later session's boundary; deactivation only drops the owner.
     *
     * [perceptionTurnLock] serialises the begin/end pairs themselves, which also makes the visible order of two
     * concurrent turns' boundaries match the ticket order.
     */
    private val perceptionTurnLock = Any()
    private var perceptionTurnGeneration: Long = 0L
    private var perceptionTurnOwner: Long = 0L

    /**
     * P3.4: what a finished turn is allowed to write. Same lifetime and the same namespace as [memoryRetrieval]; `null`
     * means "no session", and a turn then writes nothing.
     */
    @Volatile private var memoryExtraction: MemoryExtraction? = null

    /**
     * edith-0.2 Phase B (B4): the user's SKILL library, or `null` when the host granted no directory.
     *
     * Built in `activate` from the session's own grant and re-read on every turn — spec §3.5 does no file watching,
     * so "restart or refresh picks up an edit" is only true if nothing caches the list.
     */
    @Volatile private var skillManager: SkillManager? = null

    /**
     * Phase 6 (design D-2/D-3): the session's SKILL lifecycle governor, built once in [activate] and dropped in
     * [deactivate]. It is session state like the transcript: a governor left over from the previous session would count
     * this session's turns against entries it never advertised.
     */
    @Volatile private var libraryGovernor: LibraryGovernor? = null

    /**
     * Test-only observation of the session's SKILL governor.
     *
     * The governor is production state the plugin owns, so a JVM test can only pin "a turn is one observation" by
     * reading it; no production caller has any reason to reach for it. Same shape as [auditTrail].
     */
    internal fun skillGovernor(): LibraryGovernor? = libraryGovernor

    /**
     * Test-only observation of the session's bounded failure-observation source.
     *
     * The source is production state the plugin owns, so a JVM test can only pin "one per session, closed on
     * deactivate" by reading it; no production caller has any reason to reach for it. Same shape as [skillGovernor].
     */
    internal fun observationSourceForTest(): ToolObservationSource? = activeObservationSource

    /**
     * The one monitor guarding every read and mutation of the SKILL turn state: the generation/owner below, the pulled
     * bodies, and **every** call into the session's [SkillManager] — whose [LibraryGovernor] documents that it is "not
     * thread-safe". Turns are concurrent (the host runs plugin work on a two-thread pool and both routes submit to it),
     * so all of it must be serialised, not just the map.
     *
     * Deliberately **not** [gate]. The plugin's gate guards events, turn ownership and configuration; `Stop`, a
     * confirmation and the next wake all queue behind it, so governor work and the catalog's logging must never run
     * there. Same discipline as `publishBusy`: the report happens outside the lock that produced the numbers.
     *
     * Never reset: a generation is process-wide monotonic, so a stopped turn's generation can never be handed to a
     * later turn and be mistaken for the live owner. Only the *owner* is cleared when a session ends.
     */
    private val skillLock = Any()

    /** The last generation handed out. Only an owner turn takes one; guarded by [skillLock]. */
    private var skillTurnGeneration: Long = 0L

    /**
     * The generation of the turn that owns the SKILL lifecycle, or `0` when no turn owns it. Guarded by [skillLock].
     *
     * A turn's end is honoured only when it carries this generation. A stopped/replaced turn keeps unwinding and still
     * reaches [endSkillTurn]; without the owner check its generation would be indistinguishable from the live turn's,
     * so it would clear the live turn's pulled bodies and close its observation (review finding).
     */
    private var skillTurnOwner: Long = 0L

    /**
     * Phase 6 (design D-2, obligation 5): the bodies pulled with `load_skill` during the **current** turn, in pull
     * order and keyed by `name` so a second pull overwrites rather than duplicates. Cleared at the turn boundary — the
     * body's lifetime is one turn, and letting it accumulate across turns would move the unbounded growth this phase
     * removes into the conversation itself.
     *
     * Guarded by [skillLock]. It is only read through [skillSnapshot], which copies it and the SKILL catalog in **one**
     * critical section: the catalog and the pulled bodies are the two halves of one request's SKILL view, and
     * [beginSkillTurn] clears the bodies under the same lock. Two separate snapshots could pair a newer catalog with
     * the previous turn's pulled set (0.3 Task 12 review fix).
     */
    private val loadedSkillBodies = LinkedHashMap<String, Skill>()

    /**
     * Phase 6 (design D-2/D-8, obligation 3): the model's only way to pull a SKILL body.
     *
     * **One instance for the plugin, never one per request.** The receipt is `loaded:<n>` from a per-instance counter,
     * and `ProgressGuard` ends a run after three identical consecutive tool receipts; constructing this inside
     * [systemSlot] (or the per-request frame builder) would restart the counter at `loaded:1` for every request, so
     * three pulls within one run would look like `NO_PROGRESS` and the run would be killed by the very tool the
     * catalog exists for. Built once, both routes' registries share the same counter.
     */
    private val loadSkillTool = LoadSkillTool(
        lookup = { name ->
            // A library that throws (or a host that granted no directory) refuses the pull; it must never throw into
            // the loop, and an absent name is exactly what the model may correct. Under [skillLock], because the pull
            // mutates the governor: the turn that is ending on another thread must not interleave with it.
            synchronized(skillLock) {
                runCatching { skillManager?.load(name) }.getOrNull()
                    ?: SkillLoad.NotFound(SkillLoadReason.NOT_IN_LIBRARY)
            }
        },
        onLoaded = { skill -> rememberLoadedSkill(skill) },
    )

    private fun rememberLoadedSkill(skill: Skill) {
        synchronized(skillLock) { loadedSkillBodies[skill.name] = skill }
    }

    /**
     * Ends the session's SKILL lifecycle state: no bodies, no owner.
     *
     * The generation counter is **not** reset — a turn from the session that just ended may still be unwinding, and a
     * reused generation would let its late end pass the owner check against the new session's owner.
     */
    private fun resetSkillTurnState() {
        synchronized(skillLock) {
            loadedSkillBodies.clear()
            skillTurnOwner = 0L
        }
    }

    /**
     * P3.3 (spec §17.4): the question-asker the loop blocks on.
     *
     * Built once per plugin, not per turn: the gate's invariant is "one live question at a time", and the host channel
     * only exists after activation, so the channel is attached in [activate] and withdrawn in [deactivate].
     */
    private val confirmation = SmartConfirmation(
        channel = null,
        gate = ConfirmationGate(clock, scheduler, SmartConfirmation.DEFAULT_TIMEOUT_MILLIS * 1_000_000L),
    )
    private var config: Config? = null
    private var context: PluginContext? = null
    private var turn: SmartTurn? = null

    /**
     * 0.3 Phase 3 Task 7 / P3-13: the **PROACTIVE** conversation turn, in its own slot.
     *
     * It is a full [SmartTurn] - the same loop, display, cancellation and answer machinery as [turn] - but it is never
     * the wake turn and never shares its store, cycle state, summary or hint identities. Written and read under
     * [gate]. Two life stages live here:
     *
     * - `AwaitingTranscript` after a displayed answer's continuation opened the next PROACTIVE listening round;
     * - `Running`/`Idle` while it executes the wearer's follow-up through the normal tool face.
     *
     * The initial FOLLOWUP_TURN opened by a hint's listening window also lands here (the window is not itself a turn).
     */
    private var followupTurn: SmartTurn? = null
    private var lastFinalTranscript: String? = null
    private var lastFinalTurnId: String? = null

    /**
     * 0.3 ASR/continuous-dialogue repair: the wearer's next sentence, spoken **while the current WAKE turn was still
     * running or before its continuation opened**.
     *
     * The device run on 2026-10-22 lost exactly this input: a usable ASR final arrived during the wake turn's tool
     * round, the final-review guard (`wakeOwnsFinal`) deliberately kept it out of `acceptTranscript` (it must not be
     * replayed by `Help` or parked as a proactive hint), and no other destination existed - so the sentence vanished
     * with no `P3_TRANSCRIPT`, no turn and no state change. The wearer experiences that as "speaking during the
     * response does nothing".
     *
     * The queue is a **single slot, latest wins**: only the most recent usable final can be the wearer's next
     * sentence, and a stale one must never be replayed after a long answer. It is consumed by the continuation the
     * displayed answer opens (see [openContinuationTurn]) and cleared by every path that ends the conversation without
     * one. Read and written under [gate].
     */
    private class QueuedUtterance(
        val route: AssistantRoute,
        val text: String,
        val asrTurnId: String,
        val capturedNanos: Long,
    )
    private var queuedUtterance: QueuedUtterance? = null

    /**
     * 0.24 S3 (spec §5.3): the official conversation's 10 s/120 s/IDLE state machine, per route.
     *
     * It owns **all** official conversation timers: a turn itself never arms a listening deadline, so there is exactly
     * one window and exactly one close per round. Since 0.3 Phase 3 Task 6 the PROACTIVE route also uses it for the
     * bounded follow-up listening window a displayed hint opens (spec §4.3). The callbacks re-enter this plugin
     * ([openContinuationTurn], [cancelConversationTurn]) and are invoked by the cycle outside its own lock; this class
     * in turn never calls the cycle while holding [gate], so the two locks cannot invert.
     */
    private val conversationCycle = ConversationCycle(
        clock = clock,
        scheduler = scheduler,
        display = { conversation() },
        continueDialogue = continueDialogue,
        openContinuation = { route, previousTurnId -> openContinuationTurn(route, previousTurnId) },
        isPanelVisible = isPanelVisible,
        cancelTurn = { route, expectedTurnId -> cancelConversationTurn(route, expectedTurnId) },
    )

    // ---------------------------------------------------------------- proactive state (P3.2)

    /**
     * The plugin's **own** arming evidence: the host produced a perception signal in this session.
     *
     * Spec §16.2 makes arming the host's decision - with 「主动辅助」 off, no `AcousticEvent` and no `ContextChanged`
     * are produced at all - so this flag is the second, defensive half: a transcript that somehow arrives with no
     * perception behind it is refused as `DISARMED` rather than turned into a request.
     */
    private var perceptionArmed = false
    private var lastAcousticLabel: AcousticLabel? = null
    private var lastContextValues: Map<String, String> = emptyMap()
    /**
     * The proactive filter. Rebuilt by [configure] from the two configured keys; until a configuration arrives it is
     * the brief's defaults, so a plugin that is somehow driven before it is configured still refuses rather than
     * guessing.
     */
    private var proactive = SmartProactive(DEFAULT_PROACTIVE_COOLDOWN_NANOS, ProactiveSensitivity.LOW)
    private var proactiveHint: ProactiveHint? = null

    /**
     * 0.3 Phase 3 Task 5: the hint turn in flight, or `null`.
     *
     * It carries the loop handle and the cancellation flag the hint run's gateway polls; [proactiveHint] keeps owning
     * the submitted-`Assistance` bookkeeping. Both are opened together in [runProactive] and share one `hintId`/
     * `turnId`, and both are cleared by every terminal path ([discard], `Stop`, deactivate).
     *
     * Read and written under [gate].
     */
    private var proactiveRequest: ProactiveHintRequest? = null

    /**
     * The hint a proactive turn submitted, and the little state its ACK needs.
     *
     * Deliberately not a [SmartTurn]: a hint turn has no listening window and no transcript to adopt, so reusing the
     * wake turn's machinery would arm waits that can never be satisfied. Its `AgentLoop` lives on the separate
     * [ProactiveHintRequest]; this class owns only what the display conversation needs - its identity, its expiry and
     * the submit/ACK race - and is the displayed row [Stop][stopAll] cancels.
     */
    private class ProactiveHint(val hintId: String, val turnId: String) {
        /**
         * When the hint stops being displayable, stamped **at submit time**.
         *
         * Anchoring it at the trigger would give a reply that lands late only the remainder of the request deadline -
         * a hint the wearer cannot read. Zero until the hint is submitted, so an ACK that somehow arrives earlier
         * cannot count.
         */
        var expiryNanos: Long = 0L
        var submitting = false
        var readable = false
        var pendingAck = false
        var displayed = false
        @Volatile var cancelled = false
    }

    /**
     * 0.3 Phase 3 Task 6 / spec §4.3: the PROACTIVE follow-up listening window opened by the hint's `Displayed` ACK.
     *
     * It is **not** a [SmartTurn]: the window is the conversation cycle's bounded (10 s) listening round on the
     * PROACTIVE route, and the follow-up turn it may start belongs to Task 7. The hint and the cycle turn id live here
     * because those are the two identities the ASR routing and the close callbacks must match: a final may only consume
     * the window whose hint is on screen, and a cycle callback may only close the window it was decided for.
     *
     * Read and written under [gate].
     */
    private class FollowupWindow(
        val hint: ProactiveHint,
        val turnId: String,
        val openedNanos: Long,
    ) {
        /**
         * Whether the window's PROACTIVE round is armed on the conversation cycle yet.
         *
         * The `Displayed` ACK installs the window under [gate], but `onWake` must run outside the lock (the cycle
         * displays through the host port). Between those two steps the host can deliver `PANEL_SHOWN`/`UI_CLOSED` on
         * another thread; only an **attached** window may be treated as "has no cycle turn" by `onPanelShown`. Written
         * under [gate].
         */
        var attached: Boolean = false
    }

    /** The PROACTIVE follow-up window, or `null` when the displayed hint has no listening round. Under [gate]. */
    private var proactiveFollowup: FollowupWindow? = null

    /**
     * True while [openFollowupWindow] is arming a window's cycle round (between the install and the post-`onWake`
     * revalidation). It serializes attaches: a window installed in that gap is picked up by the in-flight call, so the
     * cycle always ends up armed for the **newest** window and no attach can overwrite a newer round. Under [gate].
     */
    private var followupAttachInProgress = false

    /**
     * 0.3 Phase 3 Task 9d (spec §4.3): the outcome of acquiring the PROACTIVE panel before a hint is submitted.
     *
     * [ACQUIRED] covers both "the panel was already visible" and "the glasses reported its first layout", because the
     * hint turn only needs to know that the sentence will land on a panel. [PANEL_NOT_SHOWN] is the acquisition
     * failure that is recorded; [CANCELLED] is a STOP/replacement/session end and is not a panel verdict.
     */
    private enum class HintPanelAcquisition { ACQUIRED, PANEL_NOT_SHOWN, CANCELLED }

    /**
     * One hint waiting for the PROACTIVE panel before it may be submitted.
     *
     * The glasses' `PANEL_OPEN` resets the panel's conversation, and the CXR custom-command channel does not guarantee
     * delivery order, so a `SHOW` crossing an `PANEL_OPEN` is wiped. The hint turn therefore asks for the window first
     * and blocks here (bounded) until `PANEL_SHOWN` proves the reset already happened. The wait also ends when the
     * cycle's acquisition cap closes the pre-armed round, or when the hint is stopped/replaced/session-ended.
     *
     * The monitor is a private object rather than the plugin lock: [onPanelShown] may run on the host dispatcher while
     * the hint task is blocked, and neither may hold [gate] across that hand-off.
     */
    private class PendingHintPanel(val hint: ProactiveHint) {
        private val monitor = Object()
        private var shown = false
        private var abort: HintPanelAcquisition? = null

        /** The glasses reported the panel's first layout. A late abort cannot undo a panel that is already up. */
        fun signalShown() {
            synchronized(monitor) {
                if (abort == null) shown = true
                monitor.notifyAll()
            }
        }

        /** Ends the wait without a panel: [reason] is [HintPanelAcquisition.PANEL_NOT_SHOWN] or `CANCELLED`. */
        fun abort(reason: HintPanelAcquisition) {
            synchronized(monitor) {
                if (!shown && abort == null) abort = reason
                monitor.notifyAll()
            }
        }

        fun awaitAcquisition(timeoutMillis: Long): HintPanelAcquisition {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            synchronized(monitor) {
                while (!shown && abort == null) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return HintPanelAcquisition.PANEL_NOT_SHOWN
                    try {
                        monitor.wait(maxOf(1L, remaining / 1_000_000L))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return HintPanelAcquisition.CANCELLED
                    }
                }
                if (shown) return HintPanelAcquisition.ACQUIRED
                return abort ?: HintPanelAcquisition.CANCELLED
            }
        }
    }

    /** The hint waiting for the PROACTIVE panel, or `null`. Read and written under [gate]. */
    private var pendingHintPanel: PendingHintPanel? = null

    /**
     * 0.3 Phase 3 Task 6: one accepted follow-up utterance on its way to the FOLLOWUP_TURN engine.
     *
     * Task 6 deliberately stops at this hand-off: it clears the listening window, closes the cycle round and records
     * the utterance under the turn id the cycle just used. **Task 7 completes the seam**: `startFollowupTurn` then
     * builds the PROACTIVE `SmartTurn` and runs the full `AgentLoop`/display path (`runFollowupTurn`). This state is the
     * contract between the two tasks, not a second turn engine.
     *
     * Read and written under [gate].
     */
    private class FollowupTurnRequest(val turnId: String, val text: String, val startedNanos: Long)

    private var followupTurnRequest: FollowupTurnRequest? = null

    /** One usable final accepted out of the PROACTIVE window, before it is handed to the follow-up engine. */
    private class FollowupUtterance(
        val turnId: String,
        val text: String,
        /**
         * 0.3 Phase 3 Task 7: the ASR turn id the recogniser gave this revision. The follow-up turn adopts it exactly
         * as a wake turn adopts the final's id, so the hint/answer the host correlates carries the transcript's own id.
         */
        val asrTurnId: String,
    )

    /**
     * True while a proactive request is on the wire.
     *
     * The wake path's [turn] cannot answer this (a proactive turn has no `SmartTurn`) and the cooldown only starts
     * after the reply, so without this flag two triggers inside one request's window would both reach the model -
     * which is exactly what the plan's "one turn at a time" invariant forbids. Read and written under [gate].
     */
    private var proactiveInFlight = false

    /**
     * True between a wake turn opening and that turn saying something (Phase C, spec §6.6 加载动画).
     *
     * Deliberately **not** "a `SmartTurn` object exists": a finished turn stays installed until the next one replaces
     * it, so a level derived from the object would keep the glasses' ring turning after the answer had already been
     * shown. The level the wearer sees is "a wake turn is being worked on and has not answered yet", which starts in
     * `openTurn` and ends the moment `emit` submits - or when the turn is stopped or the session ends.
     *
     * Read and written under [gate].
     */
    private var wakeBusy = false

    /**
     * 0.3 Phase 3 Task 7: the PROACTIVE conversation's own busy level (spec §4.5).
     *
     * `proactiveInFlight` is the HINT_TURN's level; this is the FOLLOWUP_TURN's (a running follow-up, or the listening
     * continuation round it opened and is waiting to fill). Both are merged for the PROACTIVE level in [busyChanges] -
     * the busy level is a property of the route's conversation, not of one implementation of a turn. Deliberately a
     * flag rather than "the slot exists": a finished follow-up stays installed until the next round or the cycle's
     * continue-off close, and a level derived from the object would keep the ring turning after the answer.
     *
     * Read and written under [gate].
     */
    private var followupBusy = false

    /**
     * The busy level last reported to the host, per route (Phase C, spec §6.6 加载动画).
     *
     * The two methods on the port are a **level**, so this remembers what the host was last told and publishes only
     * the difference. Writing an `idle` by hand at each terminal branch is how one branch gets missed, and a missed
     * `idle` leaves the glasses' ring turning and the panel held awake on a device whose wearer cannot switch it off
     * from the app; deriving the level from [wakeBusy] and [proactiveInFlight] means a new terminal path has to clear
     * a flag rather than remember a call.
     *
     * Read and written under [gate].
     */
    private val reportedBusy = mutableMapOf(AssistantRoute.WAKE to false, AssistantRoute.PROACTIVE to false)

    /**
     * The one proactive trigger a wake turn made wait (spec §4.6: "WAKE 激活时 PROACTIVE 暂停执行（不丢弃，等待恢复）").
     *
     * Held under [gate] and cleared by [resetPerception], so it cannot outlive the session that produced it.
     */
    private var parkedProactive: ProactiveTrigger? = null

    // ---------------------------------------------------------------- configuration

    override fun validate(configuration: PluginConfiguration) {
        parse(configuration)
    }

    override fun configure(configuration: PluginConfiguration) {
        val parsed = parse(configuration)
        val replaced = synchronized(gate) {
            val current = config
            config = parsed
            // P3.2: the filter's thresholds are the configured ones, rebuilt with the configuration so a reconfigure
            // (a settings save plus restart) cannot leave the old cooldown behind.
            proactive = SmartProactive(parsed.proactiveCooldownMillis * 1_000_000L, parsed.proactiveSensitivity)
            if (current == parsed) null else detach()
        }
        teardown(replaced)
    }

    private fun parse(configuration: PluginConfiguration): Config {
        require(configuration.schemaVersion == CONFIG_SCHEMA_VERSION) {
            "smart-assistant schema version must be $CONFIG_SCHEMA_VERSION, was ${configuration.schemaVersion}"
        }
        val unknown = configuration.values.keys - KNOWN_KEYS
        require(unknown.isEmpty()) { "unknown smart-assistant configuration keys: $unknown" }
        val profileId = configuration.values["profileId"]
        require(!profileId.isNullOrBlank()) { "profileId is required" }
        val revision = configuration.values["profileRevision"]?.toLongOrNull()
        require(revision != null && revision >= 0) { "profileRevision must be a non-negative integer" }
        val wakeWords = splitList(configuration.values["wakeWords"] ?: DEFAULT_WAKE_WORDS)
        require(wakeWords.isNotEmpty()) { "wakeWords must contain at least one word" }
        val maxLevel = parseRiskLevel(configuration.values["maxLevel"] ?: DEFAULT_MAX_LEVEL)
        val whitelist = parseWhitelist(configuration.values["whitelist"] ?: "")
        val consent = configuration.values["requestConsent"]?.let {
            require(it == "true" || it == "false") { "requestConsent must be 'true' or 'false'" }
            it == "true"
        } ?: false
        val proactiveCooldownMillis = configuration.values["proactiveCooldownMillis"]?.let {
            val millis = it.toLongOrNull()
            require(millis != null && millis >= 0) { "proactiveCooldownMillis must be a non-negative integer, was '$it'" }
            millis
        } ?: (DEFAULT_PROACTIVE_COOLDOWN_NANOS / 1_000_000L)
        val proactiveSensitivity = configuration.values["proactiveSensitivity"]?.let {
            val level = ProactiveSensitivity.entries.firstOrNull { level -> level.name == it.trim().uppercase() }
            require(level != null) { "proactiveSensitivity must be LOW, MEDIUM or HIGH, was '$it'" }
            level
        } ?: ProactiveSensitivity.LOW
        val noInterception = configuration.values["noInterception"]?.let {
            require(it == "true" || it == "false") { "noInterception must be 'true' or 'false', was '$it'" }
            it == "true"
        } ?: false
        return Config(profileId, revision, wakeWords, maxLevel, whitelist, consent,
            proactiveCooldownMillis, proactiveSensitivity, noInterception)
    }

    private fun parseRiskLevel(raw: String): RiskLevel {
        val level = RiskLevel.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        require(level != null) { "maxLevel must be one of L0..L4, was '$raw'" }
        return level
    }

    /**
     * Parses `action|scope,...` entries.
     *
     * An entry naming an `L3`/`L4` action is kept as written rather than filtered by name: the plugin has not been
     * shown that action's descriptor, so inventing a risk level for it would be a guess. `WhitelistActionPolicy`
     * already refuses to grant `L3`/`L4` at any ceiling, which is what makes such an entry harmless. What this method
     * does refuse is a malformed entry, so a typo cannot silently become a grant of something else.
     */
    private fun parseWhitelist(raw: String): List<WhitelistEntry> = splitList(raw).map { entry ->
        val parts = entry.split('|')
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "whitelist entries must be 'action|scope', was '$entry'"
        }
        WhitelistEntry(parts[0].trim(), parts[1].trim())
    }

    private fun splitList(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- lifecycle

    /**
     * The session's pinned prompt plan for [route] (0.3 design §4.1/§4.4).
     *
     * [activate] loads every prompt route's plan exactly once. Every later request reads this map, so a catalog edit,
     * a device profile change or a version bump can never rewrite a live session: only a new activation picks them up.
     *
     * A call before activation (a direct test seam, never a production request path) falls back to the profile-less
     * catalog plan. It is a plan, not an invented device: the device section is simply absent.
     */
    private fun promptPlan(route: PromptRoute): PromptPlan =
        activePromptPlans?.get(route) ?: promptCatalog(route, null)

    /**
     * The legacy `ContextRoute` → [PromptRoute] mapping for the paths still keyed by the conversation route.
     *
     * A `ContextRoute` says which store/transcript a turn belongs to; the proactive chain's two jobs share
     * `ContextRoute.PROACTIVE` but carry different postures. The HINT_TURN is the **hint** decision, so it maps to
     * [PromptRoute.PROACTIVE_HINT]; the follow-up turn passes [PromptRoute.PROACTIVE_FOLLOWUP] explicitly when it is
     * wired (0.3 Phase 3 later task).
     */
    private fun ContextRoute.toPromptRoute(): PromptRoute = when (this) {
        ContextRoute.WAKE -> PromptRoute.WAKE
        ContextRoute.PROACTIVE -> PromptRoute.PROACTIVE_HINT
    }

    /**
     * 0.3 final-fix wave (spec §6.2): the token-unit window a turn's frame is trimmed to, `ContextPolicy.hardLimit`.
     *
     * Derived from the profile the turn will actually send under, so a profile change is not frozen into an
     * activation-time number; a profile that cannot produce a policy answers [Int.MAX_VALUE] and the gateway's own
     * fail-closed code refuses the turn before it trims anything.
     */
    private fun turnHardLimit(profile: ModelProfile?): Int =
        profile?.let { runCatching { ContextPolicy.from(it, contextTuning).hardLimit }.getOrNull() }
            ?: Int.MAX_VALUE

    /**
     * Deterministic SHA-256 of the session's static [DeviceProfile] (spec §4.2.1 rule 4), or `"none"` when the host
     * supplied no profile.
     *
     * It is the provenance half of `P3_PROMPT`: `hash` proves which prompt prose ran, `deviceHash` proves the
     * `SESSION_STATIC` section really followed the device. Only the digest is logged - never a field value. Nulls are
     * encoded distinctly from empty strings, and the capability set is sorted first, so the digest is stable for one
     * profile and changes for a different one.
     */
    private fun deviceProfileHash(profile: DeviceProfile?): String {
        if (profile == null) return DEVICE_HASH_NONE
        val facts: List<String?> = listOf(
            profile.manufacturer,
            profile.model,
            profile.deviceName,
            profile.androidRelease,
            profile.apiLevel.toString(),
            profile.screenWidthPx.toString(),
            profile.screenHeightPx.toString(),
            profile.densityDpi.toString(),
            profile.glassesModel,
            profile.glassesFirmware,
            profile.glassesWidthPx?.toString(),
            profile.glassesHeightPx?.toString(),
            profile.appVersion,
            profile.locale,
            profile.timeZone,
            profile.wakeWords.joinToString("\u001f"),
            profile.capabilities.sorted().joinToString("\u001f"),
        )
        // Length-prefixed, so concatenation cannot make two different profiles share one payload.
        val payload = facts.joinToString("\u0000") { fact ->
            if (fact == null) "~" else "${fact.length}:$fact"
        }
        return sha256Hex(payload)
    }

    /** Deterministic SHA-256 hex of [text]; the only digest shape this file logs (never the text itself). */
    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 0.3 Phase 2 Task 11 (spec §4.1/§5.3; review fix R12): the per-**assembled-request** prompt-provenance line.
     *
     * Counts and hashes only — the catalog version, the session-pinned plan's ordered-section hash, the device-profile
     * digest, the pinned SKILL catalog hash, the route, and the assembled request frame's system and dynamic-tail
     * character counts. One line is emitted for each assembled request: the wake route passes it to `AgentLoop` as
     * `AgentRequest.onRequestBuilt`, which calls it after folding the loop's own segments in and before the gateway
     * sends (a tool round is a second assembled request), and the proactive request assembly calls it directly because
     * that route has no loop. A gateway overflow re-send reuses one assembled request and reports only `P3_CACHE`; a
     * request the gateway refuses before sending still has this line, because the assembly did happen. No prompt prose,
     * device value, SKILL name or user text ever rides it.
     *
     * `dynamicChars` is the assembled frame's [RequestLayout.dynamicTail] length with the loop-owned `RUNTIME_STATE`
     * excluded: memory evidence, a pulled `SKILL_BODY`, a `RETRY` instruction and the `PERCEPTION` block are counted,
     * while the loop's own per-request state line is not (it is request scaffolding, not plugin-owned prompt context).
     * The gateway's `P3_CACHE` line describes the exact kept frame that was sent, after any trim.
     */
    private fun logPromptRequest(route: PromptRoute, frame: RequestFrame) {
        val dynamicChars = RequestLayout.dynamicTail(frame.conversationFlow)
            .filter { it.origin != SegmentOrigin.RUNTIME_STATE }
            .sumOf { it.text.length }
        P3Log.line(
            "P3_PROMPT version=${PromptCatalog.VERSION} " +
                "hash=${activePromptPlans?.get(route)?.hash ?: PROMPT_HASH_NONE} " +
                "deviceHash=$activeDeviceHash catalogHash=$pinnedSkillCatalogHash " +
                "route=${route.name} " +
                "staticChars=${frame.systemText.length} " +
                "dynamicChars=$dynamicChars",
        )
    }

    override fun activate(context: PluginContext) {
        deactivate(DeactivationReason.Switched)
        synchronized(gate) {
            this.context = context
            active = true
            // `Help` may only ever run a transcript from the session being activated: the host keeps **one** plugin
            // instance and re-activates it per session/generation, so a transcript left over from the previous session
            // would be replayed under the new session's envelope (and its stale turn id adopted as `sourceTurnId`).
            lastFinalTranscript = null
            lastFinalTurnId = null
            queuedUtterance = null
            // P3.2: the perception state is session state too. A label or an arming left over from the previous
            // session would let the new session perceive before the host has produced anything for it.
            resetPerception()
        }
        // Best effort: a host that refuses the declaration leaves `current()` unchanged, and that is not a reason to
        // fail activation.
        runCatching { context.services.wakeWord().declare(configuredWakeWords()) }
        // P3.3: same rule for the audit trail's namespace. A missing grant degrades the trail to memory-only; it must
        // not take the passive loop down, and `AuditTrail` logs a write failure rather than pretending it recorded.
        audit.attach(runCatching { context.services.storage() }.getOrNull())
        // P3.4: the same rule again for long-term memory. Without a namespace the store is empty and every lookup
        // answers nothing; either way a turn is unaffected, because the gateway degrades a failed retrieval to an empty
        // context.
        val memoryLog = MemoryLog(
            storage = runCatching { context.services.storage() }.getOrNull(),
            normalizer = { text, kind -> normalize(text, kind) },
        )
        // edith-0.2 Phase B (B5): the session's memory runs through the module in **both** directions. Writing via
        // the store alone leaves the module's vector index cold forever, so every retrieval would quietly fall back
        // to the lexical order; retrieving via the lexical retriever alone leaves the module unused, which is the
        // state B5 shipped in (a class with tests and no caller). The embedding service is a hard dependency (B9),
        // so it is present whenever a turn runs — but this stays `runCatching` for the same reason the rest of
        // activation does: a missing grant must degrade to the lexical order, not take activation down.
        val memory = MemoryModule(
            log = memoryLog,
            embeddings = runCatching { context.services.embedding() }.getOrNull(),
            lexicon = SmartMemoryRetrieval(memoryLog),
            // Phase 7 F4 (§3.1): the measurement switch is read here, once per activation. Production (the default)
            // passes the same renderer `MemoryModule` already defaults to, so its output is unchanged; only an
            // explicit debug-only LEGACY_RAW selection takes the experiment's raw path.
            renderer = if (memoryMeasurementForm == MemoryMeasurementForm.LEGACY_RAW) {
                LegacyRawMemoryEvidenceRenderer
            } else {
                ProductionMemoryEvidenceRenderer
            },
        )
        memoryRetrieval = MemoryModuleRetrieval(memory)
        memoryExtraction = MemoryExtraction(
            log = memoryLog,
            write = { kind, text, source -> memory.remember(text, kind, source) },
        )
        // B1/B3/B6: the session's transcript and compressor (see the field comments for why they are built here).
        //
        // 0.3 Task 11: the budget is the session profile's `ContextPolicy`, never a constant. The final-fix wave makes
        // the failure explicit rather than swallowed: `PROFILE_INVALID`/`PROFILE_TOO_SMALL` is kept as the session's
        // fail-closed code and handed to every turn, which refuses before the transport. A missing profile stays the
        // gateway's `PROFILE_MISSING` (the lookup itself answers nothing). No path invents a budget.
        val sessionProfile = config?.profileId
            ?.let { profileId -> runCatching { profileLookup(profileId) }.getOrNull() }
        val policyFailure = sessionProfile?.let { profile -> ContextPolicy.failureCode(profile, contextTuning) }
        val contextPolicy = if (sessionProfile != null && policyFailure == null) {
            // `failureCode` just ran the same derivation, so this cannot throw for a profile it accepted; the
            // `runCatching` keeps a future contract change from taking activation down without a fail-closed code.
            runCatching { ContextPolicy.from(sessionProfile, contextTuning) }.getOrNull()
        } else {
            null
        }
        val contextFailure = policyFailure
            ?: if (sessionProfile != null && contextPolicy == null) ContextPolicy.PROFILE_INVALID else null
        activeContextFailure = contextFailure
        if (contextFailure != null) P3Log.line("P3_CONTEXT policy=$contextFailure")
        // 0.3 Task 12 (§4.2.1): the static device profile is read once per session. The provider is the host's, resolved
        // here unless a test injected one; a host that cannot answer yields `null` and the catalog omits the device
        // section. No default model/locale/version is invented to fill the gap.
        val deviceProvider = deviceState ?: runCatching { context.services.deviceState() }.getOrNull()
        // 0.3 Phase 2 Task 9: the per-turn perception port and the per-request device-state source. Both optional: a
        // host that grants neither still activates, and the turn then simply carries no perception block.
        activeDeviceStateProvider = deviceProvider
        activePerception = runCatching { context.services.perception() }.getOrNull()
        // M4: one bounded observation source per session, sharing the provider's lifetime. `activate` begins with
        // `deactivate`, so any previous source is already closed; assigning here cannot strand a worker.
        activeObservationSource = activePerception?.let { PerceptionObservationSource(it) }
        val sessionDeviceProfile = runCatching { deviceProvider?.profile() }.getOrNull()
        // §4.1/§4.4; 0.3 Phase 3 §5.1: the session pins one plan for **every** prompt route, the not-yet-driven
        // follow-up included. All are loaded **now** and never re-planned, so a catalog change (or a different device
        // read) cannot rewrite a live session or invalidate its prefix cache mid-stream.
        val sessionPlans = PromptRoute.entries.associateWith { route ->
            promptCatalog(route, sessionDeviceProfile)
        }
        activePromptPlans = sessionPlans
        val contextManager = contextForTurn ?: contextPolicy?.let { policy ->
            ContextManager(
                memory = memoryRetrieval,
                policy = policy,
                // The manager's instructions are the same session-pinned plan every request's system slot uses, so
                // the legacy no-store path and the frame path cannot disagree about the system text. The manager is
                // still keyed by `ContextRoute`; the PROACTIVE route it assembles is the hint posture.
                promptPlan = { route -> sessionPlans.getValue(route.toPromptRoute()) },
            )
        }
        activeContextForTurn = contextManager
        // Phase 5: the injected per-route stores are session state too. A store left over from the previous session
        // would be replayed under the new session's envelope - the same leak `lastFinalTranscript` is cleared for - so
        // activation empties them; `deactivate` clears them at the other end of the handover.
        activeStores = conversationStores?.also { stores -> stores.values.forEach { it.clear() } }
        // B3 / spec §4.4's asynchronous half. It is built per activation for the same reason the transcript is: it
        // carries a request deadline and must reach **this** session's model client, and it is `null` when the host
        // granted no model service, which the compressor's own contract turns into plain truncation.
        val summarizer = runCatching { context.services.model() }.getOrNull()?.let { client ->
            ModelSummarizer(
                client = client,
                profileLookup = profileLookup,
                profileId = config?.profileId ?: return@let null,
                // 0.3 design §6.4 / Phase 4A A7: the structured six-part instruction is an authored resource.
                prompt = PromptCatalog.summarizer(),
                clock = clock,
                scheduler = scheduler,
                envelopeFor = { requestId, startNanos, endNanos ->
                    // A summary is not a turn, so it carries a distinguishable request id; the envelope's session and
                    // generation are still this session's, because it is a request made on its behalf.
                    EventEnvelope(context.sessionId, context.generation, requestId, startNanos, endNanos)
                },
                deadlineNanos = { clock.nowNanos() + SUMMARY_DEADLINE_MILLIS * 1_000_000L },
            )
        }
        activeCompressor = compressor ?: contextManager?.let { manager ->
            ContextCompressor(
                // Both allowances are the session policy's (0.3 design §6.3/§6.4): the end-of-turn compression
                // threshold and the summary output cap. No six-way token split exists any more.
                historyBudgetTokens = manager.compressionBudgetTokens(),
                summaryMaxTokens = manager.summaryMaxTokens(),
                memory = memory,
                summarizer = summarizer,
                // The plugin's task pool: `compressIfNeeded` runs inside the turn, and an inline summary would hold the
                // caller's thread for a cloud round trip (the same defect the gate had before review C2).
                scope = context.services.tasks,
            )
        }
        // B4: the SKILL library. A host that granted no directory leaves this null and the prompt is simply the
        // system instructions - "no SKILLs installed" is a normal state (spec §3.7), not a degraded one.
        val governor = LibraryGovernor()
        libraryGovernor = governor
        skillManager = runCatching { context.services.storageAccess() }.getOrNull()
            ?.let { SkillManager(StorageAccessSkillDirectory(it), governor = governor) }
        // 0.3 final-fix wave (spec §4.4/§5.1): the catalog is part of the cacheable system slot, so it is read **once**
        // here and pinned for the whole session; a later request must not rebuild it. `beginTurn()` first creates the
        // governor's entries (the catalog only lists known entries) and opens the session's first observation, which
        // the first real turn joins. A broken library contributes no catalog rather than failing activation (§3.7).
        val pinnedCatalog = synchronized(skillLock) {
            skillManager?.let { manager ->
                runCatching {
                    manager.beginTurn()
                    manager.catalog()
                }.getOrElse { "" }
            }.orEmpty()
        }
        pinnedSkillCatalog = pinnedCatalog
        pinnedSkillCatalogHash = if (pinnedCatalog.isBlank()) SKILL_HASH_NONE else sha256Hex(pinnedCatalog)
        skillCatalogChangeLogged = false
        // 0.3 Phase 2 Task 11: the device digest is pinned with the plans and read by every request's `P3_PROMPT`
        // line. The line itself is no longer emitted here; a session has not made a model request at activation, and a
        // per-request line must describe the frame that request actually rendered.
        activeDeviceHash = deviceProfileHash(sessionDeviceProfile)
        // P3.3: the same rule for the confirmation channel. A host that cannot ask the user leaves this null, and the
        // loop then refuses every flagged action (`CONFIRMATION_UNAVAILABLE`) instead of acting unasked.
        confirmation.attach(runCatching { context.services.confirmation() }.getOrNull())
    }

    override fun deactivate(reason: DeactivationReason) {
        // 0.24 S3: a session that ends takes the conversation's deadlines with it. A pinned 120 s close left armed
        // would otherwise fire on the **next** session's panel (or a stale display port), which is a device bug that
        // no later event could undo.
        conversationCycle.reset()
        // Phase C: the level is cleared and reported **before** the context goes, because the conversation port is
        // resolved through it - reporting after `context = null` would resolve to nothing and leave the glasses' ring
        // turning until its own bound expired. Reported outside the lock, as `publishBusy` requires.
        synchronized(gate) {
            wakeBusy = false
            proactiveInFlight = false
            followupBusy = false
        }
        publishBusy()
        var strandedFollowup: SmartTurn? = null
        val stranded = synchronized(gate) {
            context = null
            active = false
            // The remembered transcript is session state, not plugin state: leaving it behind is exactly the bug
            // `Help` in a later session would replay it. `activate` clears it too, so both ends of the handover do.
            lastFinalTranscript = null
            lastFinalTurnId = null
            queuedUtterance = null
            resetPerception()
            // P3.4: a session that ends takes its memory boundary with it (the namespace was scoped to it).
            memoryRetrieval = null
            memoryExtraction = null
            // The session's conversation and compressor go with it: `activate` rebuilds both, and a transcript left
            // behind would be the previous session's words under the new session's envelope.
            activeContextForTurn = null
            activeCompressor = null
            // Task 9: the perception port and its device-state source belong to the session that resolved them.
            activePerception = null
            // M4: the bounded observation worker ends with the session too; `close` is idempotent and a later
            // activation builds its own source/provider pair.
            activeObservationSource?.close()
            activeObservationSource = null
            activeDeviceStateProvider = null
            // The pinned plans belong to the session that loaded them; a later activation loads its own (spec §4.1).
            activePromptPlans = null
            // The pinned catalog, its provenance and the session's policy are session state for the same reason: a
            // later activation must build them from the library/profile it is actually given.
            pinnedSkillCatalog = ""
            pinnedSkillCatalogHash = SKILL_HASH_NONE
            activeDeviceHash = DEVICE_HASH_NONE
            skillCatalogChangeLogged = false
            activeContextFailure = null
            // Phase 5: the injected stores are the same session state, so both ends of the handover empty them.
            activeStores?.values?.forEach { it.clear() }
            activeStores = null
            skillManager = null
            libraryGovernor = null
            // The pulled bodies are session/turn state too: nothing may survive the session that obtained it. The
            // turn owner goes with them, without reusing the generation (a stale turn may still be unwinding).
            resetSkillTurnState()
            strandedFollowup = detachFollowup()
            detach()
        }
        // P3.3: a session that ends withdraws any question it asked. Leaving one pending would let the *next* session's
        // tap answer this session's action - the same class of leak the display and transcript resets exist to prevent.
        confirmation.cancelAll()
        confirmation.attach(null)
        teardown(stranded)
        // 0.3 Phase 3 Task 7: the follow-up turn is the other session's turn and must be torn down with the session.
        teardown(strandedFollowup)
    }

    /** Must hold [gate]: forgets the session's perception, so nothing survives into the next one. */
    private fun resetPerception() {
        perceptionArmed = false
        lastAcousticLabel = null
        lastContextValues = emptyMap()
        proactiveHint?.cancelled = true
        proactiveHint = null
        // 0.3 Phase 3 Task 5: a session that ends also cancels the hint turn it may be running. The flag is the
        // gateway's `stop` predicate, so an in-flight request ends CANCELLED instead of writing back into a session
        // whose state has already been dropped; a late success is suppressed by `submitProactiveHint`'s ownership
        // check and by `hintDecision`'s cancelled branch.
        proactiveRequest?.cancelled = true
        proactiveRequest = null
        proactiveInFlight = false
        // 0.3 Phase 3 Task 9d: a hint still waiting for the panel belongs to the session that is ending; it must not
        // submit into the next one. The hint task wakes and ends without a sentence.
        pendingHintPanel?.abort(HintPanelAcquisition.CANCELLED)
        pendingHintPanel = null
        // 0.3 Phase 3 Task 6: the PROACTIVE follow-up window and its hand-off are session state. A later session must
        // never inherit a listening round or a turn started from a hint the previous session showed.
        proactiveFollowup = null
        followupTurnRequest = null
        // 0.3 Phase 3 Task 7: the FOLLOWUP_TURN's busy level is session state too; the turn itself is detached by
        // `deactivate` before the context goes, so a session end always tears it down.
        followupBusy = false
        // A parked trigger describes a session that is ending; running it later would act on a stale world under a
        // session that never observed it.
        parkedProactive = null
        // Task 9 review minor: the perception boundary belongs to the session. Clearing the owner (not the generation)
        // makes an unwinding turn's late `endTurn()` a no-op, while a stale ticket can never be handed out again.
        synchronized(perceptionTurnLock) { perceptionTurnOwner = 0L }
    }

    private fun configuredWakeWords(): List<String> =
        synchronized(gate) { config?.wakeWords } ?: splitList(DEFAULT_WAKE_WORDS)

    // ---------------------------------------------------------------- conversation (Phase C)

    /**
     * The conversation port, resolved per call.
     *
     * Optional by design: a host with no display never provides it, and every call here is best-effort - a missing
     * conversation view must not stop a turn, exactly as a missing speech player must not stop a hint from being shown.
     * Resolved rather than cached for the same reason `activeGate()` is: the capability is the host's to withdraw.
     */
    private fun conversation(): ConversationDisplay? =
        runCatching { currentContext()?.services?.conversationDisplay() }.getOrNull()

    /**
     * The differences to report, recorded as reported. Must hold [gate]; returns only what changed.
     *
     * Split out from [publishBusy] so the decision is made under the lock and the **call to the host happens outside
     * it**: the port reaches the CXR wire, and this class already documents why a host call must not be made while
     * `gate` is held (a host that answers synchronously would re-enter [handle] and deadlock).
     */
    private fun busyChanges(): List<Pair<AssistantRoute, Boolean>> {
        val wanted = mapOf(
            AssistantRoute.WAKE to wakeBusy,
            // 0.3 Phase 3 Task 7 (spec §4.5): the PROACTIVE level is the HINT_TURN's in-flight flag merged with the
            // FOLLOWUP_TURN's own level. They are the same conversation's two kinds of work, not two conversations.
            AssistantRoute.PROACTIVE to (proactiveInFlight || followupBusy),
        )
        return wanted.mapNotNull { (route, busy) ->
            if (reportedBusy[route] == busy) null else {
                reportedBusy[route] = busy
                route to busy
            }
        }
    }

    /**
     * Reports the current busy level to the host's conversation view, sending only what changed.
     *
     * **Must be called without [gate] held.** The port is the host's, and the plugin's task pool is multi-threaded, so
     * deferring the report to it would not preserve the order of a `working` and the `idle` that ends it.
     */
    private fun publishBusy() {
        val changes = synchronized(gate) { busyChanges() }
        if (changes.isEmpty()) return
        val port = conversation() ?: return
        changes.forEach { (route, busy) ->
            if (busy) port.working(route) else port.idle(route)
        }
    }

    /**
     * One utterance the wearer made, for the route that consumed it, when no turn id is available.
     *
     * This is the legacy/proactive path (a proactive trigger has no ASR turn). A wake final uses [publishUserFinal]
     * below so it carries the exact `asr-${turnId}` entry id.
     */
    private fun publishUserMessage(route: ContextRoute, text: String) {
        if (text.isBlank()) return
        // Which conversation this landed in, as a count and a route name and nothing else. Owner report (2026-10-15)
        // asked the obvious question of a two-conversation screen - "which one did this go into?" - and there was no
        // line in the log that could answer it, on either end. The text never appears here (the same rule as
        // `P3_HINT show ... chars=`).
        P3Log.line("P3_CONV said route=${route.toContractRoute().name} chars=${text.length}")
        conversation()?.userMessage(route.toContractRoute(), text)
    }

    /**
     * One streaming ASR revision of the current turn (0.24 spec §5.5).
     *
     * The row is identified by `asr-${turnId}`; every revision resends the turn, so the log's whole-entry replacement
     * keeps the grouping. The caller has already decided the revision is usable and the turn is still waiting.
     */
    private fun publishUserPartial(route: ContextRoute, turnId: String, revision: Long, text: String) {
        if (text.isBlank() || turnId.isBlank()) return
        P3Log.line("P3_CONV asr route=${route.toContractRoute().name} rev=$revision chars=${text.length}")
        conversation()?.userPartial(route.toContractRoute(), turnId, revision, text)
    }

    /**
     * The final utterance of [turnId] (0.24 spec §5.5/§6).
     *
     * The entry id is `asr-${turnId}` - the same id [publishUserPartial] used - so the glasses replace one row. The
     * plugin never mints the id: the display and the wire must agree, and a second minting site is a second identity.
     */
    private fun publishUserFinal(route: ContextRoute, turnId: String, text: String) {
        if (text.isBlank() || turnId.isBlank()) return
        P3Log.line("P3_CONV said route=${route.toContractRoute().name} chars=${text.length} final=true")
        conversation()?.userFinal(route.toContractRoute(), turnId, "asr-$turnId", text)
    }

    /**
     * The final answer the assistant is telling the wearer, under the entry identity its revisions used.
     *
     * 0.24 S4 Task 16 (spec §5.5/§6): the entry id is `say-${hintId}` on both screens. The plugin passes the turn and
     * the hint it emitted, so a final with no streamed revision still replaces the same documented row; a late delta
     * is then matched against that id and dropped. The final itself reaches the glasses as the hint (`SHOW`), so this
     * is the phone's record.
     */
    private fun publishAssistantFinal(route: ContextRoute, turnId: String, hintId: String, text: String) {
        if (text.isBlank() || turnId.isBlank() || hintId.isBlank()) return
        P3Log.line("P3_CONV answer route=${route.toContractRoute().name} chars=${text.length}")
        conversation()?.assistantFinal(route.toContractRoute(), turnId, hintId, text)
    }

    /**
     * A **displayed** statement that is not this turn's answer - today the explicit-memory acknowledgement.
     *
     * 0.24 S4 Task 16 (I1): it is recorded under its own entry, never under `say-${hintId}`. The acknowledgement is
     * emitted after a `notify_user` answer (or before the closing fallback answer), so publishing it as the answer's
     * final would overwrite the wearer's answer in the phone record - deterministic on the first order, transient on
     * the second. The statement itself already reaches the glasses as the hint (`SHOW`); this is the phone's record.
     */
    private fun publishAssistantNotice(route: ContextRoute, text: String) {
        if (text.isBlank()) return
        P3Log.line("P3_CONV notice route=${route.toContractRoute().name} chars=${text.length}")
        conversation()?.assistantNotice(route.toContractRoute(), text)
    }

    /**
     * One streaming answer revision (0.24 S4 Task 16, spec §5.5).
     *
     * Runs on the model transport thread (the gateway's `onTextDelta` contract), while the final answer is assembled
     * on the plugin pool and `Stop` arrives on the host dispatcher. The turn identity is read under [gate] and the port
     * is called **outside** it, like every other display call. The callback is best-effort: any condition that is not
     * "this exact turn is running and has not emitted its answer yet" drops the revision silently.
     *
     * [owned] is captured when the run starts, not read from the current `turn`: a late transport callback from a
     * previous run must never paint text into the next turn's answer row.
     */
    private fun publishAssistantPartial(owned: SmartTurn, delta: String) {
        if (delta.isEmpty()) return
        var turnId = ""
        var hintId = ""
        var revision = 0L
        var text = ""
        synchronized(gate) {
            if (!owns(owned)) return
            // Once an answer was emitted, the `SHOW` is the authoritative text: nothing streamed after it may revise
            // the row, and the run-end answer-done decision must stay attached to that emit.
            if (owned.answerFinal || owned.answerEmitId != null) return
            val streamed = owned.appendAnswerDelta(delta) ?: return
            turnId = owned.turnId
            hintId = owned.hintId
            revision = streamed.first
            text = streamed.second
        }
        P3Log.line("P3_CONV answer route=${owned.route.name} rev=$revision chars=${text.length}")
        conversation()?.assistantPartial(owned.route, turnId, hintId, revision, text)
    }

    /**
     * One tool START/END from the agent loop (0.24 S4 Task 17, spec §5.6).
     *
     * Runs on the plugin's run thread. The turn is captured by the caller, so a late callback from a superseded run is
     * inert. A START opens the official status 7 (the tool/PAG level) **before** the row, and clears the streamed-answer
     * buffer: the text streamed before a tool call is that request's preamble, not part of the answer the next request
     * will stream. END closes the row but deliberately leaves the level at 7 - the answer emit moves the conversation to
     * status 3, and the official chain has no "back to thinking" transition between a tool and its answer.
     *
     * [NotifyUserTool] and [LoadSkillTool] are the plugin's own channels (the answer and the SKILL body), not device
     * actions; they must never appear as tool rows. A name no descriptor declared is shown raw, per spec §5.6.
     */
    private fun publishToolStatus(
        owned: SmartTurn,
        action: String,
        phase: ToolPhase,
        labels: Map<String, String>,
    ) {
        // The answer channel is not a tool row, and it must not reset the streamed answer either.
        if (action == NotifyUserTool.DESCRIPTOR.name) return
        var turnId = ""
        synchronized(gate) {
            if (!owns(owned)) return
            if (phase == ToolPhase.START) owned.resetAnswerStream()
            turnId = owned.turnId
        }
        // A SKILL pull is a real tool round (its preamble must not leak into the answer stream) but not a device action,
        // so it resets the buffer above and stops before painting a row.
        if (action == LoadSkillTool.NAME) return
        // 0.24 S4 Task 19: the host title is the live source; the explicit map is the registered fallback for an EDITH
        // action whose descriptor came without copy. A name in neither is the raw model name (spec §5.6).
        val label = labels[action]?.takeIf { it.isNotBlank() } ?: ToolLabelMap.labelFor(action)
        P3Log.line("P3_CONV tool route=${owned.route.name} turn=$turnId phase=${phase.name} action=$action chars=${label.length}")
        // The row's state must already be 7 when the row arrives: the glasses hide the tool row outside status 7.
        if (phase == ToolPhase.START) {
            conversationCycle.onStatus(owned.route, OfficialStatus.TOOL)
        }
        conversation()?.toolStatus(owned.route, turnId, phase, label)
    }

    /** The contract's name for a route; the plugin's own enum stays internal to this module. */
    private fun ContextRoute.toContractRoute(): AssistantRoute = when (this) {
        ContextRoute.WAKE -> AssistantRoute.WAKE
        ContextRoute.PROACTIVE -> AssistantRoute.PROACTIVE
    }

    /** The module's conversation key for a contract route (P3-13: one route, one conversation, one store). */
    private fun AssistantRoute.toContextRoute(): ContextRoute = when (this) {
        AssistantRoute.WAKE -> ContextRoute.WAKE
        AssistantRoute.PROACTIVE -> ContextRoute.PROACTIVE
    }

    /**
     * 0.3 Phase 3 Task 7 (P3-13): the turn slot that currently owns [route], or `null`.
     *
     * This is the one place the two conversations' turns are resolved. It exists so every display/settlement path can
     * be written once against "the turn that owns this emit" instead of twice against two fields: the wake route owns
     * [turn], the proactive route owns [followupTurn], and no code path may treat one as the other. Must hold [gate].
     */
    private fun owner(route: AssistantRoute): SmartTurn? = when (route) {
        AssistantRoute.WAKE -> turn
        AssistantRoute.PROACTIVE -> followupTurn
    }

    /**
     * Must hold [gate]: whether [owned] is still the live turn of its own route.
     *
     * A late callback from a superseded run must never paint into the newer turn's row or settle its answer, and a
     * PROACTIVE turn must never be accepted into the WAKE slot merely because it is "the current turn object".
     */
    private fun owns(owned: SmartTurn): Boolean = !owned.closed && owner(owned.route) === owned

    /**
     * The `P3_TURN`/`P3_CONV` route field, appended only for the PROACTIVE route.
     *
     * The wake lines are deliberately byte-for-byte what they were before Task 7 (the 0.3 Phase 3 global constraint
     * and the existing telemetry tests pin them); the new route is the only line that needs naming.
     */
    private fun routeField(route: AssistantRoute): String =
        if (route == AssistantRoute.WAKE) "" else " route=${route.name}"

    // ---------------------------------------------------------------- official conversation cycle (0.24 S3)

    /**
     * 0.24 S3 (spec §5.3/§5.4): the glasses' `PANEL_SHOWN`, delivered by the host on its own thread after the link
     * validated the session identity.
     *
     * Starts — or, for Task 11's idempotent re-announcement, refreshes — the official 10 s listening window. It never
     * opens a turn: the wake already did, and a delayed/duplicated `PANEL_SHOWN` from the same panel must not create a
     * second one.
     *
     * 0.24 S5 final review Important #1: a turn can exist without a cycle window. `Help` is the non-wake entry point -
     * it resets the cycle and opens its own turn immediately - so a panel that reports its first layout afterwards
     * belongs to that turn, not to an orphan wake, and the cycle's turn-less defensive close must not end it. Every
     * wake path is still announced to the cycle by `onWake`, and a refused wake has no turn at all, so this guard
     * cannot hide the orphan case it exists for.
     */
    fun onPanelShown(route: AssistantRoute) {
        // 0.3 Phase 3 Task 6: a PROACTIVE window whose cycle turn is already gone (a Help reset, a closed session) is
        // inert; a panel announcement is not an event that may revive it. A live window is simply refreshed by the
        // cycle's own duplicate-`PANEL_SHOWN` handling below.
        if (route == AssistantRoute.PROACTIVE) {
            // The identity is captured so the inert clear below cannot drop a **newer** window that opened between the
            // two locks (a timeout callback and the host dispatcher are different threads). A window that is still
            // **attaching** is not inert: its cycle round is armed immediately after the install (see
            // `openFollowupWindow`), so clearing it here would leave that round with no plugin window behind it.
            val armed = synchronized(gate) { proactiveFollowup?.takeIf { it.attached } }
            if (armed != null && !conversationCycle.hasTurn(AssistantRoute.PROACTIVE)) {
                synchronized(gate) {
                    if (proactiveFollowup === armed) {
                        proactiveFollowup = null
                        followupTurnRequest = null
                    }
                }
            }
            // 0.3 Phase 3 Task 7: the same inert rule for a continuation round. A follow-up turn waiting for the next
            // utterance whose PROACTIVE cycle turn is gone (Help reset, a closed round) can neither settle nor continue;
            // it is dropped here instead of consuming the next final.
            val waiting = synchronized(gate) {
                followupTurn?.takeIf { it.phase == SmartTurnPhase.AwaitingTranscript }
            }
            if (waiting != null && !conversationCycle.hasTurn(AssistantRoute.PROACTIVE)) {
                val stranded = synchronized(gate) {
                    if (followupTurn === waiting) {
                        followupTurn = null
                        followupBusy = false
                        waiting.also { it.closed = true }
                    } else {
                        null
                    }
                }
                teardown(stranded)
                publishBusy()
            }
        }
        val liveTurn = synchronized(gate) { turn != null }
        if (liveTurn && !conversationCycle.hasTurn(route)) return
        conversationCycle.onPanelShown(route)
        // 0.3 Phase 3 Task 9d: a hint that pre-armed this round is waiting for the first layout before it may submit.
        // The cycle call above has already armed the 10 s window for that round, so the hint lands on a listening
        // panel; a report whose pre-armed round was closed meanwhile arms nothing and must not release the hint.
        if (route == AssistantRoute.PROACTIVE && conversationCycle.hasTurn(route)) {
            synchronized(gate) { pendingHintPanel }?.signalShown()
        }
    }

    /**
     * Whether the scene would admit a wake **right now** (0.24 S5 final review Important #1).
     *
     * The composition asks this before requesting the resident panel. `openTurn` refuses a wake during the scene
     * cooldown, and such a wake opens no turn - a panel opened for it would have no turn behind it and no
     * conversation timer, which is the blank overlay that only BACK could close.
     *
     * Only the scene gate is mirrored: when a turn is already waiting for its transcript, the panel belongs to that
     * turn and its original wake is what opened it, so a repeated request is the link's idempotent no-op and must not
     * be suppressed here.
     *
     * @return false before activation (no plugin context, so no turn can open) or while the previous admitted wake
     *   is inside the cooldown.
     */
    fun canOpenWake(): Boolean = synchronized(gate) {
        context != null && scene.admits(clock.nowNanos())
    }

    /**
     * 0.4 Task 10 (`D203`, spec §5.2): whether a typed message would be accepted right now.
     *
     * The composition pre-checks this before building the event; [handleTyped] re-checks it under [gate] atomically
     * with the open, so a stale pre-check cannot install a second turn. The rule is the busy predicate the spec pins:
     * no queued utterance, and either no turn or an **Idle** one whose answer was already settled. A running, an
     * awaiting-transcript, or an Idle turn whose answer is not settled yet is busy - and the query is read-only, so it
     * never cancels, replaces or settles any of them. It is independent of the display mode: detached mode is the
     * phone's own panel and the typed path works there exactly as on the glasses.
     */
    fun canAcceptTypedMessage(): Boolean = synchronized(gate) { active && canAcceptTypedLocked() }

    /** Must hold [gate]: the typed-message busy predicate [canAcceptTypedMessage] and [handleTyped] share. */
    private fun canAcceptTypedLocked(): Boolean {
        if (context == null) return false
        // A sentence already queued for a continuation is the wearer's next turn; a typed message must not race it.
        if (queuedUtterance != null) return false
        val current = turn ?: return true
        return !current.closed && current.phase == SmartTurnPhase.Idle && current.answerNotified
    }

    /**
     * 0.24 S3 (spec §5.4): `UI_CLOSED` / the panel went hidden.
     *
     * A hidden panel cannot be listening, so the cycle releases its deadlines and the turn behind it is cancelled. No
     * `CLOSE` is sent: the panel is already gone.
     */
    fun onPanelClosed(route: AssistantRoute) {
        // 0.3 Phase 3 Task 6 (spec §4.3): UI_CLOSED ends the follow-up listening round, but the already-displayed hint
        // keeps its own TTL - the panel is gone and no CLOSE is sent again.
        if (route == AssistantRoute.PROACTIVE) closeFollowup("ui_closed")
        conversationCycle.onClose(route)
    }

    /**
     * 0.24 S3 (spec §5.5) / 0.3 Phase 3 Task 7: continual dialogue, **per route**.
     *
     * Opens the next listening turn for the conversation that just finished - without a wake, without the wake cooldown
     * and without a chime. The route is the cycle's answer-done route, never inferred from "whatever is current": a
     * PROACTIVE answer continues only the PROACTIVE conversation (P3-13) and `P3_CONV continue route=PROACTIVE` names
     * it. The callback carries the turn that just finished so a stale continuation (the turn was stopped or replaced
     * while the answer was being displayed) opens nothing.
     */
    private fun openContinuationTurn(route: AssistantRoute, previousTurnId: String) {
        var opened: SmartTurn? = null
        var queuedText: QueuedUtterance? = null
        synchronized(gate) {
            val current = context ?: return
            val now = clock.nowNanos()
            opened = when (route) {
                AssistantRoute.WAKE -> {
                    if (turn?.turnId != previousTurnId) return
                    openTurn(
                        now,
                        EventEnvelope(current.sessionId, current.generation, "continuation-${UUID.randomUUID()}", now, now),
                        bypassWakeGates = true,
                        continuation = true,
                    )
                }
                AssistantRoute.PROACTIVE -> {
                    // The finished turn is still installed in its own slot; a stale callback is inert.
                    if (followupTurn?.turnId != previousTurnId) return
                    openFollowupTurnLocked(now, current, runsImmediately = false, continuation = true)
                }
            }
            // 0.3 ASR/continuous-dialogue repair: the wearer already said the next sentence while this answer was
            // being produced. Start the continuation's turn from it instead of presenting an empty listening round the
            // wearer would have to fill again.
            val created = opened
            if (created != null) {
                val queued = takeQueuedUtteranceLocked(route, now)
                if (queued != null) {
                    created.adoptTranscriptTurnId(queued.asrTurnId)
                    created.extendDeadline(SmartTurn.saturatingAdd(now, turnBudgetNanos()))
                    created.running()
                    queuedText = queued
                }
            }
        }
        val created = opened ?: return
        // The wake line is kept byte for byte; the proactive route names itself so A-04's evidence can find it.
        if (route == AssistantRoute.WAKE) {
            P3Log.line("P3_CONV continue turn=${created.turnId} from=$previousTurnId")
        } else {
            P3Log.line("P3_CONV continue route=${route.name} turn=${created.turnId} from=$previousTurnId")
        }
        // Outside the lock: the cycle displays the new listening level through the host port.
        conversationCycle.onWake(route, created.turnId)
        val consumed = queuedText
        if (consumed != null) {
            // The queued sentence is this round's utterance: the official ASR levels and the wearer's row must name
            // its own ASR turn id, and the model runs immediately rather than waiting for a second transcript.
            conversationCycle.onAsr(route)
            conversationCycle.onStatus(route, OfficialStatus.ASR)
            conversationCycle.onStatus(route, OfficialStatus.ASR_END)
            publishUserFinal(consumed.route.toContextRoute(), consumed.asrTurnId, consumed.text)
            // Counts only, like every other conversation line.
            P3Log.line("P3_CONV queued_consumed route=${route.name} chars=${consumed.text.length}")
            publishBusy()
            when (route) {
                AssistantRoute.WAKE -> launchTurn(created, consumed.text)
                AssistantRoute.PROACTIVE -> launchFollowupTurn(created, consumed.text)
            }
        } else {
            publishBusy()
        }
    }

    /**
     * Ends the conversation's turn **only if it is still [expectedTurnId]** (the official timeout/close paths).
     *
     * The close decision is made under the cycle's lock, but this callback runs after that lock is released. A wake
     * can open a new turn in that gap; without the identity check this call would tear the new turn down (and its state
     * would already have armed the new round's window, leaving a later second `CLOSE`). A stale callback is inert.
     *
     * It clears the busy level as well: the ring must stop when the panel times out, exactly as it does when the
     * wearer's answer is shown. The WAKE branch keeps that behaviour; the PROACTIVE branch (0.3 Phase 3 Task 6) closes
     * the follow-up window only, recording the timeout reason, and never cancels the displayed hint.
     */
    private fun cancelConversationTurn(route: AssistantRoute, expectedTurnId: String) {
        if (route == AssistantRoute.PROACTIVE) {
            // 0.3 Phase 3 Task 9d: the cycle's bounded panel-acquisition wait ended without `PANEL_SHOWN` for a hint
            // that pre-armed this round. No window/turn exists yet; the hint task is waiting to submit and must be
            // released with the real reason, so it records the skip line instead of displaying on a hidden panel.
            val pending = synchronized(gate) {
                pendingHintPanel?.takeIf { it.hint.turnId == expectedTurnId }?.also { pendingHintPanel = null }
            }
            if (pending != null) {
                pending.abort(HintPanelAcquisition.PANEL_NOT_SHOWN)
                return
            }
            var cancelledTurn: SmartTurn? = null
            var closedWindow = false
            val cleared = synchronized(gate) {
                val window = proactiveFollowup
                val followup = followupTurn
                val windowMatches = window != null && window.turnId == expectedTurnId
                val turnMatches = followup != null && followup.turnId == expectedTurnId
                if (!windowMatches && !turnMatches) {
                    false
                } else {
                    if (windowMatches) {
                        proactiveFollowup = null
                        followupTurnRequest = null
                        closedWindow = true
                    }
                    if (turnMatches) {
                        cancelledTurn = detachFollowup()
                        followupBusy = false
                        clearQueuedUtteranceLocked(AssistantRoute.PROACTIVE)
                    }
                    true
                }
            }
            if (cleared) {
                // A pinned round idles at 10 s and closes at 120 s (spec §5.5); the callback is the same one, so the
                // pin decides the honest reason for a **listening window**. A continuation round's close is already
                // named by the cycle's own `P3_CONV close route=PROACTIVE reason=...` line.
                if (closedWindow) {
                    val reason = if (conversationCycle.isPinned(AssistantRoute.PROACTIVE)) "pinned_timeout" else "timeout"
                    P3Log.line("P3_PROACTIVE followup close reason=$reason")
                }
                teardown(cancelledTurn)
                publishBusy()
            }
            return
        }
        if (route != AssistantRoute.WAKE) return
        var stopped: SmartTurn? = null
        synchronized(gate) {
            if (turn?.turnId != expectedTurnId) return
            wakeBusy = false
            clearQueuedUtteranceLocked(AssistantRoute.WAKE)
            stopped = detach()
        }
        teardown(stopped)
        publishBusy()
    }

    /**
     * Whether [owned]'s displayed answer is final **and** the wearer has seen it.
     *
     * Must hold [gate]. The [SmartTurn.answerFinal] flag is what keeps a mid-run notification's acknowledgement from
     * opening a next round while the loop still runs: the run end marks the turn's last emit as the answer (Task 14 I1),
     * so a `notify_user` answer emitted while `Running` settles exactly like the closing fallback emit. Returns whether
     * the displayed answer carried speech (the cycle then waits for `SpeechFinished`), or `null` when there is nothing
     * to settle. Route-aware since 0.3 Phase 3 Task 7: the caller names the slot's own turn, never "the current one".
     */
    private fun maybeAnswerDone(owned: SmartTurn): Boolean? {
        if (!owns(owned)) return null
        if (owned.closed || !owned.answerFinal) return null
        if (owned.answerEmitId == null || !owned.displayed || owned.answerNotified) return null
        owned.answerNotified = true
        return owned.answerEmitSpeech
    }

    /**
     * The displayed answer's continuation/settlement, reported to the cycle outside [gate].
     *
     * The speech flag and the answer's own display window are read once under the lock so the answer cannot change
     * between the decision and the report; the window lets the cycle's speech liveness bound wait until the answer
     * really leaves the panel before it gives up on a missing `SpeechFinished`. The route is the turn's own (P3-13):
     * a PROACTIVE answer settles the PROACTIVE round only.
     */
    private fun settleDisplayedAnswer(owned: SmartTurn) {
        val (speech, visibleUntil) = synchronized(gate) {
            val displayedSpeech = maybeAnswerDone(owned) ?: return
            displayedSpeech to owned.hintExpiry
        }
        conversationCycle.onAnswerDone(
            owned.route,
            displayed = true,
            speech = speech,
            answerVisibleUntilNanos = visibleUntil,
        )
    }

    /**
     * 0.24 S3 Task 14 (spec §5.5 line 324): an answer nothing displayable reached the wearer.
     *
     * Used for a suppressed/silent answer, a rejected submit and a terminal `Expired`/`Rejected` for the final hint.
     * Must be called once per turn: [SmartTurn.answerNotified] is set under [gate] before the cycle is told, so a late
     * second event cannot close a panel a continuation already opened. 0.3 Phase 3 Task 7: the route is the turn's.
     */
    private fun settleNoDisplayAnswer(owned: SmartTurn) {
        val turnId = synchronized(gate) {
            if (!owns(owned) || owned.answerNotified) return
            owned.answerNotified = true
            owned.turnId
        }
        P3Log.line("P3_CONV no_display${routeField(owned.route)} turn=$turnId")
        conversationCycle.onAnswerDone(owned.route, displayed = false, speech = false)
    }

    /**
     * 0.24 S3 Task 14 (the Task 12 carry I1): settle [owned]'s answer once the agent run has really ended.
     *
     * The production answer is `notify_user`, emitted **while the run is `Running`**, so the answer-done decision
     * cannot be keyed on the turn's phase. At the run end the last recorded emit becomes the final answer:
     * - displayed → the normal continuation/speech decision;
     * - rejected, or a terminal `Expired`/`Rejected` already arrived → close with `no_display`;
     * - a handler that suppressed every notification (silent) → close with `no_display`;
     * - accepted but not yet acknowledged → its own `Displayed`/`Expired` event settles it.
     *
     * The closing fallback emit (`submitOutcome` → assembler) is asynchronous, so when no notification was sent the
     * turn is left to that emit: it is the one emitted after the run reached `Idle`, and [emit] marks it final.
     *
     * @param fallbackExpected `submitOutcome` scheduled the closing answer through the assembler, which emits about
     *   150 ms later. Its absence at this instant is not "nothing was shown"; the later emit decides.
     */
    private fun settleAnswerAfterRun(owned: SmartTurn, fallbackExpected: Boolean) {
        var displayedSpeech: Boolean? = null
        var noDisplay = false
        synchronized(gate) {
            if (!owns(owned) || owned.answerNotified) return
            if (owned.answerFinal) {
                // A fallback emit that really did run synchronously is already the final answer.
                when {
                    owned.displayed -> displayedSpeech = owned.answerEmitSpeech
                    !owned.answerEmitAccepted || owned.answerTerminal != null -> noDisplay = true
                }
                return@synchronized
            }
            val emitted = owned.answerEmitId != null
            owned.answerFinal = emitted
            when {
                !emitted && fallbackExpected -> {
                    // Review I2: the assembler can legitimately emit nothing (a one-character closing answer), so
                    // "a fallback is on its way" must be bounded. Without this deadline no timer exists at all.
                    armFallbackSettlementLocked(owned)
                    return@synchronized
                }
                !emitted -> noDisplay = true
                owned.displayed -> displayedSpeech = owned.answerEmitSpeech
                !owned.answerEmitAccepted || owned.answerTerminal != null -> noDisplay = true
            }
        }
        if (displayedSpeech != null) {
            settleDisplayedAnswer(owned)
        } else if (noDisplay) {
            settleNoDisplayAnswer(owned)
        }
    }

    /**
     * 0.24 S3 Task 14 fix round (I2): the liveness bound for a fallback emit that never comes.
     *
     * The assembler's merge is 150 ms and its only remaining work is `readable()`; one second is a wide margin while
     * still removing the "pre-answer status forever" state. The callback re-checks that [owned] still owns the turn
     * and that nothing was emitted in the meantime, so a real emit (or a continuation) makes it inert.
     */
    private fun armFallbackSettlementLocked(owned: SmartTurn) {
        owned.cancelFallbackWait()
        val deadline = SmartTurn.saturatingAdd(clock.nowNanos(), FALLBACK_EMIT_WAIT_MILLIS * 1_000_000L)
        owned.fallbackWait = scheduler.at(deadline) {
            val close = synchronized(gate) {
                owns(owned) && !owned.answerFinal && owned.answerEmitId == null
            }
            if (close) {
                P3Log.line("P3_CONV no_display${routeField(owned.route)} reason=fallback_missing")
                settleNoDisplayAnswer(owned)
            }
        }
    }

    /**
     * 0.24 S3 Task 14 fix (C1): whether a host event names the answer's **current emit**.
     *
     * After the fix every emit carries its own `Assistance.emitId`, and the host copies it into
     * `OutputStateEvent`/`SpeechFinished`; a superseded emit's terminal then fails this check and is ignored. A null
     * emit id is a producer that predates the field (an older host, a proactive hint, a test); for those the legacy
     * turn-hint comparison is kept, which is also what every pre-fix event looked like.
     */
    private fun namesCurrentAnswerEmit(owned: SmartTurn, hintId: String, emitId: String?): Boolean =
        if (emitId != null) emitId == owned.answerEmitId else hintId == owned.hintId

    /**
     * Must hold [gate]: remembers a terminal display status for the answer hint.
     *
     * `Expired`/`Rejected` for the final answer mean the wearer never saw it, so the answer-done decision is
     * `displayed = false` (spec §5.5 line 324). The status is remembered until [settleTerminalAnswer] runs outside the
     * lock, and it survives an event that arrives **before** the run end (the run-end settlement consumes it). A
     * `Displayed` acknowledgement clears it: a later terminal for a superseded emit must not undo a real display.
     */
    private fun noteAnswerTerminal(owned: SmartTurn?, event: OutputStateEvent) {
        if (owned == null || !owns(owned)) return
        if (!namesCurrentAnswerEmit(owned, event.hintId, event.emitId)) return
        // 0.24 S4 Task 16 (I1): a non-answer statement emitted after the answer replaces it by hint id, and the arbiter
        // reports that removal as `Rejected` naming the **answer's** emit. It means "superseded", not "the wearer never
        // saw the answer": recording it would settle `no_display` while the answer's own `Displayed` ACK is still in
        // flight. `Expired` is never produced by the replacement path, so only `Rejected` is filtered here.
        if (owned.answerSupersededByNotice && event.status == OutputStatus.Rejected) return
        when (event.status) {
            OutputStatus.Expired, OutputStatus.Rejected -> if (!owned.displayed) owned.answerTerminal = event.status
            else -> Unit
        }
    }

    /** Reports the remembered terminal answer as `no_display`, once, outside [gate]. */
    private fun settleTerminalAnswer(owned: SmartTurn) {
        val close = synchronized(gate) {
            owns(owned) && !owned.answerNotified && owned.answerFinal && !owned.displayed &&
                owned.answerEmitId != null && owned.answerTerminal != null
        }
        if (close) settleNoDisplayAnswer(owned)
    }


    // ---------------------------------------------------------------- events

    override fun handle(event: HostEvent) {
        when (event) {
            is WakeDetected -> {
                var opened: SmartTurn? = null
                var preempted = false
                var preemptedFollowup: SmartTurn? = null
                synchronized(gate) {
                    if (matches(event.envelope)) {
                        // 0.3 Phase 3 Task 6 / P3-4: a wake outranks the PROACTIVE follow-up. It cancels the pending
                        // hand-off, the listening window **and** a running/waiting FOLLOWUP_TURN (0.3 Phase 3 Task 7),
                        // but never the hint already on the panel - that keeps its own TTL, so the wearer does not lose
                        // the sentence they were reading.
                        preempted =
                            proactiveFollowup != null || followupTurnRequest != null || followupTurn != null
                        proactiveFollowup = null
                        followupTurnRequest = null
                        preemptedFollowup = detachFollowup()
                        opened = openTurn(clock.nowNanos(), event.envelope, refusedWakeWord = event.word)
                        // The plugin's half of P3_WAKE: the phone-side builder logs the word/confidence on
                        // emission, and this names the turn the wake opened. A refused wake opens nothing and logs
                        // no turn line.
                        opened?.let { P3Log.line("P3_WAKE turn=${it.turnId}") }
                    }
                }
                if (preempted) {
                    P3Log.line("P3_PROACTIVE preempt reason=WAKE_PREEMPTED")
                    // Cancels the cancelled turn's loop/assembler (outside the plugin lock) and releases the PROACTIVE
                    // listening timer. `onClose` does not send a display close, so the wake turn is the only round that
                    // remains on the panel.
                    teardown(preemptedFollowup)
                    conversationCycle.onClose(AssistantRoute.PROACTIVE)
                }
                // 0.24 S3: outside the lock, because the cycle displays the official level through the host port.
                opened?.let { conversationCycle.onWake(AssistantRoute.WAKE, it.turnId) }
                // Phase C: outside the lock, because the report reaches the host's conversation port.
                publishBusy()
            }

            // P3.2: the host's perception signals. They are remembered rather than acted on here - the utterance is
            // what carries a request, and the label/context are what that request is judged and built with.
            is AcousticEvent -> synchronized(gate) {
                if (matches(event.envelope)) {
                    lastAcousticLabel = event.label
                    perceptionArmed = true
                }
            }

            is ContextChanged -> {
                // Spec §4.7's invariant, made real: a `ContextChanged` is the only event that can move the fields
                // the gate reads besides the utterance, so without this call site the state would change with no
                // evaluation - the miss the coverage table exists to prevent.
                val fromContext = synchronized(gate) {
                    if (!matches(event.envelope)) {
                        null
                    } else {
                        lastContextValues = event.values
                        if (activeGate() == null) null else proactiveTriggerFromContext()
                    }
                }
                if (fromContext != null) runProactive(fromContext)
            }

            is TranscriptEvent -> {
                // W4 (P3.5 §M): one line per revision, carrying **counts and flags only**. Without it the
                // "why did this row reach no decision" question is unanswerable - the offline diagnosis of P3.4's
                // evidence returned UNKNOWN for all 18 no-decision rows precisely because nothing recorded whether a
                // final ever arrived. `usable` is [isUsableUtterance], so "the recogniser never finished" and "a final
                // arrived but was not a usable utterance" become distinguishable after the fact. Never the text.
                P3Log.line(
                    "P3_ASR revision=${event.revision.revision} final=${event.revision.final} " +
                        "chars=${event.revision.text.trim().length} usable=${isUsableUtterance(event.revision.text.trim())}",
                )
                var matched = false
                var usableText = false
                var consumedFinal = false
                var consumedByConfirmation = false
                var adoptedTurnId: String? = null
                // 0.24 S4: one usable non-final revision of the current conversation turn, held for publication
                // outside the lock. The turn check is here, where the slot is authoritative: a stray transcript after
                // the final (or a proactive trigger) must not open a user row.
                var livePartial: Triple<String, Long, String>? = null
                var liveFollowupPartial: Triple<String, Long, String>? = null
                // 0.3 Phase 3 Task 6 / P3-13: the PROACTIVE window is a different session, so the cycle is asked
                // outside `gate` (this class never calls the cycle while holding the lock). "Armed" means both the
                // plugin's window and the cycle's PROACTIVE turn exist; a pinned idle or a Help reset leaves the window
                // inert, and the lock body below drops it instead of consuming anything. The window's identity is
                // captured so a timeout callback that clears it between the two locks cannot make this revision consume
                // a newer window.
                val windowTurnId = synchronized(gate) {
                    if (matches(event.envelope)) proactiveFollowup?.turnId else null
                }
                val windowArmed = windowTurnId != null && conversationCycle.hasTurn(AssistantRoute.PROACTIVE)
                // 0.3 Phase 3 Task 7: the same inert-identity rule for a continuation round - a PROACTIVE turn waiting
                // for the next utterance. Its cycle round was armed by the previous answer's continuation.
                val waitingTurnId = synchronized(gate) {
                    if (matches(event.envelope)) {
                        followupTurn?.takeIf { it.phase == SmartTurnPhase.AwaitingTranscript }?.turnId
                    } else {
                        null
                    }
                }
                val waitingArmed = waitingTurnId != null && conversationCycle.hasTurn(AssistantRoute.PROACTIVE)
                var followup: FollowupUtterance? = null
                var followupContinuation: Pair<SmartTurn, String>? = null
                var queuedWakeFinal = false
                var queuedProactiveFinal = false
                val (started, proactive) = synchronized(gate) {
                    if (!matches(event.envelope)) null to null
                    else {
                        matched = true
                        // 0.24 S3: the official `AsrMessageHandler` removes the timeout before it checks what the
                        // payload says, so a blank or wake-word-only revision cancels the 10 s window too.
                        usableText = isUsableUtterance(event.revision.text.trim())
                        // P3.3: a final that answers a live question is consumed here and nowhere else - it must not
                        // open a wake turn or be reconsidered by the proactive path.
                        consumedByConfirmation = confirmation.onTranscript(event.revision)
                        if (consumedByConfirmation) null to null
                        else {
                            // P3-13: a WAKE turn waiting for or running its own utterance consumes the final first;
                            // only then may the PROACTIVE window take it. A blank/echo final is not a usable
                            // utterance, so it never touches the window (Q4=A).
                            val acceptedFollowup =
                                if (windowArmed) acceptFollowupTranscript(event.revision, windowTurnId) else null
                            if (acceptedFollowup != null) {
                                followup = acceptedFollowup
                                null to null
                            } else {
                                // An armed window whose cycle turn is gone is inert (a pinned idle closed the round,
                                // Help reset the cycle, the session is ending). Drop exactly that window here; no
                                // follow-up may start.
                                if (!windowArmed && windowTurnId != null && proactiveFollowup?.turnId == windowTurnId) {
                                    proactiveFollowup = null
                                    followupTurnRequest = null
                                }
                                // A continuation round whose PROACTIVE cycle turn is gone is inert for the same
                                // reason: it cannot settle or continue, so it must not consume this final. It never
                                // emitted anything yet, so dropping the slot is the whole teardown.
                                if (!waitingArmed && waitingTurnId != null && followupTurn?.turnId == waitingTurnId) {
                                    followupTurn?.closed = true
                                    followupTurn = null
                                    followupBusy = false
                                }
                                // 0.24 S4: only a turn still waiting for its utterance has a streaming user row.
                                // Once the final is accepted the turn runs, and a late revision must not reopen the
                                // line. The two conversations' waiting turns are checked in their own slots.
                                if (!event.revision.final && usableText &&
                                    turn?.phase == SmartTurnPhase.AwaitingTranscript
                                ) {
                                    livePartial = Triple(
                                        event.revision.turnId,
                                        event.revision.revision,
                                        event.revision.text.trim(),
                                    )
                                }
                                if (!event.revision.final && usableText && waitingArmed &&
                                    followupTurn?.phase == SmartTurnPhase.AwaitingTranscript
                                ) {
                                    liveFollowupPartial = Triple(
                                        event.revision.turnId,
                                        event.revision.revision,
                                        event.revision.text.trim(),
                                    )
                                }
                                // P3-13 (final review Important 1): a usable final that arrives while the WAKE turn is
                                // **Running** belongs to WAKE and is not a proactive utterance. It must not reach
                                // `acceptTranscript`, which remembers a final as Help's next wake utterance *before* its
                                // phase check, and must not reach the proactive trigger either: admission would park it
                                // while WAKE is busy, and the wake turn's own end would then run it as a HINT_TURN. The
                                // `AwaitingTranscript` case is different and stays with `acceptTranscript` - that final
                                // is the wake turn's own utterance.
                                val wakeRunning = turn?.phase == SmartTurnPhase.Running
                                val wakeOwnsFinal = wakeRunning && event.revision.final && usableText
                                // The same sentence is lost in the short gap between the run ending (Idle) and the
                                // displayed answer opening its continuation (the device run measured ~3 s on a slow
                                // ACK). Such a final is the wearer's next sentence, not a hint trigger, provided the
                                // answer really became the final emitted one and its own speech is not still playing.
                                // An answer still being spoken keeps the old proactive route: the microphone is
                                // hearing the assistant, and the existing test suite pins that behaviour.
                                val wakeEnding = turn != null &&
                                    turn?.phase == SmartTurnPhase.Idle &&
                                    turn?.closed == false &&
                                    turn?.answerEmitId != null &&
                                    !(turn?.answerEmitSpeech == true && turn?.answerSpeechFinished == false) &&
                                    continueDialogue() &&
                                    event.revision.final && usableText
                                // A usable final arriving while this turn runs is the wearer's next sentence: queue it
                                // for the continuation instead of letting `wakeOwnsFinal` discard it.
                                val wakeQueuesFinal = wakeOwnsFinal || wakeEnding
                                if (wakeQueuesFinal) {
                                    queuedWakeFinal = true
                                    queueContinuationUtterance(AssistantRoute.WAKE, turn, event.revision)
                                }
                                val continuation =
                                    acceptFollowupContinuation(event.revision, waitingTurnId, waitingArmed)
                                if (continuation != null) followupContinuation = continuation
                                // The PROACTIVE conversation has the same loss: a usable final spoken while a
                                // follow-up answer is running would otherwise be considered only as a hint trigger and
                                // never as the next round's utterance. Queue it for the PROACTIVE continuation, and
                                // keep it away from the proactive trigger while it is queued (P3-13).
                                val proactiveRunning = followupTurn?.phase == SmartTurnPhase.Running
                                val proactiveQueuesFinal = continuation == null && proactiveRunning &&
                                    event.revision.final && usableText
                                if (proactiveQueuesFinal) {
                                    queuedProactiveFinal = true
                                    queueContinuationUtterance(AssistantRoute.PROACTIVE, followupTurn, event.revision)
                                }
                                val consumed =
                                    if (continuation == null && !wakeOwnsFinal) acceptTranscript(event.revision) else null
                                consumedFinal = consumed != null
                                // The ASR final's own id replaces the wake's placeholder; the official levels below
                                // must carry that id, not the placeholder.
                                if (consumed != null) {
                                    adoptedTurnId = turn?.turnId
                                    confirmation.excludeSegment(event.revision.turnId)
                                }
                                consumed to (
                                    if (consumed == null && continuation == null && !wakeQueuesFinal &&
                                        !proactiveQueuesFinal
                                    ) {
                                        proactiveTrigger(event.revision)
                                    } else {
                                        null
                                    }
                                    )
                            }
                        }
                    }
                }
                // The accepted follow-up is not a WAKE revision: it must not drive the WAKE route's levels or its
                // listening window (P3-13). The window is closed first, then the PROACTIVE turn is started.
                val acceptedFollowup = followup
                val continuation = followupContinuation
                if (matched && acceptedFollowup == null && continuation == null) {
                    // Outside the lock: the cycle displays the official levels through the host port.
                    conversationCycle.onAsr(AssistantRoute.WAKE)
                    adoptedTurnId?.let { conversationCycle.onTurnId(AssistantRoute.WAKE, it) }
                    // A blank/wake-word-only ASR deliberately publishes no status-2 row (spec §10 item 19); it still
                    // cancelled the window above, which is the official half of the behavior. A transcript consumed
                    // by a confirmation is not the conversation's user row either. A final queued for the continuation
                    // is also not this round's row: publishing it here would regress the panel from ANSWERING/status 5
                    // back to ASR, and the continuation publishes it under its own turn a moment later.
                    if (usableText && !consumedByConfirmation && !queuedWakeFinal && !queuedProactiveFinal) {
                        conversationCycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ASR)
                    }
                    if (consumedFinal) conversationCycle.onStatus(AssistantRoute.WAKE, OfficialStatus.ASR_END)
                    // The streaming revision is published after the status levels, outside the lock: the port is the
                    // host's and must never be called while `gate` is held. `livePartial` is only set for a usable
                    // non-final revision of a turn that is still waiting, so blank/echo revisions have no row.
                    livePartial?.let { (turnId, revision, text) ->
                        publishUserPartial(ContextRoute.WAKE, turnId, revision, text)
                    }
                }
                if (acceptedFollowup != null) {
                    // Spec §4.3: a usable final is what closes the PROACTIVE listening window. The final is a real ASR
                    // message, so the route takes the official ASR path (`onAsr` releases the 10 s timer but keeps the
                    // round's turn) instead of `onClose`, which would reset the route's turn id and leave the
                    // follow-up's answer unable to settle or continue (the Task 6 carry). The Task 6 window clear
                    // already happened under the lock.
                    startFollowupTurn(acceptedFollowup)
                }
                if (continuation != null) {
                    val (owned, text) = continuation
                    // Same inert rule as the initial turn: a round released in the accept-to-run gap cannot settle or
                    // continue this answer, so it must not run at all.
                    if (conversationCycle.hasTurn(AssistantRoute.PROACTIVE)) {
                        conversationCycle.onAsr(AssistantRoute.PROACTIVE)
                        conversationCycle.onTurnId(AssistantRoute.PROACTIVE, owned.turnId)
                        conversationCycle.onStatus(AssistantRoute.PROACTIVE, OfficialStatus.ASR)
                        conversationCycle.onStatus(AssistantRoute.PROACTIVE, OfficialStatus.ASR_END)
                        publishUserFinal(ContextRoute.PROACTIVE, event.revision.turnId, text)
                        publishBusy()
                        launchFollowupTurn(owned, text)
                    } else {
                        val stranded = synchronized(gate) {
                            if (followupTurn === owned) detachFollowup() else null
                        }
                        teardown(stranded)
                        publishBusy()
                    }
                }
                liveFollowupPartial?.let { (turnId, revision, text) ->
                    publishUserPartial(ContextRoute.PROACTIVE, turnId, revision, text)
                }
                if (started != null) {
                    // Phase C: the utterance this turn consumed is the wearer's own side of the conversation. The
                    // 0.24 final carries the ASR turn and the exact `asr-${turnId}` entry id, so the glasses replace
                    // the row the partial opened instead of appending a second one. Published here rather than inside
                    // the lock: the port is the host's, and a host that answered synchronously must not be able to
                    // re-enter this plugin while `gate` is held.
                    publishUserFinal(ContextRoute.WAKE, event.revision.turnId, started.second)
                    launchTurn(started.first, started.second)
                } else if (proactive != null) runProactive(proactive)
            }

            // P3.3 (spec §17.4): the host's answer to a question this plugin asked. It settles the gate; the loop,
            // which is blocked on that gate on the turn's own thread, then either executes or refuses.
            is ConfirmationResult -> confirmation.onConfirmationResult(event)

            // 0.24 S3 (spec §5.5): the wearer pinned the panel. One-way; the cycle owns the pinned-timeout outcome.
            is PinChanged -> {
                val mine = synchronized(gate) { matches(event.envelope) }
                if (mine) conversationCycle.onPin(event.route)
            }

            // 0.24 S3 (spec §5.5): the phone's playback of the displayed answer finished. The route is decided by the
            // hint the event names, never by "whatever is current": a stale hint must not continue a newer turn.
            //
            // 0.24 S3 fix (C1): the hint id alone cannot tell a superseded emit's playback end from the current
            // answer's (one turn keeps one hint id). The emit id is the identity; a mismatch is a stale result and is
            // dropped instead of being stored as `earlySpeech`.
            is SpeechFinished -> {
                var wakeMine = false
                var proactiveMine = false
                synchronized(gate) {
                    if (matches(event.envelope)) {
                        // 0.3 Phase 3 Task 7: match the result against **both** routes' slots by identity. A WAKE answer
                        // and a follow-up answer are different turns with different emit ids; a result for one must never
                        // settle the other (P3-13).
                        wakeMine = turn?.let { owned ->
                            !owned.closed && namesCurrentAnswerEmit(owned, event.hintId, event.emitId)
                        } == true
                        proactiveMine = followupTurn?.let { owned ->
                            !owned.closed && namesCurrentAnswerEmit(owned, event.hintId, event.emitId)
                        } == true
                        // 0.3 ASR/continuous-dialogue repair: the answer's own playback is over, so a final that
                        // reaches the running turn from now on is the wearer's, not the speaker's echo.
                        if (wakeMine) turn?.answerSpeechFinished = true
                        if (proactiveMine) followupTurn?.answerSpeechFinished = true
                    }
                }
                if (wakeMine) conversationCycle.onSpeechFinished(AssistantRoute.WAKE, event.result)
                if (proactiveMine) conversationCycle.onSpeechFinished(AssistantRoute.PROACTIVE, event.result)
            }

            is OutputStateEvent -> {
                var mine = false
                var followupWindow: FollowupWindow? = null
                var wakeTurn: SmartTurn? = null
                var proactiveTurn: SmartTurn? = null
                synchronized(gate) {
                    if (matches(event.envelope)) {
                        mine = true
                        // 0.3 Phase 3 Task 7: each slot accepts only the acknowledgement that names its own emit; a
                        // PROACTIVE hint's ACK opens the window and never touches a turn, and a follow-up answer's ACK
                        // never settles the WAKE conversation (P3-13).
                        acceptDisplayed(turn, event)
                        acceptDisplayed(followupTurn, event)
                        followupWindow = acceptProactiveDisplayed(event)
                        noteAnswerTerminal(turn, event)
                        noteAnswerTerminal(followupTurn, event)
                        wakeTurn = turn
                        proactiveTurn = followupTurn
                    }
                }
                // 0.3 Phase 3 Task 6: the `Displayed` ACK of a proactive hint opens the PROACTIVE listening round.
                // Opened outside the lock, because the cycle displays the official level through the host port.
                val openedFollowup = followupWindow
                // 0.24 S3: the acknowledgement is also the answer-done signal. Reported outside the lock so the
                // continuation opener can re-enter the plugin (spec §5.5). A terminal `Expired`/`Rejected` for the
                // final answer settles the same way with `displayed = false` (Task 14 / Task 12 carry I1). The two
                // slots are settled independently: only the turn whose answer this acknowledgement names has anything
                // to settle, and each settles on its own route.
                if (mine) {
                    openedFollowup?.let { openFollowupWindow(it) }
                    wakeTurn?.let {
                        settleDisplayedAnswer(it)
                        settleTerminalAnswer(it)
                    }
                    proactiveTurn?.let {
                        settleDisplayedAnswer(it)
                        settleTerminalAnswer(it)
                    }
                }
            }

            is UserAction -> when (event.action) {
                UserAction.Action.Stop -> stopAll(event.envelope)
                UserAction.Action.Help -> handleHelp(event.envelope)
                // 0.4 Task 10 (spec §5.2): the phone's typed input enters the WAKE route. A Typed action with no body
                // is refused by `handleTyped`, so the event shape cannot smuggle a blank turn into the loop.
                UserAction.Action.Typed -> event.text?.let { handleTyped(event.envelope, it) }
                UserAction.Action.Pause, UserAction.Action.Resume -> Unit
            }

            else -> Unit
        }
    }

    /** True when the event belongs to the session/generation this plugin was activated for. */
    private fun matches(envelope: EventEnvelope): Boolean {
        val current = context ?: return false
        return envelope.sessionId == current.sessionId && envelope.generation == current.generation
    }

    /**
     * Opens a turn for a wake, unless the scene cooldown refuses it or a turn is already waiting for its transcript.
     *
     * A manual `Help` is an explicit user action, so it passes [bypassWakeGates]: neither the awaiting-transcript guard
     * nor the wake cooldown applies to it, and the turn it replaces is still torn down (so two loops never run side by
     * side). It remains subject to the caller's own requirement of a current-session final transcript.
     *
     * A cooldown-refused wake is **recorded**, not dropped without a trace: it has no turn to key an audit line by, so
     * the [`WakeDetected`][com.sheen.rokid.contracts.WakeDetected] envelope's `eventId` is used instead. A refusal by the
     * awaiting-transcript guard is a different, already-visible condition (a turn is provably in flight) and is not
     * recorded as a cooldown refusal.
     *
     * Must be called under [gate]; returns the new turn, or null when the wake was refused.
     */
    private fun openTurn(
        nowNanos: Long,
        envelope: EventEnvelope,
        runsImmediately: Boolean = false,
        transcriptTurnId: String? = null,
        bypassWakeGates: Boolean = false,
        refusedWakeWord: String? = null,
        /**
         * 0.24 S3 (spec §5.5): this turn continues the conversation after an answer. It must not start the wake
         * cooldown (the official `cancelContinuation`/`startNewTalk` path is not a wake) and it starts already
         * listening, with the official 10 s armed by the conversation cycle.
         */
        continuation: Boolean = false,
        /**
         * 0.4 Task 10 (`D203`, spec §5.2): the turn id the caller wants. The typed path passes the `typed-<uuid>` the
         * composition put on its envelope, so the log line, the row and the hint all name one identity. The default
         * preserves the wake/Help placeholder minting every pre-0.4 caller relies on.
         */
        turnId: String = "smart-${UUID.randomUUID()}",
        /**
         * 0.4 Task 10 (`D203`, spec §5.2): whether this turn starts the 3 s scene cooldown. A typed message is not a
         * wake, so it must not delay the next wake; the default keeps every pre-0.4 caller byte-for-byte.
         */
        startsCooldown: Boolean = true,
    ): SmartTurn? {
        val current = context ?: return null
        if (!bypassWakeGates) {
            if (turn?.phase == SmartTurnPhase.AwaitingTranscript) return null
            if (!scene.admit(nowNanos, envelope.eventId)) {
                if (refusedWakeWord != null) {
                    audit.record(envelope.eventId, "wake", refusedWakeWord, "CooldownRefused")
                }
                return null
            }
        }
        if (!continuation) {
            if (startsCooldown) scene.noteTurn(nowNanos)
            // A new wake (or Help) starts a new conversation; a sentence queued for the previous answer's
            // continuation must not be replayed into it.
            queuedUtterance = null
        }
        val replaced = turn?.also { it.closed = true }
        val created = buildTurn(nowNanos, current, runsImmediately, transcriptTurnId, turnId = turnId)
        turn = created
        // 0.24 S3: a continuation replaces the finished turn but must **not** cancel its output - the answer is exactly
        // what the next round continues, and the official status 5→1 keeps it on the panel.
        teardown(replaced, cancelOutput = !continuation)
        // 0.24 S3: the official 10 s window belongs to the conversation cycle, never to the turn (Task 12) - arming
        // both would close one wake twice. `Help` runs immediately and has nothing to listen for; the caller of a
        // wake/continuation arms the cycle once the turn is installed.
        // Phase C: a wake turn is now being worked on, and the wearer's ring should say so until it answers. The flag
        // is set here (under `gate`) and reported by the caller, because `publishBusy` must not run under the lock.
        wakeBusy = true
        return created
    }

    /** Must be called under [gate]; builds the turn, its hint window and its hint assembler. */
    private fun buildTurn(
        nowNanos: Long,
        current: PluginContext,
        runsImmediately: Boolean,
        transcriptTurnId: String?,
        /**
         * 0.3 Phase 3 Task 7: the route identity the turn carries into its display/settlement paths. The defaults are
         * the wake route, so every pre-Task-7 construction is unchanged.
         */
        route: AssistantRoute = AssistantRoute.WAKE,
        promptRoute: PromptRoute = PromptRoute.WAKE,
        turnId: String = "smart-${UUID.randomUUID()}",
    ): SmartTurn {
        // The assembler's emit callback must name the turn it belongs to - the plugin has two slots now, and an
        // identity-less callback could only guess which conversation should receive the sentence. The holder is
        // assigned before the assembler can run: its first emit is scheduled at least one merge interval away.
        var created: SmartTurn? = null
        val built = SmartTurn.awaiting(
            turnId = turnId,
            sessionId = current.sessionId,
            generation = current.generation,
            clock = clock,
            scheduler = scheduler,
            deadlineNanos = SmartTurn.saturatingAdd(nowNanos, turnBudgetNanos()),
            hintGraceNanos = DEFAULT_HINT_GRACE_MILLIS * 1_000_000L,
            hintId = "hint-${UUID.randomUUID()}",
            onEmit = { text -> created?.let { emit(it, text) } },
            route = route,
            promptRoute = promptRoute,
        )
        created = built
        if (transcriptTurnId != null) built.adoptTranscriptTurnId(transcriptTurnId)
        if (runsImmediately) built.running()
        return built
    }

    /**
     * The agent run's **machine budget** (2026-10-22, D197): none.
     *
     * The profile timeout still bounds each **provider request** (`requestTimeoutNanos`), and the loop still stops on
     * the step guard, `ProgressGuard`, the error streak and the observation budget. A total wall-clock cap is what the
     * mainstream harnesses do not use, and it cut a working multi-step proactive follow-up at 180 s.
     *
     * This is deliberately not the official 10 s listening window: since 0.24 S3 that window belongs to
     * [conversationCycle] and starts at `PANEL_SHOWN`; this number no longer bounds the run at all.
     */
    private fun turnBudgetNanos(): Long = AGENT_TURN_BUDGET_NANOS

    /**
     * The final-transcript gate.
     *
     * Must be called under [gate]. A **usable** final transcript is remembered whether or not a turn is waiting,
     * because `Help` is the spec's manual entry point and has to be able to run the last thing the wearer said even
     * though no wake opened a turn for it. The loop only runs when a turn is actually waiting for its transcript, and
     * only on a final revision: a partial never reaches the model, and a repeat after the turn has started does not
     * restart it.
     *
     * A final that is blank, or that is only the wake word, is the segment that carried the wake finalising — not the
     * request (D12: measured on the device, the request is transcribed in a later segment ~5.5 s afterwards). It is
     * neither remembered nor adopted, and it does **not** restart a listening timer: 0.24's official window was
     * already cancelled by the ASR message itself (spec §5.3), and inventing another one here would close a panel the
     * official flow leaves open until the wearer speaks or leaves.
     */
    private fun acceptTranscript(revision: TranscriptRevision): Pair<SmartTurn, String>? {
        if (revision.final) {
            val adopted = revision.text.trim()
            if (isUsableUtterance(adopted)) {
                lastFinalTranscript = adopted
                lastFinalTurnId = revision.turnId
            }
            // The adopted text is remembered above; the line carries only its revision and size, never the text.
            P3Log.line("P3_TRANSCRIPT turn=${revision.turnId} rev=${revision.revision} final=${revision.final} " +
                "chars=${adopted.length}")
        }
        val current = turn ?: return null
        if (current.phase != SmartTurnPhase.AwaitingTranscript) return null
        if (!revision.final) return null
        val text = revision.text.trim()
        if (!isUsableUtterance(text)) return null
        // The transcript's own turn id becomes the turn id from here on: the host keys `cancelOutputTurn` and the UI
        // correlate a hint by it, so the run and its hint must carry the ASR turn id rather than the wake's placeholder.
        current.adoptTranscriptTurnId(revision.turnId)
        // 0.24 S3: the run's machine budget starts **now**, not at the wake. The official window may stay open as long
        // as the wearer is still speaking (any ASR cancelled its timer), so a late transcript would otherwise hand the
        // loop whatever was left of a window that began minutes ago.
        current.extendDeadline(SmartTurn.saturatingAdd(clock.nowNanos(), turnBudgetNanos()))
        current.running()
        return current to text
    }

    /**
     * Must hold [gate]: remembers a usable final spoken during the running wake turn (or in the brief Idle gap before
     * its continuation opens) as the **next** utterance, latest wins.
     *
     * Deliberately not routed through [acceptTranscript]: that method remembers a final in `lastFinalTranscript` for
     * `Help` **before** its phase check, and a sentence spoken during an answer must not become the manual `Help`
     * input. The final is also kept out of the proactive trigger - the wearer is talking to the conversation, not
     * proposing a hint.
     *
     * A final captured while the assistant's own answer is still being spoken is refused: the microphone hears that
     * playback too, and it is not the wearer's next sentence. The shipped host has speech off, so this only guards the
     * opt-in `语音朗读` path.
     */
    private fun queueContinuationUtterance(
        route: AssistantRoute,
        owned: SmartTurn?,
        revision: TranscriptRevision,
    ) {
        owned ?: return
        if (owned.closed || owned.route != route) return
        if (owned.answerEmitSpeech && !owned.answerSpeechFinished) {
            P3Log.line("P3_CONV queue_drop route=${route.name} reason=self_speech")
            return
        }
        val text = revision.text.trim()
        if (!isUsableUtterance(text)) return
        queuedUtterance = QueuedUtterance(route, text, revision.turnId, clock.nowNanos())
        // Counts only: the wearer's words are display content and never reach the log.
        P3Log.line("P3_CONV queue route=${route.name} chars=${text.length}")
    }

    /**
     * Must hold [gate]: the queued sentence for the continuation that was just opened, or `null` when none is fresh.
     *
     * A sentence that has waited longer than [CONTINUATION_QUEUE_TTL_NANOS] is dropped rather than answered: after a
     * long turn it no longer refers to the conversation the continuation is opening, and replaying it would surprise
     * the wearer with an answer to something they said a minute ago.
     */
    private fun takeQueuedUtteranceLocked(route: AssistantRoute, nowNanos: Long): QueuedUtterance? {
        val queued = queuedUtterance ?: return null
        if (queued.route != route) {
            // A continuation never consumes the other conversation's sentence (P3-13), and it must not drop it either:
            // the sibling route's continuation is the only consumer it has.
            return null
        }
        queuedUtterance = null
        if (nowNanos - queued.capturedNanos > CONTINUATION_QUEUE_TTL_NANOS) {
            P3Log.line("P3_CONV queue_drop route=${route.name} reason=stale")
            return null
        }
        return queued
    }

    /** Must hold [gate]: drops a queued sentence only when it belongs to [route] (the two routes stay independent). */
    private fun clearQueuedUtteranceLocked(route: AssistantRoute) {
        if (queuedUtterance?.route == route) queuedUtterance = null
    }

    /** Whether a trimmed final can be the utterance: not empty, and not the wake segment's own echo of the word. */
    private fun isUsableUtterance(text: String): Boolean = text.isNotBlank() && !isWakeWordOnly(text)

    /**
     * True when a trimmed final is just a configured wake word, wrapped in at most punctuation.
     *
     * The recogniser's own punctuation around the word (`伊迪丝。`, `，伊迪丝，`, `EDITH.`) does not make the wake
     * segment a request. Both ends are stripped of anything that is not a letter or a digit and the comparison ignores
     * case, so `EDITH` matches the configured `edith` while `伊迪丝，帮我记一条笔记` remains a real utterance.
     */
    private fun isWakeWordOnly(text: String): Boolean {
        val normalized = text.trim().trim { !it.isLetterOrDigit() }
        if (normalized.isEmpty()) return false
        return configuredWakeWords().any { it.equals(normalized, ignoreCase = true) }
    }

    /**
     * 0.3 Phase 3 Task 6 / P3-13: whether a WAKE `AwaitingTranscript`/running turn must consume the final first.
     *
     * Must hold [gate]. A finished wake turn stays installed in `turn` until something replaces it; only a turn that
     * is actually waiting for its utterance or running the loop outranks the proactive window.
     */
    private fun wakeTurnWaitingOrRunning(): Boolean = when (turn?.phase) {
        SmartTurnPhase.AwaitingTranscript, SmartTurnPhase.Running -> true
        else -> false
    }

    /**
     * Must hold [gate]: consumes a usable final for the PROACTIVE follow-up window, or returns `null`.
     *
     * The order is the hard P3-13 constraint, not a preference: a WAKE turn waiting for or running its own utterance
     * takes the final; only when no WAKE turn is busy may the PROACTIVE session consume it. Both sessions then own
     * their own turn - the final never enters the other route.
     *
     * A blank or wake-word-only final never reaches this method ([isUsableUtterance] refuses it earlier), which is the
     * Q4=A deviation from the official `AsrMessageHandler`: that handler removes the listening timeout *before* the
     * blank check, but doing so here would let the wake segment's echo make the window unbounded.
     */
    private fun acceptFollowupTranscript(revision: TranscriptRevision, expectedTurnId: String): FollowupUtterance? {
        if (!revision.final) return null
        val text = revision.text.trim()
        if (!isUsableUtterance(text)) return null
        if (wakeTurnWaitingOrRunning()) return null
        val window = proactiveFollowup ?: return null
        if (window.turnId != expectedTurnId) return null
        proactiveFollowup = null
        return FollowupUtterance(window.turnId, text, revision.turnId)
    }

    /**
     * 0.3 Phase 3 Task 7: consumes a usable final for a **continuation** PROACTIVE round (the turn a previous
     * follow-up answer's `openContinuation` opened), or returns `null`.
     *
     * Must hold [gate]. The same P3-13 order applies: a WAKE turn waiting for or running its own utterance takes the
     * final first, and a WAKE turn's final must never enter this route. The waiting turn adopts the ASR id exactly as
     * [acceptTranscript] does for WAKE, then starts running; the answer and its continuation stay on this route.
     */
    private fun acceptFollowupContinuation(
        revision: TranscriptRevision,
        expectedTurnId: String?,
        armed: Boolean,
    ): Pair<SmartTurn, String>? {
        if (!armed || expectedTurnId == null) return null
        if (!revision.final) return null
        val text = revision.text.trim()
        if (!isUsableUtterance(text)) return null
        if (wakeTurnWaitingOrRunning()) return null
        val current = followupTurn ?: return null
        if (current.phase != SmartTurnPhase.AwaitingTranscript) return null
        if (current.turnId != expectedTurnId) return null
        current.adoptTranscriptTurnId(revision.turnId)
        current.extendDeadline(SmartTurn.saturatingAdd(clock.nowNanos(), turnBudgetNanos()))
        current.running()
        return current to text
    }

    /**
     * 0.3 Phase 3 Task 7: completes the Task 6 seam - one accepted follow-up utterance becomes a real PROACTIVE turn.
     *
     * The window's listening round is **not** closed with `onClose`: a usable final is an ASR message, so the route
     * takes the official `onAsr` path (releases the 10 s window, keeps the round's turn identity). That is the Task 6
     * carry: `onClose` resets the route's turn id, after which the follow-up's answer could neither settle
     * (`onAnswerDone(PROACTIVE)`) nor continue (`openContinuation(PROACTIVE)`). The turn then adopts the ASR turn id,
     * shows the official ASR levels on PROACTIVE, and runs the shared `runTurnBody` through the normal six-tool
     * registry with the `PROACTIVE_FOLLOWUP` prompt posture.
     */
    private fun startFollowupTurn(utterance: FollowupUtterance) {
        var created: SmartTurn? = null
        synchronized(gate) {
            val current = context ?: return
            // Keep the Task 6 hand-off observable for its seam accessor until the turn exists, then consume it.
            followupTurnRequest = FollowupTurnRequest(utterance.turnId, utterance.text, clock.nowNanos())
            created = openFollowupTurnLocked(
                clock.nowNanos(),
                current,
                runsImmediately = true,
                turnId = utterance.turnId,
                transcriptTurnId = utterance.asrTurnId,
                continuation = false,
            )
            followupTurnRequest = null
            proactiveFollowup = null
        }
        val owned = created ?: return
        // The window's cycle round is what will settle and continue this answer. A concurrent UI_CLOSED can release it
        // between the accept and this install (the host scheduler delivers panel events on its own thread); an
        // unsettleable run would then display an answer that can never be continued. The window rule applies to the
        // turn it became: no cycle round, no follow-up.
        if (!conversationCycle.hasTurn(AssistantRoute.PROACTIVE)) {
            val stranded = synchronized(gate) {
                if (followupTurn === owned) {
                    followupTurn = null
                    followupBusy = false
                    owned.also { it.closed = true }
                } else {
                    null
                }
            }
            teardown(stranded)
            publishBusy()
            return
        }
        conversationCycle.onAsr(AssistantRoute.PROACTIVE)
        conversationCycle.onTurnId(AssistantRoute.PROACTIVE, owned.turnId)
        conversationCycle.onStatus(AssistantRoute.PROACTIVE, OfficialStatus.ASR)
        conversationCycle.onStatus(AssistantRoute.PROACTIVE, OfficialStatus.ASR_END)
        publishUserFinal(ContextRoute.PROACTIVE, owned.turnId, utterance.text)
        publishBusy()
        launchFollowupTurn(owned, utterance.text)
    }

    /**
     * Hands one FOLLOWUP_TURN to the plugin's task scope, for the same reason [launchTurn] does: the run blocks on the
     * transport **and** on the confirmation gate, and the host's dispatcher must stay free to deliver the answer.
     */
    private fun launchFollowupTurn(owned: SmartTurn, text: String) {
        val services = synchronized(gate) { context?.services } ?: return
        services.tasks.launch { runFollowupTurn(owned, text) }
    }

    /**
     * 0.3 Phase 3 Task 7: drives one PROACTIVE follow-up through the shared turn engine.
     *
     * It is the wake run's exact body ([runTurn]) - action/perception boundaries, the six-tool registry, confirmation,
     * memory, the route's store and the display - selected by the turn's own [SmartTurn.route]/[SmartTurn.promptRoute].
     * The route is data, not a second implementation.
     */
    private fun runFollowupTurn(owned: SmartTurn, text: String) {
        runTurn(owned, text)
    }

    /**
     * Must hold [gate]: opens (or replaces) a turn in the PROACTIVE slot.
     *
     * [runsImmediately] is the initial FOLLOWUP_TURN: the final already arrived, so the turn starts `Running`.
     * `true` with [continuation] `false` is Task 6's window, converted to a full turn; [continuation] `true` is the
     * next round a displayed answer opened and starts `AwaitingTranscript`.
     *
     * [turnId] is minted for a continuation and is the window's id for the initial turn; [transcriptTurnId] is the ASR
     * id it adopts so the answer carries the transcript's identity, exactly like a wake turn.
     */
    private fun openFollowupTurnLocked(
        nowNanos: Long,
        current: PluginContext,
        runsImmediately: Boolean,
        continuation: Boolean,
        turnId: String = "followup-${UUID.randomUUID()}",
        transcriptTurnId: String? = null,
    ): SmartTurn {
        val replaced = followupTurn?.also { it.closed = true }
        val created = buildTurn(
            nowNanos = nowNanos,
            current = current,
            runsImmediately = runsImmediately,
            transcriptTurnId = transcriptTurnId,
            route = AssistantRoute.PROACTIVE,
            promptRoute = PromptRoute.PROACTIVE_FOLLOWUP,
            turnId = turnId,
        )
        followupTurn = created
        // A displayed answer is what the next round continues, exactly as on the wake route: it must not be cancelled.
        teardown(replaced, cancelOutput = !continuation)
        followupBusy = true
        return created
    }

    /** Detaches the PROACTIVE turn slot: marks it closed and hands it to the caller for teardown. Must hold [gate]. */
    private fun detachFollowup(): SmartTurn? = followupTurn?.also {
        it.closed = true
        followupTurn = null
        followupBusy = false
    }

    // ---------------------------------------------------------------- the run

    /**
     * Hands one turn to the plugin's task scope instead of running it on the caller's thread.
     *
     * The caller is the host's **single** event dispatcher: `HostRuntime.deliverEvent` calls `handle` on the
     * `edith-host` thread. [runTurn] blocks twice — the gateway waits on the transport, and the confirmation gate
     * waits on the wearer — so running it inline occupied that dispatcher for the whole question. Both halves of
     * spec §17.4 then break at once:
     *
     * 1. the answer is itself a `ConfirmationResult` **event**, and the host delivers events through the very
     *    dispatcher the question is holding, so a phone tap could never be delivered while the question was open;
     * 2. the App's UI thread blocked in `HostRuntime.command` behind that dispatcher — reported by the platform as
     *    an ANR (`Input dispatching timed out … Waited 5001ms for MotionEvent(action=UP)`) on the K40.
     *
     * `runProactive` has handed its blocking half over for exactly this reason since P3.2, including in its comment;
     * this is the same rule applied to the wake turn, which was missed. [runTurn] re-checks ownership under [gate]
     * before writing anything back, so a turn detached while it was queued stays detached.
     */
    private fun launchTurn(owned: SmartTurn, text: String) {
        val services = synchronized(gate) { context?.services } ?: return
        services.tasks.launch { runTurn(owned, text) }
    }

    /**
     * Drives one turn through the agent loop and turns its terminal state into a hint.
     *
     * The loop runs **outside** [gate]: the gateway blocks on the transport, and holding the plugin lock across that
     * would stop `Stop` and `deactivate` from ever cancelling it. Ownership is re-checked under the lock before
     * anything is written back to the turn — the same discipline the diagnostic plugin uses for its streaming path.
     */
    private fun runTurn(owned: SmartTurn, text: String) {
        val (current, configured) = synchronized(gate) {
            val c = context ?: return
            val cfg = config ?: return
            c to cfg
        }
        // 0.25 Task 10 (R42): a new assistant turn re-arms the turn-scoped action executor. `teardown` cancels it
        // when a turn ends (`Stop`, a replaced turn, a session end); without the matching begin the next turn would
        // keep getting CANCELLED from the device kernel for the rest of the process.
        runCatching { current.services.actions().beginTurn() }
        // edith-0.3 Phase 2 Task 9: the perception turn opens immediately after the action turn, and the `finally`
        // below closes it so every exit that still owns the boundary — success, an early refusal, a thrown transport
        // failure or a cancellation — pairs its `beginTurn()` with an `endTurn()`. The host's provider owns the refs
        // and the open capture window; a turn left open would let stale refs survive into the next one. A superseded
        // turn deliberately does **not** call `endTurn()`: its ticket is stale and the close would clear the newer
        // turn's snapshot/refs (review minor).
        val perception = activePerception
        val perceptionTurn = perception?.let { provider -> beginPerceptionTurn(provider) }
        val perceptionRequests = AtomicInteger(0)
        try {
            runTurnBody(
                owned,
                text,
                current,
                configured,
                perception,
                perceptionTurn?.initialSnapshot,
                perceptionRequests,
            )
        } finally {
            // A superseded turn still unwinds through here after the newer turn has opened its boundary; the ticket
            // check keeps its late close from clearing the newer turn's snapshot/refs (review minor).
            if (perception != null && perceptionTurn != null) endPerceptionTurn(perception, perceptionTurn.ticket)
        }
    }

    /** One opened perception boundary: its owner [ticket] and the snapshot `beginTurn()` took (or `null` on failure). */
    private class PerceptionTurnBoundary(val ticket: Long, val initialSnapshot: PerceptionSnapshot?)

    /**
     * Opens the host's perception boundary and takes ownership of it, serialised under [perceptionTurnLock].
     *
     * The ticket is allocated **before** `beginTurn()` runs and the whole pair is under the lock, so two turns cannot
     * interleave their begin/end calls: the newer turn's begin always lands after the older turn's close (when the older
     * one got there first), and the ticket order matches the order the host saw. A throwing `beginTurn()` still owns a
     * ticket: its `finally` closes the boundary attempt, and the request path falls back to an explicit full read.
     */
    private fun beginPerceptionTurn(provider: PerceptionProvider): PerceptionTurnBoundary =
        synchronized(perceptionTurnLock) {
            perceptionTurnGeneration += 1L
            perceptionTurnOwner = perceptionTurnGeneration
            PerceptionTurnBoundary(
                ticket = perceptionTurnGeneration,
                initialSnapshot = runCatching { provider.beginTurn() }.getOrNull(),
            )
        }

    /**
     * Closes the perception boundary only when [ticket] is still the owner; a superseded/cancelled turn is a no-op.
     *
     * The close runs under the same lock as [beginPerceptionTurn], so a newer begin cannot install a snapshot between
     * the owner check and the older `endTurn()`.
     */
    private fun endPerceptionTurn(provider: PerceptionProvider, ticket: Long) {
        synchronized(perceptionTurnLock) {
            if (perceptionTurnOwner != ticket) return
            perceptionTurnOwner = 0L
            runCatching { provider.endTurn() }
        }
    }

    /**
     * The blocking half of [runTurn], separated only so the perception turn's `try/finally` encloses all of it. Its
     * early returns end this body; the caller's `finally` still closes the provider.
     */
    private fun runTurnBody(
        owned: SmartTurn,
        text: String,
        current: PluginContext,
        configured: Config,
        perception: PerceptionProvider?,
        initialSnapshot: PerceptionSnapshot?,
        perceptionRequests: AtomicInteger,
    ) {
        // 0.3 Phase 3 Task 7: the turn's own route selects the conversation it records/assembles and the prompt posture
        // its requests render. Wake turns resolve to exactly today's values; a follow-up turn to PROACTIVE.
        val contextRoute = owned.route.toContextRoute()
        val promptRoute = owned.promptRoute
        // B9 (spec §1.3 C2): a hard dependency that is missing refuses the turn *here*, before the loop is built and
        // before the listening window has anything to wait for. A refusal is a per-turn judgement, not a latch: the
        // models are resident and their state can change between turns, so a slow load that finishes later serves
        // the next turn instead of leaving the plugin dead.
        serviceRefusal()?.let { reason ->
            P3Log.line("P3_DEPENDENCY reason=$reason")
            P3Log.line(
                "P3_TURN end turn=${owned.turnId} outcome=${AgentOutcome.FAILED} code=$reason steps=0" +
                    routeField(owned.route),
            )
            finishWithFailure(owned, DEPENDENCY_HINT)
            return
        }
        // The turn id is final by now (the final transcript's ASR id has replaced the wake's placeholder), so the
        // start/end pair and the transcript line all name the same turn.
        P3Log.line("P3_TURN start turn=${owned.turnId}${routeField(owned.route)}")
        if (text.isBlank()) {
            P3Log.line(
                "P3_TURN end turn=${owned.turnId} outcome=${AgentOutcome.FAILED} code=BLANK_UTTERANCE steps=0" +
                    routeField(owned.route),
            )
            finishWithFailure(owned, BLANK_UTTERANCE_HINT)
            return
        }
        // The loop is held outside the `try` so the failure path below can still read the human wait it had measured
        // before it threw (review I-1): a transport failure after a late answer is otherwise the one path where the
        // number is lost, and the honest "没能给出结果" sentence would be dropped as expired - the phone acted and the
        // glasses stayed blank.
        var loop: AgentLoop? = null
        // One ledger per turn, handed to the `notify_user` tool the loop carries (spec §6.3's guard is per-turn).
        val ledger = NotifyLedger()
        // B1/B6/spec §4.2: the session's own assembly — the route's instructions, the transcript this turn
        // continues, and the memory for the utterance. Read once per turn so the three come from one snapshot.
        // `null` when the wiring supplied no manager, which is every test that is about the passive loop; the request
        // then falls back to the pre-Phase-B shape and the gateway retrieves memory itself.
        //
        // SKILL is deliberately not part of this assembly since Phase 6: the managed text would double-inject what the
        // system slot already carries, so the manager is de-skilled. The catalog rides the system slot's SKILL segment
        // and the pulled bodies ride the request tail as `SKILL_BODY` (design D-1/D-2; 0.3 Task 12).
        //
        // Phase 6 (obligation 1): the turn's **single** governor observation opens here, before the turn's first
        // request is assembled and never from inside the per-request builder — the observation unit is the turn
        // (design D-3), and a call per request would inflate drift by every tool round-trip.
        val skillTurn = beginSkillTurn(owner = true)
        val prompt = activeContextForTurn?.let { contexts ->
            runCatching { contexts.buildPrompt(contextRoute, text) }.getOrNull()
        }
        // B1/B7/B6 evidence, counts only: without this line "the session carried the previous turn into this request"
        // and "the session carried nothing" are the same empty log, which is exactly what blocked these rows on the
        // device. Sizes are what a log may carry (P3.1's privacy rule); no prompt text is ever printed.
        //
        // Phase 5: with a store injected, `buildPrompt` still runs (its memory and instructions are what the turn
        // uses), but the manager it reads is not this route's session - the store is. The manager's transcript holds
        // only what the assembly put there, and `history=1` is the truthful value for it: the utterance itself. What
        // the line must **not** do is let a reader take that 1 as "the session carried one turn" (the store may hold
        // many, or none) - so with a store in use the counts come from the store instead, and only when there is no
        // store is the manager's line the whole session. Still counts only: never text.
        val store = activeStores?.get(contextRoute)
        if (store != null) {
            val history = store.history()
            P3Log.line(
                "P3_CONTEXT route=$contextRoute store=${history.size} " +
                    "historyChars=${history.sumOf { item -> item.estimatedLength() }} " +
                    "memory=${prompt?.memorySegments?.size ?: 0}",
            )
        } else {
            prompt?.let {
                P3Log.line(
                    "P3_CONTEXT route=${it.route} instructions=${it.instructions.length} " +
                        "history=${it.history.size} historyChars=${it.history.sumOf { item -> item.estimatedLength() }} " +
                        "memory=${it.memorySegments.size} tokens=${it.estimatedTokens} budget=${it.historyBudgetTokens} " +
                        "over=${it.overHistoryBudget}",
                )
            }
        }
        // Phase 5: with a store injected, this turn's shape is a `RequestFrame` — the route's snapshot, the memory the
        // assembly retrieved, and this turn's utterance last — and the gateway maps it. The registry is built here
        // (rather than inside `buildLoop`) so the frame's tool table and the loop's own are the same table.
        val registry = registryFor(current, owned, ledger)
        val gateway = gatewayFor(current, owned)
        // 0.24 S4 Task 17 (spec §5.6): the tool row's copy. edith-0.3 Phase 4A Task 5 (A5) reads the descriptor's
        // **user-facing title**, not `description`: the latter is the model's instruction prose and painting it would put
        // a paragraph on the glasses. The label map stays the fallback for a name the host advertised without copy; a
        // name in neither is the raw model name.
        val toolLabels = registry.descriptors().associate { it.name to it.title }
        // A **builder**, not a one-shot frame: a tool round makes this run ask the model a second time, and that
        // request must be rendered by the same renderer from the transcript it continues. Only the store path gets one;
        // the legacy path keeps its frame-less behaviour byte for byte.
        val frameFor = store?.let {
            frameBuilderFor(it, contextRoute, promptRoute, text, prompt, toolSpecsOf(registry))
        }
        // 0.3 final-fix wave (spec §5.2): one profile read feeds the request timeout, the per-turn output cap and the
        // trim window; the session's fail-closed policy code (if any) refuses the turn before the transport.
        val turnProfile = runCatching { profileLookup(configured.profileId) }.getOrNull()
        // Phase 2 Task 10: the profile's own image capability. `null` (no profile) is **not** evidence that images are
        // unsupported, so only a real profile that omits `IMAGE` turns the rendered coverage into
        // `unavailable(no_image_profile)`. The host's gate-off token (`disabled`) always wins over this.
        val profileAcceptsImages = turnProfile?.let { InputModality.IMAGE in it.inputs }
        val run = try {
            val built = buildLoop(configured, registry, gateway)
            loop = built
            synchronized(gate) {
                if (!owns(owned)) {
                    // Phase 6 (obligation 2): this turn is already over (stopped, replaced or the session ended), and
                    // this return leaves the function before the normal turn-end bookkeeping. Close the SKILL turn
                    // here too, or the governor's open turn would fold the next turn into this one.
                    endSkillTurn(skillTurn)
                    return
                }
                owned.loop = built
            }
            built.run(
                AgentRequest(
                    turnId = owned.turnId,
                    utterance = text,
                    // 0.3 Phase 3 Task 7 (the 0.3 Task 1 carry, minor 4): the `ContextManager` is still keyed by
                    // `ContextRoute` and maps PROACTIVE to the HINT posture, so its instructions are the hint's. A wake
                    // request keeps the manager's text (identical to the pinned WAKE plan in production, and what the
                    // legacy tests pin); a PROACTIVE follow-up must render its own pinned FOLLOWUP plan, or a no-store
                    // host would ask the model in the hint posture. The frame path already renders the same plan through
                    // `systemSlot`/`frameBuilderFor`; this is the legacy `AgentRequest.instructions` seam.
                    instructions = if (owned.route == AssistantRoute.WAKE) {
                        prompt?.instructions ?: promptPlan(promptRoute).systemText
                    } else {
                        promptPlan(promptRoute).systemText
                    },
                    profileId = configured.profileId,
                    deadlineNanos = owned.deadlineNanos,
                    // D49: the profile's timeout caps **one model request**; the turn's own budget is the number above,
                    // and it no longer pays for the time a person spends deciding.
                    requestTimeoutNanos = modelRequestTimeoutNanos(turnProfile?.timeoutMillis),
                    // 0.3 final-fix wave (spec §5.2): the profile's own output cap is this turn's cap, declared
                    // explicitly; the adapter's fallback is only for a caller that supplies none.
                    maxOutputTokens = turnProfile?.maxOutputTokens,
                    // The session policy's hard limit is the frame trim window; its failure code (if any) refuses
                    // every model turn of this session before the transport.
                    limitTokens = turnHardLimit(turnProfile),
                    contextFailure = activeContextFailure,
                    // `buildPrompt` ends its history on this turn's user message while the loop appends the utterance
                    // itself, so the last item is dropped: passing it whole would ask the model the same thing twice.
                    //
                    // Phase 5: the store path replaces the manager transcript with the route's own store, so the
                    // session this turn continues is the store's — not `prompt.history`, which the store path never
                    // writes (it holds only this utterance, i.e. an empty session). The store is handed over **whole**:
                    // the loop appends this turn's utterance and drops it again before calling `frameFor`, so the
                    // builder receives the store exactly as it stands, with the previous turn's assistant half intact.
                    // (`dropLast(1)` here would drop that half and lose a turn from every frame; the loop's own
                    // drop is the one that matches the "the utterance is already in the history" convention.)
                    history = store?.history() ?: prompt?.history?.dropLast(1).orEmpty(),
                    frameFor = frameFor,
                    // 0.24 S4 (spec §5.5): every text delta of the run is a display revision for this exact turn.
                    // The turn is captured here, so a late transport callback from a superseded run is inert.
                    onTextDelta = { delta -> publishAssistantPartial(owned, delta) },
                    // 0.24 S4 Task 17: one START/END pair per tool, with the action's mapped label.
                    onToolStatus = { action, phase -> publishToolStatus(owned, action, phase, toolLabels) },
                    // 0.25 Task 12 (R58): both full conversation routes answer a wearer who is waiting for an action,
                    // so a text-only plan may be nudged into executing it. The HINT_TURN remains the one proposal-only
                    // turn and still passes `false`; this field is the shared turn engine's, not a route decision.
                    allowPlanNudge = true,
                    // 0.25 Task 12 (R59): one short state line from the scoped executor, folded into the request by
                    // the loop. It is never written to the store: the loop keeps it out of the transcript.
                    runtimeState = { runtimeStateForTurn() },
                    // 0.3 Phase 2 Task 9: the perception block for **this** request. The lambda is called once per
                    // model request of the run; request 0 uses the snapshot `beginTurn()` already took (or a full
                    // re-read when that failed), later requests ask only for the ambient device/media state. The
                    // renderer never logs content; the one `P3_PERCEPTION` line is counts plus coverage tokens.
                    perception = perception?.let { provider ->
                        { perceptionBlockFor(provider, initialSnapshot, perceptionRequests, profileAcceptsImages) }
                    },
                    // edith-0.3 Phase 2.2 Task 6 / Phase 3 Task 3 (M4): the loop's bounded failure-observation source,
                    // held for the whole session and closed by `deactivate`. Without it the loop's
                    // `screenObservation` enrichment would be dead code on the device: every screen-tool failure that
                    // could not observe itself would report `no_source`. A null provider (a host that grants no
                    // perception) leaves the loop's explicit `no_source` mapping.
                    observationSource = activeObservationSource,
                    // 0.3 Phase 2 Task 11 (review fix R12): the loop calls this once per **assembled** request, after
                    // folding its RUNTIME_STATE/PERCEPTION segments in and before the gateway sends (or refuses), so
                    // the `P3_PROMPT` line describes the exact frame this request is. The legacy no-store path has no
                    // frame and therefore emits no prompt line.
                    onRequestBuilt = { frame -> logPromptRequest(promptRoute, frame) },
                ),
            )
        } catch (failure: Throwable) {
            // `ModelClientGateway` documents that an escaping transport exception must not be disguised as a finished
            // turn. The plugin's own contract is that a turn always produces an honest hint, so it is translated into
            // the `FAILED` terminal state here instead of escaping into the host's dispatcher.
            //
            // The cause is reported by **class name only**: `PLUGIN_RUN_THREW` cannot say which layer refused (a
            // missing capability, a rejected configuration, a tool schema), and the message is exactly where a URL,
            // a profile id or user content would leak into the log. The class is the part that is safe and useful.
            // The cause is reported by a **shape-validated code**, never by the raw message: the class alone cannot
            // distinguish a profile revision mismatch from an invalid endpoint from an over-budget context (all four
            // are `ConfigurationRejected`), and the message is exactly where a URL, a profile id or a path would leak.
            // `safeFailureCode` keeps a bounded reason-shaped prefix and refuses anything that looks like a payload.
            P3Log.line("P3_RUN error=${failure::class.simpleName ?: failure.javaClass.name} " +
                "code=${failure.safeFailureCode("UNSPECIFIED")}")
            // A run that threw never handed its report over, so the measurement is taken from the loop itself - the wait
            // it had already excluded from its budget is exactly what keeps this turn's hint window open.
            AgentRun(
                AgentOutcome.FAILED, "PLUGIN_RUN_THREW", null, emptyList(),
                loop?.measuredHumanWaitNanos ?: 0L,
            )
        }
        val stillOurs = synchronized(gate) {
            if (!owns(owned)) {
                false
            } else {
                owned.idle()
                owned.loop = null
                true
            }
        }
        // The run terminated, so its steps and its outcome are reported even when the turn was detached underneath
        // it: that terminal outcome is exactly what P3.1 has to prove. `AgentStep.result` is `ok` or the stable safe
        // code the loop fed back to the model - never the model's text and never an executor detail that could carry
        // user content (P3.1 privacy rules).
        run.steps.forEach { step ->
            P3Log.line("P3_TOOL call=${step.action} ok=${step.result == "ok"} detail=${step.result}")
        }
        P3Log.line(
            "P3_TURN end turn=${owned.turnId} outcome=${run.outcome} code=${run.safeCode ?: "-"} " +
                "steps=${run.steps.size}${routeField(owned.route)}",
        )
        // Phase 6 (obligations 2 and 5): close the turn that `beginSkillTurn()` opened. This runs before the outcome
        // is submitted **and** on the detached path below, so every ending pairs with its opening: an unclosed turn
        // would let the following turn fold into this one as a single observation, and a body pulled here would still
        // be in the next turn's first request.
        endSkillTurn(skillTurn)
        if (!stillOurs) return
        // Review I-1: the loop shifts its *own* budget by the time a person spent deciding (D49), but this turn's hint
        // window is derived from **its** deadline (`hintValidUntil` = deadline + grace). Without moving that too, a
        // result assembled after a late answer is dropped as expired: the phone executes the approved action and the
        // glasses stay blank. The measurement comes back with the run, so the two numbers cannot drift apart.
        if (run.humanWaitNanos > 0L) {
            synchronized(gate) {
                if (owns(owned)) {
                    owned.extendDeadline(SmartTurn.saturatingAdd(owned.deadlineNanos, run.humanWaitNanos))
                }
            }
        }
        // P3.4 §3.2: what this turn leaves behind. Written **before** the outcome is submitted because the turn still
        // owns its hint window here - an acknowledgement emitted afterwards would be dropped as expired, which is
        // exactly the "silent remember" the explicit path exists to avoid.
        rememberFromTurn(owned, text, run)
        // B6 / spec §4.9: the transcript and the compression come **after** the memory write, in that order, so a
        // message that compression is about to discard is already in the store. Doing it the other way round would
        // lose exactly the content compression exists to preserve. The route is the turn's own (P3-13): a follow-up
        // records into the PROACTIVE store and never the wake one.
        recordTurnAndCompress(contextRoute, text, run, ledger.notifications, gateway.lastTrim)
        submitOutcome(owned, run, ledger)
        // 0.24 S3 (spec §5.5, Task 14 / Task 12 carry I1): the run has ended, so the last emit is the final answer -
        // whether it was a mid-run `notify_user` or the closing fallback the assembler is about to emit. This is where
        // the displayed/rejected/silent decision is made, not in `emit`'s phase check (a `notify_user` answer is
        // emitted while the run is still `Running`).
        settleAnswerAfterRun(owned, fallbackExpected = ledger.calls == 0 && !ledger.suppressed)
        // 0.3 Phase 3 Task 7 fix (controller ruling): only a **WAKE** turn may release the proactive work it made wait
        // (spec §4.6). `runTurnBody` is shared with the FOLLOWUP_TURN, and releasing from a follow-up's end resumed a
        // trigger parked during the follow-up as a HINT_TURN right after the follow-up answer. That hint's `Displayed`
        // ACK then installed a fresh PROACTIVE window over the continuation round the follow-up answer had just opened,
        // so the wearer's next sentence became the hint's follow-up and the continuation was silently superseded. A
        // follow-up leaves the parked trigger parked - no new queue, no second HINT_TURN.
        if (owned.route == AssistantRoute.WAKE) releaseParkedProactive()
    }

    /**
     * edith-0.3 Phase 2 Task 9: one model request's perception block, built at the moment the loop is about to send it.
     *
     * Request 0 uses the snapshot `beginTurn()` already took (spec §7.5's "完整感知快照"), falling back to one explicit
     * full read when that failed; every later request asks only for [AMBIENT_PERCEPTION_SOURCES], because a tool round
     * must not repeat the whole snapshot. Both calls are guarded: a provider that throws contributes no block rather
     * than failing the turn. The device state is read per call for the same reason — it carries the current clock,
     * battery and foreground.
     *
     * Exactly one count-only `P3_PERCEPTION` line is emitted per rendered block; the block itself is never logged.
     * Returns `null` when no snapshot could be read, which the loop treats as "no perception segment".
     *
     * [visionSupported] is the resolved profile's `IMAGE` capability (Task 10): `false` downgrades an `ok` coverage
     * token to `unavailable(no_image_profile)`; `null` leaves the host's own token alone. The same
     * [SmartPerceptionRenderer.RenderedPerception.coverage] feeds both the block and the telemetry, so the logged
     * `coverage=` can never disagree with what the model was told.
     */
    private fun perceptionBlockFor(
        provider: PerceptionProvider,
        initialSnapshot: PerceptionSnapshot?,
        requests: AtomicInteger,
        visionSupported: Boolean?,
    ): String? {
        val full = requests.getAndIncrement() == 0
        val snapshot = if (full) {
            initialSnapshot ?: runCatching {
                provider.snapshot(PerceptionSource.entries.toSet(), PerceptionFilter.ALL, null)
            }.getOrNull()
        } else {
            runCatching {
                provider.snapshot(AMBIENT_PERCEPTION_SOURCES, PerceptionFilter.ALL, null)
            }.getOrNull()
        } ?: return null
        val device = runCatching { activeDeviceStateProvider?.state() }.getOrNull()
        // `renderDetailed` is the block plus the local-clip flags; reducing it to a String here would hide a value
        // the policy cap deliberately truncated (review I3), so the telemetry call gets the same result.
        val rendered = SmartPerceptionRenderer.renderDetailed(
            snapshot,
            device,
            perceptionPolicy,
            full,
            visionSupported = visionSupported,
        )
        P3Log.line(
            SmartPerceptionRenderer.telemetry(
                snapshot,
                rendered.text,
                rendered.truncated,
                rendered.coverage,
            ),
        )
        return rendered.text
    }

    /**
     * P3.4 §3.2: what this turn earned, into long-term memory, plus the one sentence an explicit instruction is owed.
     *
     * Only steps whose result is `ok` count towards a habit - a refused or failed action is not something the user
     * "keeps doing". Nothing here is logged with content: [MemoryLog] and [MemoryExtraction] print counts, kinds and
     * reason codes only.
     */
    private fun rememberFromTurn(owned: SmartTurn, utterance: String, run: AgentRun) {
        val extraction = memoryExtraction ?: return
        val succeeded = run.steps.filter { it.result == "ok" }.map { it.action }
        val report = extraction.afterTurn(utterance, succeeded)
        // Task 14 (Task 12 review minor 8): the acknowledgement is a hint, but it is not the answer the conversation
        // continues. Recording it as an answer would let a no-speech acknowledgement hide the notification answer's
        // speech or settle a conversation on text the model did not mean as its reply. 0.24 S4 Task 16 (I1): it is
        // recorded under its own entry, and its replacement of the answer is classified as a supersession.
        if (report.explicit) emit(owned, MemoryExtraction.ACKNOWLEDGEMENT, recordAsAnswer = false)
    }

    /**
     * Edits a route's session transcript for the turn that just ended, then compresses it if it has outgrown its
     * budget.
     *
     * Must be called **after** the turn's memory write (spec §4.9 steps ③→⑤). Both halves are skipped when the wiring
     * did not supply a [ContextManager], which is the pre-Phase-B shape.
     *
     * Phase 5 gives the route two possible owners, and the one that exists is used **instead of** the other: with a
     * store injected for this route the transcript is the store and the compression is [ContextCompressor.compressStore]
     * (whole turns, gated by D-4's ③ rule); otherwise the `ConversationContext` path runs exactly as it did before.
     *
     * The route is a parameter because both routes own a turn to record and each has its own transcript: the wake
     * route records the utterance it answered, the proactive route the observation it reacted to. Passing `WAKE` here
     * from the proactive path was the other way "two independent conversations" was half-true.
     */
    private fun recordTurnAndCompress(
        route: ContextRoute,
        userText: String,
        run: AgentRun,
        notifications: List<String> = emptyList(),
        /**
         * Phase 5: the frame path's trim counts for this turn, or `null` on the legacy path (which never trims a
         * frame). [TrimCounts] carries no text by type, so it is safe to hold; only its two counts are ever printed.
         */
        trim: TrimCounts? = null,
    ) {
        // A turn with no manager has no transcript to write: the counts below belong to a turn that was actually
        // recorded, so the early return comes first rather than logging a trim for a route this session never owns.
        val contexts = activeContextForTurn ?: return
        // The counts (and, since Task 12, the origin names) the frame path is allowed to log: how many whole turns
        // cut ① dropped, whether cut ③ is still owed, which request-scoped segments HARD shortened, and which whole
        // origins it gave up. Origin names only — never a segment's text; `-` means "none".
        trim?.let {
            val truncated = it.truncatedOrigins.joinToString(",") { origin -> origin.name }.ifEmpty { "-" }
            val dropped = it.droppedOrigins.joinToString(",") { origin -> origin.name }.ifEmpty { "-" }
            P3Log.line(
                "P3_CONTEXT trim route=$route droppedTurns=${it.droppedTurns} compressionOwed=${it.compressionOwed} " +
                    "truncated=$truncated dropped=$dropped",
            )
        }
        // §4.2/§6.2: a turn that reached a terminal state **always says something** ([submitOutcome]), so the
        // transcript records the same words the wearer got rather than only the user half. Recording the user half
        // alone left the route's transcript ending on an unanswered user message, and the model path refuses that
        // shape on purpose (`ContextBudgeter` rejects a trailing one-item group): on 2026-10-14 one failed turn made
        // **every** later turn fail with `ConfigurationRejected` until the app was restarted, and the same shape
        // explains the 08 session's unexplained `PLUGIN_RUN_THREW` pair (a turn cancelled by an early injection left
        // no reply behind either).
        //
        // §6.1: the wearer reaches the model only through `notify_user`, so the notification bodies are the assistant
        // turn's content - and `submitOutcome` suppresses the closing text exactly because it was never displayed.
        // Recording only `finalText` dropped every notification from the conversation (review M1's "没有任何地方把
        // 通知内容记进上下文"), so both halves are recorded here, in the order they happened.
        val closingText = run.finalText?.takeIf { it.isNotBlank() }
        val reply = when {
            notifications.isNotEmpty() -> (notifications + listOfNotNull(closingText)).joinToString("\n")
            closingText != null -> closingText
            else -> FAILURE_HINTS[run.outcome] ?: FAILURE_HINTS.getValue(AgentOutcome.FAILED)
        }
        val store = activeStores?.get(route)
        if (store != null) {
            // Phase 5's production path: the route's own store is the transcript. Order is the spec's (memory write →
            // transcript record → compression): this is called after `rememberFromTurn`.
            recordStoreTurn(store, userText, run, reply)
            // D-4's ③ gate: cut ③ may start **only after cut ① really happened**. `compressionOwed` alone is also true
            // for an over-budget frame whose flow holds no turn to give (an empty store, or memory as the only thing
            // trimmed), and a summary started there would be invented content.
            val owed = trim != null && trim.droppedTurns > 0 && trim.compressionOwed
            activeCompressor?.compressStore(store, owed)
            return
        }
        val context = contexts.context(route)
        context.appendUser(userText)
        contexts.appendAssistantMessage(route, reply)
        activeCompressor?.compressIfNeeded(context)
    }

    /**
     * Records the turn that just ended into the route's store (0.25 spec §5.1).
     *
     * The run hands over its own transcript — this turn's user text, every tool call/output and the loop's closing
     * text — so the store keeps the whole multi-step turn instead of only its two text halves. A `device` action
     * output here is the loop's already-redacted form (`(perception content not persisted)`, no images), not the raw
     * screen/notification content the model saw during the run (0.3 Phase 2 C2); every other tool's output — image
     * references included — is stored as the model saw it. The transcript is this turn **on top of** the history the
     * request started from (the plugin passes the store itself as `AgentRequest.history`), so only the tail past the
     * stored items is new: appending the whole list would duplicate the session on every turn.
     *
     * Two kinds of item are filtered out of that tail before it is stored, because neither is conversation:
     *
     * - **Incomplete tool rounds** ([ConversationStore.withoutIncompleteToolRounds]): a `STEP_LIMIT`/cancel can leave
     *   a call with no output. Filtering happens **before** [closeStoreTurnWithReply] so a turn that ended on a
     *   dangling call ends on the loop's assistant opener instead, and the reply replaces it. Appending the reply
     *   first and letting the store drop the call afterwards would leave two adjacent assistant texts — the shape
     *   `ContextBudgeter` refuses, which is how one step-limited turn used to make every later turn fail.
     * - **Retry scaffolding** ([AgentLoop.withoutRetryScaffolding]): the retry instruction and its assistant
     *   placeholder are loop-internal (ruling R16), not wearer/assistant speech.
     *
     * The assistant half is written from [reply] rather than copied verbatim. When a tool round precedes the final
     * answer the loop does not append that text to its transcript at all (the run ends on the last `ToolOutput`), and
     * when it does close a turn the appended text is a wire placeholder for an empty/failed model reply. [reply] is
     * the sentence the wearer actually received (notification bodies + closing text, or the stable failure hint), so
     * the trailing assistant text is **replaced**, not kept beside it.
     *
     * Falls back to the pre-0.25 `appendUser`/`appendAssistant` pair when the run never produced a transcript (a
     * transport failure makes the plugin's catch build an empty `AgentRun`), when the transcript does not continue
     * the store it was built from (the asynchronous compressor may have changed the store mid-run), or when the
     * filters leave nothing to attribute. The loop's C2 redaction is limited to this run's suffix for exactly this
     * check: an earlier turn's output keeps its body even when this run reuses its call id, so a legal id reuse
     * cannot make the prefix mismatch and drop the current round. Losing the turn's tool round is worse than falling
     * back, but losing the turn itself would be worse still.
     */
    private fun recordStoreTurn(
        store: ConversationStore,
        userText: String,
        run: AgentRun,
        reply: String,
    ) {
        val pinned = store.history()
        val transcript = run.transcript
        val turn = if (transcript.size > pinned.size && transcript.subList(0, pinned.size) == pinned) {
            transcript.drop(pinned.size)
        } else {
            emptyList()
        }
        val conversation = AgentLoop.withoutRetryScaffolding(
            ConversationStore.withoutIncompleteToolRounds(turn),
        )
        if (conversation.isEmpty()) {
            store.appendUser(userText)
            store.appendAssistant(reply)
            return
        }
        store.appendTranscript(closeStoreTurnWithReply(conversation, reply))
    }

    /**
     * The turn as the store should keep it: the filtered run items with [reply] as the closing assistant text.
     *
     * The caller has already dropped incomplete tool rounds and retry scaffolding, so a turn that ended early ends on
     * the loop's assistant opener (replaced here) or on its last complete `ToolOutput` (where the reply is appended).
     * The loop's own last assistant text is either the same closing text (a plain answer) or a wire placeholder
     * (`(no reply)` / a partial reply the wearer never got); in both cases [reply] is the authoritative record of the
     * assistant half. A trailing `ToolOutput` has no text to replace, so the reply is simply appended.
     */
    private fun closeStoreTurnWithReply(
        turn: List<ModelHistoryItem>,
        reply: String,
    ): List<ModelHistoryItem> {
        val last = turn.lastOrNull()
        val kept = if (last is ModelHistoryItem.Text && last.role == MessageRole.ASSISTANT) turn.dropLast(1) else turn
        return kept + ModelHistoryItem.Text(MessageRole.ASSISTANT, reply)
    }

    /**
     * The turn's tool registry: the host's action set plus `notify_user` (B7 — the model's only channel to the wearer,
     * spec §6.1) and `load_skill` (Phase 6 — the catalog's only way to a body, design D-1/D-2). Built per turn so the
     * frame's table and the loop's registry are literally the same table.
     *
     * `load_skill` is the plugin's **one** tool instance (see [loadSkillTool]); this method must never construct a new
     * one, or the counter receipt restarts and `ProgressGuard` mistakes a working pull for `NO_PROGRESS`.
     *
     * edith-0.3 Phase 4A Task 4 (A4): the host half is the 22-descriptor `ToolSurface` (18 atomic + 4 umbrella), so this
     * registry is the 24-tool default face. The count-only `P4_TOOLFACE` line reports `tools` (the model-visible tool
     * count after `ToolRegistry`'s duplicate merge) and `duplicate` (how many declarations that merge absorbed; 0 on the
     * pinned Phase 4A face). No tool name, argument or content may reach the line (D117).
     *
     * The line is a **session** fact, not a per-build one: the registry is rebuilt every turn, while the face it
     * describes is pinned for the session, so [toolFaceLoggedSession] emits it once and again only for a new session.
     */
    private fun registryFor(context: PluginContext, owned: SmartTurn, ledger: NotifyLedger): ToolRegistry {
        val executors = listOf(
            // 0.25 Task 11 (R52): every host action passes the precheck gate first. `notify_user`/`load_skill` are
            // added beside the wrapper, not inside it, so they never grow a command-level confirmation.
            ConfirmingActions(
                host = context.services.actions(),
                confirmation = confirmation,
                canAsk = { confirmation.canAsk },
                // 0.3 Phase 4B (D189): the same live provider the policy reads, so one save governs both gates.
                noInterception = noInterception,
            ),
            // B7: `notify_user` is the model's only channel to the wearer (spec §6.1). It rides the action
            // registry rather than a new tool port, and `NotifyUserTool`'s KDoc records what that choice costs.
            NotifyUserTool { channel, text -> onNotify(owned, ledger, channel, text) },
            loadSkillTool,
        )
        val registry = ToolRegistry(executors)
        val declaredNames = executors.flatMap { executor -> executor.descriptors() }.map { it.name }
        val faceLine = "P4_TOOLFACE tools=${registry.descriptors().size} " +
            "duplicate=${declaredNames.size - declaredNames.toSet().size}"
        synchronized(toolFaceLock) {
            if (toolFaceLoggedSession != context.sessionId) {
                P3Log.line(faceLine)
                toolFaceLoggedSession = context.sessionId
            }
        }
        return registry
    }

    /** The registry's tools in wire shape, which is what a [RequestFrame] carries. */
    private fun toolSpecsOf(registry: ToolRegistry): List<ToolSpec> =
        registry.descriptors().map { ToolSpec(it.name, it.description, it.parametersJson) }

    /** The gateway one turn sends through; held by `runTurn` so the frame path's trim counts can be read afterwards. */
    private fun gatewayFor(context: PluginContext, owned: SmartTurn): ModelClientGateway = ModelClientGateway(
        client = context.services.model(),
        profileLookup = profileLookup,
        clock = clock,
        scheduler = scheduler,
        envelopeFor = { requestId, startNanos, endNanos ->
            EventEnvelope(context.sessionId, context.generation, requestId, startNanos, endNanos)
        },
        // 0.3 Phase 2 Task 11: one `P3_CACHE` line per model request, including this run's tool rounds and overflow
        // re-sends. The gateway owns the line so the proactive path reports the same shape.
        stop = { owned.closed },
        metricLine = P3Log::line,
    )

    /**
     * 0.25 Task 12 (R59): the one runtime-state line the agent loop folds into its next request, or `null`.
     *
     * It is read through the **scoped** executor this plugin was granted, which is why the host's wrapper forwards
     * `runtimeState()` (R81): without that forwarding the interface default `null` would be all the plugin ever saw and
     * the feature would be silently dead in production.
     *
     * The line is pure state. A missing, blank or oversized line contributes nothing (the loop's `TurnRuntimeState`
     * cap is the same bound), and a provider that throws is a state problem, never a failed turn — the wearer still
     * gets an answer. Nothing here logs or stores the text: it travels only inside the assembled request.
     */
    private fun runtimeStateForTurn(): TurnRuntimeState? {
        val line = runCatching { currentContext()?.services?.actions()?.runtimeState() }.getOrNull() ?: return null
        val text = line.trim()
        if (text.isEmpty() || text.length > TurnRuntimeState.MAX_CHARS) return null
        return TurnRuntimeState(text)
    }

    /**
     * Phase 5: the frame for **one** model request, built from the transcript that request continues.
     *
     * This is the builder `AgentRequest.frameFor` calls for every request of the run, which is what keeps a tool round's
     * second request from losing the session: the plugin composes the system slot, the memory it captured once for the
     * turn ([prompt]'s segments), the given transcript, and this turn's utterance **last**. The transcript arrives
     * without the **pending** utterance (a frame renders that as `USER_INPUT`; the loop appends it to the transcript
     * only once a tool round or a retry has moved past it), so a builder that also added it would ask the model twice.
     *
     * The transcript's items are mapped back to segments with their roles preserved — tool rounds stay `TOOL_ROUND`, so
     * `TurnTrimmer`'s whole-turn boundary is still visible. No text is folded together here: that stays
     * `frameToTranscript`'s single job.
     *
     * ## One question, one input (0.25 Task 26, R140)
     *
     * The pending-utterance rule above holds only for the **opening** request. A tool round or a retry appends after the
     * utterance, so the loop keeps it in the transcript (it is now an interior turn of the run) and the frame receives
     * it at [pinnedCount]. Appending the same text as `USER_INPUT` again would put it in the request history **and** in
     * the input — the gateway maps everything before the input into history — so the model would read the question
     * twice. In that case the frame's one `USER_INPUT` is [CONTINUE_AFTER_TOOL] instead (see [continuationInput]); the
     * opening request still carries the wearer's own text. The detection is positional and exact-text, and any doubt
     * keeps the pre-Task-26 behaviour: a repeated question is better than a lost one.
     *
     * ## The two items the flat transcript cannot attribute
     *
     * A `List<ModelHistoryItem>` carries no [SegmentOrigin], and two items need one that its text cannot supply:
     * the store's **head summary** and the loop's **retry instruction**. Both are recovered from the store's own
     * metadata and from position — never from what the text says, so no user content can be reclassified by its
     * wording. The store answers the summary ([ConversationStore.headIsSummary], a summary is only ever at index 0),
     * and the retry is the bare user text the loop appended **past** the store's pinned history (see [retryIndexIn]).
     */
    private fun frameBuilderFor(
        store: ConversationStore,
        route: ContextRoute,
        promptRoute: PromptRoute,
        userInput: String,
        prompt: PromptAssembly?,
        tools: List<ToolSpec>,
    ): (List<ModelHistoryItem>) -> RequestFrame {
        // Where the store's items end: the utterance sits exactly here, and only the loop appends past it.
        val pinnedCount = store.history().size
        return { transcript ->
            val retryIndex = retryIndexIn(transcript, pinnedCount)
            // One snapshot per request (0.3 Task 12 review fix): the catalog in the system slot and the pulled bodies
            // in the tail are read together, because `beginSkillTurn` clears the bodies under the same lock. A begin
            // that landed between two separate reads would pair a newer catalog with the previous turn's bodies.
            val skills = requestSkillSegments(promptRoute)
            val frame = RequestFrame(
                systemSegments = skills.system,
                conversationFlow = buildList {
                    transcript.forEachIndexed { index, item ->
                        add(
                            when {
                                // A summary may occupy only the store's head, so index 0 is the only place this can be it.
                                index == 0 && store.headIsSummary() ->
                                    item.toSegment().copy(origin = SegmentOrigin.SUMMARY, trusted = false)
                                index == retryIndex -> item.toSegment().copy(origin = SegmentOrigin.RETRY)
                                else -> item.toSegment()
                            },
                        )
                    }
                    addAll(prompt?.memorySegments.orEmpty())
                    // The SKILL bodies are re-read every request: a `load_skill` pull earlier in this turn must be
                    // visible to the continuation request without rewriting the system slot.
                    addAll(skills.bodies)
                    add(
                        Segment(
                            SegmentOrigin.USER_INPUT,
                            trusted = true,
                            text = continuationInput(transcript, pinnedCount, userInput),
                        ),
                    )
                },
                tools = tools,
            )
            // No `P3_PROMPT` here: the builder runs **before** `AgentLoop` folds its RUNTIME_STATE/PERCEPTION
            // segments in, so a line emitted from this lambda would under-count the tail. The loop's
            // `AgentRequest.onRequestBuilt` hook emits the one line per assembled request instead (review fix R12).
            frame
        }
    }

    /**
     * R140: the text of the frame's one `USER_INPUT` for [transcript].
     *
     * The loop appends this turn's utterance to the caller's history itself, at the store's pinned boundary. While that
     * utterance is still the pending turn, the loop's `frameTranscript` excludes it from the transcript and the frame
     * must render it as `USER_INPUT` (the opening request's behaviour). Once a tool round or a retry appended after it,
     * the loop keeps it in the transcript and the frame receives it here, at exactly [pinnedCount]; rendering the same
     * text as `USER_INPUT` again would duplicate the question across the request's history and input.
     *
     * The check is deliberately narrow: the item at the pinned index must be a **user** [ModelHistoryItem.Text] whose
     * text is byte-identical to [userInput]. Anything else — an out-of-range index (the pending first request), a
     * different role, a different text — keeps the original utterance as the input, so an unrecognised shape can only
     * repeat the question (the pre-R140 behaviour), never lose it. The cue is a fixed constant and carries no user
     * content.
     */
    private fun continuationInput(
        transcript: List<ModelHistoryItem>,
        pinnedCount: Int,
        userInput: String,
    ): String {
        if (pinnedCount !in transcript.indices) return userInput
        val item = transcript[pinnedCount]
        return if (item is ModelHistoryItem.Text && item.role == MessageRole.USER && item.text == userInput) {
            CONTINUE_AFTER_TOOL
        } else {
            userInput
        }
    }

    /**
     * The index of the loop's retry instruction in [transcript], or `-1` when this request is not a retry continuation.
     *
     * Positional and store-relative, never textual. The loop appends at most one bare user text after this turn's
     * utterance: its retry instruction. Everything at or before [pinnedCount] came from the store, and the utterance
     * sits exactly at that index, so a user text that is **past** it *and* is the transcript's last item is the loop's
     * own continuation — a loop-internal instruction, not the wearer speaking. An **earlier** retry is not selected:
     * the model has already seen it, so it stays an ordinary user turn in the transcript, which is also what keeps the
     * wire shape alternating.
     */
    private fun retryIndexIn(transcript: List<ModelHistoryItem>, pinnedCount: Int): Int {
        val last = transcript.lastIndex
        if (last <= pinnedCount) return -1
        val item = transcript[last]
        return if (item is ModelHistoryItem.Text && item.role == MessageRole.USER) last else -1
    }

    /**
     * The two SKILL layers of one request, copied in **one** critical section of [skillLock].
     *
     * 0.3 Task 12 review fix (Important 2): the catalog is the system half and the pulled bodies are the tail half, but
     * they are one view of one turn. [beginSkillTurn] clears the bodies under the same lock, so reading the two in
     * separate critical sections could produce a catalog from turn N+1 paired with the bodies of turn N. This snapshot
     * is what [requestSkillSegments] hands to both halves of a request.
     *
     * The catalog is built here because it mutates the session's `SkillManager` governor, which documents that it is
     * not thread-safe (spec §3.7/design D-1) — the same reason every other manager call runs under this lock.
     */
    private class SkillSnapshot(val catalog: String, val bodies: List<Skill>)

    /** One request's two SKILL layers, both derived from a single [SkillSnapshot]. */
    private class RequestSkillSegments(val system: List<Segment>, val bodies: List<Segment>)

    /**
     * The system slot both frame paths share: exactly two stable things (0.3 design §4.4, §5.1).
     *
     * - the route's **session-pinned** `PromptPlan.systemText` as `INSTRUCTIONS`. The plan already contains the core
     *   sections, the route posture and the rendered device profile, including the memory policy
     *   (`core/50-memory-policy.md`), which is why the old separate `MEMORY_POLICY` segment is gone: keeping it would
     *   state the rule twice.
     * - **one** SKILL **catalog** segment, when the library is non-empty. The catalog is [SkillSnapshot.catalog], taken
     *   together with the pulled bodies; a pulled body is request-scoped `SKILL_BODY` flow content
     *   ([RequestSkillSegments.bodies]), never system text: a pull must not rewrite the cacheable prefix.
     *
     * A library that throws contributes no catalog segment rather than failing the request (spec §3.7).
     */
    private fun systemSlot(promptRoute: PromptRoute, skills: SkillSnapshot): List<Segment> = buildList {
        add(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = promptPlan(promptRoute).systemText))
        if (skills.catalog.isNotBlank()) {
            add(Segment(SegmentOrigin.SKILL, trusted = true, text = skills.catalog))
        }
    }

    /**
     * The bodies pulled with `load_skill` this turn, in pull order, as `SKILL_BODY` segments for the request tail.
     *
     * They are the dynamic half of the two-layer SKILL design (0.3 design §4.4/§5.1): request-scoped content that
     * changes only the tail, before `USER_INPUT`, so the system prompt and the cacheable history stay stable when a
     * skill is loaded. The body travels whole, never summarised (D-2). The list is the [SkillSnapshot.bodies] copy, so
     * it belongs to the same snapshot as the catalog in the system slot.
     */
    private fun skillBodySegments(skills: SkillSnapshot): List<Segment> = skills.bodies.map { skill ->
        Segment(SegmentOrigin.SKILL_BODY, trusted = true, text = bodySegmentText(skill))
    }

    /**
     * Both SKILL layers of one request, read as one snapshot (0.3 Task 12 review fix, Important 2).
     *
     * A frame builder calls this **once** and uses both halves; the pairing is the contract, not an implementation
     * detail: a concurrent [beginSkillTurn] may run wholly before or wholly after this call, never between its halves.
     */
    private fun requestSkillSegments(promptRoute: PromptRoute): RequestSkillSegments {
        val skills = skillSnapshot()
        return RequestSkillSegments(
            system = systemSlot(promptRoute, skills),
            bodies = skillBodySegments(skills),
        )
    }

    /**
     * Reads the session-pinned catalog and the pulled bodies under one acquisition of [skillLock].
     *
     * The catalog half is the string [activate] pinned: rebuilding it here would let a directory edit rewrite the
     * cacheable system slot mid-session (0.3 design §4.4/§5.1). The pull half is turn state and is still cleared at
     * each turn boundary, so the two halves still belong to one moment of one turn.
     */
    private fun skillSnapshot(): SkillSnapshot = synchronized(skillLock) {
        SkillSnapshot(
            catalog = pinnedSkillCatalog,
            bodies = loadedSkillBodies.values.toList(),
        )
    }

    /**
     * One pulled body as its own `SKILL_BODY` segment. The heading names the entry so the model can tell which body
     * this is when it pulled more than one this turn; the body follows verbatim.
     */
    private fun bodySegmentText(skill: Skill): String = "## SKILL 正文: ${skill.name}\n${skill.body}"

    /** Builds the single-use loop for one turn from the registry and gateway its caller already built. */
    private fun buildLoop(configured: Config, registry: ToolRegistry, gateway: ModelClientGateway): AgentLoop =
        AgentLoop(
            gateway = gateway,
            registry = registry,
            policy = WhitelistActionPolicy(configured.maxLevel, configured.whitelist, noInterception),
            guard = ProgressGuard(),
            audit = audit,
            clock = clock,
            // P3.3: the loop asks before it acts on anything the policy flagged, and never acts without an answer.
            confirmation = confirmation,
            // 0.25 Task 14 (R78): the run's one count-only metric line reaches the plugin's single P3 sink.
            metricLine = P3Log::line,
            // 0.3 Phase 4A A7: all loop prose is the plugin catalog's authored resource, never the runtime's fallback.
            retryInstruction = PromptCatalog.loop("retry"),
            planNudgeInstruction = PromptCatalog.loop("plan-nudge"),
            timeoutInstruction = PromptCatalog.loop("timeout"),
        )

    // ---------------------------------------------------------------- proactive turn (P3.2)

    /**
     * Whether this final transcript should become a proactive request.
     *
     * Must hold [gate]; returns the trigger to run, or null after recording why not. The admission itself is
     * [SmartProactive]'s (Task 7), and this method's own contributions are the two facts only the plugin knows: the
     * remembered perception (label, foreground) and whether another turn is in flight.
     *
     * A skipped trigger is logged with its reason - including `DISARMED`, which is the trace of the host not producing
     * perception. It is one cheap local line per utterance, and it is the only evidence the device log has that the
     * gate is where the spec says it is.
     */
    /**
     * Whether the host is armed **right now**.
     *
     * [perceptionArmed] only proves that a perception event arrived once; it cannot notice the switch being turned
     * off afterwards, and local transcription deliberately continues while it is off - so without this the off switch
     * would not stop proactive uploads (a review finding: the switch is the user's only in-session control).
     *
     * The host publishes its current state as [PROACTIVE_ARMED_KEY]; that answer wins when the provider is wired, and
     * the sticky flag is the fallback for a host without one (and for the plugin's own tests).
     */
    private fun armedNow(): Boolean {
        val flag = runCatching {
            currentContext()?.services?.context()?.snapshot()?.values?.get(PROACTIVE_ARMED_KEY)
        }.getOrNull()
        return when (flag) {
            null -> perceptionArmed
            "true" -> true
            else -> false
        }
    }

    /** Must hold [gate]; returns the trigger to run, or null after recording why not. */
    private fun proactiveTrigger(revision: TranscriptRevision): ProactiveTrigger? {
        if (!revision.final) return null
        val text = revision.text.trim()
        if (!isUsableUtterance(text)) return null
        return admitAndGate(text)
    }

    /**
     * Must hold [gate]; the `ContextChanged` entry point (spec §4.7).
     *
     * It re-judges the **last** utterance against the new ambient state. The state's leading field is the transcript,
     * so with no utterance behind it there is nothing to judge and this answers nothing rather than asking the gate
     * the same empty question once per context change.
     */
    private fun proactiveTriggerFromContext(): ProactiveTrigger? {
        val text = lastFinalTranscript ?: return null
        if (!isUsableUtterance(text)) return null
        return admitAndGate(text)
    }

    /**
     * The shared tail of both proactive entry points: P3's admission and throttle first, then the intervention gate.
     *
     * Must hold [gate]. The order is the spec's (C11): arming, sensitivity, cooldown and dedup are the *first* stage,
     * and only a candidate that passed them is worth spending a local-model judgement on.
     */
    private fun admitAndGate(text: String): ProactiveTrigger? {
        val values = currentContextValues()
        val trigger = ProactiveTrigger(text, lastAcousticLabel, values[FOREGROUND_KEY], clock.nowNanos())
        // "Busy" means a turn is genuinely in flight - waiting for its transcript, running its loop, or a proactive
        // request on the wire. A turn that has **finished** stays installed as `turn` until something replaces or
        // stops it (the wake path does that on purpose, so a late ACK still finds its identity), and counting it as
        // busy would refuse every proactive trigger for the rest of the session. Found on the K40: after one wake turn
        // ended, the first real utterance produced `skip reason=BUSY` instead of a request.
        //
        // The PROACTIVE follow-up window is a live conversation too (spec §4.5): its 10 s round, the accepted
        // follow-up hand-off, a running/awaiting FOLLOWUP_TURN **and an answer that has not settled into its
        // continuation yet** must not be overlapped by a hint turn. The answer emit clears `followupBusy` (the busy
        // ring stops exactly when the sentence appears), so between that emit and the continuation's `Displayed` ACK
        // the installed FOLLOWUP_TURN is the only busy marker. Without it, a foreground-changing `ContextChanged`
        // after the (configurable) hint cooldown starts a HINT_TURN whose own `Displayed` ACK installs a new follow-up
        // window over the continuation (final review Important 2).
        val followupLive = proactiveFollowup != null || followupTurnRequest != null ||
            followupBusy || followupTurn != null
        val busy = turn?.let { it.phase != SmartTurnPhase.Idle } == true || proactiveInFlight || followupLive
        val skip = proactive.admit(trigger, armed = armedNow(), busy = busy)
        if (skip != null) {
            // B10 / spec §4.6: WAKE outranks PROACTIVE, and a trigger refused *only* because a wake turn is in flight
            // is **paused rather than discarded**. Every other reason is a decision about this trigger and stands.
            // The slot holds one trigger: an unbounded queue would let a long wake turn accumulate observations that
            // are stale by the time it runs, and the newest one is the one that describes the world now.
            if (skip == ProactiveSkip.BUSY) {
                parkedProactive = trigger
                P3Log.line("P3_PROACTIVE park reason=BUSY")
            } else {
                P3Log.line("P3_PROACTIVE skip reason=$skip")
            }
            return null
        }
        // P3.5 §W3: an admitted trigger says **which features** let it through, so a device log can answer "why did this
        // one fire" without the transcript (feature **names**, never the text). The rule is deterministic, so re-reading
        // it here reports the same verdict `admit` just used; recomputing is cheaper and clearer than threading the
        // verdict out through the cooldown/dedup branches, which return their own reasons.
        val verdict = admitByFeatures(trigger.transcript, trigger.foreground, proactive.minTranscriptCharsBoundary)
        P3Log.line(
            "P3_PROACTIVE admit score=${verdict.score} " +
                "features=${verdict.fired.joinToString(",") { it.name }}",
        )
        return trigger
    }

    /**
     * The gate this turn will use, or `null` when this plugin has none.
     *
     * An injected gate wins — that is the test seam — and otherwise a configured [gateForm] builds one from the
     * host's decision model. Built per call rather than cached: the capability is the host's to withdraw, and a
     * cached gate would keep answering from a service the session no longer holds.
     */
    private fun activeGate(): ProactiveGate? {
        interventionGate?.let { return it }
        val form = gateForm ?: return null
        val service = runCatching { currentContext()?.services?.systemOne() }.getOrNull() ?: return null
        return when (form) {
            // The operating point is named here rather than left to the constructor default, so the value the
            // production gate runs at is visible at the one place the gate is built.
            GateForm.NOUL -> NoulGate(
                service,
                threshold = com.sheen.rokid.phone.plugin.smarthelper.proactive.NoulThreshold.VALUE,
                question = PromptCatalog.pinned("proactive-gate"),
            )
            // 0.3 Phase 4A A7: the calibrated question and its neutral options are frozen authored resources; a
            // paraphrase here would be a question the fine-tune never saw.
            GateForm.CHOICE -> ChoiceGate(
                service,
                question = PromptCatalog.pinned("proactive-gate"),
                options = parseGateOptions(PromptCatalog.pinned("proactive-gate-options")),
            )
        }
    }

    /**
     * Phase 6 (obligation 1): open the current turn's SKILL observation, once, at the turn's assembly point, and return
     * the **generation** that owns it (`0` for a turn that only joins another one).
     *
     * Two modes (review fix, turn ownership by generation):
     * - **owner** — the wake route. Takes the next generation, becomes the current owner, and clears the pulled bodies:
     *   a new turn starts with none. A proactive turn that arrives with no live owner also owns its own turn, because
     *   nothing else would ever close its observation.
     * - **joiner** — the proactive route beside a live wake turn. Calls `governor.beginTurn()` only. It has no
     *   `load_skill` tool, so it never contributes a body, and it must never wipe the bodies of the turn that is still
     *   running.
     *
     * Everything runs under [skillLock], because the governor is not thread-safe and a stopped turn may be unwinding on
     * the other pool thread at the same moment. Guarded because a library that refuses to list must not fail the turn
     * (spec §3.7): the governor then simply learns nothing and the request goes out without a catalog segment.
     */
    private fun beginSkillTurn(owner: Boolean): Long = synchronized(skillLock) {
        // A **joiner** (the proactive route, which has no `load_skill` tool and therefore never pulls a body) arrived
        // while another turn owns the lifecycle. It still observes — the observation unit is the turn — but it must
        // not clear the live turn's pulled bodies and must not take ownership: its own end must not be able to close
        // or clear the turn that is still running.
        //
        // A proactive turn that begins with **no** owner is not a joiner: nothing else would ever close its
        // observation, and leaving the governor's turn open would fold it into the next wake turn (D-3's
        // one-observation-per-turn). So it owns its own generation and gives it back at its own end.
        if (!owner && skillTurnOwner != 0L) {
            runCatching { skillManager?.beginTurn() }
            detectSkillCatalogChange()
            return 0L
        }
        val generation = ++skillTurnGeneration
        skillTurnOwner = generation
        // An owner begin starts a turn with no pulled bodies. The previous owner (if any) is being replaced; its late
        // end is made harmless by the generation check, not by this clear.
        loadedSkillBodies.clear()
        runCatching { skillManager?.beginTurn() }
        // 0.3 final-fix wave: the change is detected at the **turn** boundary, never per request, and it is reported
        // (`P3_SKILL catalog_changed`, hashes only) without rewriting the pinned system text. Spec §4.4 allows either
        // "takes effect next session" or an explicit signal with one accepted cache invalidation; this session chose
        // the former, so the trace is the whole observable effect.
        detectSkillCatalogChange()
        generation
    }

    /**
     * Names a detectable SKILL-catalog change once per session, under [skillLock] (the governor is not thread-safe).
     *
     * The comparison read is the only per-turn directory read this fix adds; the catalog **text used by requests**
     * stays the activation pin. A broken or revoked library is a no-op, exactly like the activation read.
     */
    private fun detectSkillCatalogChange() {
        val manager = skillManager ?: return
        val current = runCatching { manager.catalog() }.getOrNull() ?: return
        if (skillCatalogChangeLogged || current == pinnedSkillCatalog) return
        skillCatalogChangeLogged = true
        P3Log.line("P3_SKILL catalog_changed pinned=$pinnedSkillCatalogHash current=${sha256Hex(current)}")
    }

    /**
     * Phase 6 (obligations 2 and 5): close the turn [beginSkillTurn] opened — on every ending of the turn and before
     * its outcome is submitted.
     *
     * Only the **current owner** may close: generation `0` (a joiner) or any other generation (a turn that has already
     * been replaced, only now unwinding) is a no-op. That check is the whole fix — a stopped turn's late end must not
     * clear the live turn's bodies or advance its governor turn.
     *
     * For the owner the order is the design's (D-2, design §5): clear the pulled bodies (their lifetime is this turn),
     * advance the governor once, then report the count-only `P3_SKILL pulls` line (D-10). All of it happens under
     * [skillLock]; the report is emitted after the lock is released.
     */
    private fun endSkillTurn(generation: Long) {
        val report = synchronized(skillLock) {
            if (generation == 0L || generation != skillTurnOwner) return
            loadedSkillBodies.clear()
            val manager = skillManager
            runCatching { manager?.endTurn() }
            // The counts are read under the same lock: the governor is documented not thread-safe, and its between-turns
            // state is exactly what `pullsThisTurn`/`neverPulledCount` read.
            val counts = Triple(
                manager?.pullsThisTurn ?: 0,
                manager?.pullsThisSession ?: 0,
                runCatching { manager?.neverPulledCount() }.getOrNull() ?: 0,
            )
            skillTurnOwner = 0L
            counts
        }
        // Counts only, no names and no bodies — and outside the lock, like every other report in this file.
        P3Log.line("P3_SKILL pulls turn=${report.first} session=${report.second} neverPulled=${report.third}")
    }

    // ---------------------------------------------------------------- SKILL turn test seams

    /**
     * Test-only: open the SKILL turn boundary directly and return the generation the turn owns.
     *
     * The defect this models cannot be produced through the event flow deterministically: a stopped turn's **late**
     * end has to land after a newer turn already owns the lifecycle, and a proactive turn has to join a wake turn
     * that is still in flight. These hooks call the production methods themselves (never a copy), so the test pins
     * the real boundary rather than a re-implementation of it. No production caller exists.
     */
    internal fun beginSkillTurnForTest(owner: Boolean): Long = beginSkillTurn(owner)

    /** Test-only: close the boundary opened by [beginSkillTurnForTest] with that turn's generation. */
    internal fun endSkillTurnForTest(generation: Long) = endSkillTurn(generation)

    /**
     * Test-only: the plugin's one `load_skill` executor, so a test can drive a pull through the same `onLoaded` write
     * the model's tool call makes instead of manufacturing a body into the turn's map.
     */
    internal fun loadSkillToolForTest(): LoadSkillTool = loadSkillTool

    /**
     * Test-only: runs in the lifecycle gap between installing the PROACTIVE window and arming its cycle round, i.e.
     * exactly where the host can deliver `PANEL_SHOWN`/`UI_CLOSED` on another thread. Production never sets it.
     */
    internal var beforeFollowupWindowAttachForTest: (() -> Unit)? = null

    /**
     * Test-only: the PROACTIVE follow-up window's turn id, or `null` when no window is armed.
     *
     * The window is session state and has no host-facing accessor; this lets the JVM suite prove "armed" and "cleared"
     * without inventing an event that no production path produces.
     */
    internal fun followupWindowTurnIdForTest(): String? = synchronized(gate) { proactiveFollowup?.turnId }

    /** Test-only: the accepted follow-up hand-off (turn id to text), or `null` when none was accepted. */
    internal fun followupTurnForTest(): Pair<String, String>? = synchronized(gate) {
        followupTurnRequest?.let { it.turnId to it.text }
    }

    /** Test-only: the PROACTIVE follow-up turn slot, or `null` when no follow-up turn is installed. */
    internal fun followupTurnStateForTest(): Pair<String, SmartTurnPhase>? = synchronized(gate) {
        followupTurn?.let { it.turnId to it.phase }
    }

    /**
     * Test-only: both SKILL layers of the request the plugin would build for [route], read through the same
     * [requestSkillSegments] the frame builders call.
     *
     * The pair is deliberate: a test that needs to prove the catalog and the pulled bodies belong to one moment must
     * read them together, because either half on its own can be observed after a turn boundary has legitimately cleared
     * the other (0.3 Task 12 review fix).
     */
    internal fun requestSkillSegmentsForTest(route: ContextRoute): Pair<List<Segment>, List<Segment>> {
        val skills = requestSkillSegments(route.toPromptRoute())
        return skills.system to skills.bodies
    }

    /** The gate's input, assembled from the same signals the request carries (spec §4.7). */    private fun gateState(trigger: ProactiveTrigger): ProactiveGateState {
        val values = currentContextValues()
        return ProactiveGateState.of(
            transcript = trigger.transcript,
            foreground = values[FOREGROUND_KEY],
            notification = values[NOTIFICATION_KEY],
            calendar = values[CALENDAR_KEY],
        )
    }

    /**
     * Opens one proactive **HINT_TURN** (0.3 Phase 3 spec §4.2) and hands its blocking half to the task scope.
     *
     * The turn is either a sentence or silence. After the intervention gate accepts, the blocking half runs an
     * `AgentLoop` whose only tool is the terminal `propose_hint`: the call is the decision to speak, it is never a
     * device action, and a successful call ends the run (see [runHintTurn]). The gate therefore sits **before** the
     * hint turn and is untouched by this task (Q1=A0: four-key state, no forced gate).
     *
     * The whole turn runs **outside** [gate]: it blocks on the transport, and `Stop` must be able to cancel it while it
     * waits. [ProactiveHintRequest] carries the identity and the cancellation flag the gateway's `stop` predicate reads;
     * [ProactiveHint] keeps owning the submitted-`Assistance` bookkeeping.
     */
    private fun runProactive(trigger: ProactiveTrigger) {
        val (current, configured, filter) = synchronized(gate) {
            val c = context ?: return
            val cfg = config ?: return
            Triple(c, cfg, proactive)
        }
        // 0.25 Task 10 (R42): the proactive route is an assistant turn too, and it may run beside a wake turn that
        // already cancelled/ended. Re-arm the turn-scoped executor before this route uses it.
        runCatching { current.services.actions().beginTurn() }
        // B9 applied at the other entry point. The proactive path is the one the decision model *is* the gate for, so
        // a proactive turn reaching the cloud model with no local model behind it would be the exact cost the
        // dependency exists to avoid. Same judgement, same code, same sentence as `runTurn`.
        serviceRefusal()?.let { reason ->
            P3Log.line("P3_DEPENDENCY reason=$reason")
            P3Log.line("P3_PROACTIVE silence reason=$reason ms=0")
            return
        }
        val started = clock.nowNanos()
        val (message, contextEntries) = proactiveMessage(trigger)
        val hint = ProactiveHint(hintId = "hint-${UUID.randomUUID()}", turnId = "proactive-${UUID.randomUUID()}")
        val request = ProactiveHintRequest(hintId = hint.hintId, turnId = hint.turnId, startedNanos = started)
        var replacedPanelWait: PendingHintPanel? = null
        var replacedHint: ProactiveHint? = null
        var replacedHintHadWindow = false
        synchronized(gate) {
            if (context !== current) return
            // A newer trigger may legitimately replace an older slot; the old run's gateway polls this flag as its
            // `stop` predicate, so marking it is what keeps the replaced turn from writing back.
            replacedHint = proactiveHint
            proactiveHint?.cancelled = true
            proactiveRequest?.cancelled = true
            // 0.3 Phase 3 Task 9d: a hint still waiting for the panel is replaced with the turn it belongs to.
            replacedPanelWait = pendingHintPanel
            pendingHintPanel = null
            replacedHintHadWindow = replacedHint != null && proactiveFollowup?.hint === replacedHint
            proactiveHint = hint
            proactiveRequest = request
            proactiveInFlight = true
        }
        replacedPanelWait?.abort(HintPanelAcquisition.CANCELLED)
        // 0.3 Phase 3 Task 9d fix (review Important 1): a replaced hint that already acquired its panel pre-armed a
        // listening round, and a newer trigger that stays silent never cancels it - the old round then closed the panel
        // at the 10 s deadline. Close it with the replaced hint's own id, so a newer attempt's round is untouched. A
        // hint still waiting for the panel is handled by its acquisition (release, or the submit early return); a
        // displayed hint with a live follow-up window cannot be admitted over (the busy rule), so it is left alone.
        val superseded = replacedHint
        if (replacedPanelWait == null && superseded != null && !replacedHintHadWindow) {
            conversationCycle.closeIfTurn(AssistantRoute.PROACTIVE, superseded.turnId)
        }
        // Phase C: the proactive route is now working, and the wearer's ring should say so from the trigger rather than
        // from the first thing that happens to arrive. Outside the lock, as `publishBusy` requires.
        publishBusy()
        P3Log.line("P3_PROACTIVE request ctx=$contextEntries chars=${trigger.transcript.length}")
        // Off the host's dispatcher: the hint turn blocks on the transport, and running it inside `handle` would hold
        // the host's single drain thread for up to the deadline - delaying `Stop` (and its UI thread) and the next
        // wake turn. The task re-checks ownership under `gate` before writing anything back. A test host runs the task
        // inline, which is what keeps the plugin's own tests deterministic.
        current.services.tasks.launch {
            // Phase 6 (obligation 1): the proactive turn's SKILL boundary opens here, before the blocking work, and
            // closes in the `finally` that every proactive ending passes through - a hint, a decline, a failure, a
            // cancellation or a throw. The hint turn has no `load_skill` tool, so its `begin` is a **joiner** whenever
            // a wake turn already owns the lifecycle: it must not clear that turn's pulled bodies. When it is alone it
            // owns its own turn, which is what gives it its own observation instead of folding it into the next wake
            // turn (design D-3).
            val skillGeneration = beginSkillTurn(owner = false)
            try {
                deliverProactive(current, configured, filter, request, hint, trigger, message)
            } finally {
                // Phase C: this is the one place every proactive ending passes through - a hint, a decline, a failure,
                // a cancellation - which is why the level is cleared here rather than on each branch. The report happens
                // outside the lock, as `publishBusy` requires.
                //
                // Phase 6 (obligations 2 and 5): the same is true of the proactive turn's SKILL bookkeeping. Closing it
                // here is what keeps the proactive turn and the next wake turn **two** observations instead of one
                // folded turn, and a stale close from a turn that has already been replaced is a no-op.
                endSkillTurn(skillGeneration)
                synchronized(gate) {
                    // The request's loop handle dies with the turn: a `Stop` that already cleared the slot cannot be
                    // holding it, and a later cancel must not reach a finished run.
                    if (proactiveRequest === request) proactiveRequest = null
                    request.loop = null
                    // Only the turn that still owns the proactive slot may clear the level. A STOP (or a newer trigger)
                    // can have replaced this turn while it was blocked; the old `finally` must not clear the new
                    // turn's BUSY level, which would send the glasses back to idle while work is still in flight.
                    if (proactiveHint === hint || proactiveHint == null) proactiveInFlight = false
                }
                publishBusy()
            }
        }
    }

    /**
     * The intervention decision of one proactive turn; runs on the task scope, never under [gate].
     *
     * The gate is the last decision **before** the hint turn, and that placement is load-bearing: it is a local-model
     * forward pass (~231 ms GPU / ~742 ms CPU per A5), and the plugin's own rule forbids one inside `handle` - it would
     * hold the host's single drain thread *and* the plugin lock, putting `Stop`, confirmations and the next wake turn
     * behind a model call. By this point the trigger has passed P3's admission and throttle (C11). A declined trigger
     * must cost nothing at all: no `AgentLoop` is built and no model request is made.
     */
    private fun deliverProactive(
        current: PluginContext,
        configured: Config,
        filter: SmartProactive,
        request: ProactiveHintRequest,
        hint: ProactiveHint,
        trigger: ProactiveTrigger,
        message: String,
    ) {
        val started = request.startedNanos
        activeGate()?.let { gate ->
            val outcome = gate.shouldIntervene(gateState(trigger))
            P3Log.line(
                "P3_PROACTIVE gate form=${gate.form} intervene=${outcome.intervene} " +
                    "confidence=${outcome.confidence} latencyMs=${outcome.latencyMs} " +
                    "reason=${outcome.reason ?: "-"}",
            )
            if (!outcome.intervene) {
                // A refusal restarts the cooldown, exactly as the first stage's refusals do: otherwise every
                // `ContextChanged` would spend another forward pass asking the same question.
                filter.noteRefused(clock.nowNanos())
                P3Log.line("P3_PROACTIVE silence reason=GATE_DECLINED ms=${elapsedMillis(started)}")
                discard(request, hint)
                return
            }
        }
        runHintTurn(current, configured, filter, request, hint, trigger, message)
    }

    /**
     * The blocking half of one **HINT_TURN** (0.3 Phase 3 spec §4.2): the gate has accepted, so this opens the
     * perception boundary, runs the single-use `AgentLoop` whose only tool is the terminal `propose_hint`, and turns
     * the run's `finalText` into the hint the wearer sees - or into silence.
     *
     * The loop is the point of the task: it gives the hint turn the shared cancellation, timeout, audit and result
     * envelope, while a terminal tool keeps the turn at one proposal and no device action. Its registry holds
     * **nothing but** `ProposeHintTool`, so even a model that names `device`/`open`/`shell`/`dial` cannot execute one.
     * `maxSteps = 1` bounds execution to the single proposal; a refused call may be fed back once (that is the loop's
     * shared behaviour), but a second call is refused before it runs.
     *
     * Two request shapes are assembled exactly as the wake route's: with a store, a `frameFor` builder renders every
     * request from the PROACTIVE transcript (system slot = the pinned `PROACTIVE_HINT` plan, memory tail, USER_INPUT
     * last) and `onRequestBuilt` emits the per-request `P3_PROMPT` line **after** the loop folds its
     * `RUNTIME_STATE`/`PERCEPTION` segments in; without a store, the legacy history path is preserved byte for byte
     * and there is no frame to describe. [AgentRequest.instructions] and the frame's system slot are the same pinned
     * plan, so the two cannot disagree about the hint posture.
     *
     * Perception (spec §7.5/§5.2): the host's `beginTurn()`/`endTurn()` pair wraps the **whole** hint turn, including
     * the gate-accepted run and every exit (a hint, silence, a cancellation, a throw). The opening request renders the
     * full snapshot (the `P3_PERCEPTION` line is emitted by the renderer); a continuation after a refused call renders
     * the ambient subset, exactly as the wake route does. `observationSource` is explicitly `null`: the hint turn never
     * runs a screen tool, so there is no failure for the loop to observe.
     */
    private fun runHintTurn(
        current: PluginContext,
        configured: Config,
        filter: SmartProactive,
        request: ProactiveHintRequest,
        hint: ProactiveHint,
        trigger: ProactiveTrigger,
        message: String,
    ) {
        val started = request.startedNanos
        val proactiveProfile = runCatching { profileLookup(configured.profileId) }.getOrNull()
        // Task 9's boundary protocol, applied to the hint route: one ticket per opened turn, serialised with every
        // other begin/end, and a superseded turn's late close is a no-op.
        val perception = activePerception
        val perceptionTurn = perception?.let { provider -> beginPerceptionTurn(provider) }
        val perceptionRequests = AtomicInteger(0)
        try {
            synchronized(gate) { if (request.cancelled) return }
            val gateway = ModelClientGateway(
                client = runCatching { current.services.model() }.getOrElse { failure ->
                    // `D53`①: no upload gate exists any more; a client that cannot be resolved is an honest turn
                    // failure, and the cooldown must still restart so a broken profile is not asked on every trigger.
                    filter.noteRefused(clock.nowNanos())
                    P3Log.line("P3_PROACTIVE silence reason=FAILED ms=${elapsedMillis(started)}")
                    discard(request, hint)
                    return
                },
                profileLookup = profileLookup,
                clock = clock,
                scheduler = scheduler,
                envelopeFor = { requestId, startNanos, endNanos ->
                    EventEnvelope(current.sessionId, current.generation, requestId, startNanos, endNanos)
                },
                // The one cancellation seam of the hint turn: `Stop`/deactivate set this flag and the gateway ends
                // the blocked request with CANCELLED instead of waiting for the transport's own terminal.
                stop = { request.cancelled },
                // 0.3 Phase 2 Task 11: the same count-only sink gives the hint route its per-request `P3_CACHE` line.
                metricLine = P3Log::line,
            )
            // B1/B3/B6 / spec §4.2: the route consumes the **session's own** assembly under `ContextRoute.PROACTIVE`
            // (the SKILL catalog arrives through the shared system slot and pulled bodies through the frame tail). The
            // manager is still `ContextRoute`-keyed and maps PROACTIVE -> PROACTIVE_HINT, which is this route's job
            // (Task 7 owns the follow-up mapping).
            val prompt = activeContextForTurn?.let { contexts ->
                runCatching { contexts.buildPrompt(ContextRoute.PROACTIVE, trigger.transcript) }.getOrNull()
            }
            // Phase 5: with a store injected the request is a `RequestFrame`: the PROACTIVE snapshot, the memory this
            // assembly retrieved, and the perception message last. The frame's user segment is `message`, not the
            // trigger: the trigger is what the transcript records (see `recordProactiveTurn`), while the message is
            // what this request asks the model about.
            val store = activeStores?.get(ContextRoute.PROACTIVE)
            val tools = listOf(ProactiveHintSpec.PROPOSE_HINT)
            val frameFor = store?.let {
                frameBuilderFor(it, ContextRoute.PROACTIVE, PromptRoute.PROACTIVE_HINT, message, prompt, tools)
            }
            // Phase 2 Task 10: the profile's own image capability; `null` (no profile) is not evidence that images are
            // unsupported, so only a real profile that omits `IMAGE` downgrades the rendered coverage.
            val profileAcceptsImages = proactiveProfile?.let { InputModality.IMAGE in it.inputs }
            val tool = ProposeHintTool()
            val loop = AgentLoop(
                gateway = gateway,
                registry = ToolRegistry(listOf(tool)),
                policy = WhitelistActionPolicy(configured.maxLevel, configured.whitelist, noInterception),
                guard = ProgressGuard(),
                audit = audit,
                clock = clock,
                // A hint turn may execute exactly one tool call: the terminal `propose_hint` *is* the answer.
                maxSteps = 1,
                metricLine = P3Log::line,
                // 0.3 Phase 4A A7: the same catalog loop prose as the wake/follow-up loop. A hint turn usually only
                // reaches the timeout branch, but no production loop may fall back to a compiled-in instruction.
                retryInstruction = PromptCatalog.loop("retry"),
                planNudgeInstruction = PromptCatalog.loop("plan-nudge"),
                timeoutInstruction = PromptCatalog.loop("timeout"),
            )
            synchronized(gate) {
                if (request.cancelled) return
                if (proactiveRequest === request) request.loop = loop
            }
            val run = try {
                loop.run(
                    AgentRequest(
                        turnId = request.turnId,
                        utterance = message,
                        // The brief's pinned identity: the *hint* plan, and the frame's system slot resolves to the
                        // same plan through `ContextRoute.PROACTIVE.toPromptRoute()`.
                        instructions = promptPlan(PromptRoute.PROACTIVE_HINT).systemText,
                        profileId = configured.profileId,
                        deadlineNanos = SmartTurn.saturatingAdd(
                            started,
                            ProactiveHintSpec.PROACTIVE_DEADLINE_MILLIS * 1_000_000L,
                        ),
                        // D49: the profile's timeout caps **one request**; the turn's own 15 s budget above is the
                        // machine deadline, and the loop sends the earlier of the two.
                        requestTimeoutNanos = modelRequestTimeoutNanos(proactiveProfile?.timeoutMillis),
                        maxOutputTokens = proactiveProfile?.maxOutputTokens,
                        limitTokens = turnHardLimit(proactiveProfile),
                        contextFailure = activeContextFailure,
                        // The store path hands its transcript over whole: the loop appends this turn's utterance and
                        // drops it again before calling `frameFor`, so the builder receives the session exactly as it
                        // stands. The legacy path keeps the old `dropLast(1)` convention.
                        history = store?.history() ?: prompt?.history?.dropLast(1).orEmpty(),
                        frameFor = frameFor,
                        // 0.25 Task 12 (R58): a hint is a proposal, never a plan to execute - no nudge here.
                        allowPlanNudge = false,
                        runtimeState = { runtimeStateForTurn() },
                        perception = perception?.let { provider ->
                            {
                                perceptionBlockFor(
                                    provider,
                                    perceptionTurn?.initialSnapshot,
                                    perceptionRequests,
                                    profileAcceptsImages,
                                )
                            }
                        },
                        onRequestBuilt = { frame -> logPromptRequest(PromptRoute.PROACTIVE_HINT, frame) },
                        // The hint turn never executes a screen tool; the loop's failure observation has no subject.
                        observationSource = null,
                    ),
                )
            } catch (failure: Throwable) {
                // `D53`① (2026-10-11): a transport/client refusal is an honest failure of this turn, and the cooldown
                // must restart - `noteRequest` runs only on a successful hint, so without this a profile the client
                // keeps refusing would be attempted on every final utterance inside one window.
                filter.noteRefused(clock.nowNanos())
                P3Log.line("P3_PROACTIVE silence reason=FAILED ms=${elapsedMillis(started)}")
                discard(request, hint)
                return
            } finally {
                synchronized(gate) { if (request.loop === loop) request.loop = null }
            }
            val elapsed = elapsedMillis(started)
            val decision = hintDecision(run, tool, request)
            val hintText = decision.text
            if (hintText == null) {
                silence(request, hint, decision.reason ?: "FAILED", elapsed)
                return
            }
            filter.noteRequest(clock.nowNanos())
            filter.noteDedup(trigger)
            P3Log.line("P3_PROACTIVE hint chars=${hintText.length} ms=$elapsed")
            // 0.3 Phase 3 Task 9d: a hidden panel is opened **before** the hint goes out. The glasses' `PANEL_OPEN`
            // resets the panel's conversation, and the CXR channel is unordered, so submitting first would let the
            // open wipe the sentence; waiting for `PANEL_SHOWN` is what proves the reset already happened.
            when (acquireHintPanel(hint)) {
                HintPanelAcquisition.ACQUIRED -> Unit
                HintPanelAcquisition.PANEL_NOT_SHOWN -> {
                    // No hint on an invisible panel: count-only evidence, and the deterministic hint turn ends here.
                    P3Log.line("P3_PROACTIVE hint skipped reason=panel_not_shown")
                    discard(request, hint)
                    return
                }
                HintPanelAcquisition.CANCELLED -> {
                    discard(request, hint)
                    return
                }
            }
            // B3/B6 / spec §4.9: the proactive route records its own turn into its own transcript and compresses it
            // against the same budget, **before** the hint is submitted. The order is the submission's, not the
            // compressor's: the compressor's truncation is what writes the dropped messages to memory, and the trigger
            // belongs to the conversation whether or not the display accepted it.
            recordProactiveTurn(trigger.transcript, hintText, hint.hintId, hint.turnId, gateway.lastTrim)
            submitProactiveHint(hint, hintText)
        } finally {
            // Every exit - a hint, silence, a cancellation, a throw - closes the perception boundary it opened.
            if (perception != null && perceptionTurn != null) endPerceptionTurn(perception, perceptionTurn.ticket)
        }
    }

    /** The text a hint run may show, or the reason it must stay silent. */
    private class HintDecision(val text: String?, val reason: String?)

    /**
     * The one sentence a hint run may show, or the reason it must stay silent.
     *
     * The `propose_hint` call is the decision to speak (spec §4.1's state machine: "the model does not call it →
     * silence"). A text-only reply is a legal silence even though `AgentLoop` reports it as `COMPLETED` with that text
     * as `finalText`, so the run must end on a **successful terminal `propose_hint` step** and only then may its
     * `finalText` become the hint. The sentence is re-validated here as well: the tool is the authority, but a
     * different final path must never be able to put an over-long or blank sentence on the glasses.
     *
     * The silence vocabulary keeps the P3.2 codes the device triage already reads (`NO_CALL`, `UNPARSABLE`, `BLANK`,
     * `TOO_LONG`), mapped from the tool's own rejection record; a run that failed for another reason reports the
     * loop's stable code (or its outcome name). A cancelled run always reports `CANCELLED`, whatever a refused attempt
     * earlier in it recorded.
     */
    private fun hintDecision(
        run: AgentRun,
        tool: ProposeHintTool,
        request: ProactiveHintRequest,
    ): HintDecision {
        if (request.cancelled || run.outcome == AgentOutcome.CANCELLED) return HintDecision(null, "CANCELLED")
        tool.lastRejection?.let { return HintDecision(null, it.name) }
        // The registry refuses arguments that are not parseable JSON **before** the tool runs, so that refusal has no
        // tool-side record and is named by the shared result code instead. It is checked before the outcome mapping
        // because the very next request after the feedback is refused by the one-step bound (`STEP_LIMIT`), which is
        // the turn's ending but not the reason the proposal never happened.
        val attempted = run.steps.firstOrNull { it.action == ProposeHintTool.NAME }
        if (attempted?.result == "ARGUMENTS_INVALID") return HintDecision(null, "UNPARSABLE")
        if (run.outcome != AgentOutcome.COMPLETED) {
            return HintDecision(null, run.safeCode ?: run.outcome.name)
        }
        val last = run.steps.lastOrNull()
        val proposed = last != null && last.action == ProposeHintTool.NAME && last.result == "ok"
        if (!proposed) {
            // A text-only reply is a legal silence; an attempt that reached the tool but did not end the run keeps its
            // own stable code.
            return HintDecision(null, attempted?.result ?: "NO_CALL")
        }
        val text = run.finalText
        return when {
            text.isNullOrBlank() -> HintDecision(null, "BLANK")
            text.codePointCount(0, text.length) > ProactiveHintSpec.PROACTIVE_HINT_MAX_CHARS ->
                HintDecision(null, "TOO_LONG")
            else -> HintDecision(text, null)
        }
    }

    /**
     * Records one spoken proactive turn into the PROACTIVE transcript, then compresses it (spec §4.9 steps ④→⑤).
     *
     * The **trigger** is what the route records as its user side, not the synthesized perception message: the message
     * is this turn's model input (perception is not something the wearer said), while the transcript is the
     * conversation, and a later proactive turn asking "what did we already talk about" must not be told that the
     * assistant heard a block of key-values.
     *
     * `finalText` is the hint itself, so the ordinary [recordTurnAndCompress] shape applies unchanged. Only a hint is
     * recorded: a turn that ended in silence said nothing, and a proactive turn has no memory-extraction candidate
     * (spec §5.5's habits are what the *wearer* keeps doing, which a proactive hint is not).
     */
    private fun recordProactiveTurn(
        triggerTranscript: String,
        hintText: String,
        hintId: String,
        turnId: String,
        trim: TrimCounts?,
    ) {
        if (triggerTranscript.isBlank()) return
        // Phase C: the trigger is what this route records as its user side (see this method's note above), so it is also
        // what the wearer's conversation shows as their own words on the proactive route.
        publishUserMessage(ContextRoute.PROACTIVE, triggerTranscript)
        publishAssistantFinal(ContextRoute.PROACTIVE, turnId, hintId, hintText)
        recordTurnAndCompress(
            ContextRoute.PROACTIVE,
            triggerTranscript,
            AgentRun(AgentOutcome.COMPLETED, null, hintText, emptyList()),
            trim = trim,
        )
    }

    /** The context message one proactive request carries, and how many perception entries it attached. */
    private fun proactiveMessage(trigger: ProactiveTrigger): Pair<String, Int> {
        val values = currentContextValues()
        // One entry per context value, plus the acoustic label. The foreground app is already one of the values, so it
        // is not counted twice (`ctx` is what the message carried, not a sum of overlapping sources).
        val entries = values.size + if (trigger.label != null) 1 else 0
        val message = buildString {
            append("用户没有对你说话。本回合的感知上下文：")
            append("\n最近一句转写：").append(trigger.transcript)
            trigger.label?.let { append("\n声学标签：").append(it.name) }
            trigger.foreground?.let { append("\n前台应用：").append(it) }
            values.toSortedMap().forEach { (key, value) -> append("\n").append(key).append("=").append(value) }
        }
        return message to entries
    }

    /**
     * The context the request carries: the provider's own snapshot, or the last delivered change when no provider
     * answers at all.
     *
     * The snapshot **wins outright**; it is not merged with the remembered change. Merging looked defensive and was
     * wrong: a key the host stopped reporting - a permission the user just revoked - survived in the merged map, so
     * the request still told the model about a foreground app the App may no longer see. Found on the K40 by revoking
     * usage access (`ctx` stayed high after `app.foreground` had left the host's own key line).
     *
     * Reading the provider here is also what drives the host's change detection - the composition reports a
     * `ContextChanged` when a snapshot differs from the previous one - so an unread context stays invisible.
     */
    private fun currentContextValues(): Map<String, String> {
        val remembered = synchronized(gate) { lastContextValues }
        val fresh = runCatching { currentContext()?.services?.context()?.snapshot()?.values }.getOrNull()
        return fresh ?: remembered
    }

    /** Records a proactive outcome that shows nothing, and releases the hint/request slots. */
    private fun silence(request: ProactiveHintRequest, hint: ProactiveHint, reason: String, elapsedMillis: Long) {
        P3Log.line("P3_PROACTIVE silence reason=$reason ms=$elapsedMillis")
        discard(request, hint)
    }

    /**
     * Releases one hint turn: the displayed-hint bookkeeping and the loop request, but only while this turn still owns
     * either slot. A STOP (or a newer trigger) may already have replaced them, and the old turn's late ending must not
     * cancel the new one.
     */
    private fun discard(request: ProactiveHintRequest, hint: ProactiveHint) {
        val abandonedPanelWait = synchronized(gate) {
            hint.cancelled = true
            request.cancelled = true
            if (proactiveHint === hint) proactiveHint = null
            if (proactiveRequest === request) proactiveRequest = null
            pendingHintPanel?.takeIf { it.hint === hint }?.also { pendingHintPanel = null }
        }
        abandonedPanelWait?.abort(HintPanelAcquisition.CANCELLED)
    }

    /**
     * Submits the sentence, with the diagnostic plugin's submit/ACK discipline and the same privacy line as the
     * passive path: the log carries the hint's identity and size, never the text.
     */
    private fun submitProactiveHint(hint: ProactiveHint, text: String) {
        val current = currentContext() ?: return
        val now = clock.nowNanos()
        val assistance = synchronized(gate) {
            if (proactiveHint !== hint || hint.cancelled) {
                // 0.3 Phase 3 Task 9d fix (review Important 1): the hint lost its slot after acquiring the panel (a
                // STOP or a replacement). Return the marker for the identity-checked round cleanup outside the lock.
                null
            } else {
                // The visible window starts here, not at the trigger: a reply that used most of the request deadline must
                // still give the wearer a full window (a review finding).
                val expiry = SmartTurn.saturatingAdd(now, ProactiveHintSpec.PROACTIVE_HINT_TTL_MILLIS * 1_000_000L)
                hint.expiryNanos = expiry
                hint.submitting = true
                hint.pendingAck = false
                Assistance(
                    hintId = hint.hintId,
                    sessionId = current.sessionId,
                    generation = current.generation,
                    sourceTurnId = hint.turnId,
                    // Phase C follow-up (owner report, 2026-10-15): the route decides which conversation this hint is
                    // filed under - on the glasses the wire route is the route the entry lands in. It defaults to WAKE,
                    // so leaving it out filed every proactive answer under the wake conversation.
                    route = AssistantRoute.PROACTIVE,
                    priority = 0,
                    text = text,
                    // P5 (spec §S6): proactive hints carry speech so the TTS path is exercised. The host's capability
                    // gate decides whether it actually plays; without a player the hint still displays normally (§S2).
                    optionalSpeech = SpeechRequest(),
                    expiresAtNanos = expiry,
                )
            }
        }
        if (assistance == null) {
            // The pre-armed round belongs to this hint's turn; a newer attempt's round is untouched by the identity
            // check, and a round that never showed its panel gets no CLOSE (it was released by the acquisition).
            conversationCycle.closeIfTurn(AssistantRoute.PROACTIVE, hint.turnId)
            return
        }
        val sink: OutputSink? = runCatching { current.services.textOutput() }.getOrNull()
        val result = if (sink != null) {
            P3Log.line("P3_HINT show hint=${assistance.hintId} chars=${assistance.text.length}")
            runCatching { sink.submit(assistance) }.getOrNull()
        } else {
            null
        }
        var cancelLateOutput = false
        var releaseAcquisition = false
        var openedFollowup: FollowupWindow? = null
        synchronized(gate) {
            hint.submitting = false
            if (result == OutputSubmission.Accepted && proactiveHint === hint && !hint.cancelled) {
                hint.readable = true
                if (hint.pendingAck && !hint.displayed && clock.nowNanos() < hint.expiryNanos) {
                    hint.pendingAck = false
                    hint.displayed = true
                    P3Log.line("P3_HINT ack hint=${hint.hintId} status=${OutputStatus.Displayed}")
                    onDisplayedAck()
                    // The ACK arrived inside `submit`; the hint is admitted now, so the follow-up window opens with
                    // every other admitted ACK. Opened outside the lock by the caller below.
                    openedFollowup = openFollowupWindowLocked(hint)
                }
            } else if (result == OutputSubmission.Accepted) {
                // A STOP (or a newer trigger) replaced the hint while `submit` was running outside the lock. The host
                // may already have shown it, so it must be cancelled explicitly - not merely marked unreadable.
                hint.cancelled = true
                if (proactiveHint === hint) proactiveHint = null
                cancelLateOutput = true
                releaseAcquisition = true
            } else {
                // Refused by the host's display gate: one line, no retry, no second request.
                P3Log.line("P3_PROACTIVE hint rejected chars=${assistance.text.length}")
                hint.cancelled = true
                if (proactiveHint === hint) proactiveHint = null
                releaseAcquisition = true
            }
        }
        val opened = openedFollowup
        if (opened != null) openFollowupWindow(opened)
        if (releaseAcquisition) {
            // 0.3 Phase 3 Task 9d: a hint the host refused (or one replaced mid-submit) does not own a listening
            // round. Release the round the hidden-panel acquisition pre-armed; a newer attempt's round is untouched.
            conversationCycle.closeIfTurn(AssistantRoute.PROACTIVE, hint.turnId)
        }
        if (cancelLateOutput) {
            runCatching { cancelOutputTurn(current.sessionId, current.generation, hint.turnId) }
        }
    }

    /**
     * Renders a `Displayed` ACK of the proactive hint; a rejected, repeated or expired hint never counts twice.
     *
     * A newly admitted ACK returns the follow-up window it opened (0.3 Phase 3 Task 6, spec §4.3). The window's
     * `onWake` runs in the caller **outside** [gate], because the conversation cycle displays through the host port.
     */
    private fun acceptProactiveDisplayed(event: OutputStateEvent): FollowupWindow? {
        val hint = proactiveHint ?: return null
        if (hint.cancelled || hint.displayed) return null
        if (event.hintId != hint.hintId || event.status != OutputStatus.Displayed) return null
        if (hint.expiryNanos == 0L || clock.nowNanos() >= hint.expiryNanos) return null
        if (hint.submitting) {
            hint.pendingAck = true
            return null
        }
        if (!hint.readable) return null
        hint.displayed = true
        P3Log.line("P3_HINT ack hint=${hint.hintId} status=${event.status}")
        onDisplayedAck()
        return openFollowupWindowLocked(hint)
    }

    /**
     * Must hold [gate]: installs the bounded PROACTIVE listening window for the hint whose `Displayed` ACK was admitted.
     *
     * The window is not a listening `SmartTurn`; it is the cycle round on the PROACTIVE route whose 10 s deadline the
     * routing and close paths below key on. A new hint therefore supersedes any hand-off an older window accepted but
     * Task 7 has not started yet.
     */
    private fun openFollowupWindowLocked(hint: ProactiveHint): FollowupWindow {
        val window = FollowupWindow(
            hint = hint,
            turnId = "followup-${UUID.randomUUID()}",
            openedNanos = clock.nowNanos(),
        )
        proactiveFollowup = window
        followupTurnRequest = null
        return window
    }

    /**
     * Opens the PROACTIVE listening round through the conversation cycle. Never called while [gate] is held: the cycle's
     * `onWake` displays the official listening level through the host port, which may re-enter [handle].
     *
     * Install and attach are two steps - [gate] is released before the host-visible `onWake` - so this is also where a
     * `PANEL_SHOWN`/`UI_CLOSED` delivered on the composition's scheduler thread is reconciled:
     * - the window starts **unattached**, which is what makes [onPanelShown] defer its inert clear;
     * - the install is revalidated immediately before the attach (a close in the gap arms nothing);
     * - it is revalidated again after the attach, and a round whose window was cleared or replaced meanwhile is
     *   released (or replaced by the newer window's own attach) rather than left orphaned with no plugin window.
     *
     * Attaches are serialized by [followupAttachInProgress]: a window installed while another one is attaching is
     * picked up by the in-flight call after its own attach, so the cycle always ends up armed for the newest window.
     */
    private fun openFollowupWindow(window: FollowupWindow) {
        var next: FollowupWindow? = window
        while (true) {
            val candidate = next ?: return
            val target = synchronized(gate) {
                if (proactiveFollowup !== candidate || candidate.attached) return
                if (followupAttachInProgress) return
                followupAttachInProgress = true
                candidate
            }
            var armed = false
            var attachedNow = false
            var currentAfter: FollowupWindow? = null
            try {
                beforeFollowupWindowAttachForTest?.invoke()
                // Revalidate immediately before the host-visible attach: a close that landed in the install gap must
                // not arm a round with no window behind it.
                if (synchronized(gate) { proactiveFollowup === target }) {
                    conversationCycle.onWake(AssistantRoute.PROACTIVE, target.turnId)
                    armed = true
                }
            } finally {
                synchronized(gate) {
                    followupAttachInProgress = false
                    if (proactiveFollowup === target) {
                        target.attached = true
                        attachedNow = true
                    } else {
                        currentAfter = proactiveFollowup
                    }
                }
            }
            if (attachedNow) {
                // Counts and identities only - the hint's text is display content and never reaches the log.
                P3Log.line("P3_PROACTIVE followup open turn=${target.turnId} hint=${target.hint.hintId}")
                return
            }
            val newer = currentAfter
            if (newer != null) {
                // A newer window replaced this one while it was attaching. Attaching it now replaces the round this
                // call just armed (`onWake` cancels this round's timers and installs the newer turn id).
                next = newer
                continue
            }
            if (armed) {
                // Cleared while attaching (UI_CLOSED/STOP/wake) with no newer window behind it: release the round this
                // call armed, so no 10 s PROACTIVE turn without a plugin window is left behind.
                conversationCycle.onClose(AssistantRoute.PROACTIVE)
            }
            return
        }
    }

    /**
     * 0.3 Phase 3 Task 9d (spec §4.3): makes sure a hidden panel is **open** before the hint is submitted.
     *
     * The glasses' `PANEL_OPEN` runs `resetForOpen()`/`clearConversation()`, so an open processed after the hint's
     * `SHOW` wipes the just-ACKed sentence; the CXR custom-command channel is unordered, so "send open then show" is
     * not a guarantee. The order here is therefore: pre-arm the cycle round (so the panel's own `PANEL_SHOWN` arms the
     * official 10 s window instead of the turn-less defensive close), request the readiness-aware open, wait for that
     * report, and only then submit.
     *
     * With an already visible panel there is nothing to acquire: the caller submits immediately (the visible branch of
     * the conversation cycle arms the window at the hint's ACK), so a panel the wearer is watching is never reset. A
     * host with no panel-opening seam (`requestPanelOpen == null`) keeps the legacy behavior.
     *
     * A refused request or a bounded timeout returns [HintPanelAcquisition.PANEL_NOT_SHOWN]; the caller records the
     * stable skip line and submits nothing. The wait's real bound is the cycle's 3 s acquisition cap; the hint task's
     * own wall-clock bound is the defensive backstop.
     */
    private fun acquireHintPanel(hint: ProactiveHint): HintPanelAcquisition {
        val openPanel = requestPanelOpen ?: return HintPanelAcquisition.ACQUIRED
        if (isPanelVisible()) return HintPanelAcquisition.ACQUIRED
        val pending = PendingHintPanel(hint)
        val claimed = synchronized(gate) {
            if (proactiveHint !== hint || hint.cancelled || pendingHintPanel != null) {
                false
            } else {
                pendingHintPanel = pending
                true
            }
        }
        if (!claimed) return HintPanelAcquisition.CANCELLED
        var result = HintPanelAcquisition.PANEL_NOT_SHOWN
        try {
            // The pre-arm is what gives `PANEL_SHOWN` a real turn to start the 10 s window on. It displays nothing on
            // a hidden panel, so it cannot itself clear a hint.
            conversationCycle.onWake(AssistantRoute.PROACTIVE, hint.turnId)
            if (isPanelVisible()) {
                // The panel came up between the check and the pre-arm; the pre-arm already took the visible branch.
                pending.signalShown()
                result = HintPanelAcquisition.ACQUIRED
            } else {
                // A STOP or a replacement that landed between the pre-arm and the request must not ask the glasses to
                // open a window the turn no longer wants.
                if (!synchronized(gate) { pendingHintPanel === pending && !hint.cancelled }) {
                    result = HintPanelAcquisition.CANCELLED
                } else {
                    val accepted = runCatching { openPanel(AssistantRoute.PROACTIVE) }.getOrDefault(false)
                    when {
                        accepted -> result = pending.awaitAcquisition(HINT_PANEL_ACQUIRE_TIMEOUT_MILLIS)
                        isPanelVisible() -> {
                            // The link answers "not needed" for an already visible panel. Take the visible branch of
                            // the cycle before submitting so the ACK's window is not charged to a hidden acquisition.
                            conversationCycle.onWake(AssistantRoute.PROACTIVE, hint.turnId)
                            pending.signalShown()
                            result = HintPanelAcquisition.ACQUIRED
                        }
                        else -> result = HintPanelAcquisition.PANEL_NOT_SHOWN
                    }
                }
            }
        } finally {
            synchronized(gate) { if (pendingHintPanel === pending) pendingHintPanel = null }
            // A failed acquisition releases the round it pre-armed; the identity-checked release keeps a newer
            // attempt's round safe, and a panel that never opened gets no CLOSE command.
            if (result != HintPanelAcquisition.ACQUIRED) {
                conversationCycle.releaseIfTurn(AssistantRoute.PROACTIVE, hint.turnId)
            }
        }
        return result
    }

    /** Ends the panel wait of a hint that is being stopped/replaced/closed. Must not hold [gate] when notifying. */
    private fun cancelPendingHintPanel(reason: HintPanelAcquisition) {
        val pending = synchronized(gate) {
            pendingHintPanel.also { pendingHintPanel = null }
        }
        pending?.abort(reason)
    }

    /**
     * Clears the PROACTIVE conversation's listening window, a not-yet-started hand-off and the follow-up turn itself,
     * recording [reason] once.
     *
     * A stop/UI-close is a decision about the listening round, not about the sentence the wearer is reading: the
     * already-displayed hint keeps its own TTL (spec §4.3). 0.3 Phase 3 Task 7 completes the Task 6 seam note: a
     * running/waiting follow-up turn is cancelled here too, and its loop/assembler are torn down outside [gate].
     */
    private fun closeFollowup(reason: String) {
        cancelPendingHintPanel(HintPanelAcquisition.PANEL_NOT_SHOWN)
        var cancelledTurn: SmartTurn? = null
        val cleared = synchronized(gate) {
            val had = proactiveFollowup != null || followupTurnRequest != null || followupTurn != null
            proactiveFollowup = null
            followupTurnRequest = null
            clearQueuedUtteranceLocked(AssistantRoute.PROACTIVE)
            cancelledTurn = detachFollowup()
            had
        }
        if (cleared) P3Log.line("P3_PROACTIVE followup close reason=$reason")
        teardown(cancelledTurn)
        publishBusy()
    }

    private fun elapsedMillis(startedNanos: Long): Long = (clock.nowNanos() - startedNanos) / 1_000_000L

    // ---------------------------------------------------------------- output

    /**
     * Turns one terminal run into the hint the wearer sees.
     *
     * `COMPLETED` renders the model's own text; every other outcome renders a fixed honest sentence. There is no path
     * here that submits nothing: a turn that reached a terminal state always says something.
     */
    private fun submitOutcome(owned: SmartTurn, run: AgentRun, ledger: NotifyLedger) {
        val assembler = synchronized(gate) {
            if (!owns(owned)) return
            owned.assembler
        } ?: return
        val finalText = run.finalText
        // Spec §6.1: the model reaches the wearer only through `notify_user`, so a reply must not be displayed a
        // second time on top of the notifications it already sent. §6.2: an explicit `silent` must not be
        // contradicted by the fallback either.
        if (ledger.calls > 0) {
            P3Log.line("P3_NOTIFY reply=suppressed n=${ledger.calls}")
            return
        }
        if (ledger.suppressed) {
            P3Log.line("P3_NOTIFY reply=suppressed reason=SILENT")
            return
        }
        if (run.outcome == AgentOutcome.COMPLETED && !finalText.isNullOrBlank()) {
            // §6.2's fallback: the model forgot to notify, so the system says it - on `screen`, because speaking a
            // sentence the model never chose to say out loud would be the system's decision rather than the model's.
            P3Log.line("P3_NOTIFY fallback=screen")
            assembler.append(finalText)
            assembler.finish(ModelEnd.COMPLETED)
        } else {
            assembler.append(FAILURE_HINTS[run.outcome] ?: FAILURE_HINTS.getValue(AgentOutcome.FAILED))
            assembler.finish(ModelEnd.FAILED)
        }
    }

    /**
     * The turn a wake opened has finished, so the proactive work it made wait may run (spec §4.6).
     *
     * **Only the WAKE route calls this.** The shared `runTurnBody` gates the call on the owning turn's route: a
     * PROACTIVE follow-up must leave a trigger it merely observed parked, or the resumed hint would install its window
     * over that follow-up's own continuation round (Task 7 fix).
     *
     * Takes the slot under [gate] before running, so two turns finishing at once cannot both run the same trigger,
     * and runs it outside the lock because `runProactive` starts a task of its own.
     */
    private fun releaseParkedProactive() {
        val parked = synchronized(gate) {
            val held = parkedProactive
            parkedProactive = null
            held
        } ?: return
        P3Log.line("P3_PROACTIVE resume reason=WAKE_DONE")
        runProactive(parked)
    }

    /**
     * One notification from the model, with §6.3's mid-step guard applied.
     *
     * The guard's rule is "this turn has already notified" — a second notification inside one turn means the task is
     * still running, so its voice is suppressed while its screen text is not. The **first** notification of a turn is
     * never downgraded, which is what leaves the "task starting" and "task done" notices free to speak.
     */
    private fun onNotify(owned: SmartTurn, ledger: NotifyLedger, channel: NotifyChannel, text: String) {
        ledger.calls++
        // Every notification body this turn sent, in order: §6.1 requires all of it to reach the context, and the
        // transcript is where the model's own turn lives. A `silent` body is recorded too - §6.2 keeps it off the
        // display, it does not make the model's own message unreal.
        ledger.notifications += text
        val midStep = ledger.calls > 1
        val effective = if (midStep && channel != NotifyChannel.SCREEN && channel != NotifyChannel.SILENT) {
            NotifyChannel.SCREEN
        } else {
            channel
        }
        val downgraded = effective != channel
        P3Log.line(
            "P3_NOTIFY channel=${channel.wireName} effective=${effective.wireName} " +
                "n=${ledger.calls} downgraded=$downgraded",
        )
        when (val decision = decideChannel(effective)) {
            is NotifyDecision.Suppress -> ledger.suppressed = true
            is NotifyDecision.Show -> emit(owned, text, decision.optionalSpeech)
        }
    }

    /**
     * One turn's notification bookkeeping.
     *
     * A local of [runTurn], handed to the tool the loop carries, and never shared: §6.3's guard is about *this* turn,
     * and a field on the plugin would make a previous turn's notification downgrade this one's.
     */
    private class NotifyLedger {
        var calls = 0
        var suppressed = false

        /** Every notification body this turn sent, in order, so §6.1's "all notification content" reaches the context. */
        val notifications = mutableListOf<String>()
    }

    /**
     * A terminal state reached before the loop could run at all.
     *
     * The 0.24 official listening expiry is **not** one of these any more: the 10 s window belongs to
     * [conversationCycle], which closes the conversation instead of submitting a failure hint. This serves the
     * dependency/blank-utterance refusals only.
     */
    private fun finishWithFailure(owned: SmartTurn, text: String) {
        val assembler = synchronized(gate) {
            if (!owns(owned)) return
            owned.idle()
            owned.assembler
        } ?: return
        assembler.append(text)
        assembler.finish(ModelEnd.FAILED)
    }

    /**
     * The assembler's emit callback: builds the `Assistance` and submits it, with the diagnostic plugin's
     * submit/ACK discipline.
     *
     * The context is read under [gate] but the submit itself happens outside it, so a synchronous `OutputStateEvent`
     * delivered from inside `submit` (the phone UI is live during a run) can re-enter [handle] without deadlocking.
     * If that ACK arrives mid-submit it is only *remembered* ([SmartTurn.pendingAck]) and admitted after
     * [OutputSubmission.Accepted] comes back.
     *
     * [recordAsAnswer] is `false` for the post-turn memory acknowledgement: it is displayed like any hint, but it is
     * not the answer the conversation continues from (Task 12 review minor 8). 0.24 S4 Task 16 (I1) records it under
     * its own entry identity and classifies its replacement of the answer as a supersession, not a terminal.
     */
    private fun emit(
        owned: SmartTurn,
        text: String,
        optionalSpeech: SpeechRequest? = null,
        recordAsAnswer: Boolean = true,
    ) {
        var finalAnswer = false
        val hint = synchronized(gate) {
            val current = owned
            if (!owns(current)) return
            val context = context ?: return
            val now = clock.nowNanos()
            // P3.2-CLIP: a hint taller than the glasses' window is slid through at a reading pace, so a fixed five
            // seconds cleared the text mid-sentence. The budget adds the slide's own duration; a hint that fits keeps
            // exactly `HINT_LIFETIME_MILLIS`.
            val lifetime = HintReadingBudget.readMillis(text.length, HINT_LIFETIME_MILLIS)
            val wanted = SmartTurn.saturatingAdd(now, lifetime * 1_000_000L)
            // The turn's own window bounds `sourceTurnId`'s validity, and it used to be the *only* bound: a hint the
            // reading budget had already sized for two or three screens was clipped to whatever was left of the turn,
            // so the panel cleared part-way through the slide - the same "突然内容清空了" P3.2-CLIP fixed once, moved
            // from the base lifetime into this window. Extending the window by exactly the deficit keeps the promise
            // "the panel holds the hint until the slide is done" true for long notifications too (owner ruling
            // 2026-10-14: 超长通知不能截断，要推送眼镜端滚动显示). Every check that reads this window afterwards
            // (a late hint, an ACK) now reads the later instant, which is the honest one: the turn really is still
            // being read.
            if (wanted > current.hintValidUntil) current.hintValidUntil = wanted
            val expiry = wanted
            if (expiry <= now) return
            current.submitting = true
            current.pendingAck = false
            current.hintExpiry = expiry
            // 0.24 S3 Task 14 fix (C1): every emit gets its own identity. The turn keeps one `hintId` (one conversation
            // entry, spec §6), so without this a replacement's `Rejected` is indistinguishable from a rejection of the
            // final answer, and a stale `SpeechFinished` is indistinguishable from the answer's own speech end.
            val emitId = "emit-${UUID.randomUUID()}"
            // 0.24 S3 Task 14 fix (C2, spec risk row 17): a `both`/`voice` request is only a speech request if a player
            // can actually play it. With「语音朗读」off (the shipped default) no player exists, so the hint is
            // display-only and the answer is settled from its `Displayed` acknowledgement instead of a
            // `SpeechFinished` that can never arrive.
            val speech = if (speechAvailable()) optionalSpeech else null
            // 0.24 S3 (spec §5.5): the closing answer is assembled after the run reached Idle; a `notify_user` answer
            // was emitted while the run was still `Running` and is marked as the final answer by
            // `settleAnswerAfterRun()` at the run end instead. Recording the emit identity here is what lets that
            // run-end decision (and a terminal display event) name the right emit instead of asking the phase.
            finalAnswer = recordAsAnswer && current.phase == SmartTurnPhase.Idle
            if (recordAsAnswer) {
                // A real answer emit makes the fallback liveness bound unnecessary; releasing it keeps the scheduler
                // (and the pending-deadline counts the tests read) honest.
                current.cancelFallbackWait()
                current.answerEmitId = emitId
                current.answerEmitSpeech = speech != null
                // A new answer emit is a new speech identity: the previous emit's playback end must not mark this one
                // finished (and let the utterance queue treat the still-playing answer as the wearer's next sentence).
                current.answerSpeechFinished = false
                current.answerFinal = finalAnswer
                current.answerTerminal = null
                current.displayed = false
                // A new answer is the entry's current identity; an earlier acknowledgement's supersession no longer
                // describes a terminal of this emit.
                current.answerSupersededByNotice = false
            } else if (current.answerEmitId != null) {
                // 0.24 S4 Task 16 (I1): a non-answer statement (the memory acknowledgement) shares the turn's hint id,
                // so submitting it replaces the answer and the arbiter reports the removed answer as `Rejected`.
                // Remember that the supersession is what a later Rejected for the answer's emit means; it is not a
                // terminal that proves the wearer never saw the answer.
                current.answerSupersededByNotice = true
            }
            Assistance(
                hintId = current.hintId,
                sessionId = context.sessionId,
                generation = context.generation,
                sourceTurnId = current.turnId,
                // 0.3 Phase 3 Task 7: the route the wire files this entry under is the turn's own (P3-13). It used to
                // be stated as WAKE because only a wake turn could emit; a PROACTIVE follow-up must file under
                // PROACTIVE or the glasses show the answer in the wrong conversation.
                route = owned.route,
                priority = 0,
                text = text,
                optionalSpeech = speech,
                expiresAtNanos = expiry,
                emitId = emitId,
            )
        }
        val current = owned
        val sink: OutputSink? = runCatching { currentContext()?.services?.textOutput() }.getOrNull()
        val stillCurrent = synchronized(gate) { owns(current) }
        // 0.24 S3: a hint is being put in front of the wearer, so the official level is ANSWERING (3). Outside the
        // lock, because the cycle displays through the host port.
        //
        // 0.24 S4 Task 16: a new answer emit also starts a new speech identity, so a `SpeechFinished` remembered for a
        // superseded emit cannot satisfy this answer's wait (Task 14 carry). Only for the turn that still owns the
        // wire: a late emit from a replaced turn must not clear the new turn's memory.
        if (stillCurrent) {
            if (recordAsAnswer) conversationCycle.onAnswerEmit(owned.route)
            conversationCycle.onStatus(owned.route, OfficialStatus.ANSWERING)
        }
        val result = if (stillCurrent && sink != null) {
            // On submit: the hint's identity and size only. The text itself is the display content and never logged.
            P3Log.line("P3_HINT show hint=${hint.hintId} chars=${hint.text.length}")
            runCatching { sink.submit(hint) }.getOrNull()
        } else {
            null
        }
        // Phase C: the conversation record gets the sentence first - it is what the wearer is being told - and the
        // "working" level then ends, so the ring stops exactly when the answer appears rather than a moment before it.
        // 0.24 S4 Task 16: the final names the turn and the hint, so it replaces the same `say-${hintId}` row the
        // streamed revisions opened (and blocks a later revision under that id). A non-answer statement (the memory
        // acknowledgement) is **not** the answer: it gets its own record entry, so it can never overwrite or finalize
        // `say-${hintId}`.
        val contextRoute = owned.route.toContextRoute()
        if (recordAsAnswer) {
            publishAssistantFinal(contextRoute, hint.sourceTurnId ?: current.turnId, hint.hintId, hint.text)
        } else {
            publishAssistantNotice(contextRoute, hint.text)
        }
        synchronized(gate) {
            if (owned.route == AssistantRoute.WAKE) wakeBusy = false else followupBusy = false
        }
        // Outside the lock, as `publishBusy` requires: the host's port is what ends the ring.
        publishBusy()
        var ackAdmitted = false
        synchronized(gate) {
            current.submitting = false
            if (recordAsAnswer && owns(current)) {
                current.answerEmitAccepted = result == OutputSubmission.Accepted
            }
            if (result == OutputSubmission.Accepted && owns(current)) {
                current.readable = true
                if (current.pendingAck && !current.displayed && clock.nowNanos() < current.hintExpiry) {
                    current.displayed = true
                    ackAdmitted = true
                    P3Log.line("P3_HINT ack hint=${current.hintId} status=${OutputStatus.Displayed}")
                    onDisplayedAck()
                }
            }
            current.pendingAck = false
        }
        if (ackAdmitted) {
            // The in-submit acknowledgement is the answer-done signal exactly like a delivered one; the cycle call
            // happens outside the lock so the continuation can re-enter.
            settleDisplayedAnswer(current)
        } else if (finalAnswer && stillCurrent && result != OutputSubmission.Accepted) {
            // 0.24 S3 (spec §5.5): an answer no display accepted has nothing for the next round to continue. Close and
            // record `no_display`; opening a turn on top of an answer the wearer never saw is the failure the spec
            // names.
            settleNoDisplayAnswer(current)
        }
    }

    /** Renders a `Displayed` ACK into [owned]'s own bookkeeping, never into another turn's. Must hold [gate]. */
    private fun acceptDisplayed(owned: SmartTurn?, event: OutputStateEvent) {
        if (owned == null || !owns(owned)) return
        if (owned.closed || owned.displayed) return
        if (!namesCurrentAnswerEmit(owned, event.hintId, event.emitId)) return
        if (event.status != OutputStatus.Displayed) return
        if (clock.nowNanos() >= owned.hintExpiry) {
            // 0.24 S3 Task 14: a late acknowledgement cannot be admitted - the hint's display window is over. It is
            // the same situation as an `Expired` result: the answer never reached the wearer.
            if (owned.answerFinal) owned.answerTerminal = OutputStatus.Expired
            return
        }
        if (owned.submitting) {
            owned.pendingAck = true
        } else if (owned.readable) {
            owned.displayed = true
            owned.answerTerminal = null
            P3Log.line("P3_HINT ack hint=${owned.hintId} status=${event.status}")
            onDisplayedAck()
        }
    }

    // ---------------------------------------------------------------- stop / teardown

    /**
     * `Stop` is the emergency brake: cancel the in-flight loop, cancel the pending output, clear the turn. Nothing is
     * submitted afterwards — a run that returns a late `CANCELLED` finds its turn already gone.
     *
     * It is scoped to the session/generation this plugin was activated for, exactly like every other branch: a `Stop`
     * raised in another session (or for an older generation of this one) must not be able to cancel this plugin's turn.
     */
    private fun stopAll(envelope: EventEnvelope) {
        var cancelledProactive: ProactiveHint? = null
        var cancelledHintRun: ProactiveHintRequest? = null
        var cancelledHintLoop: AgentLoop? = null
        var cancelledFollowup = false
        var stoppedFollowup: SmartTurn? = null
        var cancelledPanelWait: PendingHintPanel? = null
        var accepted = false
        val stopped = synchronized(gate) {
            if (!matches(envelope)) null
            else {
                accepted = true
                // P3.2: a proactive request is in flight outside this lock; marking its hint cancelled is what stops a
                // late reply from being shown. 0.3 Phase 3 Task 5: the hint turn is an AgentLoop, so its request object
                // is also marked (the gateway's `stop` predicate) and its loop is captured to be cancelled outside the
                // lock, exactly as the wake turn's loop is.
                cancelledProactive = proactiveHint
                cancelledHintRun = proactiveRequest
                proactiveHint?.cancelled = true
                proactiveHint = null
                cancelledHintRun?.cancelled = true
                cancelledHintLoop = cancelledHintRun?.loop
                proactiveRequest = null
                // 0.3 Phase 3 Task 9d: a hint that is still acquiring the panel belongs to this stopped turn too.
                cancelledPanelWait = pendingHintPanel
                pendingHintPanel = null
                // 0.3 Phase 3 Task 6/7: STOP is also the emergency brake for the PROACTIVE conversation. The window,
                // a not-yet-started hand-off and a running/waiting FOLLOWUP_TURN are all cancelled here; the hint
                // already on the panel keeps its TTL (spec §4.3).
                cancelledFollowup =
                    proactiveFollowup != null || followupTurnRequest != null || followupTurn != null
                proactiveFollowup = null
                followupTurnRequest = null
                stoppedFollowup = detachFollowup()
                // 0.23 P5 (U11): the level must be down before `publishBusy` runs, or the glasses keep thinking after
                // the wearer asked to interrupt.
                proactiveInFlight = false
                // Phase C: an emergency stop ends whatever was being worked on, so the ring must stop with it - this
                // is one of the two paths that can end a turn without an answer ever being produced.
                wakeBusy = false
                detach()
            }
        }
        // R128: a STOP must withdraw the live question here, before teardown and before the loop can see a late Allow.
        // Without this the turn was cancelled but `SmartConfirmation.pending` stayed armed, the phone's card stayed up,
        // and a tap that arrived afterwards settled the gate as approved - reaching `host.execute` after the wearer had
        // stopped the assistant (the R52 bypass). A foreign Stop is not this session's stop and cancels nothing.
        if (accepted) confirmation.cancelAll()
        // The hint loop's own cancel flag is the second half of the stop: the gateway predicate above ends a blocked
        // request, and this stops the run between steps and asks its (single, pure) executor to stop. Outside the lock,
        // because `cancel` reaches the executors.
        cancelledHintLoop?.cancel()
        teardown(stopped)
        teardown(stoppedFollowup)
        // 0.23 P5 (D145): an already-displayed proactive hint is output too. The official `stopTalk` stops the speech
        // and leaves the panel idle without closing it; `cancelOutputTurn` is the host path that both stops speech and
        // clears the hint (OutputArbiter.cancelTurn). Done outside the lock, as every host call here must be.
        cancelledProactive?.let { hint ->
            runCatching { cancelOutputTurn(envelope.sessionId, envelope.generation, hint.turnId) }
        }
        // 0.24 S3 (spec §5.5): STOP cancels the round and shows the official idle, keeping the panel. Outside the
        // lock, because the cycle displays through the host port. A **foreign** Stop is not this session's stop and
        // must not touch this conversation at all.
        if (accepted) conversationCycle.onStop(AssistantRoute.WAKE)
        // 0.3 Phase 3 Task 6: STOP also ends the PROACTIVE listening round - but only when one existed, so a stop in a
        // session with no proactive conversation does not paint an idle on that route.
        // 0.3 Phase 3 Task 9d fix (review Important 1): a hint that already acquired its panel has no plugin window yet
        // (`cancelledFollowup` is false) and `acquireHintPanel` has already cleared `pendingHintPanel`, so cleanup is
        // tied to the hint's own turn id. With the panel up that is the official idle/keep-panel; while the round is
        // still waiting for `PANEL_SHOWN` it is released without a display call. The identity check keeps a newer
        // attempt's round safe.
        val cancelledHint = cancelledProactive ?: cancelledPanelWait?.hint
        if (accepted && cancelledFollowup) {
            P3Log.line("P3_PROACTIVE followup close reason=stop")
            conversationCycle.onStop(AssistantRoute.PROACTIVE)
        } else if (accepted && cancelledHint != null) {
            conversationCycle.stopIfTurn(AssistantRoute.PROACTIVE, cancelledHint.turnId)
        }
        cancelledPanelWait?.abort(HintPanelAcquisition.CANCELLED)
        // Outside the lock, as `publishBusy` requires.
        publishBusy()
    }

    /**
     * Manual entry point (spec keeps `Help` as the non-wake path): run the most recent final transcript **of this
     * session**. It is an explicit user action, so it bypasses the wake cooldown and the awaiting-transcript guard;
     * with no current-session final transcript it does nothing.
     */
    private fun handleHelp(envelope: EventEnvelope) {
        // 0.24 S3: `Help` replaces whatever the official conversation was waiting on. Releasing the cycle's timers and
        // levels without a display call keeps a stale 10 s window from closing the panel during this turn. A foreign
        // Help never reaches here, so it cannot reset this session's conversation.
        if (!synchronized(gate) { matches(envelope) }) return
        // 0.3 Phase 3 Task 9d: a hint still waiting for the panel cannot submit into the turn Help is opening.
        cancelPendingHintPanel(HintPanelAcquisition.CANCELLED)
        conversationCycle.reset()
        val text = synchronized(gate) {
            if (!matches(envelope)) return
            lastFinalTranscript?.takeIf { it.isNotBlank() }
        } ?: return
        // The reused turn keeps the transcript's own turn id, exactly as a wake-opened turn does.
        startManualTurn(envelope, text, transcriptTurnId = lastFinalTurnId)
    }

    /**
     * 0.4 Task 10 (`D203`, spec §5.2): the phone's typed input, through the WAKE route.
     *
     * The body is not a transcript: it deliberately skips [isUsableUtterance] (a wake-word-only body is a real request
     * when the wearer typed it) and is never stored as [lastFinalTranscript] (Help must keep replaying the last thing
     * that was **said**). Only the WAKE route's conversation cycle is released - `Help`'s session-wide reset would also
     * discard a pending PROACTIVE hint panel that this message has nothing to do with.
     */
    private fun handleTyped(envelope: EventEnvelope, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        // Refuse before touching anything: a foreign or busy message must not release the live WAKE round.
        if (!synchronized(gate) { matches(envelope) && canAcceptTypedLocked() }) return
        conversationCycle.reset(AssistantRoute.WAKE)
        startManualTurn(
            envelope,
            body,
            turnId = typedTurnId(envelope),
            startsCooldown = false,
            // Re-checked inside the same gate as `openTurn`: the acceptance look above and the open are one decision.
            admissibleLocked = { canAcceptTypedLocked() },
        )
    }

    /**
     * The tail both manual WAKE entries share (`Help` and `Typed`): open an immediate, gate-free WAKE turn, raise the
     * working level, publish the wearer's row and launch the run.
     *
     * Must be called without [gate] held.
     *
     * @param transcriptTurnId the ASR turn `Help` reuses; null for the typed path and for a legacy final with no id.
     * @param turnId the turn id the caller minted, or null to let this method mint the usual `smart-<uuid>` one.
     * @param startsCooldown false for the typed path: typing is not a wake, so it must not start the scene cooldown.
     * @param admissibleLocked a final acceptance predicate re-checked **inside the same [gate] as `openTurn`**, so a
     *   turn that became busy between the caller's first look and the open is still refused.
     * @return true when a turn was opened; false for a blank body, a foreign envelope or a refused acceptance.
     */
    private fun startManualTurn(
        envelope: EventEnvelope,
        text: String,
        transcriptTurnId: String? = null,
        turnId: String? = null,
        startsCooldown: Boolean = true,
        admissibleLocked: (() -> Boolean)? = null,
    ): Boolean {
        if (text.isBlank()) return false
        val resolvedTurnId = turnId ?: "smart-${UUID.randomUUID()}"
        val opened = synchronized(gate) {
            if (!matches(envelope)) return false
            if (admissibleLocked != null && !admissibleLocked()) return false
            openTurn(
                clock.nowNanos(),
                envelope,
                runsImmediately = true,
                transcriptTurnId = transcriptTurnId,
                bypassWakeGates = true,
                turnId = resolvedTurnId,
                startsCooldown = startsCooldown,
            )
        } ?: return false
        // Phase C: a manual turn is still a real turn and must raise the same level a wake does, or the ring never
        // starts on an acceptance path and the idle emits at the end would be unpaired. Outside the lock, as
        // `publishBusy` requires.
        publishBusy()
        // The row identity is the one the conversation already uses: Help's ASR turn, or the typed turn. A legacy Help
        // final with no turn id keeps the pre-0.24 message path.
        val rowTurnId = turnId ?: transcriptTurnId
        if (rowTurnId == null) {
            publishUserMessage(ContextRoute.WAKE, text)
        } else {
            publishUserFinal(ContextRoute.WAKE, rowTurnId, text)
        }
        launchTurn(opened, text)
        return true
    }

    /** The typed turn id: the composition's `typed-<uuid>` event id when it is one, otherwise a fresh one. */
    private fun typedTurnId(envelope: EventEnvelope): String =
        envelope.eventId.takeIf { it.startsWith(TYPED_TURN_PREFIX) } ?: "$TYPED_TURN_PREFIX${UUID.randomUUID()}"

    /** Detaches the current turn: marks it closed and hands it to the caller for teardown. Must hold [gate]. */
    private fun detach(): SmartTurn? = turn?.also {
        it.closed = true
        turn = null
    }

    /**
     * Cancels everything a detached turn owns; the exact teardown the brief requires.
     *
     * [cancelOutput] is `false` only for the turn a **continuation** replaces: its answer was accepted and is the
     * conversation the next round continues, so cancelling the hint would erase the sentence the official status 5
     * just finalised.
     */
    private fun teardown(owned: SmartTurn?, cancelOutput: Boolean = true) {
        if (owned == null) return
        // R128: a question belongs to the turn that asked it. Every teardown - STOP, a panel close, a replaced turn, a
        // session end - withdraws the host's card and settles the wait as cancelled, so no later Allow can execute an
        // action whose turn is already gone. `cancelAll` is idempotent, and `deactivate` calling it first is harmless.
        confirmation.cancelAll()
        // 0.24 S3: the listening deadline is the conversation cycle's, not the turn's. Teardown here cancels the loop,
        // the assembler and the pending output; the cycle closes its own window when the conversation ends.
        runCatching { owned.loop?.cancel() }
        owned.cancelAssembler()
        // 0.24 S3 fix (I2): a pending fallback liveness bound belongs to this turn; leaving it armed would let it
        // close a later panel (it re-checks the turn, but cancelling is the honest teardown).
        owned.cancelFallbackWait()
        val sessionId = owned.sessionId ?: return
        val generation = owned.generation ?: return
        if (cancelOutput) runCatching { cancelOutputTurn(sessionId, generation, owned.turnId) }
        runCatching { currentContext()?.services?.actions()?.cancel() }
    }

        private fun currentContext(): PluginContext? = synchronized(gate) { context }

    /**
     * Whether a hard dependency is missing, as a stable reason code, or `null` when both models are ready.
     *
     * A missing *grant* and an unavailable *model* are the same answer on purpose: a host that never provided the
     * capability and one whose model failed to load both leave the assistant unable to serve, and the caller only
     * needs to know which one to name.
     */
    private fun serviceRefusal(): String? {
        val services = synchronized(gate) { context?.services } ?: return null
        val systemOne = runCatching { services.systemOne() }.getOrNull()
        if (systemOne == null || !systemOne.isAvailable) return REASON_SYSTEM_ONE
        val embedding = runCatching { services.embedding() }.getOrNull()
        if (embedding == null || !embedding.isAvailable) return REASON_EMBEDDING
        return null
    }

    private companion object {
        /**
         * 0.3 ASR/continuous-dialogue repair: how long a sentence spoken during a running wake turn may wait for that
         * turn's continuation to open.
         *
         * It follows the conversation, not the model's latency: a tool round that stops for a confirmation (the device
         * run measured 53 s) is still the same conversation, and the wearer's sentence must survive it. The queue is
         * cleared by every close/stop/session path, so this bound only guards a conversation that never ends; 120 s is
         * the conversation cycle's own pinned-close horizon (D164/D169), not a new listening window.
         */
        const val CONTINUATION_QUEUE_TTL_NANOS = 120_000_000_000L

        /**
         * 0.3 Phase 2 Task 9: the sources a continuation's ambient perception block asks for. A tool round already
         * carries its own observation result, so the request tail only refreshes what changes without one.
         */
        val AMBIENT_PERCEPTION_SOURCES: Set<PerceptionSource> =
            setOf(PerceptionSource.DEVICE, PerceptionSource.MEDIA)

        /**
         * 0.3 design §4.2.1 rule 4: the `deviceHash` value for a host that supplied no device profile. It is a stable
         * word, not a hash, so a reader can tell "no profile" from "a profile whose digest happens to look like this".
         * An absent provider must never be filled with an invented profile.
         */
        const val DEVICE_HASH_NONE = "none"

        /**
         * 0.3 Phase 2 Task 11: the `hash` value for a request whose route has no pinned plan (a direct test seam,
         * never a production request path). A stable word, not a digest, so a reader can tell it apart from a real
         * plan hash.
         */
        const val PROMPT_HASH_NONE = "none"

        val SystemMonotonicClock = object : MonotonicClock {
            override fun nowNanos(): Long = System.nanoTime()
        }

        /** B9 reason codes. Safe to log: they name a component, never the user or a message. */
        const val REASON_SYSTEM_ONE = "SYSTEM_ONE_UNAVAILABLE"
        const val REASON_EMBEDDING = "EMBEDDING_UNAVAILABLE"

        /**
         * The scheduler a bare construction gets when the host wiring is absent.
         *
         * Production always injects the host's own scheduler (`SmartWiring`) and the JVM rigs inject their fake, so
         * this exists only so a plugin built without one cannot crash the host dispatcher on a wake: a deadline handed
         * to it is accepted and never released, which is exactly the pre-D12 behaviour of a turn whose window never
         * ends. It is deliberately not a throwing default any more, because the wake path now always arms a window.
         */
        val NoDeadlineScheduler = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle = NoCancellation
        }

        val NoCancellation = object : CancellationHandle {
            override fun cancel() = Unit
        }
    }
}

/**
 * Phase 5: one transcript item as a request-flow segment, so the accumulated transcript can go back through the one
 * frame renderer (`ModelClientGateway.frameToTranscript`) rather than a second expression that maps a transcript to the
 * wire. Roles ride along as metadata; a tool item carries no role, which is what keeps `TurnTrimmer`'s whole-turn
 * boundary (`isTurnStart`: a `TRANSCRIPT` segment with [SegmentRole.USER]) visible after the round trip.
 *
 * A tool item becomes a `TOOL_ROUND` segment whose text is the frame vocabulary's JSON entry (the same shape the
 * model-runtime adapters parse), so the renderer can restore the call and its output as **structured** items: a
 * continuation whose tool round arrived as plain text would hand the endpoint an assistant message where it expects the
 * function call it produced, and two assistant texts in a row are a shape `ContextBudgeter` rejects.
 */
internal fun ModelHistoryItem.toSegment(): Segment = when (this) {
    is ModelHistoryItem.Text -> Segment(
        origin = SegmentOrigin.TRANSCRIPT,
        trusted = true,
        text = text,
        role = if (role == MessageRole.USER) SegmentRole.USER else SegmentRole.ASSISTANT,
    )
    is ModelHistoryItem.ToolCall -> Segment(
        origin = SegmentOrigin.TOOL_ROUND,
        trusted = true,
        text = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "call")
                    put("callId", callId)
                    put("itemId", itemId)
                    put("name", name)
                    put("argsJson", argsJson)
                },
            )
        }.toString(),
    )
    is ModelHistoryItem.ToolOutput -> Segment(
        origin = SegmentOrigin.TOOL_ROUND,
        trusted = true,
        text = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "output")
                    put("callId", callId)
                    put("output", output)
                    // 0.25 spec §5.3: this JSON entry is the only channel from the store to the model request, so an
                    // image reference the tool produced must ride here or it is lost at the adapter boundary. The
                    // member is omitted when there is nothing to carry: a pre-0.25 round keeps its exact shape, and
                    // `ModelClientGateway` decodes a missing member as an empty list.
                    if (images.isNotEmpty()) {
                        put(
                            "images",
                            buildJsonArray {
                                images.forEach { image ->
                                    add(
                                        buildJsonObject {
                                            put("artifactId", image.artifactId)
                                            put("mime", image.mime)
                                            put("width", image.width)
                                            put("height", image.height)
                                            put("sha256", image.sha256)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }.toString(),
    )
}
