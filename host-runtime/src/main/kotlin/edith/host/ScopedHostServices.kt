package edith.host

import edith.contracts.*
import edith.plugin.*
import java.util.concurrent.atomic.AtomicBoolean

class ScopedHostServices(
    private val scope: ResourceScope,
    private val granted: Set<Capability>,
    providers: Map<Capability, Any>,
    taskScope: TaskScope,
) : HostServices {
    private val services: Map<Capability, Any>
    override val tasks: TaskScope

    init {
        services = granted.associateWith { capability ->
            val provider = providers[capability] ?: throw ContractError.CapabilityMissing("provider missing: $capability")
            when (capability) {
                Capability.Audio -> scopedAudio(provider as? AudioSubscription ?: wrong(capability))
                Capability.Asr -> scopedAsr(provider as? AsrSession ?: wrong(capability))
                Capability.Speaker -> scopedSpeaker(provider as? SpeakerService ?: wrong(capability))
                Capability.Model -> scopedModel(provider as? ModelClient ?: wrong(capability))
                Capability.Storage -> scopedStorage(provider as? StorageHandle ?: wrong(capability))
                Capability.TextOutput -> scopedOutput(provider as? OutputSink ?: wrong(capability))
                Capability.SpeechOutput -> throw ContractError.CapabilityMissing("speech output unavailable")
                Capability.Actions -> scopedActions(provider as? ActionExecutor ?: wrong(capability))
                Capability.Context -> scopedContext(provider as? ContextSignals ?: wrong(capability))
                Capability.WakeWord -> scopedWakeWord(provider as? WakeWordRegistry ?: wrong(capability))
            }
        }
        val child = ResourceScope(scope.pluginId, scope.sessionId, scope.generation)
        val tasksCancelled = AtomicBoolean()
        fun cancelTasks() {
            if (tasksCancelled.compareAndSet(false, true)) child.revoke()
        }
        scope.bind(object : CancellationHandle { override fun cancel() = cancelTasks() })
        tasks = object : TaskScope {
            override fun launch(block: () -> Unit): CancellationHandle = scope.start {
                subscription({ callback -> taskScope.launch { callback(Unit) } }, { _: Unit -> block() }, child)
            }
            override fun cancelAll() = cancelTasks()
        }
    }

    override fun require(capability: Capability): Any = scope.active {
        services[capability] ?: throw ContractError.CapabilityMissing("capability not granted: $capability")
    }

    private fun wrong(capability: Capability): Nothing =
        throw ContractError.ConfigurationRejected("wrong provider type: $capability")

    private fun <T> subscription(start: ((T) -> Unit) -> CancellationHandle, listener: (T) -> Unit,
        owner: ResourceScope = scope) = owner.start {
        val child = ResourceScope(scope.pluginId, scope.sessionId, scope.generation)
        child.bind(start { value -> scope.deliver { owner.deliver { child.deliver { listener(value) } } } })
        object : CancellationHandle { override fun cancel() = child.revoke() }
    }

    private fun scopedAudio(provider: AudioSubscription) = object : AudioSubscription {
        override fun listen(listener: (AudioSegment) -> Unit) = subscription(provider::listen, listener)
    }
    private fun scopedAsr(provider: AsrSession) = object : AsrSession {
        override fun listen(listener: (TranscriptRevision) -> Unit) = subscription(provider::listen, listener)
    }
    private fun scopedSpeaker(provider: SpeakerService) = object : SpeakerService {
        override fun enroll(windows: List<SpeakerWindow>, listener: (SpeakerProfileRef?) -> Unit) =
            subscription({ provider.enroll(windows, it) }, listener)
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit) =
            subscription({ provider.assess(window, it) }, listener)
        override fun delete(profile: SpeakerProfileRef) = scope.active { provider.delete(profile) }
    }
    private fun scopedModel(provider: ModelClient) = object : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            scope.checkActive()
            require(request.envelope.sessionId == scope.sessionId && request.envelope.generation == scope.generation) {
                "request scope mismatch"
            }
            return subscription({ provider.request(request, it) }, listener)
        }
    }
    private fun scopedStorage(provider: StorageHandle) = object : StorageHandle {
        override fun read(key: String) = scope.active { provider.read(key) }
        override fun write(key: String, value: ByteArray) = scope.active { provider.write(key, value) }
        override fun delete(key: String) = scope.active { provider.delete(key) }
        override fun clear() = scope.active { provider.clear() }
    }
    private fun scopedOutput(provider: OutputSink) = object : OutputSink {
        override fun submit(assistance: Assistance) = scope.active {
            if (!assistance.isCurrent(scope.sessionId, scope.generation)) OutputSubmission.Rejected
            else provider.submit(assistance)
        }
    }
    private fun scopedActions(provider: ActionExecutor) = object : ActionExecutor {
        override fun descriptors() = scope.active { provider.descriptors() }
        override fun execute(name: String, argsJson: String) = scope.active { provider.execute(name, argsJson) }
        override fun cancel() = scope.active { provider.cancel() }
    }
    private fun scopedContext(provider: ContextSignals) = object : ContextSignals {
        override fun snapshot() = scope.active { provider.snapshot() }
        override fun listen(listener: (ContextSnapshot) -> Unit) = subscription(provider::listen, listener)
    }
    private fun scopedWakeWord(provider: WakeWordRegistry) = object : WakeWordRegistry {
        override fun declare(words: List<String>) = scope.active { provider.declare(words) }
        override fun current() = scope.active { provider.current() }
    }
}
