package com.sheen.rokid.plugin

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

/**
 * Platform device facts (spec §3.2, §4.2.1): the static profile and the per-request state.
 *
 * **Optional by design**: a host with no platform provider never grants it, so a caller asks for it the way the
 * plugin asks for a confirmation channel - `runCatching { services.deviceState() }.getOrNull()` - and omits the
 * device section from the prompt instead of failing the turn.
 */
fun HostServices.deviceState(): DeviceStateProvider = require(Capability.DeviceState) as DeviceStateProvider

/**
 * The user's own SKILL directory, on shared storage (spec §3.5).
 *
 * Separate from [storage], which is the app-private key/value namespace: `Capability.StorageAccess` is the grant to
 * read a directory the user manages, and a plugin granted only one of the two gets only that one.
 */
fun HostServices.storageAccess(): StorageAccess = require(Capability.StorageAccess) as StorageAccess

/**
 * The conversation as the wearer sees it (Phase C, spec §6.6).
 *
 * **Optional by design**: a host with no display never provides it, so a caller asks for it the way the plugin asks for
 * a confirmation channel - `runCatching { services.conversationDisplay() }.getOrNull()` - and carries on without it. A
 * missing conversation view must never stop a turn.
 */
fun HostServices.conversationDisplay(): ConversationDisplay =
    require(Capability.ConversationDisplay) as ConversationDisplay

/**
 * The per-turn perception port (edith-0.3 Phase 2, spec §3.2, §7.1–§7.4).
 *
 * **Optional by design**: a host with no perception layer never grants the capability, so a caller asks for it the
 * way the plugin asks for a confirmation channel — `runCatching { services.perception() }.getOrNull()` — and omits
 * the perception block instead of failing the turn. The host owns the turn boundary; see [PerceptionProvider].
 */
fun HostServices.perception(): PerceptionProvider = require(Capability.Perception) as PerceptionProvider
