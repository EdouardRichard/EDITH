package edith.plugin

fun HostServices.speech(): SpeechPlayer = require(Capability.SpeechOutput) as SpeechPlayer

/**
 * Returns the audio segment source for the current session. When the host has no audio capture wired, the returned
 * source's [AudioSegmentSource.fetch] always returns null rather than throwing.
 */
/**
 * Returns the audio segment source for the current session.
 *
 * P5 (C2 fix): the source is set by the host composition via [setGlobalAudioSegmentSource] rather than going through
 * the capability-based [require] path, because the smart plugin does not declare [Capability.Audio] and the segment
 * service is host-owned infrastructure, not a plugin-granted capability.
 */
fun HostServices.audioSegments(): AudioSegmentSource = globalAudioSegmentSource ?: NoOpAudioSegmentSource

@Volatile private var globalAudioSegmentSource: AudioSegmentSource? = null

/** Called by the host composition to register the audio segment bridge. */
fun setGlobalAudioSegmentSource(source: AudioSegmentSource?) { globalAudioSegmentSource = source }

private object NoOpAudioSegmentSource : AudioSegmentSource {
    override fun fetch(turnId: String): AudioSegmentHandle? = null
}
fun HostServices.model(): ModelClient = require(Capability.Model) as ModelClient
fun HostServices.audio(): AudioSubscription = require(Capability.Audio) as AudioSubscription
fun HostServices.asr(): AsrSession = require(Capability.Asr) as AsrSession
fun HostServices.speaker(): SpeakerService = require(Capability.Speaker) as SpeakerService
fun HostServices.storage(): StorageHandle = require(Capability.Storage) as StorageHandle
fun HostServices.textOutput(): OutputSink = require(Capability.TextOutput) as OutputSink
fun HostServices.actions(): ActionExecutor = require(Capability.Actions) as ActionExecutor
fun HostServices.context(): ContextSignals = require(Capability.Context) as ContextSignals
fun HostServices.wakeWord(): WakeWordRegistry = require(Capability.WakeWord) as WakeWordRegistry
fun HostServices.confirmation(): ConfirmationChannel = require(Capability.Confirmation) as ConfirmationChannel
fun HostServices.systemOne(): SystemOneService = require(Capability.SystemOne) as SystemOneService
fun HostServices.embedding(): EmbeddingService = require(Capability.Embedding) as EmbeddingService
