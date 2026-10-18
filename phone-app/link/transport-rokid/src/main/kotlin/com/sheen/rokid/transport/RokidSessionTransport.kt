package com.sheen.rokid.transport

import android.app.Activity
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

data class RokidTransportState(val ready: Boolean = false, val epoch: InputEpoch? = null,
    val format: AudioFormat = AudioFormat.Unknown, val errorCode: String? = null)

data class RokidCommandRoute(val outbound: String, val replyChannel: String) {
    init { require(outbound.isNotBlank() && replyChannel.isNotBlank()) }
    companion object { val CustomClient = RokidCommandRoute("rk_custom_client", "rk_custom_key") }
}

/** Caller validates its wire identity and never reuses it within this connection. */
fun interface RokidReplyCorrelation { fun matches(json: String): Boolean }

/**
 * What the vendor SDK actually said about an AI-App authorization prompt.
 *
 * One of the three answers the phone can honestly carry, and the only thing the App is allowed to put on the 会话 page.
 * It is decided **here**, from the SDK's own parse (`CxrSessionManager.parseAuthorizationResult` -> `AuthResult`), never
 * from the bare activity `resultCode`: the vendor's parser reports a fail for a `RESULT_OK` result whose `auth_result`
 * extra it does not recognise, or whose `auth_token` is missing/blank (javap of the pinned `client-l-1.1.2.aar`,
 * `AuthorizationHelper.parseAuthorizationResult`), so a verdict read off the code would announce an authorization the
 * SDK refused.
 *
 * Lives beside the transport rather than in the App because the parse result never leaves this module.
 */
enum class CxrAuthorizationOutcome {
    /** The parser returned a success with a token the session path will accept. */
    CONFIRMED,

    /** The user declined the prompt; a legitimate answer rather than a fault. */
    CANCELLED,

    /** Any other answer, including a parse the SDK refused. */
    UNEXPECTED,
}

/**
 * The glasses-scene events EDITH consumes (0.24 spec §5.7).
 *
 * The vendor interface is `com.rokid.cxr.session.IGlassesEventListener` (nine callbacks, javap of the pinned
 * `client-l-1.1.2.aar`); this type names only the five that drive EDITH's behaviour. The others stay vendor defaults,
 * so a host listener is not forced to implement facts it ignores.
 */
interface GlassesEvents {
    fun appResumed()
    fun appPaused()
    fun wearingChanged(wearing: Boolean)
    fun screenOn()
    fun screenOff()
}

interface RokidBackend : AutoCloseable {
    interface Events {
        fun ready()
        fun interrupted()
        fun error(code: String)
    }
    interface Audio {
        fun received(data: ByteArray)
        fun failed()
        fun streaming(active: Boolean)
    }
    fun connect(activity: Activity, events: Events)
    fun cancelAuthorization() = Unit
    fun start(audio: Audio): Boolean
    fun stop(audio: Audio)

    /**
     * Asks the SDK to start the glasses scene, and reports the SDK's own answer asynchronously.
     *
     * The synchronous return answers only "was there a live client to ask?". A scene that refuses to open is an
     * accepted call whose callback is `false`. The callback runs on this backend's worker, never inline on the
     * caller's thread, and is not invented when no client could be asked.
     *
     * Defaults to refusing, so a test backend that has not modelled the path cannot look like it started a scene.
     */
    fun openGlassesApp(onResult: (Boolean) -> Unit): Boolean = false

    /**
     * Registers a consumer for the SDK's glasses-scene events; the returned handle removes it.
     *
     * Defaults to `null` - "this backend cannot observe the scene" - so an old fake keeps compiling and cannot
     * silently claim to forward events.
     */
    fun addGlassesEventListener(listener: GlassesEvents): AutoCloseable? = null

    /**
     * Installs the listener **before** a CXR session is created, as the official 1.1.2 sample does.
     *
     * `SessionHubViewModel.createSessionFromRequest` registers `IGlassesEventListener` on the created session and then
     * calls `connect`, and calls that order critical because the SDK can deliver scene events from the first moment.
     * The factory is invoked once per newly created session, so a reconnect gets exactly one new SDK registration and
     * the replaced session's registration is released. Defaults to refusing, so a backend that cannot observe a scene
     * cannot look like it registered one.
     */
    fun setGlassesEventListenerFactory(factory: (() -> GlassesEvents?)?) = Unit

