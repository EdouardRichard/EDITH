package com.sheen.rokid.phone.plugin.smarthelper.confirmation
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeClock
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeScheduler

import com.sheen.rokid.agent.ConfirmationGate
import com.sheen.rokid.agent.ConfirmationOutcome
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConfirmationChannel
import kotlin.concurrent.thread
import kotlin.test.*
import java.util.concurrent.TimeUnit

/**
 * The plugin half of spec §17.4: the loop blocks on a question, the host *presents* it, and the answer arrives either as
 * a `ConfirmationResult` event (a phone tap) or as a strict voice phrase in a **later** transcript segment. The gate is
 * the authority on the timeout, and silence is never consent.
 */
class SmartConfirmationTest {

    private val clock = SmartFakeClock()
    private val scheduler = SmartFakeScheduler(clock)

    private class FakeChannel(var canServe: Boolean = true) : ConfirmationChannel {
        val presented = mutableListOf<Triple<String, String, Long>>()
        /** 0.3 Phase 4B (B7): the action names the host channel was told, one per 4-arg `present`. */
        val presentedTools = mutableListOf<String>()
        /** B7: every settlement the plugin reported to the host: (request id, decision, answer channel). */
        val settled = mutableListOf<Triple<String, String, String?>>()
        var cancelled = 0
        var failure: RuntimeException? = null

        override fun available(): Boolean = canServe

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            failure?.let { throw it }
            presented += Triple(requestId, summary, timeoutMillis)
            return object : CancellationHandle { override fun cancel() { cancelled++ } }
        }

        override fun present(requestId: String, summary: String, timeoutMillis: Long, tool: String): CancellationHandle {
            presentedTools += tool
            return present(requestId, summary, timeoutMillis)
        }

