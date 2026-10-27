package com.sheen.rokid.phone.audio
import com.sheen.rokid.phone.session.EchoMuteWindow
import com.sheen.rokid.phone.withP3Sink

import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.audio.WakeWordEngine
import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.GapReason
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.SpeakerWindow
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polled phone-side ingress loop: the scheduled tick drains what the ingress has queued, frames it, and hands the
 * frames to **both** consumers, with every ingress outcome either reported or deliberately silent.
 *
 * The rule the tests pin is that the loop is the **single** reader of the ingress — a second loop, or a second poll of
 * the same chunk, would deliver audio twice and double every transcript. The number of chunks one tick takes is a
 * policy the first version got wrong: polling exactly once per 20 ms tick consumes half of what a ~10 ms uplink
 * offers, which is what made the ingress overflow every two seconds on the K40 (see
 * `aTickDrainsEveryQueuedChunkSoAFastProducerCannotBackTheIngressUp`). `PcmIngress.poll()` is a plain FIFO pop, so
 * draining still delivers each chunk once and in order.
 *
 * The one PCM stream has two consumers and they are fed the same [PcmFrame]: the wake detector (which VAD-gates its
 * own engine) and the speech pipeline (which VAD-gates a turn). The tests below observe both through their own
 * engines, so "forwarded to both" is asserted rather than assumed.
 */
class AudioIngressLoopTest {

    /** Speech is always present, so both consumers actually process every frame. */
    private class LoudVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(true, false)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class RecordingAsr : StreamingAsrEngine {
        val frames = mutableListOf<PcmFrame>()
        override fun accept(frame: PcmFrame): List<AsrHypothesis> { frames += frame; return emptyList() }
        override fun finish() = AsrHypothesis("", true)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentSpeaker : SpeakerEngine {
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class RecordingWakeEngine : WakeWordEngine {
        val frames = mutableListOf<PcmFrame>()
        var resetCount = 0
        override fun accept(frame: PcmFrame): List<String> { frames += frame; return emptyList() }
        override fun reset() { resetCount++ }
        override fun close() = Unit
    }

    private class Rig(muteWindow: EchoMuteWindow? = null) {
        val epoch = InputEpoch(SessionId("s-1"), Generation(1), 0)
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "test-format")
        val ingress = PcmIngress(clock)
        val framer = PcmFramer(verified, 20)
        val asr = RecordingAsr()
        val pipeline = SpeechPipeline(LoudVad(), asr, SilentSpeaker(), emit = {}, cancelTurn = {})
        val wakeEngine = RecordingWakeEngine()
        val wake = WakeWordDetector(LoudVad(), wakeEngine, emit = { _, _ -> }, clock = clock)
        val faults = mutableListOf<String>()
        val mute = muteWindow ?: EchoMuteWindow(clock)
        val loop = AudioIngressLoop(ingress, framer, pipeline, wake, scheduler, clock,
            cadenceMillis = 20, onFault = { faults += it }, muteWindow = mute)

        fun open() = ingress.open(epoch, verified)
        fun offer(bytes: Int) = ingress.offer(epoch, ByteArray(bytes))
        fun offerLoud() = ingress.offer(epoch, ByteArray(640) { 0x3F })
        fun tick() { clock.advance(20_000_000); scheduler.runDue() }
    }

    /** 20 ms of 16 kHz mono PCM16 = 320 samples = 640 bytes, one whole frame. */
    private fun Rig.offerWholeFrame() = offer(640)

    @Test
    fun aTickDrainsTheQueueAndAStoppedLoopEndsPolling() {
        val rig = Rig()
        rig.open()
        repeat(3) { rig.offerWholeFrame() }
        rig.loop.start(rig.epoch)
        assertEquals("nothing is polled before a tick is due", 0, rig.asr.frames.size)

        rig.tick()
        assertEquals("a tick drains what is queued rather than one chunk of it", 0, rig.ingress.queuedBytes)
        assertEquals(3, rig.asr.frames.size)
        rig.tick()
        assertEquals("an empty ingress must not fabricate a frame", 3, rig.asr.frames.size)

        rig.loop.stop(rig.epoch)
        rig.offerWholeFrame()
        rig.tick()
        assertEquals("a stopped loop must not poll", 3, rig.asr.frames.size)
    }

    @Test
    fun framesInsideTheEchoMuteWindowReachBothConsumersSilenced() {
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)

        rig.offerLoud()
        rig.tick()
        assertTrue("outside the window the real samples reach the ASR",
            rig.asr.frames.last().samples.any { it != 0f })
        assertTrue("and the wake engine", rig.wakeEngine.frames.last().samples.any { it != 0f })

