package edith.plugin

fun HostServices.speech(): SpeechPlayer = require(Capability.SpeechOutput) as SpeechPlayer

/**
 * Returns the audio segment source for the current session. When the host has no audio capture wired, the returned
 * source's [AudioSegmentSource.fetch] always returns null rather than throwing.
 */
fun HostServices.audioSegments(): AudioSegmentSource {
    // Try to get from the Audio capability first; fall back to a no-op source.
    return try { require(Capability.Audio) as? AudioSegmentSource ?: NoOpAudioSegmentSource }
    catch (_: Exception) { NoOpAudioSegmentSource }
}

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
