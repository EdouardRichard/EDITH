package com.sheen.rokid.phone.plugin.diagnostic

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import com.sheen.rokid.output.*
import kotlin.test.*

class DiagnosticTextPluginTest {
    private val clock = FakeClock(1_000_000_000)
    private val scheduler = ManualScheduler(clock)
    private val requests = mutableListOf<ModelRequest>()
    private val callbacks = mutableListOf<(ModelStreamEvent) -> Unit>()
    private val handles = mutableListOf<FakeCancellation>()
    private val outputs = mutableListOf<Assistance>()
    private val clears = mutableListOf<String>()
    private val hintClears = mutableListOf<String>()
    private var duringSubmit: ((Assistance) -> Unit)? = null
    private var submissionResult = OutputSubmission.Accepted
    private var duringOutputLookup: (() -> Unit)? = null
    private var duringModelLookup: (() -> Unit)? = null
    private var duringRequest: ((ModelRequest, (ModelStreamEvent) -> Unit) -> Unit)? = null
    private var lookup: ModelProfile? = profile()
    private val context = PluginContext(SessionId("private-session-sentinel"), Generation(2), Capability.entries.toSet(),
        object : HostServices {
            override val tasks = FakeTaskScope()
            override fun require(capability: Capability): Any = when (capability) {
                Capability.Model -> { duringModelLookup?.invoke(); object : ModelClient {
                    override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                        requests += request; callbacks += listener
                        val h = FakeCancellation(); handles += h; duringRequest?.invoke(request, listener); return h
                    }
                } }
                Capability.TextOutput -> { duringOutputLookup?.invoke(); object : OutputSink {
                    override fun submit(assistance: Assistance): OutputSubmission {
                        outputs += assistance; duringSubmit?.invoke(assistance); return submissionResult
                    }
                } }
                else -> error("No redundant subscriptions")
            }
        })
    private fun envelope(g: Generation = context.generation) =
        EventEnvelope(context.sessionId, g, "e", clock.nowNanos, clock.nowNanos)
    private fun transcript(turn: String = "a", start: Long = 100, revision: Long = 1, final: Boolean = true,
        text: String = "当前测试问题", g: Generation = context.generation) =
        TranscriptEvent(TranscriptRevision(envelope(g), turn, revision, text, final, start, clock.nowNanos))
    private fun help(p: PluginLifecycle) = p.handle(UserAction(envelope(), UserAction.Action.Help))
    private fun config(consent: String = "true", mode: String = "transcript", revision: String = "3") =
        PluginConfiguration(1, mapOf("profileId" to "dummy", "profileRevision" to revision,
            "requestConsent" to consent, "inputMode" to mode))
    private fun trace() = TraceRecorder()
    private fun plugin(allow: Boolean? = true, trace: TraceRecorder = trace(), budget: Long = 30_000): PluginLifecycle {
        val lookupFn: (String) -> ModelProfile? = { lookup }
        val allowFn: (String) -> Boolean = { allow == true }
        val clearFn: (SessionId, Generation, String) -> Unit = { _, _, t -> clears += t }
        val clearHintFn: (SessionId, Generation, String, String) -> Unit = { _, _, _, h -> hintClears += h }
        val p = DiagnosticTextPlugin(clock, scheduler, trace, lookupFn, allowFn, clearFn, budget, clearHintFn)
        (p as ConfiguredPlugin).configure(config()); p.activate(context); return p
    }
    private fun tick() { clock.advance(150_000_000); scheduler.runDue() }
    private fun marks(t: TraceRecorder): List<String> = t.snapshot().map { it.mark.name }

    /**
     * The module-local resource a fixed sentence really comes from, loaded independently of the plugin's own loader so
     * this test proves the classpath file exists rather than trusting a constant.
     */
    private fun diagnosticResource(name: String): String =
        requireNotNull(javaClass.getResource("/prompts/diagnostic/$name")) {
            "the diagnostic plugin must ship prompts/diagnostic/$name"
        }.readText().replace("\r\n", "\n").trim()

    private fun profile(timeout: Long = 10_000) = ModelProfile("dummy", 3, "dummy", "dummy", "https://invalid.test",
        "dummy", "dummy", object : SecretHandle { override val id = "dummy" }, 1000, 500, 100, 100, timeout,
        setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())

    @Test fun `manual help on authorized final invokes one request and new turn cancels it`() {
        val p = plugin(); p.handle(transcript()); assertTrue(requests.isEmpty()); help(p)
        assertEquals(1, requests.size)
        val r = requests.single()
        assertEquals(context.sessionId, r.envelope.sessionId); assertEquals(context.generation, r.envelope.generation)
        assertEquals("a", r.turnId); assertEquals("dummy", r.profileId); assertEquals(3, r.profileRevision)
        assertEquals(diagnosticResource("instructions.md"), r.instructions)
        assertEquals(listOf(InputPart.Text("当前测试问题")), r.input)
        assertTrue(r.history.isEmpty()); assertEquals(OutputModality.TEXT, r.output)
        assertEquals(11_000_000_000, r.deadlineNanos)
        callbacks.single()(ModelStreamEvent.TextDelta(r.requestId, "建议。"))
        p.handle(transcript("b", 200)); tick()
        assertEquals(1, handles.single().cancelCount); assertTrue(outputs.isEmpty()); assertEquals(listOf("a"), clears)
        callbacks.single()(ModelStreamEvent.TextDelta(r.requestId, "旧输出。")); tick(); assertTrue(outputs.isEmpty())
        p.deactivate(DeactivationReason.Stopped)
    }
    @Test fun `help requires current final intent host authority and matching profile`() {
        val p = plugin(); help(p); p.handle(transcript(final = false)); help(p); assertTrue(requests.isEmpty())
        p.handle(transcript(revision = 2)); (p as ConfiguredPlugin).configure(config("false")); help(p)
        assertTrue(requests.isEmpty()); p.configure(config()); lookup = profile().copy(revision = 4); help(p)
        assertTrue(requests.isEmpty()); lookup = null; help(p); assertTrue(requests.isEmpty())
        p.deactivate(DeactivationReason.Paused)
        val denied = plugin(false); denied.handle(transcript()); help(denied); assertTrue(requests.isEmpty())
    }
    @Test fun `strict configuration rejects implicit consent mode revision and unknown fields`() {
        val p = plugin() as ConfiguredPlugin
        for (c in listOf(config("yes"), config(mode = "automatic"), config(revision = "-1"),
            config().copy(schemaVersion = 2), config().copy(values = config().values + ("secret" to "sentinel")),
            config().copy(values = config().values - "requestConsent"))) {
            assertFailsWith<IllegalArgumentException> { p.validate(c) }
        }
    }
    @Test fun `explicit synthetic mode needs a final and sends only fixed input`() {
        val p = plugin(); (p as ConfiguredPlugin).configure(config(mode = "synthetic")); help(p)
        assertTrue(requests.isEmpty()); p.handle(transcript()); help(p)
        assertEquals(listOf(InputPart.Text(diagnosticResource("synthetic-input.md"))), requests.single().input)
    }

    @Test fun `the fixed sentences are shipped as module resources verbatim`() {
        // Byte-for-byte migration: the resource carries exactly the sentence the plugin used to compile in. The
        // request assertions above then prove the plugin sends the resource, not a second copy of the text.
        assertEquals("仅对当前测试问题给出一句简短中文建议。不要返回推理、工具调用或个人资料。", diagnosticResource("instructions.md"))
        assertEquals("只回复测试通过", diagnosticResource("synthetic-input.md"))
    }
    @Test fun `new turn orders by start locks final and rejects stale identities`() {
        val p = plugin(); p.handle(transcript()); help(p)
        p.handle(transcript(revision = 9, text = "final locked")); p.handle(transcript("x", 100))
        p.handle(transcript("b", 200, g = Generation(1))); assertEquals(0, handles[0].cancelCount)
        p.handle(transcript("b", 200)); help(p)
        p.handle(transcript("a", 100, revision = 10)); p.handle(transcript("x", 150))
        help(p); assertEquals(listOf("a", "b", "b"), requests.map { it.turnId })
        assertEquals(listOf(1, 1, 0), handles.map { it.cancelCount })
        assertEquals(3, requests.map { it.requestId }.toSet().size)
    }
    @Test fun `pause fences model scheduled output ACK and help`() {
        val t = trace(); val p = plugin(trace = t); p.handle(transcript()); help(p)
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "建议。"))
        p.deactivate(DeactivationReason.Paused); tick(); help(p)
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "迟到。")); tick()
        assertEquals(1, requests.size); assertEquals(1, handles[0].cancelCount); assertTrue(outputs.isEmpty())
        p.activate(context.copy(generation = Generation(3))); help(p); assertEquals(1, requests.size)
        assertFalse("DISPLAY_ACK" in marks(t))
    }
    @Test fun `terminal before returned handle is attached still cancels it and flushes completed tail`() {
        val p = plugin(); duringRequest = { r, cb ->
            cb(ModelStreamEvent.TextDelta(r.requestId, "简短建议"))
            cb(ModelStreamEvent.Terminal(r.requestId, ModelEnd.COMPLETED, null, null))
        }
        p.handle(transcript()); help(p); assertEquals(1, handles[0].cancelCount)
        assertTrue(outputs.isEmpty()); tick(); assertEquals(listOf("简短建议"), outputs.map { it.text })
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "迟到。")); tick()
        assertEquals(1, outputs.size)
    }
    @Test fun `reentrant replacement cancels late handle and suppresses old stream`() {
        val p = plugin(); duringRequest = { _, _ -> duringRequest = null; p.handle(transcript("b", 200)) }
        p.handle(transcript()); help(p); assertEquals(1, handles[0].cancelCount)
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "旧句。")); tick(); assertTrue(outputs.isEmpty())
    }
    @Test fun `token readable admission and displayed are distinct and trace identity is opaque`() {
        val t = trace(); val p = plugin(trace = t); p.handle(transcript()); help(p)
        val r = requests[0]; callbacks[0](ModelStreamEvent.TextDelta("wrong", "忽略。"))
        callbacks[0](ModelStreamEvent.TextDelta(r.requestId, "")); assertFalse("FIRST_TOKEN" in marks(t))
        callbacks[0](ModelStreamEvent.TextDelta(r.requestId, "建")); tick()
        assertEquals(1, marks(t).count { it == "FIRST_TOKEN" }); assertFalse("FIRST_READABLE_HINT" in marks(t))
        callbacks[0](ModelStreamEvent.TextDelta(r.requestId, "议。")); tick()
        assertEquals(1, marks(t).count { it == "FIRST_READABLE_HINT" }); assertFalse("DISPLAY_ACK" in marks(t))
        val h = outputs.single(); assertEquals("a", h.sourceTurnId); assertEquals(0, h.priority)
        assertNull(h.optionalSpeech); assertEquals(clock.nowNanos + 5_000_000_000, h.expiresAtNanos)
        for (s in listOf(OutputStatus.Accepted, OutputStatus.Queued)) p.handle(OutputStateEvent(envelope(), h.hintId, s))
        assertFalse("DISPLAY_ACK" in marks(t))
        p.handle(OutputStateEvent(envelope(Generation(1)), h.hintId, OutputStatus.Displayed))
        p.handle(OutputStateEvent(envelope(), "old", OutputStatus.Displayed))
        repeat(2) { p.handle(OutputStateEvent(envelope(), h.hintId, OutputStatus.Displayed)) }
        assertEquals(1, marks(t).count { it == "DISPLAY_ACK" })
        assertFalse(t.toJson().contains("private-session-sentinel"))
    }
    @Test fun `turn budget expires bounds profile deadline and saturates arithmetic`() {
        val p = plugin(budget = 1000); p.handle(transcript()); help(p)
        assertEquals(2_000_000_000, requests.single().deadlineNanos)
        clock.advance(1_000_000_000); help(p); assertEquals(1, requests.size)
        p.deactivate(DeactivationReason.Stopped)
        clock.nowNanos = Long.MAX_VALUE - 100; lookup = profile(Long.MAX_VALUE)
        val q = plugin(); q.handle(transcript()); help(q); assertEquals(Long.MAX_VALUE, requests.last().deadlineNanos)
    }
    @Test fun `synchronous displayed during submit is retained only after accepted admission`() {
        val t = trace(); val p = plugin(trace = t)
        duringSubmit = { h -> p.handle(OutputStateEvent(envelope(), h.hintId, OutputStatus.Displayed)) }
        p.handle(transcript()); help(p)
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "建议。")); tick()
        assertEquals(1, marks(t).count { it == "DISPLAY_ACK" })
        assertTrue(marks(t).indexOf("FIRST_READABLE_HINT") < marks(t).indexOf("DISPLAY_ACK"))
        submissionResult = OutputSubmission.Rejected; help(p)
        callbacks[1](ModelStreamEvent.TextDelta(requests[1].requestId, "建议。")); tick()
        assertEquals(1, marks(t).count { it == "DISPLAY_ACK" })
    }
    @Test fun `default host policy denies even explicit synthetic intent`() {
        val p = DiagnosticTextPlugin(clock, scheduler, com.sheen.rokid.output.TraceRecorder(), { lookup })
        p.configure(config(mode = "synthetic")); p.activate(context); p.handle(transcript()); help(p)
        assertTrue(requests.isEmpty())
    }
    @Test fun `new turn during output acquisition cannot admit old scheduled output`() {
        val p = plugin(); p.handle(transcript()); help(p)
        duringOutputLookup = { duringOutputLookup = null; p.handle(transcript("b", 200)) }
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "旧建议。")); tick()
        assertTrue(outputs.isEmpty()); assertEquals(1, handles[0].cancelCount)
    }
    @Test fun `new turn during model acquisition cannot upload the stale final`() {
        val p = plugin(); p.handle(transcript())
        duringModelLookup = { duringModelLookup = null; p.handle(transcript("b", 200)) }
        help(p); assertTrue(requests.isEmpty())
    }
    @Test fun `admission returning after replacement clears only the old hint`() {
        val t = trace(); val p = plugin(trace = t); p.handle(transcript()); help(p)
        duringSubmit = { duringSubmit = null; p.handle(transcript("b", 200)) }
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "旧建议。")); tick()
        assertEquals(listOf("a"), clears)
        assertEquals(listOf(outputs.first().hintId), hintClears)
        assertFalse("FIRST_READABLE_HINT" in marks(t))
    }

    @Test fun `stale same turn admission cannot clear replacement request hint`() {
        val p = plugin(); p.handle(transcript()); help(p)
        duringSubmit = {
            duringSubmit = null
            help(p)
            callbacks[1](ModelStreamEvent.TextDelta(requests[1].requestId, "新建议。"))
            tick()
        }
        callbacks[0](ModelStreamEvent.TextDelta(requests[0].requestId, "旧建议。")); tick()
        assertEquals(listOf("a"), clears)
        assertEquals(listOf(outputs.first().hintId), hintClears)
        assertEquals(listOf("旧建议。", "新建议。"), outputs.map { it.text })
    }

    @Test fun `tool events are ignored without ending the turn or emitting content`() {
        val t = trace(); val p = plugin(trace = t)
        duringRequest = { r, cb ->
            cb(ModelStreamEvent.ToolCallDelta(r.requestId, "fc_1", "record_note", "{\"title\":\"回电话\"}"))
            cb(ModelStreamEvent.ToolCallTerminal(r.requestId, "fc_1", "call_1", "record_note", "{\"title\":\"回电话\"}"))
            cb(ModelStreamEvent.TextDelta(r.requestId, "建议。"))
            cb(ModelStreamEvent.Terminal(r.requestId, ModelEnd.COMPLETED, null, null))
        }
        p.handle(transcript()); help(p); tick()
        // The diagnostic plugin drives no tools, so a tool event must not produce a hint by itself.
        assertEquals(listOf("建议。"), outputs.map { it.text })
    }
}
