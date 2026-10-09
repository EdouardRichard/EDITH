package edith.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import edith.audio.AcousticLabeler
import edith.audio.LocalSpeakerService
import edith.audio.KeywordSpotterWakeWordEngine
import edith.audio.PcmFramer
import edith.audio.PcmIngress
import edith.audio.PcmOffer
import edith.audio.SpeakerEngine
import edith.audio.SpeakerPolicy
import edith.audio.SpeechPipeline
import edith.audio.StreamingAsrEngine
import edith.audio.VadEngine
import edith.audio.WakeWordDetector
import edith.audio.android.EngineManifest
import edith.audio.android.EngineManifestLoader
import edith.audio.android.EngineModelStore
import edith.audio.android.KeywordAssetBundle
import edith.audio.android.SherpaEngineFactory
import edith.audio.android.SherpaKeywordSpotterFactory
import edith.audio.android.SherpaVadFactory
import edith.audio.android.ValidatedModelBundle
import edith.contracts.*
import edith.host.HostEnvironment
import edith.host.HostRuntime
import edith.model.*
import edith.output.TraceRecorder
import edith.plugin.*
import edith.plugins.smart.SmartAssistantPlugin
import edith.plugins.interview.InterviewAssistantPlugin
import edith.rokid.PcmFormatEvidence
import edith.storage.*
import edith.phone.storage.*
import edith.phone.actions.AppLaunchResolver
import edith.phone.actions.ContextIntentLauncher
import edith.phone.actions.IntentActionExecutor
import edith.phone.actions.PackageManagerAppCatalog
import edith.phone.model.ProactiveArming
import edith.phone.model.ProfileViewModel
import edith.phone.model.ProbeConsent
import edith.phone.model.ProfileProbeGate
import edith.phone.model.SessionUploadConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HostCompositionRoot internal constructor(
    val host: HostRuntime,
    val profiles: ProfileRepository,
    val model: ModelClient,
    private val environment: HostEnvironment?,
    resources: List<AutoCloseable> = emptyList(),
    val trace: TraceRecorder = TraceRecorder(),
    val profileTester: ProfileTester? = null,
    private val profileConsent: (ProbeConsent?) -> Unit = {},
    private val namespaceFactory: ((String) -> StorageHandle)? = null,
    val speakerProfiles: SpeakerProfileStore? = null,
    /** Production App wiring for the CXR transport; null keeps the audio capability honestly missing. */
    val rokidConnectionFactory: ((Any) -> CxrConnection)? = null,
    /** Initial RAM-only tee of the live CXR PCM; see [installRawPcmObserver]. */
    internal val initialRawPcmObserver: (ByteArray) -> Unit = {},
    private val authorizeResult: (Int, Intent?) -> Unit = { _, _ -> },
    /** T18 display path for a live CXR connection; null keeps the assembly without one (tests, unwired builds). */
    private val hintLink: CxrHintLink? = null,
    /**
     * The smart plugin and the settings-built configuration it must be given before activation (Task 14a item 1).
     *
     * Null keeps a composition that has no smart plugin registered (several T17/T18 harnesses build one), so the
     * configuration/activation steps below simply do not run. The `Capability.WakeWord` provider is
     * [SmartWiring.wakeWords] - the same instance the plugin declares its configured words to - so the assembly
     * holds exactly one word set (Task 14a-fix item 5).
     */
    private val smartWiring: SmartWiring? = null,
    /**
     * The store the settings screen writes through (Task 14b).
     *
     * Null for the harnesses that build a root by hand: they have no blob store to hand, and the settings screen is
     * the only caller of the two accessors below, so a root without one refuses those calls instead of pretending to
     * save. Production passes the same instance `create` loaded the composition's own settings from, so a save and
     * the next load read one blob.
     */
    private val smartSettingsStore: SmartSettingsStore? = null,
    /**
     * D13: the session-scoped text-upload toggle.
     *
     * Session state, not a stored secret, and **off by default**. The gated model client consults it through
     * [consentSource] for real turns; the 会话 page's switch is its only writer, and [stop] returns it to off so a
     * grant can never outlive the session it was given for.
     */
    internal val sessionUploadConsent: SessionUploadConsent = SessionUploadConsent(),
    /**
     * P3.2: the session-scoped 「主动辅助」 arming switch (spec §16.2).
     *
     * Session state, not a stored secret, and **off by default**. It is the host's gate on producing perception
     * events - see [armedAcousticSink] - so `create`'s chain emits no `AcousticEvent` while it is off, and [stop]
     * (and every transition back to `Idle`) returns it to off so an arming can never outlive its session.
     */
    internal val proactiveArming: ProactiveArming = ProactiveArming(),
) : AutoCloseable {
    /**
     * RAM-only tee of the live CXR PCM used by the T17 controlled format bring-up. Defaulted to a no-op
     * so normal app behaviour is unchanged; the observer must never persist or export the bytes. It stays
     * assignable because the bring-up harness arms it after the App composition exists but before the lazy
     * connection is built.
     */
    internal var rawPcmObserver: (ByteArray) -> Unit = initialRawPcmObserver
        private set

    /**
     * Shares the observer slot with the lazy connection factory, which is created before [installRawPcmObserver] can
     * be called. Null only for compositions built outside [create] (tests that supply their own factory).
     */
    private var observerHolder: Array<(ByteArray) -> Unit>? = null

    /**
     * Keeps the public field and the factory's shared slot in step.
     *
     * Both paths must agree: the field is what callers and tests read, the holder is what the lazy connection calls.
     * Updating only one of them is exactly the defect that made every capture read zero bytes.
     */
    private fun armObserver(observer: (ByteArray) -> Unit) {
        rawPcmObserver = observer
        observerHolder?.set(0, observer)
    }

    /** Resources that must be released with this composition; later additions close first. */
    private val resources: MutableList<AutoCloseable> =
        resources.toMutableList().also { list -> hintLink?.let(list::add) }
    /** Set when an activation was refused for missing capabilities, so a later display can retry it once. */
    @Volatile private var awaitingDisplayForActivation = false

    /**
     * The mapping the display retry was last attempted against, so one exchange is not retried twice.
     *
     * Both display signals can arrive for the same `PONG` (the transition **and** "a mapping is live"), and each
     * exchange mints a fresh `syncId`, so the identifier is what separates "the same sync, reported twice" from "a
     * later renewal" - which is a fresh chance for the missing capability to have appeared and must be retried.
     */
    private var retriedMappingSyncId: String? = null

    /**
     * Debug-only: submits the next fixed hint through the diagnostic probe plugin, or null when no probe is
     * registered (release builds). The UI offers a button only when this is non-null.
     */
    internal var diagnosticHintSender: (() -> Boolean)? = null

    /**
     * Debug-only: submits a hint of a **given** text through the diagnostic probe plugin, or null when no probe is
     * registered. Unlike [diagnosticHintSender] this is driven from a host shell (P3.2 Stage 0's capacity probe) and
     * its text must reach the display unchanged, so the caller owns the length. Never present in a release build.
     */
    internal var diagnosticHintTextSender: ((String) -> Boolean)? = null

    init {
        // Activation runs when the scene becomes ready, which is before the first clock mapping exists, so a plugin
        // whose only missing capability is the display is refused at that moment. Two signals retry it, because the
        // mapping that fixes the refusal is not always a fresh transition: the display becoming real on a link that
        // was not live before, and **every** successful exchange - the link's steady state is "live and re-syncing
        // in place", which fires no transition at all (D10, measured on the K40 2026-10-09, where a refused
        // activation stayed unretryable for a whole session while `mapping live` recurred every ~20 s).
        //
        // D7: the same two signals are also the only moments the answerable set can grow, so the capability panel is
        // recomputed here as well - **outside** that retry's `awaitingDisplayForActivation` gate, which is closed
        // whenever nothing was refused and would otherwise leave the panel stale for a perfectly healthy session.
        hintLink?.onDisplayBecameLive = { onDisplaySignal() }
        hintLink?.onMappingLive = { onDisplaySignal() }
    }

    /** One display signal: the panel follows what is wired now, then an activation refused for the display is retried. */
    private fun onDisplaySignal() {
        mutableState.update { it.copy(capabilityReasons = capabilityReasons()) }
        retryActivationForDisplay()
    }
    /**
     * What each capability the environment cannot answer is reported as, **recomputed on every read** (D7).
     *
     * This used to be a `val` computed once at construction and stored in the UI state. The environment's
     * `availableCapabilities` is a live derivation - `TextOutput` appears only once a clock mapping makes the display
     * real - so the stored copy was a start-up snapshot: measured on the K40 on 2026-10-09, the 会话 page still read
     * `眼镜显示/尚不可用` after the display had become real and a plugin had activated on the strength of it. Nothing
     * gates on this map; it is the panel's, which is exactly why it must not be able to lie about the present.
     */
    private fun capabilityReasons(): Map<Capability, String> =
        Capability.entries.filter { it !in (environment?.availableCapabilities ?: emptySet()) }
            .associateWith { reasonFor(it) }

    private fun reasonFor(capability: Capability): String = when (capability) {
        Capability.Audio -> "TRANSPORT_MISSING"
        Capability.Asr, Capability.Speaker -> "ENGINE_MISSING"
        Capability.Model -> "MODEL_OPERATION_UNAVAILABLE"
        Capability.TextOutput -> "DISPLAY_MISSING"
        Capability.SpeechOutput -> "SPEECH_OUTPUT_MISSING"
        Capability.Storage -> "HOST_STORAGE_PROVIDER_MISSING"
        Capability.Actions -> "ACTION_PROVIDER_MISSING"
        Capability.Context -> "CONTEXT_PROVIDER_MISSING"
        Capability.WakeWord -> "WAKE_PROVIDER_MISSING"
    }
    private val mutableState =
        MutableStateFlow(SessionUiState(SessionState.Idle, null, capabilityReasons(), null))
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    private val observation = host.observeState { session ->
        // D13: the upload grant is scoped to one session, and every path back to Idle ends it - including the ones
        // that call `host.stop()` directly (a denied start) and never reach this composition's `stop`.
        if (session == SessionState.Idle) sessionUploadConsent.deactivate()
        // P3.2: arming is session-scoped too, and for the same reason - a switch left on after the session it was
        // given for would let the next session perceive without the user asking again.
        if (session == SessionState.Idle) proactiveArming.deactivate()
        mutableState.update { it.copy(session = session, capabilityReasons = capabilityReasons()) }
    }
    private var closed = false
    private var activatedPlugin: String? = null
    private var activeConnection: CxrConnection? = null

    /**
     * The self-audio gate the wake detector consults (Task 14b), and the **only** gate this composition has.
     *
     * A composition-owned `val` exposed to the UI so P3.2's speech output can bracket a real playback with
     * [PlaybackGate.begin]/[PlaybackGate.end]. It is the same instance the chain's detector reads: `create` hands it
     * to the chain through [playbackSuppression], so `begin()` on this exposed handle suppresses the detector this
     * composition's own chain built (Task 14b-fix item 1 - a second, local gate inside `create` left this handle
     * inert).
     *
     * In P3.1 it is always open and inert: hints carry no speech and no provider answers `Capability.SpeechOutput`,
     * so there is nothing to suppress yet.
     */
    val playbackGate = PlaybackGate()

    /**
     * The settings the composition was assembled with, read back from the same store the screen writes through.
     *
     * Read fresh rather than returned from the assembled snapshot because the screen's job is to edit the stored
     * value, and showing the snapshot after a save would be misleading. The composition's own configuration is
     * deliberately unaffected until the next assembly - which is what the screen's copy says.
     */
    fun storedSettings(): SmartSettings =
        requireNotNull(smartSettingsStore) { "SETTINGS_STORE_UNAVAILABLE" }.load(profiles)

    /** Persists one settings edit; the next App start's composition picks it up (this one never re-reads). */
    fun saveSmartSettings(settings: SmartSettings) {
        requireNotNull(smartSettingsStore) { "SETTINGS_STORE_UNAVAILABLE" }.save(settings)
    }

    /**
     * Descriptors of the plugins this composition registers, keyed by plugin id.
     *
     * The composition owns the plugins it registers, so it can answer "does the plugin I just activated declare
     * X?" itself. The alternative - a public `HostRuntime.activeDescriptor()` - was one accessor past the granted
     * read-only identity surface (Task 14a-fix item 4), and the descriptor of the plugin *this* composition just
     * registered is exactly what a side effect like arming the ears must be gated on. A plugin registered straight
     * on the host by some harness is simply not known here, and "declares nothing" is then the honest answer.
     */
    private val descriptors = java.util.concurrent.ConcurrentHashMap<String, PluginDescriptor>()

    /**
     * Registers [plugin] with the host and remembers what it declares.
     *
     * Every registration the composition performs goes through here, so [declares] covers the smart plugin, the
     * interview shell and the debug-only plugins alike; `create` uses it for all of them.
     */
    internal fun registerPlugin(plugin: PluginLifecycle) {
        host.register(plugin)
        descriptors[plugin.descriptor.pluginId] = plugin.descriptor
    }

    /** True when the plugin this composition knows under [pluginId] declares [capability]. */
    private fun declares(pluginId: String, capability: Capability): Boolean =
        descriptors[pluginId]?.capabilities?.contains(capability) == true

    private data class StartGrant(val token: String, val pluginId: String, val expires: Long)
    private var pendingStart: StartGrant? = null
    private val clock = environment?.clock ?: object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
    fun profileViewModel(): ProfileViewModel? {
        val repository = profiles as? AndroidProfileRepository ?: return null
        return ProfileViewModel(repository, profileTester ?: return null, profileConsent, ::applyProfileSelection)
    }
    fun namespace(pluginId: String): StorageHandle {
        require(pluginId in setOf("smart-assistant", "interview-assistant") || (BuildConfig.DEBUG && pluginId == DIAGNOSTIC_PLUGIN_ID))
        return namespaceFactory?.invoke(pluginId) ?: throw ContractError.CapabilityMissing("Persistent storage unavailable")
    }
    /**
     * Applies a profile selection the user made to the plugin it belongs to.
     *
     * The configuration is rebuilt whole ([configurationFor]) rather than patched with the profile fields:
     * `HostRuntime.configure` replaces the stored configuration, so sending only the profile selection would drop the
     * settings-derived keys the plugin runs with (Task 14a-fix item 1). A plugin that is already active must be
     * paused first, because the host refuses to configure an active plugin.
     */
    @Synchronized private fun applyProfileSelection(pluginId: String, profile: ModelProfile?) {
        if (closed) return
        if (activatedPlugin == pluginId) { host.pause(); activatedPlugin = null }
        val configuration = configurationFor(pluginId, profile) ?: return
        check(host.configure(pluginId, configuration)) { "Profile selection unavailable" }
    }

    /**
     * The complete configuration this composition owns for [pluginId], or null when it owns none.
     *
     * **One** configuration per activation. The smart plugin's settings keys and the user's profile selection used to
     * be applied as two successive `configure` calls, and `HostRuntime.configure` replaces the stored configuration -
     * so the second call silently dropped the first one's keys and the plugin fell back to its own defaults for the
     * risk ceiling, the wake words and the whitelist the user had configured (Task 14a-fix item 1). It is also built
     * only for the plugin it belongs to: the diagnostic text plugin's key set is closed and different, so sending it
     * the smart keys refuses the whole activation (item 2). A plugin this composition does not register (and so knows
     * no keys for) is left untouched.
     */
    private fun configurationFor(pluginId: String, profile: ModelProfile?): PluginConfiguration? {
        if (!descriptors.containsKey(pluginId)) return null
        smartWiring?.let { wiring ->
            if (pluginId == wiring.plugin.descriptor.pluginId) return wiring.configuration(profile)
        }
        if (pluginId == DIAGNOSTIC_PLUGIN_ID) return PluginConfiguration(1, diagnosticValues(profile))
        return null
    }

    /**
     * The profile that applies to [pluginId].
     *
     * The App stores one selection per plugin, so that selection wins where this composition has such a store (the
     * Android repository). Without one - a JVM harness, or a plugin the user never selected for - the smart plugin
     * falls back to the settings' own resolved choice, because that is the profile the settings name; any other
     * plugin gets no profile, and its own shape fills in the contract-valid placeholder it needs. A repository that
     * refuses (locked keystore, deleted payload) yields null rather than propagating: the configuration still has to
     * be built, and the plugin's own lookup finds nothing until storage answers again.
     */
    private fun selectedProfile(pluginId: String): ModelProfile? {
        val chosen = runCatching { (profiles as? AndroidProfileRepository)?.selections()?.get(pluginId) }.getOrNull()
        if (chosen != null) runCatching { profiles.get(chosen) }.getOrNull()?.let { return it }
        return if (pluginId == smartWiring?.plugin?.descriptor?.pluginId) smartWiring?.selectedProfile() else null
    }

    /** The diagnostic text plugin's own configuration shape; it refuses every key that is not one of these. */
    private fun diagnosticValues(profile: ModelProfile?): Map<String, String> = mapOf(
        "profileId" to (profile?.id ?: "__unselected__"),
        "profileRevision" to (profile?.revision ?: 0).toString(),
        "requestConsent" to "false",
        "inputMode" to "transcript")

    @Synchronized fun start(pluginId: String) {
        if (closed) return
        pendingStart = null
        mutableState.update { it.copy(selectedPluginId = pluginId, errorCode = null) }
        val session = SessionId(UUID.randomUUID().toString())
        host.startSession(session)
        activeSessionId = session.value
        activatedPlugin = null
        // A new session re-arms the clock exchange (`sessionEstablished` drops the old mapping), so the next live
        // mapping is a fresh one and must be retried against even if it somehow carried the previous identifier.
        retriedMappingSyncId = null
        // A connection that became ready **before** the session existed must still establish the hint link: the
        // transport reports readiness once, and dropping that signal on the floor would leave the glasses without a
        // `HELLO` for the whole session, so every hint would be refused as an unknown session.
        maybeMarkSceneReady()
        if (environment == null) { host.pause(); error("CAPABILITY_MISSING") }
    }
    @Synchronized fun onSceneReady() {
        if (closed) return
        // Remembered rather than only acted on: readiness can arrive before a session is started, and the signal is
        // delivered exactly once by the transport.
        linkReady = true
        maybeMarkSceneReady()
    }

    /** True once the transport reported a live session; kept so a later session start can still establish the link. */
    private var linkReady = false
    private var activeSessionId: String? = null

    /**
     * The audio epoch this composition started, or null while the ears are not armed.
     *
     * Kept so `stop`/`pause` stop the epoch that is actually running: `AudioIngressLoop.stop` matches on the epoch
     * value, so a freshly built epoch would stop nothing and the poll loop would outlive its session.
     */
    @Volatile private var runningAudioEpoch: InputEpoch? = null

    /**
     * The transport port [armAudioEpoch] started alongside the loop, so `disarm` stops the same port even if the
     * active connection has been replaced meanwhile (Task 15c D1).
     */
    private var runningAudioTransport: InputPort? = null
    private var inputSerial = 0L

    /** The epoch currently polling the ingress, or null. Read by the App's diagnostics and by tests. */
    fun currentAudioEpoch(): InputEpoch? = runningAudioEpoch

    /**
     * Starts the single audio epoch the passive loop listens on (Task 14a item 4).
     *
     * The smart plugin does **not** declare `Capability.Audio` - advertising that would make the host start the
     * microphone path for any audio-requiring plugin, which is not what the wake word needs - so the host will never
     * start `environment.input` for it. The composition therefore arms the epoch itself, and only while a plugin is
     * actually active: a second call is a no-op, because `AudioIngressLoop` would double-poll every chunk if a second
     * loop were created.
     *
     * **Both ports, one epoch (Task 15c D1).** The chain's loop only *polls* the ingress; opening it and asking the
     * glasses for the stream happens solely inside `RokidSessionTransport.start` (`ingress.open` + `startAudioStream`).
     * Arming only the loop therefore left the ingress unopened for the whole of a device run - `PcmIngress.format`
     * stayed null (so any datagram would be refused `UNKNOWN_FORMAT`) and the glasses, never asked, logged
     * `audio data 0 bytes`. The transport's own [InputPort] is started first, so the loop built after it polls an open
     * ingress; both are handed the **same** epoch. With no CXR connection there is no port to open the ingress, so
     * nothing is started at all here and the display-live retry re-enters this method once the connection exists.
     */
    @Synchronized fun armAudioEpoch() {
        if (closed || activatedPlugin == null) {
            P3Log.line("P3_AUDIO state=disarmed generation=${host.currentGeneration.value} " +
                "transport=false loop=false reason=NOT_ACTIVE")
            return
        }
        // A second arm of a still-running epoch changes nothing, so it is not an observable event and gets no line.
        if (runningAudioEpoch != null) return
        val session = activeSessionId ?: run {
            P3Log.line("P3_AUDIO state=disarmed generation=${host.currentGeneration.value} " +
                "transport=false loop=false reason=SESSION_MISSING")
            return
        }
        val transport = activeConnection?.inputPortOrNull()
        if (transport == null) {
            // Nothing has opened (or will open) the ingress, so a loop started here would poll a closed one. Left
            // unarmed on purpose rather than half-armed: the refusal path that precedes a connection leaves the
            // activation retryable, and that retry runs this method again with the port present.
            android.util.Log.i("EdithHost", "AUDIO_EPOCH_TRANSPORT_MISSING: no CXR connection, the audio epoch " +
                "stays disarmed (session=$session generation=${host.currentGeneration} state=${host.currentState})")
            P3Log.line("P3_AUDIO state=disarmed generation=${host.currentGeneration.value} " +
                "transport=false loop=false reason=TRANSPORT_MISSING")
            return
        }
        // The generation is read **here**, at the moment the epoch is built, and is never passed in by the caller.
        // `activateSelected` resolves its own copy before `HostRuntime.activate`/`switchTo` runs, and `switchTo`
        // increments the generation internally, so an epoch armed from that copy would name a generation the host has
        // already replaced - every frame it carries would build an envelope the host's identity gate silently drops
        // (Task 14a-fix2). This method runs under the composition lock, which every activation path holds, so the
        // read cannot observe a generation belonging to an activation other than the one that just succeeded.
        val epoch = InputEpoch(SessionId(session), host.currentGeneration, Math.incrementExact(inputSerial).also { inputSerial = it })
        runningAudioEpoch = epoch
        runningAudioTransport = transport
        // Either both halves run or neither does: a transport that opened the ingress with nothing polling it leaks a
        // live stream, and a loop over an ingress the transport refused to open is the defect this fix removes.
        val failure = runCatching {
            transport.start(epoch)
            environment?.input?.start(epoch)
        }.exceptionOrNull()
        if (failure != null) {
            runCatching { environment?.input?.stop(epoch) }
            runCatching { transport.stop(epoch) }
            // Nothing is polling, so the composition must not claim the ears are armed.
            runningAudioEpoch = null
            runningAudioTransport = null
            android.util.Log.i("EdithHost", "audio epoch could not start: ${failure.javaClass.name}: ${failure.message}")
            P3Log.line("P3_AUDIO state=disarmed generation=${epoch.generation.value} " +
                "transport=false loop=false reason=START_FAILED")
        } else {
            // Both ports are up and polling the same ingress under the same epoch (Task 15c D1), which is what the
            // two booleans let a device run check.
            P3Log.line("P3_AUDIO state=armed generation=${epoch.generation.value} transport=true loop=true")
        }
    }

    /** Stops the epoch [armAudioEpoch] started, if any. Idempotent, and safe before anything was armed. */
    @Synchronized fun disarmAudioEpoch() {
        val epoch = runningAudioEpoch ?: return
        runningAudioEpoch = null
        val transport = runningAudioTransport
        runningAudioTransport = null
        // The reverse of the arm order: stop the poll loop first, so it cannot poll an ingress the transport is about
        // to close, then the transport, which closes the ingress and stops the glasses' stream (Task 15c D1).
        runCatching { environment?.input?.stop(epoch) }
        runCatching { transport?.stop(epoch) }
        P3Log.line("P3_AUDIO state=disarmed generation=${epoch.generation.value} " +
            "transport=false loop=false reason=DISARMED")
    }

    /**
     * The spec's explicit, non-wake entry point: raise `UserAction.Help` at the host's current identity (Task 15c D2).
     *
     * The plugin's help path already existed (`SmartAssistantPlugin.handleHelp`, which runs the most recent final
     * transcript of **this** session) but nothing in production raised the action - only tests did - so the spec's
     * non-wake path could not be exercised on a device. The identity is read from the host at call time, exactly like
     * the wake emitter's: `HostRuntime.onEvent` accepts nothing else. A request that arrives after a pause, a stop or
     * a session switch is therefore dropped by the host rather than delivered under a stale generation, and a
     * composition with nothing active answers false instead of throwing.
     *
     * @return true when the host accepted the event for delivery to the active plugin; false when there is no live
     *   session to help (or this composition is closed).
     */
    @Synchronized fun requestHelp(): Boolean {
        if (closed) return false
        // A host closed underneath this composition has no identity to name; "nothing to help with" is a state, not
        // an error, so it is answered rather than thrown.
        val identity = runCatching { host.currentIdentity() }.getOrNull() ?: return false
        val now = clock.nowNanos()
        return host.onEvent(UserAction(
            EventEnvelope(identity.first, identity.second, "help-${UUID.randomUUID()}", now, now),
            UserAction.Action.Help,
        ))
    }

    /**
     * Forwards one receiver offer outcome to the chain (Task 14a item 5).
     *
     * `PcmIngress.offer`'s result used to be discarded inside the transport, so `INGRESS_OVERFLOW` and
     * `UNKNOWN_FORMAT` could never be reported. Arrives on the transport's worker thread; the chain makes the
     * "is there a loop yet" decision under its own lock, so a fault that races the first `start` is buffered, not lost.
     */
    internal fun onPcmOffer(offer: PcmOffer) {
        audioChain?.onOffer(offer)
    }

    /** The chain, once provisioned; the offer seam above has no other way to reach it. */
    internal var audioChain: CxrAudioChain? = null

    /**
     * Marks the scene ready once both halves exist - a live transport and a started session - and then establishes
     * the hint link. Order matters: `HELLO` and the clock exchange must precede any hint the plugins can produce.
     */
    @Synchronized private fun maybeMarkSceneReady() {
        if (closed || !linkReady || host.currentState != SessionState.BuildingScene) return
        host.markSceneReady()
        activeSessionId?.let { session -> hintLink?.sessionEstablished(session, host.currentGeneration.value) }
        // This runs on the transport's callback thread, so a failure while activating (unavailable storage, a refused
        // configuration) must become the App's error state rather than an exception thrown into the SDK's thread.
        runCatching { activateSelected() }.onFailure {
            host.pause()
            error("CAPABILITY_MISSING")
        }
    }

    /**
     * Explicit CXR entry point. Returns false when this assembly has no glasses transport (the audio
     * capability stays missing) and true when the connection was requested; the transport reports
     * readiness asynchronously through [onSceneReady], never through this call. The connection is built
     * on first use so the app does not open a CXR worker thread unless the user actually connects.
     */
    @Synchronized fun connectCxr(activity: Any): Boolean {
        val factory = rokidConnectionFactory ?: return false
        if (closed) return false
        val connection = activeConnection ?: factory(activity).also { built ->
            activeConnection = built
            resources.add(built)
        }
        // Route the shared reply channel into the hint link: acks are matched by sequence on the phone side, and the
        // clock exchange is answered there. The device owns neither the channel nor the routing decision.
        connection.onInboundCommand { json -> hintLink?.onInbound(json) }
        connection.connect(activity)
        return true
    }

    /** Routes the AI-App authorization result back into the transport; a no-op without a connection. */
    fun onCxrAuthorizationResult(resultCode: Int, data: Intent?) {
        activeConnection?.onAuthorizationResult(resultCode, data)
        authorizeResult(resultCode, data)
    }

    /** The live CXR transport, for callers that must drive an audio epoch (T17/T18 device bring-up). */
    fun cxrInputPort(): edith.plugin.InputPort? = activeConnection?.inputPortOrNull()

    /** True only once the live CXR transport reports a session; there is no ready transport without one. */
    fun cxrReady(): Boolean = activeConnection?.ready == true

    /**
     * The uplink receiver for the live connection, so a device test can compare what the glasses sent against what
     * the phone actually accepted.
     *
     * Deliberately narrow: it returns only the counters and the declared format, not the audio. A test needs to know
     * whether frames arrived and whether any were lost; it does not need the samples, and exposing them would make it
     * easy to persist audio that D33 says must stay in RAM.
     */
    fun cxrUplinkReport(): UplinkReport? {
        val receiver = (activeConnection as? RokidConnection)?.uplinkReceiver ?: return null
        val stats = receiver.stats
        return UplinkReport(
            framesAccepted = stats.framesAccepted,
            framesLost = stats.framesLost,
            duplicates = stats.duplicates,
            malformed = stats.malformed,
            gaps = stats.gaps,
            overflowed = stats.overflowed,
            declaredSampleRateHz = receiver.declaredFormat?.sampleRateHz,
            declaredChannelCount = receiver.declaredFormat?.selectedChannels?.size,
            endedWith = receiver.endedWith?.name,
        )
    }

    /**
     * Debug-only bring-up seam for the T17 controlled format check: captures the RAM-only tee the lazy
     * CXR connection passes to the transport. Refuses once a connection exists, because that connection
     * would already hold the previous observer and silently drop the capture.
     */
    internal fun installRawPcmObserver(observer: (ByteArray) -> Unit) {
        check(activeConnection == null) { "CXR_CONNECTION_ALREADY_BUILT" }
        // Both the field and the factory's slot, so a connection built later actually calls this observer. Updating
        // only the field left the transport on the no-op captured at construction.
        armObserver(observer)
    }

    private fun activateSelected() {
        val selected = state.value.selectedPluginId ?: return
        // One configuration, built once - settings keys and profile selection together - and applied only to the
        // plugin it belongs to. `HostRuntime.configure` replaces the stored configuration, so a second call carrying
        // just the profile selection silently dropped the smart keys the user configured (Task 14a-fix item 1), and
        // sending the smart keys to a plugin whose key set is different refuses the whole activation (item 2).
        configurationFor(selected, selectedProfile(selected))?.let { configuration ->
            if (!host.configure(selected, configuration)) {
                android.util.Log.i("EdithHost", "configuration refused for $selected")
                // Mirrors the activation-refusal branch below: no refusal may leave the previously active plugin
                // running with the ears armed while the App shows an error.
                disarmAudioEpoch()
                host.pause()
                error("CAPABILITY_MISSING")
                return
            }
        }
        val result = if (activatedPlugin == null) host.activate(selected) else host.switchTo(selected)
        if (result == edith.host.ActivationResult.Activated) {
            activatedPlugin = selected
            awaitingDisplayForActivation = false
            // Clearing the earlier refusal is part of succeeding: a retry that worked must not leave the App showing
            // the error it was retried from.
            mutableState.update { it.copy(errorCode = null) }
            // The smart plugin does not declare `Capability.Audio`, so the host never starts this epoch: the
            // composition arms it as soon as a plugin that declares the wake word is genuinely active. Gating on the
            // *capability* rather than "something activated" keeps a display-only plugin from putting a poll loop on
            // the CXR ingress - the microphone-start side effect `Audio` is deliberately unadvertised for. The other
            // half matters just as much: a successful switch **away** from the wake-word plugin must stop the epoch,
            // or the ears stay armed while the App says a plugin with no wake word owns the session (item 3).
            //
            // Stop the previous activation's epoch **before** arming, rather than only when the new plugin has no
            // wake word: a switch increments the host generation, so the epoch the previous plugin was polling under
            // can never be the one the pipeline should keep tagging frames with. Leaving it running also made
            // `armAudioEpoch` a no-op (it refuses while an epoch is running) on a switch between two wake-word
            // plugins, which left the ears on a dead generation (Task 14a-fix2). Disarming first also makes the arm
            // read the generation that is current *after* the switch, which is the only one the host accepts.
            disarmAudioEpoch()
            if (declares(selected, Capability.WakeWord)) armAudioEpoch()
        } else {
            disarmAudioEpoch()
            host.pause()
            // Recorded with the real result and the advertised set: the App's label collapses every failure into
            // "capability missing", so without this a device run cannot tell an OwnerBusy from a genuine gap.
            android.util.Log.i("EdithHost", "activation refused result=$result selected=$selected " +
                "available=${environment?.availableCapabilities} state=${host.currentState} " +
                "cause=${host.lastActivationCause}")
            error(if (result == edith.host.ActivationResult.UnknownPlugin) "UNKNOWN_PLUGIN" else "CAPABILITY_MISSING")
            // A capability refusal is worth one retry when the display becomes real; an unknown plugin is not.
            awaitingDisplayForActivation = result == edith.host.ActivationResult.Failed
        }
    }

    /**
     * Retries an activation that was refused for missing capabilities, once a clock mapping has made the display real.
     *
     * Reached from both display signals, and deliberately narrow:
     *
     * - **Once per mapping.** The transition callback and [CxrHintLink.onMappingLive] both arrive for a `PONG` that
     *   makes the display live, so the mapping's own `syncId` is remembered and a repeat for the same exchange is
     *   dropped. A later renewal carries a fresh `syncId`, so a capability that appears later is still picked up,
     *   and a capability that stays missing costs exactly one attempt per signal - not a loop.
     * - **Nothing once it is settled.** No attempt while a plugin is already active or this composition is closed,
     *   and the refusal flag is what `stop()` clears, so a stopped session is never resurrected by a re-sync.
     * - **The session is put back where activation is legal.** The refusal path's `host.pause()` leaves the session
     *   paused, and `markSceneReady()` is the same call the start path makes after `startSession` - without a ready
     *   scene, `HostRuntime.activate` refuses with `SessionNotReady` (which is what an `interrupt()` that cleared
     *   the scene would otherwise leave behind). It deliberately starts **no** session: no `startSession`, no new
     *   generation and no second `SessionService` grant, because the live clock mapping, the identity and the
     *   generation the armed epoch must name all belong to the session that is already running.
     */
    @Synchronized private fun retryActivationForDisplay() {
        if (closed || !awaitingDisplayForActivation || activatedPlugin != null) return
        val link = hintLink ?: return
        // A signal is only useful while a mapping is really live; the syncId is also the "same exchange" token.
        val syncId = link.liveSyncId() ?: return
        if (syncId == retriedMappingSyncId) return
        retriedMappingSyncId = syncId
        android.util.Log.i("EdithHost", "display retry: syncId=$syncId state=${host.currentState} " +
            "activated=$activatedPlugin available=${environment?.availableCapabilities}")
        // Refuses (throws) when there is no session at all - the stopped case - which is why the result is checked.
        if (runCatching { host.markSceneReady() }.isFailure) return
        runCatching { activateSelected() }
    }

    @Synchronized fun pause() { if (!closed) { pendingStart = null; disarmAudioEpoch(); host.pause() } }
    @Synchronized fun resume() {
        if (closed || host.currentState != SessionState.Paused) return
        if (environment == null) { error("CAPABILITY_MISSING"); return }
        mutableState.update { it.copy(errorCode = null) }
        activateSelected()
    }
    @Synchronized fun stop() {
        if (closed) return
        pendingStart = null
        // D13: the upload grant does not outlive the session it was given for. Done here as well as on the Idle
        // transition below, because `stop` is also reached when no transition happens (a stop after an interrupt, or
        // before the first start), and a session-scoped grant must never survive it.
        sessionUploadConsent.deactivate()
        // P3.2: and so does the arming switch. Done here as well as on the Idle transition, because `stop` is also
        // reached when no transition happens (a stop before the first start), and an arming must never survive it.
        proactiveArming.deactivate()
        // D15: a stopped session must not keep repeating its opening handshake to the glasses.
        hintLink?.sessionEnded()
        // Before `host.stop()`: the epoch named by `runningAudioEpoch` is the one the loop is polling under, and
        // after teardown the input port may no longer be able to answer for it.
        disarmAudioEpoch()
        host.stop()
        activatedPlugin = null
        awaitingDisplayForActivation = false
        retriedMappingSyncId = null
        mutableState.update { it.copy(errorCode = null) }
    }
    @Synchronized fun selectPlugin(pluginId: String) {
        if (closed || state.value.selectedPluginId == pluginId) return
        mutableState.update { it.copy(selectedPluginId = pluginId, errorCode = null) }
        when (host.currentState) {
            SessionState.Ready, SessionState.Listening -> activateSelected()
            else -> Unit
        }
    }
    @Synchronized fun authorizeStart(pluginId: String): String {
        check(!closed) { "COMPOSITION_CLOSED" }
        val token = UUID.randomUUID().toString()
        pendingStart = StartGrant(token, pluginId, clock.nowNanos() + TimeUnit.SECONDS.toNanos(5))
        return token
    }
    @Synchronized fun dispatch(action: String?, token: String? = null, redelivered: Boolean = false, permissionGranted: Boolean = true,
        foregroundActive: Boolean = true, beforeStart: () -> Unit = {}): Boolean {
        if (closed) return false
        if (action == "edith.START") {
            val grant = pendingStart
            pendingStart = null
            if (redelivered || grant == null || token != grant.token || clock.nowNanos() >= grant.expires) return false
            if (!permissionGranted) { host.stop(); error("BLUETOOTH_PERMISSION_DENIED"); return false }
            beforeStart()
            start(grant.pluginId)
            return true
        }
        if (redelivered || action == null) return false
        return when (action) {
            "edith.STOP" -> { stop(); true }
            "edith.PAUSE" -> { pause(); true }
            "edith.RESUME" -> {
                if (!foregroundActive) { error("FOREGROUND_UI_REQUIRED"); false }
                else if (!permissionGranted) { pause(); error("BLUETOOTH_PERMISSION_DENIED"); false }
                else { resume(); true }
            }
            else -> false
        }
    }
    internal fun error(code: String) { mutableState.update { it.copy(errorCode = code) } }
    @Synchronized override fun close() {
        if (closed) return
        stop()
        closed = true
        profileConsent(null)
        observation.cancel()
        try { host.close() } finally {
            try { (model as? AutoCloseable)?.close() } finally {
                resources.asReversed().forEach { runCatching { it.close() } }
            }
        }
    }
    companion object {
        /** The debug-only diagnostic text plugin's id; its configuration shape is not the smart plugin's. */
        private const val DIAGNOSTIC_PLUGIN_ID = "p2-diagnostic"

        fun create(context: Context, initialObserver: (ByteArray) -> Unit = {}): HostCompositionRoot {            // Transport, audio engines and glasses output remain separate device gates.
            val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
            val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "edith-phone-deadlines").apply { isDaemon = true } }
            val scheduler = object : DeadlineScheduler {
                override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                    val future = timer.schedule(task, (deadlineNanos - clock.nowNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
                }
            }
            val blobs = AndroidEncryptedBlobStore(context)
            val resolver = KeystoreVault(blobs)
            val profiles = AndroidProfileRepository(blobs, resolver)
            val probeGate = ProfileProbeGate(profiles)
            // D13: the one instance the UI's switch writes and the model client reads. Session state only - nothing
            // here is persisted, and it starts off.
            val sessionUploadConsent = SessionUploadConsent()
            // P3.2: the arming switch and its gate. One instance for the UI, the chain's acoustic sink and the
            // reset paths, exactly as the upload consent is one instance for the UI and the model client.
            val proactiveArming = ProactiveArming()
            // The model request had no production line before the P3.1 wrap-up: the client was built with no
            // interceptor, so a successful request was silent and that hop rested on its effects. This is the one line
            // that closes the gap, and it carries nothing but method, status and duration (`ModelHttpLog`).
            val http = OkHttpClient.Builder().addInterceptor(ModelHttpLog(clock::nowNanos)).build()
            val model = ControlledModelClient(http, profiles, resolver, CapabilityGate(), ContextBudgeter(), clock, scheduler,
                consentSource(sessionUploadConsent, probeGate::requestConsent))
            val controlledTester = ControlledProfileTester(model, profiles, clock)
            val tester = object : ProfileTester {
                override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                    return probeGate.test(controlledTester, profileId, listener)
                }
            }
            val connection = arrayOfNulls<RokidConnection>(1)
            // T18 display path. Both pieces are built before the host because `HostRuntime` reads the environment at
            // construction, while the connection they depend on is created lazily on first connect - so the sender
            // reads the holder rather than capturing a connection that does not exist yet.
            val hintLink = CxrHintLink(clock, scheduler,
                sendJson = { json -> connection[0]?.sendCommand(json) ?: false },
                diagnostics = { message -> android.util.Log.i("EdithHint", message) })
            // Plugin background work gets its own single thread: the deadline executor must not be delayed by it.
            val pluginTasks = Executors.newSingleThreadExecutor { Thread(it, "edith-plugin-tasks").apply { isDaemon = true } }
            val allowedNamespaces = setOf("smart-assistant", "interview-assistant") +
                (if (BuildConfig.DEBUG) setOf("p2-diagnostic") else emptySet())
            val namespaceFactory: (String) -> StorageHandle = { pluginId ->
                require(pluginId in allowedNamespaces) { "UNKNOWN_PLUGIN_NAMESPACE" }
                AndroidNamespaceStorage(pluginId, blobs)
            }
            // Task 12: the L0/L1 action provider. The catalog resolves a spoken app name through the package manager
            // (the manifest's QUERY_ALL_PACKAGES is what makes other apps visible), the launcher starts the platform
            // intent from this application context, and notes go into the App's own encrypted `smart-assistant`
            // namespace. Nothing here reaches an L2+ capability: no message, no dial, no payment, no deletion.
            // Built eagerly but touching no device API at construction, so a composition without a real Context still
            // assembles (the catalog and the launcher refuse at call time instead).
            val actions = IntentActionExecutor(
                resolver = AppLaunchResolver(PackageManagerAppCatalog(context)),
                launcher = ContextIntentLauncher(context),
                noteStorage = namespaceFactory("smart-assistant"))
            // The environment advertises exactly the capabilities it can answer: the composition's own gated model
            // client, the per-plugin namespace, the display once a clock mapping makes it real, and - since Task 11 -
            // the ASR provider over the CXR audio chain when a validated engine bundle is present. `Audio` stays
            // unadvertised even then: there is no `AudioSubscription` in the delivery path (transcripts reach a
            // plugin through `AsrSession`), and the host starts `input` for audio-requiring plugins, so advertising
            // it would turn the CXR ingress into a microphone-start path. Speaker and speech output stay unwired, so
            // the host still refuses plugins that need them (`interview-assistant` needs Speaker - deliberately).
            //
            // The chain is built only from a validated bundle and the verified uplink format. When either is absent
            // the input keeps refusing and Asr stays unadvertised: a provider that cannot transcribe must not be
            // advertised, or a plugin would start and then fail confusingly later.
            //
            // Task 14a: the settings are read **once**, at assembly time, and produce both the plugin's configuration
            // and the wake-word provider's content. The store's failure modes are already defaults, so a missing or
            // corrupt blob leaves a configure-able, arm-able app rather than an un-activatable plugin.
            //
            // Task 14b: this is also the store the settings screen writes through, so a save and the next load read
            // the same blob. The composition's own settings stay the ones read here; the screen's copy is explicit
            // that only a full App restart re-reads them (this root is a process-lifetime singleton).
            val smartSettingsStore = SmartSettingsStore(blobs)
            val smartSettings = smartSettingsStore.load(profiles)
            // Task 14b: the self-audio seam. P3.1 emits no audio at all, so the gate stays open and this wiring
            // changes nothing today; it is what P3.2 hands to the speech output path. See `PlaybackGate`.
            //
            // Task 14b-fix item 1: there is exactly **one** gate - the composition's own `playbackGate` field - and
            // the detector reads it through this holder. `create` provisions the chain before the composition exists,
            // so the holder is how the same instance reaches both the detector and the exposed accessor; a second,
            // local gate here is what made `root.playbackGate.begin()` suppress nothing.
            val rootHolder = arrayOfNulls<HostCompositionRoot>(1)
            val hostHolder = arrayOfNulls<HostRuntime>(1)
            // P3.2 Task 6: the context provider. Stage A exposes the zero-permission time keys; Stage B appends its
            // three permissioned sources to the same list. The listener is the production arming gate, so a disarmed
            // session produces no `ContextChanged` at all - the plugin's only context signal is the acoustic event.
            // The arming state itself is published as a source, because the plugin has to be able to read the switch's
            // **current** answer at request time (local transcription continues while it is off).
            val contextSignals = CxrContextSignals(clock, listOf(CxrContextSignals.armingSource(proactiveArming)))
            contextSignals.listen(armedContextSink({ hostHolder[0] }, clock, proactiveArming))
            val audio = provisionAudioChain(
                context = context,
                clock = clock, scheduler = scheduler, ingress = { connection[0]?.ingress },
                emit = { event -> hostHolder[0]?.onEvent(event) },
                cancelTurn = { turnId ->
                    hostHolder[0]?.let { runtime ->
                        runtime.currentInputEpoch?.let { epoch ->
                            runtime.cancelOutputTurn(epoch.sessionId, runtime.currentGeneration, turnId)
                        }
                    }
                },
                allowDebugImport = BuildConfig.DEBUG,
                blobs = blobs,
                onFault = { code -> android.util.Log.i("EdithAudio", code) },
                // Task 14a-fix item 7: an accepted chunk arrives every ~20 ms and a `STALE_EPOCH` offer is the
                // ordinary consequence of audio arriving after a session ended, so neither is worth a log line. Only
                // the two outcomes the loop itself treats as faults are reported here.
                onOffer = { offer -> when (offer) {
                    PcmOffer.OVERFLOW, PcmOffer.UNKNOWN_FORMAT -> android.util.Log.i("EdithAudio", "OFFER:${offer.name}")
                    PcmOffer.ACCEPTED, PcmOffer.STALE_EPOCH -> Unit
                } },
                // Task 14a item 3: the detector's emission becomes a real event. The identity is resolved **per
                // emission** and from the host itself, so the only wakes that exist are the ones the host will accept
                // - and a wake that arrives after the session ended is dropped by `onEvent` instead of opening a turn
                // under a generation that no longer exists.
                wakeEmit = { word, confidence -> hostHolder[0]?.let { host -> emitWakeDetected(host, clock, word, confidence) } },
                // Task 4 (P3.2): the same per-emission identity resolution for the acoustic gate, so a label that
                // arrives after the session ended is dropped by `onEvent` instead of opening anything under a
                // generation that no longer exists. Task 5: the host only produces the event while 「主动辅助」 is on.
                acousticEmit = armedAcousticSink({ hostHolder[0] }, clock, proactiveArming),
                cooldownNanos = smartSettings.cooldownNanos,
                suppressWhile = playbackSuppression { rootHolder[0] })
            // The smart plugin is registered and configured here, with the composition's own clock, scheduler,
            // profile lookup, output-turn cancellation and configured cooldown - Task 13 made it a real
            // `ConfiguredPlugin`, so the bare constructor would be refused by the host's configuration policy.
            val smartWiring = SmartWiring(smartSettings, profiles, clock, scheduler) { sessionId, generation, turnId ->
                hostHolder[0]?.cancelOutputTurn(sessionId, generation, turnId)
            }
            val environment = CxrHostEnvironment(clock = clock, input = audio?.input ?: UnwiredInputPort,
                liveOutput = hintLink::liveOutput, scheduler = scheduler,
                providers = buildMap {
                    put(Capability.Model, model)
                    audio?.let { put(Capability.Asr, it.asrSession) }
                    // Task 12: always answerable - the five L0/L1 actions need no engine, no transport and no
                    // permission at assembly time, so the advertised set and the answerable set stay equal.
                    put(Capability.Actions, actions)
                    // Task 14a item 2: the configured word list is answerable without a model, a transport or a
                    // permission, so `Capability.WakeWord` joins the advertised set and the smart plugin (which
                    // declares it) can finally pass the host's capability gate. The provider is the wiring's own
                    // registry - the instance the plugin declares its configured words to - so there is one word set,
                    // not a second registry built from the same settings (Task 14a-fix item 5).
                    put(Capability.WakeWord, smartWiring.wakeWords)
                    // P3.2 Task 6: `Capability.Context` stops being an advertised-but-unanswerable capability. The
                    // provider is the composition's own signals object, so the values a plugin reads and the events
                    // the host delivers describe one source, not two.
                    put(Capability.Context, contextSignals)
                },
                storageFor = namespaceFactory,
                taskScope = ExecutorTaskScope(pluginTasks))
            val host = HostRuntime(1, environment)
            hostHolder[0] = host
            // The observer is shared through a holder rather than captured by value, because the bring-up harness arms
            // it (via installRawPcmObserver) AFTER this composition is built but BEFORE the lazy connection is created.
            // Capturing the value here - or letting `create`'s parameter name shadow the field, as it did - hands the
            // transport a permanent no-op, so every capture reads zero bytes however much audio actually arrives.
            val observerHolder = arrayOf(initialObserver)
            val scene = SceneReadyResolver()
            val root = HostCompositionRoot(host, profiles, model, environment, buildList {
                add(AutoCloseable {
                    timer.shutdownNow(); pluginTasks.shutdownNow()
                    http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
                })
                // Closes the pipeline (and with it the native engines) with the composition that owns them.
                audio?.let { add(it) }
            }, profileTester = tester, profileConsent = probeGate::grant,
                namespaceFactory = namespaceFactory, speakerProfiles = AndroidSpeakerProfileStore(blobs),
                // One CXR connection per composition, built on first use: the transport stays the only
                // SDK access path and the App carries the exact recorded pairing. Readiness still arrives
                // from the SDK, so a wired-but-not-ready connection keeps the audio capability missing.
                rokidConnectionFactory = { activity ->
                    // Read through the holder, so an observer armed after this composition was built is still the one
                    // the transport calls.
                    RokidConnection(activity.asCxrActivity(), CxrGlassesTarget.config(), host, scene::onSceneReady,
                        clock, { data -> observerHolder[0](data) },
                        // Task 14a item 5: the receiver's offer outcome is what turns an overflow into a report.
                        onOffer = { offer -> rootHolder[0]?.onPcmOffer(offer) }).also { connection[0] = it }
                },
                authorizeResult = { resultCode, data -> connection[0]?.onAuthorizationResult(resultCode, data) },
                hintLink = hintLink,
                smartWiring = smartWiring,
                smartSettingsStore = smartSettingsStore,
                sessionUploadConsent = sessionUploadConsent,
                proactiveArming = proactiveArming)
            // Keep the instance field and the holder pointing at the same observer, so installRawPcmObserver affects a
            // connection that has not been built yet.
            root.observerHolder = observerHolder
            root.armObserver(initialObserver)
            root.audioChain = audio
            rootHolder[0] = root
            scene.composition = root
            // Task 14a item 1: the real plugin, not `SmartAssistantPlugin()` with defaults. Its configuration is
            // applied by `activateSelected` before every activation, from the same settings this root was built with.
            root.registerPlugin(smartWiring.plugin)
            root.registerPlugin(InterviewAssistantPlugin())
            // Debug-only: registers the diagnostic text plugin and the hint probe, and hands the UI the probe's
            // sender. Null in release builds, which is what keeps the probe out of the product surface.
            root.diagnosticHintSender = DiagnosticRegistration.register(root, clock, scheduler)
            return root
        }

        /** Holds the composition so a lazily built transport can report scene readiness back into it. */
        private class SceneReadyResolver {
            @Volatile var composition: HostCompositionRoot? = null
            fun onSceneReady() { composition?.onSceneReady() }
        }

        /**
         * D13: the consent the gated model client consults, and the **only** one it is given.
         *
         * A named production seam for the same reason as [playbackSuppression]: the composition that has the probe
         * gate and the session toggle is built by `create`, which needs a real Android `Context` and so cannot be
         * driven from a JVM test. Both answers have to reach the one source, and the rule that matters - the session
         * toggle adds `text` for a real turn and **never** the probe flag - is then provable against the real
         * `ControlledModelClient` rather than against a copy of the merge.
         */
        internal fun consentSource(session: SessionUploadConsent, probe: () -> RequestConsent): () -> RequestConsent =
            { session.requestConsent(probe()) }

        /**
         * The wake detector's self-audio guard over the composition's one [HostCompositionRoot.playbackGate]
         * (Task 14b-fix item 1).
         *
         * A named production seam rather than an inline lambda in `create`: `create` provisions the chain **before**
         * the composition exists, so the guard takes the composition through [composition] and resolves the gate at
         * frame time - which is also what makes the identity testable. [create] hands exactly this function the holder
         * it later fills with the root, and a JVM test hands it an already-built composition; both read the same
         * exposed `playbackGate`, so `begin()` on the handle the UI exposes is the state the detector consults.
         *
         * The null branch keeps the gate **open** (no suppression) rather than closing it, because "the composition
         * is not built yet" is not a playback and must not drop legitimate wakes.
         */
        internal fun playbackSuppression(composition: () -> HostCompositionRoot?): () -> Boolean =
            { composition()?.playbackGate?.isPlaying == true }

        /**
         * Turns one detector emission into the event the host delivers (Task 14a item 3).
         *
         * Built here, and with the host's **current** identity, because that is the only identity `HostRuntime.onEvent`
         * accepts. Nothing else is added: `WakeDetected` and its `Capability.WakeWord` mapping already exist, so
         * arming the wake path needs a correct identity and nothing else - a new contract would have been the wrong
         * fix for "the event was dropped".
         *
         * `internal`, with the clock injected, so a test drives **this** builder rather than a copy of it
         * (Task 14a-fix item 6); the clock is the composition's own, which is what `HostRuntime` validates against,
         * so the removed `currentClockNanos()` accessor is not needed (item 4).
         */
        internal fun emitWakeDetected(host: HostRuntime, clock: MonotonicClock, word: String, confidence: Double) {
            val identity = host.currentIdentity() ?: return
            val now = clock.nowNanos()
            val bounded = confidence.coerceIn(0.0, 1.0)
            // On emission, before delivery: the line is evidence that the detector fired even if the host later drops
            // the event for a stale identity. It carries the word and the bounded confidence and nothing else.
            P3Log.line("P3_WAKE word=$word confidence=$bounded")
            host.onEvent(WakeDetected(
                EventEnvelope(identity.first, identity.second, "wake-${UUID.randomUUID()}", now, now),
                word,
                bounded,
            ))
        }

        /**
         * Turns one acoustic label transition into the event the host delivers (Task 4).
         *
         * Built here and with the host's **current** identity, exactly like [emitWakeDetected]: the label is the only
         * payload, so the line and the event carry no audio and no transcript. A label that arrives after the session
         * ended is dropped by `onEvent` rather than delivered under a generation that no longer exists.
         */
        internal fun emitAcoustic(host: HostRuntime, clock: MonotonicClock, label: AcousticLabel) {
            val identity = host.currentIdentity() ?: return
            val now = clock.nowNanos()
            // The label is a coarse category, never the audio that produced it.
            P3Log.line("P3_ACOUSTIC label=$label")
            host.onEvent(AcousticEvent(
                EventEnvelope(identity.first, identity.second, "acoustic-${UUID.randomUUID()}", now, now),
                label,
            ))
        }

        /**
         * The **arming gate**: the acoustic sink the chain is assembled with, which produces nothing unless
         * [ProactiveArming.enabled] is on (P3.2, spec §16.2).
         *
         * A named production seam rather than a lambda inside `create`, for the same reason as [playbackSuppression]:
         * the gate is the whole mechanism by which the switch works, so a JVM test drives **this** function instead of
         * a copy of it. The check happens before [emitAcoustic], so a disarmed composition emits no event *and* writes
         * no `P3_ACOUSTIC` line - nothing downstream can observe the label at all.
         *
         * `host` is a function because `create` provisions the chain before the composition's host holder is filled;
         * resolving it per label is also what makes a late label drop out through the host's own identity gate.
         */
        internal fun armedAcousticSink(host: () -> HostRuntime?, clock: MonotonicClock,
            arming: ProactiveArming): (AcousticLabel) -> Unit =
            { label -> if (arming.enabled.value) host()?.let { emitAcoustic(it, clock, label) } }

        /**
         * Turns one context change into the event the host delivers (Task 6).
         *
         * Only the **key names** reach the log: a value can be a foreground package or, in Stage B, a calendar title,
         * and neither belongs in a log line. The values themselves travel only inside the event, to a plugin the host
         * has already granted `Context`.
         */
        internal fun emitContextChanged(host: HostRuntime, clock: MonotonicClock, snapshot: ContextSnapshot) {
            val identity = host.currentIdentity() ?: return
            val now = clock.nowNanos()
            P3Log.line("P3_CONTEXT keys=${snapshot.values.keys.sorted().joinToString(",")}")
            host.onEvent(ContextChanged(
                EventEnvelope(identity.first, identity.second, "context-${UUID.randomUUID()}", now, now),
                snapshot.capturedAtNanos,
                snapshot.values,
            ))
        }

        /**
         * The arming gate for context, exactly as [armedAcousticSink] is for the acoustic labels: with 「主动辅助」 off
         * the composition produces no `ContextChanged`, so a plugin receives no context signal (spec §16.2, pre-flight
         * P4).
         */
        internal fun armedContextSink(host: () -> HostRuntime?, clock: MonotonicClock,
            arming: ProactiveArming): (ContextSnapshot) -> Unit =
            { snapshot -> if (arming.enabled.value) host()?.let { emitContextChanged(it, clock, snapshot) } }
    }
}

