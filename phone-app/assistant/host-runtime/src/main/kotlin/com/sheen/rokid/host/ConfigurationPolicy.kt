package com.sheen.rokid.host

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*

internal object ConfigurationPolicy {
    fun validate(plugin: PluginLifecycle, configuration: PluginConfiguration) {
        if (configuration.schemaVersion != plugin.descriptor.configSchema.version) {
            throw ContractError.ConfigurationRejected("schema mismatch")
        }
        (plugin as? ConfiguredPlugin)?.validate(configuration)
    }
}
