package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class OutputArbiter private constructor(
    private val nowNanos: () -> Long,
    private val scheduler: DeadlineScheduler?,
    private val device: HintDevice?,
    private val speech: SpeechPlayer?,
    private val current: (() -> Pair<SessionId, Generation>?)?,
    private val onState: (Assistance, OutputStatus) -> Unit,
    /**
     * 0.24 S3 (spec §5.5): the only speech event a plugin acts on - a played hint's playback ended (`FINISHED` or
     * `FAILED`). `STARTED` is internal to the gate and is not reported here.
     *
     * The callback runs on the arbiter's serial executor for a hint that is **still on screen**; a hint that was
     * replaced, cleared or expired before its playback ended is not reported. That distinction is what keeps a
     * cancelled answer's playback end from opening the next conversation round (Task 14).
     */
    private val onSpeech: (Assistance, SpeechResult) -> Unit,
) {
    constructor(nowNanos: () -> Long) : this(nowNanos, null, null, null, null, { _, _ -> }, { _, _ -> })
    constructor(clock: MonotonicClock, scheduler: DeadlineScheduler, device: HintDevice,
        current: () -> Pair<SessionId, Generation>?, onState: (Assistance, OutputStatus) -> Unit) :
        this(clock::nowNanos, scheduler, device, null, current, onState, { _, _ -> })
    constructor(clock: MonotonicClock, scheduler: DeadlineScheduler, device: HintDevice,
        speech: SpeechPlayer?, current: () -> Pair<SessionId, Generation>?, onState: (Assistance, OutputStatus) -> Unit) :
        this(clock::nowNanos, scheduler, device, speech, current, onState, { _, _ -> })
    constructor(clock: MonotonicClock, scheduler: DeadlineScheduler, device: HintDevice,
        speech: SpeechPlayer?, current: () -> Pair<SessionId, Generation>?,
        onState: (Assistance, OutputStatus) -> Unit,
        onSpeech: (Assistance, SpeechResult) -> Unit) :
        this(clock::nowNanos, scheduler, device, speech, current, onState, onSpeech)

    private var ownerThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "edith-output").also { it.isDaemon = true; ownerThread = it }
    }
    private data class Item(val hint: Assistance, val revision: Long, val sequence: Long,
        var output: CancellationHandle? = null, var deadline: CancellationHandle? = null,
        var speechHandle: CancellationHandle? = null, var speechSettled: Boolean = false,
        /**
         * 0.25 Task 20 / R114: the display budget expired while this item's utterance was still in flight, so the
         * hint is being held on screen until the speech terminal state (or the bounded grace) arrives.
         */
        var expiryHeldForSpeech: Boolean = false)
    private val policy = HintPolicy()
    private val queue = mutableListOf<Item>()
    private var displayed: Item? = null
    private var serial = 0L
    private val revisions = mutableMapOf<String, Long>()

    private fun <T> command(block: () -> T): T {
        if (Thread.currentThread() === ownerThread) return block()
        return try { executor.submit<T> { block() }.get() } catch (e: ExecutionException) { throw (e.cause ?: e) }
    }
    private fun status(item: Item, state: OutputStatus) { onState(item.hint, state) }
    private fun valid(hint: Assistance) = current?.invoke() == (hint.sessionId to hint.generation)

    fun submit(assistance: Assistance): OutputStatus = command {
        if (assistance.expiresAtNanos <= nowNanos()) return@command OutputStatus.Expired
        if (device == null) return@command OutputStatus.Accepted
        if (!valid(assistance) || !policy.accepts(assistance)) return@command OutputStatus.Rejected
        if (revisions.size >= 4096 && assistance.hintId !in revisions) return@command OutputStatus.Rejected
        val revision = Math.incrementExact(revisions[assistance.hintId] ?: 0)
        revisions[assistance.hintId] = revision
        val item = Item(assistance, revision, serial++)
        queue.filter { it.hint.hintId == assistance.hintId }.toList().forEach { remove(it, OutputStatus.Rejected) }
        val previous = displayed
        if (previous == null || previous.hint.hintId == assistance.hintId || assistance.priority > previous.hint.priority) {
            previous?.let { remove(it, OutputStatus.Rejected) }
            show(item)
            OutputStatus.Accepted
        } else {
            if (queue.size >= 8) {
                val lowest = queue.minWith(compareBy<Item> { it.hint.priority }.thenByDescending { it.sequence })
                if (assistance.priority <= lowest.hint.priority) return@command OutputStatus.Rejected
                remove(lowest, OutputStatus.Rejected)
            }
            queue += item
            item.deadline = scheduler!!.at(assistance.expiresAtNanos) { command { expire(item) } }
            status(item, OutputStatus.Queued)
            OutputStatus.Queued
        }
    }

    private fun show(item: Item) {
        if (item.hint.expiresAtNanos <= nowNanos()) { status(item, OutputStatus.Expired); next(); return }
        if (!valid(item.hint)) { status(item, OutputStatus.Rejected); next(); return }
        displayed = item
        status(item, OutputStatus.Accepted)
        item.deadline = scheduler!!.at(item.hint.expiresAtNanos) { command { expire(item) } }
        val handle = try {
            device!!.show(item.hint, item.revision) { state -> executor.execute {
                if (displayed !== item || !valid(item.hint)) return@execute
                if (item.hint.expiresAtNanos <= nowNanos()) { expire(item); return@execute }
                when (state) {
                    OutputStatus.Displayed, OutputStatus.Queued -> status(item, state)
                    OutputStatus.Expired, OutputStatus.Rejected -> { remove(item, state); next() }
                    OutputStatus.Accepted -> Unit
                }
            } }
        } catch (_: Exception) { remove(item, OutputStatus.Rejected); next(); return }
        if (displayed === item) item.output = handle else runCatching { handle.cancel() }
        if (displayed === item && item.hint.optionalSpeech != null && speech != null) {
            item.speechHandle = speech.play(item.hint) { state -> onSpeechState(item, state) }
        }
    }

    /**
     * 0.24 S3 (spec §5.5): a playback state reported by [SpeechPlayer].
     *
     * `STARTED` only drives the host's own playback gate and is not forwarded. A terminal state is forwarded **once**
     * and only while the hint is still the displayed one: a replaced/cleared item's cancellation must not be read as
     * "the answer's speech finished" (Task 14's stale-hint rule). The callback can arrive on any thread, so it is
     * marshalled onto the arbiter's serial executor before it touches the item or the host.
     *
     * 0.25 Task 20 / R116 (the user's long-TTS defect): a hint whose display budget already expired while its
     * utterance was still playing is deliberately **still displayed** (see [expire]), so its terminal state must be
     * forwarded rather than dropped as "already expired". The screen is cleared only after the event is reported -
     * the cycle needs `SpeechFinished` before the hint/next display is gone.
     */
    private fun onSpeechState(item: Item, state: SpeechPlayback) {
        val result = when (state) {
            SpeechPlayback.STARTED -> return
            SpeechPlayback.FINISHED -> SpeechResult.FINISHED
            SpeechPlayback.FAILED -> SpeechResult.FAILED
        }
        executor.execute {
            if (item.speechSettled) return@execute
            item.speechSettled = true
            if (displayed !== item) return@execute
            onSpeech(item.hint, result)
            if (item.expiryHeldForSpeech) {
                // R116 order: forward the terminal FIRST, then finish the hint the display budget already gave up on.
                remove(item, OutputStatus.Expired)
                next()
            }
        }
    }

    /**
     * 0.25 Task 20 / R114 (the user's "TTS not finished before the turn ends" defect): a **natural** display expiry
     * must not cut an in-flight utterance.
     *
     * The old implementation sent every expiry through [remove], and [remove] cancelled the speech handle first
     * ("先停播再撤屏"). A long answer's TTS therefore stopped at its display budget without ever reporting
     * `SpeechFinished`, and the cycle's longer wait could not recover an utterance the host itself had cancelled.
     *
     * Expiry now only defers: it neither cancels the playback nor clears the screen nor reports `Expired`, and it
     * re-arms the arbiter's own [scheduler] at [SPEECH_EXPIRY_GRACE_MILLIS] after the original deadline. The hint is
     * finished by its terminal state ([onSpeechState]) or, when the player never reports one, by the grace task
     * ([expireAtGrace]) - so the screen is never unbounded, and no caller thread is blocked.
     *
     * Interrupts are different and keep stop-before-clear: [remove] from a replacement, turn/hint cancellation or
     * [clear] still stops the playback first. A hint without an in-flight utterance (no speech request, no player, or
     * playback already finished) still leaves at its display deadline exactly as before.
     */
    private fun expire(item: Item) {
        if (displayed === item && item.speechHandle != null && !item.speechSettled) {
            holdExpiry(item)
            return
        }
        if (displayed === item || item in queue) { remove(item, OutputStatus.Expired); next() }
    }

    private fun holdExpiry(item: Item) {
        // A second deadline report (a late display callback also calls expire) must not move the bound later: the
        // grace is measured from the ORIGINAL display deadline only.
        if (item.expiryHeldForSpeech) return
        item.expiryHeldForSpeech = true
        val graceAtNanos = saturatingAdd(item.hint.expiresAtNanos, SPEECH_EXPIRY_GRACE_NANOS)
        if (graceAtNanos <= nowNanos()) { remove(item, OutputStatus.Expired); next(); return }
        item.deadline?.cancel()
        item.deadline = try {
            scheduler!!.at(graceAtNanos) { command { expireAtGrace(item) } }
        } catch (_: Exception) {
            // Without a scheduler to re-arm there would be no bound at all: fall back to the old stop+clear.
            remove(item, OutputStatus.Expired)
            next()
            return
        }
    }

    private fun expireAtGrace(item: Item) {
        if (displayed !== item && item !in queue) return
        remove(item, OutputStatus.Expired)
        next()
    }

    private fun saturatingAdd(base: Long, delta: Long): Long =
        if (base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta

    private fun remove(item: Item, state: OutputStatus) {
        val wasDisplayed = displayed === item
        if (wasDisplayed) displayed = null else queue.remove(item)
        item.deadline?.cancel()
        // Stop speech BEFORE clearing the screen (spec §S3: "先停播再撤屏"). This is the **interrupt** path; a
        // naturally finished utterance (R114/R116) must not be cancelled after its terminal state, so only playback
        // that is still in flight is stopped here.
        if (!item.speechSettled) item.speechHandle?.let { runCatching { it.cancel() } }
        item.output?.let { runCatching { it.cancel() } }
        if (wasDisplayed) runCatching { device!!.clear(item.hint.sessionId, item.hint.generation) }
        status(item, state)
    }

    private fun next() {
        if (displayed != null) return
        while (queue.isNotEmpty()) {
            val item = queue.maxWith(compareBy<Item> { it.hint.priority }.thenByDescending { it.sequence })
            queue.remove(item)
            item.deadline?.cancel()
            if (item.hint.expiresAtNanos <= nowNanos()) { status(item, OutputStatus.Expired); continue }
            if (!valid(item.hint)) { status(item, OutputStatus.Rejected); continue }
            show(item)
            return
        }
    }

    fun clear(sessionId: SessionId, generation: Generation) = command {
        queue.filter { it.hint.isCurrent(sessionId, generation) }.toList().forEach { remove(it, OutputStatus.Rejected) }
        val active = displayed?.takeIf { it.hint.isCurrent(sessionId, generation) }
        if (active != null) remove(active, OutputStatus.Rejected) else device?.clear(sessionId, generation)
        revisions.clear()
    }

    fun cancelTurn(turnId: String) = command {
        queue.filter { it.hint.sourceTurnId == turnId }.toList().forEach { remove(it, OutputStatus.Rejected) }
        displayed?.takeIf { it.hint.sourceTurnId == turnId }?.let { remove(it, OutputStatus.Rejected) }
        next()
    }

    fun cancelHint(turnId: String, hintId: String) = command {
        queue.filter { it.hint.sourceTurnId == turnId && it.hint.hintId == hintId }
            .toList().forEach { remove(it, OutputStatus.Rejected) }
        displayed?.takeIf { it.hint.sourceTurnId == turnId && it.hint.hintId == hintId }
            ?.let { remove(it, OutputStatus.Rejected) }
        next()
    }

    companion object {
        /**
         * 0.25 Task 20 / R114: how long a hint whose display budget expired while its utterance was still playing may
         * stay on screen waiting for `SpeechFinished`.
         *
         * Measured from the **original** display deadline, not from when the arbiter noticed it, so repeated display
         * callbacks cannot extend the hold. It matches the cycle's own speech liveness bound
         * (`OfficialConversation.SPEECH_LIVENESS_MILLIS`, 120 s), so the two cannot disagree about when a silent
         * player is lost. When the bound is reached the old stop-then-clear path runs: the screen is never unbounded.
         */
        const val SPEECH_EXPIRY_GRACE_MILLIS = 120_000L
        private const val SPEECH_EXPIRY_GRACE_NANOS = SPEECH_EXPIRY_GRACE_MILLIS * 1_000_000L
    }
}
