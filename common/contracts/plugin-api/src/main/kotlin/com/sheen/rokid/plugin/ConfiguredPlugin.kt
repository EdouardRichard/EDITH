package com.sheen.rokid.plugin

interface ConfiguredPlugin {
    fun validate(configuration: PluginConfiguration)
    fun configure(configuration: PluginConfiguration)
}
