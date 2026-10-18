package com.sheen.rokid.transport

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.session.*
import com.rokid.sprite.aiapp.externalapp.example.ExternalAppClient
import com.sheen.rokid.plugin.CancellationHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class RokidConnectionConfig(val glassesPackageName: String, val glassesActivityName: String,
    val glassesApkPath: String?, val firmware: String, val requestMedia: Boolean = false) {
    init {
        require(glassesPackageName.isNotBlank() && glassesActivityName.isNotBlank() && firmware.isNotBlank())
        require(glassesApkPath == null || glassesApkPath.isNotBlank())
    }
}

internal class CxrBackend(private val config: RokidConnectionConfig, private val worker: Executor,
    managerProvider: () -> CxrSessionManager,
    private val uiDispatch: (Activity, () -> Unit) -> Unit,
    private val sendSdk: (CxrSession, String, String) -> Boolean,
    /**
     * Resolves where an audio subscription is installed for a freshly created session.
     *
     * Device evidence is why this is not simply `session.addAudioCallback`: on the same glasses at the same instant the
     * vendor's own implementation, which installs on the client via `setCXRAudioCbk`, received 652,160 bytes of
     * microphone audio while our session-level registration received none. Injectable so the wiring is unit-testable.
     */
    private val audioInstaller: (CxrSession) -> AudioInstaller = { it.audioInstallerOrNull() ?: UnavailableAudioInstaller },
    /**
     * Where the **inbound** custom-command callback is installed.
     *
     * Injectable for the same reason as [audioInstaller], and defaulting to the client because that is the layer the
     * vendor's own reference uses: registering on the session wrapper delivered nothing on device, exactly as the
     * session-level audio registration had.
     */
    private val commandInstaller: (CxrSession) -> CustomCmdInstaller = { it.customCmdInstallerOrNull() ?: UnavailableCustomCmdInstaller },
    /**
     * Resolves where the glasses scene can be started.
     *
     * Injectable for the same reason as [audioInstaller] and [commandInstaller], plus one of its own: the vendor
     * `ExternalAppClient` is a concrete AAR class that loads a native library, so a JVM test cannot construct it.
     * `null` is an honest "no client is reachable", not a failed start.
     */
    private val glassAppStarter: (CxrSession) -> GlassAppStarter? = { it.glassAppStarterOrNull() },
) : RokidBackend {
    constructor(context: Context, config: RokidConnectionConfig, worker: Executor) : this(config, worker,
        { CxrSessionManager.getInstance(context) }, { activity, action -> activity.runOnUiThread(action) },
        { current, command, json -> sendLikeTheOfficialSample(current, command, json) })
    constructor(config: RokidConnectionConfig, worker: Executor, manager: CxrSessionManager,
        ui: (Activity, () -> Unit) -> Unit, sdkSend: (CxrSession, String, String) -> Boolean) :
        this(config, worker, { manager }, ui, sdkSend)

    private val manager by lazy(managerProvider)
    private val fence = BackendLifecycleFence()
    private val request = AtomicLong()
    private val pendingGate = Any()
    private val nextCommand = AtomicLong()
    /** Channel keys whose payloads are raw bytes, never text. Guarded by [binaryGate]. */
    private val binaryGate = Any()
    private val binaryChannels = mutableMapOf<String, MutableList<(ByteArray) -> Unit>>()
    @Volatile private var binding: Binding? = null
    @Volatile private var authCallback: ((AuthResult) -> Unit)? = null
    /** The pre-connect scene listener factory; see [setGlassesEventListenerFactory]. Guarded by [listenerGate]. */
    private val listenerGate = Any()
    @Volatile private var glassesEventListenerFactory: (() -> GlassesEvents?)? = null

    private class Pending(val route: RokidCommandRoute, val correlation: RokidReplyCorrelation?,
        val listener: (String?) -> Unit) {
        val alive = AtomicBoolean(true)
    }
    private class Binding(val session: CxrSession) {
        val audio = mutableMapOf<RokidBackend.Audio, AudioInstaller>()
        val pending = linkedMapOf<Long, Pending>()
        val usedReplyChannels = mutableSetOf<String>()
        /** Installed glasses-scene listeners; removed on release so a dead session cannot keep notifying the host. */
        val glassesEventListeners = CopyOnWriteArrayList<CxrGlassesEventListener>()
        /**
         * True once the SDK reported `onSessionClosed` for this session.
         *
         * Liveness has to end here, not only when a reconnect replaces [binding]: the SDK can deliver a late screen,
         * wearing or scene callback from a closed-but-not-yet-replaced session, and it must not speak for the host.
         */
        val closed = AtomicBoolean(false)
        lateinit var lifecycle: ISessionLifecycleCbk
        lateinit var commands: ICustomCmdSessionCallback
        lateinit var installer: AudioInstaller
        lateinit var commandSubscription: CustomCmdInstaller
    }

    override fun connect(activity: Activity, events: RokidBackend.Events) {
        val identity = fence.dispatch { request.incrementAndGet() } ?: return
        val accepted = AtomicBoolean()
        val callback: (AuthResult) -> Unit = callback@{ result ->
            val live = fence.dispatch {
                if (request.get() != identity || !accepted.compareAndSet(false, true)) false
                else { authCallback = null; true }
            } ?: false
            if (!live) return@callback
            val token = result.token
            if (!result.isSuccess || token.isNullOrBlank()) events.error("AUTHORIZATION_FAILED")
            else enqueue {
                if (fence.dispatch { request.get() == identity } != true) return@enqueue
                // Creation may block; cancellation can win before the final connect fence.
                val created = manager.create(SessionConfig(sessionType = SessionType.CUSTOM_APP,
                    glassesPackageName = config.glassesPackageName,
                    glassesActivityName = config.glassesActivityName, glassesApkPath = config.glassesApkPath))
                var installed = false
                var released = false
                fence.dispatch {
                    if (request.get() == identity) {
                        val previous = binding
                        val owned = callbacks(created, events)
                        binding = owned
                        // Task 11: a reconnect gets a fresh pre-connect registration (inside `callbacks`); the replaced
                        // session's SDK handle is released rather than left registered on a dead binding.
                        previous?.let(::releaseGlassesEventListeners)
                        created.addLifecycleCallback(owned.lifecycle)
                        // The inbound custom-command registration is chosen inside `callbacks`: the client callback is
                        // preferred and the session registration is only a fallback, so it is not added here.
                        if (!fence.isClosed() && request.get() == identity) {
                            installed = true
                            created.connect(token)
                        } else { binding = null; release(owned); released = true }
                    }
                }
                if (!installed && !released) created.close()
            }
        }
        fence.dispatch { if (request.get() == identity) authCallback = callback }
        uiDispatch(activity) {
            fence.dispatch {
                if (request.get() == identity) manager.requestAuthorization(activity,
                    authorizationPermissions(config.requestMedia), callback)
            }
        }
    }
    private fun callbacks(created: CxrSession, events: RokidBackend.Events): Binding {
        val owned = Binding(created)
        // Resolved once per connection: the installer is bound to this session's client, so it must not be rebuilt
        // per epoch or a second epoch could install onto a stale client.
        owned.installer = audioInstaller(created)
        fun live() = !fence.isClosed() && binding === owned
        owned.lifecycle = object : ISessionLifecycleCbk {
            override fun onSessionStarted() { if (live()) events.ready() }
            override fun onSessionResumed() { if (live()) events.ready() }
            override fun onSessionPaused(reason: PausedReason) { if (live()) events.interrupted() }
            override fun onSessionTerminating(reason: TerminatingReason, gracePeriodMs: Long) { if (live()) events.interrupted() }
            override fun onSessionClosed(reason: CloseReason) {
                // The session is gone even while this binding is still the current reference. Mark it before the
                // interruption so every listener attached to it stops forwarding at the same moment.
                owned.closed.set(true)
                if (live()) events.interrupted()
            }
            override fun onConnectResult(success: Boolean, errorCode: SessionErrorCode?) {
                if (!success && live()) events.error("CXR_CONNECT_FAILED")
            }
        }
        /**
         * The single inbound routing path, shared by both registration layers.
         *
         * One function on purpose: whichever layer actually delivers on this firmware, the routing rules (binary
         * channels first, then reply correlation) are applied identically and cannot drift apart.
         */
        fun handleInbound(channelKey: String?, data: ByteArray?) {
            if (!live()) return
            if (data == null || channelKey == null) return
            // Binary channels are handled before any text work. Decoding audio as UTF-8 would be lossy - arbitrary
            // bytes are not valid UTF-8 - so a PCM frame would reach the other end as replacement characters.
            val binary = synchronized(binaryGate) { binaryChannels[channelKey]?.toList() }
            if (binary != null) {
                // A consumer failure must not escape into the vendor's callback thread.
                binary.forEach { consumer -> runCatching { consumer(data) } }
                return
            }
            val response = data.toString(Charsets.UTF_8)
            enqueue {
                if (!live()) return@enqueue
                val matched = synchronized(pendingGate) {
                    val candidates = owned.pending.filterValues { pending ->
                        pending.alive.get() && pending.route.replyChannel == channelKey &&
                            (pending.correlation == null ||
                                runCatching { pending.correlation.matches(response) }.getOrDefault(false))
                    }
                    if (candidates.size != 1) null else candidates.entries.single().let { entry ->
                        owned.pending.remove(entry.key)
                        entry.value
                    }
                }
                if (matched != null && matched.alive.compareAndSet(true, false)) matched.listener(response)
            }
        }
        owned.commands = object : ICustomCmdSessionCallback {
            override fun onCustomCmdResult(channelKey: String, data: ByteArray?) = handleInbound(channelKey, data)
        }
        // Installed on the CLIENT, like the audio callback and for the same measured reason: the session-level
        // registration delivered nothing on device while the client-level one is what the vendor's own reference uses.
        // The session callback stays registered only when the client could not be reached, so a message is never
        // processed twice and a firmware where the session layer does work still functions.
        owned.commandSubscription = commandInstaller(created)
        val installedOnClient = owned.commandSubscription.install { key, payload -> handleInbound(key, payload) }
        if (installedOnClient) {
            Log.i(TAG, "inbound custom commands are served by the client callback")
        } else {
            created.addCustomCmdCallback(owned.commands)
            Log.w(TAG, "client custom-command callback unavailable; fell back to the session registration")
        }
        // Official CXR-L 1.1.2 order: every callback, `addGlassesEventListener` included, is registered on the created
        // session before `connect`. Installing here - not on the first `ready()` - is what keeps the listener from
        // double-registering on started+resumed and from missing first-moment scene events.
        attachPreConnectGlassesListener(owned)
        return owned
    }

    /**
     * Installs the factory's listener on a newly created session, before the caller runs `connect`.
     *
     * A factory (rather than a listener) so one registration can be minted per session: the adapter closes over the
     * binding it belongs to, which is what makes a released/replaced session inert. A throwing factory is contained -
     * a scene observer is optional and must not abort the connection.
     */
    private fun attachPreConnectGlassesListener(owned: Binding) {
        val factory = synchronized(listenerGate) { glassesEventListenerFactory } ?: return
        val listener = runCatching { factory() }.getOrElse { thrown ->
            Log.e(TAG, "glasses-event factory failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
            null
        } ?: return
        installGlassesListener(owned, listener)
    }

    /** One adapter per listener per binding, with liveness owned by that binding. */
    private fun installGlassesListener(owned: Binding, listener: GlassesEvents) {
        val adapter = CxrGlassesEventListener(listener) { bindingLive(owned) }
        try {
            owned.session.addGlassesEventListener(adapter)
            owned.glassesEventListeners += adapter
        } catch (thrown: Throwable) {
            Log.e(TAG, "addGlassesEventListener failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
        }
    }

    /**
     * Whether [owned] may still speak for the host: the backend is open, the binding was not replaced, and the SDK has
     * not reported the session closed.
     */
    private fun bindingLive(owned: Binding): Boolean =
        !fence.isClosed() && binding === owned && !owned.closed.get()

    /** Detaches and removes every listener still registered on [owned]; idempotent. */
    private fun releaseGlassesEventListeners(owned: Binding) {
        owned.glassesEventListeners.forEach { listener ->
            listener.detach()
            runCatching { owned.session.removeGlassesEventListener(listener) }
        }
        owned.glassesEventListeners.clear()
    }
    /**
     * Delivers an activity result to the pending authorization request and reports the verdict the SDK's own parse of
     * it produced.
     *
     * The AI-App prompt is started with `requestAuthorization(activity, ...)`, so its answer arrives at the activity -
     * but so does every other result that activity starts for, including the App's own permission dialogs. The only
     * proof that a result belongs to the prompt is the callback `connect` armed for it (armed as the connection starts
     * asking, cleared the moment it is answered); guessing from a request code would be inventing a fact about the
     * vendor's SDK. `null` is therefore the answer for a result this backend did not ask for.
     *
     * The verdict comes from the SDK's parser (`parseAuthorizationResult` -> `AuthResult`), never from the bare result
     * code (review Minor 6). That parser reports a **failure** for a `RESULT_OK` result whose `auth_result` extra it
     * does not recognise or whose token is missing/blank, so reading the code would let the page announce a
     * confirmation the SDK refused. The token-testing rule is the same one the authorization callback above applies
     * before it creates a session, so the page and the session path can never disagree about what "confirmed" means.
     */
    override fun onAuthorizationResult(resultCode: Int, data: Intent?): CxrAuthorizationOutcome? = fence.dispatch {
        val pending = authCallback ?: return@dispatch null
        // Parsed once: the vendor's parser is the single reader of the intent, and the callback below must receive the
        // very answer the verdict was derived from.
        val parsed = manager.parseAuthorizationResult(resultCode, data)
        val verdict = verdictOf(resultCode, parsed)
        // The one line that makes the verdict checkable on a device: "the page says CONFIRMED" and "the SDK parsed a
        // success with a usable token" are then separable facts in the log rather than one inference from a code, which
        // is what review Minor 6 was about. Never the token itself - only whether one was usable.
        Log.i(TAG, "authorization result: resultCode=$resultCode parsedSuccess=${parsed.isSuccess} " +
            "tokenUsable=${!parsed.token.isNullOrBlank()} verdict=$verdict")
        pending(parsed)
        verdict
    }

    private fun verdictOf(resultCode: Int, parsed: AuthResult): CxrAuthorizationOutcome = when {
        // The user declining a prompt is a legitimate answer rather than an anomaly, and it is the platform's fact
        // rather than something read out of the intent.
        resultCode == Activity.RESULT_CANCELED -> CxrAuthorizationOutcome.CANCELLED
        !parsed.isSuccess || parsed.token.isNullOrBlank() -> CxrAuthorizationOutcome.UNEXPECTED
        else -> CxrAuthorizationOutcome.CONFIRMED
    }
    override fun cancelAuthorization() {
        fence.close()
        request.incrementAndGet()
        authCallback = null
    }
    override fun setBinaryChannelListener(channelKey: String, listener: (ByteArray) -> Unit) {
        require(channelKey.isNotBlank()) { "BINARY_CHANNEL_KEY_REQUIRED" }
        synchronized(binaryGate) {
            binaryChannels.getOrPut(channelKey) { mutableListOf() }.add(listener)
        }
    }

    override fun removeBinaryChannelListener(channelKey: String) {
        synchronized(binaryGate) { binaryChannels.remove(channelKey) }
    }

    override fun start(audio: RokidBackend.Audio): Boolean {
        val owned = binding ?: return false
        if (fence.isClosed()) return false
        // Installed on the CLIENT, not the session wrapper: the vendor's own working implementation uses
        // `setCXRAudioCbk` on the client, and the session-level registration measured zero bytes on device.
        val installer = owned.installer
        val events = object : CxrAudioAdapter.Events {
            private fun live() = !fence.isClosed() && binding === owned
            override fun received(data: ByteArray) { if (data.isNotEmpty() && live()) audio.received(data) }
            override fun streaming(active: Boolean) { if (live()) audio.streaming(active) }
            override fun failed() { if (live()) audio.failed() }
        }
        if (!installer.install(events)) return false
        owned.audio[audio] = installer
        return owned.session.startAudioStream().isSuccess
    }
    override fun stop(audio: RokidBackend.Audio) {
        val owned = binding ?: return
        val installer = owned.audio.remove(audio) ?: return
        try { owned.session.stopAudioStream() } finally { installer.release() }
    }
    override fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
        listener: (String?) -> Unit): CancellationHandle {
        val owned = binding
        val id = nextCommand.incrementAndGet()
        val pending = Pending(route, correlation, listener)
        val registered = synchronized(pendingGate) {
            if (owned == null || fence.isClosed() || binding !== owned ||
                correlation == null && route.replyChannel in owned.usedReplyChannels ||
                owned.pending.values.any { it.route.replyChannel == route.replyChannel && it.correlation == null }) false
            else {
                owned.usedReplyChannels.add(route.replyChannel)
                owned.pending[id] = pending
                true
            }
        }
        if (!registered) { pending.alive.set(false); listener(null) }
        else if (!sendSdk(owned!!.session, route.outbound, json)) {
            synchronized(pendingGate) { owned.pending.remove(id) }
            if (pending.alive.compareAndSet(true, false)) listener(null)
        }
        return object : CancellationHandle {
            override fun cancel() {
                pending.alive.set(false)
                synchronized(pendingGate) { owned?.pending?.remove(id) }
            }
        }
    }
    /**
     * Fire-and-forget send: the SDK call only, with no pending entry.
     *
     * Registering nothing is the point. A pending entry with no correlation would claim the reply channel for the
     * binding's lifetime, so the second hint would be refused, and a never-matching correlation would accumulate one
     * entry per hint because nothing ever removes it.
     */
    override fun sendCommand(route: RokidCommandRoute, json: String): Boolean {
        require(json.isNotBlank()) { "COMMAND_PAYLOAD_REQUIRED" }
        val owned = binding ?: return false
        if (fence.isClosed() || binding !== owned) return false
        return sendSdk(owned.session, route.outbound, json)
    }

    /**
     * Starts the glasses scene through the vendor client, exactly as the official phone sample does:
     * `ExternalAppClient.appStart(activityName, IGlassAppCbk)` (javap of the pinned `client-l-1.1.2.aar`).
     *
     * Three facts are kept separate rather than collapsed into one boolean:
     * - no live binding/client to ask: `false`, and the callback is **not** invented;
     * - the SDK call was accepted: `true`; the scene's own answer arrives later as `onOpenAppResult`;
     * - the SDK call itself threw: `false`; no `onOpenAppResult` exists to report.
     *
     * The activity name is the one this session was configured with, so an `edith.cxr.glassesActivity` override cannot
     * silently start a different screen from the session the phone established.
     */
    override fun openGlassesApp(onResult: (Boolean) -> Unit): Boolean {
        val owned = binding ?: return false
        if (fence.isClosed() || binding !== owned || owned.closed.get()) return false
        val starter = runCatching { glassAppStarter(owned.session) }.getOrNull() ?: return false
        // The SDK may answer synchronously on a fake transport or later on its own thread; the host sees one answer,
        // on this backend's worker, and only while the session that asked is still live. `owned.closed` is checked at
        // delivery too: the session can close between the SDK answer and the marshalled callback, and a dead session's
        // answer must not touch the next session's handshake state.
        val reported = AtomicBoolean()
        fun report(success: Boolean) {
            if (!reported.compareAndSet(false, true)) return
            enqueue {
                if (!fence.isClosed() && binding === owned && !owned.closed.get()) onResult(success)
            }
        }
        return try {
            starter.start(config.glassesActivityName) { success -> report(success) }
        } catch (thrown: Throwable) {
            Log.e(TAG, "appStart(${config.glassesActivityName}) failed: " +
                "${thrown.javaClass.simpleName}: ${thrown.message}")
            false
        }
    }

    /**
     * Registers the five glasses-scene events on the **session**, matching the official 1.1.2 sample
     * (`created.addGlassesEventListener(this)`; `javap` shows `addGlassesEventListener` on `CxrSession`).
     *
     * `null` means no live session could be observed. The handle removes the registration exactly once; backend release
     * removes anything still installed, so a closed session cannot keep calling into the host.
     */
    override fun addGlassesEventListener(listener: GlassesEvents): AutoCloseable? {
        val owned = binding ?: return null
        if (fence.isClosed() || binding !== owned || owned.closed.get()) return null
        val adapter = CxrGlassesEventListener(listener) { bindingLive(owned) }
        return try {
            owned.session.addGlassesEventListener(adapter)
            owned.glassesEventListeners += adapter
            AutoCloseable {
                adapter.detach()
                owned.glassesEventListeners.remove(adapter)
                runCatching { owned.session.removeGlassesEventListener(adapter) }
            }
        } catch (thrown: Throwable) {
            Log.e(TAG, "addGlassesEventListener failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
            null
        }
    }

    /**
     * Installs the factory for the session-level scene listener (Task 11).
     *
     * The official sample exercises this before `connect`, so the factory is called inside the connection's callback
     * installation rather than from the caller. It is invoked once per new session; replacing the binding also releases
     * the previous session's SDK registration.
     */
    override fun setGlassesEventListenerFactory(factory: (() -> GlassesEvents?)?) {
        synchronized(listenerGate) { glassesEventListenerFactory = factory }
    }

    override fun close() {
        cancelAuthorization()
        val owned = binding
        binding = null
        if (owned != null) release(owned)
    }    private fun release(owned: Binding) {
        try {
            // Releases the client-installed subscriptions; the session callback list is no longer used for audio.
            owned.audio.values.toList().forEach { runCatching { it.release() } }
            runCatching { owned.commandSubscription.release() }
            // 0.24: a released session must not keep calling scene events into the host. Detach first so an in-flight
            // vendor callback is inert even if the SDK's own removal races it.
            releaseGlassesEventListeners(owned)
            owned.session.removeLifecycleCallback(owned.lifecycle)
            owned.session.removeCustomCmdCallback(owned.commands)
        } finally {
            owned.session.close()
            owned.audio.clear()
            synchronized(pendingGate) {
                owned.pending.values.forEach { it.alive.set(false) }
                owned.pending.clear()
            }
        }
    }
    private fun enqueue(action: () -> Unit) {
        if (fence.isClosed()) return
        try { worker.execute { if (!fence.isClosed()) action() } } catch (_: RejectedExecutionException) {
            if (!fence.isClosed()) throw IllegalStateException("CXR_WORKER_UNAVAILABLE")
        }
    }
    companion object {
        /** Same tag family as the custom-command subscription, so one grep shows which layer served the traffic. */
        private const val TAG = "EdithCxrCommand"

        /** The reply channel key the official sample puts in the first `Caps` string. */
        const val REPLY_CHANNEL_KEY = "rk_custom_key"

        /**
         * Sends exactly the way the official phone sample does.
         *
         * `cxrLink.sendCustomCmd("rk_custom_client", Caps { write("rk_custom_key"); write(payload) })` - the **client**
         * (not the session wrapper) and the reply channel key as the first string, with the payload second. Following
         * the sample here is deliberate: the same layer mistake on the inbound side is what produced the earlier
         * "no reply arrives" measurement, and this keeps both directions on the API the vendor's own code exercises.
         */
        private fun sendLikeTheOfficialSample(session: CxrSession, command: String, json: String): Boolean {
            val client = session.glassesClientOrNull() ?: run {
                Log.w(TAG, "no glasses client reachable; cannot send $command")
                return false
            }
            return try {
                val caps = Caps().apply {
                    write(REPLY_CHANNEL_KEY)
                    write(json)
                }
                // The sample's return code: 0 is the accepted value, and null means no result was produced.
                client.sendCustomCmd(command, caps)?.toInt() == 0
            } catch (thrown: Throwable) {
                Log.e(TAG, "sendCustomCmd($command) failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
                false
            }
        }

        internal fun authorizationPermissions(media: Boolean): List<GlassPermission> =
            if (media) listOf(GlassPermission.MICROPHONE, GlassPermission.MEDIA) else listOf(GlassPermission.MICROPHONE)
    }
}

/**
 * Starts the glasses scene for one live session.
 *
 * A seam, not a wrapper: the production implementation makes exactly one vendor call
 * (`ExternalAppClient.appStart(activityName, IGlassAppCbk)`), and tests record the activity name and drive the
 * callback without constructing the vendor client.
 */
interface GlassAppStarter {
    /** Returns whether the SDK accepted the request; [onResult] carries the scene's own answer when it arrives. */
    fun start(activityName: String, onResult: (Boolean) -> Unit): Boolean
}

/**
 * The official `appStart` call, verified with `javap` on the pinned `client-l-1.1.2.aar`:
 * `ExternalAppClient.appStart(String, IGlassAppCbk) -> void`, with the result in `IGlassAppCbk.onOpenAppResult`.
 *
 * `IGlassAppCbk` has six abstract callbacks. Only `onOpenAppResult` answers this request; the install/uninstall/stop/
 * query results cannot arrive from an app start, and `onGlassAppResume` is a separate scene-lifecycle event that the
 * session-level `IGlassesEventListener` reports. They are implemented as explicit no-ops rather than omitted because
 * the interface has no defaults.
 */
internal class ExternalAppClientGlassAppStarter(private val client: ExternalAppClient) : GlassAppStarter {
    override fun start(activityName: String, onResult: (Boolean) -> Unit): Boolean = try {
        client.appStart(activityName, object : IGlassAppCbk {
            override fun onInstallAppResult(success: Boolean) = Unit
            override fun onUnInstallAppResult(success: Boolean) = Unit
            override fun onOpenAppResult(success: Boolean) { onResult(success) }
            override fun onStopAppResult(success: Boolean) = Unit
            override fun onGlassAppResume(resumed: Boolean) = Unit
            override fun onQueryAppResult(installed: Boolean) = Unit
        })
        true
    } catch (thrown: Throwable) {
        Log.e(APP_START_TAG, "appStart($activityName) failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
        false
    }
}

/** Resolves the app-start seam from the client the official sample starts scenes on; `null` when none is reachable. */
internal fun CxrSession.glassAppStarterOrNull(): GlassAppStarter? =
    glassesClientOrNull()?.let(::ExternalAppClientGlassAppStarter)

/**
 * Translates the vendor's nine-callback `IGlassesEventListener` into EDITH's five [GlassesEvents].
 *
 * `javap` shows every method has a `DefaultImpls` default, so the four EDITH ignores are left to the SDK. The adapter
 * detaches before the SDK registration is removed, which makes an in-flight vendor callback inert; and it contains a
 * host failure rather than letting it escape into the vendor's callback thread.
 */
internal class CxrGlassesEventListener(
    private val events: GlassesEvents,
    /** Whether the binding that installed this adapter is still the session the host is using. */
    private val live: () -> Boolean,
) : IGlassesEventListener {
    @Volatile private var active = true

    /** Makes every later vendor callback a no-op. Idempotent. */
    fun detach() { active = false }

    private inline fun emit(action: () -> Unit) {
        // `active` covers the owner's handle or release; `live()` covers a reconnect that replaced the binding while
        // the SDK still holds this session's callback. Neither may let a dead session speak for the current one.
        if (active && live()) runCatching { action() }
    }

    override fun onGlassesAppResumed() = emit(events::appResumed)
    override fun onGlassesAppPaused() = emit(events::appPaused)
    override fun onWearingStatusChanged(wearing: Boolean) = emit { events.wearingChanged(wearing) }
    override fun onScreenOff() = emit(events::screenOff)
    override fun onScreenOn() = emit(events::screenOn)
}

private const val APP_START_TAG = "EdithCxrCommand"