        rig.mute.open()
        rig.offerLoud()
        rig.tick()
        assertTrue("inside the window the ASR receives silence",
            rig.asr.frames.last().samples.all { it == 0f })
        assertTrue("and so does the wake engine", rig.wakeEngine.frames.last().samples.all { it == 0f })
        assertTrue("the timeline stays contiguous through the muted frames",
            rig.asr.frames.zipWithNext().all { (a, b) -> a.range.endExclusive == b.range.first })

        rig.mute.close()
        rig.offerLoud()
        rig.tick()
        assertTrue("audio captured inside the reverb tail stays muted",
            rig.asr.frames.last().samples.all { it == 0f })
        // The frame timeline advances with offered audio, not with the wall clock: drain until it passes the tail.
        var drained = 0
        while (rig.asr.frames.last().samples.all { it == 0f } && drained < 32) {
            rig.offerLoud()
            rig.tick()
            drained++
        }
        assertTrue("after the reverb tail the real samples return",
            rig.asr.frames.last().samples.any { it != 0f })
    }

    @Test
    fun aTickDrainsEveryQueuedChunkSoAFastProducerCannotBackTheIngressUp() {
        // The device's uplink offers ~10 ms chunks while the loop ticks every 20 ms, so a tick that polls exactly once
        // consumes half of what arrives. The queue then fills, and `PcmIngress` answers a full queue with a
        // **whole-queue** OVERFLOW reset — one to two seconds of audio discarded at a time. That is what the K40 run
        // of 2026-10-14 measured: `GAP:OVERFLOW` every ~2 s for nine minutes, most ASR finals reading `chars=0`, and
        // no `P3_WAKE` in ten attempts, against `overflow=0 gaps=0` in the 2026-10-09 run that fired the wake word.
        //
        // Draining what is queued is what keeps the consumer level with the producer. `PcmIngress.poll()` is a plain
        // FIFO pop, so every chunk is still delivered exactly once and in order — the older rule ("exactly one poll
        // per tick") was a policy that encoded the defect, not a property of the ingress.
        val rig = Rig()
        rig.open()
        repeat(5) { rig.offerWholeFrame() }
        rig.loop.start(rig.epoch)

        rig.tick()

        assertEquals("a tick must leave nothing queued when it can drain it", 0, rig.ingress.queuedBytes)
        assertEquals(listOf(0L, 320L, 640L, 960L, 1280L), rig.asr.frames.map { it.range.first })
        assertEquals("the wake path sees the same frames", 5, rig.wakeEngine.frames.size)
    }

    @Test
    fun aTickDrainsAtMostItsBoundSoOneTickCannotStallTheLoop() {
        // Catching up must not become an unbounded amount of work on the scheduler's thread: a tick takes at most
        // [AudioIngressLoop.MAX_CHUNKS_PER_TICK] chunks and leaves the rest for the next one.
        val rig = Rig()
        rig.open()
        repeat(AudioIngressLoop.MAX_CHUNKS_PER_TICK + 10) { rig.offerWholeFrame() }
        rig.loop.start(rig.epoch)

        rig.tick()
        assertEquals(AudioIngressLoop.MAX_CHUNKS_PER_TICK, rig.asr.frames.size)
        assertTrue("the rest stays queued for the next tick", rig.ingress.queuedBytes > 0)

        rig.tick()
        assertEquals(
            "two ticks must clear the backlog",
            AudioIngressLoop.MAX_CHUNKS_PER_TICK + 10,
            rig.asr.frames.size,
        )
        assertEquals(0, rig.ingress.queuedBytes)
    }

    @Test
    fun startingAFreshEpochResetsTheWakeStreams() {
        // 0.4 final K40 acceptance: after a live glasses session, switching to detached reuses the same input loop and
        // wake detector; the eight native KWS streams still held the CXR stream state, and five synthetic wake plays
        // produced no hit until an explicit detector reset/cold start. A new epoch is a new source boundary, so the
        // wake engine must be re-staggered rather than continue the old stream.
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)
        assertEquals("a fresh epoch must reset the KWS streams", 1, rig.wakeEngine.resetCount)

        rig.loop.start(rig.epoch)
        assertEquals("starting the same epoch again is the documented no-op", 1, rig.wakeEngine.resetCount)

