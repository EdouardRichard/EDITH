package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextCompressor
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptKind
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptPlan
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptSection

import com.sheen.rokid.agent.ContextPolicy
import com.sheen.rokid.agent.ContextTuning

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.HostServices
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.OutputSink
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.StorageAccess
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.plugin.WakeWordRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Test-only doubles for `plugins-smart`. This is a non-`*Test` source file on purpose (the brief's rule): the tests
 * build their own fakes here rather than reaching into another module's test sources, and `plugins-smart` deliberately
 * gains no `test-support` dependency.
 *
 * The names carry a `Smart`/`Fake` prefix so they can never be mistaken for production types, and every one of them
 * records what the plugin did to it, so a test can assert on real behaviour instead of on a mock's own bookkeeping.
 */

/** A clock the test drives by hand; the plugin never sees wall time. */
internal class SmartFakeClock(var nowNanos: Long = 1_000_000_000L) : MonotonicClock {
    /**
     * When non-zero, every read moves the clock forward by this much.
     *
     * A **monotonic** fake clock and a bounded wait are a deadlock waiting to happen: `ModelClientGateway` polls
     * `clock.nowNanos() >= deadlineNanos` after each 10 ms condition wait, so a stream that never delivers a terminal
     * spins forever against a clock that never moves — a hang, not a failure. Tests that want the poll loop to reach
     * its deadline (rather than the test advancing the clock itself) turn this on; the default of zero keeps every
     * existing test's behaviour byte for byte.
     */
    var autoAdvanceNanos: Long = 0L

    override fun nowNanos(): Long {
        val at = nowNanos
        if (autoAdvanceNanos != 0L) nowNanos += autoAdvanceNanos
        return at
    }

    fun advance(nanos: Long) { nowNanos += nanos }
}

/** A cancellation handle that counts its cancels, so a test can prove a stream really was torn down. */
internal class SmartFakeCancellation : CancellationHandle {
    val cancels = AtomicInteger()
    val cancelled: Boolean get() = cancels.get() > 0
    override fun cancel() { cancels.incrementAndGet() }
}

/**
 * A deadline scheduler the test drains explicitly at the clock's current time. Tasks are released in deadline order,
 * and a task may schedule further tasks, so [runDue] loops until the clock has nothing left to release.
 */
internal class SmartFakeScheduler(private val clock: MonotonicClock) : DeadlineScheduler {
    private class Entry(val deadline: Long, val sequence: Long, val task: () -> Unit, var cancelled: Boolean = false)

    private val entries = mutableListOf<Entry>()
    private var sequence = 0L

    override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
        val entry = Entry(deadlineNanos, sequence++, task)
        entries += entry
        return object : CancellationHandle { override fun cancel() { entry.cancelled = true } }
    }

    /**
     * How many deadlines are armed and not yet released or cancelled.
     *
     * A listening window is a scheduled task like any other, so this is the only observable that can prove a turn
     * **armed** its bounded wait and that `Stop` released it again — behaviour no event can show, because a cancelled
     * deadline is exactly the one that never fires.
     */
    fun pending(): Int = entries.count { !it.cancelled }

    fun runDue() {
        while (true) {
            val next = entries
                .filter { it.deadline <= clock.nowNanos() }
                .minWithOrNull(compareBy({ it.deadline }, { it.sequence }))
                ?: return
            entries.remove(next)
            if (!next.cancelled) next.task()
        }
    }
}

/** Records every `declare`; [accept] models a host that takes the declaration. */
internal class SmartFakeWakeWords(private val accept: Boolean = true) : WakeWordRegistry {
    val declarations = mutableListOf<List<String>>()
    override fun declare(words: List<String>): Boolean {
        declarations += words
        return accept
    }

    override fun current(): List<String> = declarations.lastOrNull() ?: emptyList()
}

/**
 * A [ConversationDisplay] that records every call, including the 0.24 official ones.
 *
 * `ConversationDisplay`'s 0.24 methods are default no-ops, which is right for old hosts and old fakes - but a test
 * about the official conversation must observe the levels it produced, so this fake overrides them and keeps them in
 * call order alongside `working`/`idle`.
 */
internal class SmartRecordingDisplay : ConversationDisplay {
    data class StateCall(val route: AssistantRoute, val turnId: String, val status: Int, val sequence: Long)
    data class UserPartialCall(
        val route: AssistantRoute,
        val turnId: String,
        val revision: Long,
        val text: String,
    )
    data class UserFinalCall(
        val route: AssistantRoute,
        val turnId: String,
        val entryId: String,
        val text: String,
    )
    data class AssistantPartialCall(
        val route: AssistantRoute,
        val turnId: String,
        val hintId: String,
        val revision: Long,
        val text: String,
    )
    data class AssistantFinalCall(
        val route: AssistantRoute,
        val turnId: String,
        val hintId: String,
        val text: String,
    )
    data class AssistantNoticeCall(
        val route: AssistantRoute,
        val text: String,
    )
    data class ToolStatusCall(
        val route: AssistantRoute,
        val turnId: String,
        val phase: ToolPhase,
        val label: String,
    )

