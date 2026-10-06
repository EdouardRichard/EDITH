package edith.host

import edith.contracts.*
import edith.plugin.*

class HostRuntime(private val apiVersion: Int) {
    private val plugins = linkedMapOf<String, PluginLifecycle>()
    private var sessionId: SessionId? = null
    private var sceneReady = false
    private var active: PluginLifecycle? = null
    private var generation = Generation(0)
    val currentGeneration: Generation get() = generation
    val currentState: SessionState get() = when {
        sessionId == null -> SessionState.Idle
        active == null -> if (sceneReady) SessionState.Ready else SessionState.BuildingScene
        else -> SessionState.Listening
    }

    fun register(plugin: PluginLifecycle) {
        require(plugin.descriptor.apiVersion == apiVersion) { "unsupported api version" }
        require(plugins.putIfAbsent(plugin.descriptor.pluginId, plugin) == null) { "duplicate plugin id" }
    }

    fun startSession(id: SessionId) { sessionId = id; sceneReady = false; active = null; generation = Generation(0) }
    fun markSceneReady() { check(sessionId != null); sceneReady = true }

    fun activate(id: String): ActivationResult {
        if (!sceneReady || sessionId == null) return ActivationResult.SessionNotReady
        if (active != null) return ActivationResult.OwnerBusy
        val plugin = plugins[id] ?: return ActivationResult.UnknownPlugin
        return runCatching {
            plugin.activate(PluginContext(sessionId!!, generation, plugin.descriptor.capabilities, EmptyHostServices))
            active = plugin
            ActivationResult.Activated
        }.getOrElse { ActivationResult.Failed }
    }

    fun switchTo(id: String): ActivationResult {
        if (!sceneReady || sessionId == null) return ActivationResult.SessionNotReady
        val next = plugins[id] ?: return ActivationResult.UnknownPlugin
        active?.deactivate(DeactivationReason.Switched)
        active = null
        generation = Generation(generation.value + 1)
        return runCatching {
            next.activate(PluginContext(sessionId!!, generation, next.descriptor.capabilities, EmptyHostServices))
            active = next
            ActivationResult.Activated
        }.getOrElse { ActivationResult.Failed }
    }

    fun accept(assistance: Assistance): Boolean =
        active != null && sessionId?.let { assistance.isCurrent(it, generation) } == true && assistance.expiresAtNanos >= 0
}