        rig.loop.stop(rig.epoch)
        val next = InputEpoch(SessionId("s-2"), Generation(2), 1)
        rig.loop.start(next)
        assertEquals("a different epoch must reset again", 2, rig.wakeEngine.resetCount)
    }

    @Test
    fun acceptedFramesReachTheWakeDetectorAndThePipelineInOrder() {
        val rig = Rig()
        rig.open()
        repeat(3) { rig.offerWholeFrame() }
        rig.loop.start(rig.epoch)

        repeat(3) { rig.tick() }

        assertEquals(listOf(0L, 320L, 640L), rig.asr.frames.map { it.range.first })
        assertEquals(listOf(0L, 320L, 640L), rig.wakeEngine.frames.map { it.range.first })
    }

    @Test
    fun disablingWakeAcceptanceKeepsThePipelineFedAndNeverFeedsTheDetector() {
        // 0.4 Task 12b-ii (spec §5.3, D204): a phone-microphone dictation reuses one upload loop. While it listens the
        // KWS must not see the dictation audio (a spoken wake word there is dictation content, not a request), but the
        // pipeline still must - it is the same ASR turn that produces the final the input row appends.
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)

        rig.loop.wakeEnabled = false
        rig.offerWholeFrame()
        rig.tick()
        assertEquals("a disabled wake seam must still feed the speech pipeline", 1, rig.asr.frames.size)
        assertEquals("KWS must not see dictation audio", 0, rig.wakeEngine.frames.size)

        rig.loop.wakeEnabled = true
        rig.offerWholeFrame()
        rig.tick()
        assertEquals("re-enabling restores the wake path for the same loop", 1, rig.wakeEngine.frames.size)
        assertEquals(2, rig.asr.frames.size)
    }

    @Test
    fun disablingPipelineAcceptanceQuiescesTheAsrWithoutSilencingTheWakePath() {
        // The second half of the dictation quiesce seam: when a final/stop/timeout ends a dictation, the frames still
        // drained in the same tick must not start another ASR turn whose partials would reach the restored wake route.
        // The two switches are independent on purpose - a host can stop one consumer without touching the other.
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)

        rig.loop.pipelineEnabled = false
        rig.offerWholeFrame()
        rig.tick()
        assertEquals("a quiesced pipeline must not accept the frame", 0, rig.asr.frames.size)
        assertEquals("the wake consumer is a separate switch and stays fed", 1, rig.wakeEngine.frames.size)

        rig.loop.pipelineEnabled = true
        rig.offerWholeFrame()
        rig.tick()
        assertEquals(1, rig.asr.frames.size)
    }

    @Test
    fun overflowIsReportedAndRestartsFramingInsteadOfFabricatingFrames() {
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)

        rig.offerWholeFrame()
        rig.tick()
        assertEquals(1, rig.asr.frames.size)
        assertEquals(0L, rig.asr.frames.single().range.first)

        rig.offer(320) // half a frame stays pending in the framer

        val overflow = rig.ingress.offer(rig.epoch, ByteArray(70_000))
        assertEquals(PcmOffer.OVERFLOW, overflow)
        rig.loop.onOffer(overflow)
        assertTrue("an overflow must be reported, not silently dropped", rig.faults.contains("INGRESS_OVERFLOW"))

        rig.tick() // drains the OVERFLOW gap the ingress queued
        assertTrue(rig.faults.contains("GAP:OVERFLOW"))
        assertEquals("an overflow must not fabricate a frame from the partial buffer", 1, rig.asr.frames.size)

        rig.offerWholeFrame()
        rig.tick()
        assertEquals(2, rig.asr.frames.size)
        assertEquals("framing must restart at the discontinuity", 0L, rig.asr.frames.last().range.first)
    }

    @Test
    fun unknownFormatIsReportedAndStaleEpochIsIgnoredSilently() {
        val rig = Rig()
        rig.ingress.open(rig.epoch, null)
        val unknown = rig.ingress.offer(rig.epoch, ByteArray(640))
        assertEquals(PcmOffer.UNKNOWN_FORMAT, unknown)
        val stale = rig.ingress.offer(InputEpoch(SessionId("other"), Generation(1), 0), ByteArray(640))
        assertEquals(PcmOffer.STALE_EPOCH, stale)

        rig.loop.start(rig.epoch)
        rig.loop.onOffer(unknown)
        assertTrue(rig.faults.contains("UNKNOWN_FORMAT"))
        val reported = rig.faults.size
        rig.loop.onOffer(stale)
        rig.loop.onOffer(PcmOffer.ACCEPTED)
        assertEquals("STALE_EPOCH and ACCEPTED must be silent", reported, rig.faults.size)
        assertEquals("no frames may be fabricated", 0, rig.asr.frames.size)
    }

    @Test
    fun aGapFromTheIngressIsReportedAndRestartsFraming() {
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)
        rig.offerWholeFrame()
        rig.tick()
        assertEquals(1, rig.asr.frames.size)

        rig.ingress.reset(GapReason.FORMAT_CHANGED)
        rig.tick()
        assertTrue(rig.faults.contains("GAP:FORMAT_CHANGED"))
        assertEquals("a gap must not fabricate frames", 1, rig.asr.frames.size)

        rig.offerWholeFrame()
        rig.tick()
        assertEquals(2, rig.asr.frames.size)
        assertEquals(0L, rig.asr.frames.last().range.first)
    }

    @Test
    fun stopIsIdempotentAndRestartResetsTheFramer() {
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)
        rig.loop.start(rig.epoch) // must not create a second loop
        rig.offerWholeFrame()
        // One chunk, two ticks: a second loop polling the same ingress would deliver it twice.
        rig.tick()
        rig.tick()
        assertEquals("a second start must not double-poll", 1, rig.asr.frames.size)
        assertEquals("the chunk was consumed exactly once", 0, rig.ingress.queuedBytes)

        rig.offerWholeFrame()
        rig.offerWholeFrame()
        rig.tick()
        assertEquals(
            "both queued chunks reach the consumers, in order",
            listOf(0L, 320L, 640L),
            rig.asr.frames.map { it.range.first },
        )

        rig.loop.stop(rig.epoch)
        rig.loop.stop(rig.epoch) // idempotent
        rig.loop.start(rig.epoch) // restart without reopening the ingress
        rig.offerWholeFrame()
        rig.tick()
        assertEquals(4, rig.asr.frames.size)
        assertEquals("a restart must reset the framer so offsets do not continue", 0L, rig.asr.frames.last().range.first)
    }

    @Test
    fun aMissingWakeDetectorDoesNotBreakThePipelineConsumer() {
        // Ruling: when the KWS bundle is absent the detector stays unwired, and the ASR chain still runs.
        val rig = Rig()
        rig.open()
        val faults = mutableListOf<String>()
        val loop = AudioIngressLoop(rig.ingress, rig.framer, rig.pipeline, null, rig.scheduler, rig.clock,
            cadenceMillis = 20, onFault = { faults += it })
        loop.start(rig.epoch)
        rig.offerWholeFrame()
        rig.tick()
        loop.stop(rig.epoch)

        assertEquals(1, rig.asr.frames.size)
        assertTrue("no wake detector means no wake fault", faults.isEmpty())
    }

    // ---- P3_FRAME: one periodic summary, never a per-frame line ------------------------------------------------

    @Test
    fun theFrameSummaryIsOneLinePerIntervalNotOneLinePerFrame() {
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)

        withP3Sink { lines ->
            // 10 frames in 20 ms: below both thresholds, so a line here would be a per-frame line.
            rig.ingress.offer(rig.epoch, ByteArray(6400))
            rig.loop.onOffer(PcmOffer.ACCEPTED)
            rig.tick()
            assertEquals("no summary may be emitted before an interval", emptyList<String>(), lines.toList())

            // 24 more chunks of 10 frames each = 250 frames in 0.5 s: the frame interval fires exactly once.
            repeat(24) {
                rig.ingress.offer(rig.epoch, ByteArray(6400))
                rig.loop.onOffer(PcmOffer.ACCEPTED)
                rig.tick()
            }
            assertEquals("one line per interval, not one per frame: $lines", 1, lines.size)
            assertEquals(
                "P3_FRAME frames=250 bytes=160000 accepted=25 overflow=0 unknownFormat=0 staleEpoch=0 gaps=0",
                lines.single(),
            )

            rig.loop.stop(rig.epoch)
            assertEquals("stop must add exactly one final summary", 2, lines.size)
            assertEquals(lines.first(), lines.last())
        }
    }

    @Test
    fun theFrameSummaryFiresOnTheTimeIntervalAndCountsEveryOfferOutcomeWithoutSampleData() {
        val rig = Rig()
        rig.open()
        rig.loop.start(rig.epoch)

        withP3Sink { lines ->
            rig.offerWholeFrame()
            rig.loop.onOffer(PcmOffer.ACCEPTED)
            rig.tick()
            assertEquals("one frame and 20 ms is inside both intervals", emptyList<String>(), lines.toList())

            rig.loop.onOffer(PcmOffer.OVERFLOW)
            rig.loop.onOffer(PcmOffer.UNKNOWN_FORMAT)
            rig.loop.onOffer(PcmOffer.STALE_EPOCH)
            rig.ingress.reset(GapReason.FORMAT_CHANGED)
            rig.clock.advance(5_000_000_000L)
            rig.tick()

            assertEquals(lines.toString(), 1, lines.size)
            // The line is only the named counters: no frame, no sample, no chunk.
            assertEquals(
                "P3_FRAME frames=1 bytes=640 accepted=1 overflow=1 unknownFormat=1 staleEpoch=1 gaps=1",
                lines.single(),
            )
            assertTrue(
                "every field but the prefix must be a named counter",
                lines.single().split(' ').drop(1).all { it.contains('=') },
            )

            rig.loop.stop(rig.epoch)
            assertEquals(2, lines.size)
        }
    }
}