    val calls = mutableListOf<String>()
    val states = mutableListOf<StateCall>()
    val idleCalls = mutableListOf<Pair<AssistantRoute, Long>>()
    val closes = mutableListOf<AssistantRoute>()
    val userPartials = mutableListOf<UserPartialCall>()
    val userFinals = mutableListOf<UserFinalCall>()
    val assistantPartials = mutableListOf<AssistantPartialCall>()
    val assistantFinals = mutableListOf<AssistantFinalCall>()
    val assistantNotices = mutableListOf<AssistantNoticeCall>()
    val toolStatuses = mutableListOf<ToolStatusCall>()

    val statuses: List<Int> get() = states.map { it.status }

    /**
     * Runs at the end of [closePanel] - i.e. while the conversation cycle still holds its lock, before the queued
     * `cancelTurn` callback. The seam for the I4 interleaving test: a wake that opens the next turn in that gap must
     * not be torn down by the close that was decided for the old one.
     */
    var onClosePanel: (() -> Unit)? = null

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }

    override fun userMessage(route: AssistantRoute, text: String) {
        calls += "user:${route.name}:$text"
    }

    override fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) {
        userPartials += UserPartialCall(route, turnId, revision, text)
        calls += "partial:${route.name}:$turnId:$revision:$text"
    }

    override fun userFinal(route: AssistantRoute, turnId: String, entryId: String, text: String) {
        userFinals += UserFinalCall(route, turnId, entryId, text)
        // Deliberately the same shape as `userMessage`: this fake records the semantic "the wearer's line", and the
        // existing level-pairing tests should not care which of the two ports carried it.
        calls += "user:${route.name}:$text"
    }

    override fun assistantMessage(route: AssistantRoute, text: String) {
        calls += "said:${route.name}:$text"
    }

    override fun assistantPartial(
        route: AssistantRoute,
        turnId: String,
        hintId: String,
        revision: Long,
        text: String,
    ) {
        assistantPartials += AssistantPartialCall(route, turnId, hintId, revision, text)
        calls += "answer:${route.name}:$turnId:$hintId:$revision:$text"
    }

    override fun assistantFinal(route: AssistantRoute, turnId: String, hintId: String, text: String) {
        assistantFinals += AssistantFinalCall(route, turnId, hintId, text)
        // The semantic equivalent of the old `assistantMessage`: this fake records "the assistant's line", and the
        // level-pairing tests must not care which of the two ports carried it.
        calls += "said:${route.name}:$text"
    }

    override fun assistantNotice(route: AssistantRoute, text: String) {
        assistantNotices += AssistantNoticeCall(route, text)
        calls += "notice:${route.name}:$text"
    }

    override fun toolStatus(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) {
        toolStatuses += ToolStatusCall(route, turnId, phase, label)
        calls += "tool:${route.name}:$turnId:${phase.name}:$label"
    }

    override fun working(route: AssistantRoute) {
        calls += "working:${route.name}"
    }

    override fun idle(route: AssistantRoute) {
        calls += "idle:${route.name}"
    }

    override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
        states += StateCall(route, turnId, status, sequence)
        calls += "state:${route.name}:$turnId:$status:$sequence"
    }

    override fun showIdle(route: AssistantRoute, sequence: Long) {
        idleCalls += route to sequence
        calls += "showIdle:${route.name}:$sequence"
    }

    override fun closePanel(route: AssistantRoute) {
        closes += route
        calls += "close:${route.name}"
        onClosePanel?.invoke()
    }
}

