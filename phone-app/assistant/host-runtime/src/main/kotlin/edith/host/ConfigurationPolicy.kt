package edith.host

import edith.contracts.ContractError
import edith.plugin.*

internal object ConfigurationPolicy {
    fun validate(plugin: PluginLifecycle, configuration: PluginConfiguration) {
        if (configuration.schemaVersion != plugin.descriptor.configSchema.version) {
            throw ContractError.ConfigurationRejected("schema mismatch")
        }
        (plugin as? ConfiguredPlugin)?.validate(configuration)
    }
}
