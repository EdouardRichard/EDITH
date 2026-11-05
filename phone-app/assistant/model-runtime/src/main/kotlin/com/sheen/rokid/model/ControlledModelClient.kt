package com.sheen.rokid.model

import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.*
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
import com.sheen.rokid.contracts.ContractError
import kotlinx.serialization.json.*

/** R70: a bounded read of a provider error body — enough for its machine code, never a full diagnostic dump. */
private const val MAX_ERROR_BODY_BYTES = 8_192L

/**
 * 0.5.4 Task 6: the three stages that bound one model request. [ControlledModelClient] owns all of them, so no caller
 * (and no HTTP-library default) can silently decide when a request dies.
 *
 * - [firstTokenMillis]: send → first `TextDelta`/`ReasoningDelta`. A thinking model may spend a long time before the
 *   first visible fragment; 45 s is the grace, not the whole answer budget.
 * - [streamIdleMillis]: the longest silence allowed **between** two fragments. Every accepted delta restarts it, so a
 *   stream that keeps flowing never trips it.
 * - [hardCapMillis]: the whole request, whatever the profile or caller allows. It is only ever lowered by the caller's
 *   own `ModelRequest.deadlineNanos`.
 */
data class RequestTimeouts(
    val firstTokenMillis: Long = 45_000,
    val streamIdleMillis: Long = 20_000,
    val hardCapMillis: Long = 90_000,
) {
    init {
        require(firstTokenMillis > 0 && streamIdleMillis > 0 && hardCapMillis > 0)
    }
}

// Stable machine codes for the three stages. A timeout is never a verdict on the answer; these tell an operator (and
// Task 8's `P3_MODEL_REQ`) which stage expired. The turn/gateway boundary keeps its own `DEADLINE`.
private const val FIRST_TOKEN_TIMEOUT = "FIRST_TOKEN_TIMEOUT"
private const val STREAM_IDLE_TIMEOUT = "STREAM_IDLE_TIMEOUT"
private const val REQUEST_DEADLINE = "REQUEST_DEADLINE"

