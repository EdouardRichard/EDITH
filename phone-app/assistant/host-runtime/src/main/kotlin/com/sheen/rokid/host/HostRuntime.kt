package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CountDownLatch

class HostRuntime(private val apiVersion: Int, private val environment: HostEnvironment) : AutoCloseable {
    constructor(apiVersion: Int) : this(apiVersion, MissingHostEnvironment)

    private var ownerThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "edith-host").also { it.isDaemon = true; ownerThread = it }
    }
    private val plugins = linkedMapOf<String, PluginLifecycle>()
    private val configurations = mutableMapOf<String, PluginConfiguration>()
    private var sessionId: SessionId? = null
    private var sceneReady = false
    private var selectedId: String? = null
    private var active: PluginLifecycle? = null
    private var scope: ResourceScope? = null
    private var generation = Generation(0)
    @Volatile private var state = SessionState.Idle

    /**
     * Why the last activation failed, or null after one that succeeded.
     *
     * `ActivationResult.Failed` deliberately carries no detail, and the failure is swallowed at the call site, so this
     * is the only way an operator can tell a refused configuration from a provider that threw.
     */
    @Volatile var lastActivationCause: Throwable? = null
        private set

    @Volatile private var inputEpoch: InputEpoch? = null
    @Volatile private var closed = false
    private val closing = AtomicBoolean()
    private val closeComplete = CountDownLatch(1)
    private var publishedState = SessionState.Idle
    private class StateObservation(val listener: (SessionState) -> Unit) {
        val active = AtomicBoolean(true)
    }
    private val observations = CopyOnWriteArrayList<StateObservation>()
    private var inputSerial = 0L
    private var transitioning = false
    private val eventGate = Any()
    @Volatile private var eventIdentity: Pair<SessionId, Generation>? = null

    /**
     * Transition-only memory for the high-rate [AcousticEvent] producer. A repeat of the last delivered label is
     * dropped: the dispatcher has capacity 32 and a full queue for a non-`TranscriptEvent` is a critical overflow
     * (`DiscontinuityEvent(OVERFLOW)` + `interrupt()`), so only label transitions may reach it.
     */
    @Volatile private var lastAcoustic: AcousticLabel? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "edith-deadlines").also { it.isDaemon = true } }
    private val deadlineScheduler = object : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val future = timer.schedule(task, (deadlineNanos - environment.clock.nowNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
        }
    }
    private val arbiter = OutputArbiter(environment.clock,
        (environment as? ScheduledHostEnvironment)?.scheduler ?: deadlineScheduler,
        environment.output, environment.speech, { eventIdentity },
        { hint, status ->
            onEvent(OutputStateEvent(EventEnvelope(hint.sessionId, hint.generation,
                "output-${java.util.UUID.randomUUID()}", environment.clock.nowNanos(), environment.clock.nowNanos()),
                hint.hintId, status, hint.emitId))
        },
        { hint, result ->
            // 0.24 S3 (spec §5.5): the phone's playback of the displayed answer ended. `onEvent` is the identity gate:
            // a session that ended (or a generation that was replaced) receives nothing, exactly like every other host
            // event. There is no wire command for this - `SPEECH_DONE` is phone-internal (spec §5.4).
            //
            // 0.24 S3 Task 14 fix (C1): the event also carries the hint's **emit** id. A turn-wide hint id cannot tell
            // a superseded emit's playback end from the final answer's, and the arbiter suppresses a cancelled
            // playback after `remove` - but an event that was already queued before the replacement is delivered.
            onEvent(SpeechFinished(EventEnvelope(hint.sessionId, hint.generation,
                "speech-${java.util.UUID.randomUUID()}", environment.clock.nowNanos(), environment.clock.nowNanos()),
                hint.hintId, result, hint.emitId))
        })
    private val drainScheduled = AtomicBoolean()
    private val dispatcher = HostEventDispatcher(32, ::deliverEvent, ::onCriticalEventOverflow)
    val currentGeneration: Generation get() = if (closed) generation else command { generation }
    val currentState: SessionState get() = if (closed) state else command { state }
    val currentInputEpoch: InputEpoch? get() = if (closed) inputEpoch else command { inputEpoch }

    /**
     * The identity [onEvent] currently gates delivery by, or null when no plugin owns the session.
     *
     * Read-only and intentionally narrow: an event producer outside this class (the App's wake layer) has to tag its
     * event with exactly the pair this host accepts, and there is no other way to learn it. Returning null while
     * nothing is active is the honest answer - the host would drop any event in that state - and it keeps the value
     * from being mistaken for a session that merely exists.
     */
    fun currentIdentity(): Pair<SessionId, Generation>? = if (closed) eventIdentity else command { eventIdentity }

    /**
     * The last acoustic label this host delivered in the current activation, as the label's stable name (for example
     * `"Speech"`), or `null` when no label has arrived, the session is not armed, or the memory has been reset.
     *
     * Read-only and content-free: this is the transition label, never speech text or audio. It is public rather than
     * `internal` because the perception composition lives in the app module and Kotlin `internal` is Gradle-module
     * scoped; there is no other surface through which that composition can read the acoustic fact.
     */
    @Synchronized fun lastAcousticLabel(): String? = lastAcoustic?.name

    fun observeState(listener: (SessionState) -> Unit): CancellationHandle = command {
        val observation = StateObservation(listener)
        observations += observation
        runCatching { listener(state) }
        object : CancellationHandle {
            override fun cancel() { observation.active.set(false); observations.remove(observation) }
        }
    }

    private fun publishState() {
        if (state == publishedState) return
        publishedState = state
        observations.forEach { if (it.active.get()) runCatching { it.listener(state) } }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) {
            // An observer on the owner must not wait for the shutdown it is executing.
            if (Thread.currentThread() !== ownerThread) closeComplete.await()
            return
        }
        fun shutdown() {
            try { stop() } finally {
                closed = true
                observations.forEach { it.active.set(false) }
                observations.clear()
                timer.shutdownNow()
                executor.shutdown()
                closeComplete.countDown()
            }
        }
        if (Thread.currentThread() === ownerThread) shutdown()
        else try { executor.submit { shutdown() }.get() } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun <T> command(block: () -> T): T {
        fun execute(): T {
            check(!closed) { "HOST_CLOSED" }
            try { return block() } finally { publishState() }
        }
        check(!closed && (!closing.get() || Thread.currentThread() === ownerThread)) { "HOST_CLOSED" }
        if (Thread.currentThread() === ownerThread) return execute()
        return try { executor.submit<T> { execute() }.get() } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    fun register(plugin: PluginLifecycle) = command {
        require(plugin.descriptor.apiVersion == apiVersion) { "unsupported api version" }
        require(plugins.putIfAbsent(plugin.descriptor.pluginId, plugin) == null) { "duplicate plugin id" }
        Unit
    }

    fun configure(id: String, configuration: PluginConfiguration): Boolean = command {
        if (transitioning) return@command false
        val plugin = plugins[id] ?: return@command false
        if (active?.descriptor?.pluginId == id) return@command false
        transitioning = true
        try { runCatching {
            ConfigurationPolicy.validate(plugin, configuration)
            (plugin as? ConfiguredPlugin)?.configure(configuration)
            configurations[id] = configuration.copy(values = configuration.values.toMap())
        }.isSuccess } finally { transitioning = false }
    }

    fun startSession(id: SessionId) = command {
        check(!transitioning) { "lifecycle transition in progress" }
        teardown(DeactivationReason.Stopped)
        sessionId = id
        sceneReady = false
        selectedId = null
        generation = Generation(Math.incrementExact(generation.value))
        state = SessionState.BuildingScene
    }

    fun markSceneReady() = command {
        check(sessionId != null)
        sceneReady = true
        if (active == null) state = SessionState.Ready
    }

    fun activate(id: String): ActivationResult = command {
        if (transitioning) return@command ActivationResult.OwnerBusy
        if (!sceneReady || sessionId == null) return@command ActivationResult.SessionNotReady
        if (active != null) return@command ActivationResult.OwnerBusy
        if (id !in plugins) return@command ActivationResult.UnknownPlugin
        selectedId = id
        activateSelected()
    }

    private fun activateSelected(): ActivationResult {
        val plugin = plugins[selectedId] ?: return ActivationResult.UnknownPlugin
        val session = sessionId ?: return ActivationResult.SessionNotReady
        val resource = ResourceScope(plugin.descriptor.pluginId, session, generation)
        transitioning = true
        return try { runCatching {
            val configuration = configurations[plugin.descriptor.pluginId]
                ?: PluginConfiguration(plugin.descriptor.configSchema.version, emptyMap())
            ConfigurationPolicy.validate(plugin, configuration)
            if (!environment.availableCapabilities.containsAll(plugin.descriptor.capabilities)) {
                throw ContractError.CapabilityMissing("required provider unavailable")
            }
            (plugin as? ConfiguredPlugin)?.configure(configuration)
            val provided = environment.services(resource, plugin.descriptor.capabilities)
            val services = object : HostServices {
                override val tasks = provided.tasks
                override fun require(capability: Capability): Any {
                    resource.checkActive()
                    val service = provided.require(capability)
                    return if (capability == Capability.TextOutput) object : OutputSink {
                        override fun submit(assistance: Assistance): OutputSubmission {
                            resource.checkActive()
                            if (eventIdentity != (session to generation)) throw ContractError.Cancelled()
                            return when (arbiter.submit(assistance)) {
                                OutputStatus.Accepted, OutputStatus.Queued -> OutputSubmission.Accepted
                                else -> OutputSubmission.Rejected
                            }
                        }
                    } else service
                }
            }
            scope = resource
            active = plugin
            state = SessionState.Ready
            eventIdentity = session to generation
            lastAcoustic = null
            plugin.activate(PluginContext(session, generation, plugin.descriptor.capabilities, services))
            if (scope !== resource || resource.isRevoked) throw ContractError.Cancelled()
            if (Capability.Audio in plugin.descriptor.capabilities) {
                val epoch = InputEpoch(session, generation, Math.incrementExact(inputSerial).also { inputSerial = it })
                inputEpoch = epoch
                environment.input.start(epoch)
            }
            ActivationResult.Activated.also { lastActivationCause = null }
        }.getOrElse { cause ->
            // Kept rather than discarded: every caller sees only `ActivationResult.Failed`, so without this a device
            // run cannot tell a refused configuration from a provider that threw (found while chasing a device-only
            // activation failure on 2026-10-08, where the cause was invisible in both the App and the log).
            lastActivationCause = cause
            resource.revoke()
            teardown(DeactivationReason.Error)
            state = SessionState.Paused
            ActivationResult.Failed
        } } finally { transitioning = false }
    }

    fun switchTo(id: String): ActivationResult = command {
        if (transitioning) return@command ActivationResult.OwnerBusy
        if (!sceneReady || sessionId == null) return@command ActivationResult.SessionNotReady
        if (id !in plugins) return@command ActivationResult.UnknownPlugin
        teardown(DeactivationReason.Switched)
        generation = Generation(Math.incrementExact(generation.value))
        selectedId = id
        activateSelected()
    }

    fun pause() = command {
        if (transitioning) return@command
        if (sessionId != null && state != SessionState.Paused) {
            teardown(DeactivationReason.Paused)
            generation = Generation(Math.incrementExact(generation.value))
            state = SessionState.Paused
        }
    }

    fun resume(): ActivationResult = command {
        if (transitioning) return@command ActivationResult.OwnerBusy
        if (!sceneReady || sessionId == null) return@command ActivationResult.SessionNotReady
        if (active != null) return@command ActivationResult.OwnerBusy
        activateSelected()
    }

    fun stop() = command {
        if (transitioning) return@command
        if (sessionId != null) teardown(DeactivationReason.Stopped)
        sessionId = null
        selectedId = null
        sceneReady = false
        state = SessionState.Idle
    }

    fun interrupt() = command {
        if (transitioning) return@command
        if (sessionId != null) {
            teardown(DeactivationReason.Error)
            generation = Generation(Math.incrementExact(generation.value))
            sceneReady = false
            state = SessionState.Interrupted
        }
    }

    fun onAudioReceived(epoch: InputEpoch) {
        if (closed) return
        try { executor.execute {
            if (!closed && epoch == inputEpoch && sceneReady && active != null && scope?.isRevoked == false) {
                state = SessionState.Listening
                publishState()
            }
        } } catch (_: RejectedExecutionException) { /* Close fenced this callback. */ }
    }

    fun onEvent(event: HostEvent): Boolean {
        if (closed) return false
        val accepted = synchronized(eventGate) {
            if (eventIdentity != (event.envelope.sessionId to event.envelope.generation)) return false
            dispatcher.offer(event)
        }
        scheduleDrain()
        return accepted
    }

    private fun scheduleDrain() {
        if (closed) return
        if (drainScheduled.compareAndSet(false, true)) try { executor.execute {
            try { dispatcher.drain() } finally {
                drainScheduled.set(false)
                if (dispatcher.hasPending) scheduleDrain()
            }
        } } catch (_: RejectedExecutionException) { drainScheduled.set(false) }
    }

    private fun isCurrent(event: HostEvent) = event.envelope.sessionId == sessionId && event.envelope.generation == generation

    private fun onCriticalEventOverflow(event: HostEvent) {
        synchronized(eventGate) {
            if (eventIdentity != (event.envelope.sessionId to event.envelope.generation)) return
            eventIdentity = null
            scope?.closeAdmission()
            dispatcher.clear()
        }
        executor.execute { if (isCurrent(event)) interrupt() }
    }

    private fun deliverEvent(event: HostEvent) {
        val plugin = active ?: return
        if (eventIdentity != (event.envelope.sessionId to event.envelope.generation) ||
            !isCurrent(event) || scope?.isRevoked != false) return
        val capability = when (event) {
            is AudioEvent, is DiscontinuityEvent -> Capability.Audio
            is TranscriptEvent -> Capability.Asr
            is SpeakerEvent -> Capability.Speaker
            is OutputStateEvent, is SpeechFinished -> Capability.TextOutput
            is WakeDetected -> Capability.WakeWord
            is AcousticEvent, is ContextChanged -> Capability.Context
            is ConfirmationResult -> Capability.Actions
            else -> null
        }
        if (capability != null && capability !in plugin.descriptor.capabilities) return
        if (event is AcousticEvent) {
            if (event.label == lastAcoustic) return
            lastAcoustic = event.label
        }
        runCatching { plugin.handle(event) }.onFailure { pause() }
    }

    private fun teardown(reason: DeactivationReason) {
        transitioning = true
        try {
        synchronized(eventGate) { eventIdentity = null; lastAcoustic = null; dispatcher.clear() }
        val epoch = inputEpoch
        inputEpoch = null
        scope?.revoke()
        scope = null
        epoch?.let { runCatching { environment.input.stop(it) } }
        val previous = active
        active = null
        previous?.let { runCatching { it.deactivate(reason) } }
        sessionId?.let { runCatching { arbiter.clear(it, generation) } }
        } finally { transitioning = false }
    }

    // Spec §S2: structural eligibility is independent of speech capability. optionalSpeech no longer disqualifies a
    // hint here; whether it gets spoken is decided by the speech capability gate (player present AND user switch on).
    fun accept(assistance: Assistance): Boolean = command {
        active != null && Capability.TextOutput in active!!.descriptor.capabilities &&
            sessionId?.let { assistance.isCurrent(it, generation) } == true &&
            assistance.expiresAtNanos > environment.clock.nowNanos()
    }

    fun cancelOutputTurn(sessionId: SessionId, generation: Generation, turnId: String) = command {
        if (active != null && scope?.isRevoked == false && eventIdentity == (sessionId to generation)) {
            arbiter.cancelTurn(turnId)
        }
    }

    fun cancelOutputHint(sessionId: SessionId, generation: Generation, turnId: String, hintId: String) = command {
        if (active != null && scope?.isRevoked == false && eventIdentity == (sessionId to generation)) {
            arbiter.cancelHint(turnId, hintId)
        }
    }
}
