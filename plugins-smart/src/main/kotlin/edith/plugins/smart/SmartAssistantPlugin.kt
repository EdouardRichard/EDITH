package edith.plugins.smart

import edith.agent.AgentLoop
import edith.agent.AgentOutcome
import edith.agent.AgentRequest
import edith.agent.AgentRun
import edith.agent.AuditEntry
import edith.agent.BoundedAuditLog
import edith.agent.ConfirmationGate
import edith.agent.GatewayTurn
import edith.agent.ModelClientGateway
import edith.agent.ProgressGuard
import edith.agent.ToolRegistry
import edith.agent.WhitelistActionPolicy
import edith.agent.WhitelistEntry
import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.Assistance
import edith.contracts.ContractError
import edith.contracts.ContextChanged
import edith.contracts.ConfirmationResult
import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.HostEvent
import edith.contracts.OutputStateEvent
import edith.contracts.OutputStatus
import edith.contracts.SessionId
import edith.contracts.TranscriptEvent
import edith.contracts.TranscriptRevision
import edith.contracts.UserAction
import edith.contracts.WakeDetected
import edith.output.HintReadingBudget
import edith.plugin.Capability
import edith.plugin.CancellationHandle
import edith.plugin.ConfigSchema
import edith.plugin.ConfiguredPlugin
import edith.plugin.DeactivationReason
import edith.plugin.DeadlineScheduler
import edith.plugin.MessageRole
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelProfile
import edith.plugin.MonotonicClock
import edith.plugin.OutputSink
import edith.plugin.OutputSubmission
import edith.plugin.PluginConfiguration
import edith.plugin.PluginContext
import edith.plugin.PluginDescriptor
import edith.plugin.PluginLifecycle
import edith.plugin.RiskLevel
import edith.plugin.actions
import edith.plugin.confirmation
import edith.plugin.context
import edith.plugin.model
import edith.plugin.storage
import edith.plugin.textOutput
import edith.plugin.wakeWord
import edith.plugins.smart.memory.MemoryExtraction
import edith.plugins.smart.memory.MemoryLog
import edith.plugins.smart.memory.SmartMemoryRetrieval
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val CONFIG_SCHEMA_VERSION = 1
private const val DEFAULT_WAKE_WORDS = "伊迪丝,edith"
private const val DEFAULT_MAX_LEVEL = "L1"
private const val HINT_LIFETIME_MILLIS = 5_000L
private const val DEFAULT_HINT_GRACE_MILLIS = 5_000L

/**
 * How long a wake-opened turn listens for a usable utterance when its configured profile has no timeout to borrow.
 *
 * The window is the profile's own `timeoutMillis` whenever there is one, because that is the number the user chose for
 * this assistant; this is the documented fallback for the configurations that have no profile (or whose profile has
 * gone missing). It is deliberately longer than the ~5.5 s the device measured between the wake segment's blank final
 * and the request's own final.
 */
private const val DEFAULT_LISTEN_WINDOW_MILLIS = 15_000L
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

/** The `ContextChanged` key the foreground app arrives under; the key set is fixed (spec §16.6). */
private const val FOREGROUND_KEY = "app.foreground"

/**
 * The context key the host publishes its 「主动辅助」 state under.
 *
 * Mirrors `edith.phone.model.ProactiveArming.CONTEXT_KEY`: the plugin cannot import the App's class (module
 * direction), so the string is duplicated with a pinning test on each side. The switch has to be readable *now*
 * because local transcription continues while it is off - see [SmartAssistantPlugin.armedNow].
 */
internal const val PROACTIVE_ARMED_KEY = "proactive.armed"

/** Lenient for the same reason the loop is: model-authored arguments are not always strictly formed JSON. */
private val proactiveJson = Json { isLenient = true }

