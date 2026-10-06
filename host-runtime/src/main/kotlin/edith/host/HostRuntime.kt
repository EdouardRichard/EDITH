package edith.host

import edith.contracts.*
import edith.plugin.*
import java.util.concurrent.Executors

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
    val currentGeneration: Generation get() = command { generation }
    val currentState: SessionState get() = command { state }
    val currentInputEpoch: InputEpoch? get() = command { inputEpoch }

    private fun <T> command(block: () -> T): T =
        if (Thread.currentThread() === ownerThread) block() else executor.submit<T> { block() }.get()

    fun register(plugin: PluginLifecycle) = command {
        require(plugin.descriptor.apiVersion == apiVersion) { "unsupported api version" }
        require(plugins.putIfAbsent(plugin.descriptor.pluginId, plugin) == null) { "duplicate plugin id" }
        Unit
    }

    fun configure(id: String, configuration: PluginConfiguration): Boolean = command {
        val plugin = plugins[id] ?: return@command false
        if (active?.descriptor?.pluginId == id) return@command false
        runCatching {
            ConfigurationPolicy.validate(plugin, configuration)
            (plugin as? ConfiguredPlugin)?.configure(configuration)
            configurations[id] = configuration.copy(values = configuration.values.toMap())
        }.isSuccess
    }

    fun startSession(id: SessionId) = command {
        teardown(DeactivationReason.Stopped)
        sessionId = id
        sceneReady = false
        selectedId = null
        generation = Generation(0)
        state = SessionState.BuildingScene
    }

    fun markSceneReady() = command {
        check(sessionId != null)
        sceneReady = true
        if (active == null) state = SessionState.Ready
    }

    fun activate(id: String): ActivationResult = command {
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
        return runCatching {
            val configuration = configurations[plugin.descriptor.pluginId]
                ?: PluginConfiguration(plugin.descriptor.configSchema.version, emptyMap())
            ConfigurationPolicy.validate(plugin, configuration)
            if (!environment.availableCapabilities.containsAll(plugin.descriptor.capabilities)) {
                throw ContractError.CapabilityMissing("required provider unavailable")
            }
            (plugin as? ConfiguredPlugin)?.configure(configuration)
            val services = environment.services(resource, plugin.descriptor.capabilities)
            scope = resource
            active = plugin
            state = SessionState.Ready
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
        }
    }

    fun switchTo(id: String): ActivationResult = command {
        if (!sceneReady || sessionId == null) return@command ActivationResult.SessionNotReady
        if (id !in plugins) return@command ActivationResult.UnknownPlugin
        teardown(DeactivationReason.Switched)
        generation = Generation(Math.incrementExact(generation.value))
        selectedId = id
        activateSelected()
    }

    fun pause() = command {
        if (sessionId != null && state != SessionState.Paused) {
            teardown(DeactivationReason.Paused)
            generation = Generation(Math.incrementExact(generation.value))
            state = SessionState.Paused
        }
    }

    fun resume(): ActivationResult = command {
        if (!sceneReady || sessionId == null) return@command ActivationResult.SessionNotReady
        if (active != null) return@command ActivationResult.OwnerBusy
        activateSelected()
    }

    fun stop() = command {
        if (sessionId != null) teardown(DeactivationReason.Stopped)
        sessionId = null
        selectedId = null
        sceneReady = false
        state = SessionState.Idle
    }

    fun interrupt() = command {
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

    private fun teardown(reason: DeactivationReason) {
        val epoch = inputEpoch
        inputEpoch = null
        epoch?.let { runCatching { environment.input.stop(it) } }
        scope?.revoke()
        scope = null
        val previous = active
        active = null
        previous?.let { runCatching { it.deactivate(reason) } }
        sessionId?.let { runCatching { environment.output.clear(it, generation) } }
    }

    fun accept(assistance: Assistance): Boolean = command {
        active != null && Capability.TextOutput in active!!.descriptor.capabilities &&
            sessionId?.let { assistance.isCurrent(it, generation) } == true &&
            assistance.expiresAtNanos > environment.clock.nowNanos() && assistance.optionalSpeech == null
    }
}
