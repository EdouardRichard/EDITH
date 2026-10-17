package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.AgentLoop
import com.sheen.rokid.agent.AgentOutcome
import com.sheen.rokid.agent.AgentRequest
import com.sheen.rokid.agent.AgentRun
import com.sheen.rokid.agent.AuditEntry
import com.sheen.rokid.agent.BoundedAuditLog
import com.sheen.rokid.agent.ConfirmationGate
import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.GatewayTurn
import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.ModelClientGateway
import com.sheen.rokid.agent.ProgressGuard
import com.sheen.rokid.agent.RequestFrame
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.agent.SegmentRole
import com.sheen.rokid.agent.ToolRegistry
import com.sheen.rokid.agent.TrimCounts
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.conversationDisplay
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
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechRequest
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.output.HintReadingBudget
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ConfiguredPlugin
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputSink
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolSpec
import com.sheen.rokid.plugin.actions
import com.sheen.rokid.plugin.confirmation
import com.sheen.rokid.plugin.context
import com.sheen.rokid.plugin.embedding
import com.sheen.rokid.plugin.model
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
import com.sheen.rokid.phone.plugin.smarthelper.context.TokenBudget
import com.sheen.rokid.phone.plugin.smarthelper.context.estimatedLength
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
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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
 * The one sentence the assistant says when a hard dependency is missing (spec §1.3 C2).
 *
 * It is deliberately about the assistant rather than about the model: the wearer asked for something and cannot get
 * it, and "本地模型还没准备好" is true, actionable (wait or reinstall) and reveals nothing about which component
 * failed — that belongs in the log line, as a code.
 */