/** The SDK coordinate the shipping pairing resolves its uplink format for; see `PcmFormatEvidence`. */
private const val CXR_SDK_VERSION = "1.1.2"
private const val ENGINE_MANIFEST_ASSET = "engine-manifest.json"

/**
 * The debug-only import drop: `adb push` a locally produced bundle (P2/T17) here and a debug build picks it
 * up when the model store has no active bundle.
 *
 * Debug-only by construction ([provisionAudioChain] only reads it when `BuildConfig.DEBUG`), because a release
 * build must never activate model bytes that arrived from outside the App's own storage. **The manifest is not
 * always the App's bundled asset**: `EngineBundleProvisioning` reads a pushed `engine-manifest.json` as well, so a
 * bundle imported through the debug path is validated against the manifest it was pushed with - what limits the
 * trust is the debug-only gate above, not the manifest's origin.
 */
internal object DebugImportDirectory {
    const val STORE_DIRECTORY = "engine-models"
    const val IMPORT_DIRECTORY = "engine-models-import"

    fun store(context: Context) =
        EngineModelStore(context.applicationContext.noBackupFilesDir.toPath().resolve(STORE_DIRECTORY))

    fun source(context: Context): File =
        context.applicationContext.noBackupFilesDir.resolve(IMPORT_DIRECTORY)
}

