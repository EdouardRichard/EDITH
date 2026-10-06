package edith.plugins.smart

import edith.contracts.HostEvent
import edith.plugin.*

class SmartAssistantPlugin : PluginLifecycle {
    override val descriptor = PluginDescriptor("smart-assistant", "1.0.0", 1, "Smart Assistant", listOf(Capability.Model, Capability.TextOutput), ConfigSchema(1))
    var active = false
    override fun activate(context: PluginContext) { active = true }
    override fun handle(event: HostEvent) { if (active) Unit }
    override fun deactivate(reason: DeactivationReason) { active = false }
}
