package com.sheen.rokid.plugin

class PluginDescriptor(
    val pluginId: String,
    val version: String,
    val apiVersion: Int,
    val displayName: String,
    requestedCapabilities: List<Capability>,
    val configSchema: ConfigSchema,
) {
    val capabilities: Set<Capability> = requestedCapabilities.toSet()
    init {
        require(pluginId.isNotBlank())
        require(version.isNotBlank())
        require(apiVersion > 0)
        require(displayName.isNotBlank())
        require(requestedCapabilities.size == capabilities.size) { "duplicate capabilities" }
    }
}