class ControlledModelClient(
    http: OkHttpClient, private val repository: ProfileRepository, private val resolver: SecretResolver,
    private val gate: CapabilityGate, private val budgeter: ContextBudgeter, private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    /**
     * 0.25 R75: the consent for one profile. The profile is a parameter because image upload is decided by the
     * profile's declared `IMAGE` modality, not by the running session alone; text/audio remain the owner's policy
     * (`HostCompositionRoot.consentSource`). Existing callers' `{ RequestConsent(...) }` lambdas are unchanged.
     */
    private val consent: (ModelProfile) -> RequestConsent,
    private val wallClock: Clock = Clock.systemUTC(), private val allowLocalHttp: Boolean = false,
    /** P5 (C1 fix): real MediaSource for resolving audio segment refs at encode time. Null = no audio path. */
    private val mediaSource: MediaSource? = null,
) : ModelClient, ProfileProbeTransport, AutoCloseable {
    private val transport = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
    /** Task 6: one policy for every request this client sends. */
    private val timeouts = RequestTimeouts()
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "edith-model-http").apply { isDaemon = true } }
    // Mutable media source: starts as no-op, can be replaced after construction when the audio bridge is ready.
    @Volatile private var activeMediaSource: MediaSource = mediaSource ?: NoOpMediaSource
    // Both adapters resolve media at encode time, so a late `setMediaSource` (0.25: images as well as audio)
    // reaches them.
    private val deepSeekAdapter get() = DeepSeekResponsesAdapter(activeMediaSource)
    private val openAiAdapter get() = OpenAiChatCompletionsAdapter(activeMediaSource)
    fun setMediaSource(source: MediaSource) { activeMediaSource = source }
    private object NoOpMediaSource : MediaSource { override fun resolveAudio(ref: String) = null }
    private val lock = Any()
    private val requests = linkedSetOf<Pending>()
    private data class Owner(val generation: Long, val turn: String)
    private val owners = mutableMapOf<String, Owner>()
    private var closed = false
    private val changes = repository.listenChanges { id ->
        synchronized(lock) { requests.filter { it.input.profileId == id } }.forEach { it.end(ModelEnd.CANCELLED, "PROFILE_CHANGED") }
    }

    override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle = admit(request, listener, false)

    override fun probe(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle = admit(request, listener, true)

    private fun admit(input: ModelRequest, listener: (ModelStreamEvent) -> Unit, probe: Boolean): CancellationHandle {
        val profile = repository.get(input.profileId) ?: rejected()
        // 0.52 C1: one mapping table decides adapter + endpoint, so the catalog's family id and the adapter's support id
        // can never disagree again. `DEEPSEEK_RESPONSES` stays the documented alias for pre-0.5.2 debug profiles.
        val protocol = ProtocolIds.canonical(profile.protocolId)
        val chatCompletions = protocol == ProtocolIds.OPENAI_CHAT_COMPLETIONS
        val support = if (chatCompletions) openAiAdapter.support else deepSeekAdapter.support
        gate.check(profile, input, support, consent(profile), probe)
        val prepared = budgeter.prepare(profile, input)
        val endpoint = if (chatCompletions)
            ModelEndpoint.chatCompletions(profile.baseUrl, allowLocalHttp)
        else ModelEndpoint.responses(profile.baseUrl, allowLocalHttp)
        val admittedAt = clock.nowNanos()
        // Task 6: the client is the single owner of the request cap. It never re-applies `profile.timeoutMillis` — the
        // caller (AgentLoop) already folded that into `deadlineNanos`; here only min(caller deadline, hard cap) remains.
        val deadline = minOf(input.deadlineNanos, saturatedAdd(admittedAt, millisNanos(timeouts.hardCapMillis)))
        // 0.53 design (revised) §5.4: reasoning is passed through as-is. The system does not guess a model family, and
        // a server that returns reasoning must be answered with it on the next tool-bearing request — whatever the
        // user switch says — so the client never filters the event.
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
        val payload = if (chatCompletions) {
            openAiAdapter.encode(profile, prepared)
        } else {
            deepSeekAdapter.encode(profile, prepared)
        }
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
        // The hard cap covers the whole request, retries included. It is armed first so that a scheduler which runs the
        // callback immediately (tests) settles the request before the grace is considered at all.
        pending.attachTimer(scheduler.at(deadline) { pending.end(ModelEnd.DEADLINE, REQUEST_DEADLINE) })
        // The grace only matters while it can still expire before the cap; arming it past the cap would produce a
        // timer that can never decide anything.
        val graceAt = saturatedAdd(admittedAt, millisNanos(timeouts.firstTokenMillis))
        if (graceAt < deadline && pending.active()) {
            pending.attachFirstTokenGrace(scheduler.at(graceAt) { pending.firstTokenTimedOut() })
        }
        if (clock.nowNanos() >= deadline) pending.end(ModelEnd.DEADLINE, REQUEST_DEADLINE)
        else execute(pending, encoded ?: rejected(), 0)
        return pending
    }

    private fun execute(pending: Pending, request: Request, attempt: Int) {
        if (!pending.active()) return
        if (clock.nowNanos() >= pending.deadline) { pending.end(ModelEnd.DEADLINE, REQUEST_DEADLINE); return }
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
                        pending.end(ModelEnd.FAILED, httpFailureCode(response))
                        return@use
                    }
                    val body = response.body
                    if (body == null || body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } != true) {
                        pending.end(ModelEnd.FAILED, "CONTENT_TYPE"); return@use
                    }
                    val framing = SseDecoder()
                    // P5: route to the right SSE decoder by protocol. Chat Completions uses a different wire format
                    // (choices[].delta.content + finish_reason) than the Responses API (response.output_text.delta).
                    val isChatCompletions = ProtocolIds.isChatCompletions(
                        repository.get(pending.input.profileId)?.protocolId.orEmpty(),
                    )
                    val chatDecoder = if (isChatCompletions) ChatCompletionsDecoder(pending.input.requestId) else null
                    val responsesDecoder = if (!isChatCompletions) ResponsesDecoder(pending.input.requestId) else null
                    fun frames(frames: List<SseFrame>) {
                        for (frame in frames) {
                            if (!pending.active()) break
                            if (chatDecoder != null) {
                                chatDecoder.accept(frame).forEach { event ->
                                    // Review round 1: the retry gate is "has this turn already delivered payload?".
                                    // Chat answer text and call fragments count; reasoning never does (it is not the
                                    // answer). Before this, only the Responses path set the flag.
                                    when (event) {
                                        is ModelStreamEvent.TextDelta,
                                        is ModelStreamEvent.ToolCallDelta,
                                        is ModelStreamEvent.ToolCallTerminal -> pending.delta.set(true)
                                        else -> Unit
                                    }
                                    pending.deliver(event)
                                }
                            } else {
                                val type = try { (Json.parseToJsonElement(frame.data) as? JsonObject)?.get("type")?.jsonPrimitive?.content }
                                    catch (_: Exception) { null }
                                    catch (_: StackOverflowError) { null }
                                if (type == "response.output_text.delta") pending.delta.set(true)
                                responsesDecoder!!.accept(frame).forEach(pending::deliver)
                            }
                        }
                    }
                    body.byteStream().use { stream ->
                        val bytes = ByteArray(8192)
                        var total = 0L
                        while (pending.active()) {
                            val count = stream.read(bytes)
                            if (count == -1) {
                                frames(framing.finish())
                                if (pending.active()) (chatDecoder?.endOfInput() ?: responsesDecoder!!.endOfInput()).forEach(pending::deliver)
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

    /**
     * A non-successful HTTP answer's stable code. 401 keeps its dedicated code; any other answer is inspected for the
     * provider's context-overflow signal (R70) before falling back to `HTTP_STATUS`. The body is read bounded and only
     * matched — it is never logged or carried upward.
     */
    private fun httpFailureCode(response: Response): String {
        if (response.code == 401) return "HTTP_AUTH"
        val body = runCatching { response.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrNull()
        return providerContextOverflow(body) ?: "HTTP_STATUS"
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
        /** Task 6: true once the first `TextDelta`/`ReasoningDelta` arrived; disarms the first-token grace. */
        private val firstToken = AtomicBoolean(false)
        private var firstTokenTimer: CancellationHandle? = null
        private var idleTimer: CancellationHandle? = null
        private var call: Call? = null
        private val timers = mutableListOf<CancellationHandle>()
        fun active(): Boolean {
            if (done.get()) return false
            if (repository.get(input.profileId)?.revision != revision) { end(ModelEnd.CANCELLED, "PROFILE_CHANGED"); return false }
            if (clock.nowNanos() >= deadline) { end(ModelEnd.DEADLINE, REQUEST_DEADLINE); return false }
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
        /** Task 6: the first-token grace is kept separately so the first fragment can disarm it early. */
        fun attachFirstTokenGrace(value: CancellationHandle) {
            val accepted = synchronized(lock) {
                if (done.get()) false else { firstTokenTimer = value; timers += value; true }
            }
            if (!accepted) value.cancel()
        }
        fun firstTokenTimedOut() {
            if (!firstToken.get()) end(ModelEnd.FAILED, FIRST_TOKEN_TIMEOUT)
        }
        /** Task 6: mark the first fragment as seen (`TextDelta`/`ReasoningDelta`) and disarm the grace. */
        private fun noteFirstToken(event: ModelStreamEvent) {
            if (event !is ModelStreamEvent.TextDelta && event !is ModelStreamEvent.ReasoningDelta) return
            if (!firstToken.compareAndSet(false, true)) return
            val grace = synchronized(lock) { val armed = firstTokenTimer; firstTokenTimer = null; armed }
            grace?.cancel()
        }
        /**
         * Task 6: (re)start the idle window. Called after every accepted delta — a thinking stream that keeps producing
         * fragments therefore never trips it. Every delta replaces the previous window, so only total silence expires.
         */
        private fun armIdle() {
            if (done.get()) return
            val handle = scheduler.at(saturatedAdd(clock.nowNanos(), millisNanos(timeouts.streamIdleMillis))) {
                end(ModelEnd.FAILED, STREAM_IDLE_TIMEOUT)
            }
            var previous: CancellationHandle? = null
            val accepted = synchronized(lock) {
                if (done.get()) false else { previous = idleTimer; idleTimer = handle; timers += handle; true }
            }
            if (!accepted) { handle.cancel(); return }
            previous?.cancel()
        }
        fun deliver(event: ModelStreamEvent) {
            if (!active()) return
            if (event is ModelStreamEvent.Terminal) {
                end(event.end, event.safeCode, event.usage)
                return
            }
            // Mark liveness **before** the listener runs: a fragment that has arrived must win the race against a
            // watchdog callback firing on another thread at the same instant.
            noteFirstToken(event)
            // 0.53 (revised) §5.4: reasoning is forwarded verbatim. It is still not answer text, so it never satisfies
            // the 429/5xx retry gate (which is about text already delivered to the listener).
            val admitted = synchronized(lock) { !done.get() }
            if (admitted) try { listener(event) } catch (_: Exception) { end(ModelEnd.FAILED, "LISTENER_FAILED") }
            // Deltas of any kind are liveness for the idle window; metadata only (`response.created`) is not.
            if (event is ModelStreamEvent.TextDelta || event is ModelStreamEvent.ReasoningDelta ||
                event is ModelStreamEvent.ToolCallDelta || event is ModelStreamEvent.ToolCallTerminal) {
                armIdle()
            }
        }
        fun end(end: ModelEnd, code: String?, usage: ModelUsage? = null) {
            if (!done.compareAndSet(false, true)) return
            release()
            try { listener(ModelStreamEvent.Terminal(input.requestId, end, usage, code)) } catch (_: Exception) { }
        }
        fun discard() { if (done.compareAndSet(false, true)) release() }
        private fun release() {
            val resources = synchronized(lock) {
                requests.remove(this)
                val resources = call to timers.toList()
                call = null; timers.clear(); firstTokenTimer = null; idleTimer = null
                resources
            }
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