/** Records every executed action and every `cancel`, and answers with a scripted outcome. */
internal class SmartFakeActions(
    val descriptors: MutableList<ActionDescriptor> = mutableListOf(),
) : ActionExecutor {
    val executed = mutableListOf<Pair<String, String>>()

    /** Every call the plugin's confirmation wrapper made to the host classifier, in order. */
    val prechecks = mutableListOf<Pair<String, String>>()
    var cancellations = 0
    var outcome: (String, String) -> ActionResult = { _, _ -> ActionResult(ActionOutcome.Done, "已记录") }

    /**
     * The classifier's verdict for one call. [PrecheckDecision.Allow] keeps the pre-4B shape; a test that exercises
     * command-level confirmation installs `RequireConfirmation` here.
     */
    var precheckDecision: (String, String) -> ActionPrecheck =
        { _, _ -> ActionPrecheck(PrecheckDecision.Allow) }

    /** Task 12 (R59): the runtime-state line the plugin reads through the scoped executor, or `null`. */
    var runtimeStateLine: String? = null

    override fun descriptors(): List<ActionDescriptor> = descriptors

    override fun execute(name: String, argsJson: String): ActionResult {
        executed += name to argsJson
        return outcome(name, argsJson)
    }

    override fun cancel() { cancellations++ }

    override fun precheck(name: String, argsJson: String): ActionPrecheck {
        prechecks += name to argsJson
        return precheckDecision(name, argsJson)
    }

    override fun runtimeState(): String? = runtimeStateLine
}

/**
 * The `ModelClient` the plugin's gateway talks to. [handler] runs on the calling thread and decides what the stream
 * does — inline events model a synchronous transport, and doing nothing models a stream that only `stop` or the
 * deadline can end, which is what lets a test drive `Stop`/`deactivate` reentrantly.
 */