private const val DEPENDENCY_HINT = "本地模型还没准备好，暂时不能用。"

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
    /**
     * edith-0.2 Phase B: the session's transcript. `null` is the pre-Phase-B shape — the plugin keeps no conversation
     * — and is what a test that is about the passive loop wants.
     */
    private val contextForTurn: ContextManager? = null,
    /** The compressor, consulted **after** the turn's memory write (spec §4.9). `null` means never compress. */
    private val compressor: ContextCompressor? = null,
    /**
     * The session's token split (spec §4.3). A seam rather than a constant because the over-budget path
     * (`historyTokens > historyBudget`, spec §4.4) is otherwise unreachable in a test: at the spec's own 1638-token
     * allowance it takes roughly 200 turns at the wake cooldown. The **default is the spec's split**, and a test that
     * injects a smaller one is exercising the real production compressor against a real budget, not a fake.
     */
    private val budget: TokenBudget = TokenBudget(),
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

    /**
     * Test-only observation of the **session's** effective history allowance (spec §4.3).
     *
     * It is the allowance the compressor is handed at the end of a turn: history's own share of the injected budget
     * plus the SKILL and memory budget a session with no SKILL directory and no memories leaves unused. A test reads
     * this to prove the injected `budget` reached the compressor the session actually uses, rather than comparing
     * against a hard-coded copy of the split (which a plugin that ignored the injection would still satisfy).
     */
    internal fun historyBudgetTokensForTest(): Int {
        val categories = budget.categories()
        return categories.history + categories.skill + categories.memory
    }

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
     * Phase 5: the session's per-route stores, or `null` when the wiring supplied none. Session state like
     * [activeContextForTurn]: [activate] adopts the injected map **and empties it**, [deactivate] empties and drops it.
     */
    @Volatile private var activeStores: Map<ContextRoute, ConversationStore>? = null

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
     * Guarded by [skillLock]; [systemSlot] reads the catalog and this map in one critical section so a request cannot
     * pair a newer catalog with an older pulled set.
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
        activeContextForTurn = contextForTurn ?: ContextManager(memory = memoryRetrieval, budget = budget)
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
        activeCompressor = compressor ?: ContextCompressor(
            memory = memory,
            summarizer = summarizer,
            // The plugin's task pool: `compressIfNeeded` runs inside the turn, and an inline summary would hold the
            // caller's thread for a cloud round trip (the same defect the gate had before review C2).
            scope = context.services.tasks,
        )
        // B4: the SKILL library. A host that granted no directory leaves this null and the prompt is simply the
        // system instructions - "no SKILLs installed" is a normal state (spec §3.7), not a degraded one.
        val governor = LibraryGovernor()
        libraryGovernor = governor
        skillManager = runCatching { context.services.storageAccess() }.getOrNull()
            ?.let { SkillManager(StorageAccessSkillDirectory(it), governor = governor) }
        // P3.3: the same rule for the confirmation channel. A host that cannot ask the user leaves this null, and the
        // loop then refuses every flagged action (`CONFIRMATION_UNAVAILABLE`) instead of acting unasked.
        confirmation.attach(runCatching { context.services.confirmation() }.getOrNull())
    }

    override fun deactivate(reason: DeactivationReason) {
        // Phase C: the level is cleared and reported **before** the context goes, because the conversation port is
        // resolved through it - reporting after `context = null` would resolve to nothing and leave the glasses' ring
        // turning until its own bound expired. Reported outside the lock, as `publishBusy` requires.
        synchronized(gate) {
            wakeBusy = false
            proactiveInFlight = false
        }
        publishBusy()
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
            // The session's conversation and compressor go with it: `activate` rebuilds both, and a transcript left
            // behind would be the previous session's words under the new session's envelope.
            activeContextForTurn = null
            activeCompressor = null
            // Phase 5: the injected stores are the same session state, so both ends of the handover empty them.
            activeStores?.values?.forEach { it.clear() }
            activeStores = null
            skillManager = null
            libraryGovernor = null
            // The pulled bodies are session/turn state too: nothing may survive the session that obtained it. The
            // turn owner goes with them, without reusing the generation (a stale turn may still be unwinding).
            resetSkillTurnState()
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
        // A parked trigger describes a session that is ending; running it later would act on a stale world under a
        // session that never observed it.
        parkedProactive = null
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
            AssistantRoute.PROACTIVE to proactiveInFlight,
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

    /** One utterance the wearer made, for the route that consumed it. */
    private fun publishUserMessage(route: ContextRoute, text: String) {
        if (text.isBlank()) return
        // Which conversation this landed in, as a count and a route name and nothing else. Owner report (2026-10-15)
        // asked the obvious question of a two-conversation screen - "which one did this go into?" - and there was no
        // line in the log that could answer it, on either end. The text never appears here (the same rule as
        // `P3_HINT show ... chars=`).
        P3Log.line("P3_CONV said route=${route.toContractRoute().name} chars=${text.length}")
        conversation()?.userMessage(route.toContractRoute(), text)
    }

    /** One message the assistant is telling the wearer, for the route that produced it. */
    private fun publishAssistantMessage(route: ContextRoute, text: String) {
        if (text.isBlank()) return
        P3Log.line("P3_CONV answer route=${route.toContractRoute().name} chars=${text.length}")
        conversation()?.assistantMessage(route.toContractRoute(), text)
    }

    /** The contract's name for a route; the plugin's own enum stays internal to this module. */
    private fun ContextRoute.toContractRoute(): AssistantRoute = when (this) {
        ContextRoute.WAKE -> AssistantRoute.WAKE
        ContextRoute.PROACTIVE -> AssistantRoute.PROACTIVE
    }


    // ---------------------------------------------------------------- events

    override fun handle(event: HostEvent) {
        when (event) {
            is WakeDetected -> {
                synchronized(gate) {
                    if (matches(event.envelope)) {
                        openTurn(clock.nowNanos(), event.envelope, refusedWakeWord = event.word)?.let { opened ->
                            // The plugin's half of P3_WAKE: the phone-side builder logs the word/confidence on
                            // emission, and this names the turn the wake opened. A refused wake opens nothing and logs
                            // no turn line.
                            P3Log.line("P3_WAKE turn=${opened.turnId}")
                        }
                    }
                }
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
                if (started != null) {
                    // Phase C: the utterance this turn consumed is the wearer's own side of the conversation. Published
                    // here rather than inside the lock: the port is the host's, and a host that answered synchronously
                    // must not be able to re-enter this plugin while `gate` is held.
                    publishUserMessage(ContextRoute.WAKE, started.second)
                    launchTurn(started.first, started.second)
                } else if (proactive != null) runProactive(proactive)
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
        // B9 (spec §1.3 C2): a hard dependency that is missing refuses the turn *here*, before the loop is built and
        // before the listening window has anything to wait for. A refusal is a per-turn judgement, not a latch: the
        // models are resident and their state can change between turns, so a slow load that finishes later serves
        // the next turn instead of leaving the plugin dead.
        serviceRefusal()?.let { reason ->
            P3Log.line("P3_DEPENDENCY reason=$reason")
            P3Log.line("P3_TURN end turn=${owned.turnId} outcome=${AgentOutcome.FAILED} code=$reason steps=0")
            finishWithFailure(owned, DEPENDENCY_HINT)
            return
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
        // One ledger per turn, handed to the `notify_user` tool the loop carries (spec §6.3's guard is per-turn).
        val ledger = NotifyLedger()
        // B1/B6/spec §4.2: the session's own assembly — the route's instructions, the transcript this turn
        // continues, and the memory for the utterance. Read once per turn so the three come from one snapshot.
        // `null` when the wiring supplied no manager, which is every test that is about the passive loop; the request
        // then falls back to the pre-Phase-B shape and the gateway retrieves memory itself.
        //
        // SKILL is deliberately not part of this assembly since Phase 6: the managed text would double-inject what
        // `systemSlot` already carries, so the manager is de-skilled and the catalog/bodies ride the frame's SKILL
        // segments (design D-1).
        //
        // Phase 6 (obligation 1): the turn's **single** governor observation opens here, before the turn's first
        // request is assembled and never from inside the per-request builder — the observation unit is the turn
        // (design D-3), and a call per request would inflate drift by every tool round-trip.
        val skillTurn = beginSkillTurn(owner = true)
        val prompt = activeContextForTurn?.let { contexts ->
            runCatching { contexts.buildPrompt(ContextRoute.WAKE, text) }.getOrNull()
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
        val store = activeStores?.get(ContextRoute.WAKE)
        if (store != null) {
            val history = store.history()
            P3Log.line(
                "P3_CONTEXT route=${ContextRoute.WAKE} store=${history.size} " +
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
        // A **builder**, not a one-shot frame: a tool round makes this run ask the model a second time, and that
        // request must be rendered by the same renderer from the transcript it continues. Only the store path gets one;
        // the legacy path keeps its frame-less behaviour byte for byte.
        val frameFor = store?.let { frameBuilderFor(it, ContextRoute.WAKE, text, prompt, toolSpecsOf(registry)) }
        val run = try {
            val built = buildLoop(configured, registry, gateway)
            loop = built
            synchronized(gate) {
                if (turn !== owned || owned.closed) {
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
                    instructions = prompt?.instructions ?: SmartPrompts.instructions(),
                    profileId = configured.profileId,
                    deadlineNanos = owned.deadlineNanos,
                    // D49: the profile's timeout caps **one model request**; the turn's own budget is the number above,
                    // and it no longer pays for the time a person spends deciding.
                    requestTimeoutNanos = modelRequestTimeoutNanos(
                        config?.let { profileLookup(it.profileId) }?.timeoutMillis,
                    ),
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
                if (turn === owned && !owned.closed) {
                    owned.extendDeadline(SmartTurn.saturatingAdd(owned.deadlineNanos, run.humanWaitNanos))
                }
            }
        }
        // P3.4 §3.2: what this turn leaves behind. Written **before** the outcome is submitted because the turn still
        // owns its hint window here - an acknowledgement emitted afterwards would be dropped as expired, which is
        // exactly the "silent remember" the explicit path exists to avoid.
        rememberFromTurn(text, run)
        // B6 / spec §4.9: the transcript and the compression come **after** the memory write, in that order, so a
        // message that compression is about to discard is already in the store. Doing it the other way round would
        // lose exactly the content compression exists to preserve.
        recordTurnAndCompress(ContextRoute.WAKE, text, run, ledger.notifications, gateway.lastTrim)
        submitOutcome(owned, run, ledger)
        releaseParkedProactive()
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
        // The two counts the frame path is allowed to log (Task 4's `lastTrim`): how many whole turns cut ① dropped
        // and whether cut ③ is still owed. Nothing else from the report is printed, and the report has no text at all.
        trim?.let {
            P3Log.line("P3_CONTEXT trim route=$route droppedTurns=${it.droppedTurns} compressionOwed=${it.compressionOwed}")
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
            store.appendUser(userText)
            store.appendAssistant(reply)
            // D-4's ③ gate: cut ③ may start **only after cut ① really happened**. `compressionOwed` alone is also true
            // for an over-budget frame whose flow holds no turn to give (an empty store, or memory as the only thing
            // trimmed), and a summary started there would be invented content.
            val owed = trim != null && trim.droppedTurns > 0 && trim.compressionOwed
            activeCompressor?.compressStore(store, contexts.historyBudgetTokens(), owed)
            return
        }
        val context = contexts.context(route)
        context.appendUser(userText)
        contexts.appendAssistantMessage(route, reply)
        activeCompressor?.compressIfNeeded(context, contexts.historyBudgetTokens())
    }

    /**
     * The turn's tool registry: the host's action set plus `notify_user` (B7 — the model's only channel to the wearer,
     * spec §6.1) and `load_skill` (Phase 6 — the catalog's only way to a body, design D-1/D-2). Built per turn so the
     * frame's table and the loop's registry are literally the same table.
     *
     * `load_skill` is the plugin's **one** tool instance (see [loadSkillTool]); this method must never construct a new
     * one, or the counter receipt restarts and `ProgressGuard` mistakes a working pull for `NO_PROGRESS`.
     */
    private fun registryFor(context: PluginContext, owned: SmartTurn, ledger: NotifyLedger): ToolRegistry =
        ToolRegistry(
            listOf(
                context.services.actions(),
                // B7: `notify_user` is the model's only channel to the wearer (spec §6.1). It rides the action
                // registry rather than a new tool port, and `NotifyUserTool`'s KDoc records what that choice costs.
                NotifyUserTool { channel, text -> onNotify(owned, ledger, channel, text) },
                loadSkillTool,
            ),
        )

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
        stop = { owned.closed },
    )

    /**
     * Phase 5's request shape for one turn: the system slot (instructions + the fixed memory policy), the route's
     * snapshot, the memory the assembly retrieved, and this turn's utterance **last**.
     *
     * The plugin's job stops at "segments and their attribution": the memory is *not* folded into the first user
     * message here. That is `ModelClientGateway.frameToTranscript`'s single job, and doing it in two places would give
     * two renderers that drift.
     *
     * @param store the route's session store, whose [ConversationStore.snapshot] opens the flow.
     * @param prompt the session assembly, or `null` when it could not be built — the instructions then fall back to
     *   the route's own prompt and the flow carries no memory, which is the shape a host without a manager produces.
     */
    private fun buildFrame(
        store: ConversationStore,
        route: ContextRoute,
        userInput: String,
        prompt: PromptAssembly?,
        tools: List<ToolSpec>,
    ): RequestFrame {
        val flow = buildList {
            addAll(store.snapshot())
            addAll(prompt?.memorySegments.orEmpty())
            add(Segment(SegmentOrigin.USER_INPUT, trusted = true, text = userInput))
        }
        return RequestFrame(
            systemSegments = systemSlot(route, prompt),
            conversationFlow = flow,
            tools = tools,
        )
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
        userInput: String,
        prompt: PromptAssembly?,
        tools: List<ToolSpec>,
    ): (List<ModelHistoryItem>) -> RequestFrame {
        // Where the store's items end: the utterance sits exactly here, and only the loop appends past it.
        val pinnedCount = store.history().size
        return { transcript ->
            val retryIndex = retryIndexIn(transcript, pinnedCount)
            RequestFrame(
                systemSegments = systemSlot(route, prompt),
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
                    add(Segment(SegmentOrigin.USER_INPUT, trusted = true, text = userInput))
                },
                tools = tools,
            )
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
     * The system slot both frame paths share: the route's instructions, the SKILL layer, and the fixed memory policy.
     *
     * Phase 6 (design D-1/D-2, obligations 4 and 5): the SKILL layer is **one catalog segment** (only when the catalog
     * is non-blank — never a heading with nothing under it) followed by **one segment per body pulled this turn**, in
     * pull order. `systemSlot` is called per request, which is what makes a pull visible on the next request of the
     * same turn; the governor's observation unit stays the turn because [beginSkillTurn]/[endSkillTurn] own that
     * boundary. A library that throws contributes no segment rather than failing the request (spec §3.7).
     */
    private fun systemSlot(route: ContextRoute, prompt: PromptAssembly?): List<Segment> = buildList {
        add(
            Segment(
                SegmentOrigin.INSTRUCTIONS,
                trusted = true,
                text = prompt?.instructions ?: when (route) {
                    ContextRoute.WAKE -> SmartPrompts.instructions()
                    ContextRoute.PROACTIVE -> SmartPrompts.proactiveInstructions()
                },
            ),
        )
        // The catalog and the pulled bodies are read in **one** critical section: the catalog mutates the session's
        // governor (not thread-safe) and both halves must describe the same turn, so an ending turn on the other pool
        // thread cannot clear the bodies between the two reads.
        val (catalog, bodies) = synchronized(skillLock) {
            val text = runCatching { skillManager?.catalog() }.getOrNull().orEmpty()
            text to loadedSkillBodies.values.toList()
        }
        if (catalog.isNotBlank()) {
            add(Segment(SegmentOrigin.SKILL, trusted = true, text = catalog))
        }
        bodies.forEach { skill ->
            add(Segment(SegmentOrigin.SKILL, trusted = true, text = bodySegmentText(skill)))
        }
        add(Segment(SegmentOrigin.MEMORY_POLICY, trusted = true, text = SmartPrompts.MEMORY_POLICY))
    }

    /**
     * One pulled body as its own `SKILL` segment. The heading names the entry so the model can tell which body this is
     * when it pulled more than one this turn; the body follows verbatim (D-2: the body travels whole, never summarised).
     */
    private fun bodySegmentText(skill: Skill): String = "## SKILL 正文: ${skill.name}\n${skill.body}"

    /** Builds the single-use loop for one turn from the registry and gateway its caller already built. */
    private fun buildLoop(configured: Config, registry: ToolRegistry, gateway: ModelClientGateway): AgentLoop =
        AgentLoop(
            gateway = gateway,
            registry = registry,
            policy = WhitelistActionPolicy(configured.maxLevel, configured.whitelist),
            guard = ProgressGuard(),
            audit = audit,
            clock = clock,
            // P3.3: the loop asks before it acts on anything the policy flagged, and never acts without an answer.
            confirmation = confirmation,
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
        val busy = turn?.let { it.phase != SmartTurnPhase.Idle } == true || proactiveInFlight
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
            GateForm.NOUL -> NoulGate(service, threshold = com.sheen.rokid.phone.plugin.smarthelper.proactive.NoulThreshold.VALUE)
            GateForm.CHOICE -> ChoiceGate(service)
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
            return 0L
        }
        val generation = ++skillTurnGeneration
        skillTurnOwner = generation
        // An owner begin starts a turn with no pulled bodies. The previous owner (if any) is being replaced; its late
        // end is made harmless by the generation check, not by this clear.
        loadedSkillBodies.clear()
        runCatching { skillManager?.beginTurn() }
        generation
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

    /** Test-only: the system slot the next request for [route] would carry, built by the production [systemSlot]. */
    internal fun systemSlotForTest(route: ContextRoute): List<Segment> = systemSlot(route, null)

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
        synchronized(gate) {
            if (context !== current) return
            proactiveHint?.cancelled = true
            proactiveHint = hint
            proactiveInFlight = true
        }
        // Phase C: the proactive route is now working, and the wearer's ring should say so from the trigger rather than
        // from the first thing that happens to arrive. Outside the lock, as `publishBusy` requires.
        publishBusy()
        P3Log.line("P3_PROACTIVE request ctx=$contextEntries chars=${trigger.transcript.length}")
        // Off the host's dispatcher: the request blocks on the transport, and running it inside `handle` would hold
        // the host's single drain thread for up to the deadline - delaying `Stop` (and its UI thread) and the next
        // wake turn. The task re-checks ownership under `gate` before writing anything back. A test host runs the task
        // inline, which is what keeps the plugin's own tests deterministic.
        current.services.tasks.launch {
            // Phase 6 (obligation 1): the proactive turn's SKILL boundary opens here, before `deliverProactive`'s
            // blocking work, and closes in the `finally` that every proactive ending passes through - a hint, a
            // decline, a failure, a cancellation or a throw. The proactive route has no `load_skill` tool, so its
            // `begin` is a **joiner** whenever a wake turn already owns the lifecycle: it must not clear that turn's
            // pulled bodies. When it is alone it owns its own turn, which is what gives it its own observation
            // instead of folding it into the next wake turn (design D-3).
            val skillGeneration = beginSkillTurn(owner = false)
            try {
                deliverProactive(current, configured, filter, hint, trigger, message, started)
            } finally {
                // Phase C: this is the one place every proactive ending passes through - a hint, a decline, a failure,
                // a cancellation - which is why the level is cleared here rather than on each branch. The report happens
                // outside the lock, as `publishBusy` requires.
                //
                // Phase 6 (obligations 2 and 5): the same is true of the proactive turn's SKILL bookkeeping. Closing it
                // here is what keeps the proactive turn and the next wake turn **two** observations instead of one
                // folded turn, and a stale close from a turn that has already been replaced is a no-op.
                endSkillTurn(skillGeneration)
                synchronized(gate) { proactiveInFlight = false }
                publishBusy()
            }
        }
    }

    /** The blocking half of one proactive turn; runs on the task scope, never under [gate]. */
    private fun deliverProactive(current: PluginContext, configured: Config, filter: SmartProactive,
        hint: ProactiveHint, trigger: ProactiveTrigger, message: String, started: Long) {
        // Phase 6 (obligation 1): the proactive turn's `beginTurn()` opened at the task launch in `runProactive`,
        // before this blocking half; its matching `endTurn()` is that launch's `finally`. Neither is here so the
        // whole turn - including this function throwing - is inside the pair.
        //
        // The intervention gate runs **here**, on the task scope, and that placement is load-bearing: it is a
        // local-model forward pass (~231 ms GPU / ~742 ms CPU per A5), and the plugin's own rule forbids one inside
        // `handle` - it would hold the host's single drain thread *and* the plugin lock, putting `Stop`, confirmations
        // and the next wake turn behind a model call. By this point the trigger has passed P3's admission and
        // throttle (C11), so this is the last decision before the cloud request.
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
                discard(hint)
                return
            }
        }
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
        )
        // B1/B3/B6 / spec §4.2: the proactive route consumes the **session's own** assembly exactly as the wake route
        // does — its transcript comes from `buildPrompt` under `ContextRoute.PROACTIVE` (the SKILL layer arrives
        // through the shared `systemSlot`, not through this assembly). Before this, the route hand-built
        // `SmartPrompts.proactiveInstructions()` plus one history item, so the manager's PROACTIVE context was
        // constructed and never read: "two independent conversations" held for the wake route only. `null` is the
        // pre-Phase-B shape a test without a manager gets.
        val prompt = activeContextForTurn?.let { contexts ->
            runCatching { contexts.buildPrompt(ContextRoute.PROACTIVE, trigger.transcript) }.getOrNull()
        }
        // Phase 5: with a store injected, this route's request is a `RequestFrame` too — the PROACTIVE snapshot, the
        // memory this assembly retrieved, and the perception message last. The frame's user segment is `message`, not
        // the trigger: the trigger is what the transcript records (see `recordProactiveTurn`), while the message is
        // what this request asks the model about.
        val frame = activeStores?.get(ContextRoute.PROACTIVE)?.let {
            buildFrame(it, ContextRoute.PROACTIVE, message, prompt, listOf(SmartPrompts.PROPOSE_HINT))
        }
        val reply = try {
            gateway.turn(GatewayTurn(
                turnId = hint.turnId,
                profileId = configured.profileId,
                instructions = prompt?.instructions ?: SmartPrompts.proactiveInstructions(),
                // The gateway lifts a trailing USER turn into `input`, which is what puts the utterance and the
                // perception last on the wire - the same shape the passive path uses. The assembly's history ends on
                // this turn's trigger, so that last item is dropped and the full message below becomes the input:
                // passing it whole would put the observation to the model twice.
                history = (prompt?.history?.dropLast(1).orEmpty() +
                    ModelHistoryItem.Text(MessageRole.USER, message)),
                tools = listOf(SmartPrompts.PROPOSE_HINT),
                deadlineNanos = SmartTurn.saturatingAdd(started, SmartPrompts.PROACTIVE_DEADLINE_MILLIS * 1_000_000L),
                frame = frame,
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
                        // B3/B6 / spec §4.9: the proactive route records its own turn into its own transcript and
                        // compresses it against the same budget, **before** the hint is submitted. The order is the
                        // submission's, not the compressor's: the compressor's truncation is what writes the dropped
                        // messages to memory, and the trigger belongs to the conversation whether or not the display
                        // accepted it.
                        recordProactiveTurn(trigger.transcript, text, gateway.lastTrim)
                        submitProactiveHint(hint, text)
                    }
                }
            }
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
    private fun recordProactiveTurn(triggerTranscript: String, hintText: String, trim: TrimCounts?) {
        if (triggerTranscript.isBlank()) return
        // Phase C: the trigger is what this route records as its user side (see this method's note above), so it is also
        // what the wearer's conversation shows as their own words on the proactive route.
        publishUserMessage(ContextRoute.PROACTIVE, triggerTranscript)
        publishAssistantMessage(ContextRoute.PROACTIVE, hintText)
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
    private fun submitOutcome(owned: SmartTurn, run: AgentRun, ledger: NotifyLedger) {
        val assembler = synchronized(gate) {
            if (turn !== owned || owned.closed) return
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
            is NotifyDecision.Show -> emit(text, decision.optionalSpeech)
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
    private fun emit(text: String, optionalSpeech: SpeechRequest? = null) {
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
            owned = current
            Assistance(
                hintId = current.hintId,
                sessionId = context.sessionId,
                generation = context.generation,
                sourceTurnId = current.turnId,
                                // Stated rather than defaulted, so the default is never what decides this.
                route = AssistantRoute.WAKE,
                priority = 0,
                text = text,
                optionalSpeech = optionalSpeech,
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
        // Phase C: the conversation record gets the sentence first - it is what the wearer is being told - and the
        // "working" level then ends, so the ring stops exactly when the answer appears rather than a moment before it.
        publishAssistantMessage(ContextRoute.WAKE, hint.text)
        synchronized(gate) { wakeBusy = false }
        // Outside the lock, as `publishBusy` requires: the host's port is what ends the ring.
        publishBusy()
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
                // Phase C: an emergency stop ends whatever was being worked on, so the ring must stop with it - this
                // is one of the two paths that can end a turn without an answer ever being produced.
                wakeBusy = false
                detach()
            }
        }
        teardown(stopped)
        // Outside the lock, as `publishBusy` requires.
        publishBusy()
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
        // Phase C: Help opens a real turn and must raise the same level a wake does, or the ring never starts on a
        // path that is an acceptance row - and the idle emit reports at the end would be an unpaired report. Outside
        // the lock, as publishBusy requires.
        publishBusy()
        publishUserMessage(ContextRoute.WAKE, started.second)
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
private fun ModelHistoryItem.toSegment(): Segment = when (this) {
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
                },
            )
        }.toString(),
    )
}