/**
 * The loop's scheduling contract (Task 11b, Important 4): each tick is scheduled at the **previous deadline
 * plus one cadence**, never at "now plus one cadence".
 *
 * The distinction is not cosmetic. A tick that is late schedules from the moment it finally ran, so the real
 * period becomes `cadence + processing cost`; the error accumulates until the loop falls far enough behind the
 * ingress to reach the 2 s buffer the ingress is bounded by. The tests below drive the clock **while a tick is
 * running**, which is what a real blocking `poll()`/VAD/ASR costs the loop, and assert that the loop's own
 * period stays one cadence.
 */
class AudioIngressLoopScheduleTest {

    private class SilentVad : VadEngine {
        override fun accept(frame: PcmFrame) = VadDecision(false, false)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class RecordingAsr : StreamingAsrEngine {
        override fun accept(frame: PcmFrame): List<AsrHypothesis> = emptyList()
        override fun finish() = AsrHypothesis("", true)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentSpeaker : SpeakerEngine {
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** Records every deadline the loop asked for and every moment a tick actually ran. */
    private class Rig(val clock: FakeClock = FakeClock(1_000_000_000L)) {
        val epoch = InputEpoch(SessionId("s-1"), Generation(1), 0)
        val scheduler = ManualScheduler(clock)
        val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "test-format")
        val ingress = PcmIngress(clock)
        val pipeline = SpeechPipeline(SilentVad(), RecordingAsr(), SilentSpeaker(), emit = {}, cancelTurn = {})
        val scheduledDeadlines = mutableListOf<Long>()
        val tickTimes = mutableListOf<Long>()
        private val delegate = scheduler
        val wrapping = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                scheduledDeadlines += deadlineNanos
                return delegate.at(deadlineNanos) { tickTimes += clock.nowNanos(); task() }
            }
        }
        val loop = AudioIngressLoop(ingress, PcmFramer(verified, 20), pipeline, null, wrapping, clock, cadenceMillis = 20)
    }

