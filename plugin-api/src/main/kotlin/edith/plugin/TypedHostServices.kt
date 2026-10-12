package edith.plugin

fun HostServices.speech(): SpeechPlayer = require(Capability.SpeechOutput) as SpeechPlayer
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
