package edith.phone

import android.content.Context
import edith.contracts.*
import edith.host.HostEnvironment
import edith.host.HostRuntime
import edith.model.*
import edith.output.TraceRecorder
import edith.plugin.*
import edith.plugins.smart.SmartAssistantPlugin
import edith.plugins.interview.InterviewAssistantPlugin
import edith.storage.*
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
    private val resources: List<AutoCloseable> = emptyList(),
    val trace: TraceRecorder = TraceRecorder(),
) : AutoCloseable {
    private val reasons = Capability.entries.filter { it !in (environment?.availableCapabilities ?: emptySet()) }
        .associateWith { when (it) {
            Capability.Audio -> "TRANSPORT_MISSING"
            Capability.Asr, Capability.Speaker -> "ENGINE_MISSING"
            Capability.Model -> "USER_VAULT_AND_CONSENT_MISSING"
            Capability.TextOutput -> "DISPLAY_MISSING"
            Capability.SpeechOutput -> "SPEECH_OUTPUT_MISSING"
            Capability.Storage -> "PERSISTENT_STORAGE_MISSING"
        } }
    private val mutableState = MutableStateFlow(SessionUiState(SessionState.Idle, null, reasons, null))
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    private val observation = host.observeState { session -> mutableState.update { it.copy(session = session) } }
    private var closed = false
    private var activatedPlugin: String? = null
    private data class StartGrant(val token: String, val pluginId: String, val expires: Long)
    private var pendingStart: StartGrant? = null
    private val clock = environment?.clock ?: object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }

    @Synchronized fun start(pluginId: String) {
        if (closed) return
        pendingStart = null
        mutableState.update { it.copy(selectedPluginId = pluginId, errorCode = null) }
        host.startSession(SessionId(UUID.randomUUID().toString()))
        activatedPlugin = null
        if (environment == null) { host.pause(); error("CAPABILITY_MISSING") }
    }

    @Synchronized fun onSceneReady() {
        if (closed || host.currentState != SessionState.BuildingScene) return
        host.markSceneReady()
        activateSelected()
    }

    private fun activateSelected() {
        val selected = state.value.selectedPluginId ?: return
        val result = if (activatedPlugin == null) host.activate(selected) else host.switchTo(selected)
        if (result == edith.host.ActivationResult.Activated) activatedPlugin = selected
        else { host.pause(); error(if (result == edith.host.ActivationResult.UnknownPlugin) "UNKNOWN_PLUGIN" else "CAPABILITY_MISSING") }
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
        observation.cancel()
        try { host.close() } finally {
            try { (model as? AutoCloseable)?.close() } finally {
                resources.asReversed().forEach { runCatching { it.close() } }
            }
        }
    }
    companion object {
        fun create(context: Context): HostCompositionRoot {
            // T15-T18 supply the user vault and verified transport/engines/display.
            val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
            val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "edith-phone-deadlines").apply { isDaemon = true } }
            val scheduler = object : DeadlineScheduler {
                override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                    val future = timer.schedule(task, (deadlineNanos - clock.nowNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
                }
            }
            val resolver = object : SecretResolver {
                override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit): Unit = throw ContractError.CapabilityMissing("USER_VAULT_MISSING")
                override fun revoke(handle: SecretHandle) = Unit
            }
            val profiles = MemoryProfileRepository(resolver::revoke)
            val http = OkHttpClient()
            val model = ControlledModelClient(http, profiles, resolver, CapabilityGate(), ContextBudgeter(), clock, scheduler,
                { RequestConsent(false, false, false, false) })
            val root = HostCompositionRoot(HostRuntime(1), profiles, model, null, listOf(AutoCloseable {
                timer.shutdownNow(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            }))
            root.host.register(SmartAssistantPlugin())
            root.host.register(InterviewAssistantPlugin())
            DiagnosticRegistration.register(root, clock, scheduler)
            return root
        }
    }
}
