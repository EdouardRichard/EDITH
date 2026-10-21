package com.sheen.rokid.phone.plugin.diagnostic

import com.sheen.rokid.contracts.*
import com.sheen.rokid.output.*
import com.sheen.rokid.plugin.*
import java.util.UUID

class DiagnosticTextPlugin(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val trace: TraceRecorder,
    private val profileLookup: (String) -> ModelProfile?,
    private val hostTextAllowed: (String) -> Boolean = { false },
    private val cancelOutputTurn: (SessionId, Generation, String) -> Unit = { _, _, _ -> },
    private val turnBudgetMillis: Long = 30_000,
    private val cancelOutputHint: (SessionId, Generation, String, String) -> Unit = { _, _, _, _ -> },
) : PluginLifecycle, ConfiguredPlugin {
    init { require(turnBudgetMillis > 0) }
    override val descriptor = PluginDescriptor("p2-diagnostic", "1.0.0", 1, "P2 Diagnostic",
        listOf(Capability.Audio, Capability.Asr, Capability.Speaker, Capability.Model, Capability.TextOutput), ConfigSchema(1))
    private data class Config(val profile: String, val revision: Long, val consent: Boolean, val synthetic: Boolean)
    private class Owner(val context: PluginContext, val turn: TranscriptRevision, val requestId: String,
        val hintId: String, val validUntil: Long) {
        lateinit var assembler: ReadableHintAssembler
        var handle: CancellationHandle? = null
        var closed = false
        var terminal = false
        var firstToken = false
        var readable = false
        var displayed = false
        var submitting = false
        var pendingAck = false
        var hintExpiry = 0L
    }
    private val gate = Any()
    private var config: Config? = null
    private var context: PluginContext? = null
    private var turn: TranscriptRevision? = null
    private var owner: Owner? = null
    private var traceSession: String? = null
    override fun validate(configuration: PluginConfiguration) { parse(configuration) }
    override fun configure(configuration: PluginConfiguration) {
        val parsed = parse(configuration)
        val old = synchronized(gate) { val changed = config != parsed; config = parsed; if (changed) detach() else null }
        close(old)
    }
    private fun parse(c: PluginConfiguration): Config {
        require(c.schemaVersion == 1)
        require(c.values.keys.all { it in setOf("profileId", "profileRevision", "requestConsent", "inputMode") })
        val profile = c.values["profileId"]; require(!profile.isNullOrBlank())
        val revision = c.values["profileRevision"]?.toLongOrNull(); require(revision != null && revision >= 0)
        val consent = c.values["requestConsent"]?.toBooleanStrictOrNull(); require(consent != null)
        val mode = c.values["inputMode"] ?: "transcript"; require(mode in setOf("transcript", "synthetic"))
        return Config(profile, revision, consent, mode == "synthetic")
    }
    override fun activate(context: PluginContext) {
        deactivate(DeactivationReason.Switched)
        synchronized(gate) { this.context = context; traceSession = "s-${UUID.randomUUID()}" }
    }
    override fun deactivate(reason: DeactivationReason) {
        val old = synchronized(gate) { context = null; turn = null; traceSession = null; detach() }
        close(old)
    }
    override fun handle(event: HostEvent) {
        var old: Owner? = null
        var help = false
        synchronized(gate) {
            val c = context ?: return
            if (event.envelope.sessionId != c.sessionId || event.envelope.generation != c.generation) return
            when (event) {
                is TranscriptEvent -> {
                    val incoming = event.revision
                    val current = turn
                    if (current != null) {
                        if (incoming.turnId == current.turnId) {
                            if (current.final || incoming.revision <= current.revision || incoming.startNanos != current.startNanos) return
                        } else {
                            if (incoming.startNanos <= current.startNanos) return
                            old = detach()
                        }
                    }
                    turn = incoming
                    mark(if (incoming.final) TraceMark.ASR_FINAL else TraceMark.ASR_PARTIAL)
                }
                is AudioEvent -> mark(TraceMark.AUDIO_RECEIVED)
                is SpeakerEvent -> mark(TraceMark.SPEAKER_READY)
                is UserAction -> if (event.action == UserAction.Action.Help) { mark(TraceMark.MANUAL_DECISION); help = true }
                is OutputStateEvent -> owner?.let { o ->
                    if (!o.closed && !o.displayed && event.hintId == o.hintId &&
                        event.status == OutputStatus.Displayed && clock.nowNanos() < o.hintExpiry) {
                        if (o.submitting) o.pendingAck = true
                        else if (o.readable) { o.displayed = true; mark(TraceMark.DISPLAY_ACK, o) }
                    }
                }
                else -> Unit
            }
        }
        close(old)
        if (help) request()
    }
    private fun request() {
        val snapshot = synchronized(gate) {
            val c = context ?: return
            val t = turn?.takeIf { it.final && it.text.isNotBlank() } ?: return
            val cfg = config?.takeIf { it.consent } ?: return
            Triple(c, t, cfg)
        }
        val (c, t, cfg) = snapshot
        val text = if (cfg.synthetic) DiagnosticPrompts.syntheticInput else t.text
        if (!hostTextAllowed(text)) return
        val profile = profileLookup(cfg.profile)?.takeIf { it.id == cfg.profile && it.revision == cfg.revision } ?: return
        val validUntil = plusMillis(t.endNanos, turnBudgetMillis)
        val now = clock.nowNanos()
        val deadline = minOf(plusMillis(now, profile.timeoutMillis), validUntil)
        if (deadline <= now) return
        val o = Owner(c, t, "r-${UUID.randomUUID()}", "h-${UUID.randomUUID()}", validUntil)
        o.assembler = ReadableHintAssembler(clock, scheduler, { emit(o, it) })
        val old = synchronized(gate) {
            if (context !== c || turn !== t || config != cfg) return
            val previous = detach(); owner = o; previous
        }
        close(old)
        val request = ModelRequest(EventEnvelope(c.sessionId, c.generation, o.requestId, now, now), o.requestId,
            t.turnId, cfg.profile, cfg.revision, DiagnosticPrompts.instructions, listOf(InputPart.Text(text)), emptyList(),
            OutputModality.TEXT, deadline)
        try {
            synchronized(gate) { if (owner !== o || o.closed) return; mark(TraceMark.REQUEST_START, o) }
            val model = c.services.model()
            synchronized(gate) { if (owner !== o || o.closed || context !== c || turn !== t) return }
            val h = model.request(request) { event -> stream(o, event) }
            val cancel = synchronized(gate) {
                if (o.closed || o.terminal || owner !== o) true else { o.handle = h; false }
            }
            if (cancel) h.cancel()
        } catch (_: Exception) {
            val failed = synchronized(gate) { if (owner === o) detach() else null }
            close(failed)
        }
    }
    private fun stream(o: Owner, event: ModelStreamEvent) {
        var handle: CancellationHandle? = null
        synchronized(gate) {
            if (owner !== o || o.closed || o.terminal || context !== o.context || turn !== o.turn) return
            when (event) {
                is ModelStreamEvent.TextDelta -> {
                    if (event.requestId != o.requestId) return
                    if (event.text.isNotEmpty() && !o.firstToken) { o.firstToken = true; mark(TraceMark.FIRST_TOKEN, o) }
                }
                is ModelStreamEvent.ToolCallDelta, is ModelStreamEvent.ToolCallTerminal -> Unit
                is ModelStreamEvent.Terminal -> {
                    if (event.requestId != o.requestId) return
                    o.terminal = true; handle = o.handle; o.handle = null
                }
            }
        }
        when (event) {
            is ModelStreamEvent.TextDelta -> o.assembler.append(event.text)
            is ModelStreamEvent.Terminal -> o.assembler.finish(event.end)
            // This plugin declares no tools, so a tool event carries nothing it can act on: it must not end the turn
            // (a terminal tool call is not the model's text answer) and must not be rendered.
            is ModelStreamEvent.ToolCallDelta, is ModelStreamEvent.ToolCallTerminal -> Unit
        }
        handle?.cancel()
    }
    private fun emit(o: Owner, text: String) {
        val hint = synchronized(gate) {
            if (owner !== o || o.closed || context !== o.context || turn !== o.turn) return
            val now = clock.nowNanos(); val expiry = minOf(plusMillis(now, 5000), o.validUntil)
            if (expiry <= now) return
            o.submitting = true; o.pendingAck = false; o.hintExpiry = expiry
            Assistance(o.hintId, o.context.sessionId, o.context.generation, o.turn.turnId, 0, text, null, expiry)
        }
        val sink = runCatching { o.context.services.textOutput() }.getOrNull()
        val current = synchronized(gate) { owner === o && !o.closed && context === o.context && turn === o.turn }
        val result = if (current) runCatching { sink?.submit(hint) }.getOrNull() else null
        val staleAdmission = synchronized(gate) {
            o.submitting = false
            if (owner === o && !o.closed && result == OutputSubmission.Accepted) {
                if (!o.readable) { o.readable = true; mark(TraceMark.FIRST_READABLE_HINT, o) }
                if (o.pendingAck && !o.displayed && clock.nowNanos() < o.hintExpiry) {
                    o.displayed = true; mark(TraceMark.DISPLAY_ACK, o)
                }
            }
            o.pendingAck = false
            result == OutputSubmission.Accepted && (owner !== o || o.closed)
        }
        if (staleAdmission) cancelOutputHint(o.context.sessionId, o.context.generation, o.turn.turnId, o.hintId)
    }
    private fun detach(): Owner? = owner?.also { it.closed = true; owner = null }
    private fun close(o: Owner?) {
        if (o == null) return
        o.assembler.cancel()
        val h = synchronized(gate) { o.handle.also { o.handle = null } }
        runCatching { h?.cancel() }
        cancelOutputTurn(o.context.sessionId, o.context.generation, o.turn.turnId)
    }
    private fun mark(mark: TraceMark, o: Owner? = null) {
        val session = traceSession ?: return
        trace.record(TraceRecord(session, o?.requestId, mark, clock.nowNanos(), null, null, null))
    }
    private fun plusMillis(base: Long, millis: Long): Long {
        if (millis > Long.MAX_VALUE / 1_000_000) return Long.MAX_VALUE
        val nanos = millis * 1_000_000
        return if (base > Long.MAX_VALUE - nanos) Long.MAX_VALUE else base + nanos
    }
}
