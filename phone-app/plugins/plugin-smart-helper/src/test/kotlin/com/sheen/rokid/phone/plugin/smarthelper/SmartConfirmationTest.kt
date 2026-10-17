package com.sheen.rokid.phone.plugin.smarthelper

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

    private class FakeChannel : ConfirmationChannel {
        val presented = mutableListOf<Triple<String, String, Long>>()
        var cancelled = 0
        var failure: RuntimeException? = null

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            failure?.let { throw it }
            presented += Triple(requestId, summary, timeoutMillis)
            return object : CancellationHandle { override fun cancel() { cancelled++ } }
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

    @Test fun `a missing or failing channel is a denial rather than a silent approval`() {
        val channel = FakeChannel()
        channel.failure = RuntimeException("no display")
        val subject = confirmation(channel)
        val ask = ask(subject)

        assertEquals(ConfirmationOutcome(false, ConfirmationSource.CANCEL), ask.join())
        assertFalse(subject.onConfirmationResult(tap("c1", true)), "a settled question takes no second answer")
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
}