    @Test
    fun aLateTickStillPublishesTheCadenceDeadlineItWasAlreadyOwed() {
        val rig = Rig()
        rig.loop.start(rig.epoch)

        // Tick 1 is due at 1_020_000_000 and "costs" 18 ms of processing, so at the moment it reschedules the
        // clock reads 1_038_000_000. The next deadline must still be 1_040_000_000 - the previous deadline plus
        // one cadence - not now plus one cadence (1_058_000_000).
        rig.clock.nowNanos = 1_020_000_000L
        rig.scheduler.runDue()
        rig.clock.advance(18_000_000)
        rig.scheduler.runDue()
        assertEquals("the next deadline is the previous one plus a cadence",
            listOf(1_020_000_000L, 1_040_000_000L), rig.scheduledDeadlines)
        assertEquals("the late tick must not run a second time just because it ran late",
            listOf(1_020_000_000L), rig.tickTimes)

        // And that already-published deadline really is the next tick's.
        rig.clock.advance(2_000_000)
        rig.scheduler.runDue()
        assertEquals(listOf(1_020_000_000L, 1_040_000_000L), rig.tickTimes)
        assertEquals(listOf(1_020_000_000L, 1_040_000_000L, 1_060_000_000L), rig.scheduledDeadlines)
        rig.loop.stop(rig.epoch)
    }

    @Test
    fun processingCostDoesNotAccumulateIntoThePollingPeriod() {
        val rig = Rig()
        val clock = rig.clock
        rig.loop.start(rig.epoch)

        // 50 ticks that each run 12 ms late: a deadline-anchored loop stays one cadence apart throughout and
        // never falls further behind than the processing cost of the tick that is running.
        repeat(50) { index ->
            clock.nowNanos = 1_000_000_000L + (index + 1) * 20_000_000L + 12_000_000L
            rig.scheduler.runDue()
        }
        rig.loop.stop(rig.epoch)

        assertEquals("processing cost must not move the loop's own period",
            (1L..51L).map { 1_000_000_000L + it * 20_000_000L }, rig.scheduledDeadlines)
        assertEquals(50, rig.tickTimes.size)
    }
}