/**
 * The engines a provisioned chain needs, as a seam.
 *
 * Production uses the defaults, which are the real sherpa factories over the App's own assets; a JVM unit test
 * cannot load the JNI library, so it substitutes stubs by overriding the factory methods and the branch
 * selection around them is still exercised. The wake detector's own VAD comes from the same validated bundle
 * and is deliberately a **separate instance** from the pipeline's: `WakeWordDetector` resets its VAD on the
 * speech→silence edge.
 *
 * A null `clock` means the default [wakeDetector] must not build a detector, because `WakeWordDetector` needs a
 * clock to honour its cooldown; tests override that method and never reach the default. The `suppressWhile`
 * argument is forwarded to the detector unchanged: it is the caller's state (the `PlaybackGate`), not the
 * factory's business, and this factory is the only place production constructs a detector.
 */
internal open class AudioChainEngines(
    internal val assets: android.content.res.AssetManager? = null,
    internal val clock: MonotonicClock? = null,
    internal val blobs: AndroidEncryptedBlobStore? = null,
    /**
     * The configured per-word cooldown, in nanoseconds, that the detector must honour.
     */
    internal val cooldownNanos: Long = 3_000_000_000L,
) {
    /** The VAD-only engine for the detector; null when it cannot be built from the bundle. */
    open fun vad(bundle: ValidatedModelBundle): VadEngine? = SherpaVadFactory.create(bundle).engine

    /**
     * The pipeline's engines loaded from the bundle, using the format the caller already resolved. The full
     * candidate is the only place the recognizer and the speaker extractor are loaded, and it agrees with the
     * VAD-only factory on the same validated bundle.
     */
    open fun asrCandidate(bundle: ValidatedModelBundle, verified: VerifiedPcm): CandidateEngines? {
        val candidate = SherpaEngineFactory.createCandidate(bundle, verified)
        return CandidateEngines(candidate.vad, candidate.asr,
            LocalSpeakerService(candidate.embedding, AndroidSpeakerProfileStore(requireNotNull(blobs)),
                // No attribution thresholds are configured in this phase, so every assessment is UNKNOWN - the
                // label the product rules require to be non-triggering. Enrolment, thresholds and the `Speaker`
                // capability are their own task; inventing them here would silently change attribution behaviour.
                SpeakerPolicy(minVoicedMillis = 1, selfThreshold = null, otherThreshold = null)))
    }

    /** The detector over the packaged KWS assets, or null when the assets or the spotter are unavailable. */
    open fun wakeDetector(bundle: ValidatedModelBundle, vad: VadEngine,
        emit: (word: String, confidence: Double) -> Unit,
        suppressWhile: () -> Boolean): WakeWordDetector? {
        val manager = assets ?: return null
        val timeSource = clock ?: return null
        val keywordAssets = KeywordAssetBundle.load(manager) ?: return null
        val port = SherpaKeywordSpotterFactory.create(keywordAssets, manager) ?: return null
        return WakeWordDetector(vad, KeywordSpotterWakeWordEngine(port, keywordAssets.keywordsArgument),
            emit = emit, clock = timeSource, cooldownNanos = cooldownNanos, suppressWhile = suppressWhile)
    }
}

