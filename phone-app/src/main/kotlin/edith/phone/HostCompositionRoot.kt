package edith.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import edith.contracts.*
import edith.host.HostEnvironment
import edith.host.HostRuntime
import edith.model.*
import edith.output.TraceRecorder
import edith.plugin.*
import edith.plugins.smart.SmartAssistantPlugin
import edith.plugins.interview.InterviewAssistantPlugin
import edith.storage.*
import edith.phone.storage.*
import edith.phone.model.ProfileViewModel
import edith.phone.model.ProbeConsent
import edith.phone.model.ProfileProbeGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
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
     * Debug-only: submits the next fixed hint through the diagnostic probe plugin, or null when no probe is
     * registered (release builds). The UI offers a button only when this is non-null.
     */
    internal var diagnosticHintSender: (() -> Boolean)? = null

    init {
        // Activation runs when the scene becomes ready, which is before the first clock mapping exists, so a plugin
        // whose only missing capability is the display is refused at that moment. This is the retry: bounded to the
        // not-live -> live transition, and only when an activation was actually refused.
        hintLink?.onDisplayBecameLive = { onDisplayBecameLive() }
    }
    private val reasons = Capability.entries.filter { it !in (environment?.availableCapabilities ?: emptySet()) }
        .associateWith { when (it) {
            Capability.Audio -> "TRANSPORT_MISSING"
            Capability.Asr, Capability.Speaker -> "ENGINE_MISSING"
            Capability.Model -> "MODEL_OPERATION_UNAVAILABLE"
            Capability.TextOutput -> "DISPLAY_MISSING"
            Capability.SpeechOutput -> "SPEECH_OUTPUT_MISSING"
            Capability.Storage -> "HOST_STORAGE_PROVIDER_MISSING"
        } }
    private val mutableState = MutableStateFlow(SessionUiState(SessionState.Idle, null, reasons, null))
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    private val observation = host.observeState { session -> mutableState.update { it.copy(session = session) } }
    private var closed = false
    private var activatedPlugin: String? = null
    private var activeConnection: CxrConnection? = null
    private data class StartGrant(val token: String, val pluginId: String, val expires: Long)
    private var pendingStart: StartGrant? = null
    private val clock = environment?.clock ?: object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
    fun profileViewModel(): ProfileViewModel? {
        val repository = profiles as? AndroidProfileRepository ?: return null
        return ProfileViewModel(repository, profileTester ?: return null, profileConsent, ::applyProfileSelection)
    }
    fun namespace(pluginId: String): StorageHandle {
        require(pluginId in setOf("smart-assistant", "interview-assistant") || (BuildConfig.DEBUG && pluginId == "p2-diagnostic"))
        return namespaceFactory?.invoke(pluginId) ?: throw ContractError.CapabilityMissing("Persistent storage unavailable")
    }
    @Synchronized private fun applyProfileSelection(pluginId: String, profile: ModelProfile?) {
        if (closed) return
        if (activatedPlugin == pluginId) { host.pause(); activatedPlugin = null }
        val values = if (pluginId == "p2-diagnostic") mapOf("profileId" to (profile?.id ?: "__unselected__"),
            "profileRevision" to (profile?.revision ?: 0).toString(), "requestConsent" to "false", "inputMode" to "transcript")
        else profile?.let { mapOf("profileId" to it.id, "profileRevision" to it.revision.toString()) } ?: emptyMap()
        check(host.configure(pluginId, PluginConfiguration(1, values))) { "Profile selection unavailable" }
    }

    @Synchronized fun start(pluginId: String) {
        if (closed) return
        pendingStart = null
        mutableState.update { it.copy(selectedPluginId = pluginId, errorCode = null) }
        val session = SessionId(UUID.randomUUID().toString())
        host.startSession(session)
        activeSessionId = session.value
        activatedPlugin = null
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
        // Also reported, because this call is made inside a `runCatching` at every call site: an exception here
        // otherwise disappears and the App shows a stale "capability missing" that has nothing to do with the cause.
        try {
            (profiles as? AndroidProfileRepository)?.let { repository ->
                val profile = repository.selections()[selected]?.let(repository::get)
                applyProfileSelection(selected, profile)
            }
        } catch (thrown: Throwable) {
            android.util.Log.i("EdithHost", "profile selection failed for $selected: ${thrown.message}")
            throw thrown
        }
        val result = if (activatedPlugin == null) host.activate(selected) else host.switchTo(selected)
        if (result == edith.host.ActivationResult.Activated) {
            activatedPlugin = selected
            awaitingDisplayForActivation = false
            // Clearing the earlier refusal is part of succeeding: a retry that worked must not leave the App showing
            // the error it was retried from.
            mutableState.update { it.copy(errorCode = null) }
        } else {
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
     * Retries an activation that was refused for missing capabilities, once the display has become real.
     *
     * Deliberately narrow: only the not-live -> live transition calls it, only when an activation was actually
     * refused, and never after the user paused or stopped (those clear the flag through a later activation attempt
     * or `stop()`). Without it a display that has just become usable would never be used, because the host attempts
     * activation before the first clock mapping exists.
     */
    @Synchronized private fun onDisplayBecameLive() {
        android.util.Log.i("EdithHost", "display-live retry: armed=$awaitingDisplayForActivation " +
            "state=${host.currentState} activated=$activatedPlugin available=${environment?.availableCapabilities}")
        if (closed || !awaitingDisplayForActivation || activatedPlugin != null) return
        if (host.currentState != SessionState.Paused) return
        runCatching { activateSelected() }
    }

    @Synchronized fun pause() { if (!closed) { pendingStart = null; host.pause() } }
    @Synchronized fun resume() {
        if (closed || host.currentState != SessionState.Paused) return
        if (environment == null) { error("CAPABILITY_MISSING"); return }
        mutableState.update { it.copy(errorCode = null) }
        activateSelected()
    }
    @Synchronized fun stop() {
        if (closed) return
        pendingStart = null
        host.stop()
        activatedPlugin = null
        awaitingDisplayForActivation = false
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
            val http = OkHttpClient()
            val model = ControlledModelClient(http, profiles, resolver, CapabilityGate(), ContextBudgeter(), clock, scheduler,
                probeGate::requestConsent)
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
            // The environment advertises exactly the capabilities it can answer: the composition's own gated model
            // client, the per-plugin namespace, and the display once a clock mapping makes it real. Audio, ASR,
            // speaker and speech output stay unwired, so they stay unadvertised and the host still refuses plugins
            // that need them (`interview-assistant` needs Speaker, so it still refuses - deliberately).
            val environment = CxrHostEnvironment(clock = clock, input = UnwiredInputPort,
                liveOutput = hintLink::liveOutput, scheduler = scheduler,
                providers = mapOf(Capability.Model to model),
                storageFor = namespaceFactory,
                taskScope = ExecutorTaskScope(pluginTasks))
            val host = HostRuntime(1, environment)
            // The observer is shared through a holder rather than captured by value, because the bring-up harness arms
            // it (via installRawPcmObserver) AFTER this composition is built but BEFORE the lazy connection is created.
            // Capturing the value here - or letting `create`'s parameter name shadow the field, as it did - hands the
            // transport a permanent no-op, so every capture reads zero bytes however much audio actually arrives.
            val observerHolder = arrayOf(initialObserver)
            val scene = SceneReadyResolver()
            val root = HostCompositionRoot(host, profiles, model, environment, listOf(AutoCloseable {
                timer.shutdownNow(); pluginTasks.shutdownNow()
                http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            }), profileTester = tester, profileConsent = probeGate::grant,
                namespaceFactory = namespaceFactory, speakerProfiles = AndroidSpeakerProfileStore(blobs),
                // One CXR connection per composition, built on first use: the transport stays the only
                // SDK access path and the App carries the exact recorded pairing. Readiness still arrives
                // from the SDK, so a wired-but-not-ready connection keeps the audio capability missing.
                rokidConnectionFactory = { activity ->
                    // Read through the holder, so an observer armed after this composition was built is still the one
                    // the transport calls.
                    RokidConnection(activity.asCxrActivity(), CxrGlassesTarget.config(), host, scene::onSceneReady,
                        clock, { data -> observerHolder[0](data) }).also { connection[0] = it }
                },
                authorizeResult = { resultCode, data -> connection[0]?.onAuthorizationResult(resultCode, data) },
                hintLink = hintLink)
            // Keep the instance field and the holder pointing at the same observer, so installRawPcmObserver affects a
            // connection that has not been built yet.
            root.observerHolder = observerHolder
            root.armObserver(initialObserver)
            scene.composition = root
            root.host.register(SmartAssistantPlugin())
            root.host.register(InterviewAssistantPlugin())
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
    }
}
