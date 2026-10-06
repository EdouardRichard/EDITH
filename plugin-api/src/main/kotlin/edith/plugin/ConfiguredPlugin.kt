package edith.plugin

interface ConfiguredPlugin {
    fun validate(configuration: PluginConfiguration)
    fun configure(configuration: PluginConfiguration)
}