/** The pipeline-side engines of one candidate; a holder rather than a `SherpaEngineCandidate` so tests can substitute. */
internal class CandidateEngines(val vad: VadEngine, val asr: StreamingAsrEngine, val speaker: SpeakerEngine)

/**
 * A wake detector together with the VAD-only engine it owns.
 *
 * Separate from the pipeline's VAD by contract (`WakeWordDetector` resets its VAD on the speech→silence edge),
 * and therefore separate in ownership too: closing the chain closes this VAD, while the pipeline closes its own
 * engines in `SpeechPipeline.close()`. Modelling that as one object means a caller cannot build a detector and
 * forget to release its engine.
 */
internal class WiredWakeDetector(val detector: WakeWordDetector, private val vad: VadEngine) : AutoCloseable {
    override fun close() {
        runCatching { detector.close() }
        runCatching { vad.close() }
    }
}

/**
 * The App's assembled audio chain (Task 11): the CXR ingress polled into the speech pipeline, with the pipeline's
 * transcripts exposed to plugins as the `Capability.Asr` provider.
 *
 * The chain is built from pieces that already exist - native engines from a validated bundle and the connection's
 * own [PcmIngress] - so nothing here reaches for a microphone or a network: PCM arrives over CXR, exactly as D33
 * requires it to stay in RAM.
 *
 * The ingress is resolved **lazily** because the CXR connection (and with it the ingress) is created on first
 * connect, after the environment that holds this chain has to exist. The loop is built on the first `start(epoch)`
 * - or on the first reported offer - and then reused.
 *
 * [onFault]/[onOffer] are the chain's public forwarders (Task 11b, Important 3). The ingress owner receives the
 * receiver's `PcmOffer` and cannot hand it to a private loop, so the chain is the object that turns it into the
 * loop's fault codes and passes the outcome on to diagnostics.
 */