internal class SmartFakeModel(
    private val handler: (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit = { _, _ -> },
) : ModelClient {
    val requests = mutableListOf<ModelRequest>()
    val callbacks = mutableListOf<(ModelStreamEvent) -> Unit>()
    val handles = mutableListOf<SmartFakeCancellation>()

    /** Scripted stream behaviour, assignable per test; the constructor handler stays the default. */
    var onRequest: (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit = handler

    override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        requests += request
        callbacks += listener
        val handle = SmartFakeCancellation()
        handles += handle
        onRequest(request, listener)
        return handle
    }
}

/** Records every hint the plugin submitted; [submission] lets a test model a rejecting host. */
internal class SmartFakeOutput : OutputSink {
    val hints = mutableListOf<Assistance>()
    var submission: OutputSubmission = OutputSubmission.Accepted

    /**
     * Runs *inside* `submit`, before the result is returned — the host's live UI really does deliver an ACK this way,
     * which is the race the plugin's `pendingAck` bookkeeping exists for.
     */
    var onSubmit: ((Assistance) -> Unit)? = null

    override fun submit(assistance: Assistance): OutputSubmission {
        onSubmit?.invoke(assistance)
        hints += assistance
        return submission
    }
}

/**
 * The host services the plugin is activated with: a capability map, a grant set and a task scope.
 *
 * **The grant set is the point.** The real host hands the plugin a scope that refuses any capability the plugin did not
 * declare, and this fake used to answer from its provider map alone - so every port was reachable here and a
 * capability the plugin *consumed but never declared* was invisible to the entire suite. That is not hypothetical: the
 * plugin published nothing to the phone's conversation view on the device (no `SAID`, no `BUSY`, an empty 助手 page)
 * while every test was green, because `ConversationDisplay` was missing from the descriptor and the optional-port
 * access degraded silently. Refusing here is what makes the rig behave like the host it stands in for.
 *
 * The default scope runs the block **inline**, which is what keeps the plugin's own tests deterministic (the same
 * reason the P3.2 proactive tests rely on). [OffThreadTasks] models production instead: there the plugin's task pool is
 * a real pool, and a test that asserts "handing work over returned the caller" needs the work to actually leave the
 * calling thread.
 */
internal class SmartFakeHost(
    private val providers: Map<Capability, Any>,
    override val tasks: TaskScope = InlineTasks,
    /** What the descriptor declared; anything else is refused the way the host's scope refuses it. */
    private val granted: Set<Capability> = Capability.entries.toSet(),
) : HostServices {
    override fun require(capability: Capability): Any {
        if (capability !in granted) {
            error("CapabilityMissing: $capability was not declared by the plugin's descriptor")
        }
        return providers[capability] ?: error("missing fake provider: $capability")
    }
}

/** The default fake scope: the block runs on the calling thread before `launch` returns. */
internal object InlineTasks : TaskScope {
    override fun launch(block: () -> Unit): CancellationHandle { block(); return SmartFakeCancellation() }
    override fun cancelAll() = Unit
}

/**
 * A production-shaped scope: one real worker thread per launch, so a caller is never held by the work it handed over.
 *
 * `cancelAll` is inert on purpose - these tests answer or time out the question; they never need to cancel a worker,
 * and a non-inert cancel would only add a second mechanism to reason about.
 */
internal class OffThreadTasks : TaskScope {
    private val workers = java.util.Collections.synchronizedList(mutableListOf<Thread>())

    override fun launch(block: () -> Unit): CancellationHandle {
        val worker = Thread({ block() }, "smart-task-test")
        worker.isDaemon = true
        workers += worker
        worker.start()
        return SmartFakeCancellation()
    }

    override fun cancelAll() = Unit

    /** Joins every worker that has been started, so a test can assert on what they wrote without racing. */
    fun joinAll(millis: Long = 5_000) { workers.toList().forEach { it.join(millis) } }
}

/** Minimal but valid `ModelProfile` fixture; the plugin reads its window limits, `id`, `revision` and `timeoutMillis`. */
internal fun smartProfile(
    id: String = "smart-profile",
    revision: Long = 3,
    timeoutMillis: Long = 10_000,
    usableWindowTokens: Int = 4_000,
    inputBudgetTokens: Int = 2_000,
    maxOutputTokens: Int = 1_000,
    safetyTokens: Int = 100,
) = ModelProfile(
    id = id,
    revision = revision,
    name = "Smart profile",
    service = "fake",
    baseUrl = "https://invalid.test",
    protocolId = "fake",
    model = "fake-model",
    secret = object : SecretHandle { override val id = "fake-secret" },
    usableWindowTokens = usableWindowTokens,
    inputBudgetTokens = inputBudgetTokens,
    maxOutputTokens = maxOutputTokens,
    safetyTokens = safetyTokens,
    timeoutMillis = timeoutMillis,
    inputs = setOf(InputModality.TEXT),
    outputs = setOf(OutputModality.TEXT),
    verifications = emptyList(),
)

/** The policy a session with [profile] and [tuning] gets, exactly as the plugin derives it in `activate`. */
internal fun smartContextPolicy(
    profile: ModelProfile = smartProfile(),
    tuning: ContextTuning = ContextTuning(),
): ContextPolicy = ContextPolicy.from(profile, tuning)

/**
 * A one-section prompt plan for a test manager. 0.3 Task 11's `ContextManager` reads its instructions from a plan, and
 * Task 12 supplies the real `PromptCatalog` in production; a test that is not about prompt content uses this
 * deterministic stand-in.
 */
internal fun smartPromptPlan(
    systemText: (ContextRoute) -> String = { route ->
        when (route) {
            ContextRoute.WAKE -> "WAKE SYSTEM"
            ContextRoute.PROACTIVE -> "PROACTIVE SYSTEM"
        }
    },
): (ContextRoute) -> PromptPlan = { route ->
    val text = systemText(route)
    val sections = listOf(PromptSection("test-system", PromptKind.STATIC, text))
    PromptPlan("0.3.0", sections, text, PromptPlan.hashOf(sections))
}

/** The same plan for every route, for a test that only needs one instruction text. */
internal fun smartPromptPlan(systemText: String): (ContextRoute) -> PromptPlan =
    smartPromptPlan { systemText }

/**
 * A host `StorageAccess` grant backed by an in-memory SKILL library (Phase 6): one `.md` file per map entry.
 *
 * `listThrows` is the "the directory itself refuses / throws" case: a throwing listing must leave the turn running
 * with no catalog segment, because a broken library is not a broken turn (spec §3.7 and design D-1).
 */
internal class SmartFakeSkills(
    private val files: Map<String, String?> = emptyMap(),
    private val listThrows: Boolean = false,
) : StorageAccess {
    override fun listFiles(dirPath: String, suffix: String): List<String> =
        if (listThrows) throw IllegalStateException("the directory refused") else files.keys.toList()

    override fun readFile(path: String): String? = files[path]
    override fun deleteFile(path: String): Boolean = false
}

/**
 * A mutable SKILL library for the session-pin tests: the same on-disk shape as [SmartFakeSkills], with a `files` map a
 * test can edit **after** activation to model the user adding or editing a SKILL while the session is live.
 */
internal class MutableSmartFakeSkills(initial: Map<String, String?> = emptyMap()) : StorageAccess {
    val files = LinkedHashMap(initial)

    override fun listFiles(dirPath: String, suffix: String): List<String> = files.keys.toList()

    override fun readFile(path: String): String? = files[path]

    override fun deleteFile(path: String): Boolean = files.remove(path) != null
}

/** One SKILL `.md` file with valid front matter, as the user would write it. */
internal fun smartSkillDoc(
    name: String,
    body: String,
    description: String = "d-$name",
    enabled: Boolean = true,
) = "---\nname: $name\nversion: 1.0.0\ndescription: $description\nenabled: $enabled\n---\n$body\n"

/** One fresh `ConversationStore` per route, exactly as `SmartWiring` builds them. */
internal fun smartStores(): Map<com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute, com.sheen.rokid.agent.ConversationStore> =
    com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute.entries.associateWith { com.sheen.rokid.agent.ConversationStore() }

/** `ActionDescriptor` fixture whose defaults describe a harmless L0 action in the `app` scope. */
internal fun smartAction(
    name: String,
    risk: RiskLevel = RiskLevel.L0,
    requiresConfirmation: Boolean = false,
    scope: String = "app",
    title: String = "Action $name",
    /** edith-0.3 Phase 4A A5: the model-facing prose, which must never be painted as the tool row. */
    description: String = title,
    /** Task 6: a fake screen tool whose failures the loop should enrich from the host observation source. */
    screenObservation: Boolean = false,
) = ActionDescriptor(
    name = name,
    title = title,
    parametersJson = """{"type":"object","properties":{}}""",
    risk = risk,
    requiresConfirmation = requiresConfirmation,
    idempotent = true,
    reversible = true,
    scope = scope,
    screenObservation = screenObservation,
    description = description,
)

/** One text-delta-then-terminal stream, the shape a successful answer arrives in. */
internal fun streamText(request: ModelRequest, listener: (ModelStreamEvent) -> Unit, text: String) {
    listener(ModelStreamEvent.TextDelta(request.requestId, text))
    listener(ModelStreamEvent.Terminal(request.requestId, com.sheen.rokid.plugin.ModelEnd.COMPLETED, null, null))
}

/** One tool-call-then-terminal stream; the call is complete before the terminal, as the transport promises. */
internal fun smartToolCall(
    id: String,
    name: String,
    argsJson: String = "{}",
    itemId: String = id,
    /**
     * The request the event echoes back. Non-blank by contract (`ToolCallTerminal` rejects a blank id), so a caller
     * with a live request passes that request's own id.
     */
    requestId: String = "request-$id",
): ModelStreamEvent.ToolCallTerminal = ModelStreamEvent.ToolCallTerminal(
    requestId = requestId,
    itemId = itemId,
    callId = id,
    name = name,
    argsJson = argsJson,
)

internal class SmartTestRig(
    val sessionId: SessionId = SessionId("smart-session"),
    val generation: Generation = Generation(2),
    /**
     * edith-0.2 Phase B: injected into the plugin so a test can drive the session transcript and the compressor.
     * Both default to `null`, which is the pre-Phase-B plugin shape and what most of these tests are about.
     */
    contextForTurn: com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager? = null,
    compressor: com.sheen.rokid.phone.plugin.smarthelper.context.ContextCompressor? = null,
    /** edith-0.2 Phase B (B11): the intervention gate, `null` for the pre-B11 behaviour. */
    interventionGate: com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate? = null,
    /**
     * edith-0.3 Phase 4A A7: the production gate form, `null` — the default — for a plugin with no gate. When set, the
     * plugin builds a real `NoulGate`/`ChoiceGate` from the host's `SystemOneService` (the resource wiring under test).
     */
    gateForm: com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm? = null,
    /**
     * 0.3 design §5.2/§6.3: the tuning the plugin derives its `ContextPolicy` with. The default is production's;
     * a test that needs the history allowance small without shrinking the profile's frame window lowers
     * `compressionThreshold` here.
     */
    contextTuning: ContextTuning = ContextTuning(),
    /**
     * Phase 5: the per-route session stores the production wiring injects. `null` — the default — is the legacy shape
     * every pre-Phase-5 test uses, so those tests keep both old paths untouched.
     */
    conversationStores: Map<com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute, com.sheen.rokid.agent.ConversationStore>? = null,
    /**
     * 0.24 S3: whether the glasses panel is visible when a turn opens. The official 10 s window starts at
     * `PANEL_SHOWN`; a turn that opens on an already-visible panel starts it immediately. Default `false` keeps every
     * pre-0.24 test on the hidden-panel path (no official timer until the test shows the panel).
     */
    var panelVisible: Boolean = false,
    /**
     * 0.3 Phase 3 Task 9d: the host's readiness-aware `PANEL_OPEN` request, or `null` for a host that has no panel
     * opening path at all (the legacy hidden submission). Production wires `HostCompositionRoot.requestPanelOpen`
     * through `SmartWiring`; a test that drives the hidden-panel acquisition supplies its own recording seam.
     */
    requestPanelOpen: ((AssistantRoute) -> Boolean)? = null,
    /**
     * 0.24 S3: continual dialogue after a displayed answer. Default `false` preserves the pre-0.24 tests, which were
     * written before an automatic next round existed; the tests about the feature opt in.
     */
    var continueDialogue: Boolean = false,
    /**
     * 0.24 S3 Task 14 fix (C2): whether the host can actually speak. Default `true` keeps every pre-fix test on the
     * official-shaped host (which always has TTS); the speech-off tests opt out explicitly.
     */
    val speechAvailable: Boolean = true,
    /**
     * 0.3 Phase 4B (`D189`): the live 「不拦截」 gate this rig feeds the plugin. It is a `var` on purpose: a test can
     * flip it between tool calls to prove the plugin reads the provider live instead of snapshotting it per turn.
     */
    var noInterception: Boolean = false,
    /**
     * 0.24 S3 fix (I4): the wake cooldown. The production default (3 s) is what every pre-0.24 test expects; the
     * interleaving test needs a second wake inside the first round's 1 s close, so it opts out explicitly.
     */
    val cooldownNanos: Long = 3_000_000_000L,
    /**
     * 0.3 Phase 1 Task 12: the prompt-catalog seam. `null` uses the production `PromptCatalog.plan`; a test that pins
     * "a catalog change never rewrites a live session" supplies its own observable planner instead.
     */
    promptCatalog: ((PromptRoute, DeviceProfile?) -> PromptPlan)? = null,
    /**
     * 0.3 Phase 1 Task 12: an injected `DeviceStateProvider`. `null` keeps the production resolution
     * (`services.deviceState()` inside `activate`) under test.
     */
    deviceState: DeviceStateProvider? = null,
) {
    val clock = SmartFakeClock()
    val scheduler = SmartFakeScheduler(clock)
    val output = SmartFakeOutput()
    val wake = SmartFakeWakeWords()
    val actions = SmartFakeActions()
    val model = SmartFakeModel()
    val cancels = mutableListOf<Triple<SessionId, Generation, String>>()

    /**
     * The plugin exposes no ACK state to the host (the host's arbiter owns the display), so the tests observe ACK
     * admission through this test-only counter, fed by the plugin's `internal` test hook.
     */
    var displayedAcks = 0

    var profile: ModelProfile? = smartProfile()
    var plugin: SmartAssistantPlugin

    init {
        plugin = SmartAssistantPlugin(
            clock = clock,
            scheduler = scheduler,
            profileLookup = { profile },
            cancelOutputTurn = { s, g, t -> cancels += Triple(s, g, t) },
            onDisplayedAck = { displayedAcks += 1 },
            cooldownNanos = cooldownNanos,
            contextForTurn = contextForTurn,
            compressor = compressor,
            interventionGate = interventionGate,
            gateForm = gateForm,
            contextTuning = contextTuning,
            conversationStores = conversationStores,
            isPanelVisible = { panelVisible },
            requestPanelOpen = requestPanelOpen,
            continueDialogue = { continueDialogue },
            speechAvailable = { speechAvailable },
            noInterception = { noInterception },
            deviceState = deviceState,
        )
        // 0.3 Phase 3: the seam is an internal property (a public constructor must not expose the internal
        // `PromptRoute`); a test that pins a catalog sets it here, before any activation.
        promptCatalog?.let { plugin.promptCatalog = it }
    }

    fun envelope(generation: Generation = this.generation, sessionId: SessionId = this.sessionId) =
        EventEnvelope(sessionId, generation, "e-${clock.nowNanos()}", clock.nowNanos(), clock.nowNanos())

    fun wakeEvent(generation: Generation = this.generation, word: String = "伊迪丝") =
        WakeDetected(envelope(generation), word, 0.9)

    fun transcript(
        text: String,
        turnId: String = "asr-1",
        revision: Long = 1,
        final: Boolean = true,
        generation: Generation = this.generation,
        sessionId: SessionId = this.sessionId,
    ) = TranscriptRevision(envelope(generation, sessionId), turnId, revision, text, final, 1, clock.nowNanos())

    fun host(overrides: Map<Capability, Any> = emptyMap(), tasks: TaskScope? = null, granted: Set<Capability> = Capability.entries.toSet()): HostServices = SmartFakeHost(
        buildMap {
            put(Capability.Model, model)
            put(Capability.TextOutput, output)
            put(Capability.WakeWord, wake)
            put(Capability.Actions, actions)
            // edith-0.2 Phase B (B9): the plugin declares both local models as hard dependencies, so a
            // composition-shaped rig has to answer them or the host refuses to activate it. A test that wants the
            // refusal overrides these with `SmartFakeSystemOne(available = false)` / `SmartFakeEmbedding(false)`.
            put(Capability.SystemOne, SmartFakeSystemOne())
            put(Capability.Embedding, SmartFakeEmbedding())
            putAll(overrides)
        },
        tasks ?: InlineTasks,
        granted,
    )

    fun activate(
        profileId: String = "smart-profile",
        profileRevision: String = "3",
        wakeWords: String? = null,
        maxLevel: String? = null,
        whitelist: String? = null,
        requestConsent: String? = null,
        proactiveCooldownMillis: String? = null,
        proactiveSensitivity: String? = null,
        overrides: Map<Capability, Any> = emptyMap(),
        /** How blocking turn work is scheduled; `null` keeps the deterministic inline scope. */
        tasks: TaskScope? = null,
        // A host that keeps one plugin instance re-activates it for the next session/generation; the defaults here are
        // the rig's own, so a test can model that re-activation explicitly.
        sessionId: SessionId = this.sessionId,
        generation: Generation = this.generation,
    ): SmartAssistantPlugin {
        val values = buildMap {
            put("profileId", profileId)
            put("profileRevision", profileRevision)
            wakeWords?.let { put("wakeWords", it) }
            maxLevel?.let { put("maxLevel", it) }
            whitelist?.let { put("whitelist", it) }
            requestConsent?.let { put("requestConsent", it) }
            proactiveCooldownMillis?.let { put("proactiveCooldownMillis", it) }
            proactiveSensitivity?.let { put("proactiveSensitivity", it) }
        }
        val configuration = com.sheen.rokid.plugin.PluginConfiguration(1, values)
        plugin.validate(configuration)
        plugin.configure(configuration)
        plugin.activate(
            // Granted set == **what the descriptor declares**, which is what the real host does. This rig used to grant
            // `Capability.entries` (every capability), so any port the plugin reached for was reachable here and a
            // capability the plugin consumed but never declared was invisible to the whole suite. That is not
            // hypothetical: the plugin reported nothing to the phone's conversation view on the device - no `SAID`, no
            // `BUSY`, an empty 助手 page - while 439 tests were green, because `ConversationDisplay` was missing from
            // the descriptor and the optional-port access degraded silently.
            com.sheen.rokid.plugin.PluginContext(
                sessionId, generation, plugin.descriptor.capabilities.toSet(),
                host(overrides, tasks, plugin.descriptor.capabilities.toSet()),),
        )
        return plugin
    }

    /** Releases everything the clock has made due, the way the host's scheduler would. */
    fun tick(millis: Long = 200) {
        clock.advance(millis * 1_000_000)
        scheduler.runDue()
    }

    /** Wakes, then hands over one final transcript, and releases the scheduler. */
    fun wakeAndFinal(text: String = "需要记什么", turnId: String = "asr-1") {
        plugin.handle(wakeEvent())
        plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(transcript(text, turnId = turnId)))
        tick()
    }

    val hintTexts: List<String> get() = output.hints.map { it.text }

    /** The plugin's parsed `requestConsent` flag, through its `internal` observation property. */
    val consentFlag: Boolean get() = plugin.requestConsentConfigured

    /** The plugin's audit trail, through its `internal` observation accessor. */
    val auditEntries: List<com.sheen.rokid.agent.AuditEntry> get() = plugin.auditTrail()
}