    /**
     * Registers a consumer for raw payloads on [channelKey], for traffic that is not text - the T17 audio uplink
     * sends PCM here. A registered channel is delivered as bytes and **never** decoded as UTF-8, because that decode
     * is lossy for arbitrary bytes and would corrupt audio into replacement characters.
     *
     * Default no-op so test backends need not implement it.
     */
    fun setBinaryChannelListener(channelKey: String, listener: (ByteArray) -> Unit) = Unit

    /** Stops delivery for [channelKey]. Default no-op, matching [setBinaryChannelListener]. */
    fun removeBinaryChannelListener(channelKey: String) = Unit

    fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
        listener: (String?) -> Unit): CancellationHandle

    /**
     * Sends [json] on [route] and registers no pending reply, for traffic answered by its own payload rather than by
     * correlation (the hint channel). Returns the SDK's own synchronous result.
     *
     * Default refuses, so a test backend that has not modelled this path cannot look like it delivered something.
     */
    fun sendCommand(route: RokidCommandRoute, json: String): Boolean = false

    /**
     * Reports the verdict of the vendor SDK's own parse of an authorization activity result, or `null` when no prompt
     * this backend asked for is pending.
     *
     * Defaulted to `null` - "not mine to answer" - so a test backend that has not modelled the authorization path
     * cannot look like it answered a prompt (the same honest default as [sendCommand]).
     */
    fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
}

