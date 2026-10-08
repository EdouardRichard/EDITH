package edith.model

import edith.plugin.*
import edith.storage.*
import okhttp3.OkHttpClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import edith.contracts.ContractError
import kotlinx.serialization.json.*

class ControlledModelClient(
    http: OkHttpClient, private val repository: ProfileRepository, private val resolver: SecretResolver,
    private val gate: CapabilityGate, private val budgeter: ContextBudgeter, private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler, private val consent: () -> RequestConsent,
    private val wallClock: Clock = Clock.systemUTC(), private val allowLocalHttp: Boolean = false,
) : ModelClient, AutoCloseable {
    private val transport = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "edith-model-http").apply { isDaemon = true } }
    private val adapter = DeepSeekResponsesAdapter()
    private val lock = Any()
    private val requests = linkedSetOf<Pending>()
    private data class Owner(val generation: Long, val turn: String)
    private val owners = mutableMapOf<String, Owner>()
    private var closed = false
    private val changes = repository.listenChanges { id ->
        synchronized(lock) { requests.filter { it.input.profileId == id } }.forEach { it.end(ModelEnd.CANCELLED, "PROFILE_CHANGED") }
    }

    override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle = admit(request, listener, false)

    internal fun probe(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle = admit(request, listener, true)

    private fun admit(input: ModelRequest, listener: (ModelStreamEvent) -> Unit, probe: Boolean): CancellationHandle {
        val profile = repository.get(input.profileId) ?: rejected()
        gate.check(profile, input, adapter.support.takeIf { it.id == profile.protocolId }, consent(), probe)
        val prepared = budgeter.prepare(profile, input)
        val endpoint = ModelEndpoint.responses(profile.baseUrl, allowLocalHttp)
        val deadline = minOf(input.deadlineNanos, saturatedAdd(clock.nowNanos(), millisNanos(profile.timeoutMillis)))
        val pending = Pending(input, profile.revision, deadline, listener)
        val superseded: List<Pending>
        synchronized(lock) {
            if (closed) rejected()
            val session = input.envelope.sessionId.value
            val generation = input.envelope.generation.value
            val owner = owners[session]
            if (owner != null && generation < owner.generation) rejected()
            if (requests.any { it.input.requestId == input.requestId }) rejected()
            superseded = if (owner != null && (generation > owner.generation || input.turnId != owner.turn))
                requests.filter { it.input.envelope.sessionId == input.envelope.sessionId } else emptyList()
            owners[session] = Owner(generation, input.turnId)
            requests += pending
        }
        superseded.forEach { it.end(ModelEnd.CANCELLED, "TURN_REPLACED") }
        if (!pending.active()) return pending
        val payload = adapter.encode(profile, prepared)
        var encoded: Request? = null
        try {
            resolver.withSecret(profile.secret) { chars ->
                if (chars.isEmpty() || chars.any { it.code !in 33..126 }) rejected()
                encoded = Request.Builder().url(endpoint).header("Authorization", "Bearer ${String(chars)}")
                    .header("Accept", "text/event-stream").post(payload.toRequestBody("application/json".toMediaType())).build()
            }
        } catch (_: Exception) {
            pending.discard()
            rejected()
        }
        if (!pending.active()) return pending
        pending.attachTimer(scheduler.at(deadline) { pending.end(ModelEnd.DEADLINE, "DEADLINE") })
        if (clock.nowNanos() >= deadline) pending.end(ModelEnd.DEADLINE, "DEADLINE")
        else execute(pending, encoded ?: rejected(), 0)
        return pending
    }

    private fun execute(pending: Pending, request: Request, attempt: Int) {
        if (!pending.active()) return
        if (clock.nowNanos() >= pending.deadline) { pending.end(ModelEnd.DEADLINE, "DEADLINE"); return }
        // OkHttp status follow-ups bypass retryOnConnectionFailure; only our scheduler may repeat a POST.
        val body = requireNotNull(request.body)
        val attemptBody = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            override fun isOneShot() = true
        }
        val call = transport.newCall(request.newBuilder().post(attemptBody).build())
        if (!pending.attachCall(call)) return
        try { executor.execute {
            try {
                if (!pending.active()) return@execute
                call.execute().use { response ->
                    if (!pending.active()) return@use
                    if (!response.isSuccessful) {
                        if (attempt == 0 && (response.code == 429 || response.code in 500..599) && !pending.delta.get()) {
                            val delay = retryDelay(response.header("Retry-After"))
                            val retryAt = delay?.let { saturatedAdd(clock.nowNanos(), it) }
                            if (retryAt != null && retryAt < pending.deadline) {
                                // Close this attempt before the timer can submit its replacement.
                                response.close(); call.cancel(); pending.detachCall(call)
                                pending.attachTimer(scheduler.at(retryAt) { execute(pending, request, 1) })
                                return@use
                            }
                        }
                        pending.end(ModelEnd.FAILED, if (response.code == 401) "HTTP_AUTH" else "HTTP_STATUS")
                        return@use
                    }
                    val body = response.body
                    if (body == null || body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } != true) {
                        pending.end(ModelEnd.FAILED, "CONTENT_TYPE"); return@use
                    }
                    val framing = SseDecoder()
                    val decoder = ResponsesDecoder(pending.input.requestId)
                    fun frames(frames: List<SseFrame>) {
                        for (frame in frames) {
                            if (!pending.active()) break
                            val type = try { (Json.parseToJsonElement(frame.data) as? JsonObject)?.get("type")?.jsonPrimitive?.content }
                                catch (_: Exception) { null }
                                catch (_: StackOverflowError) { null }
                            if (type == "response.output_text.delta") pending.delta.set(true)
                            decoder.accept(frame).forEach(pending::deliver)
                        }
                    }
                    body.byteStream().use { stream ->
                        val bytes = ByteArray(8192)
                        var total = 0L
                        while (pending.active()) {
                            val count = stream.read(bytes)
                            if (count == -1) {
                                frames(framing.finish())
                                if (pending.active()) decoder.endOfInput().forEach(pending::deliver)
                                break
                            }
                            total += count
                            if (total > 1048576L) { pending.end(ModelEnd.FAILED, "RESPONSE_LIMIT"); break }
                            frames(framing.feed(bytes.copyOf(count)))
                        }
                    }
                }
            } catch (error: SseDecodingException) { pending.end(ModelEnd.FAILED, error.safeCode) }
            catch (_: Exception) { pending.end(ModelEnd.FAILED, "TRANSPORT_FAILED") }
            finally { call.cancel(); pending.detachCall(call) }
        } } catch (_: Exception) { call.cancel(); pending.end(ModelEnd.FAILED, "TRANSPORT_FAILED") }
    }

    private fun retryDelay(value: String?): Long? {
        if (value == null) return 250_000_000
        val trimmed = value.trim()
        return try {
            if (trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' }) Math.multiplyExact(trimmed.toLong(), 1_000_000_000L)
            else {
                val epoch = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                Math.multiplyExact(maxOf(0, Math.subtractExact(epoch, wallClock.millis())), 1_000_000L)
            }
        } catch (_: Exception) { null }
    }

    private inner class Pending(val input: ModelRequest, val revision: Long, val deadline: Long,
        private val listener: (ModelStreamEvent) -> Unit) : CancellationHandle {
        private val done = AtomicBoolean(false)
        val delta = AtomicBoolean(false)
        private var call: Call? = null
        private val timers = mutableListOf<CancellationHandle>()
        fun active(): Boolean {
            if (done.get()) return false
            if (repository.get(input.profileId)?.revision != revision) { end(ModelEnd.CANCELLED, "PROFILE_CHANGED"); return false }
            if (clock.nowNanos() >= deadline) { end(ModelEnd.DEADLINE, "DEADLINE"); return false }
            return !done.get()
        }
        fun attachCall(value: Call): Boolean {
            val accepted = synchronized(lock) { if (done.get()) false else { call = value; true } }
            if (!accepted || !active()) { value.cancel(); return false }
            return true
        }
        fun detachCall(value: Call) { synchronized(lock) { if (call === value) call = null } }
        fun attachTimer(value: CancellationHandle) {
            val accepted = synchronized(lock) { if (done.get()) false else { timers += value; true } }
            if (!accepted) value.cancel()
        }
        fun deliver(event: ModelStreamEvent) {
            if (!active()) return
            if (event is ModelStreamEvent.Terminal) end(event.end, event.safeCode, event.usage)
            else {
                val admitted = synchronized(lock) { !done.get() }
                if (admitted) try { listener(event) } catch (_: Exception) { end(ModelEnd.FAILED, "LISTENER_FAILED") }
            }
        }
        fun end(end: ModelEnd, code: String?, usage: ModelUsage? = null) {
            if (!done.compareAndSet(false, true)) return
            release()
            try { listener(ModelStreamEvent.Terminal(input.requestId, end, usage, code)) } catch (_: Exception) { }
        }
        fun discard() { if (done.compareAndSet(false, true)) release() }
        private fun release() {
            val resources = synchronized(lock) { requests.remove(this); val resources = call to timers.toList(); call = null; timers.clear(); resources }
            resources.first?.cancel(); resources.second.forEach { it.cancel() }
        }
        override fun cancel() { end(ModelEnd.CANCELLED, "CANCELLED") }
    }

    override fun close() {
        val pending = synchronized(lock) { closed = true; requests.toList() }
        changes.cancel(); pending.forEach { it.cancel() }; executor.shutdownNow()
    }
    private fun rejected(): Nothing = throw ContractError.ConfigurationRejected("Model configuration unavailable")
    private fun millisNanos(value: Long) = if (value > Long.MAX_VALUE / 1_000_000) Long.MAX_VALUE else value * 1_000_000
    private fun saturatedAdd(left: Long, right: Long) = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