/** Configuration keys this plugin understands; anything else is refused rather than silently ignored. */
private val KNOWN_KEYS = setOf("profileId", "profileRevision", "wakeWords", "maxLevel", "whitelist", "requestConsent",
    "proactiveCooldownMillis", "proactiveSensitivity")

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
 *   wake word, with or without punctuation) is ignored and the bounded window is re-armed instead of ending the turn
 *   (D12). A window that really expires ends `INSUFFICIENT`/`NO_UTTERANCE`, never `BLANK_UTTERANCE`;
 * - **only a final transcript runs the loop** — a partial revision never reaches the model;
 * - **never silence, never a false success** — every run that reaches a terminal state submits a hint: the model's own
 *   text for `COMPLETED`, and a fixed honest sentence for every other outcome;
 * - **`Stop` is final and session-scoped** — it cancels the loop, the armed listening window and the pending output,
 *   and must not submit anything afterwards, and only a `Stop` for this session/generation may do so;
 * - **nothing is replayed across sessions** — the remembered final transcript belongs to the session that produced it,
 *   so `Help` after a re-activation runs this session's utterance or nothing at all.
 *
 * The descriptor declares exactly `WakeWord, Asr, Model, Actions, TextOutput, Context`. `Capability.Context` is what
 * P3.2's proactive path runs on: the host produces the perception signals, so a plugin that did not declare it could
 * never be armed.
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
     * Test-only observation of the plugin's own audit trail.
     *
     * A wake the scene refuses has no turn to key an audit line by, so a JVM test can only prove the refusal was
     * recorded — rather than dropped without a trace — by reading the sink the plugin already owns. The underlying
     * `BoundedAuditLog.entries()` hands out a defensive copy, so this exposes no mutable state and no production
     * caller has any reason to reach for it.
     */
    internal fun auditTrail(): List<AuditEntry> = audit.entries()

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
    @Volatile private var memoryRetrieval: SmartMemoryRetrieval? = null

    /**
     * P3.4: what a finished turn is allowed to write. Same lifetime and the same namespace as [memoryRetrieval]; `null`
     * means "no session", and a turn then writes nothing.
     */
    @Volatile private var memoryExtraction: MemoryExtraction? = null

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
    private var lastFinalTranscript: String? = null
    private var lastFinalTurnId: String? = null

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
     * The hint a proactive turn submitted, and the little state its ACK needs.
     *
     * Deliberately not a [SmartTurn]: a proactive turn has no listening window, no transcript to adopt and no loop, so
     * reusing the wake turn's machinery would arm waits that can never be satisfied. It owns only what the display
     * conversation needs - its identity, its expiry and the submit/ACK race - and is the vehicle [Stop][stopAll]
     * cancels.
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
     * True while a proactive request is on the wire.
     *
     * The wake path's [turn] cannot answer this (a proactive turn has no `SmartTurn`) and the cooldown only starts
     * after the reply, so without this flag two triggers inside one request's window would both reach the model -
     * which is exactly what the plan's "one turn at a time" invariant forbids. Read and written under [gate].
     */
    private var proactiveInFlight = false

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
        return Config(profileId, revision, wakeWords, maxLevel, whitelist, consent,
            proactiveCooldownMillis, proactiveSensitivity)
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
        val memoryLog = MemoryLog(runCatching { context.services.storage() }.getOrNull())
        memoryRetrieval = SmartMemoryRetrieval(memoryLog)
        memoryExtraction = MemoryExtraction(memoryLog)
        // P3.3: the same rule for the confirmation channel. A host that cannot ask the user leaves this null, and the
        // loop then refuses every flagged action (`CONFIRMATION_UNAVAILABLE`) instead of acting unasked.
        confirmation.attach(runCatching { context.services.confirmation() }.getOrNull())
    }

    override fun deactivate(reason: DeactivationReason) {
        val stranded = synchronized(gate) {
            context = null
            active = false
            // The remembered transcript is session state, not plugin state: leaving it behind is exactly the bug
            // `Help` in a later session would replay it. `activate` clears it too, so both ends of the handover do.
            lastFinalTranscript = null
            lastFinalTurnId = null
            resetPerception()
            // P3.4: a session that ends takes its memory boundary with it (the namespace was scoped to it).
            memoryRetrieval = null
            memoryExtraction = null
            detach()
        }
        // P3.3: a session that ends withdraws any question it asked. Leaving one pending would let the *next* session's
        // tap answer this session's action - the same class of leak the display and transcript resets exist to prevent.
        confirmation.cancelAll()
        confirmation.attach(null)
        teardown(stranded)
    }

    /** Must hold [gate]: forgets the session's perception, so nothing survives into the next one. */
    private fun resetPerception() {
        perceptionArmed = false
        lastAcousticLabel = null
        lastContextValues = emptyMap()
        proactiveHint?.cancelled = true
        proactiveHint = null
        proactiveInFlight = false
    }

    private fun configuredWakeWords(): List<String> =
        synchronized(gate) { config?.wakeWords } ?: splitList(DEFAULT_WAKE_WORDS)

    // ---------------------------------------------------------------- events

    override fun handle(event: HostEvent) {
        when (event) {
            is WakeDetected -> synchronized(gate) {
                if (matches(event.envelope)) {
                    openTurn(clock.nowNanos(), event.envelope, refusedWakeWord = event.word)?.let { opened ->
                        // The plugin's half of P3_WAKE: the phone-side builder logs the word/confidence on emission,
                        // and this names the turn the wake opened. A refused wake opens nothing and logs no turn line.
                        P3Log.line("P3_WAKE turn=${opened.turnId}")
                    }
                }
            }

            // P3.2: the host's perception signals. They are remembered rather than acted on here - the utterance is
            // what carries a request, and the label/context are what that request is judged and built with.
            is AcousticEvent -> synchronized(gate) {
                if (matches(event.envelope)) {
                    lastAcousticLabel = event.label
                    perceptionArmed = true
                }
            }

            is ContextChanged -> synchronized(gate) {
                if (matches(event.envelope)) lastContextValues = event.values
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
                val (started, proactive) = synchronized(gate) {
                    if (!matches(event.envelope)) null to null
                    // P3.3: a final that answers a live question is consumed here and nowhere else - it must not open
                    // a wake turn or be reconsidered by the proactive path.
                    else if (confirmation.onTranscript(event.revision)) null to null
                    else {
                        val consumed = acceptTranscript(event.revision)
                        // The segment that opened the turn is the one segment that may never also answer a question
                        // the turn goes on to ask.
                        if (consumed != null) confirmation.excludeSegment(event.revision.turnId)
                        // The proactive path is only consulted when no wake turn took the utterance: a wake turn's own
                        // transcript is not a proactive candidate, and evaluating it anyway only wrote a
                        // `P3_PROACTIVE skip` line into the acceptance vocabulary.
                        consumed to (if (consumed == null) proactiveTrigger(event.revision) else null)
                    }
                }
                if (started != null) launchTurn(started.first, started.second)
                else if (proactive != null) runProactive(proactive)
            }

            // P3.3 (spec §17.4): the host's answer to a question this plugin asked. It settles the gate; the loop,
            // which is blocked on that gate on the turn's own thread, then either executes or refuses.
            is ConfirmationResult -> confirmation.onConfirmationResult(event)
            is OutputStateEvent -> synchronized(gate) {
                if (matches(event.envelope)) {
                    acceptDisplayed(event)
                    acceptProactiveDisplayed(event)
                }
            }

            is UserAction -> when (event.action) {
                UserAction.Action.Stop -> stopAll(event.envelope)
                UserAction.Action.Help -> handleHelp(event.envelope)
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
     * the [`WakeDetected`][edith.contracts.WakeDetected] envelope's `eventId` is used instead. A refusal by the
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
        scene.noteTurn(nowNanos)
        val replaced = turn?.also { it.closed = true }
        val created = buildTurn(nowNanos, current, runsImmediately, transcriptTurnId)
        turn = created
        teardown(replaced)
        // Armed only after the turn is installed, so a scheduled expiry can never find a turn that is not yet the
        // current one. `Help` runs immediately and has nothing to wait for.
        if (!runsImmediately) armListening(created)
        return created
    }

    /** Must be called under [gate]; builds the turn, its hint window and its hint assembler. */
    private fun buildTurn(
        nowNanos: Long,
        current: PluginContext,
        runsImmediately: Boolean,
        transcriptTurnId: String?,
    ): SmartTurn {
        val created = SmartTurn.awaiting(
            turnId = "smart-${UUID.randomUUID()}",
            sessionId = current.sessionId,
            generation = current.generation,
            clock = clock,
            scheduler = scheduler,
            deadlineNanos = SmartTurn.saturatingAdd(nowNanos, listeningWindowNanos()),
            hintGraceNanos = DEFAULT_HINT_GRACE_MILLIS * 1_000_000L,
            hintId = "hint-${UUID.randomUUID()}",
            onEmit = { text -> emit(text) },
        )
        if (transcriptTurnId != null) created.adoptTranscriptTurnId(transcriptTurnId)
        if (runsImmediately) created.running()
        return created
    }

    /**
     * The window a wake-opened turn waits for a usable utterance in.
     *
     * The configured profile's own timeout when there is one — the assistant's deadline is the number the user chose —
     * otherwise [DEFAULT_LISTEN_WINDOW_MILLIS]. A profile that has gone missing therefore still gets a real window
     * rather than a zero-length one: an unrelated configuration problem must not turn every wake into an instant
     * failure.
     */
    private fun listeningWindowNanos(): Long {
        val configuredMillis = config?.let { profileLookup(it.profileId) }?.timeoutMillis ?: 0L
        return if (configuredMillis > 0L) configuredMillis * 1_000_000L else DEFAULT_LISTEN_WINDOW_MILLIS * 1_000_000L
    }

    /**
     * Arms (or re-arms) the turn's bounded listening window. Must be called under [gate] with [owned] installed.
     *
     * A blank or wake-word-only final proves the request has not been spoken yet, so it re-arms the window from that
     * moment instead of ending the turn. The recogniser only finalises a segment after speech, so this cannot spin,
     * and the turn is still released by `Stop`, `deactivate`, `Help` replacing it or its own next wake's turn.
     */
    private fun armListening(owned: SmartTurn) {
        owned.extendDeadline(SmartTurn.saturatingAdd(clock.nowNanos(), listeningWindowNanos()))
        owned.listeningExpiry?.cancel()
        owned.listeningExpiry = scheduler.at(owned.deadlineNanos) { expireListening(owned) }
    }

    /**
     * The listening window expired with nothing usable in it.
     *
     * The turn kept its tentative identity — no transcript was ever adopted — and never ran, so it ends with the
     * honest `INSUFFICIENT` outcome under its own `NO_UTTERANCE` code and submits the fixed "did not catch it"
     * sentence. A single blank final must never be able to short-circuit a turn into `BLANK_UTTERANCE`; only a window
     * that really ran out ends it here.
     */
    private fun expireListening(owned: SmartTurn) {
        val stillWaiting = synchronized(gate) {
            if (turn !== owned || owned.closed || owned.phase != SmartTurnPhase.AwaitingTranscript) {
                false
            } else {
                owned.listeningExpiry = null
                owned.idle()
                true
            }
        }
        if (!stillWaiting) return
        // No `P3_TURN start`: the loop was never about to run. The terminal line is the whole story, and its code
        // says why there was nothing to run.
        P3Log.line("P3_TURN end turn=${owned.turnId} outcome=${AgentOutcome.INSUFFICIENT} code=NO_UTTERANCE steps=0")
        finishWithFailure(owned, FAILURE_HINTS.getValue(AgentOutcome.INSUFFICIENT))
    }

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
     * neither remembered nor adopted, and it re-arms the waiting turn's bounded window instead of ending it.
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
        if (!isUsableUtterance(text)) {
            armListening(current)
            return null
        }
        current.cancelListening()
        // The transcript's own turn id becomes the turn id from here on: the host keys `cancelOutputTurn` and the UI
        // correlate a hint by it, so the run and its hint must carry the ASR turn id rather than the wake's placeholder.
        current.adoptTranscriptTurnId(revision.turnId)
        current.running()
        return current to text
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
        // The turn id is final by now (the final transcript's ASR id has replaced the wake's placeholder), so the
        // start/end pair and the transcript line all name the same turn.
        P3Log.line("P3_TURN start turn=${owned.turnId}")
        if (text.isBlank()) {
            P3Log.line("P3_TURN end turn=${owned.turnId} outcome=${AgentOutcome.FAILED} code=BLANK_UTTERANCE steps=0")
            finishWithFailure(owned, BLANK_UTTERANCE_HINT)
            return
        }
        // The loop is held outside the `try` so the failure path below can still read the human wait it had measured
        // before it threw (review I-1): a transport failure after a late answer is otherwise the one path where the
        // number is lost, and the honest "没能给出结果" sentence would be dropped as expired - the phone acted and the
        // glasses stayed blank.
        var loop: AgentLoop? = null
        val run = try {
            val built = buildLoop(current, configured, owned)
            loop = built
            synchronized(gate) {
                if (turn !== owned || owned.closed) return
                owned.loop = built
            }
            built.run(
                AgentRequest(
                    turnId = owned.turnId,
                    utterance = text,
                    instructions = SmartPrompts.instructions(),
                    profileId = configured.profileId,
                    deadlineNanos = owned.deadlineNanos,
                    // D49: the profile's timeout caps **one model request**; the turn's own budget is the number above,
                    // and it no longer pays for the time a person spends deciding.
                    requestTimeoutNanos = modelRequestTimeoutNanos(
                        config?.let { profileLookup(it.profileId) }?.timeoutMillis,
                    ),
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
            P3Log.line("P3_RUN error=${failure::class.simpleName ?: failure.javaClass.name}")
            // A run that threw never handed its report over, so the measurement is taken from the loop itself - the wait
            // it had already excluded from its budget is exactly what keeps this turn's hint window open.
            AgentRun(
                AgentOutcome.FAILED, "PLUGIN_RUN_THREW", null, emptyList(),
                loop?.measuredHumanWaitNanos ?: 0L,
            )
        }
        val stillOurs = synchronized(gate) {
            if (turn !== owned || owned.closed) {
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
        P3Log.line("P3_TURN end turn=${owned.turnId} outcome=${run.outcome} code=${run.safeCode ?: "-"} " +
            "steps=${run.steps.size}")
        if (!stillOurs) return
        // Review I-1: the loop shifts its *own* budget by the time a person spent deciding (D49), but this turn's hint
        // window is derived from **its** deadline (`hintValidUntil` = deadline + grace). Without moving that too, a
        // result assembled after a late answer is dropped as expired: the phone executes the approved action and the
        // glasses stay blank. The measurement comes back with the run, so the two numbers cannot drift apart.
        if (run.humanWaitNanos > 0L) {
            synchronized(gate) {
                if (turn === owned && !owned.closed) {
                    owned.extendDeadline(SmartTurn.saturatingAdd(owned.deadlineNanos, run.humanWaitNanos))
                }
            }
        }
        // P3.4 §3.2: what this turn leaves behind. Written **before** the outcome is submitted because the turn still
        // owns its hint window here - an acknowledgement emitted afterwards would be dropped as expired, which is
        // exactly the "silent remember" the explicit path exists to avoid.
        rememberFromTurn(text, run)
        submitOutcome(owned, run)
    }

    /**
     * P3.4 §3.2: what this turn earned, into long-term memory, plus the one sentence an explicit instruction is owed.
     *
     * Only steps whose result is `ok` count towards a habit - a refused or failed action is not something the user
     * "keeps doing". Nothing here is logged with content: [MemoryLog] and [MemoryExtraction] print counts, kinds and
     * reason codes only.
     */
    private fun rememberFromTurn(utterance: String, run: AgentRun) {
        val extraction = memoryExtraction ?: return
        val succeeded = run.steps.filter { it.result == "ok" }.map { it.action }
        val report = extraction.afterTurn(utterance, succeeded)
        if (report.explicit) emit(MemoryExtraction.ACKNOWLEDGEMENT)
    }

    /** Builds the single-use loop for one turn: registry, tightened policy, guard, audit and gateway. */
    private fun buildLoop(context: PluginContext, configured: Config, owned: SmartTurn): AgentLoop {
        val registry = ToolRegistry(listOf(context.services.actions()))
        val policy = WhitelistActionPolicy(configured.maxLevel, configured.whitelist)
        val gateway = ModelClientGateway(
            client = context.services.model(),
            profileLookup = profileLookup,
            clock = clock,
            scheduler = scheduler,
            envelopeFor = { requestId, startNanos, endNanos ->
                EventEnvelope(context.sessionId, context.generation, requestId, startNanos, endNanos)
            },
            stop = { owned.closed },
            // P3.4 §3.3: the turn's context parts come from long-term memory, and only from it (the gateway asks about
            // the in-flight utterance).
            memoryRetrieval = memoryRetrieval,
        )
        return AgentLoop(
            gateway = gateway,
            registry = registry,
            policy = policy,
            guard = ProgressGuard(),
            audit = audit,
            clock = clock,
            // P3.3: the loop asks before it acts on anything the policy flagged, and never acts without an answer.
            confirmation = confirmation,
        )
    }

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
        val values = currentContextValues()
        val trigger = ProactiveTrigger(text, lastAcousticLabel, values[FOREGROUND_KEY], clock.nowNanos())
        // "Busy" means a turn is genuinely in flight - waiting for its transcript, running its loop, or a proactive
        // request on the wire. A turn that has **finished** stays installed as `turn` until something replaces or
        // stops it (the wake path does that on purpose, so a late ACK still finds its identity), and counting it as
        // busy would refuse every proactive trigger for the rest of the session. Found on the K40: after one wake turn
        // ended, the first real utterance produced `skip reason=BUSY` instead of a request.
        val busy = turn?.let { it.phase != SmartTurnPhase.Idle } == true || proactiveInFlight
        val skip = proactive.admit(trigger, armed = armedNow(), busy = busy)
        if (skip != null) {
            P3Log.line("P3_PROACTIVE skip reason=$skip")
            return null
        }
        return trigger
    }

    /**
     * One proactive round trip: **one** request, one tool table, and a decision that is either a sentence or silence.
     *
     * The gateway is called directly rather than through an `AgentLoop`, because the loop's whole purpose - feeding
     * tool results back and retrying until it makes progress - is exactly what a proactive turn must not do: the
     * `propose_hint` call *is* the answer, it is never executed, and there is no second turn to run. The request runs
     * **outside** [gate] for the same reason the wake loop does: it blocks on the transport, and `Stop` must be able to
     * mark it cancelled while it waits.
     */
    private fun runProactive(trigger: ProactiveTrigger) {
        val (current, configured, filter) = synchronized(gate) {
            val c = context ?: return
            val cfg = config ?: return
            Triple(c, cfg, proactive)
        }
        val started = clock.nowNanos()
        val (message, contextEntries) = proactiveMessage(trigger)
        val hint = ProactiveHint(hintId = "hint-${UUID.randomUUID()}", turnId = "proactive-${UUID.randomUUID()}")
        synchronized(gate) {
            if (context !== current) return
            proactiveHint?.cancelled = true
            proactiveHint = hint
            proactiveInFlight = true
        }
        P3Log.line("P3_PROACTIVE request ctx=$contextEntries chars=${trigger.transcript.length}")
        // Off the host's dispatcher: the request blocks on the transport, and running it inside `handle` would hold
        // the host's single drain thread for up to the deadline - delaying `Stop` (and its UI thread) and the next
        // wake turn. The task re-checks ownership under `gate` before writing anything back. A test host runs the task
        // inline, which is what keeps the plugin's own tests deterministic.
        current.services.tasks.launch {
            try {
                deliverProactive(current, configured, filter, hint, trigger, message, started)
            } finally {
                synchronized(gate) { proactiveInFlight = false }
            }
        }
    }

    /** The blocking half of one proactive turn; runs on the task scope, never under [gate]. */
    private fun deliverProactive(current: PluginContext, configured: Config, filter: SmartProactive,
        hint: ProactiveHint, trigger: ProactiveTrigger, message: String, started: Long) {
        val gateway = ModelClientGateway(
            client = runCatching { current.services.model() }.getOrElse { failure ->
                P3Log.line("P3_PROACTIVE silence reason=FAILED ms=${elapsedMillis(started)}")
                discard(hint)
                return
            },
            profileLookup = profileLookup,
            clock = clock,
            scheduler = scheduler,
            envelopeFor = { requestId, startNanos, endNanos ->
                EventEnvelope(current.sessionId, current.generation, requestId, startNanos, endNanos)
            },
            stop = { hint.cancelled },
            // P3.4 §3.3: the proactive request carries retrieved memory too - it is a request to the same model about
            // the same person, and it passes the same upload gate.
            memoryRetrieval = memoryRetrieval,
        )
        val reply = try {
            gateway.turn(GatewayTurn(
                turnId = hint.turnId,
                profileId = configured.profileId,
                instructions = SmartPrompts.proactiveInstructions(),
                // The gateway lifts a trailing USER turn into `input`, which is what puts the utterance and the
                // perception last on the wire - the same shape the passive path uses.
                history = listOf(ModelHistoryItem.Text(MessageRole.USER, message)),
                tools = listOf(SmartPrompts.PROPOSE_HINT),
                deadlineNanos = SmartTurn.saturatingAdd(started, SmartPrompts.PROACTIVE_DEADLINE_MILLIS * 1_000_000L),
            ))
        } catch (failure: Throwable) {
            // `D53`① (2026-10-11): this is no longer "the upload gate said no" - there is no such gate any more. It is
            // the model client refusing for its own reasons (an unverified profile operation, a missing capability),
            // which is an honest **failure** of this turn, and the reason vocabulary therefore says `FAILED`.
            //
            // What must **not** change is the cooldown: `noteRequest` runs only on a *successful* hint, so without this
            // call a profile the client keeps refusing would log `P3_PROACTIVE request` and attempt a call on **every**
            // final utterance instead of once per cooldown window (review I1: the earlier revision dropped this call
            // and its comment wrongly claimed the cooldown was already covered).
            filter.noteRefused(clock.nowNanos())
            P3Log.line("P3_PROACTIVE silence reason=FAILED ms=${elapsedMillis(started)}")
            discard(hint)
            return
        }
        val elapsed = elapsedMillis(started)
        when {
            reply.terminal == ModelEnd.CANCELLED -> silence(hint, "CANCELLED", elapsed)
            reply.terminal == ModelEnd.DEADLINE -> silence(hint, "DEADLINE", elapsed)
            reply.terminal != ModelEnd.COMPLETED -> silence(hint, "FAILED", elapsed)
            else -> {
                val call = reply.toolCalls.firstOrNull { it.name == SmartPrompts.PROPOSE_HINT.name }
                val text = call?.let { parseHint(it.argsJson) }
                when {
                    call == null -> silence(hint, "NO_CALL", elapsed)
                    text == null -> silence(hint, "UNPARSABLE", elapsed)
                    text.codePointCount(0, text.length) > SmartPrompts.PROACTIVE_HINT_MAX_CHARS ->
                        silence(hint, "TOO_LONG", elapsed)
                    else -> {
                        filter.noteRequest(clock.nowNanos())
                        filter.noteDedup(trigger)
                        P3Log.line("P3_PROACTIVE hint chars=${text.length} ms=$elapsed")
                        submitProactiveHint(hint, text)
                    }
                }
            }
        }
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

    /**
     * The hint text in one tool call's arguments, or null when they are not a JSON object whose `text` is a non-blank
     * **string**.
     *
     * The schema asks for a string, so a number or an object is a model error, not a hint: coercing `42` into `"42"`
     * would put text on the wearer's screen that no instruction asked for.
     */
    private fun parseHint(argsJson: String): String? = runCatching {
        val element = proactiveJson.parseToJsonElement(argsJson).jsonObject["text"] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        primitive.content.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Records a proactive outcome that shows nothing, and releases the hint slot. */
    private fun silence(hint: ProactiveHint, reason: String, elapsedMillis: Long) {
        P3Log.line("P3_PROACTIVE silence reason=$reason ms=$elapsedMillis")
        discard(hint)
    }

    /** Must hold [gate]: releases the hint only if it is still the current one. */
    private fun discard(hint: ProactiveHint) {
        synchronized(gate) {
            hint.cancelled = true
            if (proactiveHint === hint) proactiveHint = null
        }
    }

    /**
     * Submits the sentence, with the diagnostic plugin's submit/ACK discipline and the same privacy line as the
     * passive path: the log carries the hint's identity and size, never the text.
     */
    private fun submitProactiveHint(hint: ProactiveHint, text: String) {
        val current = currentContext() ?: return
        val now = clock.nowNanos()
        val assistance = synchronized(gate) {
            if (proactiveHint !== hint || hint.cancelled) return
            // The visible window starts here, not at the trigger: a reply that used most of the request deadline must
            // still give the wearer a full window (a review finding).
            val expiry = SmartTurn.saturatingAdd(now, SmartPrompts.PROACTIVE_HINT_TTL_MILLIS * 1_000_000L)
            hint.expiryNanos = expiry
            hint.submitting = true
            hint.pendingAck = false
            Assistance(
                hintId = hint.hintId,
                sessionId = current.sessionId,
                generation = current.generation,
                sourceTurnId = hint.turnId,
                priority = 0,
                text = text,
                optionalSpeech = null,
                expiresAtNanos = expiry,
            )
        }
        val sink: OutputSink? = runCatching { current.services.textOutput() }.getOrNull()
        val result = if (sink != null) {
            P3Log.line("P3_HINT show hint=${assistance.hintId} chars=${assistance.text.length}")
            runCatching { sink.submit(assistance) }.getOrNull()
        } else {
            null
        }
        synchronized(gate) {
            hint.submitting = false
            if (result == OutputSubmission.Accepted && proactiveHint === hint && !hint.cancelled) {
                hint.readable = true
                if (hint.pendingAck && !hint.displayed && clock.nowNanos() < hint.expiryNanos) {
                    hint.pendingAck = false
                    hint.displayed = true
                    P3Log.line("P3_HINT ack hint=${hint.hintId} status=${OutputStatus.Displayed}")
                    onDisplayedAck()
                }
            } else if (result != OutputSubmission.Accepted) {
                // Refused by the host's display gate: one line, no retry, no second request.
                P3Log.line("P3_PROACTIVE hint rejected chars=${assistance.text.length}")
                hint.cancelled = true
                if (proactiveHint === hint) proactiveHint = null
            }
        }
    }

    /** Renders a `Displayed` ACK of the proactive hint; a rejected, repeated or expired hint never counts twice. */
    private fun acceptProactiveDisplayed(event: OutputStateEvent) {
        val hint = proactiveHint ?: return
        if (hint.cancelled || hint.displayed) return
        if (event.hintId != hint.hintId || event.status != OutputStatus.Displayed) return
        if (hint.expiryNanos == 0L || clock.nowNanos() >= hint.expiryNanos) return
        if (hint.submitting) {
            hint.pendingAck = true
        } else if (hint.readable) {
            hint.displayed = true
            P3Log.line("P3_HINT ack hint=${hint.hintId} status=${event.status}")
            onDisplayedAck()
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long = (clock.nowNanos() - startedNanos) / 1_000_000L

    // ---------------------------------------------------------------- output

    /**
     * Turns one terminal run into the hint the wearer sees.
     *
     * `COMPLETED` renders the model's own text; every other outcome renders a fixed honest sentence. There is no path
     * here that submits nothing: a turn that reached a terminal state always says something.
     */
    private fun submitOutcome(owned: SmartTurn, run: AgentRun) {
        val assembler = synchronized(gate) {
            if (turn !== owned || owned.closed) return
            owned.assembler
        } ?: return
        val finalText = run.finalText
        if (run.outcome == AgentOutcome.COMPLETED && !finalText.isNullOrBlank()) {
            assembler.append(finalText)
            assembler.finish(ModelEnd.COMPLETED)
        } else {
            assembler.append(FAILURE_HINTS[run.outcome] ?: FAILURE_HINTS.getValue(AgentOutcome.FAILED))
            assembler.finish(ModelEnd.FAILED)
        }
    }

    /** A terminal state reached before the loop could run at all (a blank utterance, or an expired listening window). */
    private fun finishWithFailure(owned: SmartTurn, text: String) {
        val assembler = synchronized(gate) {
            if (turn !== owned || owned.closed) return
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
     */
    private fun emit(text: String) {
        var owned: SmartTurn? = null
        val hint = synchronized(gate) {
            val current = turn ?: return
            if (current.closed) return
            val context = context ?: return
            val now = clock.nowNanos()
            // P3.2-CLIP: a hint taller than the glasses' window is slid through at a reading pace, so a fixed five
            // seconds cleared the text mid-sentence. The budget adds the slide's own duration; a hint that fits keeps
            // exactly `HINT_LIFETIME_MILLIS`.
            val lifetime = HintReadingBudget.readMillis(text.length, HINT_LIFETIME_MILLIS)
            val expiry = minOf(SmartTurn.saturatingAdd(now, lifetime * 1_000_000L), current.hintValidUntil)
            if (expiry <= now) return
            current.submitting = true
            current.pendingAck = false
            current.hintExpiry = expiry
            owned = current
            Assistance(
                hintId = current.hintId,
                sessionId = context.sessionId,
                generation = context.generation,
                sourceTurnId = current.turnId,
                priority = 0,
                text = text,
                optionalSpeech = null,
                expiresAtNanos = expiry,
            )
        }
        val current = owned ?: return
        val sink: OutputSink? = runCatching { currentContext()?.services?.textOutput() }.getOrNull()
        val stillCurrent = synchronized(gate) { turn === current }
        val result = if (stillCurrent && sink != null) {
            // On submit: the hint's identity and size only. The text itself is the display content and never logged.
            P3Log.line("P3_HINT show hint=${hint.hintId} chars=${hint.text.length}")
            runCatching { sink.submit(hint) }.getOrNull()
        } else {
            null
        }
        synchronized(gate) {
            current.submitting = false
            if (result == OutputSubmission.Accepted && turn === current && !current.closed) {
                current.readable = true
                if (current.pendingAck && !current.displayed && clock.nowNanos() < current.hintExpiry) {
                    current.displayed = true
                    P3Log.line("P3_HINT ack hint=${current.hintId} status=${OutputStatus.Displayed}")
                    onDisplayedAck()
                }
            }
            current.pendingAck = false
        }
    }

    /** Renders a `Displayed` ACK into the turn's own bookkeeping, never into another turn's. Must hold [gate]. */
    private fun acceptDisplayed(event: OutputStateEvent) {
        val owned = turn ?: return
        if (owned.closed || owned.displayed) return
        if (event.hintId != owned.hintId) return
        if (event.status != OutputStatus.Displayed) return
        if (clock.nowNanos() >= owned.hintExpiry) return
        if (owned.submitting) {
            owned.pendingAck = true
        } else if (owned.readable) {
            owned.displayed = true
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
        val stopped = synchronized(gate) {
            if (!matches(envelope)) null
            else {
                // P3.2: a proactive request is in flight outside this lock; marking its hint cancelled is what the
                // gateway's `stop` predicate reads, and what stops a late reply from being shown.
                proactiveHint?.cancelled = true
                proactiveHint = null
                detach()
            }
        }
        teardown(stopped)
    }

    /**
     * Manual entry point (spec keeps `Help` as the non-wake path): run the most recent final transcript **of this
     * session**. It is an explicit user action, so it bypasses the wake cooldown and the awaiting-transcript guard;
     * with no current-session final transcript it does nothing.
     */
    private fun handleHelp(envelope: EventEnvelope) {
        val started = synchronized(gate) {
            if (!matches(envelope)) return
            val text = lastFinalTranscript?.takeIf { it.isNotBlank() } ?: return
            // The reused turn keeps the transcript's own turn id, exactly as a wake-opened turn does.
            openTurn(
                clock.nowNanos(),
                envelope,
                runsImmediately = true,
                transcriptTurnId = lastFinalTurnId,
                bypassWakeGates = true,
            )?.let { it to text }
        } ?: return
        launchTurn(started.first, started.second)
    }

    /** Detaches the current turn: marks it closed and hands it to the caller for teardown. Must hold [gate]. */
    private fun detach(): SmartTurn? = turn?.also {
        it.closed = true
        turn = null
    }

    /** Cancels everything a detached turn owns; the exact teardown the brief requires. */
    private fun teardown(owned: SmartTurn?) {
        if (owned == null) return
        // The armed listening window is part of what the turn owns: releasing it here is what makes a `Stop` during
        // the wait, and a `deactivate`, provably unable to submit anything later.
        owned.cancelListening()
        runCatching { owned.loop?.cancel() }
        owned.cancelAssembler()
        val sessionId = owned.sessionId ?: return
        val generation = owned.generation ?: return
        runCatching { cancelOutputTurn(sessionId, generation, owned.turnId) }
        runCatching { currentContext()?.services?.actions()?.cancel() }
    }

    private fun currentContext(): PluginContext? = synchronized(gate) { context }

    private companion object {
        val SystemMonotonicClock = object : MonotonicClock {
            override fun nowNanos(): Long = System.nanoTime()
        }

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