class RokidSessionTransport(
    private val ingress: PcmIngress,
    private val onSceneReady: () -> Unit,
    private val onInput: (InputEpoch) -> Unit,
    private val onInterrupted: () -> Unit,
    private val backend: RokidBackend,
    private val worker: Executor,
    private val resolveFormat: () -> VerifiedPcm? = { PcmFormatEvidence.resolve("1.26.011", "1.1.2") },
    /** Optional RAM-only observer for T17 controlled format verification; the transport never retains it. */
    private val rawPcmObserver: (ByteArray) -> Unit = {},
    /**
     * Reports what the epoch's [PcmIngress] decided about each offered datagram.
     *
     * Additive and defaulted so every existing call site keeps its behaviour: `PcmIngress.offer` already returned the
     * outcome, but the return value was consumed inside this class and thrown away, which made
     * `INGRESS_OVERFLOW`/`UNKNOWN_FORMAT`/`STALE_EPOCH` unobservable outside it. Forwarded **as decided**, never
     * reinterpreted here: whether an outcome is a fault is the ingress loop's contract, not the transport's.
     */
    private val onOffer: (com.sheen.rokid.audio.PcmOffer) -> Unit = {},
) : InputPort, AutoCloseable {
    private val gate = Any()
    @Volatile private var closed = false
    /** True once [connect] has been accepted; a repeat tap is a no-op rather than a crash. */
    @Volatile private var connected = false
    private var connection = 0L
    private var revision = 0L
    private var audio: RokidBackend.Audio? = null
    @Volatile var state = RokidTransportState()
        private set
    fun connect(activity: Activity) {
        val identity = synchronized(gate) {
            // Connecting is a **user action** reached from a button, so a second tap must be a no-op rather than a
            // crash: an IllegalStateException on the main thread kills the app, and the user's intent on the second
            // tap is "make sure it is connected", which is already true. A closed transport still refuses, because
            // that is a real misuse rather than an impatient tap.
            check(!closed) { "TRANSPORT_ALREADY_CONNECTED_OR_CLOSED" }
            if (connected) return
            connected = true
            ++connection
        }
        backend.connect(activity, object : RokidBackend.Events {
            override fun ready() {
                val observedRevision = synchronized(gate) { revision }
                enqueue {
                    synchronized(gate) {
                        if (closed || identity != connection || observedRevision != revision) return@enqueue
                        state = state.copy(ready = true, errorCode = null)
                    }
                    onSceneReady()
                }
            }
            override fun interrupted() = interrupt(identity, null)
            override fun error(code: String) = interrupt(identity, code)
        })
    }
    override fun start(epoch: InputEpoch) {
        synchronized(gate) {
            if (closed || !state.ready || state.epoch == epoch) return
            revoke()
            val format = resolveFormat()
            ingress.open(epoch, format)
            state = state.copy(epoch = epoch, format = format?.format ?: AudioFormat.Unknown)
            val notified = AtomicBoolean()
            val callback = object : RokidBackend.Audio {
                override fun received(data: ByteArray) {
                    if (data.isEmpty()) return
                    if (!synchronized(gate) { !closed && audio === this && state.epoch == epoch }) return
                    runCatching { rawPcmObserver(data) }
                    val accepted = synchronized(gate) {
                        if (closed || audio !== this || state.epoch != epoch) return
                        val offer = ingress.offer(epoch, data)
                        onOffer(offer)
                        offer != com.sheen.rokid.audio.PcmOffer.STALE_EPOCH
                    }
                    if (accepted && notified.compareAndSet(false, true)) enqueue {
                        synchronized(gate) {
                            if (closed || audio !== this || state.epoch != epoch || !state.ready) return@enqueue
                        }
                        onInput(epoch)
                    }
                }
                override fun failed() {
                    synchronized(gate) { if (audio !== this) return }
                    interrupt(connection, "AUDIO_STREAM_FAILED")
                }
                override fun streaming(active: Boolean) { if (!active) failed() }
            }
            audio = callback
            enqueue {
                synchronized(gate) {
                    if (closed || audio !== callback || state.epoch != epoch) return@enqueue
                }
                if (!runCatching { backend.start(callback) }.getOrDefault(false)) callback.failed()
            }
        }
    }
    override fun stop(epoch: InputEpoch) {
        synchronized(gate) { if (state.epoch == epoch) revoke() }
    }
    private fun revoke() {
        revision++
        val old = audio
        audio = null
        if (state.epoch != null) ingress.close()
        state = state.copy(epoch = null)
        if (old != null) enqueue { backend.stop(old) }
    }
    private fun interrupt(identity: Long, code: String?) {
        synchronized(gate) {
            if (closed || identity != connection) return
            val notify = state.ready || state.epoch != null || code != null
            revoke()
            // The session is gone, so `connected` must stop claiming otherwise. Leaving it true made the Connect
            // button a permanent no-op after the AI App ended the session underneath us (seen on device 2026-10-08:
            // every later send was refused with ready=false and only restarting the App recovered), which is exactly
            // the dead end that button must not become.
            connected = false
            state = state.copy(ready = false, errorCode = code)
            if (notify) enqueue { if (!closed) onInterrupted() }
        }
    }
    fun send(command: String, json: String, listener: (String?) -> Unit): CancellationHandle {
        return send(command, json, null, listener)
    }
    fun send(command: String, json: String, correlation: RokidReplyCorrelation?,
        listener: (String?) -> Unit): CancellationHandle {
        require(command.isNotBlank())
        require(command == RokidCommandRoute.CustomClient.outbound) { "CXR_REPLY_ROUTE_REQUIRED" }
        val route = RokidCommandRoute.CustomClient
        return send(route, json, correlation, listener)
    }
    /**
     * Sends a custom command that expects no correlated reply, for traffic whose answers are matched by its own
     * payload instead - the hint channel is one, because acks carry the sequence the phone chose.
     *
     * Why not reuse [send]: an uncorrelated `send` claims its reply channel for the connection's lifetime, so the
     * first SHOW would work and every later hint would be refused. This path registers nothing at all.
     *
     * Returns true only when a live session accepted the command for delivery. The SDK call runs on the transport
     * worker, so its own result is not awaited here and `true` is **not** proof the glasses received anything - that
     * is decided by the acknowledgement, or by the hint expiring.
     */
    fun sendCommand(json: String): Boolean {
        require(json.isNotBlank()) { "COMMAND_PAYLOAD_REQUIRED" }
        if (!synchronized(gate) { !closed && state.ready }) {
            // The only silent way this path can lose a command, so it is reported rather than swallowed.
            android.util.Log.i("EdithCxrCommand", "sendCommand refused: no live session (ready=false)")
            return false
        }
        return runCatching {
            enqueue { backend.sendCommand(RokidCommandRoute.CustomClient, json) }
        }.isSuccess
    }

    /**
     * Forwards an app-start request to the backend that owns the SDK session.
     *
     * Deliberately unconditional while the transport is alive: deciding *when* a scene is needed belongs to the
     * composition (no `UI_READY`/`PONG`), and the backend is the only layer that can honestly answer whether a
     * client exists. After [close] nothing may be started, so this refuses.
     */
    fun openGlassesApp(onResult: (Boolean) -> Unit): Boolean {
        if (synchronized(gate) { closed }) return false
        return backend.openGlassesApp(onResult)
    }

    /**
     * Forwards a glasses-scene event registration; the returned handle belongs to the caller.
     *
     * The transport only refuses after [close]. When there is no live session the backend answers `null`, and the
     * caller must treat that as "events are not observable right now" rather than retrying into the void.
     */
    fun addGlassesEventListener(listener: GlassesEvents): AutoCloseable? {
        if (synchronized(gate) { closed }) return null
        return backend.addGlassesEventListener(listener)
    }

    /**
     * Installs the pre-connect scene listener factory for the backend that owns the SDK session.
     *
     * Delegation only: the listener is registered on each newly created session before `connect`, which is the order
     * the official CXR-L 1.1.2 sample uses. Refused after [close] so a torn-down transport cannot register on a new
     * session.
     */
    fun setGlassesEventListenerFactory(factory: (() -> GlassesEvents?)?) {
        if (synchronized(gate) { closed }) return
        backend.setGlassesEventListenerFactory(factory)
    }

    fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
        listener: (String?) -> Unit): CancellationHandle {
        val active = AtomicBoolean(true)
        val observedRevision = synchronized(gate) { revision }
        var downstream: CancellationHandle? = null
        enqueue {
            synchronized(gate) {
                if (closed || !state.ready || revision != observedRevision || !active.get()) { active.set(false); return@enqueue }
            }
            val handle = backend.send(route, json, correlation) { result -> enqueue {
                val valid = synchronized(gate) { !closed && state.ready && revision == observedRevision }
                if (valid && active.compareAndSet(true, false)) listener(result)
            } }
            synchronized(active) {
                downstream = handle
                if (!active.get()) handle.cancel()
            }
        }
        return object : CancellationHandle {
            override fun cancel() = synchronized(active) {
                if (active.getAndSet(false)) downstream?.cancel()
                Unit
            }
        }
    }
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            revoke()
            state = state.copy(ready = false)
        }
        backend.cancelAuthorization()
        enqueue { try { backend.close() } finally { (worker as? ExecutorService)?.shutdown() } }
    }
    private fun enqueue(action: () -> Unit) {
        try { worker.execute(action) } catch (_: RejectedExecutionException) {
            check(closed) { "TRANSPORT_WORKER_UNAVAILABLE" }
        }
    }
    companion object {
        fun create(context: android.content.Context, config: RokidConnectionConfig, ingress: PcmIngress,
            onSceneReady: () -> Unit, onInput: (InputEpoch) -> Unit, onInterrupted: () -> Unit,
            onRawPcm: (ByteArray) -> Unit = {}, onOffer: (com.sheen.rokid.audio.PcmOffer) -> Unit = {}): RokidSessionTransport {
            val worker = Executors.newSingleThreadExecutor { Thread(it, "edith-cxr-transport").apply { isDaemon = true } }
            return RokidSessionTransport(ingress, onSceneReady, onInput, onInterrupted,
                CxrBackend(context.applicationContext, config, worker), worker,
                { PcmFormatEvidence.resolve(config.firmware, "1.1.2") }, onRawPcm, onOffer)
        }
    }
    /**
     * Routes an activity result to the backend and reports the verdict of the SDK's own parse, or `null` when the result
     * was not the pending AI-App authorization answer (review I-3): the caller may only turn the transport's verdict into
     * a fact about authorization, never the bare result code (review Minor 6).
     */
    fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? =
        (backend as? CxrBackend)?.onAuthorizationResult(resultCode, data)

    /**
     * Routes raw payloads on [channelKey] to [listener] instead of the JSON reply correlation.
     *
     * Exposed here so the T17 audio uplink can attach its receiver without reaching into the backend, and so the
     * attachment is undone with the connection rather than outliving it.
     */
    fun attachBinaryChannel(channelKey: String, listener: (ByteArray) -> Unit) {
        backend.setBinaryChannelListener(channelKey, listener)
    }

    /** Stops routing [channelKey]; called on close so a stale receiver cannot be fed after teardown. */
    fun detachBinaryChannel(channelKey: String) {
        backend.removeBinaryChannelListener(channelKey)
    }
}