internal class CxrAudioChain private constructor(
    private val ingress: () -> PcmIngress?,
    private val framer: PcmFramer,
    private val pipeline: SpeechPipeline,
    private val wake: WiredWakeDetector?,
    private val scheduler: DeadlineScheduler,
    private val clock: MonotonicClock,
    private val faultSink: (String) -> Unit,
    private val offerSink: (PcmOffer) -> Unit,
    val asrSession: AsrSession,
    /**
     * The detector's real emission sink, as the composition installs it.
     *
     * Exposed because it is the only seam the composition's wiring can be exercised through without a microphone or a
     * keyword-spotting model: a JVM test can call it exactly as the detector would and observe what reaches
     * `host.onEvent`. The identity a wake carries is the emitter's business - the composition resolves it from the
     * host at emission time - so this seam stays `(word, confidence)`, exactly like `WakeWordDetector.emit`.
     *
     * Assignable because the detector needs it at **construction** while the chain that owns it is built first; the
     * production factory assigns it immediately, before the chain can poll a single frame.
     */
    var wakeEmit: (word: String, confidence: Double) -> Unit = { _, _ -> },
    /**
     * The acoustic label sink, as the composition installs it (Task 4).
     *
     * Same shape and same reason as [wakeEmit]: the pipeline is built before the chain that owns it, so the
     * per-frame signal reads this property through a holder rather than capturing a value. Assignable, so a JVM test
     * can call it exactly as the labeler would - and the production factory assigns it before a single frame can be
     * polled.
     */
    var acousticEmit: (AcousticLabel) -> Unit = {},
) : AutoCloseable {

    private val lock = Any()
    private var loop: AudioIngressLoop? = null

    /** Outcomes observed before the first loop existed; drained into it as it is built. */
    private val pendingFaults = ArrayDeque<String>()
    private val pendingOffers = ArrayDeque<PcmOffer>()

    /**
     * The host-facing input port; the host starts it for an epoch exactly as it starts any other input.
     *
     * `stop` reads the loop through the lock rather than resolving one, so stopping cannot create a loop that
     * was never started.
     */
    val input: InputPort = object : InputPort {
        override fun start(epoch: InputEpoch) { resolved()?.start(epoch) }
        override fun stop(epoch: InputEpoch) { synchronized(lock) { loop }?.stop(epoch) }
    }

    /**
     * Reports a fault of the running loop, or one observed before the ingress existed.
     *
     * A forwarder rather than a direct call into the loop, because `PcmIngress` is owned by the transport and
     * its faults are real whether or not this chain has a loop yet. The buffering decision is made inside the
     * same lock the loop is built under, so a fault racing the first `start` cannot be lost.
     */
    fun onFault(code: String) {
        resolved(fault = code)
    }

    /** Reports what the receiver did with one offered datagram; see [AudioIngressLoop.onOffer]. */
    fun onOffer(offer: PcmOffer) {
        offerSink(offer)
        resolved(offer = offer)
    }

    /**
     * The loop for the current ingress, built at most once, with any outcome that arrived before it drained
     * into it.
     *
     * The `offer`/`fault` arguments are handled here rather than by the callers so that "is there a loop" and
     * "buffer this" are one atomic decision: a caller that checked and then buffered could interleave with the
     * call that builds the loop and drains the buffer, losing the outcome for good. Buffering only ever happens
     * before the first ingress exists (the CXR connection is built lazily), and the next call - a later offer,
     * or `start` - drains it.
     */
    private fun resolved(offer: PcmOffer? = null, fault: String? = null): AudioIngressLoop? =
        synchronized(lock) {
            val current = loop ?: ingress()?.let { source ->
                AudioIngressLoop(source, framer, pipeline, wake?.detector, scheduler, clock,
                    onFault = faultSink).also { built ->
                    loop = built
                    while (pendingOffers.isNotEmpty()) built.onOffer(pendingOffers.removeFirst())
                    while (pendingFaults.isNotEmpty()) built.reportFault(pendingFaults.removeFirst())
                }
            }
            if (current == null) {
                offer?.let { pendingOffers += it }
                fault?.let { pendingFaults += it }
            } else {
                offer?.let(current::onOffer)
                fault?.let(current::reportFault)
            }
            current
        }

    override fun close() {
        runCatching { wake?.close() }
        runCatching { pipeline.close() }
    }

    companion object {
        /**
         * Wires one PCM stream to both consumers and one transcript stream to plugins.
         *
         * [wake] is nullable on purpose: with no keyword-spotting assets, or no VAD that can be built for the
         * detector, there is no detector and the ruled fallback is to leave it unwired (the composition reports
         * `WAKE_UNAVAILABLE`) rather than build a second ASR engine over the same ~200 MB model. The pipeline
         * runs either way.
         */
        fun assemble(
            ingress: () -> PcmIngress?,
            framer: PcmFramer,
            vad: VadEngine,
            asr: StreamingAsrEngine,
            speaker: SpeakerEngine,
            wake: WiredWakeDetector?,
            scheduler: DeadlineScheduler,
            clock: MonotonicClock,
            emit: (HostEvent) -> Unit,
            cancelTurn: (String) -> Unit = {},
            onFault: (String) -> Unit = {},
            onOffer: (PcmOffer) -> Unit = {},
            /**
             * The detector's emission sink, as the composition installs it; see [wakeEmit].
             *
             * Defaulted inert for the harnesses that build a chain without a live host, because a chain that cannot
             * deliver a wake must not pretend it did.
             */
            wakeEmit: (word: String, confidence: Double) -> Unit = { _, _ -> },
            /**
             * The acoustic label sink, as the composition installs it; see [CxrAudioChain.acousticEmit]. Defaulted
             * inert for the harnesses that build a chain without a live host.
             */
            acousticEmit: (AcousticLabel) -> Unit = {},
        ): CxrAudioChain {
            // The session needs the pipeline and the pipeline's emit needs the session: the holder breaks the cycle
            // so both the host's delivery and the plugin-facing provider see every revision, in order.
            val holder = arrayOfNulls<SherpaAsrSession>(1)
            // The labeler is derived from these same frames and reaches the chain's sink through a second holder, for
            // the same construction-order reason as the wake detector's emission.
            val chainHolder = arrayOfNulls<CxrAudioChain>(1)
            val labeler = AcousticLabeler()
            val pipeline = SpeechPipeline(vad, asr, speaker,
                emit = { event -> holder[0]?.onEvent(event); emit(event) }, cancelTurn = cancelTurn,
                onFrameSignal = { frame, speech ->
                    labeler.accept(frame, speech)?.let { label -> chainHolder[0]?.acousticEmit?.invoke(label) }
                })
            val session = SherpaAsrSession(pipeline)
            holder[0] = session
            return CxrAudioChain(ingress, framer, pipeline, wake, scheduler, clock, onFault, onOffer, session,
                wakeEmit, acousticEmit).also { chainHolder[0] = it }
        }
    }
}

