package com.sheen.rokid.phone.audio
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.phone.session.EchoMuteWindow
import com.sheen.rokid.phone.session.silenced
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.MonotonicClock

/** One `P3_FRAME` summary per this many delivered frames, whichever interval is reached first (P3.1 acceptance). */
private const val FRAME_SUMMARY_FRAMES = 250L

/** One `P3_FRAME` summary per this much monotonic time, whichever interval is reached first (P3.1 acceptance). */
private const val FRAME_SUMMARY_NANOS = 5_000_000_000L

/**
 * The phone-side CXR PCM ingress as a polled pipeline.
 *
 * The glasses send PCM over the CXR binary channel; `transport-rokid` offers it to the connection-owned
 * [PcmIngress] but **nothing consumed that ingress in production**. This loop is the consumer: one `poll()` per
 * scheduled tick, framed once, and the frames handed to both the wake detector and the speech pipeline.
 *
 * Rules it exists to keep:
 *
 * - **One reader, and it drains.** [PcmIngress.poll] is called repeatedly by the tick, up to
 *   [MAX_CHUNKS_PER_TICK], so every queued chunk is delivered exactly once and in order. It used to be called
 *   **once** per tick, which was wrong once the uplink began offering ~10 ms chunks against a 20 ms tick: the
 *   consumer took half of what arrived, the ingress's two-second buffer filled, and each overflow discarded the whole
 *   queue (the 2026-10-14 K40 run measured one such gap every ~2 s and no wake word in ten attempts). Draining is
 *   safe because `poll()` is a plain FIFO pop — the old "at most once" rule was a policy, not a property.
 * - **One PCM stream, two consumers.** The same [PcmFrame] goes to `wake.accept` and `pipeline.accept`; neither
 *   consumer re-reads the ingress, so the VADs inside them cannot drift apart.
 * - **Silence is reported, never hidden.** [onOffer] turns the receiver's ingress outcome into a fault code and
 *   drains [PcmIngress.pollGap] as `GAP:<reason>`; both restart framing so an overflow or a format change cannot
 *   be spliced into the middle of a frame.
 * - **Stop is bounded.** `stop(epoch)` cancels the pending tick, so polling stops within one cadence (20 ms by
 *   default) rather than at the next scheduled wake-up.
 * - **The period is the cadence, not the cadence plus work.** Each tick is published one cadence after the
 *   *previous deadline* (see `dueNanos`), so a slow VAD/ASR cannot make the loop drift behind the ingress until
 *   it overflows.
 *
 * @param wake the wake detector, or `null` when the keyword-spotting assets are absent. Absence is a ruled state:
 *   the detector stays unwired (report `WAKE_UNAVAILABLE` at the composition) instead of building a second ASR
 *   engine for the text matcher over the same ~200 MB model, and the explicit manual trigger is the only arming
 *   path. The loop still drives the pipeline in that case.
 */
