package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.MonotonicClock
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SpeechPipelineTest {
    private val epoch = InputEpoch(SessionId("synthetic-session"), Generation(1), 1)
    private fun frame(index: Int, continuity: String = "c", inputEpoch: InputEpoch = epoch) = PcmFrame(
        inputEpoch, FloatArray(320), 16000, SampleRange(continuity, index * 320L, (index + 1) * 320L),
        index * 20_000_000L, (index + 1) * 20_000_000L)
    private fun envelope(frame: PcmFrame) = EventEnvelope(frame.epoch.sessionId, frame.epoch.generation,
        "synthetic-gap", frame.startNanos, frame.endNanos)
    private class Fixture(onFrameSignal: ((PcmFrame, Boolean) -> Unit)? = null,
        onReset: (() -> Unit)? = null,
        private val observe: (HostEvent) -> Unit = {}) : AutoCloseable {
        val vad = ScriptedVad()
        val asr = RecordingAsr()
        val speaker = PendingSpeaker()
        val events = CopyOnWriteArrayList<HostEvent>()
        val cancelled = CopyOnWriteArrayList<String>()
        val pipeline = SpeechPipeline(vad, asr, speaker, { events += it; observe(it) }, cancelled::add,
            onFrameSignal = onFrameSignal, onReset = onReset)
        val transcripts get() = events.filterIsInstance<TranscriptEvent>().map { it.revision }
        override fun close() { speaker.block?.countDown(); pipeline.close() }
    }

    @Test fun `ASR streams partial while a real speaker request remains pending`() = Fixture().use { f ->
        f.pipeline.accept(frame(0))
        f.speaker.awaitStarted()
        assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
        assertEquals("synthetic partial", f.transcripts.single().text)
        assertTrue(f.events.none { it is SpeakerEvent })
        f.speaker.requests.single().release()
        assertEquals(SpeakerLabel.UNKNOWN, f.events.filterIsInstance<SpeakerEvent>().single().assessment.label)
    }

    @Test fun `blocked speaker provider does not block sequential VAD and ASR worker`() = Fixture().use { f ->
        val release = CountDownLatch(1)
        f.speaker.block = release
        f.pipeline.accept(frame(0))
        f.speaker.awaitStarted()
        f.asr.hypotheses = listOf(AsrHypothesis("second partial", false))
        f.pipeline.accept(frame(1))
        assertEquals(2, f.asr.accepted.size)
        assertEquals("second partial", f.transcripts.last().text)
        release.countDown()
    }

    @Test fun `only the immediately preceding 600ms enters ASR pre roll`() = Fixture().use { f ->
        f.vad.decision = VadDecision(false, false)
        repeat(40) { f.pipeline.accept(frame(it)) }
        assertTrue(f.asr.accepted.isEmpty())
        f.vad.decision = VadDecision(true, false)
        f.pipeline.accept(frame(40))
        assertEquals(31, f.asr.accepted.size)
        assertEquals(SampleRange("c", 3200, 3520), f.asr.accepted.first())
        assertEquals(200_000_000L, f.transcripts.first().startNanos)
    }

    @Test fun `pre roll covers the vad min speech delay`() = Fixture().use { f ->
        // Silero only reports speech about 250-286 ms after the real onset (minSpeechDuration 0.25 s plus window
        // alignment), so the pre-roll must be at least that long or the first syllable never reaches the ASR.
        // Frame 30 is the real onset; the VAD first says speech on frame 42.
        f.vad.decision = VadDecision(false, false)
        repeat(42) { f.pipeline.accept(frame(it)) }
        f.vad.decision = VadDecision(true, false)
        f.pipeline.accept(frame(42))

        val onset = 30 * 320L
        val first = f.asr.accepted.first().first
        assertTrue(first <= onset - 4000, "the onset ($onset) must be inside the pre-roll, first=$first")
        f.asr.accepted.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endExclusive, b.first, "pre-roll and trigger frames must be contiguous")
        }
    }

    @Test fun `duplicate hypothesis does not increment revision and final seals turn`() = Fixture().use { f ->
        f.pipeline.accept(frame(0))
        f.pipeline.accept(frame(1))
        assertEquals(listOf(0L), f.transcripts.map { it.revision })
        f.asr.hypotheses = listOf(AsrHypothesis("synthetic final", true), AsrHypothesis("late partial", false))
        f.pipeline.accept(frame(2))
        f.vad.decision = VadDecision(false, true)
        f.pipeline.accept(frame(3))
        assertEquals(listOf(0L, 1L), f.transcripts.map { it.revision })
        assertTrue(f.transcripts.last().final)
        assertEquals("synthetic final", f.transcripts.last().text)
        assertEquals(1, f.asr.finishes)
        assertEquals(4, f.asr.accepted.size)
    }

    @Test fun `endpoint flushes streaming tail and next speech allocates another turn`() = Fixture().use { f ->
        f.pipeline.accept(frame(0))
        f.vad.decision = VadDecision(false, true)
        f.pipeline.accept(frame(1))
        assertTrue(f.transcripts.last().final)
        assertEquals(1, f.asr.finishes)
        f.vad.decision = VadDecision(true, false)
        f.pipeline.accept(frame(2))
        assertNotEquals(f.transcripts.first().turnId, f.transcripts.last().turnId)
        assertEquals(3, f.asr.accepted.size)
    }

    @Test fun `overflow resets all engines cancels active turn and fences late speaker`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val request = f.speaker.requests.single()
        val turn = f.transcripts.single().turnId
        f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW)
        assertEquals(1, f.vad.resets)
        assertEquals(1, f.asr.resets)
        assertEquals(1, f.speaker.resets)
        assertEquals(listOf(turn), f.cancelled)
        request.release(SpeakerLabel.OTHER)
        assertTrue(f.events.none { it is SpeakerEvent })
        assertEquals(GapReason.OVERFLOW, f.events.filterIsInstance<DiscontinuityEvent>().single().reason)
        f.pipeline.accept(frame(0, "new"))
        assertNotEquals(turn, f.transcripts.last().turnId)
    }

    @Test fun `real ingress overflow is processed without waiting for next PCM`() = Fixture().use { f ->
        val format = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic")
        val ingress = PcmIngress(object : MonotonicClock { override fun nowNanos() = 0L }, 20)
        val framer = PcmFramer(format)
        ingress.open(epoch, format)
        assertEquals(PcmOffer.ACCEPTED, ingress.offer(epoch, ByteArray(640)))
        framer.accept(ingress.poll()!!).forEach(f.pipeline::accept)
        val turn = f.transcripts.single().turnId
        assertEquals(PcmOffer.OVERFLOW, ingress.offer(epoch, ByteArray(641)))
        val gap = ingress.pollGap()!!
        framer.reset()
        f.pipeline.discontinuity(EventEnvelope(gap.epoch.sessionId, gap.epoch.generation,
            "synthetic-worker-gap", 20_000_000L, 20_000_000L), gap.reason)
        assertNull(ingress.poll())
        assertEquals(listOf(turn), f.cancelled)
        assertEquals(1, f.asr.resets)
        assertEquals(1, f.events.filterIsInstance<DiscontinuityEvent>().size)
    }

    @Test fun `close fences pending callbacks cancels handles and releases all engines once`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        f.pipeline.close(); f.pipeline.close()
        f.speaker.requests.single().release()
        f.pipeline.accept(frame(1))
        assertTrue(f.events.none { it is SpeakerEvent })
        assertEquals(1, f.asr.accepted.size)
        assertEquals(1, f.vad.closes); assertEquals(1, f.asr.closes); assertEquals(1, f.speaker.closes)
        assertEquals(listOf(f.transcripts.single().turnId), f.cancelled)
    }

    @Test fun `close while assess is returning cancels the late handle`() = Fixture().use { f ->
        val release = CountDownLatch(1)
        f.speaker.block = release
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        f.pipeline.close()
        release.countDown()
        // A callback after revocation is rejected even if the provider ignores interruption.
        f.speaker.requests.single().release()
        assertTrue(f.speaker.requests.single().cancellation.await(2, TimeUnit.SECONDS))
        assertTrue(f.speaker.requests.single().cancelled)
        assertTrue(f.events.none { it is SpeakerEvent })
    }

    @Test fun `continuity and epoch changes never join speech turns`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val oldRequest = f.speaker.requests.single()
        val oldTurn = f.transcripts.last().turnId
        f.pipeline.accept(frame(0, "new"))
        val secondTurn = f.transcripts.last().turnId
        assertNotEquals(oldTurn, secondTurn)
        f.pipeline.accept(frame(0, "new", epoch.copy(serial = 2)))
        assertNotEquals(secondTurn, f.transcripts.last().turnId)
        oldRequest.release()
        assertTrue(f.events.none { it is SpeakerEvent })
        assertEquals(2, f.events.filterIsInstance<DiscontinuityEvent>().size)
        assertEquals(2, f.cancelled.size)
    }

    @Test fun `gap discards silence pre roll`() = Fixture().use { f ->
        f.vad.decision = VadDecision(false, false)
        repeat(10) { f.pipeline.accept(frame(it)) }
        f.pipeline.discontinuity(envelope(frame(10)), GapReason.FORMAT_CHANGED)
        f.vad.decision = VadDecision(true, false)
        f.pipeline.accept(frame(0, "new"))
        assertEquals(listOf(SampleRange("new", 0, 320)), f.asr.accepted)
    }

    @Test fun `provider exception emits safe fault and pause then rejects further work`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        f.asr.failure = IllegalStateException("PRIVATE RAW TEXT")
        f.pipeline.accept(frame(1))
        val faults = f.events.filterIsInstance<ServiceFaultEvent>()
        assertEquals("SPEECH_PROVIDER_FAILURE", faults.single().code)
        assertEquals(SessionState.Paused, f.events.filterIsInstance<SessionStateEvent>().single().state)
        assertFalse(f.events.toString().contains("PRIVATE RAW TEXT"))
        val size = f.events.size
        f.asr.failure = null
        f.pipeline.accept(frame(2)); f.speaker.requests.single().release()
        assertEquals(size, f.events.size)
        assertEquals(1, f.cancelled.size)
    }

    @Test fun `speaker provider exception pauses and fences subsequent PCM`() = Fixture().use { f ->
        val fault = CountDownLatch(1)
        val pipeline = SpeechPipeline(f.vad, f.asr, PendingSpeaker().apply {
            failure = IllegalStateException("PRIVATE SPEAKER DATA")
        }, { f.events += it; if (it is ServiceFaultEvent) fault.countDown() }, f.cancelled::add)
        try {
            pipeline.accept(frame(0))
            assertTrue(fault.await(2, TimeUnit.SECONDS))
            val size = f.events.size
            pipeline.accept(frame(1))
            assertEquals(size, f.events.size)
            assertEquals("SPEECH_PROVIDER_FAILURE", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
        } finally { pipeline.close() }
    }

    @Test fun `twenty second turn bound resets before accepting excess PCM`() = Fixture().use { f ->
        repeat(1000) { f.pipeline.accept(frame(it)) }
        assertEquals(1000, f.asr.accepted.size)
        f.pipeline.accept(frame(1000))
        assertEquals(1000, f.asr.accepted.size)
        assertEquals("SPEECH_WINDOW_LIMIT", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
        assertEquals(1, f.vad.resets); assertEquals(1, f.asr.resets); assertEquals(1, f.speaker.resets)
        assertEquals(1, f.cancelled.size)
        assertTrue(f.speaker.requests.all { it.window.segment.pcm.size <= 640000 })
    }

    @Test fun `late speaker assessment can update an ended turn on same continuity`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val old = f.speaker.requests.single()
        f.vad.decision = VadDecision(false, true)
        f.pipeline.accept(frame(1))
        f.vad.decision = VadDecision(true, false)
        f.pipeline.accept(frame(2))
        old.release(SpeakerLabel.SELF)
        assertEquals(SpeakerLabel.SELF, f.events.filterIsInstance<SpeakerEvent>().single().assessment.label)
        assertEquals(0L, f.events.filterIsInstance<SpeakerEvent>().single().assessment.startNanos)
    }

    @Test fun `gap cancellation can await a callback on another thread without deadlock`() = Fixture().use { f ->
        val callbackCompleted = CountDownLatch(1)
        f.speaker.onCancel = { request ->
            Thread { request.release(); callbackCompleted.countDown() }.start()
            check(callbackCompleted.await(2, TimeUnit.SECONDS))
        }
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        // The handle may return just after gap admission; either path must cancel outside its lock.
        f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW)
        assertTrue(callbackCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(f.events.none { it is SpeakerEvent })
    }

    @Test fun `old gap cleanup cannot delete a newly queued continuity speaker request`() = Fixture().use { f ->
        val speakerRelease = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1)
        f.speaker.block = speakerRelease
        val pipeline = SpeechPipeline(f.vad, f.asr, f.speaker, f.events::add, { id ->
            f.cancelled += id
            cancelEntered.countDown()
            check(cancelRelease.await(2, TimeUnit.SECONDS))
        })
        try {
            pipeline.accept(frame(0)); f.speaker.awaitStarted()
            val gap = FutureTask { pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW) }
            Thread(gap).start()
            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
            pipeline.accept(frame(0, "new"))
            cancelRelease.countDown()
            gap.get(2, TimeUnit.SECONDS)
            speakerRelease.countDown()
            f.speaker.awaitRequests(2)
            assertEquals(listOf("c", "new"), f.speaker.requests.map { it.window.range.continuityId })
        } finally { cancelRelease.countDown(); speakerRelease.countDown(); pipeline.close() }
    }

    @Test fun `reset provider failure pauses instead of silently continuing after a gap`() = Fixture().use { f ->
        f.pipeline.accept(frame(0))
        f.vad.resetFailure = IllegalStateException("PRIVATE RESET DATA")
        f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW)
        assertEquals("SPEECH_PROVIDER_FAILURE", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
        assertEquals(SessionState.Paused, f.events.filterIsInstance<SessionStateEvent>().single().state)
        val size = f.events.size
        f.pipeline.accept(frame(0, "new"))
        assertEquals(size, f.events.size)
    }

    @Test fun `speaker windows include real PCM and allow exactly twenty seconds at endpoint`() = Fixture().use { f ->
        f.pipeline.accept(frame(0).copy(samples = FloatArray(320) { -1f }))
        f.speaker.awaitStarted()
        assertEquals(640, f.speaker.requests.single().window.segment.pcm.size)
        assertEquals(0x80.toByte(), f.speaker.requests.single().window.segment.pcm[1])
        for (index in 1..998) f.pipeline.accept(frame(index))
        f.vad.decision = VadDecision(false, true)
        f.pipeline.accept(frame(999))
        f.speaker.awaitRequests(2)
        val window = f.speaker.requests.last().window
        assertEquals(640000, window.segment.pcm.size)
        assertEquals(SampleRange("c", 0, 320000), window.range)
        assertEquals(20_000_000_000L, window.segment.envelope.endNanos)
        assertTrue(f.events.none { it is ServiceFaultEvent })
    }

    @Test fun `speaker pending backlog is bounded and fails safely`() = Fixture().use { f ->
        repeat(4) { index ->
            f.vad.decision = VadDecision(true, false)
            f.pipeline.accept(frame(index * 2))
            f.vad.decision = VadDecision(false, true)
            f.pipeline.accept(frame(index * 2 + 1))
        }
        f.vad.decision = VadDecision(true, false)
        f.pipeline.accept(frame(8))
        assertEquals("SPEECH_SPEAKER_BACKLOG", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
        assertEquals(SessionState.Paused, f.events.filterIsInstance<SessionStateEvent>().single().state)
        val size = f.events.size
        f.pipeline.accept(frame(9))
        assertEquals(size, f.events.size)
        assertEquals(5, f.cancelled.size)
    }

    @Test fun `continuity reset failure rejects the triggering frame before ASR`() = Fixture().use { f ->
        f.pipeline.accept(frame(0))
        f.vad.resetFailure = IllegalStateException("PRIVATE RESET DATA")
        f.pipeline.accept(frame(0, "new"))
        assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
        assertEquals(1, f.transcripts.size)
        assertEquals("SPEECH_PROVIDER_FAILURE", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
    }

    @Test fun `speaker event identity is generated locally rather than copied from provider text`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val request = f.speaker.requests.single()
        val envelope = request.window.segment.envelope
        request.listener(SpeakerAssessment(envelope.copy(eventId = "PRIVATE PROVIDER TEXT"), SpeakerLabel.UNKNOWN,
            null, envelope.startNanos, envelope.endNanos))
        val event = f.events.filterIsInstance<SpeakerEvent>().single()
        assertNotEquals("PRIVATE PROVIDER TEXT", event.envelope.eventId)
        assertEquals(epoch.sessionId, event.envelope.sessionId)
        assertEquals(0L, event.envelope.startNanos)
        assertEquals(20_000_000L, event.envelope.endNanos)
    }

    @Test fun `speaker callback rejects wrong generation and then accepts matching assessment only once`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val request = f.speaker.requests.single()
        val envelope = request.window.segment.envelope
        request.listener(SpeakerAssessment(envelope.copy(generation = Generation(2)), SpeakerLabel.SELF,
            null, envelope.startNanos, envelope.endNanos))
        assertTrue(f.events.none { it is SpeakerEvent })
        request.release(); request.release()
        assertEquals(1, f.events.filterIsInstance<SpeakerEvent>().size)
    }

    @Test fun `replacement intake waits behind held speaker reset completion`() = Fixture().use { f ->
        val resetEntered = CountDownLatch(1)
        val resetRelease = CountDownLatch(1)
        f.speaker.discardOnReset = true
        f.speaker.onReset = { resetEntered.countDown(); check(resetRelease.await(2, TimeUnit.SECONDS)) }
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val gap = FutureTask { f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW) }
        Thread(gap).start()
        try {
            assertTrue(resetEntered.await(2, TimeUnit.SECONDS))
            f.pipeline.accept(frame(0, "new"))
            if (f.asr.accepted.any { it.continuityId == "new" }) f.speaker.awaitRequests(2)
            assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
            assertEquals(1, f.transcripts.size)
        } finally { resetRelease.countDown(); gap.get(2, TimeUnit.SECONDS) }
        f.speaker.awaitRequests(2)
        assertFalse(f.speaker.requests.last().discardedByReset)
        f.speaker.requests.last().release()
        assertEquals(1, f.events.filterIsInstance<SpeakerEvent>().size)
    }

    @Test fun `held cancellation cannot expose replacement assessment to an old deferred reset`() = Fixture().use { f ->
        val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1)
        f.speaker.discardOnReset = true
        val pipeline = SpeechPipeline(f.vad, f.asr, f.speaker, f.events::add, { id ->
            f.cancelled += id
            cancelEntered.countDown()
            check(cancelRelease.await(2, TimeUnit.SECONDS))
        })
        var gap: FutureTask<Unit>? = null
        try {
            pipeline.accept(frame(0)); f.speaker.awaitStarted()
            gap = FutureTask { pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW) }
            Thread(gap).start()
            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
            pipeline.accept(frame(0, "new"))
            // On the reviewed implementation, prove the replacement assessment really starts.
            if (f.asr.accepted.any { it.continuityId == "new" }) f.speaker.awaitRequests(2)
            assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
            assertEquals(1, f.speaker.resets)
            cancelRelease.countDown()
            gap.get(2, TimeUnit.SECONDS)
            f.speaker.awaitRequests(2)
            assertFalse(f.speaker.requests.last().discardedByReset)
            f.speaker.requests.last().release(SpeakerLabel.SELF)
            assertEquals(SpeakerLabel.SELF, f.events.filterIsInstance<SpeakerEvent>().single().assessment.label)
        } finally { cancelRelease.countDown(); gap?.get(2, TimeUnit.SECONDS); pipeline.close() }
    }

    @Test fun `speaker reset exception rejects continuity triggering frame before ASR and transcript`() = Fixture().use { f ->
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        f.speaker.resetFailure = IllegalStateException("PRIVATE RESET DATA")
        f.pipeline.accept(frame(0, "new"))
        assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
        assertEquals(1, f.transcripts.size)
        assertEquals("SPEECH_PROVIDER_FAILURE", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
        assertEquals(SessionState.Paused, f.events.filterIsInstance<SessionStateEvent>().single().state)
    }

    @Test fun `a second gap cannot reopen intake while the first speaker reset is held`() = Fixture().use { f ->
        val resetEntered = CountDownLatch(1)
        val resetRelease = CountDownLatch(1)
        f.speaker.discardOnReset = true
        f.speaker.onReset = {
            if (f.speaker.resets == 1) {
                resetEntered.countDown()
                check(resetRelease.await(2, TimeUnit.SECONDS))
            }
        }
        f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
        val gap = FutureTask { f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW) }
        Thread(gap).start()
        try {
            assertTrue(resetEntered.await(2, TimeUnit.SECONDS))
            f.pipeline.discontinuity(envelope(frame(0, "new")), GapReason.FORMAT_CHANGED)
            f.pipeline.accept(frame(0, "new"))
            if (f.asr.accepted.any { it.continuityId == "new" }) f.speaker.awaitRequests(2)
            assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
            assertEquals(1, f.speaker.resets)
        } finally { resetRelease.countDown(); gap.get(2, TimeUnit.SECONDS) }
        f.speaker.awaitRequests(2)
        assertEquals(2, f.speaker.resets)
        assertEquals(listOf(GapReason.OVERFLOW, GapReason.FORMAT_CHANGED),
            f.events.filterIsInstance<DiscontinuityEvent>().map { it.reason })
        assertFalse(f.speaker.requests.last().discardedByReset)
    }

    @Test fun `reset barrier has bounded deferred intake and pauses on saturation`() = Fixture().use { f ->
        val resetEntered = CountDownLatch(1)
        val resetRelease = CountDownLatch(1)
        f.speaker.onReset = {
            if (f.speaker.resets == 1) {
                resetEntered.countDown()
                check(resetRelease.await(2, TimeUnit.SECONDS))
            }
        }
        f.pipeline.accept(frame(0))
        val gap = FutureTask { f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW) }
        Thread(gap).start()
        try {
            assertTrue(resetEntered.await(2, TimeUnit.SECONDS))
            repeat(9) { f.pipeline.accept(frame(it, "new")) }
            assertEquals("SPEECH_INPUT_BACKLOG", f.events.filterIsInstance<ServiceFaultEvent>().single().code)
            assertEquals(SessionState.Paused, f.events.filterIsInstance<SessionStateEvent>().single().state)
        } finally { resetRelease.countDown(); gap.get(2, TimeUnit.SECONDS) }
        assertEquals(listOf(SampleRange("c", 0, 320)), f.asr.accepted)
        assertEquals(1, f.transcripts.size)
    }

    @Test fun `stale speaker failure waiting for worker cannot pause replacement continuity`() {
        val settled = CountDownLatch(1)
        Fixture { if (it is ServiceFaultEvent) settled.countDown() }.use { f ->
            val failRelease = CountDownLatch(1)
            val faultBlocked = CountDownLatch(1)
            f.speaker.onAssess = { request ->
                if (request.window.range.continuityId == "c") {
                    check(failRelease.await(2, TimeUnit.SECONDS))
                    throw IllegalStateException("PRIVATE STALE FAILURE")
                } else settled.countDown()
            }
            f.pipeline.accept(frame(0)); f.speaker.awaitStarted()
            f.asr.beforeAccept = { input ->
                if (input.range.continuityId == "c" && input.range.first == 320L) {
                    failRelease.countDown()
                    val thread = f.speaker.assessmentThread!!
                    val bean = ManagementFactory.getThreadMXBean()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    var observed = false
                    while (!observed && System.nanoTime() < deadline) {
                        val info = bean.getThreadInfo(thread.threadId(), 32)
                        observed = info?.threadState == Thread.State.BLOCKED &&
                            info.stackTrace.any { it.className == SpeechPipeline::class.java.name && it.methodName == "providerFault" }
                        Thread.onSpinWait()
                    }
                    check(observed) { "old failure never waited on worker ownership" }
                    faultBlocked.countDown()
                    // The ASR hook owns the worker monitor; this replacement precedes its release.
                    f.pipeline.accept(frame(1, "new"))
                }
            }
            f.pipeline.accept(frame(1))
            assertTrue(faultBlocked.await(2, TimeUnit.SECONDS))
            assertTrue(settled.await(2, TimeUnit.SECONDS))
            assertTrue(f.events.none { it is ServiceFaultEvent || it is SessionStateEvent })
            f.speaker.awaitRequests(2)
            val replacementTurn = f.transcripts.last().turnId
            f.asr.hypotheses = listOf(AsrHypothesis("replacement remains active", false))
            f.pipeline.accept(frame(2, "new"))
            assertEquals(replacementTurn, f.transcripts.last().turnId)
            assertEquals("replacement remains active", f.transcripts.last().text)
            assertFalse(replacementTurn in f.cancelled)
        }
    }

    @Test fun `frame signal observes every accepted frame exactly once with its VAD decision`() {
        val seen = mutableListOf<Pair<Long, Boolean>>()
        Fixture(onFrameSignal = { input, speech -> seen += input.range.first to speech }).use { f ->
            f.vad.decision = VadDecision(false, false)
            f.pipeline.accept(frame(0))
            f.pipeline.accept(frame(1))
            f.vad.decision = VadDecision(true, false)
            f.pipeline.accept(frame(2))
            assertEquals(listOf(0L to false, 320L to false, 640L to true), seen)
        }
    }

    @Test fun `a discontinuity and a close both reset the frame observer`() {
        var resets = 0
        Fixture(onReset = { resets++ }).use { f ->
            f.pipeline.accept(frame(0))
            assertEquals(0, resets, "a plain frame is not a reset")

            // The pipeline just dropped its accumulated state, so anything deriving from its frames has to drop its
            // own - otherwise the first transition after the break is compared against a stale label.
            f.pipeline.discontinuity(envelope(frame(1)), GapReason.OVERFLOW)
            assertEquals(1, resets)

            f.pipeline.close()
            assertEquals(2, resets, "closing drops the state too")
        }
    }
}
