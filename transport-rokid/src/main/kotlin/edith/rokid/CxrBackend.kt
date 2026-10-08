package edith.rokid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.session.*
import edith.plugin.CancellationHandle
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

    private class Pending(val route: RokidCommandRoute, val correlation: RokidReplyCorrelation?,
        val listener: (String?) -> Unit) {
        val alive = AtomicBoolean(true)
    }
    private class Binding(val session: CxrSession) {
        val audio = mutableMapOf<RokidBackend.Audio, AudioInstaller>()
        val pending = linkedMapOf<Long, Pending>()
        val usedReplyChannels = mutableSetOf<String>()
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
                        val owned = callbacks(created, events)
                        binding = owned
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
            override fun onSessionClosed(reason: CloseReason) { if (live()) events.interrupted() }
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
        return owned
    }
    fun onAuthorizationResult(resultCode: Int, data: Intent?) {
        fence.dispatch { authCallback?.invoke(manager.parseAuthorizationResult(resultCode, data)) }
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