        override fun settle(requestId: String, decision: String, answerChannel: String?): Boolean {
            settled += Triple(requestId, decision, answerChannel)
            return true
        }
    }

    private fun confirmation(channel: FakeChannel, timeoutMillis: Long = 10_000L) = SmartConfirmation(
        channel = channel,
        gate = ConfirmationGate(clock, scheduler),
        timeoutMillis = timeoutMillis,
    )

    private fun revision(text: String, turnId: String = "asr-2", final: Boolean = true) = TranscriptRevision(
        SmartTestRig().envelope(), turnId, 1, text, final, clock.nowNanos(), clock.nowNanos(),
    )

    private fun tap(requestId: String = "c1", approved: Boolean = true) =
        ConfirmationResult(SmartTestRig().envelope(), requestId, approved)

    /** Runs the blocking question on its own thread, the way the turn thread would. */
    private fun ask(subject: SmartConfirmation, requestId: String = "c1", summary: String = "卸载探针 App") =
        Ask(subject, requestId, summary)

    private class Ask(subject: SmartConfirmation, requestId: String, summary: String) {
        private val worker = thread(name = "confirmation-test") { result = subject.request(requestId, summary) }
        @Volatile var result: ConfirmationOutcome? = null
        init { Thread.sleep(20) }
        fun join(): ConfirmationOutcome? { worker.join(2_000); return result }
    }

    @Test fun `a phone tap approves and the source is PHONE`() {
        val channel = FakeChannel()
        val subject = confirmation(channel)
        val ask = ask(subject)

        assertTrue(subject.onConfirmationResult(tap("c1", true)), "the pending question must be settled by the tap")

        assertEquals(ConfirmationOutcome(true, ConfirmationSource.PHONE), ask.join())
        assertEquals(listOf(Triple("c1", "卸载探针 App", 10_000L)), channel.presented)
    }

    @Test fun `a strict voice phrase approves and the source is VOICE`() {
        val subject = confirmation(FakeChannel())
        val ask = ask(subject)

        assertTrue(subject.onTranscript(revision("确认")))

        assertEquals(ConfirmationOutcome(true, ConfirmationSource.VOICE), ask.join())
    }

    @Test fun `a denial phrase denies and the source is VOICE`() {
        val subject = confirmation(FakeChannel())
        val ask = ask(subject)

        listOf("拒绝", "取消").forEach { phrase ->
            val fresh = confirmation(FakeChannel())
            val pending = ask(fresh)
            fresh.onTranscript(revision(phrase))
            assertEquals(ConfirmationOutcome(false, ConfirmationSource.VOICE), pending.join())
        }
        subject.onTranscript(revision("取消"))
        assertEquals(ConfirmationOutcome(false, ConfirmationSource.VOICE), ask.join())
    }

    @Test fun `嗯 and 好 are not answers at all`() {
        val subject = confirmation(FakeChannel())
        val ask = ask(subject)

        assertFalse(subject.onTranscript(revision("嗯")))
        assertFalse(subject.onTranscript(revision("好")))
        assertFalse(subject.onTranscript(revision("可以")))
        assertNull(ask.result, "an unlisted phrase must leave the question pending")

        clock.advance(11_000_000_000L); scheduler.runDue()
        assertEquals(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT), ask.join())
    }

    @Test fun `only a final revision can answer`() {
        val subject = confirmation(FakeChannel())
        val ask = ask(subject)

        assertFalse(subject.onTranscript(revision("确认", final = false)))
        clock.advance(11_000_000_000L); scheduler.runDue()
        assertEquals(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT), ask.join())
    }

    @Test fun `a sentence that merely contains an answer word is not an exact answer`() {
        // Spec §5.4: the voice channel accepts only an exact ASR-final word and never guesses from a sentence. Every
        // one of these is a plausible reply that must leave the question open - only the exact word may settle it.
        val subject = confirmation(FakeChannel())
        val ask = ask(subject)

        listOf("我觉得可以允许", "不允许", "拒绝吧", "好的确认一下", "允许吗").forEach { sentence ->
            assertFalse(subject.onTranscript(revision(sentence)), "'$sentence' is not an exact answer")
        }
        assertNull(ask.result, "an inexact phrase must leave the question pending")

        assertTrue(subject.onTranscript(revision(" 允许 ")), "the exact word still settles it")
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.VOICE), ask.join())
    }

    @Test fun `the segment that carried the command cannot answer its own question`() {
        val subject = confirmation(FakeChannel())
        subject.excludeSegment("asr-1")
        val ask = ask(subject)

        assertFalse(subject.onTranscript(revision("确认", turnId = "asr-1")),
            "the utterance that asked for the action must not confirm it")
        assertTrue(subject.onTranscript(revision("确认", turnId = "asr-2")), "a later segment may confirm it")
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.VOICE), ask.join())
    }

    @Test fun `silence times out as denied with source TIMEOUT`() {
        val subject = confirmation(FakeChannel())
        val ask = ask(subject)

        clock.advance(9_999_000_000L); scheduler.runDue()
        assertNull(ask.result, "the question must survive until its window closes")
        clock.advance(2_000_000L); scheduler.runDue()

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT), ask.join())
    }

    @Test fun `急停 cancels a pending question as denied`() {
        val channel = FakeChannel()
        val subject = confirmation(channel)
        val ask = ask(subject)

        subject.cancelAll()

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.CANCEL), ask.join())
        assertEquals(1, channel.cancelled, "the host's question must be withdrawn too")
    }

    @Test fun `a failing channel is unavailable rather than a silent approval`() {
        val channel = FakeChannel()
        channel.failure = RuntimeException("no display")
        val subject = confirmation(channel)
        val ask = ask(subject)

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE), ask.join())
        assertFalse(subject.onConfirmationResult(tap("c1", true)), "a settled question takes no second answer")
    }

    @Test fun `the plugin without a host channel answers unavailable`() {
        // R52: "no channel" and "the wearer cancelled" are different answers; only the latter is a denial.
        val subject = SmartConfirmation(channel = null, gate = ConfirmationGate(clock, scheduler))

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE),
            subject.request("c1", "卸载探针 App"))
        assertEquals(0, subject.pendingCount, "an unasked question must not stay armed")
    }

    // ------------------- 0.3 Phase 4B (B4): an unavailable surface is refused at once, not after the window

    @Test fun `a channel that reports itself unavailable makes the plugin unavailable at once`() {
        val channel = FakeChannel().apply { canServe = false }
        val subject = confirmation(channel)

        assertFalse(subject.available(), "the plugin's availability must delegate to the host channel")
        assertFalse(subject.canAsk, "an attached but unusable channel is not somebody to ask")
        val startedNanos = System.nanoTime()
        val outcome = subject.request("c1", "卸载探针 App")
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE), outcome)
        assertTrue(channel.presented.isEmpty(), "an unavailable channel must never be asked to present")
        assertEquals(0, subject.pendingCount, "nothing may stay armed behind an immediate refusal")
        assertTrue(elapsedMillis < 1_000, "the refusal must not wait for the 120 s window, waited ${elapsedMillis}ms")
    }

    @Test fun `availability follows the channel attached at activation`() {
        val subject = SmartConfirmation(channel = null, gate = ConfirmationGate(clock, scheduler))
        assertFalse(subject.available(), "a plugin nobody has attached a host channel to cannot ask")
        assertFalse(subject.canAsk)

        val channel = FakeChannel()
        subject.attach(channel)
        assertTrue(subject.available(), "the attached channel can present, so the plugin can ask")
        assertTrue(subject.canAsk)

        channel.canServe = false
        assertFalse(subject.available(), "the surface check is live, not cached at attach time")
        assertFalse(subject.canAsk)
    }

    @Test fun `a channel attached at activation can present a question the plugin was built without`() {
        val channel = FakeChannel()
        val subject = SmartConfirmation(channel = null, gate = ConfirmationGate(clock, scheduler))

        subject.attach(channel)
        val ask = ask(subject)
        assertTrue(subject.onConfirmationResult(tap("c1", true)))

        assertEquals(ConfirmationOutcome(true, ConfirmationSource.PHONE), ask.join())
        assertEquals(1, channel.presented.size)
    }

    @Test fun `a second question never replaces a live one`() {
        val subject = confirmation(FakeChannel())
        val first = ask(subject, requestId = "c1")

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.CANCEL), subject.request("c2", "另一个动作"),
            "the loop runs one turn at a time; a second question is refused, never queued in front of the first")
        assertEquals(1, subject.pendingCount)

        subject.onConfirmationResult(tap("c1", true))
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.PHONE), first.join())
    }

    @Test fun `the owner set the question window to 120 s`() {
        // 2026-10-10 owner directive, after three device attempts settled as `timeout` because a person has no
        // realistic chance to notice a notification and act on it inside 10 s. Pinned here because it is a product
        // decision, not an implementation detail.
        assertEquals(120_000L, SmartConfirmation.DEFAULT_TIMEOUT_MILLIS)
    }

    @Test fun `a gate that never settles still releases the question when the latch gives up`() {
        // The deadline thread is the gate's only way to settle a silent question (P3.3 review, Minor 1). If it is lost,
        // `request` returns on its own slack - and it must clear the slot on the way out, or every later question is
        // refused as BUSY and the only symptom is "confirmation stopped working".
        val channel = FakeChannel()
        val log = mutableListOf<String>()
        val gate = ConfirmationGate(clock, scheduler, timeoutNanos = 50_000_000L)   // scheduler never runs due
        val subject = SmartConfirmation(channel, gate, timeoutMillis = 50L, log = { log += it })
        val first = ask(subject, requestId = "c1")

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT), first.join())
        assertEquals(0, subject.pendingCount, "the pending slot must be released, not left armed forever")
        assertEquals(1, channel.cancelled, "the host's card must come down when the fallback gives up")
        assertTrue(log.any { "request=c1" in it && "source=timeout" in it },
            "the fallback must log the same timeout line the gate would")

        val second = ask(subject, requestId = "c2")
        assertTrue(subject.onConfirmationResult(tap("c2", true)), "the next question must be answerable, not BUSY")
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.PHONE), second.join())
    }

    @Test fun `a gate that settles after the slack gave up adds nothing to a released question`() {
        // The residual the leftover-sweep review asked to pin: when the fallback releases the slot, the gate may still
        // hold its own entry (it is cancelled by its own deadline, and here that deadline never ran). A *late* settle
        // from the gate must therefore be a no-op on the plugin side: the question was already answered to the user as
        // a denial, and a slow "yes" cannot retroactively become one.
        val channel = FakeChannel()
        val log = mutableListOf<String>()
        val gate = ConfirmationGate(clock, scheduler, timeoutNanos = 50_000_000L)
        val subject = SmartConfirmation(channel, gate, timeoutMillis = 50L, log = { log += it })
        val ask = ask(subject, requestId = "c1")

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT), ask.join())

        // The fallback must have released and logged **before** the gate gets there: these two are what make the late
        // settle below a no-op rather than the real settlement.
        assertEquals(0, subject.pendingCount, "the fallback already released the slot")
        assertEquals(1, log.count { "request=c1" in it }, "the fallback already told the user")

        assertTrue(gate.resolve("c1", approved = true), "the gate still held its own entry, so it settles it now")

        assertEquals(1, log.count { "request=c1" in it }, "exactly one settlement, exactly one line")
        assertEquals(0, subject.pendingCount)

        // Observable proof that the late "yes" changed nothing on the plugin's side: the slot is still free for the
        // next question. (Asserting on `ask.result` here would prove nothing - it is an immutable value the worker
        // already returned; the leftover review of this round caught that as an unfalsifiable assertion.)
        val next = ask(subject, requestId = "c2")
        assertTrue(subject.onConfirmationResult(tap("c2", true)))
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.PHONE), next.join())
    }

    // ------------------------- 0.3 Phase 4B (B7): the count-only telemetry's two plugin-owned inputs

    @Test fun `the action name travels with the question to the host channel`() {
        val channel = FakeChannel()
        val subject = confirmation(channel)
        val worker = thread(name = "confirmation-tool-test") {
            subject.request("c1", "卸载探针 App", "app.uninstall")
        }
        Thread.sleep(20)

        assertTrue(subject.onConfirmationResult(tap("c1", true)))
        worker.join(2_000)

        assertEquals(
            listOf("app.uninstall"),
            channel.presentedTools,
            "the host needs the wire name to count a confirmation per tool; it is never the summary",
        )
    }

    @Test fun `the plugin reports the decision it owns and the voice answer channel to the host`() {
        // A phone tap: the host's own presenter already recorded it, so this report carries no answer channel.
        val tapChannel = FakeChannel()
        val tapSubject = confirmation(tapChannel)
        val tapAsk = ask(tapSubject)
        assertTrue(tapSubject.onConfirmationResult(tap("c1", true)))
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.PHONE), tapAsk.join())
        assertEquals(listOf(Triple("c1", "allow", null as String?)), tapChannel.settled)

        // A voice phrase: the one answer channel that never presents the question, so the plugin must name it.
        val voiceChannel = FakeChannel()
        val voiceSubject = confirmation(voiceChannel)
        val voiceAsk = ask(voiceSubject)
        assertTrue(voiceSubject.onTranscript(revision("确认")))
        assertEquals(ConfirmationOutcome(true, ConfirmationSource.VOICE), voiceAsk.join())
        assertEquals(listOf<Triple<String, String, String?>>(Triple("c1", "allow", "voice")), voiceChannel.settled)

        // Silence: the gate owns the timeout, so the host is told `timeout` rather than inferring it from its timer.
        val timeoutChannel = FakeChannel()
        val timeoutSubject = confirmation(timeoutChannel)
        val timeoutAsk = ask(timeoutSubject)
        clock.advance(11_000_000_000L); scheduler.runDue()
        assertEquals(ConfirmationOutcome(false, ConfirmationSource.TIMEOUT), timeoutAsk.join())
        assertEquals(listOf(Triple("c1", "timeout", null as String?)), timeoutChannel.settled)

        // A denial is a `deny`, and a channel that cannot present is `unavailable` - never a silence that looks like
        // consent.
        val denyChannel = FakeChannel()
        val denySubject = confirmation(denyChannel)
        val denyAsk = ask(denySubject)
        assertTrue(denySubject.onConfirmationResult(tap("c1", false)))
        assertEquals(ConfirmationOutcome(false, ConfirmationSource.PHONE), denyAsk.join())
        assertEquals(listOf(Triple("c1", "deny", null as String?)), denyChannel.settled)

        val unavailableChannel = FakeChannel().apply { failure = RuntimeException("no display") }
        assertEquals(
            ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE),
            confirmation(unavailableChannel).request("c9", "卸载探针 App"),
        )
        assertEquals(listOf(Triple("c9", "unavailable", null as String?)), unavailableChannel.settled)
    }
}