/** The namespace a host grants: a plain in-memory map, so a test can read exactly what was persisted. */
internal class SmartFakeStorage : StorageHandle {
    val values = mutableMapOf<String, ByteArray>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: ByteArray) { values[key] = value }
    override fun delete(key: String) { values.remove(key) }
    override fun clear() = values.clear()
}

/** A namespace that refuses everything: the worst case a host can hand a store, and the one that must not throw. */
internal class SmartExplodingStorage : StorageHandle {
    override fun read(key: String): ByteArray? = throw IllegalStateException("boom")
    override fun write(key: String, value: ByteArray): Unit = throw IllegalStateException("boom")
    override fun delete(key: String): Unit = throw IllegalStateException("boom")
    override fun clear(): Unit = throw IllegalStateException("boom")
}

/** An `AtomicBoolean` guard used by the async-model tests to fail loudly if a stream is not torn down. */
internal class SmartGate {
    val open = AtomicBoolean(false)
    fun openIt() { open.set(true) }
    fun await(millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!open.get() && System.nanoTime() < deadline) Thread.sleep(5)
    }
}

/**
 * edith-0.2 Phase B (B9): the two local models the plugin now declares as hard dependencies.
 *
 * They live in the shared harness rather than in `HardDependencyTest` because the rig's default host answers both —
 * a composition-shaped host that could not would refuse to activate the plugin at all, which is the contract B9
 * adds. A test that wants the refusal passes `SmartFakeSystemOne(available = false)` or `SmartFakeEmbedding(false)`.
 */