/**
 * The engine-bundle import seam `provisionAudioChain` depends on, so a unit test can pin the branch selection
 * (attempted in debug, skipped in release, success activates, failure activates nothing) without a device.
 */
internal interface EngineBundleProvisioner {
    /** @throws ContractError.CapabilityMissing `ENGINE_MISSING` for any failed verification. */
    fun importBundle(root: File): String
}

/** The production provisioner over the App's own store. */
internal class StoreBundleProvisioner(private val store: EngineModelStore) : EngineBundleProvisioner {
    private val provisioning = EngineBundleProvisioning(store, EngineManifestLoader)
    override fun importBundle(root: File): String = provisioning.importBundle(root)
}

/**
 * The platform half of the provisioning: resolve the App's model store and manifest asset, then hand both to
 * [provisionAudioChainFrom].
 *
 * This is the only place the `Context` is touched, so a broken/absent context (a release build with no assets,
 * a test double) fails closed as "no chain" here rather than as an exception inside the composition - the same
 * result an absent bundle produces, with the class named in the log.
 */
private fun provisionAudioChain(
    context: Context,
    clock: MonotonicClock,
    scheduler: DeadlineScheduler,
    ingress: () -> PcmIngress?,
    emit: (HostEvent) -> Unit,
    cancelTurn: (String) -> Unit,
    allowDebugImport: Boolean,
    blobs: AndroidEncryptedBlobStore,
    onFault: (String) -> Unit,
    onOffer: (PcmOffer) -> Unit,
    wakeEmit: (word: String, confidence: Double) -> Unit,
    acousticEmit: (AcousticLabel) -> Unit,
    cooldownNanos: Long,
    suppressWhile: () -> Boolean,
): CxrAudioChain? = try {
    val application = requireNotNull(context.applicationContext) { "APPLICATION_CONTEXT_UNAVAILABLE" }
    val store = DebugImportDirectory.store(application)
    val manifest = application.assets.open(ENGINE_MANIFEST_ASSET).use(EngineManifestLoader::load)
    provisionAudioChainFrom(
        store = ModelStoreBundles(store),
        provisioner = StoreBundleProvisioner(store),
        importRoot = DebugImportDirectory.source(application),
        manifest = manifest,
        allowDebugImport = allowDebugImport,
        clock = clock,
        scheduler = scheduler,
        ingress = ingress,
        emit = emit,
        cancelTurn = cancelTurn,
        engines = AudioChainEngines(application.assets, clock, blobs, cooldownNanos),
        suppressWhile = suppressWhile,
        onFault = onFault,
        onOffer = onOffer,
        wakeEmit = wakeEmit,
        acousticEmit = acousticEmit,
    )
} catch (unavailable: Exception) {
    android.util.Log.i("EdithHost", "audio chain unavailable: ${unavailable.javaClass.name}: ${unavailable.message}")
    null
}

