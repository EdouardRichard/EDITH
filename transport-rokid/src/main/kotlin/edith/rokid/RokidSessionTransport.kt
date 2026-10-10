package edith.rokid

import android.app.Activity
import edith.audio.PcmIngress
import edith.contracts.*
import edith.plugin.*
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
    private val onOffer: (edith.audio.PcmOffer) -> Unit = {},
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
                        offer != edith.audio.PcmOffer.STALE_EPOCH
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
            onRawPcm: (ByteArray) -> Unit = {}, onOffer: (edith.audio.PcmOffer) -> Unit = {}): RokidSessionTransport {
            val worker = Executors.newSingleThreadExecutor { Thread(it, "edith-cxr-transport").apply { isDaemon = true } }
            return RokidSessionTransport(ingress, onSceneReady, onInput, onInterrupted,
                CxrBackend(context.applicationContext, config, worker), worker,
                { PcmFormatEvidence.resolve(config.firmware, "1.1.2") }, onRawPcm, onOffer)
        }
    }
    /**
     * Routes an activity result to the backend and reports whether it was the pending AI-App authorization answer
     * (review I-3): the caller may only turn a claimed result into a fact about authorization.
     */
    fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): Boolean =
        (backend as? CxrBackend)?.onAuthorizationResult(resultCode, data) ?: false

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
