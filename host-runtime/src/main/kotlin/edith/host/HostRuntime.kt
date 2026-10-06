package edith.host

import edith.contracts.*
import edith.plugin.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class HostRuntime(private val apiVersion: Int, private val environment: HostEnvironment) {
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
    private var state = SessionState.Idle
    private var inputEpoch: InputEpoch? = null
    private var inputSerial = 0L
    private var transitioning = false
    private val eventGate = Any()
    @Volatile private var eventIdentity: Pair<SessionId, Generation>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "edith-deadlines").also { it.isDaemon = true } }
    private val deadlineScheduler = object : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val future = timer.schedule(task, (deadlineNanos - environment.clock.nowNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
        }
    }
    private val arbiter = OutputArbiter(environment.clock,
        (environment as? ScheduledHostEnvironment)?.scheduler ?: deadlineScheduler,
        environment.output, { eventIdentity }) { hint, status ->
        onEvent(OutputStateEvent(EventEnvelope(hint.sessionId, hint.generation,
            "output-${java.util.UUID.randomUUID()}", environment.clock.nowNanos(), environment.clock.nowNanos()), hint.hintId, status))
    }
    private val drainScheduled = AtomicBoolean()
    private val dispatcher = HostEventDispatcher(32, ::deliverEvent, ::onCriticalEventOverflow)
    val currentGeneration: Generation get() = command { generation }
    val currentState: SessionState get() = command { state }
    val currentInputEpoch: InputEpoch? get() = command { inputEpoch }

    private fun <T> command(block: () -> T): T {
        if (Thread.currentThread() === ownerThread) return block()
        return try { executor.submit<T> { block() }.get() } catch (error: ExecutionException) {
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
            plugin.activate(PluginContext(session, generation, plugin.descriptor.capabilities, services))
            if (scope !== resource || resource.isRevoked) throw ContractError.Cancelled()
            if (Capability.Audio in plugin.descriptor.capabilities) {
                val epoch = InputEpoch(session, generation, Math.incrementExact(inputSerial).also { inputSerial = it })
                inputEpoch = epoch
                environment.input.start(epoch)
            }
            ActivationResult.Activated
        }.getOrElse {
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
        executor.execute {
            if (epoch == inputEpoch && sceneReady && active != null && scope?.isRevoked == false) state = SessionState.Listening
        }
    }

    fun onEvent(event: HostEvent): Boolean {
        val accepted = synchronized(eventGate) {
            if (eventIdentity != (event.envelope.sessionId to event.envelope.generation)) return false
            dispatcher.offer(event)
        }
        scheduleDrain()
        return accepted
    }

    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) executor.execute {
            try { dispatcher.drain() } finally {
                drainScheduled.set(false)
                if (dispatcher.hasPending) scheduleDrain()
            }
        }
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
            is OutputStateEvent -> Capability.TextOutput
            else -> null
        }
        if (capability != null && capability !in plugin.descriptor.capabilities) return
        runCatching { plugin.handle(event) }.onFailure { pause() }
    }

    private fun teardown(reason: DeactivationReason) {
        transitioning = true
        try {
        synchronized(eventGate) { eventIdentity = null; dispatcher.clear() }
        val epoch = inputEpoch
        inputEpoch = null
        epoch?.let { runCatching { environment.input.stop(it) } }
        scope?.revoke()
        scope = null
        val previous = active
        active = null
        previous?.let { runCatching { it.deactivate(reason) } }
        sessionId?.let { runCatching { arbiter.clear(it, generation) } }
        } finally { transitioning = false }
    }

    fun accept(assistance: Assistance): Boolean = command {
        active != null && Capability.TextOutput in active!!.descriptor.capabilities &&
            sessionId?.let { assistance.isCurrent(it, generation) } == true &&
            assistance.expiresAtNanos > environment.clock.nowNanos() && assistance.optionalSpeech == null
    }
}
