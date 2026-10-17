package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.SessionId

enum class DeactivationReason { Switched, Paused, Stopped, Error }

enum class PluginLifecycleState { Registered, Configured, Active, Stopping, Inactive }

interface PluginLifecycle {
    val descriptor: PluginDescriptor
    fun activate(context: PluginContext)
    fun handle(event: HostEvent)
    fun deactivate(reason: DeactivationReason)
}

data class PluginContext(
    val sessionId: SessionId,
    val generation: Generation,
    val grantedCapabilities: Set<Capability>,
    val services: HostServices,
)