class AudioIngressLoop(
    private val ingress: PcmIngress,
    private val framer: PcmFramer,
    private val pipeline: SpeechPipeline,
    private val wake: WakeWordDetector?,
    private val scheduler: DeadlineScheduler,
    private val clock: MonotonicClock,
    private val cadenceMillis: Long = 20,
    private val onFault: (String) -> Unit = {},
    /**
     * 0.4 detached wake acknowledgement: while this window is open the loop's frames are replaced by silence before
     * they reach the wake detector and the speech pipeline, so the phone's own acknowledgement can never become a
     * transcript. Null keeps every frame untouched (glasses mode, dictation loops, all pre-0.4 behavior).
     */
    private val muteWindow: EchoMuteWindow? = null,
) : InputPort {

    private val lock = Any()
    private var handle: CancellationHandle? = null
    private var running: InputEpoch? = null

    /**
     * Whether the frames of this loop are offered to the KWS as well as to the speech pipeline (0.4 Task 12b-ii).
     *
     * The orchestrating host disables this for the duration of a phone-microphone dictation: the same loop then keeps
     * feeding the ASR turn that produces the dictation final, but a wake word spoken into the dictation can never
     * become a wake request (spec §5.3: 唤醒 KWS 不把听写内容当唤醒/回合). It is a plain per-frame read rather than a
     * constructor value, because the loop that serves a detached session is the one that later dictates.
     *
     * Default true: a loop that nobody told about a dictation behaves exactly as before.
     */
    @Volatile var wakeEnabled: Boolean = true

    /**
     * Whether the frames of this loop are offered to the speech pipeline (0.4 Task 12b-ii).
     *
     * A terminal dictation event can be produced in the middle of one drained frame batch. The host sets this false
     * synchronously when the dictation ends, so the frames left in that batch cannot start a second ASR turn whose
     * partials would reach the restored wake route. The host re-enables it when the dictation is fully cleaned up (or
     * rebuilds the loop for glasses mode). Default true - only a dictation quiesce changes it.
     */
    @Volatile var pipelineEnabled: Boolean = true

    /**
     * The epoch this loop is polling under, or null while stopped.
     *
     * Exposed so the chain that caches the loop can tell "this is the loop for the epoch being stopped" from a stale
     * stop naming an older epoch - and only drop the cache in the former case.
     */
    val activeEpoch: InputEpoch? get() = synchronized(lock) { running }

    /**
     * The deadline of the next tick, or `null` while no loop is running.
     *
     * Kept as state rather than recomputed from [clock] on every tick: the next tick is due one cadence after
     * the **previous deadline**, not after the moment the previous tick happened to finish. Scheduling from
     * "now" would make the real period `cadence + processing cost`, and that error accumulates until the loop
     * has fallen far enough behind the ingress to hit the 2 s buffer the ingress is bounded by. Anchoring on
     * the deadline keeps the published period exactly one cadence however expensive a tick is; a tick that
     * overruns simply fires the next one immediately (see [schedule]).
     */
    private var dueNanos: Long? = null

    /**
     * The `P3_FRAME` counters, cumulative since the loop was last started and all guarded by [lock].
     *
     * Deliberately counts, never samples: a device run needs to know that frames flowed and whether any were lost or
     * refused, not what the audio was. The offer outcomes are counted in [onOffer] (`accepted`/`overflow`/
     * `unknownFormat`/`staleEpoch`), the gaps drained in [tick], and the framed output (`frameCount`/`byteCount`)
     * where the frames are handed to the consumers.
     */
    private var frameCount = 0L
    private var byteCount = 0L
    private var acceptedCount = 0L
    private var overflowCount = 0L
    private var unknownFormatCount = 0L
    private var staleEpochCount = 0L
    private var gapCount = 0L

    /** The frame total the last summary covered and the moment it was emitted; both only advance when one is due. */
    private var framesAtLastSummary = 0L
    private var lastSummaryNanos = 0L

    init { require(cadenceMillis in 1..1000) { "cadence must be a bounded poll interval" } }

    /**
     * Starts the bounded poll loop for [epoch].
     *
     * Calling it again for the epoch already running is a no-op: a second loop would double-poll every chunk.
     * Starting for a different epoch replaces the loop, and the framer is reset either way so byte offsets never
     * continue across a restart.
     */
    override fun start(epoch: InputEpoch) {
        val replaced: CancellationHandle?
        synchronized(lock) {
            if (running == epoch && handle != null) return
            resetFrameSummary()
            replaced = handle
            handle = null
            running = epoch
            framer.reset()
            handle = schedule(epoch, clock.nowNanos() + cadenceMillis * 1_000_000L)
        }
        replaced?.cancel()
        // A new epoch is a new audio source/session boundary. The eight native KWS streams otherwise keep the
        // previous source's decoder state; on the K40 a glasses→detached switch then missed every synthetic wake
        // until the detector was reset by a dictation or a cold start. Re-stagger the streams here, where every
        // start (including resume) already resets the framer.
        wake?.reset()
    }

    /** Clears the `P3_FRAME` counters for a fresh epoch and anchors the time interval on the start moment. */
    private fun resetFrameSummary() {
        frameCount = 0L
        byteCount = 0L
        acceptedCount = 0L
        overflowCount = 0L
        unknownFormatCount = 0L
        staleEpochCount = 0L
        gapCount = 0L
        framesAtLastSummary = 0L
        lastSummaryNanos = clock.nowNanos()
    }

    /** Stops polling for [epoch]; repeated calls are no-ops. */
    override fun stop(epoch: InputEpoch) {
        var finalSummary: String? = null
        val stopped = synchronized(lock) {
            if (running != epoch) return
            running = null
            dueNanos = null
            finalSummary = frameSummary()
            handle.also { handle = null }
        }
        stopped?.cancel()
        // Once on stop, so a device run always ends with the totals of the epoch it just ended (P3.1 acceptance).
        finalSummary?.let { P3Log.line(it) }
    }

    /**
     * Reports what the receiver did with one offered datagram.
     *
     * The ingress itself cannot report this through [PcmIngress.poll]: a refused offer produces no chunk, so an
     * overflow would look exactly like a quiet moment. `OVERFLOW` also resets the framer because the bytes it had
     * buffered belonged to the continuity the ingress just abandoned; a frame built across that boundary would
     * splice two different streams. `STALE_EPOCH` is silent by contract - it is the ordinary consequence of audio
     * arriving after a session ended.
     */
    fun onOffer(offer: PcmOffer) {
        val fault = when (offer) {
            // Counted but silent: an accepted chunk arrives every cadence and a stale epoch is the ordinary
            // consequence of audio arriving after a session ended. Both still feed the `P3_FRAME` summary.
            PcmOffer.ACCEPTED -> {
                synchronized(lock) { acceptedCount++ }
                return
            }
            PcmOffer.STALE_EPOCH -> {
                synchronized(lock) { staleEpochCount++ }
                return
            }
            PcmOffer.OVERFLOW -> {
                synchronized(lock) {
                    overflowCount++
                    framer.reset()
                }
                "INGRESS_OVERFLOW"
            }
            PcmOffer.UNKNOWN_FORMAT -> {
                synchronized(lock) { unknownFormatCount++ }
                "UNKNOWN_FORMAT"
            }
        }
        onFault(fault)
    }

    /**
     * Reports a fault the loop itself did not observe - a forwarder for the owner of the ingress.
     *
     * `PcmIngress` can refuse or lose a datagram before this loop exists (the transport offers into the ingress
     * as soon as it is open, while the loop is built on the first `start`), so the owner needs to hand that
     * outcome in rather than drop it. Routing it through the same [onFault] keeps every fault on one channel.
     */
    fun reportFault(code: String) = onFault(code)

    /**
     * Publishes the next tick at [deadlineNanos] and remembers it.
     *
     * A tick that overran its own deadline has, by definition, already had that deadline pass; the scheduler
     * then runs the tick at once, which is the correct behaviour (poll now rather than skip a chunk). The
     * deadline is still never a "now" value: it is always a whole number of cadences after the previous one,
     * so the loop cannot walk forward in time.
     */
    private fun schedule(epoch: InputEpoch, deadlineNanos: Long): CancellationHandle {
        dueNanos = deadlineNanos
        return scheduler.at(deadlineNanos) { tick(epoch) }
    }

    private fun tick(epoch: InputEpoch) {
        val faults = mutableListOf<String>()
        val frames = ArrayList<PcmFrame>()
        var summary: String? = null
        synchronized(lock) {
            if (running != epoch) return
            // Gaps first: a discontinuity restarts framing, so the chunks drained below are framed from offset 0
            // rather than appended to bytes that belonged to the previous continuity.
            var gap = ingress.pollGap()
            while (gap != null) {
                framer.reset()
                faults += "GAP:${gap.reason.name}"
                gapCount++
                gap = ingress.pollGap()
            }
            // **Drain what is queued**, not one chunk per tick. The uplink offers ~10 ms chunks while this loop ticks
            // every 20 ms, so a single poll per tick consumes half of what arrives; the queue then fills and
            // `PcmIngress` answers a full queue with a **whole-queue** OVERFLOW reset, i.e. one to two seconds of
            // audio discarded at a time. The 2026-10-14 K40 run measured exactly that (`GAP:OVERFLOW` every ~2 s, most
            // ASR finals `chars=0`, no `P3_WAKE` in ten attempts) against `overflow=0` in the 2026-10-09 run where
            // the wake word fired. `PcmIngress.poll()` is a plain FIFO pop, so draining still delivers every chunk
            // once and in order; the bound keeps one tick from becoming unbounded work on the scheduler's thread.
            var drained = 0
            while (drained < MAX_CHUNKS_PER_TICK) {
                val chunk = ingress.poll() ?: break
                drained++
                frames += framer.accept(chunk)
            }
            frameCount += frames.size
            byteCount += frames.sumOf { it.samples.size.toLong() * 2 }
            summary = summaryIfDue()
            // Single-flight: exactly one pending tick exists at any moment, which is what bounds the loop.
            // Anchored on the previous deadline, so processing cost cannot accumulate into the poll period.
            handle = schedule(epoch, requireNotNull(dueNanos) + cadenceMillis * 1_000_000L)
        }
        faults.forEach(onFault)
        frames.forEach { raw ->
            val frame = if (muteWindow?.covers(raw.endNanos) == true) raw.silenced() else raw
            if (wakeEnabled) wake?.accept(frame)
            if (pipelineEnabled) pipeline.accept(frame)
        }
        // Outside the lock: the summary is an observer of the tick, never part of it.
        summary?.let { P3Log.line(it) }
    }

    companion object {
        /**
         * The most chunks one tick may drain.
         *
         * 64 chunks of a 10 ms uplink is 640 ms of audio: enough to clear the ingress's two-second buffer in a few
         * ticks, and small enough that a catch-up tick never becomes unbounded work on the scheduler's thread.
         */
        const val MAX_CHUNKS_PER_TICK = 64
    }

    /**
     * The `P3_FRAME` line when an interval has been reached, or null.
     *
     * The frame interval is measured from the last summary, and the time interval from the moment that summary was
     * emitted (or from `start`). Both advance together, so the next line covers exactly the span this one did not:
     * a quiet epoch still reports on the time interval, and a busy one on the frame interval - never per frame.
     * Must hold [lock].
     */
    private fun summaryIfDue(): String? {
        val now = clock.nowNanos()
        val framesDue = frameCount - framesAtLastSummary >= FRAME_SUMMARY_FRAMES
        val timeDue = now - lastSummaryNanos >= FRAME_SUMMARY_NANOS
        if (!framesDue && !timeDue) return null
        return frameSummary().also {
            framesAtLastSummary = frameCount
            lastSummaryNanos = now
        }
    }

    /** The summary payload: every counter, and nothing a device run could mistake for audio. Must hold [lock]. */
    private fun frameSummary(): String =
        "P3_FRAME frames=$frameCount bytes=$byteCount accepted=$acceptedCount overflow=$overflowCount " +
            "unknownFormat=$unknownFormatCount staleEpoch=$staleEpochCount gaps=$gapCount"
}
