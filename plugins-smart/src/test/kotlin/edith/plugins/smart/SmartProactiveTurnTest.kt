package edith.plugins.smart

import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.ContextChanged
import edith.contracts.OutputStateEvent
import edith.contracts.OutputStatus
import edith.contracts.TranscriptEvent
import edith.contracts.UserAction
import edith.plugin.InputPart
import edith.plugin.ModelEnd
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.OutputSubmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Task 8: one proactive model request, `propose_hint` as the only control channel, silence otherwise.
 *
 * The shape under test is the ruling (spec §16.1#5): a **single** `gateway.turn` whose tool call *is* the decision to
 * speak. The tool is never executed and never fed back, so an unwelcome proactive turn cannot act - it can only say
 * one sentence. Every test here drives the real plugin through `handle`, with the fake transport standing in for the
 * model, and asserts on what the plugin did to the collaborators that matter: the model client, the action executor
 * and the output sink.
 */
class SmartProactiveTurnTest {

    /** A provider that always answers the same snapshot, so "the request sampled it" is observable. */
    private class FixedContextSignals(private val values: Map<String, String>) : edith.plugin.ContextSignals {
        override fun snapshot() = edith.plugin.ContextSnapshot(1L, values)
        override fun listen(listener: (edith.plugin.ContextSnapshot) -> Unit) =
            object : edith.plugin.CancellationHandle { override fun cancel() = Unit }
    }

    /** A provider the test can change, so "the host disarmed mid-session" is expressible. */
    private class MutableContextSignals(var values: Map<String, String>) : edith.plugin.ContextSignals {
        override fun snapshot() = edith.plugin.ContextSnapshot(1L, values)
        override fun listen(listener: (edith.plugin.ContextSnapshot) -> Unit) =
            object : edith.plugin.CancellationHandle { override fun cancel() = Unit }
    }

    /** Swaps [P3Log]'s sink for a collector and always restores it. */
    private fun <T> withLines(block: (MutableList<String>) -> T): T {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block(lines)
        } finally {
            P3Log.sink = previous
        }
    }

    /** The host's perception signal, which is the plugin's own arming evidence (spec §16.2). */
    private fun arm(rig: SmartTestRig, label: AcousticLabel = AcousticLabel.Speech) {
        rig.plugin.handle(AcousticEvent(rig.envelope(), label))
    }

    /** A `propose_hint` call followed by the terminal, as the transport delivers it. */
    private fun proposing(text: String = "现在正适合提醒他一句"): (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit =
        { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"$text"}""", requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

    private fun rigWithProposal(text: String = "现在正适合提醒他一句"): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = proposing(text)
        return rig
    }

    // ---- the one request -------------------------------------------------------------------------------------------

    @Test
    fun `an armed non wake utterance sends exactly one request whose tool call becomes the hint`() {
        val rig = rigWithProposal("现在正适合提醒他一句")

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

        assertEquals(1, rig.model.requests.size, "one trigger, one request")
        assertEquals(1, rig.output.hints.size, "exactly one hint must be submitted")
        assertEquals("现在正适合提醒他一句", rig.output.hints.single().text)
        assertNull(rig.output.hints.single().optionalSpeech, "the proactive hint is visual only")
    }

    @Test
    fun `the tool arguments are the hint text and the line never carries that text`() {
        val marker = "提示正文标记-5150"
        val rig = rigWithProposal(marker)

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            assertEquals(marker, rig.output.hints.single().text)
            val hintLine = lines.single { it.startsWith("P3_PROACTIVE hint") }
            assertFalse(hintLine.contains(marker), "the hint's text must never reach the log: $hintLine")
            assertTrue(hintLine.contains("chars="), hintLine)
        }
    }

    @Test
    fun `the trigger itself is reported once with the context size and the transcript size`() {
        val rig = rigWithProposal()

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            val requestLine = lines.single { it.startsWith("P3_PROACTIVE request") }
            assertTrue(requestLine.contains("ctx="), requestLine)
            assertTrue(requestLine.contains("chars="), requestLine)
            assertFalse(requestLine.contains("这句话"), "the request line carries sizes, never the transcript")
        }
    }

    // ---- silence ---------------------------------------------------------------------------------------------------

    @Test
    fun `a model that only answers with text produces silence and no hint`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "我不知道该不该提醒。") }

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            assertTrue(rig.output.hints.isEmpty(), "an answer without the tool call is not a hint")
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE silence reason=NO_CALL") }, lines.toString())
        }
    }

    @Test
    fun `unparsable tool arguments are silence and never a second request`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("c1", "propose_hint", "{not json", requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            assertTrue(rig.output.hints.isEmpty())
            assertEquals(1, rig.model.requests.size, "the proactive turn is one request, never a retry loop")
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE silence reason=UNPARSABLE") }, lines.toString())
        }
    }

    @Test
    fun `the request offers exactly the propose hint tool and no action at all`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.actions.descriptors += smartAction("open_app")
        rig.actions.descriptors += smartAction("compose_note")
        rig.model.onRequest = proposing()

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

        val tools = rig.model.requests.single().tools.map { it.name }
        assertEquals(listOf("propose_hint"), tools)
        assertTrue(rig.actions.descriptors.none { it.name in tools },
            "a proactive request must not offer any action tool: $tools")
        assertTrue(rig.actions.executed.isEmpty(), "nothing may be executed without the wearer asking")
    }

    @Test
    fun `a hint longer than the display bound is silence rather than a clipped screen`() {
        val tooLong = rigWithProposal("字".repeat(SmartPrompts.PROACTIVE_HINT_MAX_CHARS + 1))

        withLines { lines ->
            arm(tooLong)
            tooLong.plugin.handle(TranscriptEvent(tooLong.transcript("这句话是不是值得提醒一下")))

            assertTrue(tooLong.output.hints.isEmpty(), "an over-long hint must not be submitted to be clipped")
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE silence reason=TOO_LONG") }, lines.toString())
        }
    }

    @Test
    fun `a hint at the display bound is still delivered`() {
        val atBound = rigWithProposal("字".repeat(SmartPrompts.PROACTIVE_HINT_MAX_CHARS))

        arm(atBound)
        atBound.plugin.handle(TranscriptEvent(atBound.transcript("这句话是不是值得提醒一下")))

        assertEquals(1, atBound.output.hints.size)
    }

    // ---- concurrency and cancellation ------------------------------------------------------------------------------

    @Test
    fun `a wake turn that is still running makes the proactive trigger give way`() {
        val rig = SmartTestRig()
        rig.activate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.model.onRequest = { request, listener ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            streamText(request, listener, "答案。")
        }
        rig.plugin.handle(rig.wakeEvent())
        val worker = Thread { rig.plugin.handle(TranscriptEvent(rig.transcript("第一句话先回答我", turnId = "asr-1"))) }
        worker.isDaemon = true
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS), "the wake turn must be in flight")

        try {
            withLines { lines ->
                arm(rig)
                rig.plugin.handle(TranscriptEvent(rig.transcript("第二句话是不是值得提醒", turnId = "asr-2")))

                assertEquals(1, rig.model.requests.size,
                    "a proactive turn must not run beside the in-flight wake turn")
                assertTrue(lines.any { it == "P3_PROACTIVE skip reason=BUSY" }, lines.toString())
            }
        } finally {
            release.countDown()
            worker.join(5_000)
        }
    }

    @Test
    fun `a wake turn that has finished does not block the next proactive trigger`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        // The wake turn runs to completion. Its turn object stays installed (its hint may still be acknowledged), so
        // "a turn exists" must not be read as "a turn is in flight".
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句话先回答我", turnId = "asr-1")))
        assertEquals(1, rig.model.requests.size)

        rig.model.onRequest = proposing()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句话是不是值得提醒", turnId = "asr-2")))

        assertEquals(2, rig.model.requests.size, "a finished turn must not make the next trigger busy")
    }

    @Test
    fun `stop cancels the in flight proactive request and nothing is shown`() {
        val rig = SmartTestRig()
        rig.activate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.model.onRequest = { request, listener ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            streamText(request, listener, "迟到的建议。")
        }
        arm(rig)

        val worker = Thread { rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下"))) }
        worker.isDaemon = true
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS), "the proactive run must reach the model client")

        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))
        release.countDown()
        worker.join(5_000)

        assertTrue(rig.model.handles.any { it.cancelled }, "Stop must cancel the transport")
        assertTrue(rig.output.hints.isEmpty(), "a cancelled proactive turn must say nothing")
    }

    // ---- a rejected display ----------------------------------------------------------------------------------------

    @Test
    fun `a rejected hint is not retried and is reported once`() {
        val rig = rigWithProposal()
        rig.output.submission = OutputSubmission.Rejected

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            assertEquals(1, rig.model.requests.size, "a rejected display must not cause a retry")
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE hint rejected") }, lines.toString())
            assertEquals(0, rig.displayedAcks)
        }
    }

    // ---- an unarmed session ----------------------------------------------------------------------------------------

    @Test
    fun `without a perception signal the plugin refuses before any request`() {
        val rig = rigWithProposal()

        withLines { lines ->
            // No acoustic event at all: the host produced no perception signal, so the plugin's own arming evidence
            // is absent even though a final transcript arrived.
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            assertTrue(rig.model.requests.isEmpty(), "an unarmed plugin must not reach the model")
            assertTrue(lines.any { it == "P3_PROACTIVE skip reason=DISARMED" }, lines.toString())
        }
    }

    // ---- the configured thresholds (Task 9) ------------------------------------------------------------------------

    @Test
    fun `a configured high sensitivity admits a short utterance the default refuses`() {
        val short = "提醒我一下"
        assertEquals(5, short.length)

        val strict = SmartTestRig()
        strict.activate()
        strict.model.onRequest = proposing()
        withLines { lines ->
            arm(strict)
            strict.plugin.handle(TranscriptEvent(strict.transcript(short)))
            assertTrue(strict.model.requests.isEmpty(), "MEDIUM refuses a five-character utterance")
            assertTrue(lines.any { it == "P3_PROACTIVE skip reason=SHORT" }, lines.toString())
        }

        val eager = SmartTestRig()
        eager.activate(proactiveSensitivity = "HIGH")
        eager.model.onRequest = proposing()
        arm(eager)
        eager.plugin.handle(TranscriptEvent(eager.transcript(short)))
        assertEquals(1, eager.model.requests.size, "HIGH admits the same five characters")
    }

    @Test
    fun `a configured cooldown is what bounds the next trigger`() {
        val rig = SmartTestRig()
        rig.activate(proactiveCooldownMillis = "5000")
        rig.model.onRequest = proposing()

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))
        assertEquals(1, rig.model.requests.size)

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("另一句话是不是值得提醒", turnId = "asr-2")))
            assertEquals(1, rig.model.requests.size,
                "inside the configured five seconds the second trigger gives way")
            assertTrue(lines.any { it == "P3_PROACTIVE skip reason=COOLDOWN" }, lines.toString())
        }

        // Past the configured window - not past the un-configured sixty seconds - it is admitted again.
        rig.clock.advance(6_000_000_000L)
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("第三句话是不是值得提醒", turnId = "asr-3")))
        assertEquals(2, rig.model.requests.size, "past the configured cooldown it is admitted again")
    }

    @Test
    fun `the request samples the context provider so its keys reach the model`() {
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(edith.plugin.Capability.Context to
            FixedContextSignals(mapOf("time.local" to "20:05", "time.period" to "evening"))))
        rig.model.onRequest = proposing()

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertTrue(input.contains("20:05"), input)
            assertTrue(input.contains("evening"), input)
            // Two context values plus the acoustic label: the count has to reflect what the message carried.
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE request ctx=3") }, lines.toString())
        }
    }

    // ---- the review pass: the switch must be able to disarm, and the ACK/TTL races ---------------------------------

    @Test
    fun `turning the arming switch off mid session stops the next proactive request`() {
        val signals = MutableContextSignals(mapOf("proactive.armed" to "true"))
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(edith.plugin.Capability.Context to signals))
        rig.model.onRequest = proposing()

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句值得提醒的话", turnId = "asr-1")))
        assertEquals(1, rig.model.requests.size, "the armed session must still work")

        // The user turns 「主动辅助」 off mid-session. The host stops producing perception, but transcripts keep
        // arriving (local transcription is deliberately unaffected), so the plugin has to read the *current* arm
        // state rather than trusting the sticky "I saw an acoustic event once" flag.
        signals.values = mapOf("proactive.armed" to "false")
        rig.clock.advance(120_000_000_000L)

        withLines { lines ->
            rig.plugin.handle(TranscriptEvent(rig.transcript("第二句值得提醒的话", turnId = "asr-2")))

            assertEquals(1, rig.model.requests.size, "a disarmed session must not reach the model")
            assertTrue(lines.any { it == "P3_PROACTIVE skip reason=DISARMED" }, lines.toString())
        }
    }

    @Test
    fun `a second trigger while a proactive request is in flight gives way`() {
        val rig = SmartTestRig()
        rig.activate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.model.onRequest = { request, listener ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            proposing()(request, listener)
        }
        arm(rig)

        val worker = Thread { rig.plugin.handle(TranscriptEvent(rig.transcript("第一句值得提醒的话", turnId = "asr-1"))) }
        worker.isDaemon = true
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS), "the first proactive request must be in flight")

        try {
            withLines { lines ->
                rig.plugin.handle(TranscriptEvent(rig.transcript("第二句值得提醒的话", turnId = "asr-2")))

                assertEquals(1, rig.model.requests.size, "only one proactive request may be in flight at a time")
                assertTrue(lines.any { it == "P3_PROACTIVE skip reason=BUSY" }, lines.toString())
            }
        } finally {
            release.countDown()
            worker.join(5_000)
        }
    }

    @Test
    fun `an acknowledgement that arrives during the submit is admitted once`() {
        val rig = rigWithProposal()
        rig.output.onSubmit = { assistance ->
            rig.plugin.handle(OutputStateEvent(rig.envelope(), assistance.hintId, OutputStatus.Displayed))
        }

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

        assertEquals(1, rig.displayedAcks, "the in-submit ACK must be remembered, not lost")
    }

    @Test
    fun `a repeated acknowledgement is admitted only once`() {
        val rig = rigWithProposal()
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))
        val hintId = rig.output.hints.single().hintId

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertEquals(1, rig.displayedAcks, "a repeated Displayed ACK is not a second display")
    }

    @Test
    fun `a late reply still gets its full visible window`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            // The reply lands seven seconds into the eight-second request deadline.
            rig.clock.advance(7_000_000_000L)
            proposing()(request, listener)
        }
        arm(rig)

        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

        val hint = rig.output.hints.single()
        val remaining = hint.expiresAtNanos - rig.clock.nowNanos()
        assertTrue(remaining > 7_000_000_000L,
            "the visible window must be measured from the submit, not from the trigger: remaining=$remaining")
    }

    @Test
    fun `a context key the provider stopped reporting is not carried from an earlier change`() {
        val signals = MutableContextSignals(mapOf("proactive.armed" to "true", "app.foreground" to "com.stale"))
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(edith.plugin.Capability.Context to signals))
        rig.model.onRequest = proposing()
        // The host delivered a change that included the foreground app...
        rig.plugin.handle(ContextChanged(rig.envelope(), 7L,
            mapOf("proactive.armed" to "true", "app.foreground" to "com.stale")))
        // ...and then the user revoked usage access: the provider stops reporting it. Found on the K40 - the request
        // still carried the revoked value, because the remembered change map was merged into the fresh snapshot.
        signals.values = mapOf("proactive.armed" to "true", "time.local" to "21:05")

        withLines { lines ->
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertFalse(input.contains("com.stale"), "a revoked context value must not survive in the request: $input")
            assertTrue(input.contains("21:05"), "the fresh snapshot is what the request carries: $input")
            // Two fresh values plus the acoustic label; the stale key and the foreground double-count are both gone.
            assertTrue(lines.any { it.startsWith("P3_PROACTIVE request ctx=3") }, lines.toString())
        }
    }

    // ---- the request's context -------------------------------------------------------------------------------------

    @Test
    fun `the request lifts the context message into the input and keeps the tool table minimal`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = proposing()

        arm(rig)
        rig.plugin.handle(ContextChanged(rig.envelope(), 7L, mapOf("time.period" to "afternoon")))
        rig.plugin.handle(TranscriptEvent(rig.transcript("这句话是不是值得提醒一下")))

        val request = rig.model.requests.single()
        val input = request.input.filterIsInstance<InputPart.Text>().single().text
        assertTrue(input.contains("这句话是不是值得提醒一下"), "the utterance has to reach the model")
        assertTrue(input.contains("afternoon"), "the context the host delivered has to reach the model")
        assertEquals(1, request.tools.size)
    }
}