internal class SmartFakeSystemOne(private val available: Boolean = true) : SystemOneService {
    override fun ask(state: String, question: SystemOneQuestion): SystemOneResult =
        SystemOneResult.Success(SystemOneAnswer.Noul(probability = 0.5, confidence = 0.5), latencyMs = 1.0)

    override val isAvailable: Boolean get() = available
    override val status: SystemOneStatus
        get() = if (available) SystemOneStatus.READY else SystemOneStatus.UNAVAILABLE
    override val modelInfo: ModelInfo? get() = null
}

internal class SmartFakeEmbedding(private val available: Boolean = true) : EmbeddingService {
    override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(dimensions))
    override val isAvailable: Boolean get() = available
    override val modelInfo: ModelInfo? get() = null
    override val dimensions: Int get() = 256
}

/**
 * 0.3 Phase 1 Task 12: the host's device profile/state channel, answerable from a fixture.
 *
 * [profileReads] lets a test prove the session reads the device **once** at activation instead of per turn: the plan
 * is pinned, so a second `profile()` call for a live session would be a second snapshot the spec does not allow.
 */
internal class SmartFakeDeviceState(
    private val profile: DeviceProfile,
    private val state: DeviceState = DeviceState(
        capturedAtEpochMillis = 0L,
        timeZone = "UTC",
        localDate = "2026-10-20",
        weekday = "星期一",
        localTime = "00:00",
    ),
) : DeviceStateProvider {
    var profileReads: Int = 0
        private set

    override fun profile(): DeviceProfile {
        profileReads += 1
        return profile
    }

    override fun state(): DeviceState = state
}