/**
 * Builds the audio chain from a validated engine bundle, or returns `null` when the delivery build cannot honestly
 * support one.
 *
 * Both preconditions are real facts about the device, not optimism:
 * - the uplink format must be the verified one for the shipping pairing (`PcmFormatEvidence`), otherwise a framer
 *   would be built on an assumed geometry and the ASR would return confident nonsense;
 * - a validated bundle must be active or recoverable in the App's model store, imported through
 *   `EngineBundleProvisioning` (or the P2/T17 pipeline) with every size and digest checked.
 *
 * **The debug-only import (Task 11b, Important 2).** With neither an active **nor a recoverable** bundle (Task 15c
 * D3 made `recover` the second check, so a healthy bundle is reused rather than re-imported), a debug build attempts
 * `EngineBundleProvisioning.importBundle` on [DebugImportDirectory.IMPORT_DIRECTORY]. That is what makes the
 * ratified conditional `Asr` advertisement reachable: a bundle pushed there for development is activated exactly
 * as one imported through the P2/T17 path, or nothing happens at all. A release build never reads that
 * directory, so an external bundle cannot become the product's audio engine.
 *
 * **The wake detector (Task 11b, Important 1).** When the KWS assets are packaged and a VAD-only engine can be
 * built from the same bundle, the detector is wired with its **own** VAD (the pipeline keeps the candidate's),
 * because `WakeWordDetector` resets its VAD on the speech→silence edge. Building the second VAD from the `vad`
 * role costs one vad model handle, not a second recognizer; the pipeline's ASR model is never loaded twice.
 * Either piece missing keeps today's behaviour: `WAKE_UNAVAILABLE`.
 *
 * **The self-audio gate (Task 14b).** [suppressWhile] is handed to the detector through
 * [AudioChainEngines.wakeDetector] rather than applied here, so the seam is the factory's - the same place the
 * detector is built - and the gate is evaluated per frame. P3.1's gate is open for the whole phase; see
 * `PlaybackGate`.
 *
 * Called on the composition thread, so a build that has an imported bundle pays the validation (SHA-256 over every
 * artifact) and native engine load here, at startup. That is a known cost of keeping the ears always on; if startup
 * latency becomes the constraint, the fix is to build the chain after the first connect and re-`start` the session,
 * which is a lifecycle change and not free.
 */
