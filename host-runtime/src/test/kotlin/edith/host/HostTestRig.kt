package edith.host

import edith.contracts.*
import edith.plugin.*
import edith.testsupport.*

class HostTestRig(capabilities: Set<Capability> = setOf(Capability.Audio, Capability.TextOutput)) {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val input = RecordingInputPort()
    val output = RecordingHintDevice()
    val providers = capabilities.associateWith { capability ->
        when (capability) {
            Capability.Audio -> object : AudioSubscription {
                override fun listen(listener: (AudioSegment) -> Unit) = FakeCancellation()
            }
            Capability.TextOutput -> FakeGlassesOutput()
            else -> error("rig unsupported capability")
        }
    }
    val environment = object : HostEnvironment {
        override val clock = this@HostTestRig.clock
        override val input = this@HostTestRig.input
        override val output = this@HostTestRig.output
        override val availableCapabilities = capabilities
        override fun services(scope: ResourceScope, granted: Set<Capability>) =
            ScopedHostServices(scope, granted, providers, FakeTaskScope())
    }
    val host = HostRuntime(1, environment)
    val plugin = ProbePlugin("probe", listOf(Capability.Audio, Capability.TextOutput))
    init { host.register(plugin); host.startSession(SessionId("s")); host.markSceneReady() }

    companion object { fun readyWithAudioPlugin() = HostTestRig() }
}

class ProbePlugin(id: String, capabilities: List<Capability>) : PluginLifecycle, ConfiguredPlugin {
    override val descriptor = PluginDescriptor(id, "1", 1, id, capabilities, ConfigSchema(1))
    var activations = 0
    var deactivations = 0
    var failActivate = false
    var failDeactivate = false
    var requireValue = false
    var context: PluginContext? = null
    val events = mutableListOf<HostEvent>()
    override fun validate(configuration: PluginConfiguration) {
        if (requireValue && configuration.values["required"].isNullOrBlank()) throw ContractError.ConfigurationRejected("required")
    }
    override fun configure(configuration: PluginConfiguration) = Unit
    override fun activate(context: PluginContext) {
        activations++
        this.context = context
        if (failActivate) error("synthetic activation failure")
    }
    override fun handle(event: HostEvent) { events += event }
    override fun deactivate(reason: DeactivationReason) {
        deactivations++
        if (failDeactivate) error("synthetic teardown failure")
    }
}