/** An all-`ok` coverage fixture; a test that is about an unavailable source overrides the one token it needs. */
internal fun smartPerceptionCoverage(
    screen: String = "ok",
    notifications: String = "ok",
    media: String = "ok",
    calendar: String = "ok",
    acoustic: String = "ok",
    clipboard: String = "ok",
    vision: String = "ok",
    glasses: String = "ok",
    device: String = "ok",
) = PerceptionCoverage(
    screen = screen,
    notifications = notifications,
    media = media,
    calendar = calendar,
    acoustic = acoustic,
    clipboard = clipboard,
    vision = vision,
    glasses = glasses,
    device = device,
)

/** One synthetic turn snapshot: one compact screen element and nothing sensitive. */
internal fun smartPerceptionSnapshot(
    snapshotId: String = "snap-0042",
    elements: List<PerceptionElement> = listOf(
        PerceptionElement(
            ref = "#n12",
            role = "button",
            text = "搜索",
            centerX = 540,
            centerY = 320,
            clickable = true,
        ),
    ),
    coverage: PerceptionCoverage = smartPerceptionCoverage(),
) = PerceptionSnapshot(
    snapshotId = snapshotId,
    capturedAtEpochMillis = 1_000_000L,
    foregroundPackage = "com.miui.player",
    foregroundActivity = ".PlayerActivity",
    elements = elements,
    coverage = coverage,
)