internal fun provisionAudioChainFrom(
    store: EngineBundleStore,
    provisioner: EngineBundleProvisioner,
    importRoot: File,
    manifest: EngineManifest,
    allowDebugImport: Boolean,
    clock: MonotonicClock,
    scheduler: DeadlineScheduler,
    ingress: () -> PcmIngress?,
    emit: (HostEvent) -> Unit,
    cancelTurn: (String) -> Unit,
    engines: AudioChainEngines = AudioChainEngines(),
    /**
     * The detector's self-audio guard (Task 14b): true while the App itself is producing audio, so its own playback
     * cannot be re-detected as a wake. Evaluated per frame by `WakeWordDetector`; see `PlaybackGate` for why it is
     * open (and inert) for the whole of P3.1.
     */
    suppressWhile: () -> Boolean = { false },
    onFault: (String) -> Unit = {},
    onOffer: (PcmOffer) -> Unit = {},
    /**
     * The detector's emission sink: the composition turns `(word, confidence)` into a `WakeDetected` tagged with the
     * host's current identity and hands it to `HostRuntime.onEvent`.
     *
     * Defaulted inert so the existing harnesses keep compiling and a chain built without a live host emits nothing
     * rather than throwing into the audio loop's tick.
     */
    wakeEmit: (word: String, confidence: Double) -> Unit = { _, _ -> },
    /**
     * The acoustic label sink the audio chain reports transitions to (Task 4); the composition turns it into an
     * `AcousticEvent` tagged with the host's current identity.
     */
    acousticEmit: (AcousticLabel) -> Unit = {},
): CxrAudioChain? = try {
    val verified = PcmFormatEvidence.resolve(CxrGlassesTarget.GLASSES_FIRMWARE, CXR_SDK_VERSION)
    if (verified == null) {
        android.util.Log.i("EdithHost", "FORMAT_UNVERIFIED: no verified uplink format, the ASR chain stays unwired")
        null
    } else {
        val bundle = activateBundle(store, provisioner, importRoot, allowDebugImport, manifest)
        if (bundle == null) {
            android.util.Log.i("EdithHost", "ENGINE_MISSING: no validated engine bundle, the ASR chain stays unwired")
            null
        } else {
            // The framing comes from the manifest the bundle was validated against: a bundle is imported or
            // recovered through this same manifest, so the two cannot describe different geometries.
            val frameMillis = manifest.frameMillis
            val candidate = engines.asrCandidate(bundle, verified)
            if (candidate == null) {
                android.util.Log.i("EdithHost", "ENGINE_MISSING: the ASR candidate could not be built from the " +
                    "validated bundle, the ASR chain stays unwired")
                null
            } else {
                try {
                    // The detector's emission sink cannot be attached after construction (`WakeWordDetector.emit` is
                    // a constructor value), so it reads the chain's sink through this holder. The chain is built
                    // immediately below, before any frame can be polled.
                    val chainHolder = arrayOfNulls<CxrAudioChain>(1)
                    // The detection sink is installed as an indirection through the chain's `wakeEmit`, so the
                    // detector and the chain are not circular: the chain exists first, and this lambda reads the sink
                    // the chain currently holds. Building the detector with a no-op and patching it afterwards is
                    // what the old wiring did, and `WakeWordDetector.emit` being a constructor value made it silent.
                    //
                    // A detector needs its own VAD (it resets it on the speech→silence edge), so it is built from the
                    // same bundle's vad role. That is the only extra engine the wake path loads: one more vad handle,
                    // never a second recognizer. When there is no KWS asset bundle the engine is closed again at once
                    // and the chain runs ASR-only, which is the ruled fallback.
                    val wakeVad = engines.vad(bundle)
                    val wake = wakeVad?.let { vad ->
                        val detector = engines.wakeDetector(bundle, vad,
                            emit = { word, confidence -> chainHolder[0]?.wakeEmit(word, confidence) },
                            suppressWhile = suppressWhile)
                        if (detector == null) runCatching { vad.close() }
                        detector?.let { WiredWakeDetector(it, vad) }
                    }
                    if (wake == null) {
                        android.util.Log.i("EdithHost", "WAKE_UNAVAILABLE: the keyword-spotting path is not " +
                            "wired (no packaged KWS assets, or no VAD/detector could be built from the bundle); " +
                            "the explicit manual trigger is the only arming path")
                    }
                    CxrAudioChain.assemble(ingress = ingress, framer = PcmFramer(verified, frameMillis),
                        vad = candidate.vad, asr = candidate.asr, speaker = candidate.speaker,
                        wake = wake, scheduler = scheduler, clock = clock, emit = emit, cancelTurn = cancelTurn,
                        onFault = onFault, onOffer = onOffer, wakeEmit = wakeEmit,
                        acousticEmit = acousticEmit).also { chainHolder[0] = it }
                } catch (unavailable: Throwable) {
                    runCatching { candidate.vad.close() }
                    runCatching { candidate.asr.close() }
                    runCatching { candidate.speaker.close() }
                    throw unavailable
                }
            }
        }
    }
} catch (unavailable: Exception) {
    // The class, not only the message: a wiring bug (a missing asset, a null) and an absent bundle otherwise
    // produce the same "audio chain unavailable" line, and only one of them is a device state.
    android.util.Log.i("EdithHost", "audio chain unavailable: ${unavailable.javaClass.name}: ${unavailable.message}")
    null
}

/**
 * The bundle-store seam [activateBundle] reads.
 *
 * A named seam rather than the concrete `EngineModelStore` so a JVM test can pin the reuse-before-import rule
 * (active, then recoverable, then import) with a fake store and a recording provisioner, without a device or a
 * ~230 MiB bundle on disk. The two calls are exactly the ones the composition makes, in the order it makes them.
 */
internal interface EngineBundleStore {
    /** The bundle this process already validated, or null. */
    fun active(): ValidatedModelBundle?

    /** The bundle a previous process activated, revalidated against [manifest], or null. */
    fun recover(manifest: EngineManifest): ValidatedModelBundle?
}

/** The production [EngineBundleStore] over the App's own model store. */
internal class ModelStoreBundles(private val store: EngineModelStore) : EngineBundleStore {
    override fun active(): ValidatedModelBundle? = store.active()
    override fun recover(manifest: EngineManifest): ValidatedModelBundle? = store.recover(manifest)
}

/**
 * The bundle the chain is built from, reusing a validated one before importing the debug directory's.
 *
 * `store.active()` first, because an already-validated bundle is authoritative; then `store.recover(manifest)`, which
 * revalidates a bundle a previous process wrote and whose in-memory handle this process does not have; **only then**
 * the debug import (and only in a debug build). A refusal anywhere is `ENGINE_MISSING` - never a silently weaker
 * bundle.
 *
 * **The order is the fix (Task 15c D3).** Importing before `recover` re-copied the whole bundle into a fresh
 * directory on every cold start and left the previous copy in place: the device evidence (2026-10-09) showed two
 * different `ENGINE_IMPORTED: ACTIVE:…` ids across two runs, ~235 MB each, plus the import directory. Checking the
 * recoverable bundle first makes a healthy bundle reused instead of re-imported; the import stays the last resort for
 * a store that genuinely has nothing, which is the only case the debug drop is for.
 */
internal fun activateBundle(
    store: EngineBundleStore,
    provisioner: EngineBundleProvisioner,
    importRoot: File,
    allowDebugImport: Boolean,
    manifest: EngineManifest,
): ValidatedModelBundle? {
    // The reuse paths are logged as well as the import: without a line for them, a device run cannot tell "the
    // healthy bundle was reused" from "no bundle was found at all" - both used to be silent (Task 15b, concern 2).
    store.active()?.let { bundle ->
        android.util.Log.i("EdithHost", "ENGINE_REUSED: ACTIVE:${bundle.directory}")
        return bundle
    }
    runCatching { store.recover(manifest) }.getOrNull()?.let { bundle ->
        android.util.Log.i("EdithHost", "ENGINE_REUSED: RECOVERED:${bundle.directory}")
        return bundle
    }
    if (!allowDebugImport) return null
    val imported = runCatching { provisioner.importBundle(importRoot) }
    android.util.Log.i("EdithHost", imported.fold(
        onSuccess = { "ENGINE_IMPORTED: $it" },
        onFailure = { "ENGINE_IMPORT_FAILED: ${it.javaClass.name}: ${it.message}" }))
    return imported.getOrNull()?.let { store.active() }
}
