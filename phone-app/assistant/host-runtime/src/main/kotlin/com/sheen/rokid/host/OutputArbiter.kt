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
        var speechHandle: CancellationHandle? = null, var speechSettled: Boolean = false)
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
     * and only while the hint is still the displayed one: a replaced/cleared/expired item's cancellation must not be
     * read as "the answer's speech finished" (Task 14's stale-hint rule). The callback can arrive on any thread, so it
     * is marshalled onto the arbiter's serial executor before it touches the item or the host.
     *
     * 0.24 S3 Task 14 fix (C1 review minor): the terminal callback also re-checks the hint's deadline, exactly like the
     * display callback. A player can report its end after `expiresAtNanos` but before the queued `expire` task runs;
     * forwarding that would name a hint that is already gone.
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
            if (item.hint.expiresAtNanos <= nowNanos()) { expire(item); return@execute }
            onSpeech(item.hint, result)
        }
    }

    private fun expire(item: Item) {
        if (displayed === item || item in queue) { remove(item, OutputStatus.Expired); next() }
    }

    private fun remove(item: Item, state: OutputStatus) {
        val wasDisplayed = displayed === item
        if (wasDisplayed) displayed = null else queue.remove(item)
        item.deadline?.cancel()
        // Stop speech BEFORE clearing the screen (spec §S3: "先停播再撤屏").
        item.speechHandle?.let { runCatching { it.cancel() } }
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
}
