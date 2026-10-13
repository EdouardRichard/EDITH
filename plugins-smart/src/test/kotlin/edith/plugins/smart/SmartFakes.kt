package edith.plugins.smart

import edith.contracts.Assistance
import edith.contracts.EventEnvelope
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.contracts.TranscriptRevision
import edith.contracts.WakeDetected
import edith.plugin.ActionDescriptor
import edith.plugin.ActionExecutor
import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.CancellationHandle
import edith.plugin.Capability
import edith.plugin.DeadlineScheduler
import edith.plugin.EmbedResult
import edith.plugin.EmbeddingService
import edith.plugin.HostServices
import edith.plugin.InputModality
import edith.plugin.ModelClient
import edith.plugin.ModelInfo
import edith.plugin.ModelProfile
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.MonotonicClock
import edith.plugin.OutputModality
import edith.plugin.OutputSink
import edith.plugin.OutputSubmission
import edith.plugin.RiskLevel
import edith.plugin.SecretHandle
import edith.plugin.StorageHandle
import edith.plugin.SystemOneAnswer
import edith.plugin.SystemOneQuestion
import edith.plugin.SystemOneResult
import edith.plugin.SystemOneService
import edith.plugin.SystemOneStatus
import edith.plugin.TaskScope
import edith.plugin.WakeWordRegistry
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

/** Records every executed action and every `cancel`, and answers with a scripted outcome. */
internal class SmartFakeActions(
    val descriptors: MutableList<ActionDescriptor> = mutableListOf(),
) : ActionExecutor {
    val executed = mutableListOf<Pair<String, String>>()
    var cancellations = 0
    var outcome: (String, String) -> ActionResult = { _, _ -> ActionResult(ActionOutcome.Done, "已记录") }

    override fun descriptors(): List<ActionDescriptor> = descriptors

    override fun execute(name: String, argsJson: String): ActionResult {
        executed += name to argsJson
        return outcome(name, argsJson)
    }

    override fun cancel() { cancellations++ }
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
 * The host services the plugin is activated with: a capability map plus a task scope.
 *
 * The default scope runs the block **inline**, which is what keeps the plugin's own tests deterministic (the same
 * reason the P3.2 proactive tests rely on). [OffThreadTasks] models production instead: there the plugin's task pool is
 * a real pool, and a test that asserts "handing work over returned the caller" needs the work to actually leave the
 * calling thread.
 */
internal class SmartFakeHost(
    private val providers: Map<Capability, Any>,
    override val tasks: TaskScope = InlineTasks,
) : HostServices {
    override fun require(capability: Capability): Any = providers[capability] ?: error("missing fake provider: $capability")
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

/** Minimal but valid `ModelProfile` fixture; the plugin only reads `id`, `revision` and `timeoutMillis`. */
internal fun smartProfile(
    id: String = "smart-profile",
    revision: Long = 3,
    timeoutMillis: Long = 10_000,
) = ModelProfile(
    id = id,
    revision = revision,
    name = "Smart profile",
    service = "fake",
    baseUrl = "https://invalid.test",
    protocolId = "fake",
    model = "fake-model",
    secret = object : SecretHandle { override val id = "fake-secret" },
    usableWindowTokens = 4_000,
    inputBudgetTokens = 2_000,
    maxOutputTokens = 1_000,
    safetyTokens = 100,
    timeoutMillis = timeoutMillis,
    inputs = setOf(InputModality.TEXT),
    outputs = setOf(OutputModality.TEXT),
    verifications = emptyList(),
)

/** `ActionDescriptor` fixture whose defaults describe a harmless L0 action in the `app` scope. */
internal fun smartAction(
    name: String,
    risk: RiskLevel = RiskLevel.L0,
    requiresConfirmation: Boolean = false,
    scope: String = "app",
    title: String = "Action $name",
) = ActionDescriptor(
    name = name,
    title = title,
    parametersJson = """{"type":"object","properties":{}}""",
    risk = risk,
    requiresConfirmation = requiresConfirmation,
    idempotent = true,
    reversible = true,
    scope = scope,
)

/** One text-delta-then-terminal stream, the shape a successful answer arrives in. */
internal fun streamText(request: ModelRequest, listener: (ModelStreamEvent) -> Unit, text: String) {
    listener(ModelStreamEvent.TextDelta(request.requestId, text))
    listener(ModelStreamEvent.Terminal(request.requestId, edith.plugin.ModelEnd.COMPLETED, null, null))
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
    contextForTurn: edith.plugins.smart.context.ContextManager? = null,
    compressor: edith.plugins.smart.context.ContextCompressor? = null,
    /** edith-0.2 Phase B (B11): the intervention gate, `null` for the pre-B11 behaviour. */
    interventionGate: edith.plugins.smart.proactive.ProactiveGate? = null,
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
            contextForTurn = contextForTurn,
            compressor = compressor,
            interventionGate = interventionGate,
        )
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

    fun host(overrides: Map<Capability, Any> = emptyMap(), tasks: TaskScope? = null): HostServices = SmartFakeHost(
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
        val configuration = edith.plugin.PluginConfiguration(1, values)
        plugin.validate(configuration)
        plugin.configure(configuration)
        plugin.activate(
            edith.plugin.PluginContext(sessionId, generation, Capability.entries.toSet(), host(overrides, tasks)),
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
        plugin.handle(edith.contracts.TranscriptEvent(transcript(text, turnId = turnId)))
        tick()
    }

    val hintTexts: List<String> get() = output.hints.map { it.text }

    /** The plugin's parsed `requestConsent` flag, through its `internal` observation property. */
    val consentFlag: Boolean get() = plugin.requestConsentConfigured

    /** The plugin's audit trail, through its `internal` observation accessor. */
    val auditEntries: List<edith.agent.AuditEntry> get() = plugin.auditTrail()
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
