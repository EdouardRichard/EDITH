package com.sheen.rokid.phone.plugin.interview

import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.plugin.*

class InterviewAssistantPlugin : PluginLifecycle {
    override val descriptor = PluginDescriptor("interview-assistant", "1.0.0", 1, "Interview Assistant", listOf(Capability.Speaker, Capability.Storage, Capability.Model, Capability.TextOutput), ConfigSchema(1))
    var active = false
    override fun activate(context: PluginContext) { active = true }
    override fun handle(event: HostEvent) { if (active) Unit }
    override fun deactivate(reason: DeactivationReason) { active = false }
}
