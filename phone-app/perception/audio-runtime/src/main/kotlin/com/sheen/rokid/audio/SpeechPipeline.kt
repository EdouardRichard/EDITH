package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeakerWindow
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * How much audio before the VAD's speech trigger is replayed into the ASR.
 *
 * Must stay above the VAD's own start gate (`minSpeechDuration` 0.25 s plus window alignment); 600 ms is that gate
 * plus margin. See [SpeechPipeline]'s `remember` for the acceptance evidence.
 */
private const val PRE_ROLL_MILLIS = 600L

class SpeechPipeline(private val vad: VadEngine, private val asr: StreamingAsrEngine,
    private val speaker: SpeakerEngine, private val emit: (HostEvent) -> Unit,
    private val cancelTurn: (String) -> Unit, val timeline: TurnTimeline = TurnTimeline(),
    private val onFrameSignal: ((PcmFrame, Boolean) -> Unit)? = null,
    /**
     * Called whenever the pipeline drops its accumulated state - a discontinuity, a fault, or a close.
     *
     * A consumer that derives its own state from the frames ([onFrameSignal]) has to drop that state at the same
     * moment: keeping it would compare post-break audio against a pre-break verdict. Like the frame signal, it is
     * invoked from the deferred action batch, never while the worker lock is held.
     */
    private val onReset: (() -> Unit)? = null) {
    private data class Identity(val epoch: InputEpoch, val continuity: String, val rate: Int)
    private class Turn(val id: String, val first: PcmFrame) {
        val timeline = TurnRevision(id, first.startNanos)
        val pcm = ByteArrayOutputStream()
        var end = first
        var samples = 0L
        var startupSpeakerRequested = false
    }
    private class Pending(val turnId: String, val identity: Identity, val fence: Long, val window: SpeakerWindow,
        val timelineAdmission: TurnTimeline.SpeakerAdmission) {
        var handle: CancellationHandle? = null
    }
    private inner class SpeakerTask(val request: Pending) : Runnable {
        override fun run() = assess(request)
    }
    private sealed interface DeferredInput {
        data class Frame(val frame: PcmFrame) : DeferredInput
        data class Gap(val envelope: EventEnvelope, val reason: GapReason) : DeferredInput
    }

    // The worker owns VAD/ASR and turn state; callbacks need only the short admission lock.
    private val worker = Any()
    private val admission = Any()
    // The constructor sink is the production wake route. The holder only picks where later events go; with no
    // redirect installed it reads exactly the constructor value, so the wake path is unchanged (0.4 spec §5.3).
    private val emitSink = AtomicReference(emit)
    private var closed = false
    private var paused = false
    /** The session whose provider fault paused intake, so only a **different** session may re-arm it. */
    private var faultedSession: Pair<SessionId, Generation>? = null
    private var identity: Identity? = null
    private var fence = 0L
    private var resetting: Long? = null
    private val deferredInputs = ArrayDeque<DeferredInput>()
    private val pending = LinkedHashSet<Pending>()
    private val speakerExecutor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8), { task -> Thread(task, "edith-speaker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val preRoll = ArrayDeque<PcmFrame>()
    private var preRollSamples = 0L
    private var last: PcmFrame? = null
    private var turn: Turn? = null

    fun accept(frame: PcmFrame) {
        val actions = mutableListOf<() -> Unit>()
        synchronized(worker) {
            if (!available()) {
                // A fault is terminal for the session that produced it, but the engines are process-lifetime
                // (rebuilding them costs a full model load). The first frame of a **different** session is the
                // recovery boundary: re-arm instead of staying deaf until the App is force-stopped.
                if (!rearmForNextSessionLocked(frame, actions)) return
            }
            process(frame, actions)
        }
        actions.forEach { it() }
    }

    /**
     * Clears a provider-fault pause and resets the engines so a **new session** can use the same pipeline.
     *
     * A fault pauses intake for the session that produced it ([fault]); the host keeps one pipeline per process
     * because the ASR/VAD/speaker models are large, so without this seam a single transient provider failure
     * silenced ASR for every later session (device evidence 2026-10-27: dictation `timeout chars=0`, a KWS-hit wake
     * turn with no `P3_ASR`, restored only by force-stop). Recovery is deliberately session-scoped, not a retry
     * loop: the same session keeps rejecting audio (pinned by its own test), and only a different
     * session/generation may re-arm. Calling it on a healthy pipeline is a no-op.
     *
     * @return true when a paused pipeline was actually re-armed.
     */
    fun rearm(): Boolean {
        val actions = mutableListOf<() -> Unit>()
        val rearmed = synchronized(worker) { rearmLocked(actions) }
        actions.forEach { it() }
        return rearmed
    }

    /** Must hold [worker]: the paused-pipeline recovery; defers every callback into [actions]. */
    private fun rearmLocked(actions: MutableList<() -> Unit>): Boolean {
        val admitted = synchronized(admission) {
            if (closed || !paused) {
                false
            } else {
                paused = false
                faultedSession = null
                true
            }
        }
        if (!admitted) return false
        // Drop everything the fault left behind: the active turn, queued speaker work and deferred input. This is the
        // same revocation a discontinuity performs, so a stale assessment cannot surface under the new session.
        revoke(actions)
        synchronized(admission) { resetting = null }
        deferredInputs.clear()
        preRoll.clear(); preRollSamples = 0; last = null
        resetEngines(actions)
        return true
    }

    /** Must hold [worker]: true when [frame] belongs to a session other than the one whose fault paused intake. */
    private fun rearmForNextSessionLocked(frame: PcmFrame, actions: MutableList<() -> Unit>): Boolean {
        val faulted = synchronized(admission) { faultedSession } ?: return false
        if (faulted.first == frame.epoch.sessionId && faulted.second == frame.epoch.generation) return false
        return rearmLocked(actions)
    }

    /**
     * Redirects every event emitted from now on, or restores the constructor sink when [sink] is null.
     *
     * The dictation controller installs a UI sink while phone-mic dictation runs and restores the wake route when it
     * ends (0.4 spec §5.3, D204). The destination is read at emission time on whichever thread emits - the pipeline
     * worker for transcripts/faults/resets and the speaker executor for speaker assessments - so the sink itself must
     * be safe on those threads. Redirecting changes only the destination: turn/ASR state and event ordering stay
     * exactly as they are, and with no redirect installed events go to the constructor sink as before.
     */
    fun redirectEmit(sink: ((HostEvent) -> Unit)?) {
        emitSink.set(sink ?: emit)
    }

    private fun emitCurrent(event: HostEvent) = emitSink.get()(event)

    /**
     * Forces the active turn to its final transcript - the dictation stop / end-of-utterance seam (0.4 spec §5.3,
     * D204). A user pressing stop is not a VAD endpoint, so this runs the same finalisation the endpoint branch does:
     * `asr.finish()` as a final revision that keeps the turn's original start and the last accepted frame's end, the
     * speaker-window request, and the ASR/VAD resets. The final is emitted through the current [redirectEmit] sink,
     * in the same deferred action batch as every other transcript.
     *
     * Returns true when an active turn was finalised and cleared. An ASR that already sealed the turn still returns
     * true but produces no second final. Returns false when there is no active turn - after a VAD endpoint, a reset,
     * or [close] - and when the ASR/provider fails while finishing (the normal fault path then pauses the pipeline);
     * either way no event is emitted. Safe to call concurrently with [accept], endpoint intake and [close]: turn
     * ownership is the worker lock, so only one caller can finalise a given turn.
     */
    fun finishCurrentTurn(): Boolean {
        val actions = mutableListOf<() -> Unit>()
        val finished = synchronized(worker) {
            val active = turn
            if (active == null || !available()) return@synchronized false
            try {
                finalizeTurn(active, Identity(active.end.epoch, active.end.range.continuityId,
                    active.end.sampleRateHz), active.end, actions)
                true
            } catch (_: Exception) {
                fault(envelope(active.end), "SPEECH_PROVIDER_FAILURE", actions)
                false
            }
        }
        actions.forEach { it() }
        return finished
    }

    /**
     * Whether a speech turn is currently active (`accept` opened a turn and no endpoint/finish/reset closed it).
     *
     * The dictation host uses this before it installs its redirect: a wake request already in progress owns the turn
     * and its final must reach the LLM route, so a phone-mic dictation is refused rather than allowed to capture that
     * final (0.4 Task 12b-ii, spec §5.3: 唤醒后语音 ASR 直接给 LLM 的既有行为不变). Read-only - it never advances or
     * mutates the turn.
     */
    fun hasActiveTurn(): Boolean = synchronized(worker) { turn != null }

    private fun process(frame: PcmFrame, actions: MutableList<() -> Unit>, fromDeferred: Boolean = false) {
        try {
            require(frame.sampleRateHz in 8000..192000)
            require(frame.samples.isNotEmpty() && frame.samples.size.toLong() == frame.range.endExclusive - frame.range.first)
            require(frame.samples.size.toLong() * 1000 <= frame.sampleRateHz.toLong() * 40)
            require(frame.samples.all { it.isFinite() && it in -1f..1f })
            if (isResetting()) {
                defer(frame, actions)
                return
            }
            val next = Identity(frame.epoch, frame.range.continuityId, frame.sampleRateHz)
            val previous = synchronized(admission) { identity }
            if (previous != null && (previous != next || last?.range?.endExclusive != frame.range.first)) {
                reset(envelope(frame), if (previous.rate != next.rate) GapReason.FORMAT_CHANGED
                    else GapReason.SESSION_INTERRUPTED, actions)
                if (available()) defer(frame, actions, fromDeferred)
                return
            }
            synchronized(admission) { identity = next }
            val decision = vad.accept(frame)
            // Per-frame observation for the acoustic gate; deferred like every other callback so it never runs
            // while the worker lock is held.
            onFrameSignal?.let { signal -> actions += { signal(frame, decision.speech) } }
            if (turn == null && decision.speech) {
                turn = Turn(UUID.randomUUID().toString(), preRoll.firstOrNull() ?: frame)
                preRoll.forEach { feed(turn!!, it, actions) }
                preRoll.clear(); preRollSamples = 0
                feed(turn!!, frame, actions)
            } else if (turn != null) {
                val active = turn!!
                if (active.samples + frame.samples.size > frame.sampleRateHz.toLong() * 20) {
                    fault(envelope(frame), "SPEECH_WINDOW_LIMIT", actions)
                } else {
                    feed(active, frame, actions)
                }
            } else {
                remember(frame)
            }
            if (available() && turn != null) {
                val active = turn!!
                if (!active.startupSpeakerRequested &&
                    (speaker as? LocalSpeakerService)?.startupDurationSatisfied(active.samples, next.rate) != false) {
                    active.startupSpeakerRequested = true
                    requestSpeaker(active, next, actions)
                }
            }
            if (available() && decision.endpoint && turn != null) {
                finalizeTurn(turn!!, next, frame, actions)
            }
            if (available()) last = frame
        } catch (_: Exception) {
            fault(envelope(frame), "SPEECH_PROVIDER_FAILURE", actions)
        }
    }

    fun discontinuity(envelope: EventEnvelope, reason: GapReason) {
        val actions = mutableListOf<() -> Unit>()
        synchronized(worker) {
            if (available()) {
                if (isResetting()) enqueue(DeferredInput.Gap(envelope, reason), envelope, actions)
                else reset(envelope, reason, actions)
            }
        }
        actions.forEach { it() }
    }

    fun close() {
        val actions = mutableListOf<() -> Unit>()
        synchronized(worker) {
            synchronized(admission) {
                if (closed) return
                closed = true
            }
            revoke(actions)
            synchronized(admission) { resetting = null }
            deferredInputs.clear()
            preRoll.clear(); preRollSamples = 0; last = null
            actions += { speakerExecutor.shutdownNow() }
            actions += { runCatching { vad.close() }; runCatching { asr.close() }; runCatching { speaker.close() }; Unit }
        }
        actions.forEach { it() }
    }

    private fun available() = synchronized(admission) { !closed && !paused }
    private fun isResetting() = synchronized(admission) { resetting != null }

    private fun defer(frame: PcmFrame, actions: MutableList<() -> Unit>, first: Boolean = false) {
        enqueue(DeferredInput.Frame(frame.copy(samples = frame.samples.copyOf())), envelope(frame), actions, first)
    }

    private fun enqueue(input: DeferredInput, envelope: EventEnvelope, actions: MutableList<() -> Unit>, first: Boolean = false) {
        if (deferredInputs.size == 8) fault(envelope, "SPEECH_INPUT_BACKLOG", actions)
        else if (first) deferredInputs.addFirst(input) else deferredInputs.addLast(input)
    }

    private fun feed(active: Turn, frame: PcmFrame, actions: MutableList<() -> Unit>) {
        active.pcm.write(pcm16(frame.samples))
        active.samples += frame.samples.size
        active.end = frame
        asr.accept(frame).forEach { hypothesis ->
            active.timeline.revise(hypothesis, envelope(frame))?.let { revision ->
                registerTranscript(active, revision)
                actions += { emitCurrent(TranscriptEvent(revision)) }
            }
        }
    }

    private fun registerTranscript(active: Turn, revision: TranscriptRevision) {
        timeline.registerTranscriptProvenance(active.id, active.first.epoch, active.first.range.continuityId)
        timeline.applyTranscript(revision)
    }

    /**
     * The shared end-of-turn path: the VAD endpoint and the dictation stop ([finishCurrentTurn]) must produce the same
     * revision shape, final flag, clock (turn start to last accepted frame end) and follow-up work.
     */
    private fun finalizeTurn(active: Turn, turnIdentity: Identity, end: PcmFrame,
        actions: MutableList<() -> Unit>) {
        active.timeline.revise(asr.finish().copy(final = true), envelope(end))?.let {
            registerTranscript(active, it)
            actions += { emitCurrent(TranscriptEvent(it)) }
        }
        requestSpeaker(active, turnIdentity, actions)
        if (available()) {
            turn = null
            asr.reset()
            vad.reset()
        }
    }

    private fun remember(frame: PcmFrame) {
        preRoll += frame.copy(samples = frame.samples.copyOf())
        preRollSamples += frame.samples.size
        // 600 ms, not 200 ms (2026-10-22 K70 acceptance): Silero reports speech only after `minSpeechDuration` (0.25 s)
        // plus window alignment, i.e. ~250-286 ms after the real onset. A 200 ms pre-roll therefore started the ASR at
        // onset+50..86 ms and a fast first syllable reached the recognizer truncated; speaking slowly widened the
        // syllable enough to hide it. 600 ms carries the whole gate delay plus margin (the VAD's own segment start is
        // trigger-322 ms, which this now covers).
        val capacity = frame.sampleRateHz * PRE_ROLL_MILLIS / 1000L
        while (preRollSamples > capacity) {
            val first = preRoll.removeFirst()
            val discard = minOf(preRollSamples - capacity, first.samples.size.toLong()).toInt()
            preRollSamples -= discard
            if (discard < first.samples.size) preRoll.addFirst(first.copy(
                samples = first.samples.copyOfRange(discard, first.samples.size),
                range = first.range.copy(first = first.range.first + discard),
                startNanos = first.startNanos + discard.toLong() * 1_000_000_000 / first.sampleRateHz))
        }
    }

    private fun requestSpeaker(active: Turn, identity: Identity, actions: MutableList<() -> Unit>) {
        val windowEnvelope = envelope(active.end).copy(startNanos = active.first.startNanos)
        val window = SpeakerWindow(AudioSegment(windowEnvelope,
            AudioFormat.Pcm(identity.rate, 1, 16), active.pcm.toByteArray()),
            SampleRange(identity.continuity, active.first.range.first, active.end.range.endExclusive), false)
        timeline.registerSpeakerWindow(window, identity.epoch)
        (speaker as? LocalSpeakerService)?.registerWindow(window, identity.epoch)
        val timelineAdmission = timeline.speakerAdmission(identity.epoch, identity.continuity)
        val request = synchronized(admission) {
            if (pending.size >= 8) null else Pending(active.id, identity, fence, window, timelineAdmission).also { pending += it }
        }
        if (request == null) {
            fault(windowEnvelope, "SPEECH_SPEAKER_BACKLOG", actions)
            return
        }
        actions += {
            try { speakerExecutor.execute(SpeakerTask(request)) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                providerFault(request)
            }
        }
    }

    private fun assess(request: Pending) {
        if (!isCurrent(request)) return
        try {
            val handle = speaker.assess(request.window) { assessment ->
                val accepted = synchronized(admission) {
                    val envelope = request.window.segment.envelope
                    if (!isCurrentLocked(request) || assessment.envelope.sessionId != envelope.sessionId ||
                        assessment.envelope.generation != envelope.generation ||
                        assessment.startNanos < envelope.startNanos || assessment.endNanos > envelope.endNanos) false
                    else { pending.remove(request); true }
                }
                if (accepted) {
                    timeline.applySpeaker(assessment, request.timelineAdmission)
                    emitCurrent(SpeakerEvent(assessment.copy(envelope = EventEnvelope(
                    request.identity.epoch.sessionId, request.identity.epoch.generation,
                    UUID.randomUUID().toString(), assessment.startNanos, assessment.endNanos))))
                }
            }
            val cancel = synchronized(admission) {
                if (isCurrentLocked(request)) { request.handle = handle; false } else true
            }
            if (cancel) runCatching { handle.cancel() }
        } catch (_: Exception) {
            providerFault(request)
        }
    }

    private fun isCurrent(request: Pending) = synchronized(admission) { isCurrentLocked(request) }
    private fun isCurrentLocked(request: Pending) = !closed && !paused && request.fence == fence &&
        request.identity == identity && request in pending

    private fun providerFault(request: Pending) {
        val actions = mutableListOf<() -> Unit>()
        synchronized(worker) {
            fault(request.window.segment.envelope, "SPEECH_PROVIDER_FAILURE", actions, request)
        }
        actions.forEach { it() }
    }

    private fun fault(envelope: EventEnvelope, code: String, actions: MutableList<() -> Unit>, request: Pending? = null) {
        val admitted = synchronized(admission) {
            if (closed || paused || (request != null && !isCurrentLocked(request))) false
            else {
                paused = true
                faultedSession = envelope.sessionId to envelope.generation
                true
            }
        }
        if (!admitted) return
        revoke(actions)
        synchronized(admission) { resetting = null }
        deferredInputs.clear()
        preRoll.clear(); preRollSamples = 0; last = null
        resetEngines(actions)
        actions += { emitCurrent(ServiceFaultEvent(envelope, code)) }
        actions += { emitCurrent(SessionStateEvent(envelope, SessionState.Paused)) }
    }

    private fun reset(envelope: EventEnvelope, reason: GapReason, actions: MutableList<() -> Unit>) {
        revoke(actions)
        val token = synchronized(admission) { resetting = fence; fence }
        preRoll.clear(); preRollSamples = 0; last = null
        val vadReset = runCatching { vad.reset() }.isSuccess
        val asrReset = runCatching { asr.reset() }.isSuccess
        // Keep intake fenced through lifecycle, external cancellation, and the gap event.
        actions.add(0) { resetSpeaker(token, envelope) }
        actions += { emitCurrent(DiscontinuityEvent(envelope, reason)) }
        actions += { completeReset(token) }
        if (!vadReset || !asrReset) fault(envelope, "SPEECH_PROVIDER_FAILURE", actions)
    }

    private fun currentReset(token: Long) = synchronized(admission) {
        !closed && !paused && resetting == token && fence == token
    }

    private fun resetSpeaker(token: Long, envelope: EventEnvelope) {
        if (!currentReset(token)) return
        val successful = runCatching { speaker.reset() }.isSuccess
        if (!successful) {
            val actions = mutableListOf<() -> Unit>()
            synchronized(worker) {
                if (currentReset(token)) fault(envelope, "SPEECH_PROVIDER_FAILURE", actions)
            }
            actions.forEach { it() }
        }
    }

    private fun completeReset(token: Long) {
        val actions = mutableListOf<() -> Unit>()
        synchronized(worker) {
            if (!currentReset(token)) return
            synchronized(admission) { resetting = null }
            while (deferredInputs.isNotEmpty() && available() && !isResetting()) {
                when (val input = deferredInputs.removeFirst()) {
                    is DeferredInput.Frame -> process(input.frame, actions, fromDeferred = true)
                    is DeferredInput.Gap -> reset(input.envelope, input.reason, actions)
                }
            }
        }
        actions.forEach { it() }
    }

    private fun revoke(actions: MutableList<() -> Unit>) {
        val requests = synchronized(admission) {
            fence = Math.addExact(fence, 1)
            identity = null
            pending.toList().also { pending.clear() }
        }
        timeline.clear()
        onReset?.let { hook -> actions += { hook() } }
        val turnIds = (requests.map { it.turnId } + listOfNotNull(turn?.id)).toSet()
        turn = null
        turnIds.forEach { id -> actions += { runCatching { cancelTurn(id) }; Unit } }
        requests.mapNotNull { it.handle }.forEach { handle -> actions += { runCatching { handle.cancel() }; Unit } }
        actions += { speakerExecutor.queue.removeIf { it is SpeakerTask && it.request in requests }; Unit }
    }

    private fun resetEngines(actions: MutableList<() -> Unit>) {
        runCatching { vad.reset() }
        runCatching { asr.reset() }
        actions += { runCatching { speaker.reset() }; Unit }
    }

    private fun envelope(frame: PcmFrame) = EventEnvelope(frame.epoch.sessionId, frame.epoch.generation,
        UUID.randomUUID().toString(), frame.startNanos, frame.endNanos)

    private fun pcm16(samples: FloatArray) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            val value = (sample * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}