/**
 * edith-0.3 Phase 2 Task 9: the host's perception port, answerable from a fixture and recording its turn boundary.
 *
 * The counters are how a test proves the plugin closed the turn on **every** exit path, including the early-return
 * and failure paths; [requestedSources] proves the first request took the full snapshot and a later one asked only
 * for the ambient device/media subset.
 */
internal class SmartFakePerception(
    private val initial: PerceptionSnapshot = smartPerceptionSnapshot(),
    private val ambient: PerceptionSnapshot = initial,
    private val initialFailure: RuntimeException? = null,
    private val ambientFailure: RuntimeException? = null,
    /**
     * edith-0.3 Phase 3 Task 3 (M4): the picture the failure-observation source receives. The default is the honest
     * "no picture here" answer every pre-M4 fixture implied; a test that pins the image channel supplies an `Image`.
     */
    private val screenshotResult: PerceptionScreenshotResult = PerceptionScreenshotResult.Unavailable("test"),
) : PerceptionProvider {
    var beginTurns: Int = 0
        private set
    var endTurns: Int = 0
        private set
    val requestedSources = mutableListOf<Set<PerceptionSource>>()

    override fun beginTurn(): PerceptionSnapshot {
        beginTurns += 1
        initialFailure?.let { throw it }
        return initial
    }

    override fun snapshot(
        include: Set<PerceptionSource>,
        filter: PerceptionFilter,
        limit: Int?,
    ): PerceptionSnapshot {
        requestedSources += include
        ambientFailure?.let { throw it }
        return ambient
    }

    override fun afterAction(): PerceptionSnapshot = ambient

    override fun screenshot(): PerceptionScreenshotResult = screenshotResult

    override fun endTurn() {
        endTurns += 1
    }
}
