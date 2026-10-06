package edith.plugin

fun HostServices.model(): ModelClient = require(Capability.Model) as ModelClient
fun HostServices.audio(): AudioSubscription = require(Capability.Audio) as AudioSubscription
fun HostServices.asr(): AsrSession = require(Capability.Asr) as AsrSession
fun HostServices.speaker(): SpeakerService = require(Capability.Speaker) as SpeakerService
fun HostServices.storage(): StorageHandle = require(Capability.Storage) as StorageHandle
fun HostServices.textOutput(): OutputSink = require(Capability.TextOutput) as OutputSink
